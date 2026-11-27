/**
 * Global Payments Reporting Service
 *
 * This service class provides comprehensive reporting functionality for
 * Global Payments transactions including search, filtering, and data export,
 * read from GP API with the Global Payments Node.js SDK.
 *
 * @category  Reporting
 * @package   GlobalPayments_Reporting
 * @author    Global Payments
 * @license   MIT License
 * @link      https://github.com/globalpayments
 */

import * as dotenv from 'dotenv';
import {
    ServicesContainer,
    GpApiConfig,
    GpApiMapping,
    ReportingService,
    SearchCriteria,
    DataServiceCriteria,
    PaymentType,
    Environment,
    Channel,
    ApiError
} from 'globalpayments-api';

// Load environment variables
dotenv.config();

/**
 * A problem with the request itself, answered with VALIDATION_ERROR
 */
export class ValidationError extends Error {
    constructor(message) {
        super(message);
        this.name = 'ValidationError';
    }
}

/**
 * Wrap an SDK error with the report that failed. Anything that is not an SDK
 * error is passed on as it is, so it is not reported as a gateway error.
 */
function reportError(prefix, error) {
    if (error instanceof ApiError) {
        return new ApiError(`${prefix}: ${error.message}`);
    }
    return error;
}

/**
 * Dates come in as YYYY-MM-DD. The SDK writes the transaction, settlement and
 * deposit dates with toISOString() (UTC) and the dispute dates with the local
 * date, so each gets a Date that reads as the same day there.
 */
function utcDate(value) {
    return new Date(`${value}T00:00:00Z`);
}

function localDate(value) {
    const [year, month, day] = value.split('-').map(Number);
    return new Date(year, month - 1, day);
}

/** SDK dates as ISO strings, as GP API sends them */
function isoString(value) {
    if (!value) {
        return '';
    }
    const date = value instanceof Date ? value : new Date(value);
    return isNaN(date) ? String(value) : date.toISOString();
}

/** The SDK returns amounts as strings in major units, e.g. "19.99" */
function amount(value) {
    const number = parseFloat(value);
    return isNaN(number) ? 0 : number;
}

function nowString() {
    return new Date().toISOString();
}

function sum(rows, field) {
    return rows.reduce((total, row) => total + (row[field] || 0), 0);
}

/**
 * Global Payments Reporting Service Class
 *
 * Provides methods for transaction reporting, searching, and data export
 * using the Global Payments SDK reporting capabilities.
 */
class GlobalPaymentsReportingService {
    /**
     * Constructor - Initialize and configure the SDK
     *
     * @throws {ValidationError} If the GP API credentials are missing
     */
    constructor() {
        this.configureGpApiSdk();
    }

    /**
     * Configure the SDK for GP-API with reporting capabilities
     *
     * @throws {ValidationError} If required environment variables are missing
     */
    configureGpApiSdk() {
        for (const varName of ['GP_API_APP_ID', 'GP_API_APP_KEY']) {
            if (!process.env[varName]) {
                throw new ValidationError(`Failed to configure SDK: Missing required environment variable: ${varName}`);
            }
        }

        const config = new GpApiConfig();

        // Set GP-API credentials
        config.appId = process.env.GP_API_APP_ID;
        config.appKey = process.env.GP_API_APP_KEY;

        // Configure environment (sandbox for development)
        config.environment = Environment.Test; // Change to Environment.Production for live

        // Set channel for ecommerce transactions
        config.channel = Channel.CardNotPresent;

        // The reports use the default config. The Portico payment stub in
        // server.js has its own named config, so it does not replace this one.
        ServicesContainer.configureService(config);
    }

    /**
     * Search transactions with filters and pagination
     *
     * @param {Object} filters - Search filters and pagination parameters
     * @returns {Promise<Object>} Transaction search results
     * @throws {ApiError} If the search request fails
     */
    async searchTransactions(filters = {}) {
        const page = filters.page || 1;
        const pageSize = filters.page_size || 10;

        const reportBuilder = ReportingService.findTransactionsPaged(page, pageSize);

        // Dates and filters go through where(); withStartDate() is not read by the GP API request
        if (filters.start_date) {
            reportBuilder.where(SearchCriteria.StartDate, utcDate(filters.start_date));
        }
        if (filters.end_date) {
            reportBuilder.where(SearchCriteria.EndDate, utcDate(filters.end_date));
        }
        if (filters.transaction_id) {
            reportBuilder.withTransactionId(filters.transaction_id);
        }
        if (filters.payment_type) {
            const paymentType = { sale: PaymentType.SALE, refund: PaymentType.REFUND }[filters.payment_type.toLowerCase()];
            if (!paymentType) {
                throw new ValidationError('Invalid payment_type. Supported values: sale, refund');
            }
            reportBuilder.where(SearchCriteria.PaymentType, paymentType);
        }
        if (filters.card_last_four) {
            reportBuilder.where(SearchCriteria.CardNumberLastFour, filters.card_last_four);
        }

        let response;
        try {
            response = await reportBuilder.execute();
        } catch (error) {
            throw reportError('Transaction search failed', error);
        }

        let transactions = this.formatTransactionList(response.result || []);

        // GP API only filters on an exact amount, so the range is applied here
        const min = parseFloat(filters.amount_min);
        if (!isNaN(min)) {
            transactions = transactions.filter(transaction => transaction.amount >= min);
        }
        const max = parseFloat(filters.amount_max);
        if (!isNaN(max)) {
            transactions = transactions.filter(transaction => transaction.amount <= max);
        }

        // Status is not a search filter in this sample, it is applied to the page
        if (filters.status) {
            const statusFilter = filters.status.toUpperCase();
            transactions = transactions.filter(
                transaction => transaction.status.toUpperCase() === statusFilter
            );
        }

        return {
            success: true,
            data: {
                transactions: transactions,
                pagination: {
                    page: page,
                    page_size: pageSize,
                    total_count: transactions.length,
                    original_total_count: response.totalRecordCount || 0
                }
            },
            timestamp: nowString()
        };
    }

    /**
     * Get detailed information for a specific transaction
     *
     * @param {string} transactionId - The transaction ID to retrieve
     * @returns {Promise<Object>} Transaction details
     * @throws {ApiError} If the transaction cannot be found
     */
    async getTransactionDetails(transactionId) {
        // The SDK's transaction mapping leaves out the gateway result code, so the
        // transaction is read through the SDK's GP API connector and mapped with its own mapping
        let raw;
        try {
            raw = await this.gpApiGet(`/transactions/${encodeURIComponent(transactionId)}`);
        } catch (error) {
            throw reportError('Failed to retrieve transaction details', error);
        }

        const details = this.formatTransactionDetails(GpApiMapping.mapTransactionSummary(raw));
        details.gateway_response_code = (raw.payment_method && raw.payment_method.result) || '';

        return {
            success: true,
            data: details,
            timestamp: nowString()
        };
    }

    /**
     * Generate settlement report for a date range
     *
     * @param {Object} params - Report parameters
     * @returns {Promise<Object>} Settlement report data
     * @throws {ApiError} If the report request fails
     */
    async getSettlementReport(params = {}) {
        const page = params.page || 1;
        const pageSize = params.page_size || 50;

        const reportBuilder = ReportingService.findSettlementTransactionsPaged(page, pageSize);
        if (params.start_date) {
            reportBuilder.where(SearchCriteria.StartDate, utcDate(params.start_date));
        }
        if (params.end_date) {
            reportBuilder.where(SearchCriteria.EndDate, utcDate(params.end_date));
        }

        let response;
        try {
            response = await reportBuilder.execute();
        } catch (error) {
            throw reportError('Settlement report generation failed', error);
        }

        const settlements = this.formatSettlementList(response.result || []);

        return {
            success: true,
            data: {
                settlements: settlements,
                summary: this.generateSettlementSummary(settlements),
                pagination: {
                    page: page,
                    page_size: pageSize,
                    total_count: response.totalRecordCount || 0
                }
            },
            timestamp: nowString()
        };
    }

    /**
     * Get dispute report with filtering and pagination
     *
     * @param {Object} filters - Dispute filters and pagination
     * @returns {Promise<Object>} Dispute report data
     * @throws {ApiError} If the report request fails
     */
    async getDisputeReport(filters = {}) {
        const page = filters.page || 1;
        const pageSize = filters.page_size || 10;

        // GP API filters disputes on the stage date (from_stage_time_created / to_stage_time_created)
        const reportBuilder = ReportingService.findDisputesPaged(page, pageSize);
        if (filters.start_date) {
            reportBuilder.where(DataServiceCriteria.StartStageDate, localDate(filters.start_date));
        }
        if (filters.end_date) {
            reportBuilder.where(DataServiceCriteria.EndStageDate, localDate(filters.end_date));
        }
        if (filters.stage) {
            reportBuilder.where(SearchCriteria.DisputeStage, filters.stage.toUpperCase());
        }
        if (filters.status) {
            reportBuilder.where(SearchCriteria.DisputeStatus, filters.status.toUpperCase());
        }

        let response;
        try {
            response = await reportBuilder.execute();
        } catch (error) {
            throw reportError('Dispute report generation failed', error);
        }

        return {
            success: true,
            data: {
                disputes: (response.result || []).map(dispute => this.formatDispute(dispute)),
                pagination: {
                    page: page,
                    page_size: pageSize,
                    total_count: response.totalRecordCount || 0
                }
            },
            timestamp: nowString()
        };
    }

    /**
     * Get detailed information for a specific dispute
     *
     * @param {string} disputeId - The dispute ID to retrieve
     * @returns {Promise<Object>} Dispute details
     * @throws {ApiError} If the dispute cannot be found
     */
    async getDisputeDetails(disputeId) {
        let response;
        try {
            response = await ReportingService.disputeDetail(disputeId).execute();
        } catch (error) {
            throw reportError('Failed to retrieve dispute details', error);
        }

        return {
            success: true,
            data: this.formatDisputeDetails(response),
            timestamp: nowString()
        };
    }

    /**
     * Get deposit report with filtering and pagination
     *
     * @param {Object} filters - Deposit filters and pagination
     * @returns {Promise<Object>} Deposit report data
     * @throws {ApiError} If the report request fails
     */
    async getDepositReport(filters = {}) {
        const page = filters.page || 1;
        const pageSize = filters.page_size || 10;

        const reportBuilder = ReportingService.findDepositsPaged(page, pageSize);
        if (filters.start_date) {
            reportBuilder.where(SearchCriteria.StartDate, utcDate(filters.start_date));
        }
        if (filters.end_date) {
            reportBuilder.where(SearchCriteria.EndDate, utcDate(filters.end_date));
        }
        if (filters.deposit_id) {
            reportBuilder.where(SearchCriteria.DepositId, filters.deposit_id);
        }
        if (filters.status) {
            reportBuilder.where(SearchCriteria.DepositStatus, filters.status.toUpperCase());
        }

        let response;
        try {
            response = await reportBuilder.execute();
        } catch (error) {
            throw reportError('Deposit report generation failed', error);
        }

        return {
            success: true,
            data: {
                deposits: (response.result || []).map(deposit => this.formatDepositListItem(deposit)),
                pagination: {
                    page: page,
                    page_size: pageSize,
                    total_count: response.totalRecordCount || 0
                }
            },
            timestamp: nowString()
        };
    }

    /**
     * Get detailed information for a specific deposit
     *
     * @param {string} depositId - The deposit ID to retrieve
     * @returns {Promise<Object>} Deposit details
     * @throws {ApiError} If the deposit cannot be found
     */
    async getDepositDetails(depositId) {
        // The SDK's deposit mapping leaves out the bank account, so the deposit is
        // read through the SDK's GP API connector and mapped with its own mapping
        let raw;
        try {
            raw = await this.gpApiGet(`/settlement/deposits/${encodeURIComponent(depositId)}`);
        } catch (error) {
            throw reportError('Failed to retrieve deposit details', error);
        }

        const deposit = GpApiMapping.mapDepositSummary(raw);
        const bankTransfer = raw.bank_transfer || {};

        return {
            success: true,
            data: {
                ...this.formatDeposit(deposit),
                bank_account: {
                    masked_account_number: bankTransfer.masked_account_number_last4 || '',
                    bank_name: (bankTransfer.bank && bankTransfer.bank.name) || ''
                },
                transaction_summary: {
                    sales_count: deposit.salesTotalCount || 0,
                    sales_amount: amount(deposit.salesTotalAmount),
                    refunds_count: deposit.refundsTotalCount || 0,
                    refunds_amount: amount(deposit.refundsTotalAmount),
                    chargebacks_count: deposit.chargebackTotalCount || 0,
                    chargebacks_amount: amount(deposit.chargebackTotalAmount),
                    adjustments_count: deposit.adjustmentTotalCount || 0,
                    adjustments_amount: amount(deposit.adjustmentTotalAmount)
                }
            },
            timestamp: nowString()
        };
    }

    /**
     * Get batch report. GP API has no batch report endpoint, so this answers
     * with an API error, the same in every language of this sample.
     *
     * @throws {ApiError} Always
     */
    async getBatchReport() {
        throw new ApiError('Batch report generation failed: batch reports are not available through GP API');
    }

    /**
     * Get declined transactions report
     *
     * @param {Object} filters - Search filters
     * @returns {Promise<Object>} Declined transactions with an analysis
     * @throws {ApiError} If the search request fails
     */
    async getDeclinedTransactionsReport(filters = {}) {
        let result;
        try {
            result = await this.searchTransactions({ ...filters, status: 'DECLINED' });
        } catch (error) {
            throw reportError('Declined transactions report generation failed', error);
        }

        result.data.decline_analysis = this.analyzeDeclines(result.data.transactions);
        return result;
    }

    /**
     * Get comprehensive date range report across transactions, settlements,
     * disputes and deposits. A section that fails carries its error.
     *
     * @param {Object} params - Date range and report parameters
     * @returns {Promise<Object>} Comprehensive date range report
     */
    async getDateRangeReport(params = {}) {
        const startDate = params.start_date || new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];
        const endDate = params.end_date || new Date().toISOString().split('T')[0];

        const data = {
            period: {
                start_date: startDate,
                end_date: endDate
            },
            transactions: {},
            settlements: {},
            disputes: {},
            deposits: {},
            summary: {}
        };

        const sections = [
            ['transactions', 'Transactions', params.transaction_limit || 100, filters => this.searchTransactions(filters)],
            ['settlements', 'Settlements', params.settlement_limit || 50, filters => this.getSettlementReport(filters)],
            ['disputes', 'Disputes', params.dispute_limit || 25, filters => this.getDisputeReport(filters)],
            ['deposits', 'Deposits', params.deposit_limit || 25, filters => this.getDepositReport(filters)]
        ];

        for (const [key, label, limit, run] of sections) {
            try {
                const result = await run({ start_date: startDate, end_date: endDate, page_size: limit });
                data[key] = result.data;
            } catch (error) {
                if (error instanceof ApiError) {
                    data[key] = { error: `${label} not available: ${error.message}` };
                } else {
                    console.error(`${label} not available for the date range report:`, error);
                    data[key] = { error: `${label} not available` };
                }
            }
        }

        data.summary = this.generateComprehensiveSummary(data);

        return {
            success: true,
            data: data,
            timestamp: nowString()
        };
    }

    /**
     * Export transaction data in specified format
     *
     * @param {Object} filters - Search filters
     * @param {string} format - Export format ('json', 'csv', or 'xml')
     * @returns {Promise<Object>} Export data
     * @throws {ApiError} If export fails
     */
    async exportTransactions(filters = {}, format = 'json') {
        let result;
        try {
            // GP API pages hold up to 1000 transactions
            result = await this.searchTransactions({ ...filters, page_size: 1000 });
        } catch (error) {
            throw reportError('Export failed', error);
        }

        const transactions = result.data.transactions;

        if (format === 'csv') {
            return this.exportToCsv(transactions);
        }

        if (format === 'xml') {
            return this.exportToXml(transactions);
        }

        return {
            success: true,
            data: transactions,
            format: 'json',
            timestamp: nowString()
        };
    }

    /**
     * Get reporting summary statistics
     *
     * @param {Object} params - Summary parameters
     * @returns {Promise<Object>} Summary statistics
     */
    async getSummaryStats(params = {}) {
        const startDate = params.start_date || new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];
        const endDate = params.end_date || new Date().toISOString().split('T')[0];

        let result;
        try {
            result = await this.searchTransactions({
                start_date: startDate,
                end_date: endDate,
                page_size: 1000
            });
        } catch (error) {
            let message = 'Failed to generate summary statistics';
            if (error instanceof ApiError) {
                message += `: ${error.message}`;
            } else {
                console.error(message, error);
            }
            return {
                success: false,
                error: message,
                timestamp: nowString()
            };
        }

        return {
            success: true,
            data: this.calculateSummaryStats(result.data.transactions),
            period: {
                start_date: startDate,
                end_date: endDate
            },
            timestamp: nowString()
        };
    }

    /**
     * GET a GP API endpoint through the SDK's configured connector, which signs
     * in and raises the usual SDK errors
     *
     * @param {string} endpoint - GP API path, e.g. /transactions/TRN_123
     * @returns {Promise<Object>} The GP API response
     */
    async gpApiGet(endpoint) {
        // An empty body, as the SDK's own report requests send: the connector keeps
        // its headers between requests and this resets the Content-Length of the sign in
        return ServicesContainer.instance().getClient('default').doTransaction('GET', endpoint, '');
    }

    /**
     * Format transaction list for API response
     *
     * @param {Array} transactions - SDK transaction summaries
     * @returns {Array} Formatted transaction list
     */
    formatTransactionList(transactions) {
        return transactions.map(transaction => ({
            transaction_id: transaction.transactionId || '',
            timestamp: isoString(transaction.transactionDate),
            amount: amount(transaction.amount),
            currency: transaction.currency || 'USD',
            status: transaction.transactionStatus || '',
            payment_method: transaction.paymentType || '',
            card_last_four: transaction.maskedCardNumber || '',
            auth_code: transaction.authCode || '',
            reference_number: transaction.referenceNumber || ''
        }));
    }

    /**
     * Format detailed transaction information
     *
     * @param {Object} transaction - SDK transaction summary
     * @returns {Object} Formatted transaction details
     */
    formatTransactionDetails(transaction) {
        return {
            transaction_id: transaction.transactionId || '',
            timestamp: isoString(transaction.transactionDate),
            amount: amount(transaction.amount),
            currency: transaction.currency || 'USD',
            status: transaction.transactionStatus || '',
            payment_method: transaction.paymentType || '',
            card_details: {
                masked_number: transaction.maskedCardNumber || '',
                card_type: transaction.cardType || '',
                entry_mode: transaction.entryMode || ''
            },
            auth_code: transaction.authCode || '',
            reference_number: transaction.referenceNumber || '',
            gateway_response_code: transaction.gatewayResponseCode || '',
            gateway_response_message: transaction.gatewayResponseMessage || ''
        };
    }

    /**
     * Format settlement list for API response. GP API returns one settled
     * transaction per row, so each row counts one transaction.
     *
     * @param {Array} settlements - SDK transaction summaries
     * @returns {Array} Formatted settlement list
     */
    formatSettlementList(settlements) {
        return settlements.map(settlement => ({
            settlement_id: settlement.transactionId || '',
            settlement_date: isoString(settlement.depositTimeCreated || settlement.transactionDate),
            transaction_count: 1,
            total_amount: amount(settlement.amount),
            currency: settlement.currency || 'USD',
            status: settlement.transactionStatus || ''
        }));
    }

    /**
     * Generate settlement summary statistics
     *
     * @param {Array} settlements - Formatted settlements
     * @returns {Object} Settlement summary
     */
    generateSettlementSummary(settlements) {
        const totalAmount = sum(settlements, 'total_amount');

        return {
            total_settlements: settlements.length,
            total_amount: totalAmount,
            total_transactions: sum(settlements, 'transaction_count'),
            average_settlement_amount: settlements.length > 0 ? totalAmount / settlements.length : 0
        };
    }

    /**
     * Calculate summary statistics from transaction data
     *
     * @param {Array} transactions - Formatted transactions
     * @returns {Object} Summary statistics
     */
    calculateSummaryStats(transactions) {
        const totalAmount = sum(transactions, 'amount');
        const statusCounts = {};
        const paymentTypeCounts = {};

        transactions.forEach(transaction => {
            const status = transaction.status || 'unknown';
            statusCounts[status] = (statusCounts[status] || 0) + 1;

            const paymentType = transaction.payment_method || 'unknown';
            paymentTypeCounts[paymentType] = (paymentTypeCounts[paymentType] || 0) + 1;
        });

        return {
            total_transactions: transactions.length,
            total_amount: totalAmount,
            average_amount: transactions.length > 0 ? totalAmount / transactions.length : 0,
            status_breakdown: statusCounts,
            payment_type_breakdown: paymentTypeCounts
        };
    }

    /**
     * Export transaction data to CSV format
     *
     * @param {Array} transactions - Formatted transactions
     * @returns {Object} CSV export data
     */
    exportToCsv(transactions) {
        const field = value => {
            const text = String(value ?? '');
            return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
        };

        let csvData = 'Transaction ID,Timestamp,Amount,Currency,Status,Payment Method,Card Last Four,Auth Code,Reference Number\n';

        transactions.forEach(transaction => {
            csvData += [
                transaction.transaction_id,
                transaction.timestamp,
                transaction.amount.toFixed(2),
                transaction.currency,
                transaction.status,
                transaction.payment_method,
                transaction.card_last_four,
                transaction.auth_code,
                transaction.reference_number
            ].map(field).join(',') + '\n';
        });

        return {
            success: true,
            data: csvData,
            format: 'csv',
            filename: this.exportFilename('csv'),
            timestamp: nowString()
        };
    }

    /**
     * Export transaction data to XML format
     *
     * @param {Array} transactions - Formatted transactions
     * @returns {Object} XML export data
     */
    exportToXml(transactions) {
        let xmlData = '<?xml version="1.0" encoding="UTF-8"?>\n<transactions>\n';

        transactions.forEach(transaction => {
            xmlData += '  <transaction>\n';
            xmlData += `    <transaction_id>${this.escapeXml(transaction.transaction_id)}</transaction_id>\n`;
            xmlData += `    <timestamp>${this.escapeXml(transaction.timestamp)}</timestamp>\n`;
            xmlData += `    <amount>${transaction.amount}</amount>\n`;
            xmlData += `    <currency>${this.escapeXml(transaction.currency)}</currency>\n`;
            xmlData += `    <status>${this.escapeXml(transaction.status)}</status>\n`;
            xmlData += `    <payment_method>${this.escapeXml(transaction.payment_method)}</payment_method>\n`;
            xmlData += `    <card_last_four>${this.escapeXml(transaction.card_last_four)}</card_last_four>\n`;
            xmlData += `    <auth_code>${this.escapeXml(transaction.auth_code)}</auth_code>\n`;
            xmlData += `    <reference_number>${this.escapeXml(transaction.reference_number)}</reference_number>\n`;
            xmlData += '  </transaction>\n';
        });

        xmlData += '</transactions>';

        return {
            success: true,
            data: xmlData,
            format: 'xml',
            filename: this.exportFilename('xml'),
            timestamp: nowString()
        };
    }

    /**
     * Export file name such as transactions_2025-01-31_14-05-09.csv
     */
    exportFilename(extension) {
        const stamp = new Date().toISOString().slice(0, 19).replace('T', '_').replace(/:/g, '-');
        return `transactions_${stamp}.${extension}`;
    }

    /**
     * Escape XML special characters
     *
     * @param {string} str - String to escape
     * @returns {string} Escaped string
     */
    escapeXml(str) {
        return String(str ?? '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&apos;');
    }

    /**
     * Fields shared by the dispute list and the dispute detail
     *
     * @param {Object} dispute - SDK dispute summary
     * @returns {Object} Formatted dispute
     */
    formatDispute(dispute) {
        return {
            dispute_id: dispute.caseId || '',
            transaction_id: dispute.transactionId || '',
            case_number: dispute.caseNumber || '',
            dispute_stage: dispute.caseStage || '',
            dispute_status: dispute.caseStatus || '',
            case_amount: amount(dispute.caseAmount),
            currency: dispute.caseCurrency || 'USD',
            reason_code: dispute.reasonCode || '',
            reason_description: dispute.reason || '',
            case_time: isoString(dispute.caseIdTime),
            last_adjustment_time: isoString(dispute.lastAdjustmentTime)
        };
    }

    /**
     * Format detailed dispute information
     *
     * @param {Object} dispute - SDK dispute summary
     * @returns {Object} Formatted dispute details
     */
    formatDisputeDetails(dispute) {
        return {
            ...this.formatDispute(dispute),
            case_description: dispute.caseDescription || '',
            documents: (dispute.documents || []).map(document => ({
                id: document.id || '',
                type: document.type || ''
            })),
            transaction_details: {
                amount: amount(dispute.transactionAmount),
                currency: dispute.transactionCurrency || 'USD',
                masked_card_number: dispute.transactionMaskedCardNumber || '',
                arn: dispute.transactionARN || ''
            }
        };
    }

    /**
     * Fields shared by the deposit list and the deposit detail
     *
     * @param {Object} deposit - SDK deposit summary
     * @returns {Object} Formatted deposit
     */
    formatDeposit(deposit) {
        return {
            deposit_id: deposit.depositId || '',
            deposit_date: isoString(deposit.depositDate),
            deposit_reference: deposit.depositReference || '',
            deposit_status: deposit.status || '',
            deposit_amount: amount(deposit.amount),
            currency: deposit.currency || 'USD',
            merchant_number: deposit.merchantId || '',
            merchant_hierarchy: deposit.merchantHierarchy || ''
        };
    }

    /**
     * Format a deposit list row for API response
     *
     * @param {Object} deposit - SDK deposit summary
     * @returns {Object} Formatted deposit row
     */
    formatDepositListItem(deposit) {
        return {
            ...this.formatDeposit(deposit),
            sales_count: deposit.salesTotalCount || 0,
            sales_amount: amount(deposit.salesTotalAmount),
            refunds_count: deposit.refundsTotalCount || 0,
            refunds_amount: amount(deposit.refundsTotalAmount)
        };
    }

    /**
     * Analyze decline patterns from transaction data
     *
     * @param {Array} transactions - Declined transactions
     * @returns {Object} Decline analysis
     */
    analyzeDeclines(transactions) {
        const declineReasons = {};
        const cardTypes = {};
        const hourlyBreakdown = {};
        const totalAmount = sum(transactions, 'amount');

        transactions.forEach(transaction => {
            // The list rows carry no gateway message
            declineReasons.Unknown = (declineReasons.Unknown || 0) + 1;

            const cardType = transaction.payment_method || 'Unknown';
            cardTypes[cardType] = (cardTypes[cardType] || 0) + 1;

            // Hours are counted in UTC, as GP API sends the times
            const date = new Date(transaction.timestamp);
            if (!isNaN(date)) {
                const hour = date.getUTCHours().toString().padStart(2, '0');
                hourlyBreakdown[hour] = (hourlyBreakdown[hour] || 0) + 1;
            }
        });

        return {
            total_declined_transactions: transactions.length,
            total_declined_amount: totalAmount,
            average_declined_amount: transactions.length > 0 ? totalAmount / transactions.length : 0,
            decline_reasons: declineReasons,
            card_type_breakdown: cardTypes,
            hourly_breakdown: hourlyBreakdown
        };
    }

    /**
     * Generate comprehensive summary for date range report
     *
     * @param {Object} reportData - All report data
     * @returns {Object} Comprehensive summary
     */
    generateComprehensiveSummary(reportData) {
        const summary = {
            overview: {},
            financial_summary: {},
            operational_metrics: {}
        };

        const transactions = reportData.transactions.transactions || [];
        if (transactions.length > 0) {
            const totalAmount = sum(transactions, 'amount');
            summary.overview.transactions = {
                count: transactions.length,
                total_amount: totalAmount,
                average_amount: totalAmount / transactions.length
            };
        }

        const settlements = reportData.settlements.settlements || [];
        if (settlements.length > 0) {
            summary.financial_summary.settlements = {
                count: settlements.length,
                total_amount: sum(settlements, 'total_amount')
            };
        }

        const disputes = reportData.disputes.disputes || [];
        if (disputes.length > 0) {
            summary.operational_metrics.disputes = {
                count: disputes.length,
                total_amount: sum(disputes, 'case_amount'),
                dispute_rate: transactions.length > 0 ? (disputes.length / transactions.length) * 100 : 0
            };
        }

        const deposits = reportData.deposits.deposits || [];
        if (deposits.length > 0) {
            summary.financial_summary.deposits = {
                count: deposits.length,
                total_amount: sum(deposits, 'deposit_amount')
            };
        }

        return summary;
    }
}

/**
 * Get SDK configuration status. Answers without credentials.
 *
 * @returns {Object} Configuration status information
 */
export function getSdkConfigStatus() {
    const hasAppId = !!process.env.GP_API_APP_ID;
    const hasAppKey = !!process.env.GP_API_APP_KEY;
    const isConfigured = hasAppId && hasAppKey;

    return {
        configured: isConfigured,
        has_app_id: hasAppId,
        has_app_key: hasAppKey,
        environment: isConfigured ? 'TEST' : 'Not configured',
        timestamp: nowString()
    };
}

/**
 * Validate environment configuration. Answers without credentials.
 *
 * @returns {Object} Validation results
 */
export function validateEnvironmentConfig() {
    const results = {
        valid: true,
        errors: [],
        warnings: []
    };

    for (const varName of ['GP_API_APP_ID', 'GP_API_APP_KEY']) {
        if (!process.env[varName]) {
            results.valid = false;
            results.errors.push(`Missing required environment variable: ${varName}`);
        }
    }

    for (const varName of ['PUBLIC_API_KEY', 'SECRET_API_KEY']) {
        if (process.env[varName]) {
            results.warnings.push(`Legacy variable ${varName} found. GP-API uses GP_API_APP_ID and GP_API_APP_KEY.`);
        }
    }

    return results;
}

export default GlobalPaymentsReportingService;
