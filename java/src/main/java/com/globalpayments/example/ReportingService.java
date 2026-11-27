package com.globalpayments.example;

import com.global.api.ServicesContainer;
import com.global.api.builders.TransactionReportBuilder;
import com.global.api.entities.TransactionSummary;
import com.global.api.entities.enums.DepositStatus;
import com.global.api.entities.enums.PaymentType;
import com.global.api.entities.enums.Target;
import com.global.api.entities.exceptions.ApiException;
import com.global.api.entities.exceptions.ConfigurationException;
import com.global.api.entities.exceptions.GatewayException;
import com.global.api.entities.gpApi.GpApiRequest;
import com.global.api.entities.reporting.DepositSummary;
import com.global.api.entities.reporting.DepositSummaryPaged;
import com.global.api.entities.reporting.DisputeSummary;
import com.global.api.entities.reporting.SearchCriteria;
import com.global.api.entities.reporting.TransactionSummaryPaged;
import com.global.api.gateways.GpApiConnector;
import com.global.api.mapping.GpApiMapping;
import com.global.api.serviceConfigs.GpApiConfig;
import com.global.api.utils.JsonDoc;
import io.github.cdimascio.dotenv.Dotenv;
import org.joda.time.DateTime;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Global Payments Reporting Service
 *
 * This service class provides comprehensive reporting functionality for
 * Global Payments transactions including search, filtering, and data export,
 * read from GP API with the Global Payments Java SDK.
 *
 * @author Global Payments
 * @version 1.0
 */
public class ReportingService {

    /** .env is optional: Docker and CI pass the variables in the environment */
    private static final Dotenv DOTENV = Dotenv.configure().ignoreIfMissing().load();

    private static final DateTimeFormatter NOW_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Constructor - Initialize and configure the SDK
     *
     * @throws ConfigurationException If the GP API credentials are missing
     */
    public ReportingService() throws ConfigurationException {
        for (String name : List.of("GP_API_APP_ID", "GP_API_APP_KEY")) {
            if (env(name).isEmpty()) {
                throw new ConfigurationException("Failed to configure SDK: Missing required environment variable: " + name);
            }
        }
        configureGpApiSdk();
    }

    /**
     * Read an environment variable from the environment or .env
     */
    static String env(String name) {
        String value = DOTENV.get(name);
        return value == null ? "" : value;
    }

    /**
     * Configure the Global Payments API SDK
     */
    private void configureGpApiSdk() throws ConfigurationException {
        GpApiConfig config = new GpApiConfig();

        // Set credentials from environment
        config.setAppId(env("GP_API_APP_ID"));
        config.setAppKey(env("GP_API_APP_KEY"));

        // Sandbox unless GP_API_ENVIRONMENT=PRODUCTION
        if ("PRODUCTION".equalsIgnoreCase(env("GP_API_ENVIRONMENT"))) {
            config.setEnvironment(com.global.api.entities.enums.Environment.PRODUCTION);
        } else {
            config.setEnvironment(com.global.api.entities.enums.Environment.TEST);
        }

        // Set channel for ecommerce transactions
        config.setChannel(com.global.api.entities.enums.Channel.CardNotPresent);

        ServicesContainer.configureService(config);
    }

    /**
     * Search transactions with filters and pagination
     *
     * The list is read through the SDK's GP API connector and mapped with the
     * SDK's GpApiMapping, because the SDK report builder fails on a page that
     * holds a 3DS exemption it has no enum for (LOW_VALUE), and reports the
     * page size as the total when GP API leaves the total out (page 2 on).
     *
     * @param filters Search filters and pagination parameters
     * @return Transaction search results
     * @throws ApiException If the search request fails
     */
    public Map<String, Object> searchTransactions(Map<String, String> filters) throws ApiException {
        int page = intValue(filters, "page", 1);
        int pageSize = intValue(filters, "page_size", 10);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("page", String.valueOf(page));
        query.put("page_size", String.valueOf(pageSize));
        query.put("from_time_created", filters.get("start_date"));
        query.put("to_time_created", filters.get("end_date"));
        query.put("id", filters.get("transaction_id"));
        query.put("number_last4", filters.get("card_last_four"));
        if (has(filters, "payment_type")) {
            PaymentType paymentType = switch (filters.get("payment_type").toLowerCase()) {
                case "sale" -> PaymentType.Sale;
                case "refund" -> PaymentType.Refund;
                default -> throw new IllegalArgumentException("Invalid payment_type. Supported values: sale, refund");
            };
            query.put("type", paymentType.getValue());
        }

        JsonDoc doc;
        try {
            doc = gpApiGet("/transactions", query);
        } catch (ApiException e) {
            throw reportError("Transaction search failed", e);
        }

        List<Map<String, Object>> transactions = new ArrayList<>();
        for (JsonDoc row : rows(doc, "transactions")) {
            transactions.add(formatTransaction(mapTransaction(row)));
        }

        // GP API only filters on an exact amount, so the range is applied here
        Double min = doubleValue(filters, "amount_min");
        if (min != null) {
            transactions = filter(transactions, t -> (double) t.get("amount") >= min);
        }
        Double max = doubleValue(filters, "amount_max");
        if (max != null) {
            transactions = filter(transactions, t -> (double) t.get("amount") <= max);
        }

        // Status is not a search filter in this sample, it is applied to the page
        if (has(filters, "status")) {
            String status = filters.get("status").toUpperCase();
            transactions = filter(transactions, t -> status.equals(String.valueOf(t.get("status")).toUpperCase()));
        }

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", page);
        pagination.put("page_size", pageSize);
        pagination.put("total_count", transactions.size());
        pagination.put("original_total_count", totalCount(doc));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transactions", transactions);
        data.put("pagination", pagination);
        return success(data);
    }

    /**
     * Get detailed information for a specific transaction
     *
     * The SDK's transaction mapping leaves out the gateway result code, so the
     * transaction is read through the SDK's GP API connector and mapped with
     * the SDK's GpApiMapping.
     *
     * @param transactionId The transaction ID to retrieve
     * @return Transaction details
     * @throws ApiException If the transaction cannot be found
     */
    public Map<String, Object> getTransactionDetails(String transactionId) throws ApiException {
        JsonDoc doc;
        try {
            doc = gpApiGet("/transactions/" + encode(transactionId), Map.of());
        } catch (ApiException e) {
            throw reportError("Failed to retrieve transaction details", e);
        }

        TransactionSummary summary = mapTransaction(doc);
        Map<String, Object> info = formatTransaction(summary);

        Map<String, Object> cardDetails = new LinkedHashMap<>();
        cardDetails.put("masked_number", info.get("card_last_four"));
        cardDetails.put("card_type", text(summary.getCardType()));
        cardDetails.put("entry_mode", text(summary.getEntryMode()));

        JsonDoc paymentMethod = doc.get("payment_method");

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("transaction_id", info.get("transaction_id"));
        details.put("timestamp", info.get("timestamp"));
        details.put("amount", info.get("amount"));
        details.put("currency", info.get("currency"));
        details.put("status", info.get("status"));
        details.put("payment_method", info.get("payment_method"));
        details.put("card_details", cardDetails);
        details.put("auth_code", info.get("auth_code"));
        details.put("reference_number", info.get("reference_number"));
        details.put("gateway_response_code", paymentMethod != null ? text(paymentMethod.getString("result")) : "");
        details.put("gateway_response_message", text(summary.getGatewayResponseMessage()));
        return success(details);
    }

    /**
     * Generate settlement report for a date range
     *
     * @param params Report parameters
     * @return Settlement report data
     * @throws ApiException If the report request fails
     */
    public Map<String, Object> getSettlementReport(Map<String, String> params) throws ApiException {
        int page = intValue(params, "page", 1);
        int pageSize = intValue(params, "page_size", 50);

        TransactionReportBuilder<TransactionSummaryPaged> builder =
                com.global.api.services.ReportingService.findSettlementTransactionsPaged(page, pageSize);
        if (has(params, "start_date")) {
            builder.where(SearchCriteria.StartDate, parseDate(params.get("start_date")));
        }
        if (has(params, "end_date")) {
            builder.where(SearchCriteria.EndDate, parseDate(params.get("end_date")));
        }

        TransactionSummaryPaged response;
        try {
            response = builder.execute();
        } catch (ApiException e) {
            throw reportError("Settlement report generation failed", e);
        }

        // GP API returns one settled transaction per row, so each row counts one transaction
        List<Map<String, Object>> settlements = new ArrayList<>();
        for (TransactionSummary s : response.getResults()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("settlement_id", text(s.getTransactionId()));
            row.put("settlement_date", s.getDepositDate() != null ? dateString(s.getDepositDate()) : isoString(s.getTransactionDate()));
            row.put("transaction_count", 1);
            row.put("total_amount", amount(s.getAmount()));
            row.put("currency", currency(s.getCurrency()));
            row.put("status", text(s.getTransactionStatus()));
            settlements.add(row);
        }

        double totalAmount = sum(settlements, "total_amount");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_settlements", settlements.size());
        summary.put("total_amount", totalAmount);
        summary.put("total_transactions", settlements.size());
        summary.put("average_settlement_amount", settlements.isEmpty() ? 0.0 : totalAmount / settlements.size());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("settlements", settlements);
        data.put("summary", summary);
        data.put("pagination", pagination(page, pageSize, response.getTotalRecordCount()));
        return success(data);
    }

    /**
     * Get dispute report with filtering and pagination
     *
     * The SDK takes the stage and status as enums, and its GOODFAITH stage is
     * not a value GP API accepts (GOOD_FAITH), so the list is read through the
     * SDK's GP API connector and mapped with the SDK's GpApiMapping.
     *
     * @param filters Dispute filters and pagination
     * @return Dispute report data
     * @throws ApiException If the report request fails
     */
    public Map<String, Object> getDisputeReport(Map<String, String> filters) throws ApiException {
        int page = intValue(filters, "page", 1);
        int pageSize = intValue(filters, "page_size", 10);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("page", String.valueOf(page));
        query.put("page_size", String.valueOf(pageSize));
        query.put("from_stage_time_created", filters.get("start_date"));
        query.put("to_stage_time_created", filters.get("end_date"));
        query.put("stage", upper(filters.get("stage")));
        query.put("status", upper(filters.get("status")));

        JsonDoc doc;
        try {
            doc = gpApiGet("/disputes", query);
        } catch (ApiException e) {
            throw reportError("Dispute report generation failed", e);
        }

        List<Map<String, Object>> disputes = new ArrayList<>();
        for (JsonDoc row : rows(doc, "disputes")) {
            disputes.add(formatDispute(GpApiMapping.mapDisputeSummary(row), row));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("disputes", disputes);
        data.put("pagination", pagination(page, pageSize, totalCount(doc)));
        return success(data);
    }

    /**
     * Get detailed information for a specific dispute
     *
     * The SDK's dispute mapping leaves out the transaction amount and currency,
     * so the dispute is read through the SDK's GP API connector.
     *
     * @param disputeId The dispute ID to retrieve
     * @return Dispute details
     * @throws ApiException If the dispute cannot be found
     */
    public Map<String, Object> getDisputeDetails(String disputeId) throws ApiException {
        JsonDoc doc;
        try {
            doc = gpApiGet("/disputes/" + encode(disputeId), Map.of());
        } catch (ApiException e) {
            throw reportError("Failed to retrieve dispute details", e);
        }

        DisputeSummary dispute = GpApiMapping.mapDisputeSummary(doc);
        Map<String, Object> details = formatDispute(dispute, doc);

        List<Map<String, Object>> documents = new ArrayList<>();
        if (dispute.getDocuments() != null) {
            dispute.getDocuments().forEach(d -> {
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("id", text(d.getId()));
                document.put("type", text(d.getType()));
                documents.add(document);
            });
        }

        Map<String, Object> transactionDetails = new LinkedHashMap<>();
        JsonDoc transaction = doc.get("transaction");
        transactionDetails.put("amount", transaction != null ? amount(transaction.getAmount("amount")) : 0.0);
        transactionDetails.put("currency", currency(transaction != null ? transaction.getString("currency") : null));
        transactionDetails.put("masked_card_number", disputeMaskedCard(doc));
        transactionDetails.put("arn", text(dispute.getTransactionARN()));

        details.put("case_description", text(doc.getString("description")));
        details.put("documents", documents);
        details.put("transaction_details", transactionDetails);
        return success(details);
    }

    /**
     * Get deposit report with filtering and pagination
     *
     * @param filters Deposit filters and pagination
     * @return Deposit report data
     * @throws ApiException If the report request fails
     */
    public Map<String, Object> getDepositReport(Map<String, String> filters) throws ApiException {
        int page = intValue(filters, "page", 1);
        int pageSize = intValue(filters, "page_size", 10);

        TransactionReportBuilder<DepositSummaryPaged> builder =
                com.global.api.services.ReportingService.findDepositsPaged(page, pageSize);
        if (has(filters, "start_date")) {
            builder.where(SearchCriteria.StartDate, parseDate(filters.get("start_date")));
        }
        if (has(filters, "end_date")) {
            builder.where(SearchCriteria.EndDate, parseDate(filters.get("end_date")));
        }
        if (has(filters, "deposit_id")) {
            // The SDK sends the deposit reference as the GP API deposit id (SearchCriteria.DepositId is not read)
            builder.withDepositReference(filters.get("deposit_id"));
        }
        if (has(filters, "status")) {
            builder.where(SearchCriteria.DepositStatus, depositStatus(filters.get("status")));
        }

        DepositSummaryPaged response;
        try {
            response = builder.execute();
        } catch (ApiException e) {
            throw reportError("Deposit report generation failed", e);
        }

        List<Map<String, Object>> deposits = new ArrayList<>();
        for (DepositSummary deposit : response.getResults()) {
            Map<String, Object> info = formatDeposit(deposit);
            info.put("sales_count", deposit.getSalesTotalCount());
            info.put("sales_amount", amount(deposit.getSalesTotalAmount()));
            info.put("refunds_count", deposit.getRefundsTotalCount());
            info.put("refunds_amount", amount(deposit.getRefundsTotalAmount()));
            deposits.add(info);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deposits", deposits);
        data.put("pagination", pagination(page, pageSize, response.getTotalRecordCount()));
        return success(data);
    }

    /** The SDK deposit status for a GP API status such as FUNDED or DELAYED */
    private static DepositStatus depositStatus(String value) {
        for (DepositStatus status : DepositStatus.values()) {
            if (status.getValue(Target.GP_API).equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid status. Supported values: funded, split funding, delayed, reserved, irreg, released");
    }

    /**
     * Get detailed information for a specific deposit
     *
     * @param depositId The deposit ID to retrieve
     * @return Deposit details
     * @throws ApiException If the deposit cannot be found
     */
    public Map<String, Object> getDepositDetails(String depositId) throws ApiException {
        DepositSummary deposit;
        try {
            deposit = com.global.api.services.ReportingService.depositDetail(depositId).execute();
        } catch (ApiException e) {
            throw reportError("Failed to retrieve deposit details", e);
        }

        Map<String, Object> details = formatDeposit(deposit);

        Map<String, Object> bankAccount = new LinkedHashMap<>();
        bankAccount.put("masked_account_number", text(deposit.getAccountNumber()));
        bankAccount.put("bank_name", "");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sales_count", deposit.getSalesTotalCount());
        summary.put("sales_amount", amount(deposit.getSalesTotalAmount()));
        summary.put("refunds_count", deposit.getRefundsTotalCount());
        summary.put("refunds_amount", amount(deposit.getRefundsTotalAmount()));
        summary.put("chargebacks_count", deposit.getChargebackTotalCount());
        summary.put("chargebacks_amount", amount(deposit.getChargebackTotalAmount()));
        summary.put("adjustments_count", deposit.getAdjustmentTotalCount());
        summary.put("adjustments_amount", amount(deposit.getAdjustmentTotalAmount()));

        details.put("bank_account", bankAccount);
        details.put("transaction_summary", summary);
        return success(details);
    }

    /**
     * Get batch report. GP API has no batch report endpoint, so this answers
     * with an API error, the same in every language of this sample.
     *
     * @throws ApiException Always
     */
    public Map<String, Object> getBatchReport(Map<String, String> filters) throws ApiException {
        throw new ApiException("Batch report generation failed: batch reports are not available through GP API");
    }

    /**
     * Get declined transactions report
     *
     * @param filters Search filters
     * @return Declined transactions with an analysis
     * @throws ApiException If the search request fails
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDeclinedTransactionsReport(Map<String, String> filters) throws ApiException {
        Map<String, String> declineFilters = new HashMap<>(filters);
        declineFilters.put("status", "DECLINED");

        Map<String, Object> result;
        try {
            result = searchTransactions(declineFilters);
        } catch (ApiException e) {
            throw reportError("Declined transactions report generation failed", e);
        }

        Map<String, Object> data = (Map<String, Object>) result.get("data");
        data.put("decline_analysis", analyzeDeclines((List<Map<String, Object>>) data.get("transactions")));
        return result;
    }

    /**
     * Get comprehensive date range report across transactions, settlements,
     * disputes and deposits. A section that fails carries its error.
     *
     * @param params Date range and report parameters
     * @return Comprehensive date range report
     */
    public Map<String, Object> getDateRangeReport(Map<String, String> params) {
        String startDate = has(params, "start_date") ? params.get("start_date") : LocalDate.now().minusDays(30).toString();
        String endDate = has(params, "end_date") ? params.get("end_date") : LocalDate.now().toString();

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("start_date", startDate);
        period.put("end_date", endDate);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", period);

        record Section(String key, String label, String limit, int def, ReportCall call) {}
        List<Section> sections = List.of(
                new Section("transactions", "Transactions", "transaction_limit", 100, this::searchTransactions),
                new Section("settlements", "Settlements", "settlement_limit", 50, this::getSettlementReport),
                new Section("disputes", "Disputes", "dispute_limit", 25, this::getDisputeReport),
                new Section("deposits", "Deposits", "deposit_limit", 25, this::getDepositReport));

        for (Section section : sections) {
            Map<String, String> filters = new HashMap<>();
            filters.put("start_date", startDate);
            filters.put("end_date", endDate);
            filters.put("page_size", String.valueOf(intValue(params, section.limit(), section.def())));
            try {
                data.put(section.key(), section.call().run(filters).get("data"));
            } catch (ApiException e) {
                data.put(section.key(), Map.of("error", section.label() + " not available: " + e.getMessage()));
            } catch (RuntimeException e) {
                System.err.println(section.label() + " not available for the date range report: " + e);
                data.put(section.key(), Map.of("error", section.label() + " not available"));
            }
        }

        data.put("summary", generateComprehensiveSummary(data));
        return success(data);
    }

    @FunctionalInterface
    private interface ReportCall {
        Map<String, Object> run(Map<String, String> filters) throws ApiException;
    }

    /**
     * Export transaction data in specified format
     *
     * @param filters Search filters
     * @param format Export format (json, csv, or xml)
     * @return Export data
     * @throws ApiException If export fails
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> exportTransactions(Map<String, String> filters, String format) throws ApiException {
        Map<String, String> exportFilters = new HashMap<>(filters);
        // GP API pages hold up to 1000 transactions
        exportFilters.put("page_size", "1000");

        Map<String, Object> result;
        try {
            result = searchTransactions(exportFilters);
        } catch (ApiException e) {
            throw reportError("Export failed", e);
        }

        List<Map<String, Object>> transactions =
                (List<Map<String, Object>>) ((Map<String, Object>) result.get("data")).get("transactions");

        switch (format) {
            case "csv":
                return exportToCsv(transactions);
            case "xml":
                return exportToXml(transactions);
            default:
                Map<String, Object> export = new LinkedHashMap<>();
                export.put("success", true);
                export.put("data", transactions);
                export.put("format", "json");
                export.put("timestamp", now());
                return export;
        }
    }

    /**
     * Get reporting summary statistics
     *
     * @param params Summary parameters
     * @return Summary statistics
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSummaryStats(Map<String, String> params) {
        String startDate = has(params, "start_date") ? params.get("start_date") : LocalDate.now().minusDays(30).toString();
        String endDate = has(params, "end_date") ? params.get("end_date") : LocalDate.now().toString();

        Map<String, String> filters = new HashMap<>();
        filters.put("start_date", startDate);
        filters.put("end_date", endDate);
        filters.put("page_size", "1000");

        Map<String, Object> result;
        try {
            result = searchTransactions(filters);
        } catch (ApiException | RuntimeException e) {
            String message = "Failed to generate summary statistics";
            if (e instanceof ApiException) {
                message += ": " + e.getMessage();
            } else {
                System.err.println(message + ": " + e);
            }
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("success", false);
            failure.put("error", message);
            failure.put("timestamp", now());
            return failure;
        }

        List<Map<String, Object>> transactions =
                (List<Map<String, Object>>) ((Map<String, Object>) result.get("data")).get("transactions");

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("start_date", startDate);
        period.put("end_date", endDate);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("success", true);
        summary.put("data", calculateSummaryStats(transactions));
        summary.put("period", period);
        summary.put("timestamp", now());
        return summary;
    }

    /**
     * Get SDK configuration status. Answers without credentials.
     */
    public static Map<String, Object> getSdkConfigStatus() {
        boolean hasAppId = !env("GP_API_APP_ID").isEmpty();
        boolean hasAppKey = !env("GP_API_APP_KEY").isEmpty();
        boolean isConfigured = hasAppId && hasAppKey;

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", isConfigured);
        status.put("has_app_id", hasAppId);
        status.put("has_app_key", hasAppKey);
        status.put("environment", isConfigured ? "TEST" : "Not configured");
        status.put("timestamp", now());
        return status;
    }

    /**
     * Validate environment configuration. Answers without credentials.
     */
    public static Map<String, Object> validateEnvironmentConfig() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String name : List.of("GP_API_APP_ID", "GP_API_APP_KEY")) {
            if (env(name).isEmpty()) {
                errors.add("Missing required environment variable: " + name);
            }
        }
        for (String name : List.of("PUBLIC_API_KEY", "SECRET_API_KEY")) {
            if (!env(name).isEmpty()) {
                warnings.add("Legacy variable " + name + " found. GP-API uses GP_API_APP_ID and GP_API_APP_KEY.");
            }
        }

        Map<String, Object> results = new LinkedHashMap<>();
        results.put("valid", errors.isEmpty());
        results.put("errors", errors);
        results.put("warnings", warnings);
        return results;
    }

    // Helper methods

    /**
     * GET a GP API endpoint through the SDK's configured connector, which signs
     * in and raises the usual SDK errors. Empty parameters are left out.
     */
    private JsonDoc gpApiGet(String endpoint, Map<String, String> query) throws ApiException {
        HashMap<String, String> params = new HashMap<>();
        query.forEach((key, value) -> {
            if (value != null && !value.isEmpty()) {
                params.put(key, value);
            }
        });
        String raw = connector().doTransaction(GpApiRequest.HttpMethod.Get, endpoint, null, params, null);
        return JsonDoc.parse(raw);
    }

    private GpApiConnector connector() throws ApiException {
        return (GpApiConnector) ServicesContainer.getInstance().getGateway("default");
    }

    /**
     * Wrap an SDK error with the report that failed. GP API errors read
     * "Status Code: ERROR_CODE - description", as in the other SDKs (the Java
     * SDK puts the HTTP status there).
     */
    private static ApiException reportError(String prefix, ApiException e) {
        String message = e.getMessage();
        if (e instanceof GatewayException gatewayException && gatewayException.getResponseCode() != null
                && message != null && message.startsWith("Status Code: ") && message.contains(" - ")) {
            message = "Status Code: " + gatewayException.getResponseCode() + message.substring(message.indexOf(" - "));
        }
        return new ApiException(prefix + ": " + message, e);
    }

    private static Map<String, Object> success(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", data);
        result.put("timestamp", now());
        return result;
    }

    private static Map<String, Object> pagination(int page, int pageSize, int totalCount) {
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", page);
        pagination.put("page_size", pageSize);
        pagination.put("total_count", totalCount);
        return pagination;
    }

    private static String now() {
        return LocalDateTime.now().format(NOW_FORMAT);
    }

    private static boolean has(Map<String, String> map, String key) {
        String value = map.get(key);
        return value != null && !value.isEmpty();
    }

    private static int intValue(Map<String, String> map, String key, int defaultValue) {
        try {
            int value = Integer.parseInt(map.getOrDefault(key, ""));
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Double doubleValue(Map<String, String> map, String key) {
        try {
            return has(map, key) ? Double.parseDouble(map.get(key)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** A YYYY-MM-DD date at local midnight, which the SDK writes back as the same day */
    private static Date parseDate(String value) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd").parse(value);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid date format. Use YYYY-MM-DD.");
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String currency(String value) {
        return value == null || value.isEmpty() ? "USD" : value;
    }

    private static double amount(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    /**
     * SDK times as ISO strings. The SDK reads GP API times with the trailing Z
     * as a literal, in the JVM's time zone, so the local fields are the time GP
     * API sent, in UTC.
     */
    private static String isoString(DateTime value) {
        return value == null ? "" : value.toString("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    }

    /** SDK dates (GP API sends deposit dates as plain dates) */
    private static String dateString(Date value) {
        return value == null ? "" : new SimpleDateFormat("yyyy-MM-dd").format(value);
    }

    private static List<JsonDoc> rows(JsonDoc doc, String name) {
        List<JsonDoc> rows = doc.getEnumerator(name);
        return rows == null ? List.of() : rows;
    }

    private static int totalCount(JsonDoc doc) {
        Integer total = doc.getInt("total_record_count");
        if (total == null || total == 0) {
            total = doc.getInt("total_count");
        }
        return total == null ? 0 : total;
    }

    private static List<Map<String, Object>> filter(List<Map<String, Object>> rows, Predicate<Map<String, Object>> keep) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (keep.test(row)) {
                out.add(row);
            }
        }
        return out;
    }

    private static double sum(List<Map<String, Object>> rows, String field) {
        double total = 0;
        for (Map<String, Object> row : rows) {
            Object value = row.get(field);
            if (value instanceof Number number) {
                total += number.doubleValue();
            }
        }
        return total;
    }

    /**
     * Map a GP API transaction with the SDK's mapping. The 3DS block is left out:
     * the reports do not use it and the SDK has no enum for some of its values.
     */
    private static TransactionSummary mapTransaction(JsonDoc row) throws GatewayException {
        JsonDoc paymentMethod = row.get("payment_method");
        if (paymentMethod != null) {
            paymentMethod.remove("authentication");
        }
        return GpApiMapping.mapTransactionSummary(row);
    }

    private static Map<String, Object> formatTransaction(TransactionSummary t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("transaction_id", text(t.getTransactionId()));
        row.put("timestamp", isoString(t.getTransactionDate()));
        row.put("amount", amount(t.getAmount()));
        row.put("currency", currency(t.getCurrency()));
        row.put("status", text(t.getTransactionStatus()));
        row.put("payment_method", text(t.getPaymentType()));
        row.put("card_last_four", text(t.getMaskedCardNumber()));
        row.put("auth_code", text(t.getAuthCode()));
        row.put("reference_number", text(t.getReferenceNumber()));
        return row;
    }

    /**
     * Fields shared by the dispute list and the dispute detail. The list has
     * only the stage time, the detail has the time the case was created.
     */
    private static Map<String, Object> formatDispute(DisputeSummary d, JsonDoc doc) {
        JsonDoc transaction = doc.get("transaction");

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dispute_id", text(d.getCaseId()));
        row.put("transaction_id", transaction != null ? text(transaction.getString("id")) : "");
        row.put("case_number", "");
        row.put("dispute_stage", text(d.getCaseStage()));
        row.put("dispute_status", text(d.getCaseStatus()));
        row.put("case_amount", amount(d.getCaseAmount()));
        row.put("currency", currency(d.getCaseCurrency()));
        row.put("reason_code", text(d.getReasonCode()));
        row.put("reason_description", text(d.getReason()));
        row.put("case_time", isoString(d.getCaseIdTime() != null ? d.getCaseIdTime() : d.getCaseStageTime()));
        row.put("last_adjustment_time", "");
        return row;
    }

    /** The card of the disputed transaction when GP API sends one, else the dispute's own card */
    private static String disputeMaskedCard(JsonDoc doc) {
        JsonDoc transaction = doc.get("transaction");
        JsonDoc transactionMethod = transaction != null ? transaction.get("payment_method") : null;
        if (transactionMethod != null && transactionMethod.get("card") != null) {
            return text(transactionMethod.get("card").getString("masked_number_first6last4"));
        }
        JsonDoc paymentMethod = doc.get("payment_method");
        if (paymentMethod != null && paymentMethod.get("card") != null) {
            return text(paymentMethod.get("card").getString("number"));
        }
        return "";
    }

    private static Map<String, Object> formatDeposit(DepositSummary d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deposit_id", text(d.getDepositId()));
        row.put("deposit_date", dateString(d.getDepositDate()));
        row.put("deposit_reference", "");
        row.put("deposit_status", text(d.getStatus()));
        row.put("deposit_amount", amount(d.getAmount()));
        row.put("currency", currency(d.getCurrency()));
        row.put("merchant_number", text(d.getMerchantNumber()));
        row.put("merchant_hierarchy", text(d.getMerchantHierarchy()));
        return row;
    }

    private static Map<String, Object> calculateSummaryStats(List<Map<String, Object>> transactions) {
        double totalAmount = sum(transactions, "amount");
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        Map<String, Integer> paymentTypeCounts = new LinkedHashMap<>();

        for (Map<String, Object> t : transactions) {
            statusCounts.merge(orDefault(t.get("status"), "unknown"), 1, Integer::sum);
            paymentTypeCounts.merge(orDefault(t.get("payment_method"), "unknown"), 1, Integer::sum);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_transactions", transactions.size());
        stats.put("total_amount", totalAmount);
        stats.put("average_amount", transactions.isEmpty() ? 0.0 : totalAmount / transactions.size());
        stats.put("status_breakdown", statusCounts);
        stats.put("payment_type_breakdown", paymentTypeCounts);
        return stats;
    }

    private static String orDefault(Object value, String fallback) {
        String text = text(value);
        return text.isEmpty() ? fallback : text;
    }

    private static Map<String, Object> analyzeDeclines(List<Map<String, Object>> transactions) {
        double totalAmount = sum(transactions, "amount");
        Map<String, Integer> declineReasons = new LinkedHashMap<>();
        Map<String, Integer> cardTypes = new LinkedHashMap<>();
        Map<String, Integer> hourlyBreakdown = new LinkedHashMap<>();

        for (Map<String, Object> t : transactions) {
            // The list rows carry no gateway message
            declineReasons.merge("Unknown", 1, Integer::sum);
            cardTypes.merge(orDefault(t.get("payment_method"), "Unknown"), 1, Integer::sum);

            // Hours are counted in UTC, as GP API sends the times
            String timestamp = text(t.get("timestamp"));
            if (!timestamp.isEmpty()) {
                try {
                    String hour = String.format("%02d", OffsetDateTime.parse(timestamp).withOffsetSameInstant(ZoneOffset.UTC).getHour());
                    hourlyBreakdown.merge(hour, 1, Integer::sum);
                } catch (RuntimeException ignored) {
                    // a time GP API sent in another format is left out of the hours
                }
            }
        }

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("total_declined_transactions", transactions.size());
        analysis.put("total_declined_amount", totalAmount);
        analysis.put("average_declined_amount", transactions.isEmpty() ? 0.0 : totalAmount / transactions.size());
        analysis.put("decline_reasons", declineReasons);
        analysis.put("card_type_breakdown", cardTypes);
        analysis.put("hourly_breakdown", hourlyBreakdown);
        return analysis;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> section(Map<String, Object> data, String key) {
        Object section = data.get(key);
        if (section instanceof Map<?, ?> map && map.get(key) instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private static Map<String, Object> generateComprehensiveSummary(Map<String, Object> data) {
        Map<String, Object> overview = new LinkedHashMap<>();
        Map<String, Object> financial = new LinkedHashMap<>();
        Map<String, Object> operational = new LinkedHashMap<>();

        List<Map<String, Object>> transactions = section(data, "transactions");
        if (!transactions.isEmpty()) {
            double total = sum(transactions, "amount");
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("count", transactions.size());
            values.put("total_amount", total);
            values.put("average_amount", total / transactions.size());
            overview.put("transactions", values);
        }

        List<Map<String, Object>> settlements = section(data, "settlements");
        if (!settlements.isEmpty()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("count", settlements.size());
            values.put("total_amount", sum(settlements, "total_amount"));
            financial.put("settlements", values);
        }

        List<Map<String, Object>> disputes = section(data, "disputes");
        if (!disputes.isEmpty()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("count", disputes.size());
            values.put("total_amount", sum(disputes, "case_amount"));
            values.put("dispute_rate", transactions.isEmpty() ? 0.0 : (double) disputes.size() / transactions.size() * 100);
            operational.put("disputes", values);
        }

        List<Map<String, Object>> deposits = section(data, "deposits");
        if (!deposits.isEmpty()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("count", deposits.size());
            values.put("total_amount", sum(deposits, "deposit_amount"));
            financial.put("deposits", values);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("overview", overview);
        summary.put("financial_summary", financial);
        summary.put("operational_metrics", operational);
        return summary;
    }

    private static String exportFilename(String extension) {
        return "transactions_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + "." + extension;
    }

    private static final List<String> EXPORT_FIELDS = List.of("transaction_id", "timestamp", "amount", "currency", "status",
            "payment_method", "card_last_four", "auth_code", "reference_number");

    private static Map<String, Object> exportToCsv(List<Map<String, Object>> transactions) {
        Function<Object, String> field = value -> {
            String text = text(value);
            return text.matches("(?s).*[\",\r\n].*") ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
        };

        StringBuilder csv = new StringBuilder("Transaction ID,Timestamp,Amount,Currency,Status,Payment Method,Card Last Four,Auth Code,Reference Number\n");
        for (Map<String, Object> t : transactions) {
            List<String> cells = new ArrayList<>();
            for (String name : EXPORT_FIELDS) {
                Object value = t.get(name);
                cells.add(name.equals("amount") ? String.format(Locale.ROOT, "%.2f", (double) value) : field.apply(value));
            }
            csv.append(String.join(",", cells)).append('\n');
        }

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("success", true);
        export.put("data", csv.toString());
        export.put("format", "csv");
        export.put("filename", exportFilename("csv"));
        export.put("timestamp", now());
        return export;
    }

    private static String escapeXml(Object value) {
        return text(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static Map<String, Object> exportToXml(List<Map<String, Object>> transactions) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<transactions>\n");
        for (Map<String, Object> t : transactions) {
            xml.append("  <transaction>\n");
            for (String name : EXPORT_FIELDS) {
                Object value = t.get(name);
                String text = name.equals("amount") ? new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString() : escapeXml(value);
                xml.append("    <").append(name).append('>').append(text).append("</").append(name).append(">\n");
            }
            xml.append("  </transaction>\n");
        }
        xml.append("</transactions>");

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("success", true);
        export.put("data", xml.toString());
        export.put("format", "xml");
        export.put("filename", exportFilename("xml"));
        export.put("timestamp", now());
        return export;
    }
}
