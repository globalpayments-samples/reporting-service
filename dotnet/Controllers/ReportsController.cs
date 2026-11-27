using Microsoft.AspNetCore.Mvc;
using CardPaymentSample.Services;
using GlobalPayments.Api.Entities;
using System.Globalization;
using System.Text;
using System.Text.Json;

namespace CardPaymentSample.Controllers;

/// <summary>
/// Global Payments Reporting API Controller
///
/// This controller provides RESTful API endpoints for accessing Global Payments
/// reporting functionality including transaction search, details, settlement
/// reports, and data export capabilities.
///
/// Every report answers on /api/reports?action={action} (the same path as the
/// Node.js, Python and Go servers) and on /api/reports/{action}, for example
/// /api/reports/search or /api/reports/date-range.
/// </summary>
[ApiController]
[Route("api/reports")]
public class ReportsController : ControllerBase
{
    private static readonly string[] SearchFilters =
        { "start_date", "end_date", "transaction_id", "payment_type", "status", "amount_min", "amount_max", "card_last_four" };

    private readonly GlobalPaymentsReportingService _reportingService;
    private readonly ILogger<ReportsController>? _logger;

    /// <summary>
    /// Constructor with dependency injection
    /// </summary>
    public ReportsController(GlobalPaymentsReportingService reportingService, ILogger<ReportsController>? logger = null)
    {
        _reportingService = reportingService;
        _logger = logger;
    }

    /// <summary>
    /// GET/POST /api/reports?action=search (no action answers the API documentation)
    /// </summary>
    [HttpGet]
    [HttpPost]
    public Task<IActionResult> Index() => Handle(null, new Dictionary<string, string>());

    /// <summary>
    /// GET/POST /api/reports/search, /api/reports/date-range and the other actions
    /// </summary>
    [HttpGet("{name}")]
    [HttpPost("{name}")]
    public Task<IActionResult> ByPath(string name) => Handle(name.Replace("-", "_"), new Dictionary<string, string>());

    /// <summary>
    /// GET /api/reports/detail/{transactionId}
    /// </summary>
    [HttpGet("detail/{transactionId}")]
    public Task<IActionResult> TransactionDetail(string transactionId) =>
        Handle("detail", new Dictionary<string, string> { ["transaction_id"] = transactionId });

    /// <summary>
    /// GET /api/reports/disputes/{disputeId}
    /// </summary>
    [HttpGet("disputes/{disputeId}")]
    public Task<IActionResult> DisputeDetail(string disputeId) =>
        Handle("dispute_detail", new Dictionary<string, string> { ["dispute_id"] = disputeId });

    /// <summary>
    /// GET /api/reports/deposits/{depositId}
    /// </summary>
    [HttpGet("deposits/{depositId}")]
    public Task<IActionResult> DepositDetail(string depositId) =>
        Handle("deposit_detail", new Dictionary<string, string> { ["deposit_id"] = depositId });

    private async Task<IActionResult> Handle(string? pathAction, Dictionary<string, string> routeParams)
    {
        var parameters = await GetRequestParams();
        foreach (var (key, value) in routeParams)
        {
            parameters[key] = value;
        }
        var action = pathAction ?? parameters.GetValueOrDefault("action", "");

        try
        {
            if (action == "")
            {
                return Json(ApiDocumentation(), 200);
            }
            if (action == "config")
            {
                return Json(ConfigStatus(), 200);
            }

            // Check the request before the credentials
            var run = PrepareAction(action, parameters);
            var result = await run();

            if (action == "export" && result.GetValueOrDefault("format") is string format && (format == "csv" || format == "xml"))
            {
                Response.Headers.ContentDisposition = $"attachment; filename=\"{result["filename"]}\"";
                return new ContentResult
                {
                    Content = (string)result["data"]!,
                    ContentType = format == "csv" ? "text/csv" : "application/xml",
                    StatusCode = 200
                };
            }

            return Json(result, 200);
        }
        catch (ArgumentException ex)
        {
            return Error(ex.Message, 400, "VALIDATION_ERROR");
        }
        catch (ApiException ex)
        {
            return Error(ex.Message, 400, "API_ERROR");
        }
        catch (Exception ex)
        {
            // Log unexpected errors on the server and return a generic message
            _logger?.LogError(ex, "Unexpected error in the reporting request");
            return Error("An unexpected error occurred", 500, "INTERNAL_ERROR");
        }
    }

    /// <summary>
    /// Check the request for an action and return the call that runs it
    /// </summary>
    private Func<Task<Dictionary<string, object?>>> PrepareAction(string action, Dictionary<string, string> parameters)
    {
        switch (action)
        {
            case "search":
            {
                var filters = Pick(parameters, SearchFilters);
                filters["page"] = Page(parameters);
                filters["page_size"] = Limit(parameters, "page_size", 10, 100);
                ValidateDates(filters);
                return () => _reportingService.SearchTransactionsAsync(filters);
            }
            case "detail":
                ValidateRequiredParams(parameters, "transaction_id");
                return () => _reportingService.GetTransactionDetailsAsync(parameters["transaction_id"]);

            case "settlement":
            {
                var filters = Pick(parameters, "start_date", "end_date");
                filters["page"] = Page(parameters);
                filters["page_size"] = Limit(parameters, "page_size", 50, 100);
                ValidateDates(filters);
                return () => _reportingService.GetSettlementReportAsync(filters);
            }
            case "export":
            {
                var filters = Pick(parameters, SearchFilters);
                var format = parameters.GetValueOrDefault("format", "");
                if (format == "")
                {
                    format = "json";
                }
                if (format != "json" && format != "csv" && format != "xml")
                {
                    throw new ArgumentException("Invalid format. Supported formats: json, csv, xml");
                }
                ValidateDates(filters);
                return () => _reportingService.ExportTransactionsAsync(filters, format);
            }
            case "summary":
            {
                var filters = Pick(parameters, "start_date", "end_date");
                ValidateDates(filters);
                return () => _reportingService.GetSummaryStatsAsync(filters);
            }
            case "disputes":
            {
                var filters = Pick(parameters, "start_date", "end_date", "stage", "status");
                filters["page"] = Page(parameters);
                filters["page_size"] = Limit(parameters, "page_size", 10, 100);
                ValidateDates(filters);
                return () => _reportingService.GetDisputeReportAsync(filters);
            }
            case "dispute_detail":
                ValidateRequiredParams(parameters, "dispute_id");
                return () => _reportingService.GetDisputeDetailsAsync(parameters["dispute_id"]);

            case "deposits":
            {
                var filters = Pick(parameters, "start_date", "end_date", "deposit_id", "status");
                filters["page"] = Page(parameters);
                filters["page_size"] = Limit(parameters, "page_size", 10, 100);
                ValidateDates(filters);
                return () => _reportingService.GetDepositReportAsync(filters);
            }
            case "deposit_detail":
                ValidateRequiredParams(parameters, "deposit_id");
                return () => _reportingService.GetDepositDetailsAsync(parameters["deposit_id"]);

            case "batches":
            {
                var filters = Pick(parameters, "start_date", "end_date");
                ValidateDates(filters);
                return () => _reportingService.GetBatchReportAsync(filters);
            }
            case "declines":
            {
                var filters = Pick(parameters, "start_date", "end_date", "payment_type", "amount_min", "amount_max", "card_last_four");
                filters["page"] = Page(parameters);
                filters["page_size"] = Limit(parameters, "page_size", 10, 100);
                ValidateDates(filters);
                return () => _reportingService.GetDeclinedTransactionsReportAsync(filters);
            }
            case "date_range":
            {
                var filters = Pick(parameters, "start_date", "end_date");
                filters["transaction_limit"] = Limit(parameters, "transaction_limit", 100, 1000);
                filters["settlement_limit"] = Limit(parameters, "settlement_limit", 50, 500);
                filters["dispute_limit"] = Limit(parameters, "dispute_limit", 25, 100);
                filters["deposit_limit"] = Limit(parameters, "deposit_limit", 25, 100);
                ValidateDates(filters);
                return () => _reportingService.GetDateRangeReportAsync(filters);
            }
            default:
                throw new ArgumentException($"Invalid action: {action}");
        }
    }

    private static Dictionary<string, object?> ConfigStatus() => new()
    {
        ["success"] = true,
        ["data"] = new Dictionary<string, object?>
        {
            ["sdk_status"] = GlobalPaymentsReportingService.GetSdkConfigStatus(),
            ["environment_validation"] = GlobalPaymentsReportingService.ValidateEnvironmentConfig(),
            ["api_endpoints"] = new Dictionary<string, object?>
            {
                ["search"] = "/api/reports?action=search",
                ["detail"] = "/api/reports?action=detail&transaction_id={id}",
                ["settlement"] = "/api/reports?action=settlement",
                ["disputes"] = "/api/reports?action=disputes",
                ["dispute_detail"] = "/api/reports?action=dispute_detail&dispute_id={id}",
                ["deposits"] = "/api/reports?action=deposits",
                ["deposit_detail"] = "/api/reports?action=deposit_detail&deposit_id={id}",
                ["batches"] = "/api/reports?action=batches",
                ["declines"] = "/api/reports?action=declines",
                ["date_range"] = "/api/reports?action=date_range",
                ["export"] = "/api/reports?action=export&format={json|csv|xml}",
                ["summary"] = "/api/reports?action=summary",
                ["config"] = "/api/reports?action=config"
            }
        },
        ["timestamp"] = Now()
    };

    private static Dictionary<string, object?> Endpoint(string url, string method, string description, Dictionary<string, string>? parameters = null)
    {
        var endpoint = new Dictionary<string, object?> { ["url"] = url, ["method"] = method, ["description"] = description };
        if (parameters != null)
        {
            endpoint["parameters"] = parameters;
        }
        return endpoint;
    }

    private static Dictionary<string, string> Params(params string[] pairs)
    {
        var result = new Dictionary<string, string>();
        for (var i = 0; i < pairs.Length; i += 2)
        {
            result[pairs[i]] = pairs[i + 1];
        }
        return result;
    }

    /// <summary>
    /// The answer to /api/reports without an action
    /// </summary>
    private static Dictionary<string, object?> ApiDocumentation()
    {
        const string start = "Start date (YYYY-MM-DD)";
        const string end = "End date (YYYY-MM-DD)";

        return new Dictionary<string, object?>
        {
            ["success"] = true,
            ["data"] = new Dictionary<string, object?>
            {
                ["name"] = "Global Payments Reporting API",
                ["version"] = "1.0.0",
                ["description"] = "RESTful API for Global Payments transaction reporting and analytics",
                ["endpoints"] = new Dictionary<string, object?>
                {
                    ["search"] = Endpoint("/api/reports?action=search", "GET/POST", "Search transactions with filters and pagination",
                        Params("page", "Page number (default: 1)", "page_size", "Results per page (default: 10, max: 100)",
                            "start_date", start, "end_date", end, "transaction_id", "Specific transaction ID",
                            "payment_type", "Payment type (sale, refund)", "status", "Transaction status",
                            "amount_min", "Minimum amount", "amount_max", "Maximum amount", "card_last_four", "Last 4 digits of card")),
                    ["detail"] = Endpoint("/api/reports?action=detail&transaction_id={id}", "GET", "Get detailed transaction information",
                        Params("transaction_id", "Transaction ID (required)")),
                    ["settlement"] = Endpoint("/api/reports?action=settlement", "GET/POST", "Get settlement report",
                        Params("page", "Page number (default: 1)", "page_size", "Results per page (default: 50, max: 100)",
                            "start_date", start, "end_date", end)),
                    ["export"] = Endpoint("/api/reports?action=export&format={json|csv|xml}", "GET/POST", "Export transaction data",
                        Params("format", "Export format (json, csv, or xml)", "...filters", "Same filters as search endpoint")),
                    ["summary"] = Endpoint("/api/reports?action=summary", "GET/POST", "Get summary statistics",
                        Params("start_date", start, "end_date", end)),
                    ["disputes"] = Endpoint("/api/reports?action=disputes", "GET/POST", "Get dispute report",
                        Params("page", "Page number (default: 1)", "page_size", "Results per page (default: 10, max: 100)",
                            "start_date", start, "end_date", end, "stage", "Dispute stage", "status", "Dispute status")),
                    ["deposits"] = Endpoint("/api/reports?action=deposits", "GET/POST", "Get deposit report",
                        Params("page", "Page number (default: 1)", "page_size", "Results per page (default: 10, max: 100)",
                            "start_date", start, "end_date", end, "deposit_id", "Specific deposit ID", "status", "Deposit status")),
                    ["batches"] = Endpoint("/api/reports?action=batches", "GET/POST", "Get batch report",
                        Params("start_date", start, "end_date", end)),
                    ["declines"] = Endpoint("/api/reports?action=declines", "GET/POST", "Get declined transactions report",
                        Params("page", "Page number (default: 1)", "page_size", "Results per page (default: 10, max: 100)",
                            "start_date", start, "end_date", end, "payment_type", "Payment type",
                            "amount_min", "Minimum amount", "amount_max", "Maximum amount", "card_last_four", "Last 4 digits of card")),
                    ["date_range"] = Endpoint("/api/reports?action=date_range", "GET/POST", "Get comprehensive date range report",
                        Params("start_date", start, "end_date", end,
                            "transaction_limit", "Max transactions (default: 100, max: 1000)",
                            "settlement_limit", "Max settlements (default: 50, max: 500)",
                            "dispute_limit", "Max disputes (default: 25, max: 100)",
                            "deposit_limit", "Max deposits (default: 25, max: 100)")),
                    ["config"] = Endpoint("/api/reports?action=config", "GET", "Get API configuration and status")
                }
            },
            ["timestamp"] = Now()
        };
    }

    /// <summary>
    /// Get request parameters: the query string, merged with a JSON or form body
    /// </summary>
    private async Task<Dictionary<string, string>> GetRequestParams()
    {
        var parameters = new Dictionary<string, string>();
        foreach (var (key, value) in Request.Query)
        {
            parameters[key] = value.ToString();
        }

        if (!HttpMethods.IsPost(Request.Method))
        {
            return parameters;
        }

        if (Request.HasFormContentType)
        {
            var form = await Request.ReadFormAsync();
            foreach (var (key, value) in form)
            {
                parameters[key] = value.ToString();
            }
        }
        else if (Request.ContentType?.Contains("application/json") == true)
        {
            try
            {
                using var body = await JsonDocument.ParseAsync(Request.Body);
                if (body.RootElement.ValueKind == JsonValueKind.Object)
                {
                    foreach (var property in body.RootElement.EnumerateObject())
                    {
                        switch (property.Value.ValueKind)
                        {
                            case JsonValueKind.String:
                                parameters[property.Name] = property.Value.GetString() ?? "";
                                break;
                            case JsonValueKind.Number:
                                parameters[property.Name] = property.Value.GetRawText();
                                break;
                            case JsonValueKind.True:
                            case JsonValueKind.False:
                                parameters[property.Name] = property.Value.GetBoolean() ? "true" : "false";
                                break;
                        }
                    }
                }
            }
            catch (JsonException)
            {
                // Not a JSON object, use the query string only
            }
        }

        return parameters;
    }

    private static Dictionary<string, string> Pick(Dictionary<string, string> parameters, params string[] names)
    {
        var result = new Dictionary<string, string>();
        foreach (var name in names)
        {
            if (parameters.TryGetValue(name, out var value) && !string.IsNullOrEmpty(value))
            {
                result[name] = value;
            }
        }
        return result;
    }

    /// <summary>
    /// Read a positive number, falling back to the default and capping it
    /// </summary>
    private static string Limit(Dictionary<string, string> parameters, string name, int defaultValue, int max)
    {
        var value = parameters.TryGetValue(name, out var text) && int.TryParse(text, out var number) && number > 0 ? number : defaultValue;
        return Math.Min(value, max).ToString(CultureInfo.InvariantCulture);
    }

    private static string Page(Dictionary<string, string> parameters) => Limit(parameters, "page", 1, int.MaxValue);

    private static void ValidateRequiredParams(Dictionary<string, string> parameters, params string[] required)
    {
        foreach (var name in required)
        {
            if (!parameters.TryGetValue(name, out var value) || string.IsNullOrEmpty(value))
            {
                throw new ArgumentException($"Missing required parameter: {name}");
            }
        }
    }

    /// <summary>
    /// Validate YYYY-MM-DD start and end dates when they are given
    /// </summary>
    private static void ValidateDates(Dictionary<string, string> parameters)
    {
        foreach (var name in new[] { "start_date", "end_date" })
        {
            if (parameters.TryGetValue(name, out var value) && !string.IsNullOrEmpty(value)
                && !DateTime.TryParseExact(value, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _))
            {
                throw new ArgumentException($"Invalid {name} format. Use YYYY-MM-DD.");
            }
        }
    }

    private static string Now() => DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");

    private static IActionResult Json(object data, int statusCode) => new JsonResult(data) { StatusCode = statusCode };

    private static IActionResult Error(string message, int statusCode, string errorCode) => Json(new Dictionary<string, object?>
    {
        ["success"] = false,
        ["error"] = new Dictionary<string, object?>
        {
            ["code"] = errorCode,
            ["message"] = message,
            ["timestamp"] = Now()
        }
    }, statusCode);
}
