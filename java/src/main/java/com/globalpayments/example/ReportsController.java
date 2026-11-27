package com.globalpayments.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.global.api.entities.exceptions.ApiException;
import com.global.api.entities.exceptions.ConfigurationException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Global Payments Reporting API Controller
 *
 * This servlet provides RESTful API endpoints for accessing Global Payments
 * reporting functionality including transaction search, details, settlement
 * reports, and data export capabilities.
 *
 * Endpoints (/reports?action=...):
 * - search: Search transactions with filters and pagination
 * - detail: Get detailed transaction information
 * - settlement: Get settlement report
 * - export: Export transaction data in JSON/CSV/XML formats
 * - summary: Get summary statistics
 * - disputes: Get dispute report
 * - dispute_detail: Get dispute details
 * - deposits: Get deposit report
 * - deposit_detail: Get deposit details
 * - batches: Get batch report (not available through GP API)
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
    private static final List<String> SEARCH_FILTERS = List.of("start_date", "end_date", "transaction_id", "payment_type",
            "status", "amount_min", "amount_max", "card_last_four");

    private final ObjectMapper objectMapper = new ObjectMapper();

    // The reporting service is created on first use, so the docs and the
    // config check answer before the credentials are set
    private ReportingService reportingService;

    private synchronized ReportingService getReportingService() throws ConfigurationException {
        if (reportingService == null) {
            reportingService = new ReportingService();
        }
        return reportingService;
    }

    /**
     * Set CORS headers for API responses
     */
    private void setCorsHeaders(HttpServletResponse response) {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    /**
     * Handle OPTIONS requests for CORS preflight
     */
    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response) {
        setCorsHeaders(response);
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handle(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handle(request, response);
    }

    /**
     * Run the requested action and write the answer
     */
    @SuppressWarnings("unchecked")
    private void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        setCorsHeaders(response);
        Map<String, String> params = getRequestParams(request);
        String action = params.getOrDefault("action", "");

        try {
            if (action.isEmpty()) {
                sendJsonResponse(response, apiDocumentation(), HttpServletResponse.SC_OK);
                return;
            }
            if (action.equals("config")) {
                sendJsonResponse(response, configStatus(), HttpServletResponse.SC_OK);
                return;
            }

            // Check the request before the credentials
            ReportAction run = prepareAction(action, params);
            Map<String, Object> result = run.apply(getReportingService());

            Object format = result.get("format");
            if (action.equals("export") && ("csv".equals(format) || "xml".equals(format))) {
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType("csv".equals(format) ? "text/csv" : "application/xml");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Content-Disposition", "attachment; filename=\"" + result.get("filename") + "\"");
                response.getWriter().write((String) result.get("data"));
                return;
            }

            sendJsonResponse(response, result, HttpServletResponse.SC_OK);

        } catch (IllegalArgumentException | ConfigurationException e) {
            handleError(response, e.getMessage(), HttpServletResponse.SC_BAD_REQUEST, "VALIDATION_ERROR");
        } catch (ApiException e) {
            handleError(response, e.getMessage(), HttpServletResponse.SC_BAD_REQUEST, "API_ERROR");
        } catch (Exception e) {
            // Log unexpected errors on the server and return a generic message
            log("Unexpected error in the reporting request", e);
            handleError(response, "An unexpected error occurred", HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        }
    }

    @FunctionalInterface
    private interface ReportAction {
        Map<String, Object> apply(ReportingService service) throws ApiException;
    }

    /**
     * Check the request for an action and return the call that runs it
     */
    private ReportAction prepareAction(String action, Map<String, String> params) {
        switch (action) {
            case "search": {
                Map<String, String> filters = pick(params, SEARCH_FILTERS);
                filters.put("page", page(params));
                filters.put("page_size", limit(params, "page_size", 10, 100));
                validateDates(filters);
                return service -> service.searchTransactions(filters);
            }
            case "detail":
                validateRequiredParams(params, "transaction_id");
                return service -> service.getTransactionDetails(params.get("transaction_id"));

            case "settlement": {
                Map<String, String> filters = pick(params, List.of("start_date", "end_date"));
                filters.put("page", page(params));
                filters.put("page_size", limit(params, "page_size", 50, 100));
                validateDates(filters);
                return service -> service.getSettlementReport(filters);
            }
            case "export": {
                Map<String, String> filters = pick(params, SEARCH_FILTERS);
                String format = params.getOrDefault("format", "");
                String exportFormat = format.isEmpty() ? "json" : format;
                if (!List.of("json", "csv", "xml").contains(exportFormat)) {
                    throw new IllegalArgumentException("Invalid format. Supported formats: json, csv, xml");
                }
                validateDates(filters);
                return service -> service.exportTransactions(filters, exportFormat);
            }
            case "summary": {
                Map<String, String> filters = pick(params, List.of("start_date", "end_date"));
                validateDates(filters);
                return service -> service.getSummaryStats(filters);
            }
            case "disputes": {
                Map<String, String> filters = pick(params, List.of("start_date", "end_date", "stage", "status"));
                filters.put("page", page(params));
                filters.put("page_size", limit(params, "page_size", 10, 100));
                validateDates(filters);
                return service -> service.getDisputeReport(filters);
            }
            case "dispute_detail":
                validateRequiredParams(params, "dispute_id");
                return service -> service.getDisputeDetails(params.get("dispute_id"));

            case "deposits": {
                Map<String, String> filters = pick(params, List.of("start_date", "end_date", "deposit_id", "status"));
                filters.put("page", page(params));
                filters.put("page_size", limit(params, "page_size", 10, 100));
                validateDates(filters);
                return service -> service.getDepositReport(filters);
            }
            case "deposit_detail":
                validateRequiredParams(params, "deposit_id");
                return service -> service.getDepositDetails(params.get("deposit_id"));

            case "batches": {
                Map<String, String> filters = pick(params, List.of("start_date", "end_date"));
                validateDates(filters);
                return service -> service.getBatchReport(filters);
            }
            case "declines": {
                Map<String, String> filters = pick(params, List.of("start_date", "end_date", "payment_type",
                        "amount_min", "amount_max", "card_last_four"));
                filters.put("page", page(params));
                filters.put("page_size", limit(params, "page_size", 10, 100));
                validateDates(filters);
                return service -> service.getDeclinedTransactionsReport(filters);
            }
            case "date_range": {
                Map<String, String> filters = pick(params, List.of("start_date", "end_date"));
                filters.put("transaction_limit", limit(params, "transaction_limit", 100, 1000));
                filters.put("settlement_limit", limit(params, "settlement_limit", 50, 500));
                filters.put("dispute_limit", limit(params, "dispute_limit", 25, 100));
                filters.put("deposit_limit", limit(params, "deposit_limit", 25, 100));
                validateDates(filters);
                return service -> service.getDateRangeReport(filters);
            }
            default:
                throw new IllegalArgumentException("Invalid action: " + action);
        }
    }

    private Map<String, Object> configStatus() {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("search", "/reports?action=search");
        endpoints.put("detail", "/reports?action=detail&transaction_id={id}");
        endpoints.put("settlement", "/reports?action=settlement");
        endpoints.put("disputes", "/reports?action=disputes");
        endpoints.put("dispute_detail", "/reports?action=dispute_detail&dispute_id={id}");
        endpoints.put("deposits", "/reports?action=deposits");
        endpoints.put("deposit_detail", "/reports?action=deposit_detail&deposit_id={id}");
        endpoints.put("batches", "/reports?action=batches");
        endpoints.put("declines", "/reports?action=declines");
        endpoints.put("date_range", "/reports?action=date_range");
        endpoints.put("export", "/reports?action=export&format={json|csv|xml}");
        endpoints.put("summary", "/reports?action=summary");
        endpoints.put("config", "/reports?action=config");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sdk_status", ReportingService.getSdkConfigStatus());
        data.put("environment_validation", ReportingService.validateEnvironmentConfig());
        data.put("api_endpoints", endpoints);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", data);
        result.put("timestamp", now());
        return result;
    }

    private static Map<String, Object> endpoint(String url, String method, String description, Map<String, String> parameters) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("url", url);
        e.put("method", method);
        e.put("description", description);
        if (parameters != null) {
            e.put("parameters", parameters);
        }
        return e;
    }

    private static Map<String, String> params(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    /**
     * The answer to /reports without an action
     */
    private Map<String, Object> apiDocumentation() {
        String start = "Start date (YYYY-MM-DD)";
        String end = "End date (YYYY-MM-DD)";

        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("search", endpoint("/reports?action=search", "GET/POST", "Search transactions with filters and pagination",
                params("page", "Page number (default: 1)", "page_size", "Results per page (default: 10, max: 100)",
                        "start_date", start, "end_date", end, "transaction_id", "Specific transaction ID",
                        "payment_type", "Payment type (sale, refund)", "status", "Transaction status",
                        "amount_min", "Minimum amount", "amount_max", "Maximum amount", "card_last_four", "Last 4 digits of card")));
        endpoints.put("detail", endpoint("/reports?action=detail&transaction_id={id}", "GET", "Get detailed transaction information",
                params("transaction_id", "Transaction ID (required)")));
        endpoints.put("settlement", endpoint("/reports?action=settlement", "GET/POST", "Get settlement report",
                params("page", "Page number (default: 1)", "page_size", "Results per page (default: 50, max: 100)",
                        "start_date", start, "end_date", end)));
        endpoints.put("export", endpoint("/reports?action=export&format={json|csv|xml}", "GET/POST", "Export transaction data",
                params("format", "Export format (json, csv, or xml)", "...filters", "Same filters as search endpoint")));
        endpoints.put("summary", endpoint("/reports?action=summary", "GET/POST", "Get summary statistics",
                params("start_date", start, "end_date", end)));
        endpoints.put("disputes", endpoint("/reports?action=disputes", "GET/POST", "Get dispute report",
                params("page", "Page number (default: 1)", "page_size", "Results per page (default: 10, max: 100)",
                        "start_date", start, "end_date", end, "stage", "Dispute stage", "status", "Dispute status")));
        endpoints.put("deposits", endpoint("/reports?action=deposits", "GET/POST", "Get deposit report",
                params("page", "Page number (default: 1)", "page_size", "Results per page (default: 10, max: 100)",
                        "start_date", start, "end_date", end, "deposit_id", "Specific deposit ID", "status", "Deposit status")));
        endpoints.put("batches", endpoint("/reports?action=batches", "GET/POST", "Get batch report",
                params("start_date", start, "end_date", end)));
        endpoints.put("declines", endpoint("/reports?action=declines", "GET/POST", "Get declined transactions report",
                params("page", "Page number (default: 1)", "page_size", "Results per page (default: 10, max: 100)",
                        "start_date", start, "end_date", end, "payment_type", "Payment type",
                        "amount_min", "Minimum amount", "amount_max", "Maximum amount", "card_last_four", "Last 4 digits of card")));
        endpoints.put("date_range", endpoint("/reports?action=date_range", "GET/POST", "Get comprehensive date range report",
                params("start_date", start, "end_date", end,
                        "transaction_limit", "Max transactions (default: 100, max: 1000)",
                        "settlement_limit", "Max settlements (default: 50, max: 500)",
                        "dispute_limit", "Max disputes (default: 25, max: 100)",
                        "deposit_limit", "Max deposits (default: 25, max: 100)")));
        endpoints.put("config", endpoint("/reports?action=config", "GET", "Get API configuration and status", null));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "Global Payments Reporting API");
        data.put("version", "1.0.0");
        data.put("description", "RESTful API for Global Payments transaction reporting and analytics");
        data.put("endpoints", endpoints);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", data);
        result.put("timestamp", now());
        return result;
    }

    /**
     * Get request parameters: the query string and form fields, merged with a JSON body
     */
    private Map<String, String> getRequestParams(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();

        request.getParameterMap().forEach((key, values) -> {
            if (values != null && values.length > 0) {
                params.put(key, values[0]);
            }
        });

        String contentType = request.getContentType();
        if ("POST".equalsIgnoreCase(request.getMethod()) && contentType != null && contentType.contains("application/json")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = objectMapper.readValue(request.getInputStream(), Map.class);
                body.forEach((key, value) -> {
                    if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                        params.put(key, value.toString());
                    }
                });
            } catch (Exception e) {
                // Not a JSON object, use the query string only
            }
        }

        return params;
    }

    private static Map<String, String> pick(Map<String, String> params, List<String> names) {
        Map<String, String> out = new HashMap<>();
        for (String name : names) {
            String value = params.get(name);
            if (value != null && !value.isEmpty()) {
                out.put(name, value);
            }
        }
        return out;
    }

    /**
     * Read a positive number, falling back to the default and capping it
     */
    private static String limit(Map<String, String> params, String name, int defaultValue, int max) {
        int value;
        try {
            value = Integer.parseInt(params.getOrDefault(name, ""));
        } catch (NumberFormatException e) {
            value = 0;
        }
        return String.valueOf(Math.min(value > 0 ? value : defaultValue, max));
    }

    private static String page(Map<String, String> params) {
        return limit(params, "page", 1, Integer.MAX_VALUE);
    }

    private static void validateRequiredParams(Map<String, String> params, String... required) {
        for (String name : required) {
            String value = params.get(name);
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException("Missing required parameter: " + name);
            }
        }
    }

    /**
     * Validate YYYY-MM-DD start and end dates when they are given
     */
    private static void validateDates(Map<String, String> params) {
        for (String name : List.of("start_date", "end_date")) {
            String value = params.get(name);
            if (value != null && !value.isEmpty()) {
                try {
                    if (!LocalDate.parse(value).toString().equals(value)) {
                        throw new DateTimeParseException("not YYYY-MM-DD", value, 0);
                    }
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid " + name + " format. Use YYYY-MM-DD.");
                }
            }
        }
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private void sendJsonResponse(HttpServletResponse response, Map<String, Object> data, int statusCode) throws IOException {
        response.setStatus(statusCode);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), data);
    }

    /**
     * Handle error responses
     */
    private void handleError(HttpServletResponse response, String message, int statusCode, String errorCode) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", errorCode);
        error.put("message", message);
        error.put("timestamp", now());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", error);
        sendJsonResponse(response, result, statusCode);
    }
}
