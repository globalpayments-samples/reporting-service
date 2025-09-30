// Package main implements HTTP handler functions for Global Payments Reporting API.
// This file provides RESTful API endpoints for accessing Global Payments
// reporting functionality including transaction search, details, settlement
// reports, and data export capabilities.
package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gorilla/mux"
)

// APIResponse represents a standardized API response
type APIResponse struct {
	Success   bool        `json:"success"`
	Data      interface{} `json:"data,omitempty"`
	Error     *APIError   `json:"error,omitempty"`
	Timestamp string      `json:"timestamp"`
}

// APIError represents error details in the response
type APIError struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	Timestamp string `json:"timestamp"`
}

// APIEndpointInfo represents documentation for a single endpoint
type APIEndpointInfo struct {
	URL         string                 `json:"url"`
	Method      string                 `json:"method"`
	Description string                 `json:"description"`
	Parameters  map[string]string      `json:"parameters"`
}

var reportingService *ReportingService

// InitializeReportingAPI initializes the reporting service and sets up routes
func InitializeReportingAPI(router *mux.Router) error {
	var err error
	reportingService, err = NewReportingService()
	if err != nil {
		return fmt.Errorf("failed to initialize reporting service: %w", err)
	}

	// Set up routes
	router.HandleFunc("/reports", handleReports).Methods("GET", "POST", "OPTIONS")
	router.HandleFunc("/reports/search", handleSearch).Methods("GET", "POST", "OPTIONS")
	router.HandleFunc("/reports/detail", handleDetail).Methods("GET", "OPTIONS")
	router.HandleFunc("/reports/settlement", handleSettlement).Methods("GET", "POST", "OPTIONS")
	router.HandleFunc("/reports/export", handleExport).Methods("GET", "POST", "OPTIONS")
	router.HandleFunc("/reports/summary", handleSummary).Methods("GET", "POST", "OPTIONS")
	router.HandleFunc("/reports/disputes", handleDisputes).Methods("GET", "POST", "OPTIONS")
	router.HandleFunc("/reports/dispute/{id}", handleDisputeDetail).Methods("GET", "OPTIONS")
	router.HandleFunc("/reports/deposits", handleDeposits).Methods("GET", "POST", "OPTIONS")
	router.HandleFunc("/reports/deposit/{id}", handleDepositDetail).Methods("GET", "OPTIONS")
	router.HandleFunc("/reports/batches", handleBatches).Methods("GET", "POST", "OPTIONS")
	router.HandleFunc("/reports/declines", handleDeclines).Methods("GET", "POST", "OPTIONS")
	router.HandleFunc("/reports/date-range", handleDateRange).Methods("GET", "POST", "OPTIONS")
	router.HandleFunc("/reports/config", handleReportsConfig).Methods("GET", "OPTIONS")

	return nil
}

// setJSONHeaders sets standard JSON response headers with CORS support
func setJSONHeaders(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
}

// handlePreflight handles OPTIONS preflight requests
func handlePreflight(w http.ResponseWriter, r *http.Request) bool {
	if r.Method == "OPTIONS" {
		setJSONHeaders(w)
		w.WriteHeader(http.StatusOK)
		return true
	}
	return false
}

// sendJSONResponse sends a JSON response with the specified status code
func sendJSONResponse(w http.ResponseWriter, data interface{}, statusCode int) {
	setJSONHeaders(w)
	w.WriteHeader(statusCode)
	json.NewEncoder(w).Encode(data)
}

// handleError sends an error response
func handleError(w http.ResponseWriter, message string, statusCode int, errorCode string) {
	response := APIResponse{
		Success: false,
		Error: &APIError{
			Code:      errorCode,
			Message:   message,
			Timestamp: time.Now().Format("2006-01-02 15:04:05"),
		},
		Timestamp: time.Now().Format("2006-01-02 15:04:05"),
	}
	sendJSONResponse(w, response, statusCode)
}

// getRequestParams extracts parameters from both GET and POST requests
func getRequestParams(r *http.Request) (map[string]interface{}, error) {
	params := make(map[string]interface{})

	// Get query parameters
	for key, values := range r.URL.Query() {
		if len(values) > 0 {
			params[key] = values[0]
		}
	}

	// Get POST body parameters if applicable
	if r.Method == "POST" {
		contentType := r.Header.Get("Content-Type")
		if strings.Contains(contentType, "application/json") {
			var jsonParams map[string]interface{}
			if err := json.NewDecoder(r.Body).Decode(&jsonParams); err == nil {
				for key, value := range jsonParams {
					params[key] = value
				}
			}
		} else if err := r.ParseForm(); err == nil {
			for key, values := range r.Form {
				if len(values) > 0 {
					params[key] = values[0]
				}
			}
		}
	}

	return params, nil
}

// validateRequiredParams validates that required parameters are present
func validateRequiredParams(params map[string]interface{}, required []string) error {
	for _, param := range required {
		if val, ok := params[param]; !ok || val == "" {
			return fmt.Errorf("missing required parameter: %s", param)
		}
	}
	return nil
}

// validateDateFormat validates date format
func validateDateFormat(dateStr string) error {
	if dateStr == "" {
		return nil
	}
	_, err := time.Parse("2006-01-02", dateStr)
	if err != nil {
		return fmt.Errorf("invalid date format. Use YYYY-MM-DD")
	}
	return nil
}

// removeEmptyParams removes empty string values from params
func removeEmptyParams(params map[string]interface{}) map[string]interface{} {
	cleaned := make(map[string]interface{})
	for key, value := range params {
		if str, ok := value.(string); ok && str != "" {
			cleaned[key] = value
		} else if !ok {
			cleaned[key] = value
		}
	}
	return cleaned
}

// handleReports handles the main /reports endpoint (API documentation)
func handleReports(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}

	// Check if action parameter is provided for backward compatibility
	params, _ := getRequestParams(r)
	if action, ok := params["action"].(string); ok && action != "" {
		// Route based on action parameter (PHP-style compatibility)
		handleActionBasedRouting(w, r, action, params)
		return
	}

	// Default: show API documentation
	endpoints := map[string]APIEndpointInfo{
		"search": {
			URL:         "/reports/search",
			Method:      "GET/POST",
			Description: "Search transactions with filters and pagination",
			Parameters: map[string]string{
				"page":           "Page number (default: 1)",
				"page_size":      "Results per page (default: 10, max: 100)",
				"start_date":     "Start date (YYYY-MM-DD)",
				"end_date":       "End date (YYYY-MM-DD)",
				"transaction_id": "Specific transaction ID",
				"payment_type":   "Payment type (sale, refund, authorize, capture)",
				"status":         "Transaction status",
				"amount_min":     "Minimum amount",
				"amount_max":     "Maximum amount",
				"card_last_four": "Last 4 digits of card",
			},
		},
		"detail": {
			URL:         "/reports/detail?transaction_id={id}",
			Method:      "GET",
			Description: "Get detailed transaction information",
			Parameters: map[string]string{
				"transaction_id": "Transaction ID (required)",
			},
		},
		"settlement": {
			URL:         "/reports/settlement",
			Method:      "GET/POST",
			Description: "Get settlement report",
			Parameters: map[string]string{
				"page":       "Page number (default: 1)",
				"page_size":  "Results per page (default: 50, max: 100)",
				"start_date": "Start date (YYYY-MM-DD)",
				"end_date":   "End date (YYYY-MM-DD)",
			},
		},
		"export": {
			URL:         "/reports/export?format={json|csv|xml}",
			Method:      "GET/POST",
			Description: "Export transaction data",
			Parameters: map[string]string{
				"format":     "Export format (json, csv, or xml)",
				"...filters": "Same filters as search endpoint",
			},
		},
		"summary": {
			URL:         "/reports/summary",
			Method:      "GET/POST",
			Description: "Get summary statistics",
			Parameters: map[string]string{
				"start_date": "Start date (YYYY-MM-DD)",
				"end_date":   "End date (YYYY-MM-DD)",
			},
		},
		"disputes": {
			URL:         "/reports/disputes",
			Method:      "GET/POST",
			Description: "Get dispute report",
			Parameters: map[string]string{
				"page":       "Page number (default: 1)",
				"page_size":  "Results per page (default: 10, max: 100)",
				"start_date": "Start date (YYYY-MM-DD)",
				"end_date":   "End date (YYYY-MM-DD)",
				"stage":      "Dispute stage",
				"status":     "Dispute status",
			},
		},
		"deposits": {
			URL:         "/reports/deposits",
			Method:      "GET/POST",
			Description: "Get deposit report",
			Parameters: map[string]string{
				"page":       "Page number (default: 1)",
				"page_size":  "Results per page (default: 10, max: 100)",
				"start_date": "Start date (YYYY-MM-DD)",
				"end_date":   "End date (YYYY-MM-DD)",
				"deposit_id": "Specific deposit ID",
				"status":     "Deposit status",
			},
		},
		"batches": {
			URL:         "/reports/batches",
			Method:      "GET/POST",
			Description: "Get batch report",
			Parameters: map[string]string{
				"start_date": "Start date (YYYY-MM-DD)",
				"end_date":   "End date (YYYY-MM-DD)",
			},
		},
		"declines": {
			URL:         "/reports/declines",
			Method:      "GET/POST",
			Description: "Get declined transactions report",
			Parameters: map[string]string{
				"page":           "Page number (default: 1)",
				"page_size":      "Results per page (default: 10, max: 100)",
				"start_date":     "Start date (YYYY-MM-DD)",
				"end_date":       "End date (YYYY-MM-DD)",
				"payment_type":   "Payment type",
				"amount_min":     "Minimum amount",
				"amount_max":     "Maximum amount",
				"card_last_four": "Last 4 digits of card",
			},
		},
		"date_range": {
			URL:         "/reports/date-range",
			Method:      "GET/POST",
			Description: "Get comprehensive date range report",
			Parameters: map[string]string{
				"start_date":        "Start date (YYYY-MM-DD)",
				"end_date":          "End date (YYYY-MM-DD)",
				"transaction_limit": "Max transactions to retrieve (default: 100, max: 1000)",
				"settlement_limit":  "Max settlements to retrieve (default: 50, max: 500)",
				"dispute_limit":     "Max disputes to retrieve (default: 25, max: 100)",
				"deposit_limit":     "Max deposits to retrieve (default: 25, max: 100)",
			},
		},
		"config": {
			URL:         "/reports/config",
			Method:      "GET",
			Description: "Get API configuration and status",
			Parameters:  map[string]string{},
		},
	}

	response := APIResponse{
		Success: true,
		Data: map[string]interface{}{
			"name":        "Global Payments Reporting API",
			"version":     "1.0.0",
			"description": "RESTful API for Global Payments transaction reporting and analytics",
			"endpoints":   endpoints,
		},
		Timestamp: time.Now().Format("2006-01-02 15:04:05"),
	}

	sendJSONResponse(w, response, http.StatusOK)
}

// handleActionBasedRouting routes requests based on action parameter (PHP compatibility)
func handleActionBasedRouting(w http.ResponseWriter, r *http.Request, action string, params map[string]interface{}) {
	switch action {
	case "search":
		handleSearchWithParams(w, r, params)
	case "detail":
		handleDetailWithParams(w, r, params)
	case "settlement":
		handleSettlementWithParams(w, r, params)
	case "export":
		handleExportWithParams(w, r, params)
	case "summary":
		handleSummaryWithParams(w, r, params)
	case "disputes":
		handleDisputesWithParams(w, r, params)
	case "dispute_detail":
		handleDisputeDetailWithParams(w, r, params)
	case "deposits":
		handleDepositsWithParams(w, r, params)
	case "deposit_detail":
		handleDepositDetailWithParams(w, r, params)
	case "batches":
		handleBatchesWithParams(w, r, params)
	case "declines":
		handleDeclinesWithParams(w, r, params)
	case "date_range":
		handleDateRangeWithParams(w, r, params)
	case "config":
		handleReportsConfig(w, r)
	default:
		handleError(w, fmt.Sprintf("Invalid action: %s", action), http.StatusBadRequest, "INVALID_ACTION")
	}
}

// handleSearch handles transaction search requests
func handleSearch(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}

	params, err := getRequestParams(r)
	if err != nil {
		handleError(w, "Failed to parse request parameters", http.StatusBadRequest, "PARSE_ERROR")
		return
	}

	handleSearchWithParams(w, r, params)
}

func handleSearchWithParams(w http.ResponseWriter, r *http.Request, params map[string]interface{}) {
	// Build filters
	filters := make(map[string]interface{})

	// Pagination
	if page, ok := params["page"].(string); ok {
		if p, err := strconv.Atoi(page); err == nil {
			filters["page"] = p
		}
	}
	if pageSize, ok := params["page_size"].(string); ok {
		if ps, err := strconv.Atoi(pageSize); err == nil && ps <= 100 {
			filters["page_size"] = ps
		}
	}

	// Date filters
	if startDate, ok := params["start_date"].(string); ok {
		if err := validateDateFormat(startDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		filters["start_date"] = startDate
	}

	if endDate, ok := params["end_date"].(string); ok {
		if err := validateDateFormat(endDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		filters["end_date"] = endDate
	}

	// Other filters
	filterKeys := []string{"transaction_id", "payment_type", "status", "amount_min", "amount_max", "card_last_four"}
	for _, key := range filterKeys {
		if val, ok := params[key]; ok {
			filters[key] = val
		}
	}

	filters = removeEmptyParams(filters)

	result, err := reportingService.SearchTransactions(filters)
	if err != nil {
		handleError(w, fmt.Sprintf("Transaction search failed: %v", err), http.StatusInternalServerError, "API_ERROR")
		return
	}

	sendJSONResponse(w, result, http.StatusOK)
}

// handleDetail handles transaction detail requests
func handleDetail(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}

	params, err := getRequestParams(r)
	if err != nil {
		handleError(w, "Failed to parse request parameters", http.StatusBadRequest, "PARSE_ERROR")
		return
	}

	handleDetailWithParams(w, r, params)
}

func handleDetailWithParams(w http.ResponseWriter, r *http.Request, params map[string]interface{}) {
	if err := validateRequiredParams(params, []string{"transaction_id"}); err != nil {
		handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
		return
	}

	transactionID := params["transaction_id"].(string)
	result, err := reportingService.GetTransactionDetails(transactionID)
	if err != nil {
		handleError(w, fmt.Sprintf("Failed to retrieve transaction details: %v", err), http.StatusInternalServerError, "API_ERROR")
		return
	}

	sendJSONResponse(w, result, http.StatusOK)
}

// handleSettlement handles settlement report requests
func handleSettlement(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}

	params, err := getRequestParams(r)
	if err != nil {
		handleError(w, "Failed to parse request parameters", http.StatusBadRequest, "PARSE_ERROR")
		return
	}

	handleSettlementWithParams(w, r, params)
}

func handleSettlementWithParams(w http.ResponseWriter, r *http.Request, params map[string]interface{}) {
	settlementParams := make(map[string]interface{})

	// Pagination
	if page, ok := params["page"].(string); ok {
		if p, err := strconv.Atoi(page); err == nil {
			settlementParams["page"] = p
		}
	}
	if pageSize, ok := params["page_size"].(string); ok {
		if ps, err := strconv.Atoi(pageSize); err == nil && ps <= 100 {
			settlementParams["page_size"] = ps
		}
	}

	// Date filters
	if startDate, ok := params["start_date"].(string); ok {
		if err := validateDateFormat(startDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		settlementParams["start_date"] = startDate
	}

	if endDate, ok := params["end_date"].(string); ok {
		if err := validateDateFormat(endDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		settlementParams["end_date"] = endDate
	}

	settlementParams = removeEmptyParams(settlementParams)

	result, err := reportingService.GetSettlementReport(settlementParams)
	if err != nil {
		handleError(w, fmt.Sprintf("Settlement report generation failed: %v", err), http.StatusInternalServerError, "API_ERROR")
		return
	}

	sendJSONResponse(w, result, http.StatusOK)
}

// handleExport handles transaction export requests
func handleExport(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}

	params, err := getRequestParams(r)
	if err != nil {
		handleError(w, "Failed to parse request parameters", http.StatusBadRequest, "PARSE_ERROR")
		return
	}

	handleExportWithParams(w, r, params)
}

func handleExportWithParams(w http.ResponseWriter, r *http.Request, params map[string]interface{}) {
	format := "json"
	if f, ok := params["format"].(string); ok {
		format = strings.ToLower(f)
	}

	if format != "json" && format != "csv" && format != "xml" {
		handleError(w, "Invalid format. Supported formats: json, csv, xml", http.StatusBadRequest, "VALIDATION_ERROR")
		return
	}

	// Build export filters
	exportFilters := make(map[string]interface{})
	filterKeys := []string{"start_date", "end_date", "transaction_id", "payment_type", "status", "amount_min", "amount_max", "card_last_four"}

	for _, key := range filterKeys {
		if val, ok := params[key].(string); ok {
			if key == "start_date" || key == "end_date" {
				if err := validateDateFormat(val); err != nil {
					handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
					return
				}
			}
			exportFilters[key] = val
		}
	}

	exportFilters = removeEmptyParams(exportFilters)

	result, err := reportingService.ExportTransactions(exportFilters, format)
	if err != nil {
		handleError(w, fmt.Sprintf("Export failed: %v", err), http.StatusInternalServerError, "API_ERROR")
		return
	}

	// Handle CSV/XML export with appropriate headers
	if format == "csv" {
		w.Header().Set("Content-Type", "text/csv")
		filename := "transactions.csv"
		if fn, ok := result["filename"].(string); ok {
			filename = fn
		}
		w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"", filename))
		if data, ok := result["data"].(string); ok {
			w.Write([]byte(data))
		}
		return
	} else if format == "xml" {
		w.Header().Set("Content-Type", "application/xml")
		filename := "transactions.xml"
		if fn, ok := result["filename"].(string); ok {
			filename = fn
		}
		w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"", filename))
		if data, ok := result["data"].(string); ok {
			w.Write([]byte(data))
		}
		return
	}

	sendJSONResponse(w, result, http.StatusOK)
}

// handleSummary handles summary statistics requests
func handleSummary(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}

	params, err := getRequestParams(r)
	if err != nil {
		handleError(w, "Failed to parse request parameters", http.StatusBadRequest, "PARSE_ERROR")
		return
	}

	handleSummaryWithParams(w, r, params)
}

func handleSummaryWithParams(w http.ResponseWriter, r *http.Request, params map[string]interface{}) {
	summaryParams := make(map[string]interface{})

	if startDate, ok := params["start_date"].(string); ok {
		if err := validateDateFormat(startDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		summaryParams["start_date"] = startDate
	}

	if endDate, ok := params["end_date"].(string); ok {
		if err := validateDateFormat(endDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		summaryParams["end_date"] = endDate
	}

	summaryParams = removeEmptyParams(summaryParams)

	result, err := reportingService.GetSummaryStats(summaryParams)
	if err != nil {
		handleError(w, fmt.Sprintf("Summary generation failed: %v", err), http.StatusInternalServerError, "API_ERROR")
		return
	}

	sendJSONResponse(w, result, http.StatusOK)
}

// handleDisputes handles dispute report requests
func handleDisputes(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}

	params, err := getRequestParams(r)
	if err != nil {
		handleError(w, "Failed to parse request parameters", http.StatusBadRequest, "PARSE_ERROR")
		return
	}

	handleDisputesWithParams(w, r, params)
}

func handleDisputesWithParams(w http.ResponseWriter, r *http.Request, params map[string]interface{}) {
	disputeFilters := make(map[string]interface{})

	// Pagination
	if page, ok := params["page"].(string); ok {
		if p, err := strconv.Atoi(page); err == nil {
			disputeFilters["page"] = p
		}
	}
	if pageSize, ok := params["page_size"].(string); ok {
		if ps, err := strconv.Atoi(pageSize); err == nil && ps <= 100 {
			disputeFilters["page_size"] = ps
		}
	}

	// Date filters
	if startDate, ok := params["start_date"].(string); ok {
		if err := validateDateFormat(startDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		disputeFilters["start_date"] = startDate
	}

	if endDate, ok := params["end_date"].(string); ok {
		if err := validateDateFormat(endDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		disputeFilters["end_date"] = endDate
	}

	// Dispute-specific filters
	if stage, ok := params["stage"]; ok {
		disputeFilters["stage"] = stage
	}
	if status, ok := params["status"]; ok {
		disputeFilters["status"] = status
	}

	disputeFilters = removeEmptyParams(disputeFilters)

	result, err := reportingService.GetDisputeReport(disputeFilters)
	if err != nil {
		handleError(w, fmt.Sprintf("Dispute report generation failed: %v", err), http.StatusInternalServerError, "API_ERROR")
		return
	}

	sendJSONResponse(w, result, http.StatusOK)
}

// handleDisputeDetail handles dispute detail requests
func handleDisputeDetail(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}

	vars := mux.Vars(r)
	disputeID := vars["id"]

	if disputeID == "" {
		params, _ := getRequestParams(r)
		handleDisputeDetailWithParams(w, r, params)
		return
	}

	result, err := reportingService.GetDisputeDetails(disputeID)
	if err != nil {
		handleError(w, fmt.Sprintf("Failed to retrieve dispute details: %v", err), http.StatusInternalServerError, "API_ERROR")
		return
	}

	sendJSONResponse(w, result, http.StatusOK)
}

func handleDisputeDetailWithParams(w http.ResponseWriter, r *http.Request, params map[string]interface{}) {
	if err := validateRequiredParams(params, []string{"dispute_id"}); err != nil {
		handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
		return
	}

	disputeID := params["dispute_id"].(string)
	result, err := reportingService.GetDisputeDetails(disputeID)
	if err != nil {
		handleError(w, fmt.Sprintf("Failed to retrieve dispute details: %v", err), http.StatusInternalServerError, "API_ERROR")
		return
	}

	sendJSONResponse(w, result, http.StatusOK)
}

// handleDeposits handles deposit report requests
func handleDeposits(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}

	params, err := getRequestParams(r)
	if err != nil {
		handleError(w, "Failed to parse request parameters", http.StatusBadRequest, "PARSE_ERROR")
		return
	}

	handleDepositsWithParams(w, r, params)
}

func handleDepositsWithParams(w http.ResponseWriter, r *http.Request, params map[string]interface{}) {
	depositFilters := make(map[string]interface{})

	// Pagination
	if page, ok := params["page"].(string); ok {
		if p, err := strconv.Atoi(page); err == nil {
			depositFilters["page"] = p
		}
	}
	if pageSize, ok := params["page_size"].(string); ok {
		if ps, err := strconv.Atoi(pageSize); err == nil && ps <= 100 {
			depositFilters["page_size"] = ps
		}
	}

	// Date filters
	if startDate, ok := params["start_date"].(string); ok {
		if err := validateDateFormat(startDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		depositFilters["start_date"] = startDate
	}

	if endDate, ok := params["end_date"].(string); ok {
		if err := validateDateFormat(endDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		depositFilters["end_date"] = endDate
	}

	// Deposit-specific filters
	if depositID, ok := params["deposit_id"]; ok {
		depositFilters["deposit_id"] = depositID
	}
	if status, ok := params["status"]; ok {
		depositFilters["status"] = status
	}

	depositFilters = removeEmptyParams(depositFilters)

	result, err := reportingService.GetDepositReport(depositFilters)
	if err != nil {
		handleError(w, fmt.Sprintf("Deposit report generation failed: %v", err), http.StatusInternalServerError, "API_ERROR")
		return
	}

	sendJSONResponse(w, result, http.StatusOK)
}

// handleDepositDetail handles deposit detail requests
func handleDepositDetail(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}

	vars := mux.Vars(r)
	depositID := vars["id"]

	if depositID == "" {
		params, _ := getRequestParams(r)
		handleDepositDetailWithParams(w, r, params)
		return
	}

	result, err := reportingService.GetDepositDetails(depositID)
	if err != nil {
		handleError(w, fmt.Sprintf("Failed to retrieve deposit details: %v", err), http.StatusInternalServerError, "API_ERROR")
		return
	}

	sendJSONResponse(w, result, http.StatusOK)
}

func handleDepositDetailWithParams(w http.ResponseWriter, r *http.Request, params map[string]interface{}) {
	if err := validateRequiredParams(params, []string{"deposit_id"}); err != nil {
		handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
		return
	}

	depositID := params["deposit_id"].(string)
	result, err := reportingService.GetDepositDetails(depositID)
	if err != nil {
		handleError(w, fmt.Sprintf("Failed to retrieve deposit details: %v", err), http.StatusInternalServerError, "API_ERROR")
		return
	}

	sendJSONResponse(w, result, http.StatusOK)
}

// handleBatches handles batch report requests
func handleBatches(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}

	params, err := getRequestParams(r)
	if err != nil {
		handleError(w, "Failed to parse request parameters", http.StatusBadRequest, "PARSE_ERROR")
		return
	}

	handleBatchesWithParams(w, r, params)
}

func handleBatchesWithParams(w http.ResponseWriter, r *http.Request, params map[string]interface{}) {
	batchFilters := make(map[string]interface{})

	if startDate, ok := params["start_date"].(string); ok {
		if err := validateDateFormat(startDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		batchFilters["start_date"] = startDate
	}

	if endDate, ok := params["end_date"].(string); ok {
		if err := validateDateFormat(endDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		batchFilters["end_date"] = endDate
	}

	batchFilters = removeEmptyParams(batchFilters)

	result, err := reportingService.GetBatchReport(batchFilters)
	if err != nil {
		handleError(w, fmt.Sprintf("Batch report generation failed: %v", err), http.StatusInternalServerError, "API_ERROR")
		return
	}

	sendJSONResponse(w, result, http.StatusOK)
}

// handleDeclines handles declined transactions report requests
func handleDeclines(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}

	params, err := getRequestParams(r)
	if err != nil {
		handleError(w, "Failed to parse request parameters", http.StatusBadRequest, "PARSE_ERROR")
		return
	}

	handleDeclinesWithParams(w, r, params)
}

func handleDeclinesWithParams(w http.ResponseWriter, r *http.Request, params map[string]interface{}) {
	declineFilters := make(map[string]interface{})

	// Pagination
	if page, ok := params["page"].(string); ok {
		if p, err := strconv.Atoi(page); err == nil {
			declineFilters["page"] = p
		}
	}
	if pageSize, ok := params["page_size"].(string); ok {
		if ps, err := strconv.Atoi(pageSize); err == nil && ps <= 100 {
			declineFilters["page_size"] = ps
		}
	}

	// Date filters
	if startDate, ok := params["start_date"].(string); ok {
		if err := validateDateFormat(startDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		declineFilters["start_date"] = startDate
	}

	if endDate, ok := params["end_date"].(string); ok {
		if err := validateDateFormat(endDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		declineFilters["end_date"] = endDate
	}

	// Other filters
	filterKeys := []string{"payment_type", "amount_min", "amount_max", "card_last_four"}
	for _, key := range filterKeys {
		if val, ok := params[key]; ok {
			declineFilters[key] = val
		}
	}

	declineFilters = removeEmptyParams(declineFilters)

	result, err := reportingService.GetDeclinedTransactionsReport(declineFilters)
	if err != nil {
		handleError(w, fmt.Sprintf("Declined transactions report generation failed: %v", err), http.StatusInternalServerError, "API_ERROR")
		return
	}

	sendJSONResponse(w, result, http.StatusOK)
}

// handleDateRange handles comprehensive date range report requests
func handleDateRange(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}

	params, err := getRequestParams(r)
	if err != nil {
		handleError(w, "Failed to parse request parameters", http.StatusBadRequest, "PARSE_ERROR")
		return
	}

	handleDateRangeWithParams(w, r, params)
}

func handleDateRangeWithParams(w http.ResponseWriter, r *http.Request, params map[string]interface{}) {
	dateRangeParams := make(map[string]interface{})

	if startDate, ok := params["start_date"].(string); ok {
		if err := validateDateFormat(startDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		dateRangeParams["start_date"] = startDate
	}

	if endDate, ok := params["end_date"].(string); ok {
		if err := validateDateFormat(endDate); err != nil {
			handleError(w, err.Error(), http.StatusBadRequest, "VALIDATION_ERROR")
			return
		}
		dateRangeParams["end_date"] = endDate
	}

	// Limit parameters
	limitKeys := []string{"transaction_limit", "settlement_limit", "dispute_limit", "deposit_limit"}
	maxLimits := map[string]int{"transaction_limit": 1000, "settlement_limit": 500, "dispute_limit": 100, "deposit_limit": 100}

	for _, key := range limitKeys {
		if val, ok := params[key].(string); ok {
			if limit, err := strconv.Atoi(val); err == nil {
				if limit > maxLimits[key] {
					limit = maxLimits[key]
				}
				dateRangeParams[key] = limit
			}
		}
	}

	dateRangeParams = removeEmptyParams(dateRangeParams)

	result, err := reportingService.GetDateRangeReport(dateRangeParams)
	if err != nil {
		handleError(w, fmt.Sprintf("Date range report generation failed: %v", err), http.StatusInternalServerError, "API_ERROR")
		return
	}

	sendJSONResponse(w, result, http.StatusOK)
}

// handleReportsConfig handles configuration status requests
func handleReportsConfig(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}

	configStatus := GetSDKConfigStatus()
	envValidation := ValidateEnvironmentConfig()

	response := APIResponse{
		Success: true,
		Data: map[string]interface{}{
			"sdk_status":            configStatus,
			"environment_validation": envValidation,
			"api_endpoints": map[string]string{
				"search":         "/reports/search",
				"detail":         "/reports/detail?transaction_id={id}",
				"settlement":     "/reports/settlement",
				"disputes":       "/reports/disputes",
				"dispute_detail": "/reports/dispute/{id}",
				"deposits":       "/reports/deposits",
				"deposit_detail": "/reports/deposit/{id}",
				"batches":        "/reports/batches",
				"declines":       "/reports/declines",
				"date_range":     "/reports/date-range",
				"export":         "/reports/export?format={json|csv|xml}",
				"summary":        "/reports/summary",
				"config":         "/reports/config",
			},
		},
		Timestamp: time.Now().Format("2006-01-02 15:04:05"),
	}

	sendJSONResponse(w, response, http.StatusOK)
}

// StartReportingServer starts a standalone reporting server
func StartReportingServer(port string) error {
	router := mux.NewRouter()

	if err := InitializeReportingAPI(router); err != nil {
		return fmt.Errorf("failed to initialize reporting API: %w", err)
	}

	log.Printf("Reporting API server starting on http://localhost:%s", port)
	log.Printf("API documentation available at http://localhost:%s/reports", port)

	return http.ListenAndServe("0.0.0.0:"+port, router)
}