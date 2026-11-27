<?php

declare(strict_types=1);

/**
 * Global Payments Reporting Service
 *
 * This service class provides comprehensive reporting functionality for
 * Global Payments transactions including search, filtering, and data export,
 * read from GP API with the Global Payments PHP SDK.
 *
 * PHP version 8.2 or higher
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
use GlobalPayments\Api\ServicesContainer;
use GlobalPayments\Api\Mapping\GpApiMapping;
use GlobalPayments\Api\Entities\Reporting\SearchCriteria;
use GlobalPayments\Api\Entities\Reporting\DataServiceCriteria;
use GlobalPayments\Api\Entities\Enums\PaymentType;
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
     * Constructor - Initialize and configure the SDK
     *
     * @throws \InvalidArgumentException If the GP API credentials are missing
     */
    public function __construct()
    {
        try {
            configureGpApiSdk();
        } catch (\Exception $e) {
            throw new \InvalidArgumentException('Failed to configure SDK: ' . $e->getMessage());
        }
    }

    /**
     * Search transactions with filters and pagination
     *
     * The SDK's GP API transaction mapping leaves out the card brand and the
     * auth code, so the list is read through the SDK's GP API connector, mapped
     * with the SDK's GpApiMapping, and those two are taken from the response.
     *
     * @param array $filters Search filters and pagination parameters
     * @return array Transaction search results
     * @throws ApiException If the search request fails
     */
    public function searchTransactions(array $filters = []): array
    {
        $page = (int) ($filters['page'] ?? 1);
        $pageSize = (int) ($filters['page_size'] ?? 10);

        $query = [
            'page' => $page,
            'page_size' => $pageSize,
            'from_time_created' => $filters['start_date'] ?? null,
            'to_time_created' => $filters['end_date'] ?? null,
            'id' => $filters['transaction_id'] ?? null,
            'number_last4' => $filters['card_last_four'] ?? null,
        ];

        if (!empty($filters['payment_type'])) {
            $paymentType = $this->mapPaymentType($filters['payment_type']);
            if ($paymentType === null) {
                throw new \InvalidArgumentException('Invalid payment_type. Supported values: sale, refund');
            }
            $query['type'] = $paymentType;
        }

        try {
            $response = $this->gpApiGet('/transactions', $query);
        } catch (ApiException $e) {
            throw new ApiException('Transaction search failed: ' . $e->getMessage());
        }

        $transactions = array_map(
            fn($row) => $this->formatTransaction(GpApiMapping::mapTransactionSummary($row), $row),
            $response->transactions ?? []
        );

        // GP API only filters on an exact amount, so the range is applied here
        if (isset($filters['amount_min']) && is_numeric($filters['amount_min'])) {
            $min = (float) $filters['amount_min'];
            $transactions = array_values(array_filter($transactions, fn($t) => $t['amount'] >= $min));
        }
        if (isset($filters['amount_max']) && is_numeric($filters['amount_max'])) {
            $max = (float) $filters['amount_max'];
            $transactions = array_values(array_filter($transactions, fn($t) => $t['amount'] <= $max));
        }

        // Status is not a search filter in this sample, it is applied to the page
        if (!empty($filters['status'])) {
            $statusFilter = strtoupper($filters['status']);
            $transactions = array_values(array_filter(
                $transactions,
                fn($t) => strtoupper($t['status']) === $statusFilter
            ));
        }

        return [
            'success' => true,
            'data' => [
                'transactions' => $transactions,
                'pagination' => [
                    'page' => $page,
                    'page_size' => $pageSize,
                    'total_count' => count($transactions),
                    'original_total_count' => $response->total_record_count ?? $response->total_count ?? 0
                ]
            ],
            'timestamp' => date('Y-m-d H:i:s')
        ];
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
        try {
            $response = $this->gpApiGet('/transactions/' . rawurlencode($transactionId));
        } catch (ApiException $e) {
            throw new ApiException('Failed to retrieve transaction details: ' . $e->getMessage());
        }

        $transaction = GpApiMapping::mapTransactionSummary($response);
        $info = $this->formatTransaction($transaction, $response);

        return [
            'success' => true,
            'data' => [
                'transaction_id' => $info['transaction_id'],
                'timestamp' => $info['timestamp'],
                'amount' => $info['amount'],
                'currency' => $info['currency'],
                'status' => $info['status'],
                'payment_method' => $info['payment_method'],
                'card_details' => [
                    'masked_number' => $info['card_last_four'],
                    'card_type' => (string) ($response->payment_method->card->brand ?? ''),
                    'entry_mode' => (string) ($transaction->entryMode ?? '')
                ],
                'auth_code' => $info['auth_code'],
                'reference_number' => $info['reference_number'],
                'gateway_response_code' => (string) ($transaction->gatewayResponseCode ?? ''),
                'gateway_response_message' => (string) ($transaction->gatewayResponseMessage ?? '')
            ],
            'timestamp' => date('Y-m-d H:i:s')
        ];
    }

    /**
     * Generate settlement report for a date range
     *
     * @param array $params Report parameters
     * @return array Settlement report data
     * @throws ApiException If the report request fails
     */
    public function getSettlementReport(array $params = []): array
    {
        $page = (int) ($params['page'] ?? 1);
        $pageSize = (int) ($params['page_size'] ?? 50);

        $reportBuilder = ReportingService::findSettlementTransactionsPaged($page, $pageSize);
        $this->applyDateRange($reportBuilder, $params, SearchCriteria::START_DATE, SearchCriteria::END_DATE);

        try {
            $response = $reportBuilder->execute();
        } catch (ApiException $e) {
            throw new ApiException('Settlement report generation failed: ' . $e->getMessage());
        }

        $settlements = $this->formatSettlementList($response->result ?? []);

        return [
            'success' => true,
            'data' => [
                'settlements' => $settlements,
                'summary' => $this->generateSettlementSummary($settlements),
                'pagination' => [
                    'page' => $page,
                    'page_size' => $pageSize,
                    'total_count' => $response->totalRecordCount ?? 0
                ]
            ],
            'timestamp' => date('Y-m-d H:i:s')
        ];
    }

    /**
     * Get dispute report with filtering and pagination
     *
     * @param array $filters Dispute filters and pagination
     * @return array Dispute report data
     * @throws ApiException If the report request fails
     */
    public function getDisputeReport(array $filters = []): array
    {
        $page = (int) ($filters['page'] ?? 1);
        $pageSize = (int) ($filters['page_size'] ?? 10);

        // GP API filters disputes on the stage date (from_stage_time_created / to_stage_time_created)
        $reportBuilder = ReportingService::findDisputesPaged($page, $pageSize);
        $this->applyDateRange($reportBuilder, $filters, DataServiceCriteria::START_STAGE_DATE, DataServiceCriteria::END_STAGE_DATE);
        if (!empty($filters['stage'])) {
            $reportBuilder->where(SearchCriteria::DISPUTE_STAGE, strtoupper($filters['stage']));
        }
        if (!empty($filters['status'])) {
            $reportBuilder->where(SearchCriteria::DISPUTE_STATUS, strtoupper($filters['status']));
        }

        try {
            $response = $reportBuilder->execute();
        } catch (ApiException $e) {
            throw new ApiException('Dispute report generation failed: ' . $e->getMessage());
        }

        return [
            'success' => true,
            'data' => [
                'disputes' => array_map(fn($d) => $this->formatDispute($d), $response->result ?? []),
                'pagination' => [
                    'page' => $page,
                    'page_size' => $pageSize,
                    'total_count' => $response->totalRecordCount ?? 0
                ]
            ],
            'timestamp' => date('Y-m-d H:i:s')
        ];
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
        try {
            $dispute = ReportingService::disputeDetail($disputeId)->execute();
        } catch (ApiException $e) {
            throw new ApiException('Failed to retrieve dispute details: ' . $e->getMessage());
        }

        $documents = [];
        foreach ($dispute->documents ?? [] as $document) {
            $documents[] = ['id' => (string) $document->id, 'type' => (string) ($document->type ?? '')];
        }

        return [
            'success' => true,
            'data' => array_merge($this->formatDispute($dispute), [
                'case_description' => '',
                'documents' => $documents,
                'transaction_details' => [
                    'amount' => $dispute->transactionAmount ?? 0,
                    'currency' => $dispute->transactionCurrency ?: 'USD',
                    'masked_card_number' => (string) ($dispute->transactionMaskedCardNumber ?? ''),
                    'arn' => (string) ($dispute->transactionARN ?? '')
                ]
            ]),
            'timestamp' => date('Y-m-d H:i:s')
        ];
    }

    /**
     * Get deposit report with filtering and pagination
     *
     * @param array $filters Deposit filters and pagination
     * @return array Deposit report data
     * @throws ApiException If the report request fails
     */
    public function getDepositReport(array $filters = []): array
    {
        $page = (int) ($filters['page'] ?? 1);
        $pageSize = (int) ($filters['page_size'] ?? 10);

        $reportBuilder = ReportingService::findDepositsPaged($page, $pageSize);
        $this->applyDateRange($reportBuilder, $filters, SearchCriteria::START_DATE, SearchCriteria::END_DATE);
        if (!empty($filters['deposit_id'])) {
            $reportBuilder->where(SearchCriteria::DEPOSIT_ID, $filters['deposit_id']);
        }
        if (!empty($filters['status'])) {
            $reportBuilder->where(SearchCriteria::DEPOSIT_STATUS, strtoupper($filters['status']));
        }

        try {
            $response = $reportBuilder->execute();
        } catch (ApiException $e) {
            throw new ApiException('Deposit report generation failed: ' . $e->getMessage());
        }

        $deposits = array_map(fn($d) => array_merge($this->formatDeposit($d), [
            'sales_count' => $d->salesTotalCount ?? 0,
            'sales_amount' => $d->salesTotalAmount ?? 0,
            'refunds_count' => $d->refundsTotalCount ?? 0,
            'refunds_amount' => $d->refundsTotalAmount ?? 0
        ]), $response->result ?? []);

        return [
            'success' => true,
            'data' => [
                'deposits' => $deposits,
                'pagination' => [
                    'page' => $page,
                    'page_size' => $pageSize,
                    'total_count' => $response->totalRecordCount ?? 0
                ]
            ],
            'timestamp' => date('Y-m-d H:i:s')
        ];
    }

    /**
     * Get detailed information for a specific deposit
     *
     * The SDK's deposit mapping leaves out the bank account, so the deposit is
     * read through the SDK's GP API connector and mapped with its GpApiMapping.
     *
     * @param string $depositId The deposit ID to retrieve
     * @return array Deposit details
     * @throws ApiException If the deposit cannot be found
     */
    public function getDepositDetails(string $depositId): array
    {
        try {
            $response = $this->gpApiGet('/settlement/deposits/' . rawurlencode($depositId));
        } catch (ApiException $e) {
            throw new ApiException('Failed to retrieve deposit details: ' . $e->getMessage());
        }

        $deposit = GpApiMapping::mapDepositSummary($response);

        return [
            'success' => true,
            'data' => array_merge($this->formatDeposit($deposit), [
                'bank_account' => [
                    'masked_account_number' => (string) ($response->bank_transfer->masked_account_number_last4 ?? ''),
                    'bank_name' => (string) ($response->bank_transfer->bank->name ?? '')
                ],
                'transaction_summary' => [
                    'sales_count' => $deposit->salesTotalCount ?? 0,
                    'sales_amount' => $deposit->salesTotalAmount ?? 0,
                    'refunds_count' => $deposit->refundsTotalCount ?? 0,
                    'refunds_amount' => $deposit->refundsTotalAmount ?? 0,
                    'chargebacks_count' => $deposit->chargebackTotalCount ?? 0,
                    'chargebacks_amount' => $deposit->chargebackTotalAmount ?? 0,
                    'adjustments_count' => $deposit->adjustmentTotalCount ?? 0,
                    'adjustments_amount' => $deposit->adjustmentTotalAmount ?? 0
                ]
            ]),
            'timestamp' => date('Y-m-d H:i:s')
        ];
    }

    /**
     * Get batch report. GP API has no batch report endpoint, so this answers
     * with an API error, the same in every language of this sample.
     *
     * @param array $filters Date filters
     * @throws ApiException Always
     */
    public function getBatchReport(array $filters = []): array
    {
        throw new ApiException('Batch report generation failed: batch reports are not available through GP API');
    }

    /**
     * Get declined transactions report
     *
     * @param array $filters Search filters
     * @return array Declined transactions with an analysis
     * @throws ApiException If the search request fails
     */
    public function getDeclinedTransactionsReport(array $filters = []): array
    {
        try {
            $result = $this->searchTransactions(array_merge($filters, ['status' => 'DECLINED']));
        } catch (ApiException $e) {
            throw new ApiException('Declined transactions report generation failed: ' . $e->getMessage());
        }

        $result['data']['decline_analysis'] = $this->analyzeDeclines($result['data']['transactions']);
        return $result;
    }

    /**
     * Get comprehensive date range report across transactions, settlements,
     * disputes and deposits. A section that fails carries its error.
     *
     * @param array $params Date range and report parameters
     * @return array Comprehensive date range report
     */
    public function getDateRangeReport(array $params = []): array
    {
        $startDate = $params['start_date'] ?? date('Y-m-d', strtotime('-30 days'));
        $endDate = $params['end_date'] ?? date('Y-m-d');

        $data = [
            'period' => [
                'start_date' => $startDate,
                'end_date' => $endDate
            ],
            'transactions' => new \stdClass(),
            'settlements' => new \stdClass(),
            'disputes' => new \stdClass(),
            'deposits' => new \stdClass(),
            'summary' => new \stdClass()
        ];

        $sections = [
            ['transactions', 'Transactions', $params['transaction_limit'] ?? 100, 'searchTransactions'],
            ['settlements', 'Settlements', $params['settlement_limit'] ?? 50, 'getSettlementReport'],
            ['disputes', 'Disputes', $params['dispute_limit'] ?? 25, 'getDisputeReport'],
            ['deposits', 'Deposits', $params['deposit_limit'] ?? 25, 'getDepositReport'],
        ];

        foreach ($sections as [$key, $label, $limit, $method]) {
            try {
                $result = $this->$method([
                    'start_date' => $startDate,
                    'end_date' => $endDate,
                    'page_size' => $limit
                ]);
                $data[$key] = $result['data'];
            } catch (ApiException $e) {
                $data[$key] = ['error' => "{$label} not available: " . $e->getMessage()];
            } catch (\Throwable $e) {
                error_log("{$label} not available for the date range report: " . $e->getMessage());
                $data[$key] = ['error' => "{$label} not available"];
            }
        }

        $data['summary'] = $this->generateComprehensiveSummary($data);

        return [
            'success' => true,
            'data' => $data,
            'timestamp' => date('Y-m-d H:i:s')
        ];
    }

    /**
     * Export transaction data in specified format
     *
     * @param array $filters Search filters
     * @param string $format Export format (json, csv or xml)
     * @return array Export data
     * @throws ApiException If export fails
     */
    public function exportTransactions(array $filters = [], string $format = 'json'): array
    {
        try {
            // GP API pages hold up to 1000 transactions
            $result = $this->searchTransactions(array_merge($filters, ['page_size' => 1000]));
        } catch (ApiException $e) {
            throw new ApiException('Export failed: ' . $e->getMessage());
        }

        $transactions = $result['data']['transactions'];

        if ($format === 'csv') {
            return $this->exportToCsv($transactions);
        }

        if ($format === 'xml') {
            return $this->exportToXml($transactions);
        }

        return [
            'success' => true,
            'data' => $transactions,
            'format' => 'json',
            'timestamp' => date('Y-m-d H:i:s')
        ];
    }

    /**
     * Get reporting summary statistics
     *
     * @param array $params Summary parameters
     * @return array Summary statistics
     */
    public function getSummaryStats(array $params = []): array
    {
        $startDate = $params['start_date'] ?? date('Y-m-d', strtotime('-30 days'));
        $endDate = $params['end_date'] ?? date('Y-m-d');

        try {
            $result = $this->searchTransactions([
                'start_date' => $startDate,
                'end_date' => $endDate,
                'page_size' => 1000
            ]);
        } catch (\Exception $e) {
            $message = 'Failed to generate summary statistics';
            if ($e instanceof ApiException) {
                $message .= ': ' . $e->getMessage();
            } else {
                error_log($message . ': ' . $e->getMessage());
            }
            return [
                'success' => false,
                'error' => $message,
                'timestamp' => date('Y-m-d H:i:s')
            ];
        }

        return [
            'success' => true,
            'data' => $this->calculateSummaryStats($result['data']['transactions']),
            'period' => [
                'start_date' => $startDate,
                'end_date' => $endDate
            ],
            'timestamp' => date('Y-m-d H:i:s')
        ];
    }

    /**
     * GET a GP API endpoint through the SDK's configured connector, which signs
     * in and raises the usual SDK errors. Empty parameters are left out.
     *
     * @param string $endpoint GP API path, e.g. /transactions
     * @param array $query Query parameters
     * @return object The decoded GP API response
     * @throws ApiException If the request fails
     */
    private function gpApiGet(string $endpoint, array $query = []): object
    {
        $connector = ServicesContainer::instance()->getClient('default');
        $query = array_filter($query, fn($value) => $value !== null && $value !== '');
        $response = $connector->doTransaction('GET', $endpoint, null, $query ?: null);
        return is_object($response) ? $response : new \stdClass();
    }

    /**
     * Add YYYY-MM-DD start and end dates to a report builder
     */
    private function applyDateRange($reportBuilder, array $filters, string $startCriteria, string $endCriteria): void
    {
        if (!empty($filters['start_date'])) {
            $reportBuilder->where($startCriteria, \DateTime::createFromFormat('!Y-m-d', $filters['start_date']));
        }
        if (!empty($filters['end_date'])) {
            $reportBuilder->where($endCriteria, \DateTime::createFromFormat('!Y-m-d', $filters['end_date']));
        }
    }

    /**
     * Map payment type string to the GP API value
     */
    private function mapPaymentType(string $type): ?string
    {
        return [
            'sale' => PaymentType::SALE,
            'refund' => PaymentType::REFUND
        ][strtolower($type)] ?? null;
    }

    /**
     * SDK dates as ISO strings in UTC, as GP API sends them
     *
     * @param mixed $value DateTime, date string or empty
     * @return string
     */
    private function isoString($value): string
    {
        if ($value instanceof \DateTimeInterface) {
            return \DateTime::createFromInterface($value)
                ->setTimezone(new \DateTimeZone('UTC'))
                ->format('Y-m-d\TH:i:s.v\Z');
        }
        return is_string($value) ? $value : '';
    }

    /**
     * Format one transaction row. The card brand and the auth code come from
     * the GP API response, the SDK mapping does not fill them in.
     *
     * @param object $transaction SDK transaction summary
     * @param object $row GP API transaction
     * @return array
     */
    private function formatTransaction($transaction, $row): array
    {
        return [
            'transaction_id' => (string) ($transaction->transactionId ?? ''),
            'timestamp' => $this->isoString($transaction->transactionDate ?? ''),
            'amount' => $transaction->amount ?? 0,
            'currency' => $transaction->currency ?: 'USD',
            'status' => (string) ($transaction->transactionStatus ?? ''),
            'payment_method' => (string) ($transaction->paymentType ?? ''),
            'card_last_four' => (string) ($transaction->maskedCardNumber ?? $transaction->maskedPaymentToken ?? ''),
            'auth_code' => (string) ($row->payment_method->card->authcode ?? ''),
            'reference_number' => (string) ($transaction->referenceNumber ?? '')
        ];
    }

    /**
     * Format settlement list for API response. GP API returns one settled
     * transaction per row, so each row counts one transaction.
     */
    private function formatSettlementList(array $settlements): array
    {
        return array_map(fn($s) => [
            'settlement_id' => (string) ($s->transactionId ?? ''),
            'settlement_date' => $this->isoString($s->depositTimeCreated ?: $s->transactionDate),
            'transaction_count' => 1,
            'total_amount' => $s->amount ?? 0,
            'currency' => $s->currency ?: 'USD',
            'status' => (string) ($s->transactionStatus ?? '')
        ], $settlements);
    }

    /**
     * Generate settlement summary statistics
     */
    private function generateSettlementSummary(array $settlements): array
    {
        $totalAmount = array_sum(array_column($settlements, 'total_amount'));

        return [
            'total_settlements' => count($settlements),
            'total_amount' => $totalAmount,
            'total_transactions' => array_sum(array_column($settlements, 'transaction_count')),
            'average_settlement_amount' => count($settlements) > 0 ? $totalAmount / count($settlements) : 0
        ];
    }

    /**
     * Fields shared by the dispute list and the dispute detail
     */
    private function formatDispute($dispute): array
    {
        return [
            'dispute_id' => (string) ($dispute->caseId ?? ''),
            'transaction_id' => '',
            'case_number' => '',
            'dispute_stage' => (string) ($dispute->caseStage ?? ''),
            'dispute_status' => (string) ($dispute->caseStatus ?? ''),
            'case_amount' => $dispute->caseAmount ?? 0,
            'currency' => $dispute->caseCurrency ?: 'USD',
            'reason_code' => (string) ($dispute->reasonCode ?? ''),
            'reason_description' => (string) ($dispute->reason ?? ''),
            // The list has only the stage time, the detail has the time the case was created
            'case_time' => $this->isoString($dispute->caseIdTime ?? $dispute->disputeStageTime ?? ''),
            'last_adjustment_time' => ''
        ];
    }

    /**
     * Fields shared by the deposit list and the deposit detail
     */
    private function formatDeposit($deposit): array
    {
        return [
            'deposit_id' => (string) ($deposit->depositId ?? ''),
            // GP API sends the deposit date as a plain date
            'deposit_date' => $deposit->depositDate instanceof \DateTimeInterface ? $deposit->depositDate->format('Y-m-d') : '',
            'deposit_reference' => '',
            'deposit_status' => (string) ($deposit->status ?? ''),
            'deposit_amount' => $deposit->amount ?? 0,
            'currency' => $deposit->currency ?: 'USD',
            'merchant_number' => (string) ($deposit->merchantId ?? ''),
            'merchant_hierarchy' => (string) ($deposit->merchantHierarchy ?? '')
        ];
    }

    /**
     * Calculate summary statistics from transaction data
     */
    private function calculateSummaryStats(array $transactions): array
    {
        $totalAmount = array_sum(array_column($transactions, 'amount'));
        $statusCounts = [];
        $paymentTypeCounts = [];

        foreach ($transactions as $transaction) {
            $status = $transaction['status'] ?: 'unknown';
            $statusCounts[$status] = ($statusCounts[$status] ?? 0) + 1;

            $paymentType = $transaction['payment_method'] ?: 'unknown';
            $paymentTypeCounts[$paymentType] = ($paymentTypeCounts[$paymentType] ?? 0) + 1;
        }

        return [
            'total_transactions' => count($transactions),
            'total_amount' => $totalAmount,
            'average_amount' => count($transactions) > 0 ? $totalAmount / count($transactions) : 0,
            'status_breakdown' => (object) $statusCounts,
            'payment_type_breakdown' => (object) $paymentTypeCounts
        ];
    }

    /**
     * Export transaction data to CSV format
     */
    private function exportToCsv(array $transactions): array
    {
        $stream = fopen('php://temp', 'r+');
        fwrite($stream, "Transaction ID,Timestamp,Amount,Currency,Status,Payment Method,Card Last Four,Auth Code,Reference Number\n");

        foreach ($transactions as $transaction) {
            fputcsv($stream, [
                $transaction['transaction_id'],
                $transaction['timestamp'],
                number_format((float) $transaction['amount'], 2, '.', ''),
                $transaction['currency'],
                $transaction['status'],
                $transaction['payment_method'],
                $transaction['card_last_four'],
                $transaction['auth_code'],
                $transaction['reference_number']
            ], ',', '"', '');
        }

        rewind($stream);
        $csvData = stream_get_contents($stream);
        fclose($stream);

        return [
            'success' => true,
            'data' => $csvData,
            'format' => 'csv',
            'filename' => 'transactions_' . date('Y-m-d_H-i-s') . '.csv',
            'timestamp' => date('Y-m-d H:i:s')
        ];
    }

    /**
     * Export transaction data to XML format
     */
    private function exportToXml(array $transactions): array
    {
        $xml = new \XMLWriter();
        $xml->openMemory();
        $xml->setIndent(true);
        $xml->setIndentString('  ');
        $xml->startDocument('1.0', 'UTF-8');
        $xml->startElement('transactions');

        foreach ($transactions as $transaction) {
            $xml->startElement('transaction');
            foreach ($transaction as $field => $value) {
                $xml->writeElement($field, (string) $value);
            }
            $xml->endElement();
        }

        $xml->endElement();
        $xml->endDocument();

        return [
            'success' => true,
            'data' => $xml->outputMemory(),
            'format' => 'xml',
            'filename' => 'transactions_' . date('Y-m-d_H-i-s') . '.xml',
            'timestamp' => date('Y-m-d H:i:s')
        ];
    }

    /**
     * Analyze decline patterns from transaction data
     */
    private function analyzeDeclines(array $transactions): array
    {
        $declineReasons = [];
        $cardTypes = [];
        $hourlyBreakdown = [];
        $totalAmount = array_sum(array_column($transactions, 'amount'));

        foreach ($transactions as $transaction) {
            // The list rows carry no gateway message
            $declineReasons['Unknown'] = ($declineReasons['Unknown'] ?? 0) + 1;

            $cardType = $transaction['payment_method'] ?: 'Unknown';
            $cardTypes[$cardType] = ($cardTypes[$cardType] ?? 0) + 1;

            // Hours are counted in UTC, as GP API sends the times
            if (!empty($transaction['timestamp'])) {
                $hour = (new \DateTime($transaction['timestamp']))->setTimezone(new \DateTimeZone('UTC'))->format('H');
                $hourlyBreakdown[$hour] = ($hourlyBreakdown[$hour] ?? 0) + 1;
            }
        }

        return [
            'total_declined_transactions' => count($transactions),
            'total_declined_amount' => $totalAmount,
            'average_declined_amount' => count($transactions) > 0 ? $totalAmount / count($transactions) : 0,
            'decline_reasons' => (object) $declineReasons,
            'card_type_breakdown' => (object) $cardTypes,
            'hourly_breakdown' => (object) $hourlyBreakdown
        ];
    }

    /**
     * Generate comprehensive summary for date range report
     */
    private function generateComprehensiveSummary(array $reportData): array
    {
        $overview = [];
        $financial = [];
        $operational = [];

        $transactions = is_array($reportData['transactions']) ? ($reportData['transactions']['transactions'] ?? []) : [];
        if (count($transactions) > 0) {
            $totalAmount = array_sum(array_column($transactions, 'amount'));
            $overview['transactions'] = [
                'count' => count($transactions),
                'total_amount' => $totalAmount,
                'average_amount' => $totalAmount / count($transactions)
            ];
        }

        $settlements = is_array($reportData['settlements']) ? ($reportData['settlements']['settlements'] ?? []) : [];
        if (count($settlements) > 0) {
            $financial['settlements'] = [
                'count' => count($settlements),
                'total_amount' => array_sum(array_column($settlements, 'total_amount'))
            ];
        }

        $disputes = is_array($reportData['disputes']) ? ($reportData['disputes']['disputes'] ?? []) : [];
        if (count($disputes) > 0) {
            $operational['disputes'] = [
                'count' => count($disputes),
                'total_amount' => array_sum(array_column($disputes, 'case_amount')),
                'dispute_rate' => count($transactions) > 0 ? (count($disputes) / count($transactions)) * 100 : 0
            ];
        }

        $deposits = is_array($reportData['deposits']) ? ($reportData['deposits']['deposits'] ?? []) : [];
        if (count($deposits) > 0) {
            $financial['deposits'] = [
                'count' => count($deposits),
                'total_amount' => array_sum(array_column($deposits, 'deposit_amount'))
            ];
        }

        return [
            'overview' => (object) $overview,
            'financial_summary' => (object) $financial,
            'operational_metrics' => (object) $operational
        ];
    }
}
