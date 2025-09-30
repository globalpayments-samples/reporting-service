/**
 * Global Payments Reporting Service
 *
 * This service class provides comprehensive reporting functionality for
 * Global Payments transactions including search, filtering, and data export.
 *
 * Node.js version 14 or higher
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
    ReportingService,
    Environment,
    Channel,
    TransactionSortProperty,
    SortDirection,
    ApiError
} from 'globalpayments-api';

// Load environment variables
dotenv.config();

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
     * @throws {Error} If configuration fails
     */
    constructor() {
        this.isConfigured = false;
        this.configureGpApiSdk();
    }

    /**
     * Configure the SDK for GP-API with reporting capabilities
     *
     * @throws {Error} If required environment variables are missing
     */
    configureGpApiSdk() {
        try {
            // Validate required environment variables
            const requiredVars = ['GP_API_APP_ID', 'GP_API_APP_KEY'];
            for (const varName of requiredVars) {
                if (!process.env[varName]) {
                    throw new Error(`Missing required environment variable: ${varName}`);
                }
            }

            const config = new GpApiConfig();

            // Set GP-API credentials
            config.appId = process.env.GP_API_APP_ID;
            config.appKey = process.env.GP_API_APP_KEY;

            // Configure environment (sandbox for development)
            config.environment = Environment.TEST; // Change to PRODUCTION for live

            // Set channel for ecommerce transactions
            config.channel = Channel.CardNotPresent;

            // Configure the service container
            ServicesContainer.configureService(config, 'default');

            this.isConfigured = true;
        } catch (error) {
            throw new Error(`Failed to configure SDK: ${error.message}`);
        }
    }

    /**
     * Search transactions with filters and pagination
     *
     * @param {Object} filters - Search filters and pagination parameters
     * @returns {Promise<Object>} Transaction search results
     * @throws {ApiError} If the search request fails
     */
    async searchTransactions(filters = {}) {
        this.ensureConfigured();

        try {
            const page = filters.page || 1;
            const pageSize = filters.page_size || 10;

            const reportBuilder = ReportingService.findTransactionsPaged(page, pageSize);

            // Apply date range filters
            if (filters.start_date) {
                const startDate = new Date(filters.start_date);
                reportBuilder.withStartDate(startDate);
            }

            if (filters.end_date) {
                const endDate = new Date(filters.end_date);
                reportBuilder.withEndDate(endDate);
            }

            // Apply transaction ID filter
            if (filters.transaction_id) {
                reportBuilder.withTransactionId(filters.transaction_id);
            }

            // Apply payment type filter
            if (filters.payment_type) {
                reportBuilder.withPaymentType(filters.payment_type);
            }

            // Apply amount range filters
            if (filters.amount_min) {
                reportBuilder.withAmount(parseFloat(filters.amount_min));
            }

            if (filters.amount_max) {
                reportBuilder.withAmount(parseFloat(filters.amount_max));
            }

            // Apply card number filter (last 4 digits)
            if (filters.card_last_four) {
                reportBuilder.withCardNumberLastFour(filters.card_last_four);
            }

            // Execute the search
            const response = await reportBuilder.execute();

            let transactions = this.formatTransactionList(response.result || []);

            // Apply client-side filtering for status if needed
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
                timestamp: new Date().toISOString()
            };

        } catch (error) {
            throw new ApiError(`Transaction search failed: ${error.message}`);
        }
    }

    /**
     * Get detailed information for a specific transaction
     *
     * @param {string} transactionId - The transaction ID to retrieve
     * @returns {Promise<Object>} Transaction details
     * @throws {ApiError} If the transaction cannot be found
     */
    async getTransactionDetails(transactionId) {
        this.ensureConfigured();

        try {
            const reportBuilder = ReportingService.transactionDetail(transactionId);
            const response = await reportBuilder.execute();

            if (!response) {
                throw new ApiError('Transaction not found');
            }

            return {
                success: true,
                data: this.formatTransactionDetails(response),
                timestamp: new Date().toISOString()
            };

        } catch (error) {
            throw new ApiError(`Failed to retrieve transaction details: ${error.message}`);
        }
    }

    /**
     * Generate settlement report for a date range
     *
     * @param {Object} params - Report parameters
     * @returns {Promise<Object>} Settlement report data
     * @throws {ApiError} If the report generation fails
     */
    async getSettlementReport(params = {}) {
        this.ensureConfigured();

        try {
            const page = params.page || 1;
            const pageSize = params.page_size || 50;

            const reportBuilder = ReportingService.findSettlementTransactionsPaged(page, pageSize);

            // Apply date range
            if (params.start_date) {
                const startDate = new Date(params.start_date);
                reportBuilder.withStartDate(startDate);
            }

            if (params.end_date) {
                const endDate = new Date(params.end_date);
                reportBuilder.withEndDate(endDate);
            }

            const response = await reportBuilder.execute();

            return {
                success: true,
                data: {
                    settlements: this.formatSettlementList(response.result || []),
                    summary: this.generateSettlementSummary(response.result || []),
                    pagination: {
                        page: page,
                        page_size: pageSize,
                        total_count: response.totalRecordCount || 0
                    }
                },
                timestamp: new Date().toISOString()
            };

        } catch (error) {
            throw new ApiError(`Settlement report generation failed: ${error.message}`);
        }
    }

    /**
     * Get dispute reports with filtering and pagination
     *
     * @param {Object} filters - Dispute search filters and pagination
     * @returns {Promise<Object>} Dispute report results
     * @throws {ApiError} If the dispute search fails
     */
    async getDisputeReport(filters = {}) {
        this.ensureConfigured();

        try {
            const page = filters.page || 1;
            const pageSize = filters.page_size || 10;

            const reportBuilder = ReportingService.findDisputesPaged(page, pageSize);

            // Apply date range filters
            if (filters.start_date) {
                const startDate = new Date(filters.start_date);
                reportBuilder.withStartDate(startDate);
            }

            if (filters.end_date) {
                const endDate = new Date(filters.end_date);
                reportBuilder.withEndDate(endDate);
            }

            // Apply dispute stage filter
            if (filters.stage) {
                reportBuilder.withDisputeStage(filters.stage);
            }

            // Apply dispute status filter
            if (filters.status) {
                reportBuilder.withDisputeStatus(filters.status);
            }

            // Execute the search
            const response = await reportBuilder.execute();

            return {
                success: true,
                data: {
                    disputes: this.formatDisputeList(response.result || []),
                    pagination: {
                        page: page,
                        page_size: pageSize,
                        total_count: response.totalRecordCount || 0
                    }
                },
                timestamp: new Date().toISOString()
            };

        } catch (error) {
            throw new ApiError(`Dispute report generation failed: ${error.message}`);
        }
    }

    /**
     * Get detailed information for a specific dispute
     *
     * @param {string} disputeId - The dispute ID to retrieve
     * @returns {Promise<Object>} Dispute details
     * @throws {ApiError} If the dispute cannot be found
     */
    async getDisputeDetails(disputeId) {
        this.ensureConfigured();

        try {
            const reportBuilder = ReportingService.disputeDetail(disputeId);
            const response = await reportBuilder.execute();

            if (!response) {
                throw new ApiError('Dispute not found');
            }

            return {
                success: true,
                data: this.formatDisputeDetails(response),
                timestamp: new Date().toISOString()
            };

        } catch (error) {
            throw new ApiError(`Failed to retrieve dispute details: ${error.message}`);
        }
    }

    /**
     * Get deposit reports with filtering and pagination
     *
     * @param {Object} filters - Deposit search filters and pagination
     * @returns {Promise<Object>} Deposit report results
     * @throws {ApiError} If the deposit search fails
     */
    async getDepositReport(filters = {}) {
        this.ensureConfigured();

        try {
            const page = filters.page || 1;
            const pageSize = filters.page_size || 10;

            const reportBuilder = ReportingService.findDepositsPaged(page, pageSize);

            // Apply date range filters
            if (filters.start_date) {
                const startDate = new Date(filters.start_date);
                reportBuilder.withStartDate(startDate);
            }

            if (filters.end_date) {
                const endDate = new Date(filters.end_date);
                reportBuilder.withEndDate(endDate);
            }

            // Apply deposit ID filter
            if (filters.deposit_id) {
                reportBuilder.withDepositReference(filters.deposit_id);
            }

            // Apply status filter
            if (filters.status) {
                reportBuilder.withDepositStatus(filters.status);
            }

            // Execute the search
            const response = await reportBuilder.execute();

            return {
                success: true,
                data: {
                    deposits: this.formatDepositList(response.result || []),
                    pagination: {
                        page: page,
                        page_size: pageSize,
                        total_count: response.totalRecordCount || 0
                    }
                },
                timestamp: new Date().toISOString()
            };

        } catch (error) {
            throw new ApiError(`Deposit report generation failed: ${error.message}`);
        }
    }

    /**
     * Get detailed information for a specific deposit
     *
     * @param {string} depositId - The deposit ID to retrieve
     * @returns {Promise<Object>} Deposit details
     * @throws {ApiError} If the deposit cannot be found
     */
    async getDepositDetails(depositId) {
        this.ensureConfigured();

        try {
            const reportBuilder = ReportingService.depositDetail(depositId);
            const response = await reportBuilder.execute();

            if (!response) {
                throw new ApiError('Deposit not found');
            }

            return {
                success: true,
                data: this.formatDepositDetails(response),
                timestamp: new Date().toISOString()
            };

        } catch (error) {
            throw new ApiError(`Failed to retrieve deposit details: ${error.message}`);
        }
    }

    /**
     * Get batch report with detailed transaction information
     *
     * @param {Object} filters - Batch search filters
     * @returns {Promise<Object>} Batch report results
     * @throws {ApiError} If the batch search fails
     */
    async getBatchReport(filters = {}) {
        this.ensureConfigured();

        try {
            const reportBuilder = ReportingService.findBatchesPaged(1, 100);

            // Apply date range filters
            if (filters.start_date) {
                const startDate = new Date(filters.start_date);
                reportBuilder.withStartDate(startDate);
            }

            if (filters.end_date) {
                const endDate = new Date(filters.end_date);
                reportBuilder.withEndDate(endDate);
            }

            // Execute the search
            const response = await reportBuilder.execute();

            return {
                success: true,
                data: {
                    batches: this.formatBatchList(response.result || []),
                    summary: this.generateBatchSummary(response.result || [])
                },
                timestamp: new Date().toISOString()
            };

        } catch (error) {
            throw new ApiError(`Batch report generation failed: ${error.message}`);
        }
    }

    /**
     * Get declined transactions report
     *
     * @param {Object} filters - Decline search filters and pagination
     * @returns {Promise<Object>} Declined transactions report
     * @throws {ApiError} If the search fails
     */
    async getDeclinedTransactionsReport(filters = {}) {
        this.ensureConfigured();

        try {
            // Use transaction search with declined status filter
            const declineFilters = { ...filters, status: 'DECLINED' };
            const result = await this.searchTransactions(declineFilters);

            // Add decline analysis
            if (result.success) {
                result.data.decline_analysis = this.analyzeDeclines(result.data.transactions);
            }

            return result;

        } catch (error) {
            throw new ApiError(`Declined transactions report generation failed: ${error.message}`);
        }
    }

    /**
     * Get comprehensive date range report across all transaction types
     *
     * @param {Object} params - Date range and report parameters
     * @returns {Promise<Object>} Comprehensive date range report
     */
    async getDateRangeReport(params = {}) {
        this.ensureConfigured();

        try {
            const startDate = params.start_date || new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];
            const endDate = params.end_date || new Date().toISOString().split('T')[0];

            const report = {
                success: true,
                data: {
                    period: {
                        start_date: startDate,
                        end_date: endDate
                    },
                    transactions: {},
                    settlements: {},
                    disputes: {},
                    deposits: {},
                    summary: {}
                },
                timestamp: new Date().toISOString()
            };

            // Get transactions for the period
            const transactionResult = await this.searchTransactions({
                start_date: startDate,
                end_date: endDate,
                page_size: params.transaction_limit || 100
            });

            if (transactionResult.success) {
                report.data.transactions = transactionResult.data;
            }

            // Get settlements for the period
            const settlementResult = await this.getSettlementReport({
                start_date: startDate,
                end_date: endDate,
                page_size: params.settlement_limit || 50
            });

            if (settlementResult.success) {
                report.data.settlements = settlementResult.data;
            }

            // Get disputes for the period
            try {
                const disputeResult = await this.getDisputeReport({
                    start_date: startDate,
                    end_date: endDate,
                    page_size: params.dispute_limit || 25
                });

                if (disputeResult.success) {
                    report.data.disputes = disputeResult.data;
                }
            } catch (error) {
                report.data.disputes = { error: `Disputes not available: ${error.message}` };
            }

            // Get deposits for the period
            try {
                const depositResult = await this.getDepositReport({
                    start_date: startDate,
                    end_date: endDate,
                    page_size: params.deposit_limit || 25
                });

                if (depositResult.success) {
                    report.data.deposits = depositResult.data;
                }
            } catch (error) {
                report.data.deposits = { error: `Deposits not available: ${error.message}` };
            }

            // Generate comprehensive summary
            report.data.summary = this.generateComprehensiveSummary(report.data);

            return report;

        } catch (error) {
            return {
                success: false,
                error: `Failed to generate date range report: ${error.message}`,
                timestamp: new Date().toISOString()
            };
        }
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
        this.ensureConfigured();

        try {
            // Get all transactions (remove pagination for export)
            const exportFilters = { ...filters };
            exportFilters.page_size = 1000; // Increased limit for export

            const transactions = await this.searchTransactions(exportFilters);

            if (format === 'csv') {
                return this.exportToCsv(transactions.data.transactions);
            }

            if (format === 'xml') {
                return this.exportToXml(transactions.data.transactions);
            }

            return {
                success: true,
                data: transactions.data.transactions,
                format: 'json',
                timestamp: new Date().toISOString()
            };

        } catch (error) {
            throw new ApiError(`Export failed: ${error.message}`);
        }
    }

    /**
     * Get reporting summary statistics
     *
     * @param {Object} params - Summary parameters
     * @returns {Promise<Object>} Summary statistics
     */
    async getSummaryStats(params = {}) {
        this.ensureConfigured();

        try {
            const startDate = params.start_date || new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];
            const endDate = params.end_date || new Date().toISOString().split('T')[0];

            // Get transaction summary
            const transactions = await this.searchTransactions({
                start_date: startDate,
                end_date: endDate,
                page_size: 1000
            });

            return {
                success: true,
                data: this.calculateSummaryStats(transactions.data.transactions),
                period: {
                    start_date: startDate,
                    end_date: endDate
                },
                timestamp: new Date().toISOString()
            };

        } catch (error) {
            return {
                success: false,
                error: `Failed to generate summary statistics: ${error.message}`,
                timestamp: new Date().toISOString()
            };
        }
    }

    /**
     * Ensure SDK is properly configured
     *
     * @throws {Error} If SDK is not configured
     */
    ensureConfigured() {
        if (!this.isConfigured) {
            throw new Error('SDK is not properly configured');
        }
    }

    /**
     * Format transaction list for API response
     *
     * @param {Array} transactions - Raw transaction data
     * @returns {Array} Formatted transaction list
     */
    formatTransactionList(transactions) {
        return transactions.map(transaction => ({
            transaction_id: transaction.transactionId || '',
            timestamp: transaction.transactionDate || '',
            amount: transaction.amount || 0,
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
     * @param {Object} transaction - Raw transaction detail data
     * @returns {Object} Formatted transaction details
     */
    formatTransactionDetails(transaction) {
        return {
            transaction_id: transaction.transactionId || '',
            timestamp: transaction.transactionDate || '',
            amount: transaction.amount || 0,
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
     * Format settlement list for API response
     *
     * @param {Array} settlements - Raw settlement data
     * @returns {Array} Formatted settlement list
     */
    formatSettlementList(settlements) {
        return settlements.map(settlement => ({
            settlement_id: settlement.settlementId || '',
            settlement_date: settlement.settlementDate || '',
            transaction_count: settlement.transactionCount || 0,
            total_amount: settlement.totalAmount || 0,
            currency: settlement.currency || 'USD',
            status: settlement.status || ''
        }));
    }

    /**
     * Generate settlement summary statistics
     *
     * @param {Array} settlements - Raw settlement data
     * @returns {Object} Settlement summary
     */
    generateSettlementSummary(settlements) {
        let totalAmount = 0;
        let totalTransactions = 0;

        settlements.forEach(settlement => {
            totalAmount += settlement.totalAmount || 0;
            totalTransactions += settlement.transactionCount || 0;
        });

        return {
            total_settlements: settlements.length,
            total_amount: totalAmount,
            total_transactions: totalTransactions,
            average_settlement_amount: settlements.length > 0 ? totalAmount / settlements.length : 0
        };
    }

    /**
     * Calculate summary statistics from transaction data
     *
     * @param {Array} transactions - Transaction data
     * @returns {Object} Summary statistics
     */
    calculateSummaryStats(transactions) {
        let totalAmount = 0;
        const statusCounts = {};
        const paymentTypeCounts = {};

        transactions.forEach(transaction => {
            totalAmount += transaction.amount || 0;

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
     * @param {Array} transactions - Transaction data
     * @returns {Object} CSV export data
     */
    exportToCsv(transactions) {
        let csvData = "Transaction ID,Timestamp,Amount,Currency,Status,Payment Method,Card Last Four,Auth Code,Reference Number\n";

        transactions.forEach(transaction => {
            const timestamp = transaction.timestamp || '';
            csvData += `${transaction.transaction_id || ''},${timestamp},${transaction.amount || ''},${transaction.currency || ''},${transaction.status || ''},${transaction.payment_method || ''},${transaction.card_last_four || ''},${transaction.auth_code || ''},${transaction.reference_number || ''}\n`;
        });

        return {
            success: true,
            data: csvData,
            format: 'csv',
            filename: `transactions_${new Date().toISOString().replace(/:/g, '-').split('.')[0]}.csv`,
            timestamp: new Date().toISOString()
        };
    }

    /**
     * Export transaction data to XML format
     *
     * @param {Array} transactions - Transaction data
     * @returns {Object} XML export data
     */
    exportToXml(transactions) {
        let xmlData = '<?xml version="1.0" encoding="UTF-8"?>\n<transactions>\n';

        transactions.forEach(transaction => {
            xmlData += '  <transaction>\n';
            xmlData += `    <transaction_id>${this.escapeXml(transaction.transaction_id || '')}</transaction_id>\n`;
            xmlData += `    <timestamp>${this.escapeXml(transaction.timestamp || '')}</timestamp>\n`;
            xmlData += `    <amount>${transaction.amount || 0}</amount>\n`;
            xmlData += `    <currency>${this.escapeXml(transaction.currency || '')}</currency>\n`;
            xmlData += `    <status>${this.escapeXml(transaction.status || '')}</status>\n`;
            xmlData += `    <payment_method>${this.escapeXml(transaction.payment_method || '')}</payment_method>\n`;
            xmlData += `    <card_last_four>${this.escapeXml(transaction.card_last_four || '')}</card_last_four>\n`;
            xmlData += `    <auth_code>${this.escapeXml(transaction.auth_code || '')}</auth_code>\n`;
            xmlData += `    <reference_number>${this.escapeXml(transaction.reference_number || '')}</reference_number>\n`;
            xmlData += '  </transaction>\n';
        });

        xmlData += '</transactions>';

        return {
            success: true,
            data: xmlData,
            format: 'xml',
            filename: `transactions_${new Date().toISOString().replace(/:/g, '-').split('.')[0]}.xml`,
            timestamp: new Date().toISOString()
        };
    }

    /**
     * Escape XML special characters
     *
     * @param {string} str - String to escape
     * @returns {string} Escaped string
     */
    escapeXml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&apos;');
    }

    /**
     * Format dispute list for API response
     *
     * @param {Array} disputes - Raw dispute data
     * @returns {Array} Formatted dispute list
     */
    formatDisputeList(disputes) {
        return disputes.map(dispute => ({
            dispute_id: dispute.caseId || '',
            transaction_id: dispute.transactionId || '',
            case_number: dispute.caseNumber || '',
            dispute_stage: dispute.caseStage || '',
            dispute_status: dispute.caseStatus || '',
            case_amount: dispute.caseAmount || 0,
            currency: dispute.caseCurrency || 'USD',
            reason_code: dispute.reasonCode || '',
            reason_description: dispute.reason || '',
            case_time: dispute.caseTime || '',
            last_adjustment_time: dispute.lastAdjustmentTime || ''
        }));
    }

    /**
     * Format detailed dispute information
     *
     * @param {Object} dispute - Raw dispute detail data
     * @returns {Object} Formatted dispute details
     */
    formatDisputeDetails(dispute) {
        return {
            dispute_id: dispute.caseId || '',
            transaction_id: dispute.transactionId || '',
            case_number: dispute.caseNumber || '',
            dispute_stage: dispute.caseStage || '',
            dispute_status: dispute.caseStatus || '',
            case_amount: dispute.caseAmount || 0,
            currency: dispute.caseCurrency || 'USD',
            reason_code: dispute.reasonCode || '',
            reason_description: dispute.reason || '',
            case_time: dispute.caseTime || '',
            last_adjustment_time: dispute.lastAdjustmentTime || '',
            case_description: dispute.caseDescription || '',
            documents: dispute.documents || [],
            transaction_details: {
                amount: dispute.transactionAmount || 0,
                currency: dispute.transactionCurrency || 'USD',
                masked_card_number: dispute.transactionMaskedCardNumber || '',
                arn: dispute.transactionARN || ''
            }
        };
    }

    /**
     * Format deposit list for API response
     *
     * @param {Array} deposits - Raw deposit data
     * @returns {Array} Formatted deposit list
     */
    formatDepositList(deposits) {
        return deposits.map(deposit => ({
            deposit_id: deposit.depositId || '',
            deposit_date: deposit.depositDate || '',
            deposit_reference: deposit.depositReference || '',
            deposit_status: deposit.status || '',
            deposit_amount: deposit.amount || 0,
            currency: deposit.currency || 'USD',
            merchant_number: deposit.merchantNumber || '',
            merchant_hierarchy: deposit.merchantHierarchy || '',
            sales_count: deposit.salesCount || 0,
            sales_amount: deposit.salesAmount || 0,
            refunds_count: deposit.refundsCount || 0,
            refunds_amount: deposit.refundsAmount || 0
        }));
    }

    /**
     * Format detailed deposit information
     *
     * @param {Object} deposit - Raw deposit detail data
     * @returns {Object} Formatted deposit details
     */
    formatDepositDetails(deposit) {
        return {
            deposit_id: deposit.depositId || '',
            deposit_date: deposit.depositDate || '',
            deposit_reference: deposit.depositReference || '',
            deposit_status: deposit.status || '',
            deposit_amount: deposit.amount || 0,
            currency: deposit.currency || 'USD',
            merchant_number: deposit.merchantNumber || '',
            merchant_hierarchy: deposit.merchantHierarchy || '',
            bank_account: {
                masked_account_number: deposit.maskedAccountNumber || '',
                bank_name: deposit.bankName || ''
            },
            transaction_summary: {
                sales_count: deposit.salesCount || 0,
                sales_amount: deposit.salesAmount || 0,
                refunds_count: deposit.refundsCount || 0,
                refunds_amount: deposit.refundsAmount || 0,
                chargebacks_count: deposit.chargebacksCount || 0,
                chargebacks_amount: deposit.chargebacksAmount || 0,
                adjustments_count: deposit.adjustmentsCount || 0,
                adjustments_amount: deposit.adjustmentsAmount || 0
            }
        };
    }

    /**
     * Format batch list for API response
     *
     * @param {Array} batches - Raw batch data
     * @returns {Array} Formatted batch list
     */
    formatBatchList(batches) {
        return batches.map(batch => ({
            batch_id: batch.batchId || '',
            sequence_number: batch.sequenceNumber || '',
            transaction_count: batch.transactionCount || 0,
            total_amount: batch.totalAmount || 0,
            currency: batch.currency || 'USD',
            batch_status: batch.batchStatus || '',
            close_time: batch.closeTime || '',
            open_time: batch.openTime || ''
        }));
    }

    /**
     * Generate batch summary statistics
     *
     * @param {Array} batches - Raw batch data
     * @returns {Object} Batch summary
     */
    generateBatchSummary(batches) {
        let totalAmount = 0;
        let totalTransactions = 0;
        const batchStatuses = {};

        batches.forEach(batch => {
            totalAmount += batch.totalAmount || 0;
            totalTransactions += batch.transactionCount || 0;

            const status = batch.batchStatus || 'unknown';
            batchStatuses[status] = (batchStatuses[status] || 0) + 1;
        });

        return {
            total_batches: batches.length,
            total_amount: totalAmount,
            total_transactions: totalTransactions,
            average_batch_amount: batches.length > 0 ? totalAmount / batches.length : 0,
            status_breakdown: batchStatuses
        };
    }

    /**
     * Analyze decline patterns from transaction data
     *
     * @param {Array} transactions - Declined transaction data
     * @returns {Object} Decline analysis
     */
    analyzeDeclines(transactions) {
        const declineReasons = {};
        const cardTypes = {};
        const hourlyBreakdown = {};
        let totalAmount = 0;

        transactions.forEach(transaction => {
            // Analyze decline reasons (if available in gateway response)
            const reason = transaction.gateway_response_message || 'Unknown';
            declineReasons[reason] = (declineReasons[reason] || 0) + 1;

            // Analyze card types
            const cardType = transaction.payment_method || 'Unknown';
            cardTypes[cardType] = (cardTypes[cardType] || 0) + 1;

            // Analyze hourly patterns
            if (transaction.timestamp) {
                const date = new Date(transaction.timestamp);
                const hour = date.getHours().toString().padStart(2, '0');
                hourlyBreakdown[hour] = (hourlyBreakdown[hour] || 0) + 1;
            }

            totalAmount += transaction.amount || 0;
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

        // Transaction overview
        if (reportData.transactions && reportData.transactions.transactions) {
            const transactions = reportData.transactions.transactions;
            const transactionCount = transactions.length;
            const totalAmount = transactions.reduce((sum, t) => sum + (t.amount || 0), 0);

            summary.overview.transactions = {
                count: transactionCount,
                total_amount: totalAmount,
                average_amount: transactionCount > 0 ? totalAmount / transactionCount : 0
            };
        }

        // Settlement summary
        if (reportData.settlements && reportData.settlements.settlements) {
            const settlements = reportData.settlements.settlements;
            const settlementCount = settlements.length;
            const settledAmount = settlements.reduce((sum, s) => sum + (s.total_amount || 0), 0);

            summary.financial_summary.settlements = {
                count: settlementCount,
                total_amount: settledAmount
            };
        }

        // Dispute summary
        if (reportData.disputes && reportData.disputes.disputes) {
            const disputes = reportData.disputes.disputes;
            const disputeCount = disputes.length;
            const disputeAmount = disputes.reduce((sum, d) => sum + (d.case_amount || 0), 0);

            summary.operational_metrics.disputes = {
                count: disputeCount,
                total_amount: disputeAmount,
                dispute_rate: summary.overview.transactions && summary.overview.transactions.count > 0
                    ? (disputeCount / summary.overview.transactions.count) * 100 : 0
            };
        }

        // Deposit summary
        if (reportData.deposits && reportData.deposits.deposits) {
            const deposits = reportData.deposits.deposits;
            const depositCount = deposits.length;
            const depositAmount = deposits.reduce((sum, d) => sum + (d.deposit_amount || 0), 0);

            summary.financial_summary.deposits = {
                count: depositCount,
                total_amount: depositAmount
            };
        }

        return summary;
    }

    /**
     * Get SDK configuration status
     *
     * @returns {Object} Configuration status information
     */
    getSdkConfigStatus() {
        try {
            const hasAppId = !!process.env.GP_API_APP_ID;
            const hasAppKey = !!process.env.GP_API_APP_KEY;
            const isConfigured = hasAppId && hasAppKey;

            return {
                configured: isConfigured,
                has_app_id: hasAppId,
                has_app_key: hasAppKey,
                environment: isConfigured ? 'TEST' : 'Not configured',
                timestamp: new Date().toISOString()
            };
        } catch (error) {
            return {
                configured: false,
                error: error.message,
                timestamp: new Date().toISOString()
            };
        }
    }

    /**
     * Validate environment configuration
     *
     * @returns {Object} Validation results
     */
    validateEnvironmentConfig() {
        const results = {
            valid: true,
            errors: [],
            warnings: []
        };

        // Check required variables
        const required = ['GP_API_APP_ID', 'GP_API_APP_KEY'];
        required.forEach(varName => {
            if (!process.env[varName]) {
                results.valid = false;
                results.errors.push(`Missing required environment variable: ${varName}`);
            }
        });

        // Check legacy variables and warn if present
        const legacy = ['PUBLIC_API_KEY', 'SECRET_API_KEY'];
        legacy.forEach(varName => {
            if (process.env[varName]) {
                results.warnings.push(`Legacy variable ${varName} found. GP-API uses GP_API_APP_ID and GP_API_APP_KEY.`);
            }
        });

        return results;
    }
}

export default GlobalPaymentsReportingService;