using Microsoft.AspNetCore.Mvc;
using CardPaymentSample.Services;
using GlobalPayments.Api.Entities;
using System.Text;

namespace CardPaymentSample.Controllers;

/// <summary>
/// Global Payments Reporting API Controller
///
/// This controller provides RESTful API endpoints for accessing Global Payments
/// reporting functionality including transaction search, details, settlement
/// reports, and data export capabilities.
/// </summary>
[ApiController]
[Route("api/[controller]")]
public class ReportsController : ControllerBase
{
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
    /// Root endpoint - API documentation
    /// GET /api/reports
    /// </summary>
    [HttpGet]
    public IActionResult GetApiInfo()
    {
        return Ok(new
        {
            success = true,
            data = new
            {
                name = "Global Payments Reporting API",
                version = "1.0.0",
                description = "RESTful API for Global Payments transaction reporting and analytics",
                endpoints = new
                {
                    search = new
                    {
                        url = "/api/reports/search",
                        method = "GET/POST",
                        description = "Search transactions with filters and pagination",
                        parameters = new
                        {
                            page = "Page number (default: 1)",
                            page_size = "Results per page (default: 10, max: 100)",
                            start_date = "Start date (YYYY-MM-DD)",
                            end_date = "End date (YYYY-MM-DD)",
                            transaction_id = "Specific transaction ID",
                            payment_type = "Payment type (sale, refund, authorize, capture)",
                            status = "Transaction status",
                            amount_min = "Minimum amount",
                            amount_max = "Maximum amount",
                            card_last_four = "Last 4 digits of card"
                        }
                    },
                    detail = new
                    {
                        url = "/api/reports/detail/{transactionId}",
                        method = "GET",
                        description = "Get detailed transaction information",
                        parameters = new
                        {
                            transactionId = "Transaction ID (required)"
                        }
                    },
                    settlement = new
                    {
                        url = "/api/reports/settlement",
                        method = "GET/POST",
                        description = "Get settlement report",
                        parameters = new
                        {
                            page = "Page number (default: 1)",
                            page_size = "Results per page (default: 50, max: 100)",
                            start_date = "Start date (YYYY-MM-DD)",
                            end_date = "End date (YYYY-MM-DD)"
                        }
                    },
                    export = new
                    {
                        url = "/api/reports/export",
                        method = "GET/POST",
                        description = "Export transaction data",
                        parameters = new
                        {
                            format = "Export format (json, csv, or xml)",
                            filters = "Same filters as search endpoint"
                        }
                    },
                    summary = new
                    {
                        url = "/api/reports/summary",
                        method = "GET/POST",
                        description = "Get summary statistics",
                        parameters = new
                        {
                            start_date = "Start date (YYYY-MM-DD)",
                            end_date = "End date (YYYY-MM-DD)"
                        }
                    },
                    config = new
                    {
                        url = "/api/reports/config",
                        method = "GET",
                        description = "Get API configuration and status"
                    }
                }
            },
            timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
        });
    }

    /// <summary>
    /// Search transactions with filters and pagination
    /// GET/POST /api/reports/search
    /// </summary>
    [HttpGet("search")]
    [HttpPost("search")]
    public async Task<IActionResult> SearchTransactions()
    {
        try
        {
            var filters = GetRequestParameters();

            // Validate date formats if provided
            if (filters.TryGetValue("start_date", out var startDate) && !string.IsNullOrEmpty(startDate))
            {
                if (!ValidateDateFormat(startDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid start_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            if (filters.TryGetValue("end_date", out var endDate) && !string.IsNullOrEmpty(endDate))
            {
                if (!ValidateDateFormat(endDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid end_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            var result = await _reportingService.SearchTransactionsAsync(filters);
            return Ok(result);
        }
        catch (ApiException ex)
        {
            _logger?.LogError(ex, "API error during transaction search");
            return BadRequest(CreateErrorResponse(ex.Message, "API_ERROR"));
        }
        catch (Exception ex)
        {
            _logger?.LogError(ex, "Unexpected error during transaction search");
            return StatusCode(500, CreateErrorResponse($"An unexpected error occurred: {ex.Message}", "INTERNAL_ERROR"));
        }
    }

    /// <summary>
    /// Get transaction details
    /// GET /api/reports/detail/{transactionId}
    /// </summary>
    [HttpGet("detail/{transactionId}")]
    public async Task<IActionResult> GetTransactionDetail(string transactionId)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(transactionId))
            {
                return BadRequest(CreateErrorResponse("Missing required parameter: transaction_id", "VALIDATION_ERROR"));
            }

            var result = await _reportingService.GetTransactionDetailsAsync(transactionId);
            return Ok(result);
        }
        catch (ApiException ex)
        {
            _logger?.LogError(ex, "API error retrieving transaction detail");
            return BadRequest(CreateErrorResponse(ex.Message, "API_ERROR"));
        }
        catch (Exception ex)
        {
            _logger?.LogError(ex, "Unexpected error retrieving transaction detail");
            return StatusCode(500, CreateErrorResponse($"An unexpected error occurred: {ex.Message}", "INTERNAL_ERROR"));
        }
    }

    /// <summary>
    /// Get settlement report
    /// GET/POST /api/reports/settlement
    /// </summary>
    [HttpGet("settlement")]
    [HttpPost("settlement")]
    public async Task<IActionResult> GetSettlementReport()
    {
        try
        {
            var parameters = GetRequestParameters();

            // Validate date formats if provided
            if (parameters.TryGetValue("start_date", out var startDate) && !string.IsNullOrEmpty(startDate))
            {
                if (!ValidateDateFormat(startDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid start_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            if (parameters.TryGetValue("end_date", out var endDate) && !string.IsNullOrEmpty(endDate))
            {
                if (!ValidateDateFormat(endDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid end_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            var result = await _reportingService.GetSettlementReportAsync(parameters);
            return Ok(result);
        }
        catch (ApiException ex)
        {
            _logger?.LogError(ex, "API error generating settlement report");
            return BadRequest(CreateErrorResponse(ex.Message, "API_ERROR"));
        }
        catch (Exception ex)
        {
            _logger?.LogError(ex, "Unexpected error generating settlement report");
            return StatusCode(500, CreateErrorResponse($"An unexpected error occurred: {ex.Message}", "INTERNAL_ERROR"));
        }
    }

    /// <summary>
    /// Export transaction data
    /// GET/POST /api/reports/export
    /// </summary>
    [HttpGet("export")]
    [HttpPost("export")]
    public async Task<IActionResult> ExportTransactions()
    {
        try
        {
            var filters = GetRequestParameters();
            var format = filters.GetValueOrDefault("format", "json").ToLowerInvariant();

            if (!new[] { "json", "csv", "xml" }.Contains(format))
            {
                return BadRequest(CreateErrorResponse("Invalid format. Supported formats: json, csv, xml", "VALIDATION_ERROR"));
            }

            // Validate date formats if provided
            if (filters.TryGetValue("start_date", out var startDate) && !string.IsNullOrEmpty(startDate))
            {
                if (!ValidateDateFormat(startDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid start_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            if (filters.TryGetValue("end_date", out var endDate) && !string.IsNullOrEmpty(endDate))
            {
                if (!ValidateDateFormat(endDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid end_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            var result = await _reportingService.ExportTransactionsAsync(filters, format);
            var resultObj = (dynamic)result;

            if (format == "csv")
            {
                var filename = resultObj.filename ?? "transactions.csv";
                return File(Encoding.UTF8.GetBytes(resultObj.data), "text/csv", filename);
            }
            else if (format == "xml")
            {
                var filename = resultObj.filename ?? "transactions.xml";
                return File(Encoding.UTF8.GetBytes(resultObj.data), "application/xml", filename);
            }

            return Ok(result);
        }
        catch (ApiException ex)
        {
            _logger?.LogError(ex, "API error during export");
            return BadRequest(CreateErrorResponse(ex.Message, "API_ERROR"));
        }
        catch (Exception ex)
        {
            _logger?.LogError(ex, "Unexpected error during export");
            return StatusCode(500, CreateErrorResponse($"An unexpected error occurred: {ex.Message}", "INTERNAL_ERROR"));
        }
    }

    /// <summary>
    /// Get summary statistics
    /// GET/POST /api/reports/summary
    /// </summary>
    [HttpGet("summary")]
    [HttpPost("summary")]
    public async Task<IActionResult> GetSummary()
    {
        try
        {
            var parameters = GetRequestParameters();

            // Validate date formats if provided
            if (parameters.TryGetValue("start_date", out var startDate) && !string.IsNullOrEmpty(startDate))
            {
                if (!ValidateDateFormat(startDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid start_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            if (parameters.TryGetValue("end_date", out var endDate) && !string.IsNullOrEmpty(endDate))
            {
                if (!ValidateDateFormat(endDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid end_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            var result = await _reportingService.GetSummaryStatsAsync(parameters);
            return Ok(result);
        }
        catch (ApiException ex)
        {
            _logger?.LogError(ex, "API error generating summary");
            return BadRequest(CreateErrorResponse(ex.Message, "API_ERROR"));
        }
        catch (Exception ex)
        {
            _logger?.LogError(ex, "Unexpected error generating summary");
            return StatusCode(500, CreateErrorResponse($"An unexpected error occurred: {ex.Message}", "INTERNAL_ERROR"));
        }
    }

    /// <summary>
    /// Get dispute report
    /// GET/POST /api/reports/disputes
    /// </summary>
    [HttpGet("disputes")]
    [HttpPost("disputes")]
    public async Task<IActionResult> GetDisputeReport()
    {
        try
        {
            var filters = GetRequestParameters();

            // Validate date formats if provided
            if (filters.TryGetValue("start_date", out var startDate) && !string.IsNullOrEmpty(startDate))
            {
                if (!ValidateDateFormat(startDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid start_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            if (filters.TryGetValue("end_date", out var endDate) && !string.IsNullOrEmpty(endDate))
            {
                if (!ValidateDateFormat(endDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid end_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            var result = await _reportingService.GetDisputeReportAsync(filters);
            return Ok(result);
        }
        catch (ApiException ex)
        {
            _logger?.LogError(ex, "API error generating dispute report");
            return BadRequest(CreateErrorResponse(ex.Message, "API_ERROR"));
        }
        catch (Exception ex)
        {
            _logger?.LogError(ex, "Unexpected error generating dispute report");
            return StatusCode(500, CreateErrorResponse($"An unexpected error occurred: {ex.Message}", "INTERNAL_ERROR"));
        }
    }

    /// <summary>
    /// Get dispute details
    /// GET /api/reports/disputes/{disputeId}
    /// </summary>
    [HttpGet("disputes/{disputeId}")]
    public async Task<IActionResult> GetDisputeDetail(string disputeId)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(disputeId))
            {
                return BadRequest(CreateErrorResponse("Missing required parameter: dispute_id", "VALIDATION_ERROR"));
            }

            var result = await _reportingService.GetDisputeDetailsAsync(disputeId);
            return Ok(result);
        }
        catch (ApiException ex)
        {
            _logger?.LogError(ex, "API error retrieving dispute detail");
            return BadRequest(CreateErrorResponse(ex.Message, "API_ERROR"));
        }
        catch (Exception ex)
        {
            _logger?.LogError(ex, "Unexpected error retrieving dispute detail");
            return StatusCode(500, CreateErrorResponse($"An unexpected error occurred: {ex.Message}", "INTERNAL_ERROR"));
        }
    }

    /// <summary>
    /// Get deposit report
    /// GET/POST /api/reports/deposits
    /// </summary>
    [HttpGet("deposits")]
    [HttpPost("deposits")]
    public async Task<IActionResult> GetDepositReport()
    {
        try
        {
            var filters = GetRequestParameters();

            // Validate date formats if provided
            if (filters.TryGetValue("start_date", out var startDate) && !string.IsNullOrEmpty(startDate))
            {
                if (!ValidateDateFormat(startDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid start_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            if (filters.TryGetValue("end_date", out var endDate) && !string.IsNullOrEmpty(endDate))
            {
                if (!ValidateDateFormat(endDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid end_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            var result = await _reportingService.GetDepositReportAsync(filters);
            return Ok(result);
        }
        catch (ApiException ex)
        {
            _logger?.LogError(ex, "API error generating deposit report");
            return BadRequest(CreateErrorResponse(ex.Message, "API_ERROR"));
        }
        catch (Exception ex)
        {
            _logger?.LogError(ex, "Unexpected error generating deposit report");
            return StatusCode(500, CreateErrorResponse($"An unexpected error occurred: {ex.Message}", "INTERNAL_ERROR"));
        }
    }

    /// <summary>
    /// Get deposit details
    /// GET /api/reports/deposits/{depositId}
    /// </summary>
    [HttpGet("deposits/{depositId}")]
    public async Task<IActionResult> GetDepositDetail(string depositId)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(depositId))
            {
                return BadRequest(CreateErrorResponse("Missing required parameter: deposit_id", "VALIDATION_ERROR"));
            }

            var result = await _reportingService.GetDepositDetailsAsync(depositId);
            return Ok(result);
        }
        catch (ApiException ex)
        {
            _logger?.LogError(ex, "API error retrieving deposit detail");
            return BadRequest(CreateErrorResponse(ex.Message, "API_ERROR"));
        }
        catch (Exception ex)
        {
            _logger?.LogError(ex, "Unexpected error retrieving deposit detail");
            return StatusCode(500, CreateErrorResponse($"An unexpected error occurred: {ex.Message}", "INTERNAL_ERROR"));
        }
    }

    /// <summary>
    /// Get batch report
    /// GET/POST /api/reports/batches
    /// </summary>
    [HttpGet("batches")]
    [HttpPost("batches")]
    public async Task<IActionResult> GetBatchReport()
    {
        try
        {
            var filters = GetRequestParameters();

            // Validate date formats if provided
            if (filters.TryGetValue("start_date", out var startDate) && !string.IsNullOrEmpty(startDate))
            {
                if (!ValidateDateFormat(startDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid start_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            if (filters.TryGetValue("end_date", out var endDate) && !string.IsNullOrEmpty(endDate))
            {
                if (!ValidateDateFormat(endDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid end_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            var result = await _reportingService.GetBatchReportAsync(filters);
            return Ok(result);
        }
        catch (ApiException ex)
        {
            _logger?.LogError(ex, "API error generating batch report");
            return BadRequest(CreateErrorResponse(ex.Message, "API_ERROR"));
        }
        catch (Exception ex)
        {
            _logger?.LogError(ex, "Unexpected error generating batch report");
            return StatusCode(500, CreateErrorResponse($"An unexpected error occurred: {ex.Message}", "INTERNAL_ERROR"));
        }
    }

    /// <summary>
    /// Get declined transactions report
    /// GET/POST /api/reports/declines
    /// </summary>
    [HttpGet("declines")]
    [HttpPost("declines")]
    public async Task<IActionResult> GetDeclinedTransactions()
    {
        try
        {
            var filters = GetRequestParameters();

            // Validate date formats if provided
            if (filters.TryGetValue("start_date", out var startDate) && !string.IsNullOrEmpty(startDate))
            {
                if (!ValidateDateFormat(startDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid start_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            if (filters.TryGetValue("end_date", out var endDate) && !string.IsNullOrEmpty(endDate))
            {
                if (!ValidateDateFormat(endDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid end_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            var result = await _reportingService.GetDeclinedTransactionsReportAsync(filters);
            return Ok(result);
        }
        catch (ApiException ex)
        {
            _logger?.LogError(ex, "API error generating declines report");
            return BadRequest(CreateErrorResponse(ex.Message, "API_ERROR"));
        }
        catch (Exception ex)
        {
            _logger?.LogError(ex, "Unexpected error generating declines report");
            return StatusCode(500, CreateErrorResponse($"An unexpected error occurred: {ex.Message}", "INTERNAL_ERROR"));
        }
    }

    /// <summary>
    /// Get comprehensive date range report
    /// GET/POST /api/reports/date-range
    /// </summary>
    [HttpGet("date-range")]
    [HttpPost("date-range")]
    public async Task<IActionResult> GetDateRangeReport()
    {
        try
        {
            var parameters = GetRequestParameters();

            // Validate date formats if provided
            if (parameters.TryGetValue("start_date", out var startDate) && !string.IsNullOrEmpty(startDate))
            {
                if (!ValidateDateFormat(startDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid start_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            if (parameters.TryGetValue("end_date", out var endDate) && !string.IsNullOrEmpty(endDate))
            {
                if (!ValidateDateFormat(endDate))
                {
                    return BadRequest(CreateErrorResponse("Invalid end_date format. Use YYYY-MM-DD.", "VALIDATION_ERROR"));
                }
            }

            var result = await _reportingService.GetDateRangeReportAsync(parameters);
            return Ok(result);
        }
        catch (ApiException ex)
        {
            _logger?.LogError(ex, "API error generating date range report");
            return BadRequest(CreateErrorResponse(ex.Message, "API_ERROR"));
        }
        catch (Exception ex)
        {
            _logger?.LogError(ex, "Unexpected error generating date range report");
            return StatusCode(500, CreateErrorResponse($"An unexpected error occurred: {ex.Message}", "INTERNAL_ERROR"));
        }
    }

    /// <summary>
    /// Get API configuration and status
    /// GET /api/reports/config
    /// </summary>
    [HttpGet("config")]
    public IActionResult GetConfig()
    {
        try
        {
            return Ok(new
            {
                success = true,
                data = new
                {
                    sdk_status = new
                    {
                        configured = true,
                        environment = System.Environment.GetEnvironmentVariable("GP_ENVIRONMENT") ?? "sandbox",
                        sdk_version = "9.0.16"
                    },
                    environment_validation = new
                    {
                        has_app_id = !string.IsNullOrEmpty(System.Environment.GetEnvironmentVariable("GP_APP_ID")),
                        has_app_key = !string.IsNullOrEmpty(System.Environment.GetEnvironmentVariable("GP_APP_KEY")),
                        environment_configured = !string.IsNullOrEmpty(System.Environment.GetEnvironmentVariable("GP_ENVIRONMENT"))
                    },
                    api_endpoints = new
                    {
                        search = "/api/reports/search",
                        detail = "/api/reports/detail/{transactionId}",
                        settlement = "/api/reports/settlement",
                        disputes = "/api/reports/disputes",
                        dispute_detail = "/api/reports/disputes/{disputeId}",
                        deposits = "/api/reports/deposits",
                        deposit_detail = "/api/reports/deposits/{depositId}",
                        batches = "/api/reports/batches",
                        declines = "/api/reports/declines",
                        date_range = "/api/reports/date-range",
                        export = "/api/reports/export?format={json|csv|xml}",
                        summary = "/api/reports/summary",
                        config = "/api/reports/config"
                    }
                },
                timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            });
        }
        catch (Exception ex)
        {
            _logger?.LogError(ex, "Error retrieving config");
            return StatusCode(500, CreateErrorResponse($"An unexpected error occurred: {ex.Message}", "INTERNAL_ERROR"));
        }
    }

    // Private helper methods

    /// <summary>
    /// Get request parameters from both GET and POST requests
    /// </summary>
    private Dictionary<string, string> GetRequestParameters()
    {
        var parameters = new Dictionary<string, string>();

        // Get query string parameters
        foreach (var param in Request.Query)
        {
            parameters[param.Key] = param.Value.ToString();
        }

        // Get form/body parameters for POST requests
        if (Request.Method == "POST" && Request.HasFormContentType)
        {
            foreach (var param in Request.Form)
            {
                parameters[param.Key] = param.Value.ToString();
            }
        }

        return parameters;
    }

    /// <summary>
    /// Validate date format (YYYY-MM-DD)
    /// </summary>
    private bool ValidateDateFormat(string date)
    {
        if (string.IsNullOrEmpty(date))
            return true;

        return DateTime.TryParseExact(date, "yyyy-MM-dd",
            System.Globalization.CultureInfo.InvariantCulture,
            System.Globalization.DateTimeStyles.None, out _);
    }

    /// <summary>
    /// Create standardized error response
    /// </summary>
    private object CreateErrorResponse(string message, string errorCode)
    {
        return new
        {
            success = false,
            error = new
            {
                code = errorCode,
                message = message,
                timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            }
        };
    }
}