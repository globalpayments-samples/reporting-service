// Package main provides the core reporting service functionality for Global Payments.
// This service provides reporting functionality including search, filtering,
// dispute management, deposits and data export, read from GP API.
package main

import (
	"context"
	"encoding/csv"
	"encoding/xml"
	"errors"
	"fmt"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// validationError is a problem with the request, answered with VALIDATION_ERROR.
type validationError struct{ message string }

func (e *validationError) Error() string { return e.message }

func newValidationError(format string, args ...interface{}) error {
	return &validationError{message: fmt.Sprintf(format, args...)}
}

// reportError wraps a GP API error with the report that failed, answered with API_ERROR.
func reportError(prefix string, err error) error {
	var apiErr *gpAPIError
	if errors.As(err, &apiErr) {
		return &gpAPIError{Message: prefix + ": " + apiErr.Error()}
	}
	return err
}

// ReportingService provides methods for transaction reporting, searching, and data export
type ReportingService struct {
	client *gpAPIClient
}

// TransactionInfo represents formatted transaction data
type TransactionInfo struct {
	XMLName         xml.Name `json:"-" xml:"transaction"`
	TransactionID   string   `json:"transaction_id" xml:"transaction_id"`
	Timestamp       string   `json:"timestamp" xml:"timestamp"`
	Amount          float64  `json:"amount" xml:"amount"`
	Currency        string   `json:"currency" xml:"currency"`
	Status          string   `json:"status" xml:"status"`
	PaymentMethod   string   `json:"payment_method" xml:"payment_method"`
	CardLastFour    string   `json:"card_last_four" xml:"card_last_four"`
	AuthCode        string   `json:"auth_code" xml:"auth_code"`
	ReferenceNumber string   `json:"reference_number" xml:"reference_number"`
}

// SettlementInfo represents settlement data. GP API returns one settled
// transaction per row, so each row counts one transaction.
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
	DisputeID          string  `json:"dispute_id"`
	TransactionID      string  `json:"transaction_id"`
	CaseNumber         string  `json:"case_number"`
	DisputeStage       string  `json:"dispute_stage"`
	DisputeStatus      string  `json:"dispute_status"`
	CaseAmount         float64 `json:"case_amount"`
	Currency           string  `json:"currency"`
	ReasonCode         string  `json:"reason_code"`
	ReasonDescription  string  `json:"reason_description"`
	CaseTime           string  `json:"case_time"`
	LastAdjustmentTime string  `json:"last_adjustment_time"`
}

// DepositInfo represents deposit data
type DepositInfo struct {
	DepositID         string  `json:"deposit_id"`
	DepositDate       string  `json:"deposit_date"`
	DepositReference  string  `json:"deposit_reference"`
	DepositStatus     string  `json:"deposit_status"`
	DepositAmount     float64 `json:"deposit_amount"`
	Currency          string  `json:"currency"`
	MerchantNumber    string  `json:"merchant_number"`
	MerchantHierarchy string  `json:"merchant_hierarchy"`
	SalesCount        int     `json:"sales_count"`
	SalesAmount       float64 `json:"sales_amount"`
	RefundsCount      int     `json:"refunds_count"`
	RefundsAmount     float64 `json:"refunds_amount"`
}

// NewReportingService checks the GP API credentials and creates the service
func NewReportingService() (*ReportingService, error) {
	appID := os.Getenv("GP_API_APP_ID")
	appKey := os.Getenv("GP_API_APP_KEY")
	if appID == "" {
		return nil, newValidationError("Failed to configure SDK: Missing required environment variable: GP_API_APP_ID")
	}
	if appKey == "" {
		return nil, newValidationError("Failed to configure SDK: Missing required environment variable: GP_API_APP_KEY")
	}
	return &ReportingService{client: newGPAPIClient(appID, appKey)}, nil
}

func now() string {
	return time.Now().Format("2006-01-02 15:04:05")
}

// setDateRange adds the GP API date filters for a YYYY-MM-DD start and end date
func setDateRange(query url.Values, filters map[string]string, fromKey, toKey string) {
	if v := filters["start_date"]; v != "" {
		query.Set(fromKey, v)
	}
	if v := filters["end_date"]; v != "" {
		query.Set(toKey, v)
	}
}

func setPaging(query url.Values, page, pageSize int) {
	query.Set("page", strconv.Itoa(page))
	query.Set("page_size", strconv.Itoa(pageSize))
}

// SearchTransactions searches transactions with filters and pagination
func (rs *ReportingService) SearchTransactions(ctx context.Context, filters map[string]string) (map[string]interface{}, error) {
	page := intValue(filters, "page", 1)
	pageSize := intValue(filters, "page_size", 10)

	query := url.Values{}
	setPaging(query, page, pageSize)
	setDateRange(query, filters, "from_time_created", "to_time_created")

	if v := filters["transaction_id"]; v != "" {
		query.Set("id", v)
	}
	if v := filters["payment_type"]; v != "" {
		paymentType, ok := map[string]string{"sale": "SALE", "refund": "REFUND"}[strings.ToLower(v)]
		if !ok {
			return nil, newValidationError("Invalid payment_type. Supported values: sale, refund")
		}
		query.Set("type", paymentType)
	}
	if v := filters["card_last_four"]; v != "" {
		query.Set("number_last4", v)
	}

	var resp struct {
		gpPage
		Transactions []gpTransaction `json:"transactions"`
	}
	if err := rs.client.get(ctx, "/transactions", query, &resp); err != nil {
		return nil, reportError("Transaction search failed", err)
	}

	transactions := make([]TransactionInfo, 0, len(resp.Transactions))
	for _, t := range resp.Transactions {
		transactions = append(transactions, formatTransaction(t))
	}

	// GP API only filters on an exact amount, so the range is applied here
	if v := filters["amount_min"]; v != "" {
		if min, err := strconv.ParseFloat(v, 64); err == nil {
			transactions = filterTransactions(transactions, func(t TransactionInfo) bool { return t.Amount >= min })
		}
	}
	if v := filters["amount_max"]; v != "" {
		if max, err := strconv.ParseFloat(v, 64); err == nil {
			transactions = filterTransactions(transactions, func(t TransactionInfo) bool { return t.Amount <= max })
		}
	}

	// Status is not a search filter in this sample, it is applied to the page
	if v := filters["status"]; v != "" {
		status := strings.ToUpper(v)
		transactions = filterTransactions(transactions, func(t TransactionInfo) bool { return strings.ToUpper(t.Status) == status })
	}

	return map[string]interface{}{
		"success": true,
		"data": map[string]interface{}{
			"transactions": transactions,
			"pagination": map[string]interface{}{
				"page":                 page,
				"page_size":            pageSize,
				"total_count":          len(transactions),
				"original_total_count": resp.total(),
			},
		},
		"timestamp": now(),
	}, nil
}

// GetTransactionDetails retrieves detailed information for a specific transaction
func (rs *ReportingService) GetTransactionDetails(ctx context.Context, transactionID string) (map[string]interface{}, error) {
	var t gpTransaction
	if err := rs.client.get(ctx, "/transactions/"+url.PathEscape(transactionID), nil, &t); err != nil {
		return nil, reportError("Failed to retrieve transaction details", err)
	}

	info := formatTransaction(t)
	details := map[string]interface{}{
		"transaction_id": info.TransactionID,
		"timestamp":      info.Timestamp,
		"amount":         info.Amount,
		"currency":       info.Currency,
		"status":         info.Status,
		"payment_method": info.PaymentMethod,
		"card_details": map[string]string{
			"masked_number": info.CardLastFour,
			"card_type":     "",
			"entry_mode":    "",
		},
		"auth_code":                info.AuthCode,
		"reference_number":         info.ReferenceNumber,
		"gateway_response_code":    "",
		"gateway_response_message": "",
	}
	if pm := t.PaymentMethod; pm != nil {
		cardDetails := details["card_details"].(map[string]string)
		cardDetails["entry_mode"] = pm.EntryMode
		if pm.Card != nil {
			cardDetails["card_type"] = pm.Card.Brand
		}
		details["gateway_response_code"] = pm.Result
		details["gateway_response_message"] = pm.Message
	}

	return map[string]interface{}{
		"success":   true,
		"data":      details,
		"timestamp": now(),
	}, nil
}

// GetSettlementReport generates settlement report for a date range
func (rs *ReportingService) GetSettlementReport(ctx context.Context, params map[string]string) (map[string]interface{}, error) {
	page := intValue(params, "page", 1)
	pageSize := intValue(params, "page_size", 50)

	accountID, accountName, err := rs.client.dataAccount(ctx)
	if err != nil {
		return nil, reportError("Settlement report generation failed", err)
	}

	query := url.Values{}
	setPaging(query, page, pageSize)
	setDateRange(query, params, "from_time_created", "to_time_created")
	setDataAccount(query, accountID, accountName)

	var resp struct {
		gpPage
		Transactions []gpTransaction `json:"transactions"`
	}
	if err := rs.client.get(ctx, "/settlement/transactions", query, &resp); err != nil {
		return nil, reportError("Settlement report generation failed", err)
	}

	settlements := make([]SettlementInfo, 0, len(resp.Transactions))
	for _, t := range resp.Transactions {
		date := t.DepositTimeCreated
		if date == "" {
			date = t.TimeCreated
		}
		settlements = append(settlements, SettlementInfo{
			SettlementID:     t.ID,
			SettlementDate:   date,
			TransactionCount: 1,
			TotalAmount:      float64(t.Amount),
			Currency:         currencyOr(t.Currency),
			Status:           t.Status,
		})
	}

	return map[string]interface{}{
		"success": true,
		"data": map[string]interface{}{
			"settlements": settlements,
			"summary":     generateSettlementSummary(settlements),
			"pagination": map[string]interface{}{
				"page":        page,
				"page_size":   pageSize,
				"total_count": resp.total(),
			},
		},
		"timestamp": now(),
	}, nil
}

// GetDisputeReport retrieves dispute reports with filtering and pagination
func (rs *ReportingService) GetDisputeReport(ctx context.Context, filters map[string]string) (map[string]interface{}, error) {
	page := intValue(filters, "page", 1)
	pageSize := intValue(filters, "page_size", 10)

	query := url.Values{}
	setPaging(query, page, pageSize)
	setDateRange(query, filters, "from_stage_time_created", "to_stage_time_created")
	if v := filters["stage"]; v != "" {
		query.Set("stage", strings.ToUpper(v))
	}
	if v := filters["status"]; v != "" {
		query.Set("status", strings.ToUpper(v))
	}

	var resp struct {
		gpPage
		Disputes []gpDispute `json:"disputes"`
	}
	if err := rs.client.get(ctx, "/disputes", query, &resp); err != nil {
		return nil, reportError("Dispute report generation failed", err)
	}

	disputes := make([]DisputeInfo, 0, len(resp.Disputes))
	for _, d := range resp.Disputes {
		disputes = append(disputes, formatDispute(d))
	}

	return map[string]interface{}{
		"success": true,
		"data": map[string]interface{}{
			"disputes": disputes,
			"pagination": map[string]interface{}{
				"page":        page,
				"page_size":   pageSize,
				"total_count": resp.total(),
			},
		},
		"timestamp": now(),
	}, nil
}

// GetDisputeDetails retrieves detailed information for a specific dispute
func (rs *ReportingService) GetDisputeDetails(ctx context.Context, disputeID string) (map[string]interface{}, error) {
	var d gpDispute
	if err := rs.client.get(ctx, "/disputes/"+url.PathEscape(disputeID), nil, &d); err != nil {
		return nil, reportError("Failed to retrieve dispute details", err)
	}

	info := formatDispute(d)
	documents := make([]map[string]string, 0, len(d.Documents))
	for _, doc := range d.Documents {
		if doc.ID != "" {
			documents = append(documents, map[string]string{"id": doc.ID, "type": doc.Type})
		}
	}

	transactionDetails := map[string]interface{}{
		"amount":             0.0,
		"currency":           "USD",
		"masked_card_number": "",
		"arn":                "",
	}
	if card := disputeCard(d); card != nil {
		transactionDetails["arn"] = card.ARN
		transactionDetails["masked_card_number"] = card.Number
	}
	if t := d.Transaction; t != nil {
		transactionDetails["amount"] = float64(t.Amount)
		transactionDetails["currency"] = currencyOr(t.Currency)
		if t.PaymentMethod != nil && t.PaymentMethod.Card != nil {
			transactionDetails["masked_card_number"] = t.PaymentMethod.Card.MaskedNumberFirst6Last4
		}
	}

	return map[string]interface{}{
		"success": true,
		"data": map[string]interface{}{
			"dispute_id":           info.DisputeID,
			"transaction_id":       info.TransactionID,
			"case_number":          info.CaseNumber,
			"dispute_stage":        info.DisputeStage,
			"dispute_status":       info.DisputeStatus,
			"case_amount":          info.CaseAmount,
			"currency":             info.Currency,
			"reason_code":          info.ReasonCode,
			"reason_description":   info.ReasonDescription,
			"case_time":            info.CaseTime,
			"last_adjustment_time": info.LastAdjustmentTime,
			"case_description":     d.Description,
			"documents":            documents,
			"transaction_details":  transactionDetails,
		},
		"timestamp": now(),
	}, nil
}

// GetDepositReport retrieves deposit reports with filtering and pagination
func (rs *ReportingService) GetDepositReport(ctx context.Context, filters map[string]string) (map[string]interface{}, error) {
	page := intValue(filters, "page", 1)
	pageSize := intValue(filters, "page_size", 10)

	accountID, accountName, err := rs.client.dataAccount(ctx)
	if err != nil {
		return nil, reportError("Deposit report generation failed", err)
	}

	query := url.Values{}
	setPaging(query, page, pageSize)
	setDateRange(query, filters, "from_time_created", "to_time_created")
	setDataAccount(query, accountID, accountName)
	if v := filters["deposit_id"]; v != "" {
		query.Set("id", v)
	}
	if v := filters["status"]; v != "" {
		query.Set("status", strings.ToUpper(v))
	}

	var resp struct {
		gpPage
		Deposits []gpDeposit `json:"deposits"`
	}
	if err := rs.client.get(ctx, "/settlement/deposits", query, &resp); err != nil {
		return nil, reportError("Deposit report generation failed", err)
	}

	deposits := make([]DepositInfo, 0, len(resp.Deposits))
	for _, d := range resp.Deposits {
		deposits = append(deposits, formatDeposit(d))
	}

	return map[string]interface{}{
		"success": true,
		"data": map[string]interface{}{
			"deposits": deposits,
			"pagination": map[string]interface{}{
				"page":        page,
				"page_size":   pageSize,
				"total_count": resp.total(),
			},
		},
		"timestamp": now(),
	}, nil
}

// GetDepositDetails retrieves detailed information for a specific deposit
func (rs *ReportingService) GetDepositDetails(ctx context.Context, depositID string) (map[string]interface{}, error) {
	var d gpDeposit
	if err := rs.client.get(ctx, "/settlement/deposits/"+url.PathEscape(depositID), nil, &d); err != nil {
		return nil, reportError("Failed to retrieve deposit details", err)
	}

	info := formatDeposit(d)
	bankAccount := map[string]string{"masked_account_number": "", "bank_name": ""}
	if b := d.BankTransfer; b != nil {
		bankAccount["masked_account_number"] = b.MaskedAccountNumberLast4
		if b.Bank != nil {
			bankAccount["bank_name"] = b.Bank.Name
		}
	}

	return map[string]interface{}{
		"success": true,
		"data": map[string]interface{}{
			"deposit_id":         info.DepositID,
			"deposit_date":       info.DepositDate,
			"deposit_reference":  info.DepositReference,
			"deposit_status":     info.DepositStatus,
			"deposit_amount":     info.DepositAmount,
			"currency":           info.Currency,
			"merchant_number":    info.MerchantNumber,
			"merchant_hierarchy": info.MerchantHierarchy,
			"bank_account":       bankAccount,
			"transaction_summary": map[string]interface{}{
				"sales_count":        d.Sales.Count,
				"sales_amount":       float64(d.Sales.Amount),
				"refunds_count":      d.Refunds.Count,
				"refunds_amount":     float64(d.Refunds.Amount),
				"chargebacks_count":  d.Disputes.Chargebacks.Count,
				"chargebacks_amount": float64(d.Disputes.Chargebacks.Amount),
				"adjustments_count":  d.Disputes.Reversals.Count,
				"adjustments_amount": float64(d.Disputes.Reversals.Amount),
			},
		},
		"timestamp": now(),
	}, nil
}

// GetBatchReport answers the batch report. GP API has no batch report
// endpoint, so this gives the same API error as the Node.js and PHP services.
func (rs *ReportingService) GetBatchReport(ctx context.Context, filters map[string]string) (map[string]interface{}, error) {
	return nil, &gpAPIError{Message: "Batch report generation failed: batch reports are not available through GP API"}
}

// GetDeclinedTransactionsReport retrieves declined transactions report
func (rs *ReportingService) GetDeclinedTransactionsReport(ctx context.Context, filters map[string]string) (map[string]interface{}, error) {
	declineFilters := copyFilters(filters)
	declineFilters["status"] = "DECLINED"

	result, err := rs.SearchTransactions(ctx, declineFilters)
	if err != nil {
		return nil, reportError("Declined transactions report generation failed", err)
	}

	data := result["data"].(map[string]interface{})
	data["decline_analysis"] = analyzeDeclines(data["transactions"].([]TransactionInfo))
	return result, nil
}

// GetDateRangeReport generates a report across transactions, settlements, disputes and deposits
func (rs *ReportingService) GetDateRangeReport(ctx context.Context, params map[string]string) (map[string]interface{}, error) {
	startDate := stringValue(params, "start_date", time.Now().AddDate(0, 0, -30).Format("2006-01-02"))
	endDate := stringValue(params, "end_date", time.Now().Format("2006-01-02"))
	period := map[string]string{"start_date": startDate, "end_date": endDate}

	data := map[string]interface{}{
		"period":       period,
		"transactions": map[string]interface{}{},
		"settlements":  map[string]interface{}{},
		"disputes":     map[string]interface{}{},
		"deposits":     map[string]interface{}{},
		"summary":      map[string]interface{}{},
	}

	sections := []struct {
		key   string
		label string
		limit string
		def   int
		run   func(context.Context, map[string]string) (map[string]interface{}, error)
	}{
		{"transactions", "Transactions", "transaction_limit", 100, rs.SearchTransactions},
		{"settlements", "Settlements", "settlement_limit", 50, rs.GetSettlementReport},
		{"disputes", "Disputes", "dispute_limit", 25, rs.GetDisputeReport},
		{"deposits", "Deposits", "deposit_limit", 25, rs.GetDepositReport},
	}
	for _, section := range sections {
		result, err := section.run(ctx, map[string]string{
			"start_date": startDate,
			"end_date":   endDate,
			"page_size":  strconv.Itoa(intValue(params, section.limit, section.def)),
		})
		if err != nil {
			var apiErr *gpAPIError
			if errors.As(err, &apiErr) {
				data[section.key] = map[string]string{"error": fmt.Sprintf("%s not available: %s", section.label, err)}
			} else {
				logError(section.label+" not available for the date range report", err)
				data[section.key] = map[string]string{"error": section.label + " not available"}
			}
			continue
		}
		data[section.key] = result["data"]
	}

	data["summary"] = generateComprehensiveSummary(data)

	return map[string]interface{}{
		"success":   true,
		"data":      data,
		"timestamp": now(),
	}, nil
}

// ExportTransactions exports transaction data in specified format
func (rs *ReportingService) ExportTransactions(ctx context.Context, filters map[string]string, format string) (map[string]interface{}, error) {
	exportFilters := copyFilters(filters)
	// GP API pages hold up to 1000 transactions
	exportFilters["page_size"] = "1000"

	result, err := rs.SearchTransactions(ctx, exportFilters)
	if err != nil {
		return nil, reportError("Export failed", err)
	}
	transactions := result["data"].(map[string]interface{})["transactions"].([]TransactionInfo)

	switch format {
	case "csv":
		return exportToCSV(transactions)
	case "xml":
		return exportToXML(transactions)
	default:
		return map[string]interface{}{
			"success":   true,
			"data":      transactions,
			"format":    "json",
			"timestamp": now(),
		}, nil
	}
}

// GetSummaryStats generates summary statistics
func (rs *ReportingService) GetSummaryStats(ctx context.Context, params map[string]string) (map[string]interface{}, error) {
	startDate := stringValue(params, "start_date", time.Now().AddDate(0, 0, -30).Format("2006-01-02"))
	endDate := stringValue(params, "end_date", time.Now().Format("2006-01-02"))

	result, err := rs.SearchTransactions(ctx, map[string]string{
		"start_date": startDate,
		"end_date":   endDate,
		"page_size":  "1000",
	})
	if err != nil {
		message := "Failed to generate summary statistics"
		var apiErr *gpAPIError
		if errors.As(err, &apiErr) {
			message += ": " + err.Error()
		} else {
			logError(message, err)
		}
		return map[string]interface{}{
			"success":   false,
			"error":     message,
			"timestamp": now(),
		}, nil
	}

	transactions := result["data"].(map[string]interface{})["transactions"].([]TransactionInfo)
	return map[string]interface{}{
		"success": true,
		"data":    calculateSummaryStats(transactions),
		"period": map[string]string{
			"start_date": startDate,
			"end_date":   endDate,
		},
		"timestamp": now(),
	}, nil
}

// Helper functions

func intValue(m map[string]string, key string, defaultValue int) int {
	if i, err := strconv.Atoi(m[key]); err == nil && i > 0 {
		return i
	}
	return defaultValue
}

func stringValue(m map[string]string, key string, defaultValue string) string {
	if v := m[key]; v != "" {
		return v
	}
	return defaultValue
}

func copyFilters(filters map[string]string) map[string]string {
	out := make(map[string]string, len(filters)+1)
	for k, v := range filters {
		out[k] = v
	}
	return out
}

func currencyOr(currency string) string {
	if currency == "" {
		return "USD"
	}
	return currency
}

func setDataAccount(query url.Values, id, name string) {
	if name != "" {
		query.Set("account_name", name)
	}
	if id != "" {
		query.Set("account_id", id)
	}
}

func filterTransactions(transactions []TransactionInfo, keep func(TransactionInfo) bool) []TransactionInfo {
	filtered := []TransactionInfo{}
	for _, t := range transactions {
		if keep(t) {
			filtered = append(filtered, t)
		}
	}
	return filtered
}

func formatTransaction(t gpTransaction) TransactionInfo {
	info := TransactionInfo{
		TransactionID:   t.ID,
		Timestamp:       t.TimeCreated,
		Amount:          float64(t.Amount),
		Currency:        currencyOr(t.Currency),
		Status:          t.Status,
		ReferenceNumber: t.Reference,
	}
	if pm := t.PaymentMethod; pm != nil {
		switch {
		case pm.Card != nil:
			info.PaymentMethod = "CARD"
			info.CardLastFour = pm.Card.MaskedNumberFirst6Last4
			info.AuthCode = pm.Card.AuthCode
		case pm.DigitalWallet != nil:
			info.PaymentMethod = "DIGITAL WALLET"
			info.CardLastFour = pm.DigitalWallet.MaskedTokenFirst6Last4
		case pm.BankTransfer != nil && pm.APM == nil:
			info.PaymentMethod = "BANK TRANSFER"
		}
		if pm.APM != nil {
			info.PaymentMethod = "APM"
		}
	}
	return info
}

func disputeCard(d gpDispute) *gpCard {
	if d.Transaction != nil && d.Transaction.PaymentMethod != nil && d.Transaction.PaymentMethod.Card != nil {
		return d.Transaction.PaymentMethod.Card
	}
	if d.PaymentMethod != nil {
		return d.PaymentMethod.Card
	}
	return nil
}

func formatDispute(d gpDispute) DisputeInfo {
	caseTime := d.TimeCreated
	if caseTime == "" {
		caseTime = d.StageTimeCreated
	}
	info := DisputeInfo{
		DisputeID:         d.ID,
		DisputeStage:      d.Stage,
		DisputeStatus:     d.Status,
		CaseAmount:        float64(d.Amount),
		Currency:          currencyOr(d.Currency),
		ReasonCode:        d.ReasonCode,
		ReasonDescription: d.ReasonDescription,
		CaseTime:          caseTime,
	}
	if d.Transaction != nil {
		info.TransactionID = d.Transaction.ID
	}
	return info
}

func formatDeposit(d gpDeposit) DepositInfo {
	info := DepositInfo{
		DepositID:     d.ID,
		DepositDate:   d.TimeCreated,
		DepositStatus: d.Status,
		DepositAmount: float64(d.Amount),
		Currency:      currencyOr(d.Currency),
		SalesCount:    d.Sales.Count,
		SalesAmount:   float64(d.Sales.Amount),
		RefundsCount:  d.Refunds.Count,
		RefundsAmount: float64(d.Refunds.Amount),
	}
	if d.System != nil {
		info.MerchantNumber = d.System.MID
		info.MerchantHierarchy = d.System.Hierarchy
	}
	return info
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
		"total_settlements":         len(settlements),
		"total_amount":              totalAmount,
		"total_transactions":        totalTransactions,
		"average_settlement_amount": avgAmount,
	}
}

func calculateSummaryStats(transactions []TransactionInfo) map[string]interface{} {
	var totalAmount float64
	statusCounts := map[string]int{}
	paymentTypeCounts := map[string]int{}

	for _, t := range transactions {
		totalAmount += t.Amount
		statusCounts[orUnknown(t.Status, "unknown")]++
		paymentTypeCounts[orUnknown(t.PaymentMethod, "unknown")]++
	}

	avgAmount := 0.0
	if len(transactions) > 0 {
		avgAmount = totalAmount / float64(len(transactions))
	}

	return map[string]interface{}{
		"total_transactions":     len(transactions),
		"total_amount":           totalAmount,
		"average_amount":         avgAmount,
		"status_breakdown":       statusCounts,
		"payment_type_breakdown": paymentTypeCounts,
	}
}

func orUnknown(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

func analyzeDeclines(transactions []TransactionInfo) map[string]interface{} {
	var totalAmount float64
	declineReasons := map[string]int{}
	cardTypes := map[string]int{}
	hourlyBreakdown := map[string]int{}

	for _, t := range transactions {
		totalAmount += t.Amount
		// The list rows carry no gateway message, as in the other languages
		declineReasons["Unknown"]++
		cardTypes[orUnknown(t.PaymentMethod, "Unknown")]++
		if ts, err := time.Parse(time.RFC3339, t.Timestamp); err == nil {
			hourlyBreakdown[ts.Format("15")]++
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

func generateComprehensiveSummary(data map[string]interface{}) map[string]interface{} {
	overview := map[string]interface{}{}
	financial := map[string]interface{}{}
	operational := map[string]interface{}{}

	transactionCount := 0
	if section, ok := data["transactions"].(map[string]interface{}); ok {
		if txns, ok := section["transactions"].([]TransactionInfo); ok && len(txns) > 0 {
			var total float64
			for _, t := range txns {
				total += t.Amount
			}
			transactionCount = len(txns)
			overview["transactions"] = map[string]interface{}{
				"count":          len(txns),
				"total_amount":   total,
				"average_amount": total / float64(len(txns)),
			}
		}
	}

	if section, ok := data["settlements"].(map[string]interface{}); ok {
		if rows, ok := section["settlements"].([]SettlementInfo); ok && len(rows) > 0 {
			var total float64
			for _, s := range rows {
				total += s.TotalAmount
			}
			financial["settlements"] = map[string]interface{}{"count": len(rows), "total_amount": total}
		}
	}

	if section, ok := data["disputes"].(map[string]interface{}); ok {
		if rows, ok := section["disputes"].([]DisputeInfo); ok && len(rows) > 0 {
			var total float64
			for _, d := range rows {
				total += d.CaseAmount
			}
			rate := 0.0
			if transactionCount > 0 {
				rate = float64(len(rows)) / float64(transactionCount) * 100
			}
			operational["disputes"] = map[string]interface{}{"count": len(rows), "total_amount": total, "dispute_rate": rate}
		}
	}

	if section, ok := data["deposits"].(map[string]interface{}); ok {
		if rows, ok := section["deposits"].([]DepositInfo); ok && len(rows) > 0 {
			var total float64
			for _, d := range rows {
				total += d.DepositAmount
			}
			financial["deposits"] = map[string]interface{}{"count": len(rows), "total_amount": total}
		}
	}

	return map[string]interface{}{
		"overview":            overview,
		"financial_summary":   financial,
		"operational_metrics": operational,
	}
}

func exportFilename(extension string) string {
	return fmt.Sprintf("transactions_%s.%s", time.Now().Format("2006-01-02_15-04-05"), extension)
}

func exportToCSV(transactions []TransactionInfo) (map[string]interface{}, error) {
	var builder strings.Builder
	writer := csv.NewWriter(&builder)

	writer.Write([]string{"Transaction ID", "Timestamp", "Amount", "Currency", "Status", "Payment Method", "Card Last Four", "Auth Code", "Reference Number"})
	for _, t := range transactions {
		writer.Write([]string{
			t.TransactionID,
			t.Timestamp,
			fmt.Sprintf("%.2f", t.Amount),
			t.Currency,
			t.Status,
			t.PaymentMethod,
			t.CardLastFour,
			t.AuthCode,
			t.ReferenceNumber,
		})
	}
	writer.Flush()
	if err := writer.Error(); err != nil {
		return nil, err
	}

	return map[string]interface{}{
		"success":   true,
		"data":      builder.String(),
		"format":    "csv",
		"filename":  exportFilename("csv"),
		"timestamp": now(),
	}, nil
}

func exportToXML(transactions []TransactionInfo) (map[string]interface{}, error) {
	type transactionList struct {
		XMLName      xml.Name          `xml:"transactions"`
		Transactions []TransactionInfo `xml:"transaction"`
	}

	xmlData, err := xml.MarshalIndent(transactionList{Transactions: transactions}, "", "  ")
	if err != nil {
		return nil, err
	}

	return map[string]interface{}{
		"success":   true,
		"data":      xml.Header + string(xmlData),
		"format":    "xml",
		"filename":  exportFilename("xml"),
		"timestamp": now(),
	}, nil
}

// GetSDKConfigStatus returns current SDK configuration status
func GetSDKConfigStatus() map[string]interface{} {
	hasAppID := os.Getenv("GP_API_APP_ID") != ""
	hasAppKey := os.Getenv("GP_API_APP_KEY") != ""
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
		"timestamp":   now(),
	}
}

// ValidateEnvironmentConfig validates environment configuration
func ValidateEnvironmentConfig() map[string]interface{} {
	errs := []string{}
	warnings := []string{}

	for _, name := range []string{"GP_API_APP_ID", "GP_API_APP_KEY"} {
		if os.Getenv(name) == "" {
			errs = append(errs, fmt.Sprintf("Missing required environment variable: %s", name))
		}
	}
	for _, name := range []string{"PUBLIC_API_KEY", "SECRET_API_KEY"} {
		if os.Getenv(name) != "" {
			warnings = append(warnings, fmt.Sprintf("Legacy variable %s found. GP-API uses GP_API_APP_ID and GP_API_APP_KEY.", name))
		}
	}

	return map[string]interface{}{
		"valid":    len(errs) == 0,
		"errors":   errs,
		"warnings": warnings,
	}
}
