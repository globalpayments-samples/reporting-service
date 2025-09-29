<?php

declare(strict_types=1);

/**
 * Global Payments Reporting API Endpoint
 *
 * This script provides RESTful API endpoints for accessing Global Payments
 * reporting functionality including transaction search, details, settlement
 * reports, and data export capabilities.
 *
 * PHP version 7.4 or higher
 *
 * @category  API_Endpoint
 * @package   GlobalPayments_Reporting
 * @author    Global Payments
 * @license   MIT License
 * @link      https://github.com/globalpayments
 */

require_once 'reporting-service.php';

ini_set('display_errors', '0');

/**
 * Set JSON response headers
 */
function setJsonHeaders(): void
{
    header('Content-Type: application/json');
    header('Access-Control-Allow-Origin: *');
    header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
    header('Access-Control-Allow-Headers: Content-Type, Authorization');

    // Handle preflight OPTIONS request
    if (isset($_SERVER['REQUEST_METHOD']) && $_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
        http_response_code(200);
        exit;
    }
}

/**
 * Send JSON response
 *
 * @param array $data Response data
 * @param int $statusCode HTTP status code
 */
function sendJsonResponse(array $data, int $statusCode = 200): void
{
    http_response_code($statusCode);
    echo json_encode($data, JSON_PRETTY_PRINT);
    exit;
}

/**
 * Handle error responses
 *
 * @param string $message Error message
 * @param int $statusCode HTTP status code
 * @param string $errorCode Error code
 */
function handleError(string $message, int $statusCode = 400, string $errorCode = 'API_ERROR'): void
{
    sendJsonResponse([
        'success' => false,
        'error' => [
            'code' => $errorCode,
            'message' => $message,
            'timestamp' => date('Y-m-d H:i:s')
        ]
    ], $statusCode);
}

/**
 * Get request parameters (supports both GET and POST)
 *
 * @return array Combined request parameters
 */
function getRequestParams(): array
{
    $params = $_GET;

    if (isset($_SERVER['REQUEST_METHOD']) && $_SERVER['REQUEST_METHOD'] === 'POST') {
        $postData = json_decode(file_get_contents('php://input'), true);
        if (is_array($postData)) {
            $params = array_merge($params, $postData);
        } else {
            $params = array_merge($params, $_POST);
        }
    }

    return $params;
}

/**
 * Validate required parameters
 *
 * @param array $params Request parameters
 * @param array $required Required parameter names
 * @throws InvalidArgumentException If required parameters are missing
 */
function validateRequiredParams(array $params, array $required): void
{
    foreach ($required as $param) {
        if (!isset($params[$param]) || $params[$param] === '') {
            throw new \InvalidArgumentException("Missing required parameter: {$param}");
        }
    }
}

/**
 * Validate date format
 *
 * @param string $date Date string
 * @param string $format Expected date format
 * @return bool True if valid
 */
function validateDateFormat(string $date, string $format = 'Y-m-d'): bool
{
    $dateObj = \DateTime::createFromFormat($format, $date);
    return $dateObj && $dateObj->format($format) === $date;
}

// Set response headers
setJsonHeaders();

try {
    // Initialize the reporting service
    $reportingService = new GlobalPaymentsReportingService();

    // Get request parameters
    $params = getRequestParams();
    $action = $params['action'] ?? '';

    // Route requests based on action parameter
    switch ($action) {
        case 'search':
            // Search transactions
            $filters = [
                'page' => (int)($params['page'] ?? 1),
                'page_size' => min((int)($params['page_size'] ?? 10), 100), // Limit page size
                'start_date' => $params['start_date'] ?? '',
                'end_date' => $params['end_date'] ?? '',
                'transaction_id' => $params['transaction_id'] ?? '',
                'payment_type' => $params['payment_type'] ?? '',
                'status' => $params['status'] ?? '',
                'amount_min' => $params['amount_min'] ?? '',
                'amount_max' => $params['amount_max'] ?? '',
                'card_last_four' => $params['card_last_four'] ?? ''
            ];

            // Validate date formats if provided
            if ($filters['start_date'] && !validateDateFormat($filters['start_date'])) {
                throw new \InvalidArgumentException('Invalid start_date format. Use YYYY-MM-DD.');
            }

            if ($filters['end_date'] && !validateDateFormat($filters['end_date'])) {
                throw new \InvalidArgumentException('Invalid end_date format. Use YYYY-MM-DD.');
            }

            // Remove empty filters
            $filters = array_filter($filters, function($value) {
                return $value !== '' && $value !== null;
            });

            $result = $reportingService->searchTransactions($filters);
            sendJsonResponse($result);
            break;

        case 'detail':
            // Get transaction details
            validateRequiredParams($params, ['transaction_id']);

            $result = $reportingService->getTransactionDetails($params['transaction_id']);
            sendJsonResponse($result);
            break;

        case 'settlement':
            // Get settlement report
            $settlementParams = [
                'page' => (int)($params['page'] ?? 1),
                'page_size' => min((int)($params['page_size'] ?? 50), 100),
                'start_date' => $params['start_date'] ?? '',
                'end_date' => $params['end_date'] ?? ''
            ];

            // Validate date formats if provided
            if ($settlementParams['start_date'] && !validateDateFormat($settlementParams['start_date'])) {
                throw new \InvalidArgumentException('Invalid start_date format. Use YYYY-MM-DD.');
            }

            if ($settlementParams['end_date'] && !validateDateFormat($settlementParams['end_date'])) {
                throw new \InvalidArgumentException('Invalid end_date format. Use YYYY-MM-DD.');
            }

            // Remove empty filters
            $settlementParams = array_filter($settlementParams, function($value) {
                return $value !== '' && $value !== null;
            });

            $result = $reportingService->getSettlementReport($settlementParams);
            sendJsonResponse($result);
            break;

        case 'export':
            // Export transaction data
            $exportFilters = [
                'start_date' => $params['start_date'] ?? '',
                'end_date' => $params['end_date'] ?? '',
                'transaction_id' => $params['transaction_id'] ?? '',
                'payment_type' => $params['payment_type'] ?? '',
                'status' => $params['status'] ?? '',
                'amount_min' => $params['amount_min'] ?? '',
                'amount_max' => $params['amount_max'] ?? '',
                'card_last_four' => $params['card_last_four'] ?? ''
            ];

            $format = $params['format'] ?? 'json';
            if (!in_array($format, ['json', 'csv'])) {
                throw new \InvalidArgumentException('Invalid format. Supported formats: json, csv');
            }

            // Validate date formats if provided
            if ($exportFilters['start_date'] && !validateDateFormat($exportFilters['start_date'])) {
                throw new \InvalidArgumentException('Invalid start_date format. Use YYYY-MM-DD.');
            }

            if ($exportFilters['end_date'] && !validateDateFormat($exportFilters['end_date'])) {
                throw new \InvalidArgumentException('Invalid end_date format. Use YYYY-MM-DD.');
            }

            // Remove empty filters
            $exportFilters = array_filter($exportFilters, function($value) {
                return $value !== '' && $value !== null;
            });

            $result = $reportingService->exportTransactions($exportFilters, $format);

            if ($format === 'csv') {
                header('Content-Type: text/csv');
                header('Content-Disposition: attachment; filename="' . ($result['filename'] ?? 'transactions.csv') . '"');
                echo $result['data'];
                exit;
            }

            sendJsonResponse($result);
            break;

        case 'summary':
            // Get summary statistics
            $summaryParams = [
                'start_date' => $params['start_date'] ?? '',
                'end_date' => $params['end_date'] ?? ''
            ];

            // Validate date formats if provided
            if ($summaryParams['start_date'] && !validateDateFormat($summaryParams['start_date'])) {
                throw new \InvalidArgumentException('Invalid start_date format. Use YYYY-MM-DD.');
            }

            if ($summaryParams['end_date'] && !validateDateFormat($summaryParams['end_date'])) {
                throw new \InvalidArgumentException('Invalid end_date format. Use YYYY-MM-DD.');
            }

            // Remove empty filters
            $summaryParams = array_filter($summaryParams, function($value) {
                return $value !== '' && $value !== null;
            });

            $result = $reportingService->getSummaryStats($summaryParams);
            sendJsonResponse($result);
            break;

        case 'disputes':
            // Get dispute report
            $disputeFilters = [
                'page' => (int)($params['page'] ?? 1),
                'page_size' => min((int)($params['page_size'] ?? 10), 100),
                'start_date' => $params['start_date'] ?? '',
                'end_date' => $params['end_date'] ?? '',
                'stage' => $params['stage'] ?? '',
                'status' => $params['status'] ?? ''
            ];

            // Validate date formats if provided
            if ($disputeFilters['start_date'] && !validateDateFormat($disputeFilters['start_date'])) {
                throw new \InvalidArgumentException('Invalid start_date format. Use YYYY-MM-DD.');
            }

            if ($disputeFilters['end_date'] && !validateDateFormat($disputeFilters['end_date'])) {
                throw new \InvalidArgumentException('Invalid end_date format. Use YYYY-MM-DD.');
            }

            // Remove empty filters
            $disputeFilters = array_filter($disputeFilters, function($value) {
                return $value !== '' && $value !== null;
            });

            $result = $reportingService->getDisputeReport($disputeFilters);
            sendJsonResponse($result);
            break;

        case 'dispute_detail':
            // Get dispute details
            validateRequiredParams($params, ['dispute_id']);

            $result = $reportingService->getDisputeDetails($params['dispute_id']);
            sendJsonResponse($result);
            break;

        case 'deposits':
            // Get deposit report
            $depositFilters = [
                'page' => (int)($params['page'] ?? 1),
                'page_size' => min((int)($params['page_size'] ?? 10), 100),
                'start_date' => $params['start_date'] ?? '',
                'end_date' => $params['end_date'] ?? '',
                'deposit_id' => $params['deposit_id'] ?? '',
                'status' => $params['status'] ?? ''
            ];

            // Validate date formats if provided
            if ($depositFilters['start_date'] && !validateDateFormat($depositFilters['start_date'])) {
                throw new \InvalidArgumentException('Invalid start_date format. Use YYYY-MM-DD.');
            }

            if ($depositFilters['end_date'] && !validateDateFormat($depositFilters['end_date'])) {
                throw new \InvalidArgumentException('Invalid end_date format. Use YYYY-MM-DD.');
            }

            // Remove empty filters
            $depositFilters = array_filter($depositFilters, function($value) {
                return $value !== '' && $value !== null;
            });

            $result = $reportingService->getDepositReport($depositFilters);
            sendJsonResponse($result);
            break;

        case 'deposit_detail':
            // Get deposit details
            validateRequiredParams($params, ['deposit_id']);

            $result = $reportingService->getDepositDetails($params['deposit_id']);
            sendJsonResponse($result);
            break;

        case 'batches':
            // Get batch report
            $batchFilters = [
                'start_date' => $params['start_date'] ?? '',
                'end_date' => $params['end_date'] ?? ''
            ];

            // Validate date formats if provided
            if ($batchFilters['start_date'] && !validateDateFormat($batchFilters['start_date'])) {
                throw new \InvalidArgumentException('Invalid start_date format. Use YYYY-MM-DD.');
            }

            if ($batchFilters['end_date'] && !validateDateFormat($batchFilters['end_date'])) {
                throw new \InvalidArgumentException('Invalid end_date format. Use YYYY-MM-DD.');
            }

            // Remove empty filters
            $batchFilters = array_filter($batchFilters, function($value) {
                return $value !== '' && $value !== null;
            });

            $result = $reportingService->getBatchReport($batchFilters);
            sendJsonResponse($result);
            break;

        case 'declines':
            // Get declined transactions report
            $declineFilters = [
                'page' => (int)($params['page'] ?? 1),
                'page_size' => min((int)($params['page_size'] ?? 10), 100),
                'start_date' => $params['start_date'] ?? '',
                'end_date' => $params['end_date'] ?? '',
                'payment_type' => $params['payment_type'] ?? '',
                'amount_min' => $params['amount_min'] ?? '',
                'amount_max' => $params['amount_max'] ?? '',
                'card_last_four' => $params['card_last_four'] ?? ''
            ];

            // Validate date formats if provided
            if ($declineFilters['start_date'] && !validateDateFormat($declineFilters['start_date'])) {
                throw new \InvalidArgumentException('Invalid start_date format. Use YYYY-MM-DD.');
            }

            if ($declineFilters['end_date'] && !validateDateFormat($declineFilters['end_date'])) {
                throw new \InvalidArgumentException('Invalid end_date format. Use YYYY-MM-DD.');
            }

            // Remove empty filters
            $declineFilters = array_filter($declineFilters, function($value) {
                return $value !== '' && $value !== null;
            });

            $result = $reportingService->getDeclinedTransactionsReport($declineFilters);
            sendJsonResponse($result);
            break;

        case 'date_range':
            // Get comprehensive date range report
            $dateRangeParams = [
                'start_date' => $params['start_date'] ?? '',
                'end_date' => $params['end_date'] ?? '',
                'transaction_limit' => min((int)($params['transaction_limit'] ?? 100), 1000),
                'settlement_limit' => min((int)($params['settlement_limit'] ?? 50), 500),
                'dispute_limit' => min((int)($params['dispute_limit'] ?? 25), 100),
                'deposit_limit' => min((int)($params['deposit_limit'] ?? 25), 100)
            ];

            // Validate date formats if provided
            if ($dateRangeParams['start_date'] && !validateDateFormat($dateRangeParams['start_date'])) {
                throw new \InvalidArgumentException('Invalid start_date format. Use YYYY-MM-DD.');
            }

            if ($dateRangeParams['end_date'] && !validateDateFormat($dateRangeParams['end_date'])) {
                throw new \InvalidArgumentException('Invalid end_date format. Use YYYY-MM-DD.');
            }

            // Remove empty filters
            $dateRangeParams = array_filter($dateRangeParams, function($value) {
                return $value !== '' && $value !== null;
            });

            $result = $reportingService->getDateRangeReport($dateRangeParams);
            sendJsonResponse($result);
            break;

        case 'config':
            // Get configuration status
            $configStatus = getSdkConfigStatus();
            $envValidation = validateEnvironmentConfig();

            sendJsonResponse([
                'success' => true,
                'data' => [
                    'sdk_status' => $configStatus,
                    'environment_validation' => $envValidation,
                    'api_endpoints' => [
                        'search' => '/reports.php?action=search',
                        'detail' => '/reports.php?action=detail&transaction_id={id}',
                        'settlement' => '/reports.php?action=settlement',
                        'disputes' => '/reports.php?action=disputes',
                        'dispute_detail' => '/reports.php?action=dispute_detail&dispute_id={id}',
                        'deposits' => '/reports.php?action=deposits',
                        'deposit_detail' => '/reports.php?action=deposit_detail&deposit_id={id}',
                        'batches' => '/reports.php?action=batches',
                        'declines' => '/reports.php?action=declines',
                        'date_range' => '/reports.php?action=date_range',
                        'export' => '/reports.php?action=export&format={json|csv}',
                        'summary' => '/reports.php?action=summary',
                        'config' => '/reports.php?action=config'
                    ]
                ],
                'timestamp' => date('Y-m-d H:i:s')
            ]);
            break;

        case '':
            // Default action - show API documentation
            sendJsonResponse([
                'success' => true,
                'data' => [
                    'name' => 'Global Payments Reporting API',
                    'version' => '1.0.0',
                    'description' => 'RESTful API for Global Payments transaction reporting and analytics',
                    'endpoints' => [
                        'search' => [
                            'url' => '/reports.php?action=search',
                            'method' => 'GET/POST',
                            'description' => 'Search transactions with filters and pagination',
                            'parameters' => [
                                'page' => 'Page number (default: 1)',
                                'page_size' => 'Results per page (default: 10, max: 100)',
                                'start_date' => 'Start date (YYYY-MM-DD)',
                                'end_date' => 'End date (YYYY-MM-DD)',
                                'transaction_id' => 'Specific transaction ID',
                                'payment_type' => 'Payment type (sale, refund, authorize, capture)',
                                'status' => 'Transaction status',
                                'amount_min' => 'Minimum amount',
                                'amount_max' => 'Maximum amount',
                                'card_last_four' => 'Last 4 digits of card'
                            ]
                        ],
                        'detail' => [
                            'url' => '/reports.php?action=detail&transaction_id={id}',
                            'method' => 'GET',
                            'description' => 'Get detailed transaction information',
                            'parameters' => [
                                'transaction_id' => 'Transaction ID (required)'
                            ]
                        ],
                        'settlement' => [
                            'url' => '/reports.php?action=settlement',
                            'method' => 'GET/POST',
                            'description' => 'Get settlement report',
                            'parameters' => [
                                'page' => 'Page number (default: 1)',
                                'page_size' => 'Results per page (default: 50, max: 100)',
                                'start_date' => 'Start date (YYYY-MM-DD)',
                                'end_date' => 'End date (YYYY-MM-DD)'
                            ]
                        ],
                        'export' => [
                            'url' => '/reports.php?action=export&format={json|csv}',
                            'method' => 'GET/POST',
                            'description' => 'Export transaction data',
                            'parameters' => [
                                'format' => 'Export format (json or csv)',
                                '...filters' => 'Same filters as search endpoint'
                            ]
                        ],
                        'summary' => [
                            'url' => '/reports.php?action=summary',
                            'method' => 'GET/POST',
                            'description' => 'Get summary statistics',
                            'parameters' => [
                                'start_date' => 'Start date (YYYY-MM-DD)',
                                'end_date' => 'End date (YYYY-MM-DD)'
                            ]
                        ],
                        'config' => [
                            'url' => '/reports.php?action=config',
                            'method' => 'GET',
                            'description' => 'Get API configuration and status'
                        ]
                    ]
                ],
                'timestamp' => date('Y-m-d H:i:s')
            ]);
            break;

        default:
            throw new \InvalidArgumentException("Invalid action: {$action}");
    }

} catch (\InvalidArgumentException $e) {
    handleError($e->getMessage(), 400, 'VALIDATION_ERROR');
} catch (\GlobalPayments\Api\Entities\Exceptions\ApiException $e) {
    handleError($e->getMessage(), 400, 'API_ERROR');
} catch (\Exception $e) {
    handleError('An unexpected error occurred: ' . $e->getMessage(), 500, 'INTERNAL_ERROR');
}