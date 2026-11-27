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

/**
 * Validate YYYY-MM-DD start and end dates when they are given
 *
 * @param array $params Request parameters
 * @throws InvalidArgumentException If a date is not a real YYYY-MM-DD date
 */
function validateDates(array $params): void
{
    foreach (['start_date', 'end_date'] as $name) {
        if (!empty($params[$name]) && !validateDateFormat((string) $params[$name])) {
            throw new \InvalidArgumentException("Invalid {$name} format. Use YYYY-MM-DD.");
        }
    }
}

/**
 * Copy the named parameters that are set
 */
function pick(array $params, array $names): array
{
    $out = [];
    foreach ($names as $name) {
        if (isset($params[$name]) && $params[$name] !== '' && !is_array($params[$name])) {
            $out[$name] = (string) $params[$name];
        }
    }
    return $out;
}

/**
 * Read a positive number, falling back to the default and capping it
 */
function limit(array $params, string $name, int $default, int $max): int
{
    $value = (int) ($params[$name] ?? 0);
    return min($value > 0 ? $value : $default, $max);
}

function page(array $params): int
{
    $value = (int) ($params['page'] ?? 0);
    return $value > 0 ? $value : 1;
}

const SEARCH_FILTERS = ['start_date', 'end_date', 'transaction_id', 'payment_type', 'status', 'amount_min', 'amount_max', 'card_last_four'];

/**
 * Check the request for an action and return a function that runs it
 *
 * @param string $action Report action
 * @param array $params Request parameters
 * @return callable Runs the action with the reporting service
 * @throws InvalidArgumentException If the request is not valid
 */
function prepareAction(string $action, array $params): callable
{
    switch ($action) {
        case 'search':
            $filters = pick($params, SEARCH_FILTERS);
            $filters['page'] = page($params);
            $filters['page_size'] = limit($params, 'page_size', 10, 100);
            validateDates($filters);
            return fn($service) => $service->searchTransactions($filters);

        case 'detail':
            validateRequiredParams($params, ['transaction_id']);
            return fn($service) => $service->getTransactionDetails((string) $params['transaction_id']);

        case 'settlement':
            $filters = pick($params, ['start_date', 'end_date']);
            $filters['page'] = page($params);
            $filters['page_size'] = limit($params, 'page_size', 50, 100);
            validateDates($filters);
            return fn($service) => $service->getSettlementReport($filters);

        case 'export':
            $filters = pick($params, SEARCH_FILTERS);
            $format = (string) ($params['format'] ?? '') ?: 'json';
            if (!in_array($format, ['json', 'csv', 'xml'], true)) {
                throw new \InvalidArgumentException('Invalid format. Supported formats: json, csv, xml');
            }
            validateDates($filters);
            return fn($service) => $service->exportTransactions($filters, $format);

        case 'summary':
            $filters = pick($params, ['start_date', 'end_date']);
            validateDates($filters);
            return fn($service) => $service->getSummaryStats($filters);

        case 'disputes':
            $filters = pick($params, ['start_date', 'end_date', 'stage', 'status']);
            $filters['page'] = page($params);
            $filters['page_size'] = limit($params, 'page_size', 10, 100);
            validateDates($filters);
            return fn($service) => $service->getDisputeReport($filters);

        case 'dispute_detail':
            validateRequiredParams($params, ['dispute_id']);
            return fn($service) => $service->getDisputeDetails((string) $params['dispute_id']);

        case 'deposits':
            $filters = pick($params, ['start_date', 'end_date', 'deposit_id', 'status']);
            $filters['page'] = page($params);
            $filters['page_size'] = limit($params, 'page_size', 10, 100);
            validateDates($filters);
            return fn($service) => $service->getDepositReport($filters);

        case 'deposit_detail':
            validateRequiredParams($params, ['deposit_id']);
            return fn($service) => $service->getDepositDetails((string) $params['deposit_id']);

        case 'batches':
            $filters = pick($params, ['start_date', 'end_date']);
            validateDates($filters);
            return fn($service) => $service->getBatchReport($filters);

        case 'declines':
            $filters = pick($params, ['start_date', 'end_date', 'payment_type', 'amount_min', 'amount_max', 'card_last_four']);
            $filters['page'] = page($params);
            $filters['page_size'] = limit($params, 'page_size', 10, 100);
            validateDates($filters);
            return fn($service) => $service->getDeclinedTransactionsReport($filters);

        case 'date_range':
            $filters = pick($params, ['start_date', 'end_date']);
            $filters['transaction_limit'] = limit($params, 'transaction_limit', 100, 1000);
            $filters['settlement_limit'] = limit($params, 'settlement_limit', 50, 500);
            $filters['dispute_limit'] = limit($params, 'dispute_limit', 25, 100);
            $filters['deposit_limit'] = limit($params, 'deposit_limit', 25, 100);
            validateDates($filters);
            return fn($service) => $service->getDateRangeReport($filters);

        default:
            throw new \InvalidArgumentException("Invalid action: {$action}");
    }
}

/**
 * The answer to reports.php without an action
 */
function apiDocumentation(): array
{
    $dates = [
        'start_date' => 'Start date (YYYY-MM-DD)',
        'end_date' => 'End date (YYYY-MM-DD)'
    ];
    $paging = fn(int $default) => [
        'page' => 'Page number (default: 1)',
        'page_size' => "Results per page (default: {$default}, max: 100)"
    ];

    return [
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
                    'parameters' => $paging(10) + $dates + [
                        'transaction_id' => 'Specific transaction ID',
                        'payment_type' => 'Payment type (sale, refund)',
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
                    'parameters' => $paging(50) + $dates
                ],
                'export' => [
                    'url' => '/reports.php?action=export&format={json|csv|xml}',
                    'method' => 'GET/POST',
                    'description' => 'Export transaction data',
                    'parameters' => [
                        'format' => 'Export format (json, csv, or xml)',
                        '...filters' => 'Same filters as search endpoint'
                    ]
                ],
                'summary' => [
                    'url' => '/reports.php?action=summary',
                    'method' => 'GET/POST',
                    'description' => 'Get summary statistics',
                    'parameters' => $dates
                ],
                'disputes' => [
                    'url' => '/reports.php?action=disputes',
                    'method' => 'GET/POST',
                    'description' => 'Get dispute report',
                    'parameters' => $paging(10) + $dates + [
                        'stage' => 'Dispute stage',
                        'status' => 'Dispute status'
                    ]
                ],
                'deposits' => [
                    'url' => '/reports.php?action=deposits',
                    'method' => 'GET/POST',
                    'description' => 'Get deposit report',
                    'parameters' => $paging(10) + $dates + [
                        'deposit_id' => 'Specific deposit ID',
                        'status' => 'Deposit status'
                    ]
                ],
                'batches' => [
                    'url' => '/reports.php?action=batches',
                    'method' => 'GET/POST',
                    'description' => 'Get batch report',
                    'parameters' => $dates
                ],
                'declines' => [
                    'url' => '/reports.php?action=declines',
                    'method' => 'GET/POST',
                    'description' => 'Get declined transactions report',
                    'parameters' => $paging(10) + $dates + [
                        'payment_type' => 'Payment type',
                        'amount_min' => 'Minimum amount',
                        'amount_max' => 'Maximum amount',
                        'card_last_four' => 'Last 4 digits of card'
                    ]
                ],
                'date_range' => [
                    'url' => '/reports.php?action=date_range',
                    'method' => 'GET/POST',
                    'description' => 'Get comprehensive date range report',
                    'parameters' => $dates + [
                        'transaction_limit' => 'Max transactions (default: 100, max: 1000)',
                        'settlement_limit' => 'Max settlements (default: 50, max: 500)',
                        'dispute_limit' => 'Max disputes (default: 25, max: 100)',
                        'deposit_limit' => 'Max deposits (default: 25, max: 100)'
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
    ];
}

// Set response headers
setJsonHeaders();

$params = getRequestParams();
$action = (string) ($params['action'] ?? '');

if ($action === '') {
    sendJsonResponse(apiDocumentation());
}

if ($action === 'config') {
    sendJsonResponse([
        'success' => true,
        'data' => [
            'sdk_status' => getSdkConfigStatus(),
            'environment_validation' => validateEnvironmentConfig(),
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
                'export' => '/reports.php?action=export&format={json|csv|xml}',
                'summary' => '/reports.php?action=summary',
                'config' => '/reports.php?action=config'
            ]
        ],
        'timestamp' => date('Y-m-d H:i:s')
    ]);
}

try {
    // Check the request before the credentials
    $run = prepareAction($action, $params);
    $result = $run(new GlobalPaymentsReportingService());

    if ($action === 'export' && in_array($result['format'], ['csv', 'xml'], true)) {
        header('Content-Type: ' . ($result['format'] === 'csv' ? 'text/csv' : 'application/xml'));
        header('Content-Disposition: attachment; filename="' . $result['filename'] . '"');
        echo $result['data'];
        exit;
    }

    sendJsonResponse($result);
} catch (\InvalidArgumentException $e) {
    handleError($e->getMessage(), 400, 'VALIDATION_ERROR');
} catch (\GlobalPayments\Api\Entities\Exceptions\ApiException $e) {
    handleError($e->getMessage(), 400, 'API_ERROR');
} catch (\Throwable $e) {
    // Log unexpected errors on the server and return a generic message
    error_log('Unexpected error in the reporting request: ' . $e->getMessage());
    handleError('An unexpected error occurred', 500, 'INTERNAL_ERROR');
}
