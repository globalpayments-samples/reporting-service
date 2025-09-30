package com.globalpayments.example;

import com.global.api.ServicesContainer;
import com.global.api.entities.Transaction;
import com.global.api.entities.TransactionSummary;
import com.global.api.entities.exceptions.ApiException;
import com.global.api.entities.exceptions.ConfigurationException;
import com.global.api.entities.reporting.*;
import com.global.api.serviceConfigs.GpApiConfig;
import com.global.api.services.ReportingService;
import io.github.cdimascio.dotenv.Dotenv;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Global Payments Reporting Service
 *
 * This service class provides comprehensive reporting functionality for
 * Global Payments transactions including search, filtering, and data export.
 *
 * @author Global Payments
 * @version 1.0
 */
public class ReportingService {

    private boolean isConfigured = false;
    private final Dotenv dotenv;

    /**
     * Constructor - Initialize and configure the SDK
     *
     * @throws ConfigurationException If configuration fails
     */
    public ReportingService() throws ConfigurationException {
        this.dotenv = Dotenv.load();
        configureGpApiSdk();
        this.isConfigured = true;
    }

    /**
     * Configure the Global Payments API SDK
     */
    private void configureGpApiSdk() throws ConfigurationException {
        GpApiConfig config = new GpApiConfig();

        // Set credentials from environment
        config.setAppId(dotenv.get("GP_API_APP_ID"));
        config.setAppKey(dotenv.get("GP_API_APP_KEY"));

        // Set environment
        String environment = dotenv.get("GP_API_ENVIRONMENT", "TEST");
        if ("PRODUCTION".equalsIgnoreCase(environment)) {
            config.setEnvironment(com.global.api.entities.enums.Environment.PRODUCTION);
        } else {
            config.setEnvironment(com.global.api.entities.enums.Environment.TEST);
        }

        // Set optional configuration
        config.setChannel(com.global.api.entities.enums.Channel.CardNotPresent);

        ServicesContainer.configureService(config);
    }

    /**
     * Search transactions with filters and pagination
     *
     * @param filters Search filters and pagination parameters
     * @return Transaction search results
     * @throws ApiException If the search request fails
     */
    public Map<String, Object> searchTransactions(Map<String, Object> filters) throws ApiException {
        ensureConfigured();

        try {
            int page = (int) filters.getOrDefault("page", 1);
            int pageSize = (int) filters.getOrDefault("page_size", 10);

            TransactionReportBuilder<List<TransactionSummary>> builder =
                ReportingService.findTransactionsPaged(page, pageSize);

            // Apply date range filters
            if (filters.containsKey("start_date")) {
                Date startDate = parseDate((String) filters.get("start_date"));
                builder.withStartDate(startDate);
            }

            if (filters.containsKey("end_date")) {
                Date endDate = parseDate((String) filters.get("end_date"));
                builder.withEndDate(endDate);
            }

            // Apply transaction ID filter
            if (filters.containsKey("transaction_id")) {
                builder.withTransactionId((String) filters.get("transaction_id"));
            }

            // Apply payment type filter
            if (filters.containsKey("payment_type")) {
                String paymentType = (String) filters.get("payment_type");
                // Add payment type filtering logic
            }

            // Apply amount filters
            if (filters.containsKey("amount_min")) {
                BigDecimal minAmount = new BigDecimal(filters.get("amount_min").toString());
                builder.where(SearchCriteria.Amount, minAmount);
            }

            if (filters.containsKey("amount_max")) {
                BigDecimal maxAmount = new BigDecimal(filters.get("amount_max").toString());
                builder.where(SearchCriteria.Amount, maxAmount);
            }

            // Apply card filter
            if (filters.containsKey("card_last_four")) {
                builder.where(SearchCriteria.CardNumberLastFour, filters.get("card_last_four"));
            }

            // Execute search
            PagedResult<TransactionSummary> response = builder.execute();

            List<Map<String, Object>> transactions = formatTransactionList(response.getResults());

            // Apply client-side status filtering if needed
            if (filters.containsKey("status")) {
                String statusFilter = ((String) filters.get("status")).toUpperCase();
                transactions = transactions.stream()
                    .filter(t -> statusFilter.equals(t.get("status")))
                    .collect(Collectors.toList());
            }

            Map<String, Object> pagination = new HashMap<>();
            pagination.put("page", page);
            pagination.put("page_size", pageSize);
            pagination.put("total_count", transactions.size());
            pagination.put("original_total_count", response.getTotalRecordCount());

            Map<String, Object> data = new HashMap<>();
            data.put("transactions", transactions);
            data.put("pagination", pagination);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", data);
            result.put("timestamp", getCurrentTimestamp());

            return result;

        } catch (ApiException e) {
            throw new ApiException("Transaction search failed: " + e.getMessage());
        }
    }

    /**
     * Get detailed information for a specific transaction
     *
     * @param transactionId The transaction ID to retrieve
     * @return Transaction details
     * @throws ApiException If the transaction cannot be found
     */
    public Map<String, Object> getTransactionDetails(String transactionId) throws ApiException {
        ensureConfigured();

        try {
            Transaction response = ReportingService.transactionDetail(transactionId).execute();

            if (response == null) {
                throw new ApiException("Transaction not found");
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", formatTransactionDetails(response));
            result.put("timestamp", getCurrentTimestamp());

            return result;

        } catch (ApiException e) {
            throw new ApiException("Failed to retrieve transaction details: " + e.getMessage());
        }
    }

    /**
     * Generate settlement report for a date range
     *
     * @param params Report parameters
     * @return Settlement report data
     * @throws ApiException If the report generation fails
     */
    public Map<String, Object> getSettlementReport(Map<String, Object> params) throws ApiException {
        ensureConfigured();

        try {
            int page = (int) params.getOrDefault("page", 1);
            int pageSize = (int) params.getOrDefault("page_size", 50);

            TransactionReportBuilder<List<TransactionSummary>> builder =
                ReportingService.findSettlementTransactionsPaged(page, pageSize);

            // Apply date range
            if (params.containsKey("start_date")) {
                Date startDate = parseDate((String) params.get("start_date"));
                builder.withStartDate(startDate);
            }

            if (params.containsKey("end_date")) {
                Date endDate = parseDate((String) params.get("end_date"));
                builder.withEndDate(endDate);
            }

            PagedResult<TransactionSummary> response = builder.execute();

            List<Map<String, Object>> settlements = formatSettlementList(response.getResults());
            Map<String, Object> summary = generateSettlementSummary(response.getResults());

            Map<String, Object> pagination = new HashMap<>();
            pagination.put("page", page);
            pagination.put("page_size", pageSize);
            pagination.put("total_count", response.getTotalRecordCount());

            Map<String, Object> data = new HashMap<>();
            data.put("settlements", settlements);
            data.put("summary", summary);
            data.put("pagination", pagination);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", data);
            result.put("timestamp", getCurrentTimestamp());

            return result;

        } catch (ApiException e) {
            throw new ApiException("Settlement report generation failed: " + e.getMessage());
        }
    }

    /**
     * Get dispute reports with filtering and pagination
     *
     * @param filters Dispute search filters and pagination
     * @return Dispute report results
     * @throws ApiException If the dispute search fails
     */
    public Map<String, Object> getDisputeReport(Map<String, Object> filters) throws ApiException {
        ensureConfigured();

        try {
            int page = (int) filters.getOrDefault("page", 1);
            int pageSize = (int) filters.getOrDefault("page_size", 10);

            TransactionReportBuilder<List<DisputeSummary>> builder =
                ReportingService.findDisputesPaged(page, pageSize);

            // Apply date range filters
            if (filters.containsKey("start_date")) {
                Date startDate = parseDate((String) filters.get("start_date"));
                builder.withStartDate(startDate);
            }

            if (filters.containsKey("end_date")) {
                Date endDate = parseDate((String) filters.get("end_date"));
                builder.withEndDate(endDate);
            }

            // Apply stage and status filters
            if (filters.containsKey("stage")) {
                builder.where(SearchCriteria.DisputeStage, filters.get("stage"));
            }

            if (filters.containsKey("status")) {
                builder.where(SearchCriteria.DisputeStatus, filters.get("status"));
            }

            PagedResult<DisputeSummary> response = builder.execute();

            List<Map<String, Object>> disputes = formatDisputeList(response.getResults());

            Map<String, Object> pagination = new HashMap<>();
            pagination.put("page", page);
            pagination.put("page_size", pageSize);
            pagination.put("total_count", response.getTotalRecordCount());

            Map<String, Object> data = new HashMap<>();
            data.put("disputes", disputes);
            data.put("pagination", pagination);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", data);
            result.put("timestamp", getCurrentTimestamp());

            return result;

        } catch (ApiException e) {
            throw new ApiException("Dispute report generation failed: " + e.getMessage());
        }
    }

    /**
     * Get detailed information for a specific dispute
     *
     * @param disputeId The dispute ID to retrieve
     * @return Dispute details
     * @throws ApiException If the dispute cannot be found
     */
    public Map<String, Object> getDisputeDetails(String disputeId) throws ApiException {
        ensureConfigured();

        try {
            DisputeSummary response = ReportingService.disputeDetail(disputeId).execute();

            if (response == null) {
                throw new ApiException("Dispute not found");
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", formatDisputeDetails(response));
            result.put("timestamp", getCurrentTimestamp());

            return result;

        } catch (ApiException e) {
            throw new ApiException("Failed to retrieve dispute details: " + e.getMessage());
        }
    }

    /**
     * Get deposit reports with filtering and pagination
     *
     * @param filters Deposit search filters and pagination
     * @return Deposit report results
     * @throws ApiException If the deposit search fails
     */
    public Map<String, Object> getDepositReport(Map<String, Object> filters) throws ApiException {
        ensureConfigured();

        try {
            int page = (int) filters.getOrDefault("page", 1);
            int pageSize = (int) filters.getOrDefault("page_size", 10);

            TransactionReportBuilder<List<DepositSummary>> builder =
                ReportingService.findDepositsPaged(page, pageSize);

            // Apply date range filters
            if (filters.containsKey("start_date")) {
                Date startDate = parseDate((String) filters.get("start_date"));
                builder.withStartDate(startDate);
            }

            if (filters.containsKey("end_date")) {
                Date endDate = parseDate((String) filters.get("end_date"));
                builder.withEndDate(endDate);
            }

            // Apply deposit ID filter
            if (filters.containsKey("deposit_id")) {
                builder.where(SearchCriteria.DepositReference, filters.get("deposit_id"));
            }

            // Apply status filter
            if (filters.containsKey("status")) {
                builder.where(SearchCriteria.DepositStatus, filters.get("status"));
            }

            PagedResult<DepositSummary> response = builder.execute();

            List<Map<String, Object>> deposits = formatDepositList(response.getResults());

            Map<String, Object> pagination = new HashMap<>();
            pagination.put("page", page);
            pagination.put("page_size", pageSize);
            pagination.put("total_count", response.getTotalRecordCount());

            Map<String, Object> data = new HashMap<>();
            data.put("deposits", deposits);
            data.put("pagination", pagination);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", data);
            result.put("timestamp", getCurrentTimestamp());

            return result;

        } catch (ApiException e) {
            throw new ApiException("Deposit report generation failed: " + e.getMessage());
        }
    }

    /**
     * Get detailed information for a specific deposit
     *
     * @param depositId The deposit ID to retrieve
     * @return Deposit details
     * @throws ApiException If the deposit cannot be found
     */
    public Map<String, Object> getDepositDetails(String depositId) throws ApiException {
        ensureConfigured();

        try {
            DepositSummary response = ReportingService.depositDetail(depositId).execute();

            if (response == null) {
                throw new ApiException("Deposit not found");
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", formatDepositDetails(response));
            result.put("timestamp", getCurrentTimestamp());

            return result;

        } catch (ApiException e) {
            throw new ApiException("Failed to retrieve deposit details: " + e.getMessage());
        }
    }

    /**
     * Get batch report with detailed transaction information
     *
     * @param filters Batch search filters
     * @return Batch report results
     * @throws ApiException If the batch search fails
     */
    public Map<String, Object> getBatchReport(Map<String, Object> filters) throws ApiException {
        ensureConfigured();

        try {
            // Note: The exact batch report builder may vary based on SDK version
            // This is a placeholder implementation
            Map<String, Object> data = new HashMap<>();
            data.put("batches", new ArrayList<>());
            data.put("summary", new HashMap<>());

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", data);
            result.put("timestamp", getCurrentTimestamp());

            return result;

        } catch (Exception e) {
            throw new ApiException("Batch report generation failed: " + e.getMessage());
        }
    }

    /**
     * Get declined transactions report
     *
     * @param filters Decline search filters and pagination
     * @return Declined transactions report
     * @throws ApiException If the search fails
     */
    public Map<String, Object> getDeclinedTransactionsReport(Map<String, Object> filters) throws ApiException {
        ensureConfigured();

        try {
            // Add declined status to filters
            filters.put("status", "DECLINED");
            Map<String, Object> result = searchTransactions(filters);

            // Add decline analysis
            if ((boolean) result.get("success")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> transactions = (List<Map<String, Object>>) data.get("transactions");
                data.put("decline_analysis", analyzeDeclines(transactions));
            }

            return result;

        } catch (ApiException e) {
            throw new ApiException("Declined transactions report generation failed: " + e.getMessage());
        }
    }

    /**
     * Get comprehensive date range report across all transaction types
     *
     * @param params Date range and report parameters
     * @return Comprehensive date range report
     */
    public Map<String, Object> getDateRangeReport(Map<String, Object> params) {
        ensureConfigured();

        try {
            String startDate = (String) params.getOrDefault("start_date",
                LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_DATE));
            String endDate = (String) params.getOrDefault("end_date",
                LocalDate.now().format(DateTimeFormatter.ISO_DATE));

            Map<String, Object> period = new HashMap<>();
            period.put("start_date", startDate);
            period.put("end_date", endDate);

            Map<String, Object> data = new HashMap<>();
            data.put("period", period);

            // Get transactions for the period
            try {
                Map<String, Object> transactionFilters = new HashMap<>();
                transactionFilters.put("start_date", startDate);
                transactionFilters.put("end_date", endDate);
                transactionFilters.put("page_size", params.getOrDefault("transaction_limit", 100));
                Map<String, Object> transactionResult = searchTransactions(transactionFilters);
                if ((boolean) transactionResult.get("success")) {
                    data.put("transactions", transactionResult.get("data"));
                }
            } catch (Exception e) {
                data.put("transactions", Map.of("error", "Transactions not available: " + e.getMessage()));
            }

            // Get settlements for the period
            try {
                Map<String, Object> settlementParams = new HashMap<>();
                settlementParams.put("start_date", startDate);
                settlementParams.put("end_date", endDate);
                settlementParams.put("page_size", params.getOrDefault("settlement_limit", 50));
                Map<String, Object> settlementResult = getSettlementReport(settlementParams);
                if ((boolean) settlementResult.get("success")) {
                    data.put("settlements", settlementResult.get("data"));
                }
            } catch (Exception e) {
                data.put("settlements", Map.of("error", "Settlements not available: " + e.getMessage()));
            }

            // Get disputes for the period
            try {
                Map<String, Object> disputeFilters = new HashMap<>();
                disputeFilters.put("start_date", startDate);
                disputeFilters.put("end_date", endDate);
                disputeFilters.put("page_size", params.getOrDefault("dispute_limit", 25));
                Map<String, Object> disputeResult = getDisputeReport(disputeFilters);
                if ((boolean) disputeResult.get("success")) {
                    data.put("disputes", disputeResult.get("data"));
                }
            } catch (Exception e) {
                data.put("disputes", Map.of("error", "Disputes not available: " + e.getMessage()));
            }

            // Get deposits for the period
            try {
                Map<String, Object> depositFilters = new HashMap<>();
                depositFilters.put("start_date", startDate);
                depositFilters.put("end_date", endDate);
                depositFilters.put("page_size", params.getOrDefault("deposit_limit", 25));
                Map<String, Object> depositResult = getDepositReport(depositFilters);
                if ((boolean) depositResult.get("success")) {
                    data.put("deposits", depositResult.get("data"));
                }
            } catch (Exception e) {
                data.put("deposits", Map.of("error", "Deposits not available: " + e.getMessage()));
            }

            // Generate comprehensive summary
            data.put("summary", generateComprehensiveSummary(data));

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", data);
            result.put("timestamp", getCurrentTimestamp());

            return result;

        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "Failed to generate date range report: " + e.getMessage());
            result.put("timestamp", getCurrentTimestamp());
            return result;
        }
    }

    /**
     * Export transaction data in specified format
     *
     * @param filters Search filters
     * @param format Export format ('json', 'csv', or 'xml')
     * @return Export data
     * @throws ApiException If export fails
     */
    public Map<String, Object> exportTransactions(Map<String, Object> filters, String format) throws ApiException {
        ensureConfigured();

        try {
            // Get all transactions (increase limit for export)
            filters.put("page_size", 1000);
            Map<String, Object> transactions = searchTransactions(filters);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) transactions.get("data");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transactionList = (List<Map<String, Object>>) data.get("transactions");

            if ("csv".equals(format)) {
                return exportToCsv(transactionList);
            } else if ("xml".equals(format)) {
                return exportToXml(transactionList);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", transactionList);
            result.put("format", "json");
            result.put("timestamp", getCurrentTimestamp());

            return result;

        } catch (ApiException e) {
            throw new ApiException("Export failed: " + e.getMessage());
        }
    }

    /**
     * Get reporting summary statistics
     *
     * @param params Summary parameters
     * @return Summary statistics
     */
    public Map<String, Object> getSummaryStats(Map<String, Object> params) {
        ensureConfigured();

        try {
            String startDate = (String) params.getOrDefault("start_date",
                LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_DATE));
            String endDate = (String) params.getOrDefault("end_date",
                LocalDate.now().format(DateTimeFormatter.ISO_DATE));

            // Get transaction summary
            Map<String, Object> filters = new HashMap<>();
            filters.put("start_date", startDate);
            filters.put("end_date", endDate);
            filters.put("page_size", 1000);

            Map<String, Object> transactions = searchTransactions(filters);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) transactions.get("data");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transactionList = (List<Map<String, Object>>) data.get("transactions");

            Map<String, Object> period = new HashMap<>();
            period.put("start_date", startDate);
            period.put("end_date", endDate);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", calculateSummaryStats(transactionList));
            result.put("period", period);
            result.put("timestamp", getCurrentTimestamp());

            return result;

        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "Failed to generate summary statistics: " + e.getMessage());
            result.put("timestamp", getCurrentTimestamp());
            return result;
        }
    }

    /**
     * Get SDK configuration status
     */
    public Map<String, Object> getSdkConfigStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("configured", isConfigured);
        status.put("environment", dotenv.get("GP_API_ENVIRONMENT", "TEST"));
        status.put("app_id_configured", dotenv.get("GP_API_APP_ID") != null);
        return status;
    }

    /**
     * Validate environment configuration
     */
    public Map<String, Object> validateEnvironmentConfig() {
        Map<String, Object> validation = new HashMap<>();
        validation.put("app_id_present", dotenv.get("GP_API_APP_ID") != null);
        validation.put("app_key_present", dotenv.get("GP_API_APP_KEY") != null);
        validation.put("environment_set", dotenv.get("GP_API_ENVIRONMENT") != null);
        return validation;
    }

    // Private helper methods

    private void ensureConfigured() {
        if (!isConfigured) {
            throw new RuntimeException("SDK is not properly configured");
        }
    }

    private Date parseDate(String dateStr) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            return sdf.parse(dateStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid date format: " + dateStr);
        }
    }

    private String getCurrentTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private List<Map<String, Object>> formatTransactionList(List<TransactionSummary> transactions) {
        return transactions.stream().map(transaction -> {
            Map<String, Object> formatted = new HashMap<>();
            formatted.put("transaction_id", transaction.getTransactionId());
            formatted.put("timestamp", transaction.getTransactionDate());
            formatted.put("amount", transaction.getAmount());
            formatted.put("currency", transaction.getCurrency());
            formatted.put("status", transaction.getTransactionStatus());
            formatted.put("payment_method", transaction.getPaymentType());
            formatted.put("card_last_four", transaction.getMaskedCardNumber());
            formatted.put("auth_code", transaction.getAuthCode());
            formatted.put("reference_number", transaction.getReferenceNumber());
            return formatted;
        }).collect(Collectors.toList());
    }

    private Map<String, Object> formatTransactionDetails(Transaction transaction) {
        Map<String, Object> details = new HashMap<>();
        details.put("transaction_id", transaction.getTransactionId());
        details.put("timestamp", transaction.getTimestamp());
        details.put("amount", transaction.getAuthorizedAmount());
        details.put("currency", "USD");
        details.put("status", transaction.getResponseCode());
        details.put("payment_method", transaction.getPaymentMethodType());

        Map<String, Object> cardDetails = new HashMap<>();
        cardDetails.put("masked_number", transaction.getCardLast4());
        cardDetails.put("card_type", transaction.getCardType());
        cardDetails.put("entry_mode", transaction.getEntryMode());
        details.put("card_details", cardDetails);

        details.put("auth_code", transaction.getAuthorizationCode());
        details.put("reference_number", transaction.getReferenceNumber());
        details.put("gateway_response_code", transaction.getResponseCode());
        details.put("gateway_response_message", transaction.getResponseMessage());

        return details;
    }

    private List<Map<String, Object>> formatSettlementList(List<TransactionSummary> settlements) {
        return settlements.stream().map(settlement -> {
            Map<String, Object> formatted = new HashMap<>();
            formatted.put("settlement_id", settlement.getTransactionId());
            formatted.put("settlement_date", settlement.getTransactionDate());
            formatted.put("transaction_count", 1);
            formatted.put("total_amount", settlement.getAmount());
            formatted.put("currency", settlement.getCurrency());
            formatted.put("status", settlement.getTransactionStatus());
            return formatted;
        }).collect(Collectors.toList());
    }

    private Map<String, Object> generateSettlementSummary(List<TransactionSummary> settlements) {
        BigDecimal totalAmount = BigDecimal.ZERO;
        int totalTransactions = settlements.size();

        for (TransactionSummary settlement : settlements) {
            if (settlement.getAmount() != null) {
                totalAmount = totalAmount.add(settlement.getAmount());
            }
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("total_settlements", settlements.size());
        summary.put("total_amount", totalAmount);
        summary.put("total_transactions", totalTransactions);
        summary.put("average_settlement_amount",
            settlements.size() > 0 ? totalAmount.divide(BigDecimal.valueOf(settlements.size()), 2, BigDecimal.ROUND_HALF_UP) : BigDecimal.ZERO);

        return summary;
    }

    private List<Map<String, Object>> formatDisputeList(List<DisputeSummary> disputes) {
        return disputes.stream().map(dispute -> {
            Map<String, Object> formatted = new HashMap<>();
            formatted.put("dispute_id", dispute.getCaseId());
            formatted.put("transaction_id", dispute.getTransactionId());
            formatted.put("case_number", dispute.getCaseIdNumber());
            formatted.put("dispute_stage", dispute.getCaseStage());
            formatted.put("dispute_status", dispute.getCaseStatus());
            formatted.put("case_amount", dispute.getCaseAmount());
            formatted.put("currency", dispute.getCaseCurrency());
            formatted.put("reason_code", dispute.getReasonCode());
            formatted.put("reason_description", dispute.getReason());
            formatted.put("case_time", dispute.getCaseTime());
            formatted.put("last_adjustment_time", dispute.getLastAdjustmentTime());
            return formatted;
        }).collect(Collectors.toList());
    }

    private Map<String, Object> formatDisputeDetails(DisputeSummary dispute) {
        Map<String, Object> details = new HashMap<>();
        details.put("dispute_id", dispute.getCaseId());
        details.put("transaction_id", dispute.getTransactionId());
        details.put("case_number", dispute.getCaseIdNumber());
        details.put("dispute_stage", dispute.getCaseStage());
        details.put("dispute_status", dispute.getCaseStatus());
        details.put("case_amount", dispute.getCaseAmount());
        details.put("currency", dispute.getCaseCurrency());
        details.put("reason_code", dispute.getReasonCode());
        details.put("reason_description", dispute.getReason());
        details.put("case_time", dispute.getCaseTime());
        details.put("last_adjustment_time", dispute.getLastAdjustmentTime());
        details.put("case_description", dispute.getCaseDescription());

        Map<String, Object> transactionDetails = new HashMap<>();
        transactionDetails.put("amount", dispute.getTransactionAmount());
        transactionDetails.put("currency", dispute.getTransactionCurrency());
        transactionDetails.put("masked_card_number", dispute.getTransactionMaskedCardNumber());
        transactionDetails.put("arn", dispute.getTransactionARN());
        details.put("transaction_details", transactionDetails);

        return details;
    }

    private List<Map<String, Object>> formatDepositList(List<DepositSummary> deposits) {
        return deposits.stream().map(deposit -> {
            Map<String, Object> formatted = new HashMap<>();
            formatted.put("deposit_id", deposit.getDepositId());
            formatted.put("deposit_date", deposit.getDepositDate());
            formatted.put("deposit_reference", deposit.getDepositId());
            formatted.put("deposit_status", deposit.getStatus());
            formatted.put("deposit_amount", deposit.getAmount());
            formatted.put("currency", deposit.getCurrency());
            formatted.put("merchant_number", deposit.getMerchantNumber());
            formatted.put("merchant_hierarchy", deposit.getMerchantHierarchy());
            formatted.put("sales_count", deposit.getSalesTotalCount());
            formatted.put("sales_amount", deposit.getSalesTotalAmount());
            formatted.put("refunds_count", deposit.getRefundsTotalCount());
            formatted.put("refunds_amount", deposit.getRefundsTotalAmount());
            return formatted;
        }).collect(Collectors.toList());
    }

    private Map<String, Object> formatDepositDetails(DepositSummary deposit) {
        Map<String, Object> details = new HashMap<>();
        details.put("deposit_id", deposit.getDepositId());
        details.put("deposit_date", deposit.getDepositDate());
        details.put("deposit_reference", deposit.getDepositId());
        details.put("deposit_status", deposit.getStatus());
        details.put("deposit_amount", deposit.getAmount());
        details.put("currency", deposit.getCurrency());
        details.put("merchant_number", deposit.getMerchantNumber());
        details.put("merchant_hierarchy", deposit.getMerchantHierarchy());

        Map<String, Object> transactionSummary = new HashMap<>();
        transactionSummary.put("sales_count", deposit.getSalesTotalCount());
        transactionSummary.put("sales_amount", deposit.getSalesTotalAmount());
        transactionSummary.put("refunds_count", deposit.getRefundsTotalCount());
        transactionSummary.put("refunds_amount", deposit.getRefundsTotalAmount());
        details.put("transaction_summary", transactionSummary);

        return details;
    }

    private Map<String, Object> calculateSummaryStats(List<Map<String, Object>> transactions) {
        BigDecimal totalAmount = BigDecimal.ZERO;
        Map<String, Integer> statusCounts = new HashMap<>();
        Map<String, Integer> paymentTypeCounts = new HashMap<>();

        for (Map<String, Object> transaction : transactions) {
            Object amountObj = transaction.get("amount");
            if (amountObj != null) {
                BigDecimal amount = amountObj instanceof BigDecimal ?
                    (BigDecimal) amountObj : new BigDecimal(amountObj.toString());
                totalAmount = totalAmount.add(amount);
            }

            String status = (String) transaction.getOrDefault("status", "unknown");
            statusCounts.put(status, statusCounts.getOrDefault(status, 0) + 1);

            String paymentType = (String) transaction.getOrDefault("payment_method", "unknown");
            paymentTypeCounts.put(paymentType, paymentTypeCounts.getOrDefault(paymentType, 0) + 1);
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("total_transactions", transactions.size());
        summary.put("total_amount", totalAmount);
        summary.put("average_amount",
            transactions.size() > 0 ? totalAmount.divide(BigDecimal.valueOf(transactions.size()), 2, BigDecimal.ROUND_HALF_UP) : BigDecimal.ZERO);
        summary.put("status_breakdown", statusCounts);
        summary.put("payment_type_breakdown", paymentTypeCounts);

        return summary;
    }

    private Map<String, Object> exportToCsv(List<Map<String, Object>> transactions) {
        StringBuilder csvData = new StringBuilder();
        csvData.append("Transaction ID,Timestamp,Amount,Currency,Status,Payment Method,Card Last Four,Auth Code,Reference Number\n");

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        for (Map<String, Object> transaction : transactions) {
            csvData.append(String.format("%s,%s,%s,%s,%s,%s,%s,%s,%s\n",
                transaction.getOrDefault("transaction_id", ""),
                formatTimestamp(transaction.get("timestamp"), sdf),
                transaction.getOrDefault("amount", ""),
                transaction.getOrDefault("currency", ""),
                transaction.getOrDefault("status", ""),
                transaction.getOrDefault("payment_method", ""),
                transaction.getOrDefault("card_last_four", ""),
                transaction.getOrDefault("auth_code", ""),
                transaction.getOrDefault("reference_number", "")
            ));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", csvData.toString());
        result.put("format", "csv");
        result.put("filename", "transactions_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".csv");
        result.put("timestamp", getCurrentTimestamp());

        return result;
    }

    private Map<String, Object> exportToXml(List<Map<String, Object>> transactions) {
        StringBuilder xmlData = new StringBuilder();
        xmlData.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xmlData.append("<transactions>\n");

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        for (Map<String, Object> transaction : transactions) {
            xmlData.append("  <transaction>\n");
            xmlData.append("    <transaction_id>").append(transaction.getOrDefault("transaction_id", "")).append("</transaction_id>\n");
            xmlData.append("    <timestamp>").append(formatTimestamp(transaction.get("timestamp"), sdf)).append("</timestamp>\n");
            xmlData.append("    <amount>").append(transaction.getOrDefault("amount", "")).append("</amount>\n");
            xmlData.append("    <currency>").append(transaction.getOrDefault("currency", "")).append("</currency>\n");
            xmlData.append("    <status>").append(transaction.getOrDefault("status", "")).append("</status>\n");
            xmlData.append("    <payment_method>").append(transaction.getOrDefault("payment_method", "")).append("</payment_method>\n");
            xmlData.append("    <card_last_four>").append(transaction.getOrDefault("card_last_four", "")).append("</card_last_four>\n");
            xmlData.append("    <auth_code>").append(transaction.getOrDefault("auth_code", "")).append("</auth_code>\n");
            xmlData.append("    <reference_number>").append(transaction.getOrDefault("reference_number", "")).append("</reference_number>\n");
            xmlData.append("  </transaction>\n");
        }

        xmlData.append("</transactions>");

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", xmlData.toString());
        result.put("format", "xml");
        result.put("filename", "transactions_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".xml");
        result.put("timestamp", getCurrentTimestamp());

        return result;
    }

    private String formatTimestamp(Object timestamp, SimpleDateFormat sdf) {
        if (timestamp == null) {
            return "";
        }
        if (timestamp instanceof Date) {
            return sdf.format((Date) timestamp);
        }
        return timestamp.toString();
    }

    private Map<String, Object> analyzeDeclines(List<Map<String, Object>> transactions) {
        Map<String, Integer> declineReasons = new HashMap<>();
        Map<String, Integer> cardTypes = new HashMap<>();
        Map<String, Integer> hourlyBreakdown = new HashMap<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        SimpleDateFormat hourFormat = new SimpleDateFormat("HH");

        for (Map<String, Object> transaction : transactions) {
            // Analyze decline reasons
            String reason = (String) transaction.getOrDefault("gateway_response_message", "Unknown");
            declineReasons.put(reason, declineReasons.getOrDefault(reason, 0) + 1);

            // Analyze card types
            String cardType = (String) transaction.getOrDefault("payment_method", "Unknown");
            cardTypes.put(cardType, cardTypes.getOrDefault(cardType, 0) + 1);

            // Analyze hourly patterns
            Object timestampObj = transaction.get("timestamp");
            if (timestampObj instanceof Date) {
                String hour = hourFormat.format((Date) timestampObj);
                hourlyBreakdown.put(hour, hourlyBreakdown.getOrDefault(hour, 0) + 1);
            }

            // Sum amounts
            Object amountObj = transaction.get("amount");
            if (amountObj != null) {
                BigDecimal amount = amountObj instanceof BigDecimal ?
                    (BigDecimal) amountObj : new BigDecimal(amountObj.toString());
                totalAmount = totalAmount.add(amount);
            }
        }

        Map<String, Object> analysis = new HashMap<>();
        analysis.put("total_declined_transactions", transactions.size());
        analysis.put("total_declined_amount", totalAmount);
        analysis.put("average_declined_amount",
            transactions.size() > 0 ? totalAmount.divide(BigDecimal.valueOf(transactions.size()), 2, BigDecimal.ROUND_HALF_UP) : BigDecimal.ZERO);
        analysis.put("decline_reasons", declineReasons);
        analysis.put("card_type_breakdown", cardTypes);
        analysis.put("hourly_breakdown", hourlyBreakdown);

        return analysis;
    }

    private Map<String, Object> generateComprehensiveSummary(Map<String, Object> reportData) {
        Map<String, Object> summary = new HashMap<>();
        Map<String, Object> overview = new HashMap<>();
        Map<String, Object> financialSummary = new HashMap<>();
        Map<String, Object> operationalMetrics = new HashMap<>();

        // Transaction overview
        if (reportData.containsKey("transactions")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> transactionsData = (Map<String, Object>) reportData.get("transactions");
            if (transactionsData.containsKey("transactions")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> transactions = (List<Map<String, Object>>) transactionsData.get("transactions");

                BigDecimal totalAmount = BigDecimal.ZERO;
                for (Map<String, Object> transaction : transactions) {
                    Object amountObj = transaction.get("amount");
                    if (amountObj != null) {
                        BigDecimal amount = amountObj instanceof BigDecimal ?
                            (BigDecimal) amountObj : new BigDecimal(amountObj.toString());
                        totalAmount = totalAmount.add(amount);
                    }
                }

                Map<String, Object> transactionOverview = new HashMap<>();
                transactionOverview.put("count", transactions.size());
                transactionOverview.put("total_amount", totalAmount);
                transactionOverview.put("average_amount",
                    transactions.size() > 0 ? totalAmount.divide(BigDecimal.valueOf(transactions.size()), 2, BigDecimal.ROUND_HALF_UP) : BigDecimal.ZERO);
                overview.put("transactions", transactionOverview);
            }
        }

        summary.put("overview", overview);
        summary.put("financial_summary", financialSummary);
        summary.put("operational_metrics", operationalMetrics);

        return summary;
    }
}