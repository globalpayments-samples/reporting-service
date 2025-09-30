// Package main provides the core reporting service functionality for Global Payments.
// This service class provides comprehensive reporting functionality including
// search, filtering, dispute management, deposits, batches, and data export.
package main

import (
	"context"
	"encoding/csv"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/globalpayments/go-sdk/api"
	"github.com/globalpayments/go-sdk/api/builders"
	"github.com/globalpayments/go-sdk/api/entities/enums/channel"
	"github.com/globalpayments/go-sdk/api/entities/enums/environment"
	"github.com/globalpayments/go-sdk/api/entities/enums/paymenttype"
	"github.com/globalpayments/go-sdk/api/entities/enums/transactionstatus"
	"github.com/globalpayments/go-sdk/api/entities/reporting"
	"github.com/globalpayments/go-sdk/api/serviceconfigs"
	"github.com/joho/godotenv"
)

// ReportingService provides methods for transaction reporting, searching, and data export
type ReportingService struct {
	isConfigured bool
}

// TransactionInfo represents formatted transaction data
type TransactionInfo struct {
	TransactionID   string  `json:"transaction_id" csv:"transaction_id" xml:"transaction_id"`
	Timestamp       string  `json:"timestamp" csv:"timestamp" xml:"timestamp"`
	Amount          float64 `json:"amount" csv:"amount" xml:"amount"`
	Currency        string  `json:"currency" csv:"currency" xml:"currency"`
	Status          string  `json:"status" csv:"status" xml:"status"`
	PaymentMethod   string  `json:"payment_method" csv:"payment_method" xml:"payment_method"`
	CardLastFour    string  `json:"card_last_four" csv:"card_last_four" xml:"card_last_four"`
	AuthCode        string  `json:"auth_code" csv:"auth_code" xml:"auth_code"`
	ReferenceNumber string  `json:"reference_number" csv:"reference_number" xml:"reference_number"`
}

// TransactionDetails represents detailed transaction information
type TransactionDetails struct {
	TransactionID           string                 `json:"transaction_id"`
	Timestamp               string                 `json:"timestamp"`
	Amount                  float64                `json:"amount"`
	Currency                string                 `json:"currency"`
	Status                  string                 `json:"status"`
	PaymentMethod           string                 `json:"payment_method"`
	CardDetails             map[string]string      `json:"card_details"`
	AuthCode                string                 `json:"auth_code"`
	ReferenceNumber         string                 `json:"reference_number"`
	GatewayResponseCode     string                 `json:"gateway_response_code"`
	GatewayResponseMessage  string                 `json:"gateway_response_message"`
}

// SettlementInfo represents settlement data
type SettlementInfo struct {
	SettlementID     string  `json:"settlement_id"`
	SettlementDate   string  `json:"settlement_date"`
	TransactionCount int     `json:"transaction_count"`
	TotalAmount      float64 `json:"total_amount"`
	Currency         string  `json:"currency"`
	Status           string  `json:"status"`
}

// DisputeInfo represents dispute data
type DisputeInfo struct {
	DisputeID           string  `json:"dispute_id"`
	TransactionID       string  `json:"transaction_id"`
	CaseNumber          string  `json:"case_number"`
	DisputeStage        string  `json:"dispute_stage"`
	DisputeStatus       string  `json:"dispute_status"`
	CaseAmount          float64 `json:"case_amount"`
	Currency            string  `json:"currency"`
	ReasonCode          string  `json:"reason_code"`
	ReasonDescription   string  `json:"reason_description"`
	CaseTime            string  `json:"case_time"`
	LastAdjustmentTime  string  `json:"last_adjustment_time"`
}

// DepositInfo represents deposit data
type DepositInfo struct {
	DepositID        string  `json:"deposit_id"`
	DepositDate      string  `json:"deposit_date"`
	DepositReference string  `json:"deposit_reference"`
	DepositStatus    string  `json:"deposit_status"`
	DepositAmount    float64 `json:"deposit_amount"`
	Currency         string  `json:"currency"`
	MerchantNumber   string  `json:"merchant_number"`
	MerchantHierarchy string `json:"merchant_hierarchy"`
	SalesCount       int     `json:"sales_count"`
	SalesAmount      float64 `json:"sales_amount"`
	RefundsCount     int     `json:"refunds_count"`
	RefundsAmount    float64 `json:"refunds_amount"`
}

// BatchInfo represents batch data
type BatchInfo struct {
	BatchID          string  `json:"batch_id"`
	SequenceNumber   string  `json:"sequence_number"`
	TransactionCount int     `json:"transaction_count"`
	TotalAmount      float64 `json:"total_amount"`
	Currency         string  `json:"currency"`
	BatchStatus      string  `json:"batch_status"`
	CloseTime        string  `json:"close_time"`
	OpenTime         string  `json:"open_time"`
}

// Pagination represents pagination information
type Pagination struct {
	Page              int `json:"page"`
	PageSize          int `json:"page_size"`
	TotalCount        int `json:"total_count"`
	OriginalTotalCount int `json:"original_total_count,omitempty"`
}

// NewReportingService creates a new reporting service instance
func NewReportingService() (*ReportingService, error) {
	service := &ReportingService{}
	if err := service.configureSDK(); err != nil {
		return nil, fmt.Errorf("failed to configure SDK: %w", err)
	}
	service.isConfigured = true
	return service, nil
}

// configureSDK initializes the Global Payments SDK for GP-API
func (rs *ReportingService) configureSDK() error {
	// Load environment variables
	if err := godotenv.Load(); err != nil {
		// Not a fatal error - env vars might be set externally
	}

	// Validate required environment variables
	appID := os.Getenv("GP_API_APP_ID")
	appKey := os.Getenv("GP_API_APP_KEY")

	if appID == "" || appKey == "" {
		return fmt.Errorf("missing required environment variables: GP_API_APP_ID and GP_API_APP_KEY")
	}

	// Configure GP-API
	config := serviceconfigs.NewGpApiConfig()
	config.AppId = appID
	config.AppKey = appKey
	config.Environment = environment.TEST // Change to PRODUCTION for live
	config.Channel = channel.CardNotPresent

	return api.ConfigureService(config, "default")
}

// SearchTransactions searches transactions with filters and pagination
func (rs *ReportingService) SearchTransactions(filters map[string]interface{}) (map[string]interface{}, error) {
	if !rs.isConfigured {
		return nil, fmt.Errorf("SDK is not properly configured")
	}

	ctx := context.Background()

	// Extract pagination parameters
	page := getIntValue(filters, "page", 1)
	pageSize := getIntValue(filters, "page_size", 10)
	if pageSize > 100 {
		pageSize = 100
	}

	// Build search criteria
	searchBuilder := builders.NewReportBuilder(builders.TransactionReportType)
	searchBuilder.WithPaging(page, pageSize)

	// Apply date range filters
	if startDate, ok := filters["start_date"].(string); ok && startDate != "" {
		if date, err := time.Parse("2006-01-02", startDate); err == nil {
			searchBuilder.WithStartDate(date)
		}
	}

	if endDate, ok := filters["end_date"].(string); ok && endDate != "" {
		if date, err := time.Parse("2006-01-02", endDate); err == nil {
			searchBuilder.WithEndDate(date)
		}
	}

	// Apply transaction ID filter
	if txnID, ok := filters["transaction_id"].(string); ok && txnID != "" {
		searchBuilder.WithTransactionId(txnID)
	}

	// Apply payment type filter
	if paymentTypeStr, ok := filters["payment_type"].(string); ok && paymentTypeStr != "" {
		if pt := mapPaymentType(paymentTypeStr); pt != "" {
			searchBuilder.WithPaymentType(paymenttype.PaymentType(pt))
		}
	}

	// Apply amount range filters
	if amountMin, ok := filters["amount_min"].(string); ok && amountMin != "" {
		if amt, err := strconv.ParseFloat(amountMin, 64); err == nil {
			searchBuilder.WithAmount(fmt.Sprintf("%.2f", amt))
		}
	}

	if amountMax, ok := filters["amount_max"].(string); ok && amountMax != "" {
		if amt, err := strconv.ParseFloat(amountMax, 64); err == nil {
			searchBuilder.WithAmount(fmt.Sprintf("%.2f", amt))
		}
	}

	// Apply card last four filter
	if cardLastFour, ok := filters["card_last_four"].(string); ok && cardLastFour != "" {
		searchBuilder.WithCardNumberLastFour(cardLastFour)
	}

	// Execute search
	response, err := api.ExecuteGateway[reporting.PagedResult](ctx, searchBuilder)
	if err != nil {
		return nil, fmt.Errorf("transaction search failed: %w", err)
	}

	// Format transaction list
	transactions := formatTransactionList(response.Results)

	// Apply client-side status filtering if needed
	if status, ok := filters["status"].(string); ok && status != "" {
		transactions = filterByStatus(transactions, strings.ToUpper(status))
	}

	return map[string]interface{}{
		"success": true,
		"data": map[string]interface{}{
			"transactions": transactions,
			"pagination": Pagination{
				Page:               page,
				PageSize:           pageSize,
				TotalCount:         len(transactions),
				OriginalTotalCount: response.TotalRecordCount,
			},
		},
		"timestamp": time.Now().Format("2006-01-02 15:04:05"),
	}, nil
}

// GetTransactionDetails retrieves detailed information for a specific transaction
func (rs *ReportingService) GetTransactionDetails(transactionID string) (map[string]interface{}, error) {
	if !rs.isConfigured {
		return nil, fmt.Errorf("SDK is not properly configured")
	}

	ctx := context.Background()

	searchBuilder := builders.NewReportBuilder(builders.TransactionReportType)
	searchBuilder.WithTransactionId(transactionID)

	response, err := api.ExecuteGateway[reporting.TransactionSummary](ctx, searchBuilder)
	if err != nil {
		return nil, fmt.Errorf("failed to retrieve transaction details: %w", err)
	}

	details := formatTransactionDetails(response)

	return map[string]interface{}{
		"success":   true,
		"data":      details,
		"timestamp": time.Now().Format("2006-01-02 15:04:05"),
	}, nil
}

// GetSettlementReport generates settlement report for a date range
func (rs *ReportingService) GetSettlementReport(params map[string]interface{}) (map[string]interface{}, error) {
	if !rs.isConfigured {
		return nil, fmt.Errorf("SDK is not properly configured")
	}

	ctx := context.Background()

	page := getIntValue(params, "page", 1)
	pageSize := getIntValue(params, "page_size", 50)
	if pageSize > 100 {
		pageSize = 100
	}

	searchBuilder := builders.NewReportBuilder(builders.SettlementReportType)
	searchBuilder.WithPaging(page, pageSize)

	// Apply date range
	if startDate, ok := params["start_date"].(string); ok && startDate != "" {
		if date, err := time.Parse("2006-01-02", startDate); err == nil {
			searchBuilder.WithStartDate(date)
		}
	}

	if endDate, ok := params["end_date"].(string); ok && endDate != "" {
		if date, err := time.Parse("2006-01-02", endDate); err == nil {
			searchBuilder.WithEndDate(date)
		}
	}

	response, err := api.ExecuteGateway[reporting.PagedResult](ctx, searchBuilder)
	if err != nil {
		return nil, fmt.Errorf("settlement report generation failed: %w", err)
	}

	settlements := formatSettlementList(response.Results)
	summary := generateSettlementSummary(settlements)

	return map[string]interface{}{
		"success": true,
		"data": map[string]interface{}{
			"settlements": settlements,
			"summary":     summary,
			"pagination": Pagination{
				Page:       page,
				PageSize:   pageSize,
				TotalCount: response.TotalRecordCount,
			},
		},
		"timestamp": time.Now().Format("2006-01-02 15:04:05"),
	}, nil
}

// GetDisputeReport retrieves dispute reports with filtering and pagination
func (rs *ReportingService) GetDisputeReport(filters map[string]interface{}) (map[string]interface{}, error) {
	if !rs.isConfigured {
		return nil, fmt.Errorf("SDK is not properly configured")
	}

	ctx := context.Background()

	page := getIntValue(filters, "page", 1)
	pageSize := getIntValue(filters, "page_size", 10)
	if pageSize > 100 {
		pageSize = 100
	}

	searchBuilder := builders.NewReportBuilder(builders.DisputeReportType)
	searchBuilder.WithPaging(page, pageSize)

	// Apply date range filters
	if startDate, ok := filters["start_date"].(string); ok && startDate != "" {
		if date, err := time.Parse("2006-01-02", startDate); err == nil {
			searchBuilder.WithStartDate(date)
		}
	}

	if endDate, ok := filters["end_date"].(string); ok && endDate != "" {
		if date, err := time.Parse("2006-01-02", endDate); err == nil {
			searchBuilder.WithEndDate(date)
		}
	}

	// Apply dispute stage filter
	if stage, ok := filters["stage"].(string); ok && stage != "" {
		searchBuilder.WithDisputeStage(stage)
	}

	// Apply dispute status filter
	if status, ok := filters["status"].(string); ok && status != "" {
		searchBuilder.WithDisputeStatus(status)
	}

	response, err := api.ExecuteGateway[reporting.PagedResult](ctx, searchBuilder)
	if err != nil {
		return nil, fmt.Errorf("dispute report generation failed: %w", err)
	}

	disputes := formatDisputeList(response.Results)

	return map[string]interface{}{
		"success": true,
		"data": map[string]interface{}{
			"disputes": disputes,
			"pagination": Pagination{
				Page:       page,
				PageSize:   pageSize,
				TotalCount: response.TotalRecordCount,
			},
		},
		"timestamp": time.Now().Format("2006-01-02 15:04:05"),
	}, nil
}

// GetDisputeDetails retrieves detailed information for a specific dispute
func (rs *ReportingService) GetDisputeDetails(disputeID string) (map[string]interface{}, error) {
	if !rs.isConfigured {
		return nil, fmt.Errorf("SDK is not properly configured")
	}

	ctx := context.Background()

	searchBuilder := builders.NewReportBuilder(builders.DisputeReportType)
	searchBuilder.WithDisputeId(disputeID)

	response, err := api.ExecuteGateway[reporting.DisputeSummary](ctx, searchBuilder)
	if err != nil {
		return nil, fmt.Errorf("failed to retrieve dispute details: %w", err)
	}

	details := formatDisputeDetails(response)

	return map[string]interface{}{
		"success":   true,
		"data":      details,
		"timestamp": time.Now().Format("2006-01-02 15:04:05"),
	}, nil
}

// GetDepositReport retrieves deposit reports with filtering and pagination
func (rs *ReportingService) GetDepositReport(filters map[string]interface{}) (map[string]interface{}, error) {
	if !rs.isConfigured {
		return nil, fmt.Errorf("SDK is not properly configured")
	}

	ctx := context.Background()

	page := getIntValue(filters, "page", 1)
	pageSize := getIntValue(filters, "page_size", 10)
	if pageSize > 100 {
		pageSize = 100
	}

	searchBuilder := builders.NewReportBuilder(builders.DepositReportType)
	searchBuilder.WithPaging(page, pageSize)

	// Apply date range filters
	if startDate, ok := filters["start_date"].(string); ok && startDate != "" {
		if date, err := time.Parse("2006-01-02", startDate); err == nil {
			searchBuilder.WithStartDate(date)
		}
	}

	if endDate, ok := filters["end_date"].(string); ok && endDate != "" {
		if date, err := time.Parse("2006-01-02", endDate); err == nil {
			searchBuilder.WithEndDate(date)
		}
	}

	// Apply deposit ID filter
	if depositID, ok := filters["deposit_id"].(string); ok && depositID != "" {
		searchBuilder.WithDepositReference(depositID)
	}

	// Apply status filter
	if status, ok := filters["status"].(string); ok && status != "" {
		searchBuilder.WithDepositStatus(status)
	}

	response, err := api.ExecuteGateway[reporting.PagedResult](ctx, searchBuilder)
	if err != nil {
		return nil, fmt.Errorf("deposit report generation failed: %w", err)
	}

	deposits := formatDepositList(response.Results)

	return map[string]interface{}{
		"success": true,
		"data": map[string]interface{}{
			"deposits": deposits,
			"pagination": Pagination{
				Page:       page,
				PageSize:   pageSize,
				TotalCount: response.TotalRecordCount,
			},
		},
		"timestamp": time.Now().Format("2006-01-02 15:04:05"),
	}, nil
}

// GetDepositDetails retrieves detailed information for a specific deposit
func (rs *ReportingService) GetDepositDetails(depositID string) (map[string]interface{}, error) {
	if !rs.isConfigured {
		return nil, fmt.Errorf("SDK is not properly configured")
	}

	ctx := context.Background()

	searchBuilder := builders.NewReportBuilder(builders.DepositReportType)
	searchBuilder.WithDepositReference(depositID)

	response, err := api.ExecuteGateway[reporting.DepositSummary](ctx, searchBuilder)
	if err != nil {
		return nil, fmt.Errorf("failed to retrieve deposit details: %w", err)
	}

	details := formatDepositDetails(response)

	return map[string]interface{}{
		"success":   true,
		"data":      details,
		"timestamp": time.Now().Format("2006-01-02 15:04:05"),
	}, nil
}

// GetBatchReport retrieves batch report with detailed transaction information
func (rs *ReportingService) GetBatchReport(filters map[string]interface{}) (map[string]interface{}, error) {
	if !rs.isConfigured {
		return nil, fmt.Errorf("SDK is not properly configured")
	}

	ctx := context.Background()

	searchBuilder := builders.NewReportBuilder(builders.BatchReportType)

	// Apply date range filters
	if startDate, ok := filters["start_date"].(string); ok && startDate != "" {
		if date, err := time.Parse("2006-01-02", startDate); err == nil {
			searchBuilder.WithStartDate(date)
		}
	}

	if endDate, ok := filters["end_date"].(string); ok && endDate != "" {
		if date, err := time.Parse("2006-01-02", endDate); err == nil {
			searchBuilder.WithEndDate(date)
		}
	}

	response, err := api.ExecuteGateway[reporting.PagedResult](ctx, searchBuilder)
	if err != nil {
		return nil, fmt.Errorf("batch report generation failed: %w", err)
	}

	batches := formatBatchList(response.Results)
	summary := generateBatchSummary(batches)

	return map[string]interface{}{
		"success": true,
		"data": map[string]interface{}{
			"batches": batches,
			"summary": summary,
		},
		"timestamp": time.Now().Format("2006-01-02 15:04:05"),
	}, nil
}

// GetDeclinedTransactionsReport retrieves declined transactions report
func (rs *ReportingService) GetDeclinedTransactionsReport(filters map[string]interface{}) (map[string]interface{}, error) {
	// Use transaction search with declined status filter
	filters["status"] = "DECLINED"
	result, err := rs.SearchTransactions(filters)
	if err != nil {
		return nil, fmt.Errorf("declined transactions report generation failed: %w", err)
	}

	// Add decline analysis
	if result["success"] == true {
		data := result["data"].(map[string]interface{})
		transactions := data["transactions"].([]TransactionInfo)
		data["decline_analysis"] = analyzeDeclines(transactions)
	}

	return result, nil
}

// GetDateRangeReport generates comprehensive date range report across all transaction types
func (rs *ReportingService) GetDateRangeReport(params map[string]interface{}) (map[string]interface{}, error) {
	if !rs.isConfigured {
		return nil, fmt.Errorf("SDK is not properly configured")
	}

	startDate := getStringValue(params, "start_date", time.Now().AddDate(0, 0, -30).Format("2006-01-02"))
	endDate := getStringValue(params, "end_date", time.Now().Format("2006-01-02"))

	report := map[string]interface{}{
		"success": true,
		"data": map[string]interface{}{
			"period": map[string]string{
				"start_date": startDate,
				"end_date":   endDate,
			},
			"transactions": map[string]interface{}{},
			"settlements":  map[string]interface{}{},
			"disputes":     map[string]interface{}{},
			"deposits":     map[string]interface{}{},
			"summary":      map[string]interface{}{},
		},
		"timestamp": time.Now().Format("2006-01-02 15:04:05"),
	}

	data := report["data"].(map[string]interface{})

	// Get transactions for the period
	transactionResult, err := rs.SearchTransactions(map[string]interface{}{
		"start_date": startDate,
		"end_date":   endDate,
		"page_size":  getIntValue(params, "transaction_limit", 100),
	})
	if err == nil && transactionResult["success"] == true {
		data["transactions"] = transactionResult["data"]
	}

	// Get settlements for the period
	settlementResult, err := rs.GetSettlementReport(map[string]interface{}{
		"start_date": startDate,
		"end_date":   endDate,
		"page_size":  getIntValue(params, "settlement_limit", 50),
	})
	if err == nil && settlementResult["success"] == true {
		data["settlements"] = settlementResult["data"]
	}

	// Get disputes for the period
	disputeResult, err := rs.GetDisputeReport(map[string]interface{}{
		"start_date": startDate,
		"end_date":   endDate,
		"page_size":  getIntValue(params, "dispute_limit", 25),
	})
	if err == nil {
		data["disputes"] = disputeResult["data"]
	} else {
		data["disputes"] = map[string]string{"error": fmt.Sprintf("Disputes not available: %v", err)}
	}

	// Get deposits for the period
	depositResult, err := rs.GetDepositReport(map[string]interface{}{
		"start_date": startDate,
		"end_date":   endDate,
		"page_size":  getIntValue(params, "deposit_limit", 25),
	})
	if err == nil {
		data["deposits"] = depositResult["data"]
	} else {
		data["deposits"] = map[string]string{"error": fmt.Sprintf("Deposits not available: %v", err)}
	}

	// Generate comprehensive summary
	data["summary"] = generateComprehensiveSummary(data)

	return report, nil
}

// ExportTransactions exports transaction data in specified format
func (rs *ReportingService) ExportTransactions(filters map[string]interface{}, format string) (map[string]interface{}, error) {
	if !rs.isConfigured {
		return nil, fmt.Errorf("SDK is not properly configured")
	}

	// Get all transactions (increase limit for export)
	filters["page_size"] = 1000
	result, err := rs.SearchTransactions(filters)
	if err != nil {
		return nil, fmt.Errorf("export failed: %w", err)
	}

	data := result["data"].(map[string]interface{})
	transactions := data["transactions"].([]TransactionInfo)

	switch format {
	case "csv":
		return exportToCSV(transactions)
	case "xml":
		return exportToXML(transactions)
	default: // json
		return map[string]interface{}{
			"success":   true,
			"data":      transactions,
			"format":    "json",
			"timestamp": time.Now().Format("2006-01-02 15:04:05"),
		}, nil
	}
}

// GetSummaryStats generates summary statistics
func (rs *ReportingService) GetSummaryStats(params map[string]interface{}) (map[string]interface{}, error) {
	if !rs.isConfigured {
		return nil, fmt.Errorf("SDK is not properly configured")
	}

	startDate := getStringValue(params, "start_date", time.Now().AddDate(0, 0, -30).Format("2006-01-02"))
	endDate := getStringValue(params, "end_date", time.Now().Format("2006-01-02"))

	// Get transaction summary
	result, err := rs.SearchTransactions(map[string]interface{}{
		"start_date": startDate,
		"end_date":   endDate,
		"page_size":  1000,
	})
	if err != nil {
		return map[string]interface{}{
			"success":   false,
			"error":     fmt.Sprintf("Failed to generate summary statistics: %v", err),
			"timestamp": time.Now().Format("2006-01-02 15:04:05"),
		}, err
	}

	data := result["data"].(map[string]interface{})
	transactions := data["transactions"].([]TransactionInfo)
	stats := calculateSummaryStats(transactions)

	return map[string]interface{}{
		"success": true,
		"data":    stats,
		"period": map[string]string{
			"start_date": startDate,
			"end_date":   endDate,
		},
		"timestamp": time.Now().Format("2006-01-02 15:04:05"),
	}, nil
}

// Helper functions

func getIntValue(m map[string]interface{}, key string, defaultValue int) int {
	if val, ok := m[key]; ok {
		switch v := val.(type) {
		case int:
			return v
		case float64:
			return int(v)
		case string:
			if i, err := strconv.Atoi(v); err == nil {
				return i
			}
		}
	}
	return defaultValue
}

func getStringValue(m map[string]interface{}, key string, defaultValue string) string {
	if val, ok := m[key]; ok {
		if str, ok := val.(string); ok {
			return str
		}
	}
	return defaultValue
}

func mapPaymentType(pt string) string {
	mapping := map[string]string{
		"sale":      string(paymenttype.SALE),
		"refund":    string(paymenttype.REFUND),
		"authorize": string(paymenttype.AUTH),
		"capture":   string(paymenttype.CAPTURE),
	}
	return mapping[strings.ToLower(pt)]
}

func formatTransactionList(results interface{}) []TransactionInfo {
	// Note: Actual implementation depends on SDK response structure
	// This is a placeholder that should be adapted based on actual SDK types
	transactions := []TransactionInfo{}

	// TODO: Parse actual SDK response structure
	// For now, return empty list - this should be implemented based on actual SDK

	return transactions
}

func formatTransactionDetails(txn interface{}) TransactionDetails {
	// TODO: Parse actual SDK transaction detail structure
	return TransactionDetails{}
}

func formatSettlementList(results interface{}) []SettlementInfo {
	// TODO: Parse actual SDK settlement structure
	return []SettlementInfo{}
}

func formatDisputeList(results interface{}) []DisputeInfo {
	// TODO: Parse actual SDK dispute structure
	return []DisputeInfo{}
}

func formatDepositList(results interface{}) []DepositInfo {
	// TODO: Parse actual SDK deposit structure
	return []DepositInfo{}
}

func formatBatchList(results interface{}) []BatchInfo {
	// TODO: Parse actual SDK batch structure
	return []BatchInfo{}
}

func formatDisputeDetails(dispute interface{}) map[string]interface{} {
	// TODO: Parse actual SDK dispute detail structure
	return map[string]interface{}{}
}

func formatDepositDetails(deposit interface{}) map[string]interface{} {
	// TODO: Parse actual SDK deposit detail structure
	return map[string]interface{}{}
}

func filterByStatus(transactions []TransactionInfo, status string) []TransactionInfo {
	filtered := []TransactionInfo{}
	for _, txn := range transactions {
		if strings.ToUpper(txn.Status) == status {
			filtered = append(filtered, txn)
		}
	}
	return filtered
}

func generateSettlementSummary(settlements []SettlementInfo) map[string]interface{} {
	var totalAmount float64
	var totalTransactions int

	for _, s := range settlements {
		totalAmount += s.TotalAmount
		totalTransactions += s.TransactionCount
	}

	avgAmount := 0.0
	if len(settlements) > 0 {
		avgAmount = totalAmount / float64(len(settlements))
	}

	return map[string]interface{}{
		"total_settlements":          len(settlements),
		"total_amount":               totalAmount,
		"total_transactions":         totalTransactions,
		"average_settlement_amount":  avgAmount,
	}
}

func generateBatchSummary(batches []BatchInfo) map[string]interface{} {
	var totalAmount float64
	var totalTransactions int
	statusCounts := make(map[string]int)

	for _, b := range batches {
		totalAmount += b.TotalAmount
		totalTransactions += b.TransactionCount
		statusCounts[b.BatchStatus]++
	}

	avgAmount := 0.0
	if len(batches) > 0 {
		avgAmount = totalAmount / float64(len(batches))
	}

	return map[string]interface{}{
		"total_batches":        len(batches),
		"total_amount":         totalAmount,
		"total_transactions":   totalTransactions,
		"average_batch_amount": avgAmount,
		"status_breakdown":     statusCounts,
	}
}

func calculateSummaryStats(transactions []TransactionInfo) map[string]interface{} {
	var totalAmount float64
	statusCounts := make(map[string]int)
	paymentTypeCounts := make(map[string]int)

	for _, txn := range transactions {
		totalAmount += txn.Amount
		statusCounts[txn.Status]++
		paymentTypeCounts[txn.PaymentMethod]++
	}

	avgAmount := 0.0
	if len(transactions) > 0 {
		avgAmount = totalAmount / float64(len(transactions))
	}

	return map[string]interface{}{
		"total_transactions":      len(transactions),
		"total_amount":            totalAmount,
		"average_amount":          avgAmount,
		"status_breakdown":        statusCounts,
		"payment_type_breakdown":  paymentTypeCounts,
	}
}

func analyzeDeclines(transactions []TransactionInfo) map[string]interface{} {
	var totalAmount float64
	declineReasons := make(map[string]int)
	cardTypes := make(map[string]int)
	hourlyBreakdown := make(map[string]int)

	for _, txn := range transactions {
		totalAmount += txn.Amount

		// Card type breakdown
		cardTypes[txn.PaymentMethod]++

		// Parse timestamp for hourly breakdown
		if t, err := time.Parse("2006-01-02 15:04:05", txn.Timestamp); err == nil {
			hour := t.Format("15")
			hourlyBreakdown[hour]++
		}
	}

	avgAmount := 0.0
	if len(transactions) > 0 {
		avgAmount = totalAmount / float64(len(transactions))
	}

	return map[string]interface{}{
		"total_declined_transactions": len(transactions),
		"total_declined_amount":       totalAmount,
		"average_declined_amount":     avgAmount,
		"decline_reasons":             declineReasons,
		"card_type_breakdown":         cardTypes,
		"hourly_breakdown":            hourlyBreakdown,
	}
}

func generateComprehensiveSummary(reportData map[string]interface{}) map[string]interface{} {
	summary := map[string]interface{}{
		"overview":            map[string]interface{}{},
		"financial_summary":   map[string]interface{}{},
		"operational_metrics": map[string]interface{}{},
	}

	// Transaction overview
	if txnData, ok := reportData["transactions"].(map[string]interface{}); ok {
		if txns, ok := txnData["transactions"].([]TransactionInfo); ok && len(txns) > 0 {
			var totalAmount float64
			for _, txn := range txns {
				totalAmount += txn.Amount
			}

			overview := summary["overview"].(map[string]interface{})
			overview["transactions"] = map[string]interface{}{
				"count":          len(txns),
				"total_amount":   totalAmount,
				"average_amount": totalAmount / float64(len(txns)),
			}
		}
	}

	return summary
}

func exportToCSV(transactions []TransactionInfo) (map[string]interface{}, error) {
	var builder strings.Builder
	writer := csv.NewWriter(&builder)

	// Write header
	header := []string{"Transaction ID", "Timestamp", "Amount", "Currency", "Status", "Payment Method", "Card Last Four", "Auth Code", "Reference Number"}
	writer.Write(header)

	// Write data
	for _, txn := range transactions {
		record := []string{
			txn.TransactionID,
			txn.Timestamp,
			fmt.Sprintf("%.2f", txn.Amount),
			txn.Currency,
			txn.Status,
			txn.PaymentMethod,
			txn.CardLastFour,
			txn.AuthCode,
			txn.ReferenceNumber,
		}
		writer.Write(record)
	}

	writer.Flush()
	if err := writer.Error(); err != nil {
		return nil, err
	}

	filename := fmt.Sprintf("transactions_%s.csv", time.Now().Format("2006-01-02_15-04-05"))

	return map[string]interface{}{
		"success":   true,
		"data":      builder.String(),
		"format":    "csv",
		"filename":  filename,
		"timestamp": time.Now().Format("2006-01-02 15:04:05"),
	}, nil
}

func exportToXML(transactions []TransactionInfo) (map[string]interface{}, error) {
	type TransactionList struct {
		XMLName      xml.Name          `xml:"transactions"`
		Transactions []TransactionInfo `xml:"transaction"`
	}

	txnList := TransactionList{Transactions: transactions}
	xmlData, err := xml.MarshalIndent(txnList, "", "  ")
	if err != nil {
		return nil, err
	}

	filename := fmt.Sprintf("transactions_%s.xml", time.Now().Format("2006-01-02_15-04-05"))

	return map[string]interface{}{
		"success":   true,
		"data":      string(xmlData),
		"format":    "xml",
		"filename":  filename,
		"timestamp": time.Now().Format("2006-01-02 15:04:05"),
	}, nil
}

// GetSDKConfigStatus returns current SDK configuration status
func GetSDKConfigStatus() map[string]interface{} {
	appID := os.Getenv("GP_API_APP_ID")
	appKey := os.Getenv("GP_API_APP_KEY")

	hasAppID := appID != ""
	hasAppKey := appKey != ""
	isConfigured := hasAppID && hasAppKey

	env := "Not configured"
	if isConfigured {
		env = "TEST"
	}

	return map[string]interface{}{
		"configured":  isConfigured,
		"has_app_id":  hasAppID,
		"has_app_key": hasAppKey,
		"environment": env,
		"timestamp":   time.Now().Format("2006-01-02 15:04:05"),
	}
}

// ValidateEnvironmentConfig validates environment configuration
func ValidateEnvironmentConfig() map[string]interface{} {
	results := map[string]interface{}{
		"valid":    true,
		"errors":   []string{},
		"warnings": []string{},
	}

	errors := []string{}
	warnings := []string{}

	// Check required variables
	required := []string{"GP_API_APP_ID", "GP_API_APP_KEY"}
	for _, varName := range required {
		if os.Getenv(varName) == "" {
			errors = append(errors, fmt.Sprintf("Missing required environment variable: %s", varName))
			results["valid"] = false
		}
	}

	// Check legacy variables and warn if present
	legacy := []string{"PUBLIC_API_KEY", "SECRET_API_KEY"}
	for _, varName := range legacy {
		if os.Getenv(varName) != "" {
			warnings = append(warnings, fmt.Sprintf("Legacy variable %s found. GP-API uses GP_API_APP_ID and GP_API_APP_KEY.", varName))
		}
	}

	results["errors"] = errors
	results["warnings"] = warnings

	return results
}