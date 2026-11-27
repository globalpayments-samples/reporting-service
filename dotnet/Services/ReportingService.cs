using GlobalPayments.Api;
using GlobalPayments.Api.Builders;
using GlobalPayments.Api.Entities;
using GlobalPayments.Api.Entities.Reporting;
using GlobalPayments.Api.Mapping;
using GlobalPayments.Api.Services;
using GlobalPayments.Api.Utils;
using System.Globalization;
using System.Text;
using System.Net;
using System.Net.Http.Headers;
using System.Xml;

namespace CardPaymentSample.Services;

/// <summary>
/// Global Payments Reporting Service
///
/// This service class provides comprehensive reporting functionality for
/// Global Payments transactions including search, filtering, and data export,
/// read from GP API with the Global Payments .NET SDK.
/// </summary>
public class GlobalPaymentsReportingService
{
    // The reports use their own SDK config, so the payment endpoint's default config is left as it is
    private const string ConfigName = "reports";

    private readonly object _configureLock = new();
    private bool _isConfigured;

    // GP API sandbox, https://apis.globalpay.com/ucp for live
    private const string GpApiUrl = "https://apis.sandbox.globalpay.com/ucp";
    private const string GpApiVersion = "2021-03-22";
    // GP API answers gzip compressed
    private static readonly HttpClient Http = new(new HttpClientHandler { AutomaticDecompression = DecompressionMethods.All })
    {
        Timeout = TimeSpan.FromSeconds(60)
    };

    private readonly object _tokenLock = new();
    private string? _accessToken;
    private DateTime _accessTokenExpires;

    /// <summary>
    /// Configure the SDK for GP API on first use, so the docs and the config
    /// check answer before the credentials are set
    /// </summary>
    private void EnsureConfigured()
    {
        lock (_configureLock)
        {
            if (_isConfigured)
            {
                return;
            }

            foreach (var name in new[] { "GP_API_APP_ID", "GP_API_APP_KEY" })
            {
                if (string.IsNullOrEmpty(System.Environment.GetEnvironmentVariable(name)))
                {
                    throw new ArgumentException($"Failed to configure SDK: Missing required environment variable: {name}");
                }
            }

            ServicesContainer.ConfigureService(new GpApiConfig
            {
                AppId = System.Environment.GetEnvironmentVariable("GP_API_APP_ID"),
                AppKey = System.Environment.GetEnvironmentVariable("GP_API_APP_KEY"),
                Environment = GlobalPayments.Api.Entities.Environment.TEST, // Change to PRODUCTION for live
                Channel = Channel.CardNotPresent
            }, ConfigName);
            _isConfigured = true;
        }
    }

    /// <summary>
    /// Search transactions with filters and pagination
    /// </summary>
    public Task<Dictionary<string, object?>> SearchTransactionsAsync(Dictionary<string, string> filters)
    {
        EnsureConfigured();

        var page = IntValue(filters, "page", 1);
        var pageSize = IntValue(filters, "page_size", 10);

        var query = new Dictionary<string, string?>
        {
            ["page"] = page.ToString(CultureInfo.InvariantCulture),
            ["page_size"] = pageSize.ToString(CultureInfo.InvariantCulture),
            ["from_time_created"] = filters.GetValueOrDefault("start_date"),
            ["to_time_created"] = filters.GetValueOrDefault("end_date"),
            ["id"] = filters.GetValueOrDefault("transaction_id"),
            ["number_last4"] = filters.GetValueOrDefault("card_last_four")
        };
        if (Has(filters, "payment_type"))
        {
            query["type"] = filters["payment_type"].ToLowerInvariant() switch
            {
                "sale" => "SALE",
                "refund" => "REFUND",
                _ => throw new ArgumentException("Invalid payment_type. Supported values: sale, refund")
            };
        }

        JsonDoc response;
        try
        {
            response = GpApiGet("/transactions", query);
        }
        catch (ApiException ex)
        {
            throw ReportError("Transaction search failed", ex);
        }

        var transactions = (response.GetEnumerator("transactions") ?? Enumerable.Empty<JsonDoc>())
            .Select(row => FormatTransaction(MapTransaction(row)))
            .ToList();

        // GP API only filters on an exact amount, so the range is applied here
        if (TryDouble(filters, "amount_min", out var min))
        {
            transactions = transactions.Where(t => (double)t["amount"]! >= min).ToList();
        }
        if (TryDouble(filters, "amount_max", out var max))
        {
            transactions = transactions.Where(t => (double)t["amount"]! <= max).ToList();
        }

        // Status is not a search filter in this sample, it is applied to the page
        if (Has(filters, "status"))
        {
            var status = filters["status"].ToUpperInvariant();
            transactions = transactions.Where(t => ((string)t["status"]!).ToUpperInvariant() == status).ToList();
        }

        return Task.FromResult(Success(new Dictionary<string, object?>
        {
            ["transactions"] = transactions,
            ["pagination"] = new Dictionary<string, object?>
            {
                ["page"] = page,
                ["page_size"] = pageSize,
                ["total_count"] = transactions.Count,
                ["original_total_count"] = TotalCount(response)
            }
        }));
    }

    /// <summary>
    /// Get detailed information for a specific transaction
    /// </summary>
    public Task<Dictionary<string, object?>> GetTransactionDetailsAsync(string transactionId)
    {
        EnsureConfigured();

        JsonDoc response;
        try
        {
            response = GpApiGet($"/transactions/{Uri.EscapeDataString(transactionId)}");
        }
        catch (ApiException ex)
        {
            throw ReportError("Failed to retrieve transaction details", ex);
        }

        var transaction = MapTransaction(response);

        var info = FormatTransaction(transaction);
        return Task.FromResult(Success(new Dictionary<string, object?>
        {
            ["transaction_id"] = info["transaction_id"],
            ["timestamp"] = info["timestamp"],
            ["amount"] = info["amount"],
            ["currency"] = info["currency"],
            ["status"] = info["status"],
            ["payment_method"] = info["payment_method"],
            ["card_details"] = new Dictionary<string, object?>
            {
                ["masked_number"] = info["card_last_four"],
                ["card_type"] = transaction.CardType ?? "",
                ["entry_mode"] = transaction.EntryMode ?? ""
            },
            ["auth_code"] = info["auth_code"],
            ["reference_number"] = info["reference_number"],
            ["gateway_response_code"] = response.Get("payment_method")?.GetValue<string>("result") ?? "",
            ["gateway_response_message"] = transaction.GatewayResponseMessage ?? ""
        }));
    }

    /// <summary>
    /// Generate settlement report for a date range
    /// </summary>
    public Task<Dictionary<string, object?>> GetSettlementReportAsync(Dictionary<string, string> parameters)
    {
        EnsureConfigured();

        var page = IntValue(parameters, "page", 1);
        var pageSize = IntValue(parameters, "page_size", 50);

        var reportBuilder = ReportingService.FindSettlementTransactionsPaged(page, pageSize);
        ApplyDateRange(reportBuilder, parameters);

        PagedResult<TransactionSummary> response;
        try
        {
            response = reportBuilder.Execute(ConfigName);
        }
        catch (ApiException ex)
        {
            throw ReportError("Settlement report generation failed", ex);
        }

        // GP API returns one settled transaction per row, so each row counts one transaction
        var settlements = response.Results.Select(s => new Dictionary<string, object?>
        {
            ["settlement_id"] = s.TransactionId ?? "",
            ["settlement_date"] = s.DepositDate.HasValue ? DateString(s.DepositDate) : IsoString(s.TransactionDate),
            ["transaction_count"] = 1,
            ["total_amount"] = Amount(s.Amount),
            ["currency"] = Currency(s.Currency),
            ["status"] = s.TransactionStatus ?? ""
        }).ToList();

        var totalAmount = settlements.Sum(s => (double)s["total_amount"]!);
        return Task.FromResult(Success(new Dictionary<string, object?>
        {
            ["settlements"] = settlements,
            ["summary"] = new Dictionary<string, object?>
            {
                ["total_settlements"] = settlements.Count,
                ["total_amount"] = totalAmount,
                ["total_transactions"] = settlements.Count,
                ["average_settlement_amount"] = settlements.Count > 0 ? totalAmount / settlements.Count : 0.0
            },
            ["pagination"] = Pagination(page, pageSize, response.TotalRecordCount)
        }));
    }

    /// <summary>
    /// Get dispute report with filtering and pagination
    /// </summary>
    public Task<Dictionary<string, object?>> GetDisputeReportAsync(Dictionary<string, string> filters)
    {
        EnsureConfigured();

        var page = IntValue(filters, "page", 1);
        var pageSize = IntValue(filters, "page_size", 10);

        // GP API filters disputes on the stage date (from_stage_time_created / to_stage_time_created)
        var reportBuilder = ReportingService.FindDisputesPaged(page, pageSize);
        if (Has(filters, "start_date"))
        {
            reportBuilder.Where(DataServiceCriteria.StartStageDate, ParseDate(filters["start_date"]));
        }
        if (Has(filters, "end_date"))
        {
            reportBuilder.Where(DataServiceCriteria.EndStageDate, ParseDate(filters["end_date"]));
        }
        if (Has(filters, "stage"))
        {
            reportBuilder.Where(SearchCriteria.DisputeStage, MapEnum<DisputeStage>(filters["stage"], "stage"));
        }
        if (Has(filters, "status"))
        {
            reportBuilder.Where(SearchCriteria.DisputeStatus, MapEnum<DisputeStatus>(filters["status"], "status"));
        }

        PagedResult<DisputeSummary> response;
        try
        {
            response = reportBuilder.Execute(ConfigName);
        }
        catch (ApiException ex)
        {
            throw ReportError("Dispute report generation failed", ex);
        }

        return Task.FromResult(Success(new Dictionary<string, object?>
        {
            ["disputes"] = response.Results.Select(FormatDispute).ToList(),
            ["pagination"] = Pagination(page, pageSize, response.TotalRecordCount)
        }));
    }

    /// <summary>
    /// Get detailed information for a specific dispute
    /// </summary>
    public Task<Dictionary<string, object?>> GetDisputeDetailsAsync(string disputeId)
    {
        EnsureConfigured();

        JsonDoc response;
        try
        {
            response = GpApiGet($"/disputes/{Uri.EscapeDataString(disputeId)}");
        }
        catch (ApiException ex)
        {
            throw ReportError("Failed to retrieve dispute details", ex);
        }

        var dispute = GpApiMapping.MapDisputeSummary(response);
        var details = FormatDispute(dispute);

        // The SDK's dispute document mapping skips documents that have an id, so they are read here
        var documents = (response.GetEnumerator("documents") ?? Enumerable.Empty<JsonDoc>())
            .Where(document => !string.IsNullOrEmpty(document.GetValue<string>("id")))
            .Select(document => new Dictionary<string, object?>
            {
                ["id"] = document.GetValue<string>("id"),
                ["type"] = document.GetValue<string>("type") ?? ""
            })
            .ToList();

        var transaction = response.Get("transaction");
        details["transaction_id"] = transaction?.GetValue<string>("id") ?? "";
        details["case_description"] = response.GetValue<string>("description") ?? "";
        details["documents"] = documents;
        details["transaction_details"] = new Dictionary<string, object?>
        {
            ["amount"] = transaction != null ? MinorAmount(transaction.GetValue<string>("amount")) : 0.0,
            ["currency"] = Currency(transaction?.GetValue<string>("currency")),
            ["masked_card_number"] = DisputeMaskedCard(response),
            ["arn"] = dispute.TransactionARN ?? ""
        };
        return Task.FromResult(Success(details));
    }

    /// <summary>
    /// Get deposit report with filtering and pagination
    /// </summary>
    public Task<Dictionary<string, object?>> GetDepositReportAsync(Dictionary<string, string> filters)
    {
        EnsureConfigured();

        var page = IntValue(filters, "page", 1);
        var pageSize = IntValue(filters, "page_size", 10);

        var reportBuilder = ReportingService.FindDepositsPaged(page, pageSize);
        ApplyDateRange(reportBuilder, filters);
        if (Has(filters, "deposit_id"))
        {
            // The SDK sends the deposit reference as the GP API deposit id
            reportBuilder.Where(DataServiceCriteria.DepositReference, filters["deposit_id"]);
        }
        if (Has(filters, "status"))
        {
            reportBuilder.Where(SearchCriteria.DepositStatus, MapEnum<DepositStatus>(filters["status"], "status"));
        }

        PagedResult<DepositSummary> response;
        try
        {
            response = reportBuilder.Execute(ConfigName);
        }
        catch (ApiException ex)
        {
            throw ReportError("Deposit report generation failed", ex);
        }

        var deposits = response.Results.Select(d =>
        {
            var row = FormatDeposit(d);
            row["sales_count"] = d.SalesTotalCount;
            row["sales_amount"] = Amount(d.SalesTotalAmount);
            row["refunds_count"] = d.RefundsTotalCount;
            row["refunds_amount"] = Amount(d.RefundsTotalAmount);
            return row;
        }).ToList();

        return Task.FromResult(Success(new Dictionary<string, object?>
        {
            ["deposits"] = deposits,
            ["pagination"] = Pagination(page, pageSize, response.TotalRecordCount)
        }));
    }

    /// <summary>
    /// Get detailed information for a specific deposit
    /// </summary>
    public Task<Dictionary<string, object?>> GetDepositDetailsAsync(string depositId)
    {
        EnsureConfigured();

        JsonDoc response;
        try
        {
            response = GpApiGet($"/settlement/deposits/{Uri.EscapeDataString(depositId)}");
        }
        catch (ApiException ex)
        {
            throw ReportError("Failed to retrieve deposit details", ex);
        }

        var deposit = GpApiMapping.MapDepositSummary(response);

        var details = FormatDeposit(deposit);
        details["bank_account"] = new Dictionary<string, object?>
        {
            ["masked_account_number"] = response.Get("bank_transfer")?.GetValue<string>("masked_account_number_last4") ?? "",
            ["bank_name"] = ""
        };
        details["transaction_summary"] = new Dictionary<string, object?>
        {
            ["sales_count"] = deposit.SalesTotalCount,
            ["sales_amount"] = Amount(deposit.SalesTotalAmount),
            ["refunds_count"] = deposit.RefundsTotalCount,
            ["refunds_amount"] = Amount(deposit.RefundsTotalAmount),
            ["chargebacks_count"] = deposit.ChargebackTotalCount,
            ["chargebacks_amount"] = Amount(deposit.ChargebackTotalAmount),
            ["adjustments_count"] = deposit.AdjustmentTotalCount,
            ["adjustments_amount"] = Amount(deposit.AdjustmentTotalAmount)
        };
        return Task.FromResult(Success(details));
    }

    /// <summary>
    /// Get batch report. GP API has no batch report endpoint, so this answers
    /// with an API error, the same in every language of this sample.
    /// </summary>
    public Task<Dictionary<string, object?>> GetBatchReportAsync(Dictionary<string, string> filters)
    {
        EnsureConfigured();
        throw new ApiException("Batch report generation failed: batch reports are not available through GP API");
    }

    /// <summary>
    /// Get declined transactions report
    /// </summary>
    public async Task<Dictionary<string, object?>> GetDeclinedTransactionsReportAsync(Dictionary<string, string> filters)
    {
        var declineFilters = new Dictionary<string, string>(filters) { ["status"] = "DECLINED" };

        Dictionary<string, object?> result;
        try
        {
            result = await SearchTransactionsAsync(declineFilters);
        }
        catch (ApiException ex)
        {
            throw ReportError("Declined transactions report generation failed", ex);
        }

        var data = (Dictionary<string, object?>)result["data"]!;
        data["decline_analysis"] = AnalyzeDeclines((List<Dictionary<string, object?>>)data["transactions"]!);
        return result;
    }

    /// <summary>
    /// Get comprehensive date range report across transactions, settlements,
    /// disputes and deposits. A section that fails carries its error.
    /// </summary>
    public async Task<Dictionary<string, object?>> GetDateRangeReportAsync(Dictionary<string, string> parameters)
    {
        EnsureConfigured();

        var startDate = Has(parameters, "start_date") ? parameters["start_date"] : DateTime.Now.AddDays(-30).ToString("yyyy-MM-dd");
        var endDate = Has(parameters, "end_date") ? parameters["end_date"] : DateTime.Now.ToString("yyyy-MM-dd");

        var data = new Dictionary<string, object?>
        {
            ["period"] = new Dictionary<string, object?> { ["start_date"] = startDate, ["end_date"] = endDate }
        };

        var sections = new (string Key, string Label, string Limit, int Default, Func<Dictionary<string, string>, Task<Dictionary<string, object?>>> Run)[]
        {
            ("transactions", "Transactions", "transaction_limit", 100, SearchTransactionsAsync),
            ("settlements", "Settlements", "settlement_limit", 50, GetSettlementReportAsync),
            ("disputes", "Disputes", "dispute_limit", 25, GetDisputeReportAsync),
            ("deposits", "Deposits", "deposit_limit", 25, GetDepositReportAsync)
        };

        foreach (var section in sections)
        {
            var filters = new Dictionary<string, string>
            {
                ["start_date"] = startDate,
                ["end_date"] = endDate,
                ["page_size"] = IntValue(parameters, section.Limit, section.Default).ToString()
            };
            try
            {
                data[section.Key] = (await section.Run(filters))["data"];
            }
            catch (ApiException ex)
            {
                data[section.Key] = new Dictionary<string, object?> { ["error"] = $"{section.Label} not available: {ex.Message}" };
            }
            catch (Exception ex) when (ex is not ArgumentException)
            {
                Console.Error.WriteLine($"{section.Label} not available for the date range report: {ex}");
                data[section.Key] = new Dictionary<string, object?> { ["error"] = $"{section.Label} not available" };
            }
        }

        data["summary"] = GenerateComprehensiveSummary(data);
        return Success(data);
    }

    /// <summary>
    /// Export transaction data in specified format (json, csv or xml)
    /// </summary>
    public async Task<Dictionary<string, object?>> ExportTransactionsAsync(Dictionary<string, string> filters, string format)
    {
        // GP API pages hold up to 1000 transactions
        var exportFilters = new Dictionary<string, string>(filters) { ["page_size"] = "1000" };

        Dictionary<string, object?> result;
        try
        {
            result = await SearchTransactionsAsync(exportFilters);
        }
        catch (ApiException ex)
        {
            throw ReportError("Export failed", ex);
        }

        var transactions = (List<Dictionary<string, object?>>)((Dictionary<string, object?>)result["data"]!)["transactions"]!;

        return format switch
        {
            "csv" => ExportToCsv(transactions),
            "xml" => ExportToXml(transactions),
            _ => new Dictionary<string, object?>
            {
                ["success"] = true,
                ["data"] = transactions,
                ["format"] = "json",
                ["timestamp"] = Now()
            }
        };
    }

    /// <summary>
    /// Get reporting summary statistics
    /// </summary>
    public async Task<Dictionary<string, object?>> GetSummaryStatsAsync(Dictionary<string, string> parameters)
    {
        EnsureConfigured();

        var startDate = Has(parameters, "start_date") ? parameters["start_date"] : DateTime.Now.AddDays(-30).ToString("yyyy-MM-dd");
        var endDate = Has(parameters, "end_date") ? parameters["end_date"] : DateTime.Now.ToString("yyyy-MM-dd");

        Dictionary<string, object?> result;
        try
        {
            result = await SearchTransactionsAsync(new Dictionary<string, string>
            {
                ["start_date"] = startDate,
                ["end_date"] = endDate,
                ["page_size"] = "1000"
            });
        }
        catch (Exception ex) when (ex is not ArgumentException)
        {
            var message = "Failed to generate summary statistics";
            if (ex is ApiException)
            {
                message += $": {ex.Message}";
            }
            else
            {
                Console.Error.WriteLine($"{message}: {ex}");
            }
            return new Dictionary<string, object?> { ["success"] = false, ["error"] = message, ["timestamp"] = Now() };
        }

        var transactions = (List<Dictionary<string, object?>>)((Dictionary<string, object?>)result["data"]!)["transactions"]!;
        return new Dictionary<string, object?>
        {
            ["success"] = true,
            ["data"] = CalculateSummaryStats(transactions),
            ["period"] = new Dictionary<string, object?> { ["start_date"] = startDate, ["end_date"] = endDate },
            ["timestamp"] = Now()
        };
    }

    /// <summary>
    /// Get SDK configuration status. Answers without credentials.
    /// </summary>
    public static Dictionary<string, object?> GetSdkConfigStatus()
    {
        var hasAppId = !string.IsNullOrEmpty(System.Environment.GetEnvironmentVariable("GP_API_APP_ID"));
        var hasAppKey = !string.IsNullOrEmpty(System.Environment.GetEnvironmentVariable("GP_API_APP_KEY"));
        var isConfigured = hasAppId && hasAppKey;

        return new Dictionary<string, object?>
        {
            ["configured"] = isConfigured,
            ["has_app_id"] = hasAppId,
            ["has_app_key"] = hasAppKey,
            ["environment"] = isConfigured ? "TEST" : "Not configured",
            ["timestamp"] = Now()
        };
    }

    /// <summary>
    /// Validate environment configuration. Answers without credentials.
    /// </summary>
    public static Dictionary<string, object?> ValidateEnvironmentConfig()
    {
        var errors = new List<string>();
        var warnings = new List<string>();

        foreach (var name in new[] { "GP_API_APP_ID", "GP_API_APP_KEY" })
        {
            if (string.IsNullOrEmpty(System.Environment.GetEnvironmentVariable(name)))
            {
                errors.Add($"Missing required environment variable: {name}");
            }
        }
        foreach (var name in new[] { "PUBLIC_API_KEY", "SECRET_API_KEY" })
        {
            if (!string.IsNullOrEmpty(System.Environment.GetEnvironmentVariable(name)))
            {
                warnings.Add($"Legacy variable {name} found. GP-API uses GP_API_APP_ID and GP_API_APP_KEY.");
            }
        }

        return new Dictionary<string, object?>
        {
            ["valid"] = errors.Count == 0,
            ["errors"] = errors,
            ["warnings"] = warnings
        };
    }

    #region Helper methods

    /// <summary>
    /// Wrap an SDK error with the report that failed. GP API errors read
    /// "Status Code: ERROR_CODE - description", as in the other SDKs (the .NET
    /// SDK puts the HTTP status name there).
    /// </summary>
    private static ApiException ReportError(string prefix, ApiException ex)
    {
        var message = ex.Message;
        if (ex is GatewayException gateway && !string.IsNullOrEmpty(gateway.ResponseCode)
            && message.StartsWith("Status Code: ") && message.Contains(" - "))
        {
            message = $"Status Code: {gateway.ResponseCode}{message[message.IndexOf(" - ", StringComparison.Ordinal)..]}";
        }
        return new ApiException($"{prefix}: {message}", ex);
    }

    private static Dictionary<string, object?> Success(Dictionary<string, object?> data) => new()
    {
        ["success"] = true,
        ["data"] = data,
        ["timestamp"] = Now()
    };

    private static Dictionary<string, object?> Pagination(int page, int pageSize, int totalCount) => new()
    {
        ["page"] = page,
        ["page_size"] = pageSize,
        ["total_count"] = totalCount
    };

    private static string Now() => DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");

    private static bool Has(Dictionary<string, string> map, string key) =>
        map.TryGetValue(key, out var value) && !string.IsNullOrEmpty(value);

    private static int IntValue(Dictionary<string, string> map, string key, int defaultValue) =>
        map.TryGetValue(key, out var value) && int.TryParse(value, out var number) && number > 0 ? number : defaultValue;

    private static bool TryDouble(Dictionary<string, string> map, string key, out double value)
    {
        value = 0;
        return Has(map, key) && double.TryParse(map[key], NumberStyles.Float, CultureInfo.InvariantCulture, out value);
    }

    /// <summary>A YYYY-MM-DD date, which the SDK writes back as the same day</summary>
    private static DateTime ParseDate(string value) =>
        DateTime.ParseExact(value, "yyyy-MM-dd", CultureInfo.InvariantCulture);

    /// <summary>
    /// Dates go through Where(): the report builders the SDK hands out are
    /// ReportBuilders, whose WithStartDate() the GP API request does not read
    /// </summary>
    private static void ApplyDateRange<T>(ReportBuilder<T> builder, Dictionary<string, string> filters) where T : class
    {
        if (Has(filters, "start_date"))
        {
            builder.Where(SearchCriteria.StartDate, ParseDate(filters["start_date"]));
        }
        if (Has(filters, "end_date"))
        {
            builder.Where(SearchCriteria.EndDate, ParseDate(filters["end_date"]));
        }
    }

    /// <summary>
    /// The SDK enum for a GP API value such as CHARGEBACK or UNDER_REVIEW. The SDK
    /// maps its enum names to the GP API values (Chargeback is CHARGEBACK), so the
    /// value is matched without the underscores.
    /// </summary>
    private static T MapEnum<T>(string value, string name) where T : struct, Enum
    {
        var key = value.Replace("_", "").Replace(" ", "");
        if (!int.TryParse(key, out _) && Enum.TryParse<T>(key, true, out var result) && Enum.IsDefined(result))
        {
            return result;
        }
        throw new ArgumentException($"Invalid {name}. Supported values: {string.Join(", ", Enum.GetNames<T>())}");
    }

    private static string Currency(string? value) => string.IsNullOrEmpty(value) ? "USD" : value;

    private static double Amount(decimal? value) => value.HasValue ? (double)value.Value : 0.0;

    /// <summary>A GP API amount in minor units, e.g. "6500" is 65.00</summary>
    private static double MinorAmount(string value) =>
        double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out var number) ? number / 100 : 0.0;

    /// <summary>
    /// SDK times as ISO strings in UTC. GP API sends times in UTC, a time that
    /// comes without a zone is read as UTC as well.
    /// </summary>
    private static string IsoString(DateTime? value)
    {
        if (!value.HasValue)
        {
            return "";
        }
        var time = value.Value.Kind switch
        {
            DateTimeKind.Local => value.Value.ToUniversalTime(),
            DateTimeKind.Unspecified => DateTime.SpecifyKind(value.Value, DateTimeKind.Utc),
            _ => value.Value
        };
        return time.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);
    }

    /// <summary>SDK dates (GP API sends deposit dates as plain dates)</summary>
    private static string DateString(DateTime? value) =>
        value.HasValue ? value.Value.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) : "";

    /// <summary>The card of the disputed transaction when GP API sends one, else the dispute's own card</summary>
    private static string DisputeMaskedCard(JsonDoc dispute)
    {
        var transactionCard = dispute.Get("transaction")?.Get("payment_method")?.Get("card");
        if (transactionCard != null)
        {
            return transactionCard.GetValue<string>("masked_number_first6last4") ?? "";
        }
        return dispute.Get("payment_method")?.Get("card")?.GetValue<string>("number") ?? "";
    }

    private static int TotalCount(JsonDoc response) => response.GetValue<int>("total_record_count", "total_count");

    /// <summary>
    /// Map a GP API transaction with the SDK's mapping. The 3DS block is left
    /// out: the reports do not use it and the SDK's 3DS mapping throws on the
    /// exempt_status field (InvalidCastException to ExemptStatus).
    /// </summary>
    private static TransactionSummary MapTransaction(JsonDoc row)
    {
        row.Get("payment_method")?.Remove("authentication");
        return GpApiMapping.MapTransactionSummary(row);
    }

    /// <summary>
    /// An SDK access token, signed in again before it gets old
    /// </summary>
    private string AccessToken(bool renew)
    {
        lock (_tokenLock)
        {
            if (renew || _accessToken == null || DateTime.UtcNow >= _accessTokenExpires)
            {
                var token = GpApiService.GenerateTransactionKey(new GpApiConfig
                {
                    AppId = System.Environment.GetEnvironmentVariable("GP_API_APP_ID"),
                    AppKey = System.Environment.GetEnvironmentVariable("GP_API_APP_KEY"),
                    Environment = GlobalPayments.Api.Entities.Environment.TEST
                });
                _accessToken = token.Token;
                _accessTokenExpires = DateTime.UtcNow.AddMinutes(10);
            }
            return _accessToken!;
        }
    }

    /// <summary>
    /// GET a GP API endpoint with an SDK access token. Used where the SDK's
    /// report mapping fails on the answer or leaves out fields the reports
    /// show; the answer is then mapped with the SDK's GpApiMapping. GP API
    /// errors read "Status Code: ERROR_CODE - description", as in the SDKs.
    /// </summary>
    private JsonDoc GpApiGet(string endpoint, Dictionary<string, string?>? query = null)
    {
        var url = GpApiUrl + endpoint;
        var parameters = (query ?? new Dictionary<string, string?>())
            .Where(p => !string.IsNullOrEmpty(p.Value))
            .Select(p => $"{Uri.EscapeDataString(p.Key)}={Uri.EscapeDataString(p.Value!)}")
            .ToList();
        if (parameters.Count > 0)
        {
            url += "?" + string.Join("&", parameters);
        }

        HttpResponseMessage Send(bool renew)
        {
            var request = new HttpRequestMessage(HttpMethod.Get, url);
            request.Headers.Add("X-GP-Version", GpApiVersion);
            request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", AccessToken(renew));
            try
            {
                return Http.Send(request);
            }
            catch (Exception ex) when (ex is HttpRequestException || ex is TaskCanceledException)
            {
                throw new ApiException("Error occurred while communicating with gateway.", ex);
            }
        }

        var response = Send(false);
        if (response.StatusCode == HttpStatusCode.Unauthorized)
        {
            response = Send(true);
        }

        var body = response.Content.ReadAsStringAsync().GetAwaiter().GetResult();
        if (!response.IsSuccessStatusCode)
        {
            var error = string.IsNullOrEmpty(body) ? null : JsonDoc.Parse(body);
            var code = error?.GetValue<string>("error_code") ?? ((int)response.StatusCode).ToString(CultureInfo.InvariantCulture);
            var description = error?.GetValue<string>("detailed_error_description") ?? response.ReasonPhrase;
            throw new ApiException($"Status Code: {code} - {description}");
        }
        return JsonDoc.Parse(string.IsNullOrEmpty(body) ? "{}" : body);
    }

    private static Dictionary<string, object?> FormatTransaction(TransactionSummary t) => new()
    {
        ["transaction_id"] = t.TransactionId ?? "",
        ["timestamp"] = IsoString(t.TransactionDate),
        ["amount"] = Amount(t.Amount),
        ["currency"] = Currency(t.Currency),
        ["status"] = t.TransactionStatus ?? "",
        ["payment_method"] = t.PaymentType ?? "",
        ["card_last_four"] = t.MaskedCardNumber ?? "",
        ["auth_code"] = t.AuthCode ?? "",
        ["reference_number"] = t.ReferenceNumber ?? ""
    };

    /// <summary>
    /// Fields shared by the dispute list and the dispute detail. The list has
    /// only the stage time, the detail has the time the case was created.
    /// </summary>
    private static Dictionary<string, object?> FormatDispute(DisputeSummary d) => new()
    {
        ["dispute_id"] = d.CaseId ?? "",
        ["transaction_id"] = "",
        ["case_number"] = "",
        ["dispute_stage"] = d.CaseStage ?? "",
        ["dispute_status"] = d.CaseStatus ?? "",
        ["case_amount"] = Amount(d.CaseAmount),
        ["currency"] = Currency(d.CaseCurrency),
        ["reason_code"] = d.ReasonCode ?? "",
        ["reason_description"] = d.Reason ?? "",
        ["case_time"] = IsoString(d.CaseIdTime ?? d.CaseStageTime),
        ["last_adjustment_time"] = ""
    };

    private static Dictionary<string, object?> FormatDeposit(DepositSummary d) => new()
    {
        ["deposit_id"] = d.DepositId ?? "",
        ["deposit_date"] = DateString(d.DepositDate),
        ["deposit_reference"] = "",
        ["deposit_status"] = d.Status ?? "",
        ["deposit_amount"] = Amount(d.Amount),
        ["currency"] = Currency(d.Currency),
        ["merchant_number"] = d.MerchantNumber ?? "",
        ["merchant_hierarchy"] = d.MerchantHierarchy ?? ""
    };

    private static double Sum(List<Dictionary<string, object?>> rows, string field) =>
        rows.Sum(row => row.TryGetValue(field, out var value) && value is double number ? number : 0.0);

    private static string OrDefault(object? value, string fallback) =>
        value is string text && text != "" ? text : fallback;

    private static Dictionary<string, object?> CalculateSummaryStats(List<Dictionary<string, object?>> transactions)
    {
        var totalAmount = Sum(transactions, "amount");
        var statusCounts = new Dictionary<string, int>();
        var paymentTypeCounts = new Dictionary<string, int>();

        foreach (var t in transactions)
        {
            var status = OrDefault(t["status"], "unknown");
            statusCounts[status] = statusCounts.GetValueOrDefault(status) + 1;
            var paymentType = OrDefault(t["payment_method"], "unknown");
            paymentTypeCounts[paymentType] = paymentTypeCounts.GetValueOrDefault(paymentType) + 1;
        }

        return new Dictionary<string, object?>
        {
            ["total_transactions"] = transactions.Count,
            ["total_amount"] = totalAmount,
            ["average_amount"] = transactions.Count > 0 ? totalAmount / transactions.Count : 0.0,
            ["status_breakdown"] = statusCounts,
            ["payment_type_breakdown"] = paymentTypeCounts
        };
    }

    private static Dictionary<string, object?> AnalyzeDeclines(List<Dictionary<string, object?>> transactions)
    {
        var totalAmount = Sum(transactions, "amount");
        var declineReasons = new Dictionary<string, int>();
        var cardTypes = new Dictionary<string, int>();
        var hourlyBreakdown = new Dictionary<string, int>();

        foreach (var t in transactions)
        {
            // The list rows carry no gateway message
            declineReasons["Unknown"] = declineReasons.GetValueOrDefault("Unknown") + 1;
            var cardType = OrDefault(t["payment_method"], "Unknown");
            cardTypes[cardType] = cardTypes.GetValueOrDefault(cardType) + 1;

            // Hours are counted in UTC, as GP API sends the times
            if (DateTimeOffset.TryParse(t["timestamp"] as string, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var time))
            {
                var hour = time.UtcDateTime.ToString("HH", CultureInfo.InvariantCulture);
                hourlyBreakdown[hour] = hourlyBreakdown.GetValueOrDefault(hour) + 1;
            }
        }

        return new Dictionary<string, object?>
        {
            ["total_declined_transactions"] = transactions.Count,
            ["total_declined_amount"] = totalAmount,
            ["average_declined_amount"] = transactions.Count > 0 ? totalAmount / transactions.Count : 0.0,
            ["decline_reasons"] = declineReasons,
            ["card_type_breakdown"] = cardTypes,
            ["hourly_breakdown"] = hourlyBreakdown
        };
    }

    private static List<Dictionary<string, object?>> SectionRows(Dictionary<string, object?> data, string key) =>
        data.TryGetValue(key, out var section) && section is Dictionary<string, object?> map
            && map.TryGetValue(key, out var rows) && rows is List<Dictionary<string, object?>> list
            ? list : new List<Dictionary<string, object?>>();

    private static Dictionary<string, object?> GenerateComprehensiveSummary(Dictionary<string, object?> data)
    {
        var overview = new Dictionary<string, object?>();
        var financial = new Dictionary<string, object?>();
        var operational = new Dictionary<string, object?>();

        var transactions = SectionRows(data, "transactions");
        if (transactions.Count > 0)
        {
            var total = Sum(transactions, "amount");
            overview["transactions"] = new Dictionary<string, object?>
            {
                ["count"] = transactions.Count,
                ["total_amount"] = total,
                ["average_amount"] = total / transactions.Count
            };
        }

        var settlements = SectionRows(data, "settlements");
        if (settlements.Count > 0)
        {
            financial["settlements"] = new Dictionary<string, object?>
            {
                ["count"] = settlements.Count,
                ["total_amount"] = Sum(settlements, "total_amount")
            };
        }

        var disputes = SectionRows(data, "disputes");
        if (disputes.Count > 0)
        {
            operational["disputes"] = new Dictionary<string, object?>
            {
                ["count"] = disputes.Count,
                ["total_amount"] = Sum(disputes, "case_amount"),
                ["dispute_rate"] = transactions.Count > 0 ? (double)disputes.Count / transactions.Count * 100 : 0.0
            };
        }

        var deposits = SectionRows(data, "deposits");
        if (deposits.Count > 0)
        {
            financial["deposits"] = new Dictionary<string, object?>
            {
                ["count"] = deposits.Count,
                ["total_amount"] = Sum(deposits, "deposit_amount")
            };
        }

        return new Dictionary<string, object?>
        {
            ["overview"] = overview,
            ["financial_summary"] = financial,
            ["operational_metrics"] = operational
        };
    }

    private static string ExportFilename(string extension) =>
        $"transactions_{DateTime.Now:yyyy-MM-dd_HH-mm-ss}.{extension}";

    private static readonly string[] ExportFields =
        { "transaction_id", "timestamp", "amount", "currency", "status", "payment_method", "card_last_four", "auth_code", "reference_number" };

    private static Dictionary<string, object?> ExportToCsv(List<Dictionary<string, object?>> transactions)
    {
        static string Field(object? value)
        {
            var text = value?.ToString() ?? "";
            return text.IndexOfAny(new[] { '"', ',', '\r', '\n' }) >= 0 ? $"\"{text.Replace("\"", "\"\"")}\"" : text;
        }

        var csv = new StringBuilder("Transaction ID,Timestamp,Amount,Currency,Status,Payment Method,Card Last Four,Auth Code,Reference Number\n");
        foreach (var t in transactions)
        {
            csv.Append(string.Join(",", ExportFields.Select(name => name == "amount"
                ? ((double)t[name]!).ToString("F2", CultureInfo.InvariantCulture)
                : Field(t[name])))).Append('\n');
        }

        return new Dictionary<string, object?>
        {
            ["success"] = true,
            ["data"] = csv.ToString(),
            ["format"] = "csv",
            ["filename"] = ExportFilename("csv"),
            ["timestamp"] = Now()
        };
    }

    private static Dictionary<string, object?> ExportToXml(List<Dictionary<string, object?>> transactions)
    {
        var output = new StringBuilder();
        var settings = new XmlWriterSettings { Indent = true, IndentChars = "  ", Encoding = new UTF8Encoding(false), NewLineChars = "\n" };
        using (var writer = XmlWriter.Create(new StringWriterUtf8(output), settings))
        {
            writer.WriteStartDocument();
            writer.WriteStartElement("transactions");
            foreach (var t in transactions)
            {
                writer.WriteStartElement("transaction");
                foreach (var name in ExportFields)
                {
                    var value = t[name];
                    writer.WriteElementString(name, value is double number
                        ? number.ToString(CultureInfo.InvariantCulture)
                        : value?.ToString() ?? "");
                }
                writer.WriteEndElement();
            }
            writer.WriteEndElement();
            writer.WriteEndDocument();
        }

        return new Dictionary<string, object?>
        {
            ["success"] = true,
            ["data"] = output.ToString(),
            ["format"] = "xml",
            ["filename"] = ExportFilename("xml"),
            ["timestamp"] = Now()
        };
    }

    /// <summary>A string writer that declares UTF-8 in the XML header</summary>
    private sealed class StringWriterUtf8 : StringWriter
    {
        public StringWriterUtf8(StringBuilder builder) : base(builder, CultureInfo.InvariantCulture) { }

        public override Encoding Encoding => new UTF8Encoding(false);
    }

    #endregion
}
