<?php

declare(strict_types=1);

/**
 * Global Payments Reporting Service
 *
 * This service class provides comprehensive reporting functionality for
 * Global Payments transactions including search, filtering, and data export.
 *
 * PHP version 7.4 or higher
 *
 * @category  Reporting
 * @package   GlobalPayments_Reporting
 * @author    Global Payments
 * @license   MIT License
 * @link      https://github.com/globalpayments
 */

require_once 'vendor/autoload.php';
require_once 'sdk-config.php';

use GlobalPayments\Api\Services\ReportingService;
use GlobalPayments\Api\Entities\Reporting\SearchCriteria;
use GlobalPayments\Api\Entities\Reporting\TransactionReportBuilder;
use GlobalPayments\Api\Entities\Enums\PaymentType;
use GlobalPayments\Api\Entities\Enums\TransactionStatus;
use GlobalPayments\Api\Entities\Exceptions\ApiException;

/**
 * Global Payments Reporting Service Class
 *
 * Provides methods for transaction reporting, searching, and data export
 * using the Global Payments SDK reporting capabilities.
 */
class GlobalPaymentsReportingService
{
    /**
     * @var bool Indicates if the SDK is configured
     */
    private bool $isConfigured = false;

    /**
     * Constructor - Initialize and configure the SDK
     *
     * @throws \InvalidArgumentException If configuration fails
     */
    public function __construct()
    {
        try {
            configureGpApiSdk();
            $this->isConfigured = true;
        } catch (\Exception $e) {
            throw new \InvalidArgumentException('Failed to configure SDK: ' . $e->getMessage());
        }
    }

    /**
     * Search transactions with filters and pagination
     *
     * @param array $filters Search filters and pagination parameters
     * @return array Transaction search results
     * @throws ApiException If the search request fails
     */
    public function searchTransactions(array $filters = []): array
    {
        $this->ensureConfigured();

        try {
            $reportBuilder = ReportingService::findTransactionsPaged(
                $filters['page'] ?? 1,
                $filters['page_size'] ?? 10
            );

            // Apply date range filters
            if (!empty($filters['start_date'])) {
                $reportBuilder->withStartDate(\DateTime::createFromFormat('Y-m-d', $filters['start_date']));
            }

            if (!empty($filters['end_date'])) {
                $reportBuilder->withEndDate(\DateTime::createFromFormat('Y-m-d', $filters['end_date']));
            }

            // Apply transaction ID filter
            if (!empty($filters['transaction_id'])) {
                $reportBuilder->withTransactionId($filters['transaction_id']);
            }

            // Apply payment type filter
            if (!empty($filters['payment_type'])) {
                $paymentType = $this->mapPaymentType($filters['payment_type']);
                if ($paymentType) {
                    $reportBuilder->withPaymentType($paymentType);
                }
            }

            // Apply status filter - let the SDK handle string status values
            if (!empty($filters['status'])) {
                // For now, we'll filter results after retrieval since SDK enum mapping is complex
                // $reportBuilder->withTransactionStatus($filters['status']);
            }

            // Apply amount range filters
            if (!empty($filters['amount_min'])) {
                $reportBuilder->withAmount($filters['amount_min']);
            }

            if (!empty($filters['amount_max'])) {
                $reportBuilder->withAmount($filters['amount_max']);
            }

            // Apply card number filter (last 4 digits)
            if (!empty($filters['card_last_four'])) {
                $reportBuilder->withCardNumberLastFour($filters['card_last_four']);
            }

            // Execute the search
            $response = $reportBuilder->execute();

            $transactions = $this->formatTransactionList($response->result ?? []);

            // Apply client-side filtering for status if needed
            if (!empty($filters['status'])) {
                $statusFilter = strtoupper($filters['status']);
                $transactions = array_filter($transactions, function($transaction) use ($statusFilter) {
                    return strtoupper($transaction['status']) === $statusFilter;
                });
                $transactions = array_values($transactions); // Re-index array
            }

            return [
                'success' => true,
                'data' => [
                    'transactions' => $transactions,
                    'pagination' => [
                        'page' => $filters['page'] ?? 1,
                        'page_size' => $filters['page_size'] ?? 10,
                        'total_count' => count($transactions),
                        'original_total_count' => $response->totalRecordCount ?? 0
                    ]
                ],
                'timestamp' => date('Y-m-d H:i:s')
            ];

        } catch (ApiException $e) {
            throw new ApiException('Transaction search failed: ' . $e->getMessage());
        }
    }

    /**
     * Get detailed information for a specific transaction
     *
     * @param string $transactionId The transaction ID to retrieve
     * @return array Transaction details
     * @throws ApiException If the transaction cannot be found
     */
    public function getTransactionDetails(string $transactionId): array
    {
        $this->ensureConfigured();

        try {
            $reportBuilder = ReportingService::transactionDetail($transactionId);
            $response = $reportBuilder->execute();

            if (!$response) {
                throw new ApiException('Transaction not found');
            }

            return [
                'success' => true,
                'data' => $this->formatTransactionDetails($response),
                'timestamp' => date('Y-m-d H:i:s')
            ];

        } catch (ApiException $e) {
            throw new ApiException('Failed to retrieve transaction details: ' . $e->getMessage());
        }
    }

    /**
     * Generate settlement report for a date range
     *
     * @param array $params Report parameters
     * @return array Settlement report data
     * @throws ApiException If the report generation fails
     */
    public function getSettlementReport(array $params = []): array
    {
        $this->ensureConfigured();

        try {
            $reportBuilder = ReportingService::findSettlementTransactionsPaged(
                $params['page'] ?? 1,
                $params['page_size'] ?? 50
            );

            // Apply date range
            if (!empty($params['start_date'])) {
                $reportBuilder->withStartDate(\DateTime::createFromFormat('Y-m-d', $params['start_date']));
            }

            if (!empty($params['end_date'])) {
                $reportBuilder->withEndDate(\DateTime::createFromFormat('Y-m-d', $params['end_date']));
            }

            $response = $reportBuilder->execute();

            return [
                'success' => true,
                'data' => [
                    'settlements' => $this->formatSettlementList($response->result ?? []),
                    'summary' => $this->generateSettlementSummary($response->result ?? []),
                    'pagination' => [
                        'page' => $params['page'] ?? 1,
                        'page_size' => $params['page_size'] ?? 50,
                        'total_count' => $response->totalRecordCount ?? 0
                    ]
                ],
                'timestamp' => date('Y-m-d H:i:s')
            ];

        } catch (ApiException $e) {
            throw new ApiException('Settlement report generation failed: ' . $e->getMessage());
        }
    }

    /**
     * Get dispute reports with filtering and pagination
     *
     * @param array $filters Dispute search filters and pagination
     * @return array Dispute report results
     * @throws ApiException If the dispute search fails
     */
    public function getDisputeReport(array $filters = []): array
    {
        $this->ensureConfigured();

        try {
            $reportBuilder = ReportingService::findDisputesPaged(
                $filters['page'] ?? 1,
                $filters['page_size'] ?? 10
            );

            // Apply date range filters
            if (!empty($filters['start_date'])) {
                $reportBuilder->withStartDate(\DateTime::createFromFormat('Y-m-d', $filters['start_date']));
            }

            if (!empty($filters['end_date'])) {
                $reportBuilder->withEndDate(\DateTime::createFromFormat('Y-m-d', $filters['end_date']));
            }

            // Apply dispute stage filter
            if (!empty($filters['stage'])) {
                $reportBuilder->withDisputeStage($filters['stage']);
            }

            // Apply dispute status filter
            if (!empty($filters['status'])) {
                $reportBuilder->withDisputeStatus($filters['status']);
            }

            // Execute the search
            $response = $reportBuilder->execute();

            return [
                'success' => true,
                'data' => [
                    'disputes' => $this->formatDisputeList($response->result ?? []),
                    'pagination' => [
                        'page' => $filters['page'] ?? 1,
                        'page_size' => $filters['page_size'] ?? 10,
                        'total_count' => $response->totalRecordCount ?? 0
                    ]
                ],
                'timestamp' => date('Y-m-d H:i:s')
            ];

        } catch (ApiException $e) {
            throw new ApiException('Dispute report generation failed: ' . $e->getMessage());
        }
    }

    /**
     * Get detailed information for a specific dispute
     *
     * @param string $disputeId The dispute ID to retrieve
     * @return array Dispute details
     * @throws ApiException If the dispute cannot be found
     */
    public function getDisputeDetails(string $disputeId): array
    {
        $this->ensureConfigured();

        try {
            $reportBuilder = ReportingService::disputeDetail($disputeId);
            $response = $reportBuilder->execute();

            if (!$response) {
                throw new ApiException('Dispute not found');
            }

            return [
                'success' => true,
                'data' => $this->formatDisputeDetails($response),
                'timestamp' => date('Y-m-d H:i:s')
            ];

        } catch (ApiException $e) {
            throw new ApiException('Failed to retrieve dispute details: ' . $e->getMessage());
        }
    }

    /**
     * Get deposit reports with filtering and pagination
     *
     * @param array $filters Deposit search filters and pagination
     * @return array Deposit report results
     * @throws ApiException If the deposit search fails
     */
    public function getDepositReport(array $filters = []): array
    {
        $this->ensureConfigured();

        try {
            $reportBuilder = ReportingService::findDepositsPaged(
                $filters['page'] ?? 1,
                $filters['page_size'] ?? 10
            );

            // Apply date range filters
            if (!empty($filters['start_date'])) {
                $reportBuilder->withStartDate(\DateTime::createFromFormat('Y-m-d', $filters['start_date']));
            }

            if (!empty($filters['end_date'])) {
                $reportBuilder->withEndDate(\DateTime::createFromFormat('Y-m-d', $filters['end_date']));
            }

            // Apply deposit ID filter
            if (!empty($filters['deposit_id'])) {
                $reportBuilder->withDepositReference($filters['deposit_id']);
            }

            // Apply status filter
            if (!empty($filters['status'])) {
                $reportBuilder->withDepositStatus($filters['status']);
            }

            // Execute the search
            $response = $reportBuilder->execute();

            return [
                'success' => true,
                'data' => [
                    'deposits' => $this->formatDepositList($response->result ?? []),
                    'pagination' => [
                        'page' => $filters['page'] ?? 1,
                        'page_size' => $filters['page_size'] ?? 10,
                        'total_count' => $response->totalRecordCount ?? 0
                    ]
                ],
                'timestamp' => date('Y-m-d H:i:s')
            ];

        } catch (ApiException $e) {
            throw new ApiException('Deposit report generation failed: ' . $e->getMessage());
        }
    }

    /**
     * Get detailed information for a specific deposit
     *
     * @param string $depositId The deposit ID to retrieve
     * @return array Deposit details
     * @throws ApiException If the deposit cannot be found
     */
    public function getDepositDetails(string $depositId): array
    {
        $this->ensureConfigured();

        try {
            $reportBuilder = ReportingService::depositDetail($depositId);
            $response = $reportBuilder->execute();

            if (!$response) {
                throw new ApiException('Deposit not found');
            }

            return [
                'success' => true,
                'data' => $this->formatDepositDetails($response),
                'timestamp' => date('Y-m-d H:i:s')
            ];

        } catch (ApiException $e) {
            throw new ApiException('Failed to retrieve deposit details: ' . $e->getMessage());
        }
    }

    /**
     * Get batch report with detailed transaction information
     *
     * @param array $filters Batch search filters
     * @return array Batch report results
     * @throws ApiException If the batch search fails
     */
    public function getBatchReport(array $filters = []): array
    {
        $this->ensureConfigured();

        try {
            $reportBuilder = ReportingService::batchDetail();

            // Apply date range filters
            if (!empty($filters['start_date'])) {
                $reportBuilder->withStartDate(\DateTime::createFromFormat('Y-m-d', $filters['start_date']));
            }

            if (!empty($filters['end_date'])) {
                $reportBuilder->withEndDate(\DateTime::createFromFormat('Y-m-d', $filters['end_date']));
            }

            // Execute the search
            $response = $reportBuilder->execute();

            return [
                'success' => true,
                'data' => [
                    'batches' => $this->formatBatchList($response->result ?? []),
                    'summary' => $this->generateBatchSummary($response->result ?? [])
                ],
                'timestamp' => date('Y-m-d H:i:s')
            ];

        } catch (ApiException $e) {
            throw new ApiException('Batch report generation failed: ' . $e->getMessage());
        }
    }

    /**
     * Get declined transactions report
     *
     * @param array $filters Decline search filters and pagination
     * @return array Declined transactions report
     * @throws ApiException If the search fails
     */
    public function getDeclinedTransactionsReport(array $filters = []): array
    {
        $this->ensureConfigured();

        try {
            // Use transaction search with declined status filter
            $declineFilters = array_merge($filters, ['status' => 'DECLINED']);
            $result = $this->searchTransactions($declineFilters);

            // Add decline analysis
            if ($result['success']) {
                $result['data']['decline_analysis'] = $this->analyzeDeclines($result['data']['transactions']);
            }

            return $result;

        } catch (ApiException $e) {
            throw new ApiException('Declined transactions report generation failed: ' . $e->getMessage());
        }
    }

    /**
     * Get comprehensive date range report across all transaction types
     *
     * @param array $params Date range and report parameters
     * @return array Comprehensive date range report
     */
    public function getDateRangeReport(array $params = []): array
    {
        $this->ensureConfigured();

        try {
            $startDate = $params['start_date'] ?? date('Y-m-d', strtotime('-30 days'));
            $endDate = $params['end_date'] ?? date('Y-m-d');

            $report = [
                'success' => true,
                'data' => [
                    'period' => [
                        'start_date' => $startDate,
                        'end_date' => $endDate
                    ],
                    'transactions' => [],
                    'settlements' => [],
                    'disputes' => [],
                    'deposits' => [],
                    'summary' => []
                ],
                'timestamp' => date('Y-m-d H:i:s')
            ];

            // Get transactions for the period
            $transactionResult = $this->searchTransactions([
                'start_date' => $startDate,
                'end_date' => $endDate,
                'page_size' => $params['transaction_limit'] ?? 100
            ]);

            if ($transactionResult['success']) {
                $report['data']['transactions'] = $transactionResult['data'];
            }

            // Get settlements for the period
            $settlementResult = $this->getSettlementReport([
                'start_date' => $startDate,
                'end_date' => $endDate,
                'page_size' => $params['settlement_limit'] ?? 50
            ]);

            if ($settlementResult['success']) {
                $report['data']['settlements'] = $settlementResult['data'];
            }

            // Get disputes for the period
            try {
                $disputeResult = $this->getDisputeReport([
                    'start_date' => $startDate,
                    'end_date' => $endDate,
                    'page_size' => $params['dispute_limit'] ?? 25
                ]);

                if ($disputeResult['success']) {
                    $report['data']['disputes'] = $disputeResult['data'];
                }
            } catch (ApiException $e) {
                $report['data']['disputes'] = ['error' => 'Disputes not available: ' . $e->getMessage()];
            }

            // Get deposits for the period
            try {
                $depositResult = $this->getDepositReport([
                    'start_date' => $startDate,
                    'end_date' => $endDate,
                    'page_size' => $params['deposit_limit'] ?? 25
                ]);

                if ($depositResult['success']) {
                    $report['data']['deposits'] = $depositResult['data'];
                }
            } catch (ApiException $e) {
                $report['data']['deposits'] = ['error' => 'Deposits not available: ' . $e->getMessage()];
            }

            // Generate comprehensive summary
            $report['data']['summary'] = $this->generateComprehensiveSummary($report['data']);

            return $report;

        } catch (\Exception $e) {
            return [
                'success' => false,
                'error' => 'Failed to generate date range report: ' . $e->getMessage(),
                'timestamp' => date('Y-m-d H:i:s')
            ];
        }
    }

    /**
     * Export transaction data in specified format
     *
     * @param array $filters Search filters
     * @param string $format Export format ('json' or 'csv')
     * @return array Export data
     * @throws ApiException If export fails
     */
    public function exportTransactions(array $filters = [], string $format = 'json'): array
    {
        $this->ensureConfigured();

        try {
            // Get all transactions (remove pagination for export)
            $exportFilters = $filters;
            $exportFilters['page_size'] = 1000; // Increased limit for export

            $transactions = $this->searchTransactions($exportFilters);

            if ($format === 'csv') {
                return $this->exportToCsv($transactions['data']['transactions']);
            }

            return [
                'success' => true,
                'data' => $transactions['data']['transactions'],
                'format' => 'json',
                'timestamp' => date('Y-m-d H:i:s')
            ];

        } catch (ApiException $e) {
            throw new ApiException('Export failed: ' . $e->getMessage());
        }
    }

    /**
     * Get reporting summary statistics
     *
     * @param array $params Summary parameters
     * @return array Summary statistics
     */
    public function getSummaryStats(array $params = []): array
    {
        $this->ensureConfigured();

        try {
            $startDate = $params['start_date'] ?? date('Y-m-d', strtotime('-30 days'));
            $endDate = $params['end_date'] ?? date('Y-m-d');

            // Get transaction summary
            $transactions = $this->searchTransactions([
                'start_date' => $startDate,
                'end_date' => $endDate,
                'page_size' => 1000
            ]);

            return [
                'success' => true,
                'data' => $this->calculateSummaryStats($transactions['data']['transactions']),
                'period' => [
                    'start_date' => $startDate,
                    'end_date' => $endDate
                ],
                'timestamp' => date('Y-m-d H:i:s')
            ];

        } catch (\Exception $e) {
            return [
                'success' => false,
                'error' => 'Failed to generate summary statistics: ' . $e->getMessage(),
                'timestamp' => date('Y-m-d H:i:s')
            ];
        }
    }

    /**
     * Ensure SDK is properly configured
     *
     * @throws \RuntimeException If SDK is not configured
     */
    private function ensureConfigured(): void
    {
        if (!$this->isConfigured) {
            throw new \RuntimeException('SDK is not properly configured');
        }
    }

    /**
     * Map payment type string to PaymentType enum
     *
     * @param string $type Payment type string
     * @return PaymentType|null Mapped payment type or null
     */
    private function mapPaymentType(string $type): ?PaymentType
    {
        $mapping = [
            'sale' => PaymentType::SALE,
            'refund' => PaymentType::REFUND,
            'authorize' => PaymentType::AUTH,
            'capture' => PaymentType::CAPTURE
        ];

        return $mapping[strtolower($type)] ?? null;
    }

    /**
     * Map transaction status string to TransactionStatus enum
     *
     * @param string $status Status string
     * @return TransactionStatus|null Mapped status or null
     */
    private function mapTransactionStatus(string $status): ?TransactionStatus
    {
        // Don't map for now - the SDK may not have all these constants defined
        // Let the SDK handle the status filtering internally
        return null;
    }

    /**
     * Format transaction list for API response
     *
     * @param array $transactions Raw transaction data
     * @return array Formatted transaction list
     */
    private function formatTransactionList(array $transactions): array
    {
        return array_map(function($transaction) {
            return [
                'transaction_id' => $transaction->transactionId ?? '',
                'timestamp' => $transaction->transactionDate ?? '',
                'amount' => $transaction->amount ?? 0,
                'currency' => $transaction->currency ?? 'USD',
                'status' => $transaction->transactionStatus ?? '',
                'payment_method' => $transaction->paymentType ?? '',
                'card_last_four' => $transaction->maskedCardNumber ?? '',
                'auth_code' => $transaction->authCode ?? '',
                'reference_number' => $transaction->referenceNumber ?? ''
            ];
        }, $transactions);
    }

    /**
     * Format detailed transaction information
     *
     * @param mixed $transaction Raw transaction detail data
     * @return array Formatted transaction details
     */
    private function formatTransactionDetails($transaction): array
    {
        return [
            'transaction_id' => $transaction->transactionId ?? '',
            'timestamp' => $transaction->transactionDate ?? '',
            'amount' => $transaction->amount ?? 0,
            'currency' => $transaction->currency ?? 'USD',
            'status' => $transaction->transactionStatus ?? '',
            'payment_method' => $transaction->paymentType ?? '',
            'card_details' => [
                'masked_number' => $transaction->maskedCardNumber ?? '',
                'card_type' => $transaction->cardType ?? '',
                'entry_mode' => $transaction->entryMode ?? ''
            ],
            'auth_code' => $transaction->authCode ?? '',
            'reference_number' => $transaction->referenceNumber ?? '',
            'gateway_response_code' => $transaction->gatewayResponseCode ?? '',
            'gateway_response_message' => $transaction->gatewayResponseMessage ?? ''
        ];
    }

    /**
     * Format settlement list for API response
     *
     * @param array $settlements Raw settlement data
     * @return array Formatted settlement list
     */
    private function formatSettlementList(array $settlements): array
    {
        return array_map(function($settlement) {
            return [
                'settlement_id' => $settlement->settlementId ?? '',
                'settlement_date' => $settlement->settlementDate ?? '',
                'transaction_count' => $settlement->transactionCount ?? 0,
                'total_amount' => $settlement->totalAmount ?? 0,
                'currency' => $settlement->currency ?? 'USD',
                'status' => $settlement->status ?? ''
            ];
        }, $settlements);
    }

    /**
     * Generate settlement summary statistics
     *
     * @param array $settlements Raw settlement data
     * @return array Settlement summary
     */
    private function generateSettlementSummary(array $settlements): array
    {
        $totalAmount = 0;
        $totalTransactions = 0;

        foreach ($settlements as $settlement) {
            $totalAmount += $settlement->totalAmount ?? 0;
            $totalTransactions += $settlement->transactionCount ?? 0;
        }

        return [
            'total_settlements' => count($settlements),
            'total_amount' => $totalAmount,
            'total_transactions' => $totalTransactions,
            'average_settlement_amount' => count($settlements) > 0 ? $totalAmount / count($settlements) : 0
        ];
    }

    /**
     * Calculate summary statistics from transaction data
     *
     * @param array $transactions Transaction data
     * @return array Summary statistics
     */
    private function calculateSummaryStats(array $transactions): array
    {
        $totalAmount = 0;
        $statusCounts = [];
        $paymentTypeCounts = [];

        foreach ($transactions as $transaction) {
            $totalAmount += $transaction['amount'] ?? 0;

            $status = $transaction['status'] ?? 'unknown';
            $statusCounts[$status] = ($statusCounts[$status] ?? 0) + 1;

            $paymentType = $transaction['payment_method'] ?? 'unknown';
            $paymentTypeCounts[$paymentType] = ($paymentTypeCounts[$paymentType] ?? 0) + 1;
        }

        return [
            'total_transactions' => count($transactions),
            'total_amount' => $totalAmount,
            'average_amount' => count($transactions) > 0 ? $totalAmount / count($transactions) : 0,
            'status_breakdown' => $statusCounts,
            'payment_type_breakdown' => $paymentTypeCounts
        ];
    }

    /**
     * Export transaction data to CSV format
     *
     * @param array $transactions Transaction data
     * @return array CSV export data
     */
    private function exportToCsv(array $transactions): array
    {
        $csvData = "Transaction ID,Timestamp,Amount,Currency,Status,Payment Method,Card Last Four,Auth Code,Reference Number\n";

        foreach ($transactions as $transaction) {
            // Format timestamp for CSV export
            $timestamp = $transaction['timestamp'] ?? '';
            if (is_object($timestamp) && method_exists($timestamp, 'format')) {
                $timestamp = $timestamp->format('Y-m-d H:i:s');
            } elseif (is_array($timestamp) && isset($timestamp['date'])) {
                $timestamp = $timestamp['date'];
            }

            $csvData .= sprintf(
                "%s,%s,%s,%s,%s,%s,%s,%s,%s\n",
                $transaction['transaction_id'] ?? '',
                $timestamp,
                $transaction['amount'] ?? '',
                $transaction['currency'] ?? '',
                $transaction['status'] ?? '',
                $transaction['payment_method'] ?? '',
                $transaction['card_last_four'] ?? '',
                $transaction['auth_code'] ?? '',
                $transaction['reference_number'] ?? ''
            );
        }

        return [
            'success' => true,
            'data' => $csvData,
            'format' => 'csv',
            'filename' => 'transactions_' . date('Y-m-d_H-i-s') . '.csv',
            'timestamp' => date('Y-m-d H:i:s')
        ];
    }

    /**
     * Format dispute list for API response
     *
     * @param array $disputes Raw dispute data
     * @return array Formatted dispute list
     */
    private function formatDisputeList(array $disputes): array
    {
        return array_map(function($dispute) {
            return [
                'dispute_id' => $dispute->caseId ?? '',
                'transaction_id' => $dispute->transactionId ?? '',
                'case_number' => $dispute->caseNumber ?? '',
                'dispute_stage' => $dispute->caseStage ?? '',
                'dispute_status' => $dispute->caseStatus ?? '',
                'case_amount' => $dispute->caseAmount ?? 0,
                'currency' => $dispute->caseCurrency ?? 'USD',
                'reason_code' => $dispute->reasonCode ?? '',
                'reason_description' => $dispute->reason ?? '',
                'case_time' => $dispute->caseTime ?? '',
                'last_adjustment_time' => $dispute->lastAdjustmentTime ?? ''
            ];
        }, $disputes);
    }

    /**
     * Format detailed dispute information
     *
     * @param mixed $dispute Raw dispute detail data
     * @return array Formatted dispute details
     */
    private function formatDisputeDetails($dispute): array
    {
        return [
            'dispute_id' => $dispute->caseId ?? '',
            'transaction_id' => $dispute->transactionId ?? '',
            'case_number' => $dispute->caseNumber ?? '',
            'dispute_stage' => $dispute->caseStage ?? '',
            'dispute_status' => $dispute->caseStatus ?? '',
            'case_amount' => $dispute->caseAmount ?? 0,
            'currency' => $dispute->caseCurrency ?? 'USD',
            'reason_code' => $dispute->reasonCode ?? '',
            'reason_description' => $dispute->reason ?? '',
            'case_time' => $dispute->caseTime ?? '',
            'last_adjustment_time' => $dispute->lastAdjustmentTime ?? '',
            'case_description' => $dispute->caseDescription ?? '',
            'documents' => $dispute->documents ?? [],
            'transaction_details' => [
                'amount' => $dispute->transactionAmount ?? 0,
                'currency' => $dispute->transactionCurrency ?? 'USD',
                'masked_card_number' => $dispute->transactionMaskedCardNumber ?? '',
                'arn' => $dispute->transactionARN ?? ''
            ]
        ];
    }

    /**
     * Format deposit list for API response
     *
     * @param array $deposits Raw deposit data
     * @return array Formatted deposit list
     */
    private function formatDepositList(array $deposits): array
    {
        return array_map(function($deposit) {
            return [
                'deposit_id' => $deposit->depositId ?? '',
                'deposit_date' => $deposit->depositDate ?? '',
                'deposit_reference' => $deposit->depositReference ?? '',
                'deposit_status' => $deposit->status ?? '',
                'deposit_amount' => $deposit->amount ?? 0,
                'currency' => $deposit->currency ?? 'USD',
                'merchant_number' => $deposit->merchantNumber ?? '',
                'merchant_hierarchy' => $deposit->merchantHierarchy ?? '',
                'sales_count' => $deposit->salesCount ?? 0,
                'sales_amount' => $deposit->salesAmount ?? 0,
                'refunds_count' => $deposit->refundsCount ?? 0,
                'refunds_amount' => $deposit->refundsAmount ?? 0
            ];
        }, $deposits);
    }

    /**
     * Format detailed deposit information
     *
     * @param mixed $deposit Raw deposit detail data
     * @return array Formatted deposit details
     */
    private function formatDepositDetails($deposit): array
    {
        return [
            'deposit_id' => $deposit->depositId ?? '',
            'deposit_date' => $deposit->depositDate ?? '',
            'deposit_reference' => $deposit->depositReference ?? '',
            'deposit_status' => $deposit->status ?? '',
            'deposit_amount' => $deposit->amount ?? 0,
            'currency' => $deposit->currency ?? 'USD',
            'merchant_number' => $deposit->merchantNumber ?? '',
            'merchant_hierarchy' => $deposit->merchantHierarchy ?? '',
            'bank_account' => [
                'masked_account_number' => $deposit->maskedAccountNumber ?? '',
                'bank_name' => $deposit->bankName ?? ''
            ],
            'transaction_summary' => [
                'sales_count' => $deposit->salesCount ?? 0,
                'sales_amount' => $deposit->salesAmount ?? 0,
                'refunds_count' => $deposit->refundsCount ?? 0,
                'refunds_amount' => $deposit->refundsAmount ?? 0,
                'chargebacks_count' => $deposit->chargebacksCount ?? 0,
                'chargebacks_amount' => $deposit->chargebacksAmount ?? 0,
                'adjustments_count' => $deposit->adjustmentsCount ?? 0,
                'adjustments_amount' => $deposit->adjustmentsAmount ?? 0
            ]
        ];
    }

    /**
     * Format batch list for API response
     *
     * @param array $batches Raw batch data
     * @return array Formatted batch list
     */
    private function formatBatchList(array $batches): array
    {
        return array_map(function($batch) {
            return [
                'batch_id' => $batch->batchId ?? '',
                'sequence_number' => $batch->sequenceNumber ?? '',
                'transaction_count' => $batch->transactionCount ?? 0,
                'total_amount' => $batch->totalAmount ?? 0,
                'currency' => $batch->currency ?? 'USD',
                'batch_status' => $batch->batchStatus ?? '',
                'close_time' => $batch->closeTime ?? '',
                'open_time' => $batch->openTime ?? ''
            ];
        }, $batches);
    }

    /**
     * Generate batch summary statistics
     *
     * @param array $batches Raw batch data
     * @return array Batch summary
     */
    private function generateBatchSummary(array $batches): array
    {
        $totalAmount = 0;
        $totalTransactions = 0;
        $batchStatuses = [];

        foreach ($batches as $batch) {
            $totalAmount += $batch->totalAmount ?? 0;
            $totalTransactions += $batch->transactionCount ?? 0;

            $status = $batch->batchStatus ?? 'unknown';
            $batchStatuses[$status] = ($batchStatuses[$status] ?? 0) + 1;
        }

        return [
            'total_batches' => count($batches),
            'total_amount' => $totalAmount,
            'total_transactions' => $totalTransactions,
            'average_batch_amount' => count($batches) > 0 ? $totalAmount / count($batches) : 0,
            'status_breakdown' => $batchStatuses
        ];
    }

    /**
     * Analyze decline patterns from transaction data
     *
     * @param array $transactions Declined transaction data
     * @return array Decline analysis
     */
    private function analyzeDeclines(array $transactions): array
    {
        $declineReasons = [];
        $cardTypes = [];
        $hourlyBreakdown = [];
        $totalAmount = 0;

        foreach ($transactions as $transaction) {
            // Analyze decline reasons (if available in gateway response)
            $reason = 'Unknown';
            if (!empty($transaction['gateway_response_message'])) {
                $reason = $transaction['gateway_response_message'];
            }
            $declineReasons[$reason] = ($declineReasons[$reason] ?? 0) + 1;

            // Analyze card types
            $cardType = $transaction['payment_method'] ?? 'Unknown';
            $cardTypes[$cardType] = ($cardTypes[$cardType] ?? 0) + 1;

            // Analyze hourly patterns
            if (isset($transaction['timestamp'])) {
                $timestamp = $transaction['timestamp'];
                if (is_array($timestamp) && isset($timestamp['date'])) {
                    $hour = date('H', strtotime($timestamp['date']));
                } else {
                    $hour = date('H', strtotime($timestamp));
                }
                $hourlyBreakdown[$hour] = ($hourlyBreakdown[$hour] ?? 0) + 1;
            }

            $totalAmount += $transaction['amount'] ?? 0;
        }

        return [
            'total_declined_transactions' => count($transactions),
            'total_declined_amount' => $totalAmount,
            'average_declined_amount' => count($transactions) > 0 ? $totalAmount / count($transactions) : 0,
            'decline_reasons' => $declineReasons,
            'card_type_breakdown' => $cardTypes,
            'hourly_breakdown' => $hourlyBreakdown
        ];
    }

    /**
     * Generate comprehensive summary for date range report
     *
     * @param array $reportData All report data
     * @return array Comprehensive summary
     */
    private function generateComprehensiveSummary(array $reportData): array
    {
        $summary = [
            'overview' => [],
            'financial_summary' => [],
            'operational_metrics' => []
        ];

        // Transaction overview
        if (!empty($reportData['transactions']['transactions'])) {
            $transactions = $reportData['transactions']['transactions'];
            $transactionCount = count($transactions);
            $totalAmount = array_sum(array_column($transactions, 'amount'));

            $summary['overview']['transactions'] = [
                'count' => $transactionCount,
                'total_amount' => $totalAmount,
                'average_amount' => $transactionCount > 0 ? $totalAmount / $transactionCount : 0
            ];
        }

        // Settlement summary
        if (!empty($reportData['settlements']['settlements'])) {
            $settlements = $reportData['settlements']['settlements'];
            $settlementCount = count($settlements);
            $settledAmount = array_sum(array_column($settlements, 'total_amount'));

            $summary['financial_summary']['settlements'] = [
                'count' => $settlementCount,
                'total_amount' => $settledAmount
            ];
        }

        // Dispute summary
        if (!empty($reportData['disputes']['disputes'])) {
            $disputes = $reportData['disputes']['disputes'];
            $disputeCount = count($disputes);
            $disputeAmount = array_sum(array_column($disputes, 'case_amount'));

            $summary['operational_metrics']['disputes'] = [
                'count' => $disputeCount,
                'total_amount' => $disputeAmount,
                'dispute_rate' => isset($summary['overview']['transactions']['count']) && $summary['overview']['transactions']['count'] > 0
                    ? ($disputeCount / $summary['overview']['transactions']['count']) * 100 : 0
            ];
        }

        // Deposit summary
        if (!empty($reportData['deposits']['deposits'])) {
            $deposits = $reportData['deposits']['deposits'];
            $depositCount = count($deposits);
            $depositAmount = array_sum(array_column($deposits, 'deposit_amount'));

            $summary['financial_summary']['deposits'] = [
                'count' => $depositCount,
                'total_amount' => $depositAmount
            ];
        }

        return $summary;
    }
}