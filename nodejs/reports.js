/**
 * Global Payments Reporting API Endpoint
 *
 * This module provides RESTful API endpoints for accessing Global Payments
 * reporting functionality including transaction search, details, settlement
 * reports, and data export capabilities.
 *
 * Node.js version 14 or higher
 *
 * @category  API_Endpoint
 * @package   GlobalPayments_Reporting
 * @author    Global Payments
 * @license   MIT License
 * @link      https://github.com/globalpayments
 */

import express from 'express';
import GlobalPaymentsReportingService from './reporting-service.js';

const router = express.Router();

/**
 * Set JSON response headers
 *
 * @param {Object} res - Express response object
 */
function setJsonHeaders(res) {
    res.setHeader('Content-Type', 'application/json');
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
    res.status(statusCode).json(data);
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
 * Get request parameters (supports both GET and POST)
 *
 * @param {Object} req - Express request object
 * @returns {Object} Combined request parameters
 */
function getRequestParams(req) {
    // Merge query params and body params
    return { ...req.query, ...req.body };
}

/**
 * Validate required parameters
 *
 * @param {Object} params - Request parameters
 * @param {Array} required - Required parameter names
 * @throws {Error} If required parameters are missing
 */
function validateRequiredParams(params, required) {
    for (const param of required) {
        if (!params[param] || params[param] === '') {
            throw new Error(`Missing required parameter: ${param}`);
        }
    }
}

/**
 * Validate date format
 *
 * @param {string} dateString - Date string
 * @param {string} format - Expected date format (default: YYYY-MM-DD)
 * @returns {boolean} True if valid
 */
function validateDateFormat(dateString, format = 'YYYY-MM-DD') {
    // Simple validation for YYYY-MM-DD format
    const regex = /^\d{4}-\d{2}-\d{2}$/;
    if (!regex.test(dateString)) {
        return false;
    }

    const date = new Date(dateString);
    return date instanceof Date && !isNaN(date);
}

// Initialize the reporting service
let reportingService;
try {
    reportingService = new GlobalPaymentsReportingService();
} catch (error) {
    console.error('Failed to initialize reporting service:', error.message);
}

// Handle OPTIONS requests for CORS
router.options('*', (req, res) => {
    setJsonHeaders(res);
    res.status(200).end();
});

/**
 * Search transactions endpoint
 * GET/POST /api/reports?action=search
 */
router.all('/', async (req, res) => {
    try {
        const params = getRequestParams(req);
        const action = params.action || '';

        // Route requests based on action parameter
        switch (action) {
            case 'search': {
                // Search transactions
                const filters = {
                    page: parseInt(params.page) || 1,
                    page_size: Math.min(parseInt(params.page_size) || 10, 100), // Limit page size
                    start_date: params.start_date || '',
                    end_date: params.end_date || '',
                    transaction_id: params.transaction_id || '',
                    payment_type: params.payment_type || '',
                    status: params.status || '',
                    amount_min: params.amount_min || '',
                    amount_max: params.amount_max || '',
                    card_last_four: params.card_last_four || ''
                };

                // Validate date formats if provided
                if (filters.start_date && !validateDateFormat(filters.start_date)) {
                    throw new Error('Invalid start_date format. Use YYYY-MM-DD.');
                }

                if (filters.end_date && !validateDateFormat(filters.end_date)) {
                    throw new Error('Invalid end_date format. Use YYYY-MM-DD.');
                }

                // Remove empty filters
                Object.keys(filters).forEach(key => {
                    if (filters[key] === '' || filters[key] === null) {
                        delete filters[key];
                    }
                });

                const result = await reportingService.searchTransactions(filters);
                sendJsonResponse(res, result);
                break;
            }

            case 'detail': {
                // Get transaction details
                validateRequiredParams(params, ['transaction_id']);

                const result = await reportingService.getTransactionDetails(params.transaction_id);
                sendJsonResponse(res, result);
                break;
            }

            case 'settlement': {
                // Get settlement report
                const settlementParams = {
                    page: parseInt(params.page) || 1,
                    page_size: Math.min(parseInt(params.page_size) || 50, 100),
                    start_date: params.start_date || '',
                    end_date: params.end_date || ''
                };

                // Validate date formats if provided
                if (settlementParams.start_date && !validateDateFormat(settlementParams.start_date)) {
                    throw new Error('Invalid start_date format. Use YYYY-MM-DD.');
                }

                if (settlementParams.end_date && !validateDateFormat(settlementParams.end_date)) {
                    throw new Error('Invalid end_date format. Use YYYY-MM-DD.');
                }

                // Remove empty filters
                Object.keys(settlementParams).forEach(key => {
                    if (settlementParams[key] === '' || settlementParams[key] === null) {
                        delete settlementParams[key];
                    }
                });

                const result = await reportingService.getSettlementReport(settlementParams);
                sendJsonResponse(res, result);
                break;
            }

            case 'export': {
                // Export transaction data
                const exportFilters = {
                    start_date: params.start_date || '',
                    end_date: params.end_date || '',
                    transaction_id: params.transaction_id || '',
                    payment_type: params.payment_type || '',
                    status: params.status || '',
                    amount_min: params.amount_min || '',
                    amount_max: params.amount_max || '',
                    card_last_four: params.card_last_four || ''
                };

                const format = params.format || 'json';
                if (!['json', 'csv', 'xml'].includes(format)) {
                    throw new Error('Invalid format. Supported formats: json, csv, xml');
                }

                // Validate date formats if provided
                if (exportFilters.start_date && !validateDateFormat(exportFilters.start_date)) {
                    throw new Error('Invalid start_date format. Use YYYY-MM-DD.');
                }

                if (exportFilters.end_date && !validateDateFormat(exportFilters.end_date)) {
                    throw new Error('Invalid end_date format. Use YYYY-MM-DD.');
                }

                // Remove empty filters
                Object.keys(exportFilters).forEach(key => {
                    if (exportFilters[key] === '' || exportFilters[key] === null) {
                        delete exportFilters[key];
                    }
                });

                const result = await reportingService.exportTransactions(exportFilters, format);

                if (format === 'csv') {
                    res.setHeader('Content-Type', 'text/csv');
                    res.setHeader('Content-Disposition', `attachment; filename="${result.filename || 'transactions.csv'}"`);
                    res.send(result.data);
                    return;
                }

                if (format === 'xml') {
                    res.setHeader('Content-Type', 'application/xml');
                    res.setHeader('Content-Disposition', `attachment; filename="${result.filename || 'transactions.xml'}"`);
                    res.send(result.data);
                    return;
                }

                sendJsonResponse(res, result);
                break;
            }

            case 'summary': {
                // Get summary statistics
                const summaryParams = {
                    start_date: params.start_date || '',
                    end_date: params.end_date || ''
                };

                // Validate date formats if provided
                if (summaryParams.start_date && !validateDateFormat(summaryParams.start_date)) {
                    throw new Error('Invalid start_date format. Use YYYY-MM-DD.');
                }

                if (summaryParams.end_date && !validateDateFormat(summaryParams.end_date)) {
                    throw new Error('Invalid end_date format. Use YYYY-MM-DD.');
                }

                // Remove empty filters
                Object.keys(summaryParams).forEach(key => {
                    if (summaryParams[key] === '' || summaryParams[key] === null) {
                        delete summaryParams[key];
                    }
                });

                const result = await reportingService.getSummaryStats(summaryParams);
                sendJsonResponse(res, result);
                break;
            }

            case 'disputes': {
                // Get dispute report
                const disputeFilters = {
                    page: parseInt(params.page) || 1,
                    page_size: Math.min(parseInt(params.page_size) || 10, 100),
                    start_date: params.start_date || '',
                    end_date: params.end_date || '',
                    stage: params.stage || '',
                    status: params.status || ''
                };

                // Validate date formats if provided
                if (disputeFilters.start_date && !validateDateFormat(disputeFilters.start_date)) {
                    throw new Error('Invalid start_date format. Use YYYY-MM-DD.');
                }

                if (disputeFilters.end_date && !validateDateFormat(disputeFilters.end_date)) {
                    throw new Error('Invalid end_date format. Use YYYY-MM-DD.');
                }

                // Remove empty filters
                Object.keys(disputeFilters).forEach(key => {
                    if (disputeFilters[key] === '' || disputeFilters[key] === null) {
                        delete disputeFilters[key];
                    }
                });

                const result = await reportingService.getDisputeReport(disputeFilters);
                sendJsonResponse(res, result);
                break;
            }

            case 'dispute_detail': {
                // Get dispute details
                validateRequiredParams(params, ['dispute_id']);

                const result = await reportingService.getDisputeDetails(params.dispute_id);
                sendJsonResponse(res, result);
                break;
            }

            case 'deposits': {
                // Get deposit report
                const depositFilters = {
                    page: parseInt(params.page) || 1,
                    page_size: Math.min(parseInt(params.page_size) || 10, 100),
                    start_date: params.start_date || '',
                    end_date: params.end_date || '',
                    deposit_id: params.deposit_id || '',
                    status: params.status || ''
                };

                // Validate date formats if provided
                if (depositFilters.start_date && !validateDateFormat(depositFilters.start_date)) {
                    throw new Error('Invalid start_date format. Use YYYY-MM-DD.');
                }

                if (depositFilters.end_date && !validateDateFormat(depositFilters.end_date)) {
                    throw new Error('Invalid end_date format. Use YYYY-MM-DD.');
                }

                // Remove empty filters
                Object.keys(depositFilters).forEach(key => {
                    if (depositFilters[key] === '' || depositFilters[key] === null) {
                        delete depositFilters[key];
                    }
                });

                const result = await reportingService.getDepositReport(depositFilters);
                sendJsonResponse(res, result);
                break;
            }

            case 'deposit_detail': {
                // Get deposit details
                validateRequiredParams(params, ['deposit_id']);

                const result = await reportingService.getDepositDetails(params.deposit_id);
                sendJsonResponse(res, result);
                break;
            }

            case 'batches': {
                // Get batch report
                const batchFilters = {
                    start_date: params.start_date || '',
                    end_date: params.end_date || ''
                };

                // Validate date formats if provided
                if (batchFilters.start_date && !validateDateFormat(batchFilters.start_date)) {
                    throw new Error('Invalid start_date format. Use YYYY-MM-DD.');
                }

                if (batchFilters.end_date && !validateDateFormat(batchFilters.end_date)) {
                    throw new Error('Invalid end_date format. Use YYYY-MM-DD.');
                }

                // Remove empty filters
                Object.keys(batchFilters).forEach(key => {
                    if (batchFilters[key] === '' || batchFilters[key] === null) {
                        delete batchFilters[key];
                    }
                });

                const result = await reportingService.getBatchReport(batchFilters);
                sendJsonResponse(res, result);
                break;
            }

            case 'declines': {
                // Get declined transactions report
                const declineFilters = {
                    page: parseInt(params.page) || 1,
                    page_size: Math.min(parseInt(params.page_size) || 10, 100),
                    start_date: params.start_date || '',
                    end_date: params.end_date || '',
                    payment_type: params.payment_type || '',
                    amount_min: params.amount_min || '',
                    amount_max: params.amount_max || '',
                    card_last_four: params.card_last_four || ''
                };

                // Validate date formats if provided
                if (declineFilters.start_date && !validateDateFormat(declineFilters.start_date)) {
                    throw new Error('Invalid start_date format. Use YYYY-MM-DD.');
                }

                if (declineFilters.end_date && !validateDateFormat(declineFilters.end_date)) {
                    throw new Error('Invalid end_date format. Use YYYY-MM-DD.');
                }

                // Remove empty filters
                Object.keys(declineFilters).forEach(key => {
                    if (declineFilters[key] === '' || declineFilters[key] === null) {
                        delete declineFilters[key];
                    }
                });

                const result = await reportingService.getDeclinedTransactionsReport(declineFilters);
                sendJsonResponse(res, result);
                break;
            }

            case 'date_range': {
                // Get comprehensive date range report
                const dateRangeParams = {
                    start_date: params.start_date || '',
                    end_date: params.end_date || '',
                    transaction_limit: Math.min(parseInt(params.transaction_limit) || 100, 1000),
                    settlement_limit: Math.min(parseInt(params.settlement_limit) || 50, 500),
                    dispute_limit: Math.min(parseInt(params.dispute_limit) || 25, 100),
                    deposit_limit: Math.min(parseInt(params.deposit_limit) || 25, 100)
                };

                // Validate date formats if provided
                if (dateRangeParams.start_date && !validateDateFormat(dateRangeParams.start_date)) {
                    throw new Error('Invalid start_date format. Use YYYY-MM-DD.');
                }

                if (dateRangeParams.end_date && !validateDateFormat(dateRangeParams.end_date)) {
                    throw new Error('Invalid end_date format. Use YYYY-MM-DD.');
                }

                // Remove empty filters
                Object.keys(dateRangeParams).forEach(key => {
                    if (dateRangeParams[key] === '' || dateRangeParams[key] === null) {
                        delete dateRangeParams[key];
                    }
                });

                const result = await reportingService.getDateRangeReport(dateRangeParams);
                sendJsonResponse(res, result);
                break;
            }

            case 'config': {
                // Get configuration status
                const configStatus = reportingService.getSdkConfigStatus();
                const envValidation = reportingService.validateEnvironmentConfig();

                sendJsonResponse(res, {
                    success: true,
                    data: {
                        sdk_status: configStatus,
                        environment_validation: envValidation,
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
                break;
            }

            case '':
            default: {
                // Default action - show API documentation
                sendJsonResponse(res, {
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
                                    start_date: 'Start date (YYYY-MM-DD)',
                                    end_date: 'End date (YYYY-MM-DD)',
                                    transaction_id: 'Specific transaction ID',
                                    payment_type: 'Payment type (sale, refund, authorize, capture)',
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
                                    start_date: 'Start date (YYYY-MM-DD)',
                                    end_date: 'End date (YYYY-MM-DD)'
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
                                parameters: {
                                    start_date: 'Start date (YYYY-MM-DD)',
                                    end_date: 'End date (YYYY-MM-DD)'
                                }
                            },
                            disputes: {
                                url: '/api/reports?action=disputes',
                                method: 'GET/POST',
                                description: 'Get dispute report',
                                parameters: {
                                    page: 'Page number (default: 1)',
                                    page_size: 'Results per page (default: 10, max: 100)',
                                    start_date: 'Start date (YYYY-MM-DD)',
                                    end_date: 'End date (YYYY-MM-DD)',
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
                                    start_date: 'Start date (YYYY-MM-DD)',
                                    end_date: 'End date (YYYY-MM-DD)',
                                    deposit_id: 'Specific deposit ID',
                                    status: 'Deposit status'
                                }
                            },
                            batches: {
                                url: '/api/reports?action=batches',
                                method: 'GET/POST',
                                description: 'Get batch report',
                                parameters: {
                                    start_date: 'Start date (YYYY-MM-DD)',
                                    end_date: 'End date (YYYY-MM-DD)'
                                }
                            },
                            declines: {
                                url: '/api/reports?action=declines',
                                method: 'GET/POST',
                                description: 'Get declined transactions report',
                                parameters: {
                                    page: 'Page number (default: 1)',
                                    page_size: 'Results per page (default: 10, max: 100)',
                                    start_date: 'Start date (YYYY-MM-DD)',
                                    end_date: 'End date (YYYY-MM-DD)',
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
                                    start_date: 'Start date (YYYY-MM-DD)',
                                    end_date: 'End date (YYYY-MM-DD)',
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
                });
                break;
            }
        }

    } catch (error) {
        if (error.message.includes('Missing required parameter') || error.message.includes('Invalid')) {
            handleError(res, error.message, 400, 'VALIDATION_ERROR');
        } else if (error.name === 'ApiError') {
            handleError(res, error.message, 400, 'API_ERROR');
        } else {
            handleError(res, `An unexpected error occurred: ${error.message}`, 500, 'INTERNAL_ERROR');
        }
    }
});

export default router;