/**
 * Global Payments Reporting API Endpoint
 *
 * This module provides RESTful API endpoints for accessing Global Payments
 * reporting functionality including transaction search, details, settlement
 * reports, and data export capabilities.
 *
 * @category  API_Endpoint
 * @package   GlobalPayments_Reporting
 * @author    Global Payments
 * @license   MIT License
 * @link      https://github.com/globalpayments
 */

import express from 'express';
import { ApiError } from 'globalpayments-api';
import GlobalPaymentsReportingService, {
    ValidationError,
    getSdkConfigStatus,
    validateEnvironmentConfig
} from './reporting-service.js';

const router = express.Router();

/**
 * Set JSON response headers
 *
 * @param {Object} res - Express response object
 */
function setJsonHeaders(res) {
    res.setHeader('Content-Type', 'application/json');
    setCorsHeaders(res);
}

function setCorsHeaders(res) {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
}

/**
 * Send JSON response
 *
 * @param {Object} res - Express response object
 * @param {Object} data - Response data
 * @param {number} statusCode - HTTP status code
 */
function sendJsonResponse(res, data, statusCode = 200) {
    setJsonHeaders(res);
    res.status(statusCode).send(JSON.stringify(data));
}

/**
 * Handle error responses
 *
 * @param {Object} res - Express response object
 * @param {string} message - Error message
 * @param {number} statusCode - HTTP status code
 * @param {string} errorCode - Error code
 */
function handleError(res, message, statusCode = 400, errorCode = 'API_ERROR') {
    sendJsonResponse(res, {
        success: false,
        error: {
            code: errorCode,
            message: message,
            timestamp: new Date().toISOString()
        }
    }, statusCode);
}

/**
 * Get request parameters (query string merged with a JSON or form body)
 *
 * @param {Object} req - Express request object
 * @returns {Object} Combined request parameters as strings
 */
function getRequestParams(req) {
    const params = {};
    for (const [key, value] of Object.entries({ ...req.query, ...(req.body || {}) })) {
        const first = Array.isArray(value) ? value[0] : value;
        if (first !== undefined && first !== null && typeof first !== 'object') {
            params[key] = String(first);
        }
    }
    return params;
}

/**
 * Validate required parameters
 *
 * @param {Object} params - Request parameters
 * @param {Array} required - Required parameter names
 * @throws {ValidationError} If required parameters are missing
 */
function validateRequiredParams(params, required) {
    for (const param of required) {
        if (!params[param]) {
            throw new ValidationError(`Missing required parameter: ${param}`);
        }
    }
}

/**
 * Validate YYYY-MM-DD start and end dates when they are given
 *
 * @param {Object} params - Request parameters
 * @throws {ValidationError} If a date is not a real YYYY-MM-DD date
 */
function validateDates(params) {
    for (const name of ['start_date', 'end_date']) {
        const value = params[name];
        if (!value) {
            continue;
        }
        const date = new Date(`${value}T00:00:00Z`);
        if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || isNaN(date) || date.toISOString().slice(0, 10) !== value) {
            throw new ValidationError(`Invalid ${name} format. Use YYYY-MM-DD.`);
        }
    }
}

/**
 * Copy the named parameters that are set
 */
function pick(params, names) {
    const out = {};
    for (const name of names) {
        if (params[name]) {
            out[name] = params[name];
        }
    }
    return out;
}

/**
 * Read a positive number, falling back to the default and capping it
 */
function limit(params, name, defaultValue, max) {
    const value = parseInt(params[name], 10);
    return Math.min(value > 0 ? value : defaultValue, max);
}

function page(params) {
    const value = parseInt(params.page, 10);
    return value > 0 ? value : 1;
}

const searchFilters = ['start_date', 'end_date', 'transaction_id', 'payment_type', 'status', 'amount_min', 'amount_max', 'card_last_four'];

// The reporting service is created on first use, so the docs and the config
// check answer before the credentials are set
let reportingService;
function getReportingService() {
    if (!reportingService) {
        reportingService = new GlobalPaymentsReportingService();
    }
    return reportingService;
}

/**
 * Check the request for an action and return a function that runs it
 *
 * @param {string} action - Report action
 * @param {Object} params - Request parameters
 * @returns {Function} Runs the action with the reporting service
 */
function prepareAction(action, params) {
    switch (action) {
        case 'search': {
            const filters = pick(params, searchFilters);
            filters.page = page(params);
            filters.page_size = limit(params, 'page_size', 10, 100);
            validateDates(filters);
            return service => service.searchTransactions(filters);
        }

        case 'detail':
            validateRequiredParams(params, ['transaction_id']);
            return service => service.getTransactionDetails(params.transaction_id);

        case 'settlement': {
            const filters = pick(params, ['start_date', 'end_date']);
            filters.page = page(params);
            filters.page_size = limit(params, 'page_size', 50, 100);
            validateDates(filters);
            return service => service.getSettlementReport(filters);
        }

        case 'export': {
            const filters = pick(params, searchFilters);
            const format = params.format || 'json';
            if (!['json', 'csv', 'xml'].includes(format)) {
                throw new ValidationError('Invalid format. Supported formats: json, csv, xml');
            }
            validateDates(filters);
            return service => service.exportTransactions(filters, format);
        }

        case 'summary': {
            const filters = pick(params, ['start_date', 'end_date']);
            validateDates(filters);
            return service => service.getSummaryStats(filters);
        }

        case 'disputes': {
            const filters = pick(params, ['start_date', 'end_date', 'stage', 'status']);
            filters.page = page(params);
            filters.page_size = limit(params, 'page_size', 10, 100);
            validateDates(filters);
            return service => service.getDisputeReport(filters);
        }

        case 'dispute_detail':
            validateRequiredParams(params, ['dispute_id']);
            return service => service.getDisputeDetails(params.dispute_id);

        case 'deposits': {
            const filters = pick(params, ['start_date', 'end_date', 'deposit_id', 'status']);
            filters.page = page(params);
            filters.page_size = limit(params, 'page_size', 10, 100);
            validateDates(filters);
            return service => service.getDepositReport(filters);
        }

        case 'deposit_detail':
            validateRequiredParams(params, ['deposit_id']);
            return service => service.getDepositDetails(params.deposit_id);

        case 'batches': {
            const filters = pick(params, ['start_date', 'end_date']);
            validateDates(filters);
            return service => service.getBatchReport(filters);
        }

        case 'declines': {
            const filters = pick(params, ['start_date', 'end_date', 'payment_type', 'amount_min', 'amount_max', 'card_last_four']);
            filters.page = page(params);
            filters.page_size = limit(params, 'page_size', 10, 100);
            validateDates(filters);
            return service => service.getDeclinedTransactionsReport(filters);
        }

        case 'date_range': {
            const filters = pick(params, ['start_date', 'end_date']);
            filters.transaction_limit = limit(params, 'transaction_limit', 100, 1000);
            filters.settlement_limit = limit(params, 'settlement_limit', 50, 500);
            filters.dispute_limit = limit(params, 'dispute_limit', 25, 100);
            filters.deposit_limit = limit(params, 'deposit_limit', 25, 100);
            validateDates(filters);
            return service => service.getDateRangeReport(filters);
        }

        default:
            throw new ValidationError(`Invalid action: ${action}`);
    }
}

// Handle OPTIONS requests for CORS
router.options('*', (req, res) => {
    setJsonHeaders(res);
    res.status(200).end();
});

/**
 * Reporting endpoint
 * GET/POST /api/reports?action=search (or /api/reports/search)
 */
router.all(['/', '/:action'], async (req, res) => {
    const params = getRequestParams(req);
    const action = params.action || (req.params.action || '').replace(/-/g, '_');

    if (action === '') {
        sendJsonResponse(res, apiDocumentation());
        return;
    }

    if (action === 'config') {
        sendJsonResponse(res, {
            success: true,
            data: {
                sdk_status: getSdkConfigStatus(),
                environment_validation: validateEnvironmentConfig(),
                api_endpoints: {
                    search: '/api/reports?action=search',
                    detail: '/api/reports?action=detail&transaction_id={id}',
                    settlement: '/api/reports?action=settlement',
                    disputes: '/api/reports?action=disputes',
                    dispute_detail: '/api/reports?action=dispute_detail&dispute_id={id}',
                    deposits: '/api/reports?action=deposits',
                    deposit_detail: '/api/reports?action=deposit_detail&deposit_id={id}',
                    batches: '/api/reports?action=batches',
                    declines: '/api/reports?action=declines',
                    date_range: '/api/reports?action=date_range',
                    export: '/api/reports?action=export&format={json|csv|xml}',
                    summary: '/api/reports?action=summary',
                    config: '/api/reports?action=config'
                }
            },
            timestamp: new Date().toISOString()
        });
        return;
    }

    try {
        // Check the request before the credentials
        const run = prepareAction(action, params);
        const result = await run(getReportingService());

        if (action === 'export' && (result.format === 'csv' || result.format === 'xml')) {
            setCorsHeaders(res);
            res.setHeader('Content-Type', result.format === 'csv' ? 'text/csv' : 'application/xml');
            res.setHeader('Content-Disposition', `attachment; filename="${result.filename}"`);
            res.send(result.data);
            return;
        }

        sendJsonResponse(res, result);
    } catch (error) {
        if (error instanceof ValidationError) {
            handleError(res, error.message, 400, 'VALIDATION_ERROR');
        } else if (error instanceof ApiError) {
            handleError(res, error.message, 400, 'API_ERROR');
        } else {
            // Log unexpected errors on the server and return a generic message
            console.error('Unexpected error in the reporting request:', error);
            handleError(res, 'An unexpected error occurred', 500, 'INTERNAL_ERROR');
        }
    }
});

/**
 * The answer to /api/reports without an action
 */
function apiDocumentation() {
    const dates = {
        start_date: 'Start date (YYYY-MM-DD)',
        end_date: 'End date (YYYY-MM-DD)'
    };

    return {
        success: true,
        data: {
            name: 'Global Payments Reporting API',
            version: '1.0.0',
            description: 'RESTful API for Global Payments transaction reporting and analytics',
            endpoints: {
                search: {
                    url: '/api/reports?action=search',
                    method: 'GET/POST',
                    description: 'Search transactions with filters and pagination',
                    parameters: {
                        page: 'Page number (default: 1)',
                        page_size: 'Results per page (default: 10, max: 100)',
                        ...dates,
                        transaction_id: 'Specific transaction ID',
                        payment_type: 'Payment type (sale, refund)',
                        status: 'Transaction status',
                        amount_min: 'Minimum amount',
                        amount_max: 'Maximum amount',
                        card_last_four: 'Last 4 digits of card'
                    }
                },
                detail: {
                    url: '/api/reports?action=detail&transaction_id={id}',
                    method: 'GET',
                    description: 'Get detailed transaction information',
                    parameters: {
                        transaction_id: 'Transaction ID (required)'
                    }
                },
                settlement: {
                    url: '/api/reports?action=settlement',
                    method: 'GET/POST',
                    description: 'Get settlement report',
                    parameters: {
                        page: 'Page number (default: 1)',
                        page_size: 'Results per page (default: 50, max: 100)',
                        ...dates
                    }
                },
                export: {
                    url: '/api/reports?action=export&format={json|csv|xml}',
                    method: 'GET/POST',
                    description: 'Export transaction data',
                    parameters: {
                        format: 'Export format (json, csv, or xml)',
                        '...filters': 'Same filters as search endpoint'
                    }
                },
                summary: {
                    url: '/api/reports?action=summary',
                    method: 'GET/POST',
                    description: 'Get summary statistics',
                    parameters: { ...dates }
                },
                disputes: {
                    url: '/api/reports?action=disputes',
                    method: 'GET/POST',
                    description: 'Get dispute report',
                    parameters: {
                        page: 'Page number (default: 1)',
                        page_size: 'Results per page (default: 10, max: 100)',
                        ...dates,
                        stage: 'Dispute stage',
                        status: 'Dispute status'
                    }
                },
                deposits: {
                    url: '/api/reports?action=deposits',
                    method: 'GET/POST',
                    description: 'Get deposit report',
                    parameters: {
                        page: 'Page number (default: 1)',
                        page_size: 'Results per page (default: 10, max: 100)',
                        ...dates,
                        deposit_id: 'Specific deposit ID',
                        status: 'Deposit status'
                    }
                },
                batches: {
                    url: '/api/reports?action=batches',
                    method: 'GET/POST',
                    description: 'Get batch report',
                    parameters: { ...dates }
                },
                declines: {
                    url: '/api/reports?action=declines',
                    method: 'GET/POST',
                    description: 'Get declined transactions report',
                    parameters: {
                        page: 'Page number (default: 1)',
                        page_size: 'Results per page (default: 10, max: 100)',
                        ...dates,
                        payment_type: 'Payment type',
                        amount_min: 'Minimum amount',
                        amount_max: 'Maximum amount',
                        card_last_four: 'Last 4 digits of card'
                    }
                },
                date_range: {
                    url: '/api/reports?action=date_range',
                    method: 'GET/POST',
                    description: 'Get comprehensive date range report',
                    parameters: {
                        ...dates,
                        transaction_limit: 'Max transactions (default: 100, max: 1000)',
                        settlement_limit: 'Max settlements (default: 50, max: 500)',
                        dispute_limit: 'Max disputes (default: 25, max: 100)',
                        deposit_limit: 'Max deposits (default: 25, max: 100)'
                    }
                },
                config: {
                    url: '/api/reports?action=config',
                    method: 'GET',
                    description: 'Get API configuration and status'
                }
            }
        },
        timestamp: new Date().toISOString()
    };
}

export default router;
