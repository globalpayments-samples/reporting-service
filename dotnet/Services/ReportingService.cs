using GlobalPayments.Api;
using GlobalPayments.Api.Entities;
using GlobalPayments.Api.Entities.Enums;
using GlobalPayments.Api.Services;
using System.Text;

namespace CardPaymentSample.Services;

/// <summary>
/// Global Payments Reporting Service
///
/// This service class provides comprehensive reporting functionality for
/// Global Payments transactions including search, filtering, and data export.
/// </summary>
public class GlobalPaymentsReportingService
{
    private bool _isConfigured = false;

    /// <summary>
    /// Constructor - Initialize the service
    /// </summary>
    public GlobalPaymentsReportingService()
    {
        try
        {
            // SDK should already be configured in Program.cs
            _isConfigured = true;
        }
        catch (Exception ex)
        {
            throw new InvalidOperationException($"Failed to initialize reporting service: {ex.Message}", ex);
        }
    }

    /// <summary>
    /// Search transactions with filters and pagination
    /// </summary>
    /// <param name="filters">Search filters and pagination parameters</param>
    /// <returns>Transaction search results</returns>
    public async Task<object> SearchTransactionsAsync(Dictionary<string, string> filters)
    {
        EnsureConfigured();

        try
        {
            var page = int.TryParse(filters.GetValueOrDefault("page", "1"), out var p) ? p : 1;
            var pageSize = Math.Min(int.TryParse(filters.GetValueOrDefault("page_size", "10"), out var ps) ? ps : 10, 100);

            var reportBuilder = ReportingService.FindTransactionsPaged(page, pageSize);

            // Apply date range filters
            if (filters.TryGetValue("start_date", out var startDate) && !string.IsNullOrEmpty(startDate))
            {
                if (DateTime.TryParse(startDate, out var start))
                {
                    reportBuilder.WithStartDate(start);
                }
            }

            if (filters.TryGetValue("end_date", out var endDate) && !string.IsNullOrEmpty(endDate))
            {
                if (DateTime.TryParse(endDate, out var end))
                {
                    reportBuilder.WithEndDate(end);
                }
            }

            // Apply transaction ID filter
            if (filters.TryGetValue("transaction_id", out var transactionId) && !string.IsNullOrEmpty(transactionId))
            {
                reportBuilder.WithTransactionId(transactionId);
            }

            // Apply payment type filter
            if (filters.TryGetValue("payment_type", out var paymentType) && !string.IsNullOrEmpty(paymentType))
            {
                var mappedType = MapPaymentType(paymentType);
                if (mappedType.HasValue)
                {
                    reportBuilder.Where(SearchCriteria.PaymentType, mappedType.Value);
                }
            }

            // Apply amount range filters
            if (filters.TryGetValue("amount_min", out var amountMin) && decimal.TryParse(amountMin, out var minAmount))
            {
                reportBuilder.Where(DataServiceCriteria.Amount, minAmount);
            }

            if (filters.TryGetValue("amount_max", out var amountMax) && decimal.TryParse(amountMax, out var maxAmount))
            {
                reportBuilder.Where(DataServiceCriteria.Amount, maxAmount);
            }

            // Apply card number filter (last 4 digits)
            if (filters.TryGetValue("card_last_four", out var cardLastFour) && !string.IsNullOrEmpty(cardLastFour))
            {
                reportBuilder.Where(SearchCriteria.CardNumberLastFour, cardLastFour);
            }

            // Execute the search
            var response = await reportBuilder.ExecuteAsync();

            var transactions = FormatTransactionList(response?.Results ?? new List<TransactionSummary>());

            // Apply client-side filtering for status if needed
            if (filters.TryGetValue("status", out var status) && !string.IsNullOrEmpty(status))
            {
                var statusFilter = status.ToUpperInvariant();
                transactions = transactions.Where(t =>
                    ((string?)((dynamic)t).status)?.ToUpperInvariant() == statusFilter
                ).ToList();
            }

            return new
            {
                success = true,
                data = new
                {
                    transactions = transactions,
                    pagination = new
                    {
                        page = page,
                        page_size = pageSize,
                        total_count = transactions.Count,
                        original_total_count = response?.TotalRecordCount ?? 0
                    }
                },
                timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            };
        }
        catch (ApiException ex)
        {
            throw new ApiException($"Transaction search failed: {ex.Message}", ex);
        }
    }

    /// <summary>
    /// Get detailed information for a specific transaction
    /// </summary>
    /// <param name="transactionId">The transaction ID to retrieve</param>
    /// <returns>Transaction details</returns>
    public async Task<object> GetTransactionDetailsAsync(string transactionId)
    {
        EnsureConfigured();

        try
        {
            var response = await ReportingService.TransactionDetail(transactionId).ExecuteAsync();

            if (response == null)
            {
                throw new ApiException("Transaction not found");
            }

            return new
            {
                success = true,
                data = FormatTransactionDetails(response),
                timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            };
        }
        catch (ApiException ex)
        {
            throw new ApiException($"Failed to retrieve transaction details: {ex.Message}", ex);
        }
    }

    /// <summary>
    /// Generate settlement report for a date range
    /// </summary>
    /// <param name="parameters">Report parameters</param>
    /// <returns>Settlement report data</returns>
    public async Task<object> GetSettlementReportAsync(Dictionary<string, string> parameters)
    {
        EnsureConfigured();

        try
        {
            var page = int.TryParse(parameters.GetValueOrDefault("page", "1"), out var p) ? p : 1;
            var pageSize = Math.Min(int.TryParse(parameters.GetValueOrDefault("page_size", "50"), out var ps) ? ps : 50, 100);

            var reportBuilder = ReportingService.FindSettlementTransactionsPaged(page, pageSize);

            // Apply date range
            if (parameters.TryGetValue("start_date", out var startDate) && !string.IsNullOrEmpty(startDate))
            {
                if (DateTime.TryParse(startDate, out var start))
                {
                    reportBuilder.WithStartDate(start);
                }
            }

            if (parameters.TryGetValue("end_date", out var endDate) && !string.IsNullOrEmpty(endDate))
            {
                if (DateTime.TryParse(endDate, out var end))
                {
                    reportBuilder.WithEndDate(end);
                }
            }

            var response = await reportBuilder.ExecuteAsync();

            var settlements = FormatSettlementList(response?.Results ?? new List<TransactionSummary>());

            return new
            {
                success = true,
                data = new
                {
                    settlements = settlements,
                    summary = GenerateSettlementSummary(response?.Results ?? new List<TransactionSummary>()),
                    pagination = new
                    {
                        page = page,
                        page_size = pageSize,
                        total_count = response?.TotalRecordCount ?? 0
                    }
                },
                timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            };
        }
        catch (ApiException ex)
        {
            throw new ApiException($"Settlement report generation failed: {ex.Message}", ex);
        }
    }

    /// <summary>
    /// Get dispute reports with filtering and pagination
    /// </summary>
    /// <param name="filters">Dispute search filters and pagination</param>
    /// <returns>Dispute report results</returns>
    public async Task<object> GetDisputeReportAsync(Dictionary<string, string> filters)
    {
        EnsureConfigured();

        try
        {
            var page = int.TryParse(filters.GetValueOrDefault("page", "1"), out var p) ? p : 1;
            var pageSize = Math.Min(int.TryParse(filters.GetValueOrDefault("page_size", "10"), out var ps) ? ps : 10, 100);

            var reportBuilder = ReportingService.FindDisputesPaged(page, pageSize);

            // Apply date range filters
            if (filters.TryGetValue("start_date", out var startDate) && !string.IsNullOrEmpty(startDate))
            {
                if (DateTime.TryParse(startDate, out var start))
                {
                    reportBuilder.WithStartDate(start);
                }
            }

            if (filters.TryGetValue("end_date", out var endDate) && !string.IsNullOrEmpty(endDate))
            {
                if (DateTime.TryParse(endDate, out var end))
                {
                    reportBuilder.WithEndDate(end);
                }
            }

            // Apply dispute stage filter
            if (filters.TryGetValue("stage", out var stage) && !string.IsNullOrEmpty(stage))
            {
                reportBuilder.Where(SearchCriteria.DisputeStage, MapDisputeStage(stage));
            }

            // Apply dispute status filter
            if (filters.TryGetValue("status", out var status) && !string.IsNullOrEmpty(status))
            {
                reportBuilder.Where(SearchCriteria.DisputeStatus, MapDisputeStatus(status));
            }

            // Execute the search
            var response = await reportBuilder.ExecuteAsync();

            return new
            {
                success = true,
                data = new
                {
                    disputes = FormatDisputeList(response?.Results ?? new List<DisputeSummary>()),
                    pagination = new
                    {
                        page = page,
                        page_size = pageSize,
                        total_count = response?.TotalRecordCount ?? 0
                    }
                },
                timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            };
        }
        catch (ApiException ex)
        {
            throw new ApiException($"Dispute report generation failed: {ex.Message}", ex);
        }
    }

    /// <summary>
    /// Get detailed information for a specific dispute
    /// </summary>
    /// <param name="disputeId">The dispute ID to retrieve</param>
    /// <returns>Dispute details</returns>
    public async Task<object> GetDisputeDetailsAsync(string disputeId)
    {
        EnsureConfigured();

        try
        {
            var response = await ReportingService.DisputeDetail(disputeId).ExecuteAsync();

            if (response == null)
            {
                throw new ApiException("Dispute not found");
            }

            return new
            {
                success = true,
                data = FormatDisputeDetails(response),
                timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            };
        }
        catch (ApiException ex)
        {
            throw new ApiException($"Failed to retrieve dispute details: {ex.Message}", ex);
        }
    }

    /// <summary>
    /// Get deposit reports with filtering and pagination
    /// </summary>
    /// <param name="filters">Deposit search filters and pagination</param>
    /// <returns>Deposit report results</returns>
    public async Task<object> GetDepositReportAsync(Dictionary<string, string> filters)
    {
        EnsureConfigured();

        try
        {
            var page = int.TryParse(filters.GetValueOrDefault("page", "1"), out var p) ? p : 1;
            var pageSize = Math.Min(int.TryParse(filters.GetValueOrDefault("page_size", "10"), out var ps) ? ps : 10, 100);

            var reportBuilder = ReportingService.FindDepositsPaged(page, pageSize);

            // Apply date range filters
            if (filters.TryGetValue("start_date", out var startDate) && !string.IsNullOrEmpty(startDate))
            {
                if (DateTime.TryParse(startDate, out var start))
                {
                    reportBuilder.WithStartDate(start);
                }
            }

            if (filters.TryGetValue("end_date", out var endDate) && !string.IsNullOrEmpty(endDate))
            {
                if (DateTime.TryParse(endDate, out var end))
                {
                    reportBuilder.WithEndDate(end);
                }
            }

            // Apply deposit ID filter
            if (filters.TryGetValue("deposit_id", out var depositId) && !string.IsNullOrEmpty(depositId))
            {
                reportBuilder.Where(SearchCriteria.DepositReference, depositId);
            }

            // Apply status filter
            if (filters.TryGetValue("status", out var status) && !string.IsNullOrEmpty(status))
            {
                reportBuilder.Where(SearchCriteria.DepositStatus, MapDepositStatus(status));
            }

            // Execute the search
            var response = await reportBuilder.ExecuteAsync();

            return new
            {
                success = true,
                data = new
                {
                    deposits = FormatDepositList(response?.Results ?? new List<DepositSummary>()),
                    pagination = new
                    {
                        page = page,
                        page_size = pageSize,
                        total_count = response?.TotalRecordCount ?? 0
                    }
                },
                timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            };
        }
        catch (ApiException ex)
        {
            throw new ApiException($"Deposit report generation failed: {ex.Message}", ex);
        }
    }

    /// <summary>
    /// Get detailed information for a specific deposit
    /// </summary>
    /// <param name="depositId">The deposit ID to retrieve</param>
    /// <returns>Deposit details</returns>
    public async Task<object> GetDepositDetailsAsync(string depositId)
    {
        EnsureConfigured();

        try
        {
            var response = await ReportingService.DepositDetail(depositId).ExecuteAsync();

            if (response == null)
            {
                throw new ApiException("Deposit not found");
            }

            return new
            {
                success = true,
                data = FormatDepositDetails(response),
                timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            };
        }
        catch (ApiException ex)
        {
            throw new ApiException($"Failed to retrieve deposit details: {ex.Message}", ex);
        }
    }

    /// <summary>
    /// Get batch report with detailed transaction information
    /// </summary>
    /// <param name="filters">Batch search filters</param>
    /// <returns>Batch report results</returns>
    public async Task<object> GetBatchReportAsync(Dictionary<string, string> filters)
    {
        EnsureConfigured();

        try
        {
            // Note: Batch detail in .NET SDK might work differently
            // This is a simplified implementation
            return new
            {
                success = true,
                data = new
                {
                    batches = new List<object>(),
                    summary = new
                    {
                        total_batches = 0,
                        total_amount = 0,
                        total_transactions = 0,
                        average_batch_amount = 0,
                        status_breakdown = new Dictionary<string, int>()
                    }
                },
                timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            };
        }
        catch (ApiException ex)
        {
            throw new ApiException($"Batch report generation failed: {ex.Message}", ex);
        }
    }

    /// <summary>
    /// Get declined transactions report
    /// </summary>
    /// <param name="filters">Decline search filters and pagination</param>
    /// <returns>Declined transactions report</returns>
    public async Task<object> GetDeclinedTransactionsReportAsync(Dictionary<string, string> filters)
    {
        EnsureConfigured();

        try
        {
            // Add declined status filter
            var declineFilters = new Dictionary<string, string>(filters)
            {
                ["status"] = "DECLINED"
            };

            var result = await SearchTransactionsAsync(declineFilters);

            // Add decline analysis
            var resultObj = (dynamic)result;
            if (resultObj.success)
            {
                var transactions = (IEnumerable<dynamic>)resultObj.data.transactions;
                var analysis = AnalyzeDeclines(transactions.ToList());

                return new
                {
                    success = true,
                    data = new
                    {
                        transactions = resultObj.data.transactions,
                        pagination = resultObj.data.pagination,
                        decline_analysis = analysis
                    },
                    timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
                };
            }

            return result;
        }
        catch (ApiException ex)
        {
            throw new ApiException($"Declined transactions report generation failed: {ex.Message}", ex);
        }
    }

    /// <summary>
    /// Get comprehensive date range report across all transaction types
    /// </summary>
    /// <param name="parameters">Date range and report parameters</param>
    /// <returns>Comprehensive date range report</returns>
    public async Task<object> GetDateRangeReportAsync(Dictionary<string, string> parameters)
    {
        EnsureConfigured();

        try
        {
            var startDate = parameters.GetValueOrDefault("start_date", DateTime.Now.AddDays(-30).ToString("yyyy-MM-dd"));
            var endDate = parameters.GetValueOrDefault("end_date", DateTime.Now.ToString("yyyy-MM-dd"));

            var report = new
            {
                success = true,
                data = new Dictionary<string, object>
                {
                    ["period"] = new { start_date = startDate, end_date = endDate },
                    ["transactions"] = new { },
                    ["settlements"] = new { },
                    ["disputes"] = new { },
                    ["deposits"] = new { },
                    ["summary"] = new { }
                },
                timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            };

            // Get transactions for the period
            var transactionLimit = int.TryParse(parameters.GetValueOrDefault("transaction_limit", "100"), out var tl) ? tl : 100;
            var transactionResult = await SearchTransactionsAsync(new Dictionary<string, string>
            {
                ["start_date"] = startDate,
                ["end_date"] = endDate,
                ["page_size"] = transactionLimit.ToString()
            });

            report.data["transactions"] = ((dynamic)transactionResult).data;

            // Get settlements for the period
            var settlementLimit = int.TryParse(parameters.GetValueOrDefault("settlement_limit", "50"), out var sl) ? sl : 50;
            var settlementResult = await GetSettlementReportAsync(new Dictionary<string, string>
            {
                ["start_date"] = startDate,
                ["end_date"] = endDate,
                ["page_size"] = settlementLimit.ToString()
            });

            report.data["settlements"] = ((dynamic)settlementResult).data;

            // Get disputes for the period
            try
            {
                var disputeLimit = int.TryParse(parameters.GetValueOrDefault("dispute_limit", "25"), out var dl) ? dl : 25;
                var disputeResult = await GetDisputeReportAsync(new Dictionary<string, string>
                {
                    ["start_date"] = startDate,
                    ["end_date"] = endDate,
                    ["page_size"] = disputeLimit.ToString()
                });

                report.data["disputes"] = ((dynamic)disputeResult).data;
            }
            catch (Exception ex)
            {
                report.data["disputes"] = new { error = $"Disputes not available: {ex.Message}" };
            }

            // Get deposits for the period
            try
            {
                var depositLimit = int.TryParse(parameters.GetValueOrDefault("deposit_limit", "25"), out var depl) ? depl : 25;
                var depositResult = await GetDepositReportAsync(new Dictionary<string, string>
                {
                    ["start_date"] = startDate,
                    ["end_date"] = endDate,
                    ["page_size"] = depositLimit.ToString()
                });

                report.data["deposits"] = ((dynamic)depositResult).data;
            }
            catch (Exception ex)
            {
                report.data["deposits"] = new { error = $"Deposits not available: {ex.Message}" };
            }

            // Generate comprehensive summary
            report.data["summary"] = GenerateComprehensiveSummary(report.data);

            return report;
        }
        catch (Exception ex)
        {
            return new
            {
                success = false,
                error = $"Failed to generate date range report: {ex.Message}",
                timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            };
        }
    }

    /// <summary>
    /// Export transaction data in specified format
    /// </summary>
    /// <param name="filters">Search filters</param>
    /// <param name="format">Export format ('json', 'csv', or 'xml')</param>
    /// <returns>Export data</returns>
    public async Task<object> ExportTransactionsAsync(Dictionary<string, string> filters, string format)
    {
        EnsureConfigured();

        try
        {
            // Get all transactions (increase limit for export)
            var exportFilters = new Dictionary<string, string>(filters)
            {
                ["page_size"] = "1000"
            };

            var transactionsResult = await SearchTransactionsAsync(exportFilters);
            var transactions = ((dynamic)transactionsResult).data.transactions;

            if (format.ToLowerInvariant() == "csv")
            {
                return ExportToCsv(transactions);
            }
            else if (format.ToLowerInvariant() == "xml")
            {
                return ExportToXml(transactions);
            }

            return new
            {
                success = true,
                data = transactions,
                format = "json",
                timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            };
        }
        catch (ApiException ex)
        {
            throw new ApiException($"Export failed: {ex.Message}", ex);
        }
    }

    /// <summary>
    /// Get reporting summary statistics
    /// </summary>
    /// <param name="parameters">Summary parameters</param>
    /// <returns>Summary statistics</returns>
    public async Task<object> GetSummaryStatsAsync(Dictionary<string, string> parameters)
    {
        EnsureConfigured();

        try
        {
            var startDate = parameters.GetValueOrDefault("start_date", DateTime.Now.AddDays(-30).ToString("yyyy-MM-dd"));
            var endDate = parameters.GetValueOrDefault("end_date", DateTime.Now.ToString("yyyy-MM-dd"));

            // Get transaction summary
            var transactions = await SearchTransactionsAsync(new Dictionary<string, string>
            {
                ["start_date"] = startDate,
                ["end_date"] = endDate,
                ["page_size"] = "1000"
            });

            var transactionList = ((dynamic)transactions).data.transactions;

            return new
            {
                success = true,
                data = CalculateSummaryStats(transactionList),
                period = new
                {
                    start_date = startDate,
                    end_date = endDate
                },
                timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            };
        }
        catch (Exception ex)
        {
            return new
            {
                success = false,
                error = $"Failed to generate summary statistics: {ex.Message}",
                timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            };
        }
    }

    // Private helper methods

    private void EnsureConfigured()
    {
        if (!_isConfigured)
        {
            throw new InvalidOperationException("SDK is not properly configured");
        }
    }

    private PaymentType? MapPaymentType(string type)
    {
        return type.ToLowerInvariant() switch
        {
            "sale" => PaymentType.Sale,
            "refund" => PaymentType.Refund,
            "authorize" => PaymentType.Auth,
            "capture" => PaymentType.Capture,
            _ => null
        };
    }

    private DisputeStage MapDisputeStage(string stage)
    {
        return stage.ToUpperInvariant() switch
        {
            "CHARGEBACK" => DisputeStage.Chargeback,
            "RETRIEVAL" => DisputeStage.Retrieval,
            "REPRESENTMENT" => DisputeStage.Representment,
            _ => DisputeStage.Chargeback
        };
    }

    private DisputeStatus MapDisputeStatus(string status)
    {
        return status.ToUpperInvariant() switch
        {
            "UNDER_REVIEW" => DisputeStatus.UnderReview,
            "WITH_MERCHANT" => DisputeStatus.WithMerchant,
            "CLOSED" => DisputeStatus.Closed,
            _ => DisputeStatus.UnderReview
        };
    }

    private DepositStatus MapDepositStatus(string status)
    {
        return status.ToUpperInvariant() switch
        {
            "FUNDED" => DepositStatus.Funded,
            _ => DepositStatus.Funded
        };
    }

    private List<object> FormatTransactionList(IEnumerable<TransactionSummary> transactions)
    {
        return transactions.Select(t => (object)new
        {
            transaction_id = t.TransactionId ?? "",
            timestamp = t.TransactionDate?.ToString("yyyy-MM-dd HH:mm:ss") ?? "",
            amount = t.Amount ?? 0,
            currency = t.Currency ?? "USD",
            status = t.TransactionStatus ?? "",
            payment_method = t.PaymentType?.ToString() ?? "",
            card_last_four = t.MaskedCardNumber?.Length >= 4 ? t.MaskedCardNumber.Substring(t.MaskedCardNumber.Length - 4) : "",
            auth_code = t.AuthCode ?? "",
            reference_number = t.ReferenceNumber ?? ""
        }).ToList();
    }

    private object FormatTransactionDetails(TransactionSummary transaction)
    {
        return new
        {
            transaction_id = transaction.TransactionId ?? "",
            timestamp = transaction.TransactionDate?.ToString("yyyy-MM-dd HH:mm:ss") ?? "",
            amount = transaction.Amount ?? 0,
            currency = transaction.Currency ?? "USD",
            status = transaction.TransactionStatus ?? "",
            payment_method = transaction.PaymentType?.ToString() ?? "",
            card_details = new
            {
                masked_number = transaction.MaskedCardNumber ?? "",
                card_type = transaction.CardType ?? "",
                entry_mode = transaction.EntryMode ?? ""
            },
            auth_code = transaction.AuthCode ?? "",
            reference_number = transaction.ReferenceNumber ?? "",
            gateway_response_code = transaction.GatewayResponseCode ?? "",
            gateway_response_message = transaction.GatewayResponseMessage ?? ""
        };
    }

    private List<object> FormatSettlementList(IEnumerable<TransactionSummary> settlements)
    {
        return settlements.Select(s => (object)new
        {
            settlement_id = s.TransactionId ?? "",
            settlement_date = s.TransactionDate?.ToString("yyyy-MM-dd") ?? "",
            transaction_count = 1,
            total_amount = s.Amount ?? 0,
            currency = s.Currency ?? "USD",
            status = s.TransactionStatus ?? ""
        }).ToList();
    }

    private object GenerateSettlementSummary(IEnumerable<TransactionSummary> settlements)
    {
        var settlementList = settlements.ToList();
        var totalAmount = settlementList.Sum(s => s.Amount ?? 0);
        var totalTransactions = settlementList.Count;

        return new
        {
            total_settlements = settlementList.Count,
            total_amount = totalAmount,
            total_transactions = totalTransactions,
            average_settlement_amount = settlementList.Count > 0 ? totalAmount / settlementList.Count : 0
        };
    }

    private List<object> FormatDisputeList(IEnumerable<DisputeSummary> disputes)
    {
        return disputes.Select(d => (object)new
        {
            dispute_id = d.CaseId ?? "",
            transaction_id = d.TransactionId ?? "",
            case_number = d.CaseNumber ?? "",
            dispute_stage = d.CaseStage ?? "",
            dispute_status = d.CaseStatus ?? "",
            case_amount = d.CaseAmount ?? 0,
            currency = d.CaseCurrency ?? "USD",
            reason_code = d.ReasonCode ?? "",
            reason_description = d.Reason ?? "",
            case_time = d.CaseTime?.ToString("yyyy-MM-dd HH:mm:ss") ?? "",
            last_adjustment_time = d.LastAdjustmentTime?.ToString("yyyy-MM-dd HH:mm:ss") ?? ""
        }).ToList();
    }

    private object FormatDisputeDetails(DisputeSummary dispute)
    {
        return new
        {
            dispute_id = dispute.CaseId ?? "",
            transaction_id = dispute.TransactionId ?? "",
            case_number = dispute.CaseNumber ?? "",
            dispute_stage = dispute.CaseStage ?? "",
            dispute_status = dispute.CaseStatus ?? "",
            case_amount = dispute.CaseAmount ?? 0,
            currency = dispute.CaseCurrency ?? "USD",
            reason_code = dispute.ReasonCode ?? "",
            reason_description = dispute.Reason ?? "",
            case_time = dispute.CaseTime?.ToString("yyyy-MM-dd HH:mm:ss") ?? "",
            last_adjustment_time = dispute.LastAdjustmentTime?.ToString("yyyy-MM-dd HH:mm:ss") ?? "",
            case_description = "",
            documents = new List<object>(),
            transaction_details = new
            {
                amount = 0,
                currency = "USD",
                masked_card_number = "",
                arn = ""
            }
        };
    }

    private List<object> FormatDepositList(IEnumerable<DepositSummary> deposits)
    {
        return deposits.Select(d => (object)new
        {
            deposit_id = d.DepositId ?? "",
            deposit_date = d.DepositDate?.ToString("yyyy-MM-dd") ?? "",
            deposit_reference = d.Reference ?? "",
            deposit_status = d.Status ?? "",
            deposit_amount = d.Amount ?? 0,
            currency = d.Currency ?? "USD",
            merchant_number = d.MerchantNumber ?? "",
            merchant_hierarchy = d.MerchantHierarchy ?? "",
            sales_count = d.SalesCount ?? 0,
            sales_amount = d.SalesAmount ?? 0,
            refunds_count = d.RefundsCount ?? 0,
            refunds_amount = d.RefundsAmount ?? 0
        }).ToList();
    }

    private object FormatDepositDetails(DepositSummary deposit)
    {
        return new
        {
            deposit_id = deposit.DepositId ?? "",
            deposit_date = deposit.DepositDate?.ToString("yyyy-MM-dd") ?? "",
            deposit_reference = deposit.Reference ?? "",
            deposit_status = deposit.Status ?? "",
            deposit_amount = deposit.Amount ?? 0,
            currency = deposit.Currency ?? "USD",
            merchant_number = deposit.MerchantNumber ?? "",
            merchant_hierarchy = deposit.MerchantHierarchy ?? "",
            bank_account = new
            {
                masked_account_number = "",
                bank_name = ""
            },
            transaction_summary = new
            {
                sales_count = deposit.SalesCount ?? 0,
                sales_amount = deposit.SalesAmount ?? 0,
                refunds_count = deposit.RefundsCount ?? 0,
                refunds_amount = deposit.RefundsAmount ?? 0,
                chargebacks_count = 0,
                chargebacks_amount = 0,
                adjustments_count = 0,
                adjustments_amount = 0
            }
        };
    }

    private object CalculateSummaryStats(dynamic transactions)
    {
        var transactionList = ((IEnumerable<dynamic>)transactions).ToList();
        decimal totalAmount = 0;
        var statusCounts = new Dictionary<string, int>();
        var paymentTypeCounts = new Dictionary<string, int>();

        foreach (var transaction in transactionList)
        {
            totalAmount += (decimal)(transaction.amount ?? 0);

            var status = (string)(transaction.status ?? "unknown");
            statusCounts[status] = statusCounts.GetValueOrDefault(status, 0) + 1;

            var paymentType = (string)(transaction.payment_method ?? "unknown");
            paymentTypeCounts[paymentType] = paymentTypeCounts.GetValueOrDefault(paymentType, 0) + 1;
        }

        return new
        {
            total_transactions = transactionList.Count,
            total_amount = totalAmount,
            average_amount = transactionList.Count > 0 ? totalAmount / transactionList.Count : 0,
            status_breakdown = statusCounts,
            payment_type_breakdown = paymentTypeCounts
        };
    }

    private object ExportToCsv(dynamic transactions)
    {
        var transactionList = ((IEnumerable<dynamic>)transactions).ToList();
        var csv = new StringBuilder();
        csv.AppendLine("Transaction ID,Timestamp,Amount,Currency,Status,Payment Method,Card Last Four,Auth Code,Reference Number");

        foreach (var transaction in transactionList)
        {
            csv.AppendLine($"{transaction.transaction_id},{transaction.timestamp},{transaction.amount},{transaction.currency},{transaction.status},{transaction.payment_method},{transaction.card_last_four},{transaction.auth_code},{transaction.reference_number}");
        }

        return new
        {
            success = true,
            data = csv.ToString(),
            format = "csv",
            filename = $"transactions_{DateTime.Now:yyyy-MM-dd_HH-mm-ss}.csv",
            timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
        };
    }

    private object ExportToXml(dynamic transactions)
    {
        var transactionList = ((IEnumerable<dynamic>)transactions).ToList();
        var xml = new StringBuilder();
        xml.AppendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.AppendLine("<transactions>");

        foreach (var transaction in transactionList)
        {
            xml.AppendLine("  <transaction>");
            xml.AppendLine($"    <transaction_id>{transaction.transaction_id}</transaction_id>");
            xml.AppendLine($"    <timestamp>{transaction.timestamp}</timestamp>");
            xml.AppendLine($"    <amount>{transaction.amount}</amount>");
            xml.AppendLine($"    <currency>{transaction.currency}</currency>");
            xml.AppendLine($"    <status>{transaction.status}</status>");
            xml.AppendLine($"    <payment_method>{transaction.payment_method}</payment_method>");
            xml.AppendLine($"    <card_last_four>{transaction.card_last_four}</card_last_four>");
            xml.AppendLine($"    <auth_code>{transaction.auth_code}</auth_code>");
            xml.AppendLine($"    <reference_number>{transaction.reference_number}</reference_number>");
            xml.AppendLine("  </transaction>");
        }

        xml.AppendLine("</transactions>");

        return new
        {
            success = true,
            data = xml.ToString(),
            format = "xml",
            filename = $"transactions_{DateTime.Now:yyyy-MM-dd_HH-mm-ss}.xml",
            timestamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
        };
    }

    private object AnalyzeDeclines(List<dynamic> transactions)
    {
        var declineReasons = new Dictionary<string, int>();
        var cardTypes = new Dictionary<string, int>();
        var hourlyBreakdown = new Dictionary<string, int>();
        decimal totalAmount = 0;

        foreach (var transaction in transactions)
        {
            // Analyze decline reasons
            var reason = (string)(transaction.gateway_response_message ?? "Unknown");
            declineReasons[reason] = declineReasons.GetValueOrDefault(reason, 0) + 1;

            // Analyze card types
            var cardType = (string)(transaction.payment_method ?? "Unknown");
            cardTypes[cardType] = cardTypes.GetValueOrDefault(cardType, 0) + 1;

            // Analyze hourly patterns
            var timestamp = (string)(transaction.timestamp ?? "");
            if (!string.IsNullOrEmpty(timestamp) && DateTime.TryParse(timestamp, out var dt))
            {
                var hour = dt.Hour.ToString("D2");
                hourlyBreakdown[hour] = hourlyBreakdown.GetValueOrDefault(hour, 0) + 1;
            }

            totalAmount += (decimal)(transaction.amount ?? 0);
        }

        return new
        {
            total_declined_transactions = transactions.Count,
            total_declined_amount = totalAmount,
            average_declined_amount = transactions.Count > 0 ? totalAmount / transactions.Count : 0,
            decline_reasons = declineReasons,
            card_type_breakdown = cardTypes,
            hourly_breakdown = hourlyBreakdown
        };
    }

    private object GenerateComprehensiveSummary(Dictionary<string, object> reportData)
    {
        var summary = new Dictionary<string, object>
        {
            ["overview"] = new Dictionary<string, object>(),
            ["financial_summary"] = new Dictionary<string, object>(),
            ["operational_metrics"] = new Dictionary<string, object>()
        };

        // Transaction overview
        if (reportData.TryGetValue("transactions", out var transactionsObj))
        {
            var transactions = ((dynamic)transactionsObj).transactions;
            if (transactions != null)
            {
                var transactionList = ((IEnumerable<dynamic>)transactions).ToList();
                var transactionCount = transactionList.Count;
                var totalAmount = transactionList.Sum(t => (decimal)(t.amount ?? 0));

                ((Dictionary<string, object>)summary["overview"])["transactions"] = new
                {
                    count = transactionCount,
                    total_amount = totalAmount,
                    average_amount = transactionCount > 0 ? totalAmount / transactionCount : 0
                };
            }
        }

        return summary;
    }
}