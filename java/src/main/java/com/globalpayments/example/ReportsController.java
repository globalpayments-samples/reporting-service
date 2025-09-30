package com.globalpayments.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.global.api.entities.exceptions.ApiException;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Global Payments Reporting API Controller
 *
 * This servlet provides RESTful API endpoints for accessing Global Payments
 * reporting functionality including transaction search, details, settlement
 * reports, and data export capabilities.
 *
 * Endpoints:
 * - search: Search transactions with filters and pagination
 * - detail: Get detailed transaction information
 * - settlement: Get settlement report
 * - export: Export transaction data in JSON/CSV/XML formats
 * - summary: Get summary statistics
 * - disputes: Get dispute report
 * - dispute_detail: Get dispute details
 * - deposits: Get deposit report
 * - deposit_detail: Get deposit details
 * - batches: Get batch report
 * - declines: Get declined transactions report
 * - date_range: Get comprehensive date range report
 * - config: Get API configuration and status
 *
 * @author Global Payments
 * @version 1.0
 */
@WebServlet(urlPatterns = {"/reports"})
public class ReportsController extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private ReportingService reportingService;
    private ObjectMapper objectMapper;

    /**
     * Initialize the servlet and configure the reporting service
     *
     * @throws ServletException if initialization fails
     */
    @Override
    public void init() throws ServletException {
        try {
            this.reportingService = new ReportingService();
            this.objectMapper = new ObjectMapper();
            this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
            this.objectMapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            throw new ServletException("Failed to initialize reporting service", e);
        }
    }

    /**
     * Set CORS and JSON headers for API responses
     *
     * @param response The HTTP response
     */
    private void setJsonHeaders(HttpServletResponse response) {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    /**
     * Handle OPTIONS requests for CORS preflight
     */
    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response) {
        setJsonHeaders(response);
        response.setStatus(HttpServletResponse.SC_OK);
    }

    /**
     * Handle GET requests
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        setJsonHeaders(response);

        try {
            Map<String, String> params = getRequestParams(request);
            String action = params.getOrDefault("action", "");

            handleRequest(action, params, response);

        } catch (IllegalArgumentException e) {
            handleError(response, e.getMessage(), HttpServletResponse.SC_BAD_REQUEST, "VALIDATION_ERROR");
        } catch (ApiException e) {
            handleError(response, e.getMessage(), HttpServletResponse.SC_BAD_REQUEST, "API_ERROR");
        } catch (Exception e) {
            handleError(response, "An unexpected error occurred: " + e.getMessage(),
                       HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        }
    }

    /**
     * Handle POST requests
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        setJsonHeaders(response);

        try {
            Map<String, String> params = getRequestParams(request);
            String action = params.getOrDefault("action", "");

            handleRequest(action, params, response);

        } catch (IllegalArgumentException e) {
            handleError(response, e.getMessage(), HttpServletResponse.SC_BAD_REQUEST, "VALIDATION_ERROR");
        } catch (ApiException e) {
            handleError(response, e.getMessage(), HttpServletResponse.SC_BAD_REQUEST, "API_ERROR");
        } catch (Exception e) {
            handleError(response, "An unexpected error occurred: " + e.getMessage(),
                       HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        }
    }

    /**
     * Route requests to appropriate handlers based on action parameter
     */
    private void handleRequest(String action, Map<String, String> params, HttpServletResponse response)
            throws Exception {

        switch (action) {
            case "search":
                handleSearch(params, response);
                break;

            case "detail":
                handleDetail(params, response);
                break;

            case "settlement":
                handleSettlement(params, response);
                break;

            case "export":
                handleExport(params, response);
                break;

            case "summary":
                handleSummary(params, response);
                break;

            case "disputes":
                handleDisputes(params, response);
                break;

            case "dispute_detail":
                handleDisputeDetail(params, response);
                break;

            case "deposits":
                handleDeposits(params, response);
                break;

            case "deposit_detail":
                handleDepositDetail(params, response);
                break;

            case "batches":
                handleBatches(params, response);
                break;

            case "declines":
                handleDeclines(params, response);
                break;

            case "date_range":
                handleDateRange(params, response);
                break;

            case "config":
                handleConfig(response);
                break;

            case "":
                handleApiDocumentation(response);
                break;

            default:
                throw new IllegalArgumentException("Invalid action: " + action);
        }
    }

    /**
     * Handle search action
     */
    private void handleSearch(Map<String, String> params, HttpServletResponse response) throws Exception {
        Map<String, Object> filters = new HashMap<>();
        filters.put("page", parseInt(params.get("page"), 1));
        filters.put("page_size", Math.min(parseInt(params.get("page_size"), 10), 100));
        filters.put("start_date", params.get("start_date"));
        filters.put("end_date", params.get("end_date"));
        filters.put("transaction_id", params.get("transaction_id"));
        filters.put("payment_type", params.get("payment_type"));
        filters.put("status", params.get("status"));
        filters.put("amount_min", params.get("amount_min"));
        filters.put("amount_max", params.get("amount_max"));
        filters.put("card_last_four", params.get("card_last_four"));

        // Validate date formats
        validateDateFormat(filters.get("start_date"), "start_date");
        validateDateFormat(filters.get("end_date"), "end_date");

        // Remove null/empty filters
        filters.values().removeIf(v -> v == null || (v instanceof String && ((String) v).isEmpty()));

        Map<String, Object> result = reportingService.searchTransactions(filters);
        sendJsonResponse(response, result);
    }

    /**
     * Handle detail action
     */
    private void handleDetail(Map<String, String> params, HttpServletResponse response) throws Exception {
        validateRequiredParams(params, "transaction_id");
        Map<String, Object> result = reportingService.getTransactionDetails(params.get("transaction_id"));
        sendJsonResponse(response, result);
    }

    /**
     * Handle settlement action
     */
    private void handleSettlement(Map<String, String> params, HttpServletResponse response) throws Exception {
        Map<String, Object> settlementParams = new HashMap<>();
        settlementParams.put("page", parseInt(params.get("page"), 1));
        settlementParams.put("page_size", Math.min(parseInt(params.get("page_size"), 50), 100));
        settlementParams.put("start_date", params.get("start_date"));
        settlementParams.put("end_date", params.get("end_date"));

        validateDateFormat(settlementParams.get("start_date"), "start_date");
        validateDateFormat(settlementParams.get("end_date"), "end_date");

        settlementParams.values().removeIf(v -> v == null || (v instanceof String && ((String) v).isEmpty()));

        Map<String, Object> result = reportingService.getSettlementReport(settlementParams);
        sendJsonResponse(response, result);
    }

    /**
     * Handle export action
     */
    private void handleExport(Map<String, String> params, HttpServletResponse response) throws Exception {
        Map<String, Object> exportFilters = new HashMap<>();
        exportFilters.put("start_date", params.get("start_date"));
        exportFilters.put("end_date", params.get("end_date"));
        exportFilters.put("transaction_id", params.get("transaction_id"));
        exportFilters.put("payment_type", params.get("payment_type"));
        exportFilters.put("status", params.get("status"));
        exportFilters.put("amount_min", params.get("amount_min"));
        exportFilters.put("amount_max", params.get("amount_max"));
        exportFilters.put("card_last_four", params.get("card_last_four"));

        String format = params.getOrDefault("format", "json");
        if (!Arrays.asList("json", "csv", "xml").contains(format)) {
            throw new IllegalArgumentException("Invalid format. Supported formats: json, csv, xml");
        }

        validateDateFormat(exportFilters.get("start_date"), "start_date");
        validateDateFormat(exportFilters.get("end_date"), "end_date");

        exportFilters.values().removeIf(v -> v == null || (v instanceof String && ((String) v).isEmpty()));

        Map<String, Object> result = reportingService.exportTransactions(exportFilters, format);

        if ("csv".equals(format)) {
            response.setContentType("text/csv");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + result.get("filename") + "\"");
            response.getWriter().write((String) result.get("data"));
        } else if ("xml".equals(format)) {
            response.setContentType("application/xml");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + result.get("filename") + "\"");
            response.getWriter().write((String) result.get("data"));
        } else {
            sendJsonResponse(response, result);
        }
    }

    /**
     * Handle summary action
     */
    private void handleSummary(Map<String, String> params, HttpServletResponse response) throws Exception {
        Map<String, Object> summaryParams = new HashMap<>();
        summaryParams.put("start_date", params.get("start_date"));
        summaryParams.put("end_date", params.get("end_date"));

        validateDateFormat(summaryParams.get("start_date"), "start_date");
        validateDateFormat(summaryParams.get("end_date"), "end_date");

        summaryParams.values().removeIf(v -> v == null || (v instanceof String && ((String) v).isEmpty()));

        Map<String, Object> result = reportingService.getSummaryStats(summaryParams);
        sendJsonResponse(response, result);
    }

    /**
     * Handle disputes action
     */
    private void handleDisputes(Map<String, String> params, HttpServletResponse response) throws Exception {
        Map<String, Object> disputeFilters = new HashMap<>();
        disputeFilters.put("page", parseInt(params.get("page"), 1));
        disputeFilters.put("page_size", Math.min(parseInt(params.get("page_size"), 10), 100));
        disputeFilters.put("start_date", params.get("start_date"));
        disputeFilters.put("end_date", params.get("end_date"));
        disputeFilters.put("stage", params.get("stage"));
        disputeFilters.put("status", params.get("status"));

        validateDateFormat(disputeFilters.get("start_date"), "start_date");
        validateDateFormat(disputeFilters.get("end_date"), "end_date");

        disputeFilters.values().removeIf(v -> v == null || (v instanceof String && ((String) v).isEmpty()));

        Map<String, Object> result = reportingService.getDisputeReport(disputeFilters);
        sendJsonResponse(response, result);
    }

    /**
     * Handle dispute_detail action
     */
    private void handleDisputeDetail(Map<String, String> params, HttpServletResponse response) throws Exception {
        validateRequiredParams(params, "dispute_id");
        Map<String, Object> result = reportingService.getDisputeDetails(params.get("dispute_id"));
        sendJsonResponse(response, result);
    }

    /**
     * Handle deposits action
     */
    private void handleDeposits(Map<String, String> params, HttpServletResponse response) throws Exception {
        Map<String, Object> depositFilters = new HashMap<>();
        depositFilters.put("page", parseInt(params.get("page"), 1));
        depositFilters.put("page_size", Math.min(parseInt(params.get("page_size"), 10), 100));
        depositFilters.put("start_date", params.get("start_date"));
        depositFilters.put("end_date", params.get("end_date"));
        depositFilters.put("deposit_id", params.get("deposit_id"));
        depositFilters.put("status", params.get("status"));

        validateDateFormat(depositFilters.get("start_date"), "start_date");
        validateDateFormat(depositFilters.get("end_date"), "end_date");

        depositFilters.values().removeIf(v -> v == null || (v instanceof String && ((String) v).isEmpty()));

        Map<String, Object> result = reportingService.getDepositReport(depositFilters);
        sendJsonResponse(response, result);
    }

    /**
     * Handle deposit_detail action
     */
    private void handleDepositDetail(Map<String, String> params, HttpServletResponse response) throws Exception {
        validateRequiredParams(params, "deposit_id");
        Map<String, Object> result = reportingService.getDepositDetails(params.get("deposit_id"));
        sendJsonResponse(response, result);
    }

    /**
     * Handle batches action
     */
    private void handleBatches(Map<String, String> params, HttpServletResponse response) throws Exception {
        Map<String, Object> batchFilters = new HashMap<>();
        batchFilters.put("start_date", params.get("start_date"));
        batchFilters.put("end_date", params.get("end_date"));

        validateDateFormat(batchFilters.get("start_date"), "start_date");
        validateDateFormat(batchFilters.get("end_date"), "end_date");

        batchFilters.values().removeIf(v -> v == null || (v instanceof String && ((String) v).isEmpty()));

        Map<String, Object> result = reportingService.getBatchReport(batchFilters);
        sendJsonResponse(response, result);
    }

    /**
     * Handle declines action
     */
    private void handleDeclines(Map<String, String> params, HttpServletResponse response) throws Exception {
        Map<String, Object> declineFilters = new HashMap<>();
        declineFilters.put("page", parseInt(params.get("page"), 1));
        declineFilters.put("page_size", Math.min(parseInt(params.get("page_size"), 10), 100));
        declineFilters.put("start_date", params.get("start_date"));
        declineFilters.put("end_date", params.get("end_date"));
        declineFilters.put("payment_type", params.get("payment_type"));
        declineFilters.put("amount_min", params.get("amount_min"));
        declineFilters.put("amount_max", params.get("amount_max"));
        declineFilters.put("card_last_four", params.get("card_last_four"));

        validateDateFormat(declineFilters.get("start_date"), "start_date");
        validateDateFormat(declineFilters.get("end_date"), "end_date");

        declineFilters.values().removeIf(v -> v == null || (v instanceof String && ((String) v).isEmpty()));

        Map<String, Object> result = reportingService.getDeclinedTransactionsReport(declineFilters);
        sendJsonResponse(response, result);
    }

    /**
     * Handle date_range action
     */
    private void handleDateRange(Map<String, String> params, HttpServletResponse response) throws Exception {
        Map<String, Object> dateRangeParams = new HashMap<>();
        dateRangeParams.put("start_date", params.get("start_date"));
        dateRangeParams.put("end_date", params.get("end_date"));
        dateRangeParams.put("transaction_limit", Math.min(parseInt(params.get("transaction_limit"), 100), 1000));
        dateRangeParams.put("settlement_limit", Math.min(parseInt(params.get("settlement_limit"), 50), 500));
        dateRangeParams.put("dispute_limit", Math.min(parseInt(params.get("dispute_limit"), 25), 100));
        dateRangeParams.put("deposit_limit", Math.min(parseInt(params.get("deposit_limit"), 25), 100));

        validateDateFormat(dateRangeParams.get("start_date"), "start_date");
        validateDateFormat(dateRangeParams.get("end_date"), "end_date");

        dateRangeParams.values().removeIf(v -> v == null || (v instanceof String && ((String) v).isEmpty()));

        Map<String, Object> result = reportingService.getDateRangeReport(dateRangeParams);
        sendJsonResponse(response, result);
    }

    /**
     * Handle config action
     */
    private void handleConfig(HttpServletResponse response) throws Exception {
        Map<String, Object> configStatus = reportingService.getSdkConfigStatus();
        Map<String, Object> envValidation = reportingService.validateEnvironmentConfig();

        Map<String, Object> apiEndpoints = new LinkedHashMap<>();
        apiEndpoints.put("search", "/reports?action=search");
        apiEndpoints.put("detail", "/reports?action=detail&transaction_id={id}");
        apiEndpoints.put("settlement", "/reports?action=settlement");
        apiEndpoints.put("disputes", "/reports?action=disputes");
        apiEndpoints.put("dispute_detail", "/reports?action=dispute_detail&dispute_id={id}");
        apiEndpoints.put("deposits", "/reports?action=deposits");
        apiEndpoints.put("deposit_detail", "/reports?action=deposit_detail&deposit_id={id}");
        apiEndpoints.put("batches", "/reports?action=batches");
        apiEndpoints.put("declines", "/reports?action=declines");
        apiEndpoints.put("date_range", "/reports?action=date_range");
        apiEndpoints.put("export", "/reports?action=export&format={json|csv|xml}");
        apiEndpoints.put("summary", "/reports?action=summary");
        apiEndpoints.put("config", "/reports?action=config");

        Map<String, Object> data = new HashMap<>();
        data.put("sdk_status", configStatus);
        data.put("environment_validation", envValidation);
        data.put("api_endpoints", apiEndpoints);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", data);
        result.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        sendJsonResponse(response, result);
    }

    /**
     * Handle default action - show API documentation
     */
    private void handleApiDocumentation(HttpServletResponse response) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "Global Payments Reporting API");
        data.put("version", "1.0.0");
        data.put("description", "RESTful API for Global Payments transaction reporting and analytics");

        Map<String, Object> endpoints = new LinkedHashMap<>();

        Map<String, Object> search = new LinkedHashMap<>();
        search.put("url", "/reports?action=search");
        search.put("method", "GET/POST");
        search.put("description", "Search transactions with filters and pagination");
        Map<String, String> searchParams = new LinkedHashMap<>();
        searchParams.put("page", "Page number (default: 1)");
        searchParams.put("page_size", "Results per page (default: 10, max: 100)");
        searchParams.put("start_date", "Start date (YYYY-MM-DD)");
        searchParams.put("end_date", "End date (YYYY-MM-DD)");
        searchParams.put("transaction_id", "Specific transaction ID");
        searchParams.put("payment_type", "Payment type (sale, refund, authorize, capture)");
        searchParams.put("status", "Transaction status");
        searchParams.put("amount_min", "Minimum amount");
        searchParams.put("amount_max", "Maximum amount");
        searchParams.put("card_last_four", "Last 4 digits of card");
        search.put("parameters", searchParams);
        endpoints.put("search", search);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("url", "/reports?action=detail&transaction_id={id}");
        detail.put("method", "GET");
        detail.put("description", "Get detailed transaction information");
        Map<String, String> detailParams = new LinkedHashMap<>();
        detailParams.put("transaction_id", "Transaction ID (required)");
        detail.put("parameters", detailParams);
        endpoints.put("detail", detail);

        Map<String, Object> settlement = new LinkedHashMap<>();
        settlement.put("url", "/reports?action=settlement");
        settlement.put("method", "GET/POST");
        settlement.put("description", "Get settlement report");
        endpoints.put("settlement", settlement);

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("url", "/reports?action=export&format={json|csv|xml}");
        export.put("method", "GET/POST");
        export.put("description", "Export transaction data");
        endpoints.put("export", export);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("url", "/reports?action=summary");
        summary.put("method", "GET/POST");
        summary.put("description", "Get summary statistics");
        endpoints.put("summary", summary);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("url", "/reports?action=config");
        config.put("method", "GET");
        config.put("description", "Get API configuration and status");
        endpoints.put("config", config);

        data.put("endpoints", endpoints);
        result.put("data", data);
        result.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        sendJsonResponse(response, result);
    }

    /**
     * Get request parameters from GET or POST
     */
    private Map<String, String> getRequestParams(HttpServletRequest request) throws IOException {
        Map<String, String> params = new HashMap<>();

        // Get query parameters
        if (request.getParameterMap() != null) {
            request.getParameterMap().forEach((key, values) -> {
                if (values != null && values.length > 0) {
                    params.put(key, values[0]);
                }
            });
        }

        // Get POST body parameters (JSON)
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            try {
                StringBuilder buffer = new StringBuilder();
                BufferedReader reader = request.getReader();
                String line;
                while ((line = reader.readLine()) != null) {
                    buffer.append(line);
                }

                if (buffer.length() > 0) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> jsonParams = objectMapper.readValue(buffer.toString(), Map.class);
                    jsonParams.forEach((key, value) -> {
                        if (value != null) {
                            params.put(key, value.toString());
                        }
                    });
                }
            } catch (Exception e) {
                // Ignore JSON parsing errors, use query params only
            }
        }

        return params;
    }

    /**
     * Validate required parameters
     */
    private void validateRequiredParams(Map<String, String> params, String... required) {
        for (String param : required) {
            String value = params.get(param);
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("Missing required parameter: " + param);
            }
        }
    }

    /**
     * Validate date format (YYYY-MM-DD)
     */
    private void validateDateFormat(Object dateObj, String fieldName) {
        if (dateObj == null || !(dateObj instanceof String)) {
            return;
        }

        String date = (String) dateObj;
        if (date.isEmpty()) {
            return;
        }

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            sdf.setLenient(false);
            sdf.parse(date);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + fieldName + " format. Use YYYY-MM-DD.");
        }
    }

    /**
     * Parse integer from string with default value
     */
    private int parseInt(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Send JSON response
     */
    private void sendJsonResponse(HttpServletResponse response, Map<String, Object> data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        objectMapper.writeValue(response.getWriter(), data);
    }

    /**
     * Handle error responses
     */
    private void handleError(HttpServletResponse response, String message, int statusCode, String errorCode)
            throws IOException {
        response.setStatus(statusCode);

        Map<String, Object> error = new HashMap<>();
        error.put("code", errorCode);
        error.put("message", message);
        error.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("error", error);

        objectMapper.writeValue(response.getWriter(), result);
    }
}