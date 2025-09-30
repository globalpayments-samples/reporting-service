"""
Global Payments Reporting Service

This service class provides comprehensive reporting functionality for
Global Payments transactions including search, filtering, and data export.

Python version 3.7 or higher
"""

import os
from datetime import datetime
from typing import Dict, List, Any, Optional
from dotenv import load_dotenv

from globalpayments.api import ServicesContainer
from globalpayments.api.services import ReportingService
from globalpayments.api.gateways import GpApiConfig
from globalpayments.api.entities.enums import (
    Environment,
    Channel,
    TransactionStatus,
    PaymentType
)
from globalpayments.api.entities.exceptions import ApiException


class GlobalPaymentsReportingService:
    """
    Global Payments Reporting Service Class

    Provides methods for transaction reporting, searching, and data export
    using the Global Payments SDK reporting capabilities.
    """

    def __init__(self):
        """Initialize and configure the SDK"""
        self.is_configured = False
        try:
            self._configure_gp_api_sdk()
            self.is_configured = True
        except Exception as e:
            raise ValueError(f'Failed to configure SDK: {str(e)}')

    def _configure_gp_api_sdk(self) -> None:
        """Configure the SDK for GP-API with reporting capabilities"""
        load_dotenv()

        # Validate required environment variables
        required_vars = ['GP_API_APP_ID', 'GP_API_APP_KEY']
        for var in required_vars:
            if not os.getenv(var):
                raise ValueError(f"Missing required environment variable: {var}")

        config = GpApiConfig()
        config.app_id = os.getenv('GP_API_APP_ID')
        config.app_key = os.getenv('GP_API_APP_KEY')
        config.environment = Environment.TEST  # Change to PRODUCTION for live
        config.channel = Channel.CardNotPresent

        ServicesContainer.configure(config, 'default')

    def _ensure_configured(self) -> None:
        """Ensure SDK is properly configured"""
        if not self.is_configured:
            raise RuntimeError('SDK is not properly configured')

    def search_transactions(self, filters: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """
        Search transactions with filters and pagination

        Args:
            filters: Search filters and pagination parameters

        Returns:
            Transaction search results
        """
        self._ensure_configured()

        if filters is None:
            filters = {}

        try:
            page = filters.get('page', 1)
            page_size = filters.get('page_size', 10)

            report_builder = ReportingService.find_transactions_paged(page, page_size)

            # Apply date range filters
            if filters.get('start_date'):
                start_date = datetime.strptime(filters['start_date'], '%Y-%m-%d')
                report_builder.with_start_date(start_date)

            if filters.get('end_date'):
                end_date = datetime.strptime(filters['end_date'], '%Y-%m-%d')
                report_builder.with_end_date(end_date)

            # Apply transaction ID filter
            if filters.get('transaction_id'):
                report_builder.with_transaction_id(filters['transaction_id'])

            # Apply payment type filter
            if filters.get('payment_type'):
                payment_type = self._map_payment_type(filters['payment_type'])
                if payment_type:
                    report_builder.with_payment_type(payment_type)

            # Apply amount range filters
            if filters.get('amount_min'):
                report_builder.with_amount(float(filters['amount_min']))

            if filters.get('amount_max'):
                report_builder.with_amount(float(filters['amount_max']))

            # Apply card number filter (last 4 digits)
            if filters.get('card_last_four'):
                report_builder.with_card_number_last_four(filters['card_last_four'])

            # Execute the search
            response = report_builder.execute()

            transactions = self._format_transaction_list(response.results if response.results else [])

            # Apply client-side filtering for status if needed
            if filters.get('status'):
                status_filter = filters['status'].upper()
                transactions = [t for t in transactions if t.get('status', '').upper() == status_filter]

            return {
                'success': True,
                'data': {
                    'transactions': transactions,
                    'pagination': {
                        'page': page,
                        'page_size': page_size,
                        'total_count': len(transactions),
                        'original_total_count': response.total_record_count if hasattr(response, 'total_record_count') else 0
                    }
                },
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }

        except ApiException as e:
            raise ApiException(f'Transaction search failed: {str(e)}')

    def get_transaction_details(self, transaction_id: str) -> Dict[str, Any]:
        """
        Get detailed information for a specific transaction

        Args:
            transaction_id: The transaction ID to retrieve

        Returns:
            Transaction details
        """
        self._ensure_configured()

        try:
            report_builder = ReportingService.transaction_detail(transaction_id)
            response = report_builder.execute()

            if not response:
                raise ApiException('Transaction not found')

            return {
                'success': True,
                'data': self._format_transaction_details(response),
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }

        except ApiException as e:
            raise ApiException(f'Failed to retrieve transaction details: {str(e)}')

    def get_settlement_report(self, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """
        Generate settlement report for a date range

        Args:
            params: Report parameters

        Returns:
            Settlement report data
        """
        self._ensure_configured()

        if params is None:
            params = {}

        try:
            page = params.get('page', 1)
            page_size = params.get('page_size', 50)

            report_builder = ReportingService.find_settlement_transactions_paged(page, page_size)

            # Apply date range
            if params.get('start_date'):
                start_date = datetime.strptime(params['start_date'], '%Y-%m-%d')
                report_builder.with_start_date(start_date)

            if params.get('end_date'):
                end_date = datetime.strptime(params['end_date'], '%Y-%m-%d')
                report_builder.with_end_date(end_date)

            response = report_builder.execute()

            settlements = self._format_settlement_list(response.results if response.results else [])

            return {
                'success': True,
                'data': {
                    'settlements': settlements,
                    'summary': self._generate_settlement_summary(response.results if response.results else []),
                    'pagination': {
                        'page': page,
                        'page_size': page_size,
                        'total_count': response.total_record_count if hasattr(response, 'total_record_count') else 0
                    }
                },
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }

        except ApiException as e:
            raise ApiException(f'Settlement report generation failed: {str(e)}')

    def get_dispute_report(self, filters: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """
        Get dispute reports with filtering and pagination

        Args:
            filters: Dispute search filters and pagination

        Returns:
            Dispute report results
        """
        self._ensure_configured()

        if filters is None:
            filters = {}

        try:
            page = filters.get('page', 1)
            page_size = filters.get('page_size', 10)

            report_builder = ReportingService.find_disputes_paged(page, page_size)

            # Apply date range filters
            if filters.get('start_date'):
                start_date = datetime.strptime(filters['start_date'], '%Y-%m-%d')
                report_builder.with_start_date(start_date)

            if filters.get('end_date'):
                end_date = datetime.strptime(filters['end_date'], '%Y-%m-%d')
                report_builder.with_end_date(end_date)

            # Apply dispute stage filter
            if filters.get('stage'):
                report_builder.with_dispute_stage(filters['stage'])

            # Apply dispute status filter
            if filters.get('status'):
                report_builder.with_dispute_status(filters['status'])

            # Execute the search
            response = report_builder.execute()

            return {
                'success': True,
                'data': {
                    'disputes': self._format_dispute_list(response.results if response.results else []),
                    'pagination': {
                        'page': page,
                        'page_size': page_size,
                        'total_count': response.total_record_count if hasattr(response, 'total_record_count') else 0
                    }
                },
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }

        except ApiException as e:
            raise ApiException(f'Dispute report generation failed: {str(e)}')

    def get_dispute_details(self, dispute_id: str) -> Dict[str, Any]:
        """
        Get detailed information for a specific dispute

        Args:
            dispute_id: The dispute ID to retrieve

        Returns:
            Dispute details
        """
        self._ensure_configured()

        try:
            report_builder = ReportingService.dispute_detail(dispute_id)
            response = report_builder.execute()

            if not response:
                raise ApiException('Dispute not found')

            return {
                'success': True,
                'data': self._format_dispute_details(response),
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }

        except ApiException as e:
            raise ApiException(f'Failed to retrieve dispute details: {str(e)}')

    def get_deposit_report(self, filters: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """
        Get deposit reports with filtering and pagination

        Args:
            filters: Deposit search filters and pagination

        Returns:
            Deposit report results
        """
        self._ensure_configured()

        if filters is None:
            filters = {}

        try:
            page = filters.get('page', 1)
            page_size = filters.get('page_size', 10)

            report_builder = ReportingService.find_deposits_paged(page, page_size)

            # Apply date range filters
            if filters.get('start_date'):
                start_date = datetime.strptime(filters['start_date'], '%Y-%m-%d')
                report_builder.with_start_date(start_date)

            if filters.get('end_date'):
                end_date = datetime.strptime(filters['end_date'], '%Y-%m-%d')
                report_builder.with_end_date(end_date)

            # Apply deposit ID filter
            if filters.get('deposit_id'):
                report_builder.with_deposit_reference(filters['deposit_id'])

            # Apply status filter
            if filters.get('status'):
                report_builder.with_deposit_status(filters['status'])

            # Execute the search
            response = report_builder.execute()

            return {
                'success': True,
                'data': {
                    'deposits': self._format_deposit_list(response.results if response.results else []),
                    'pagination': {
                        'page': page,
                        'page_size': page_size,
                        'total_count': response.total_record_count if hasattr(response, 'total_record_count') else 0
                    }
                },
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }

        except ApiException as e:
            raise ApiException(f'Deposit report generation failed: {str(e)}')

    def get_deposit_details(self, deposit_id: str) -> Dict[str, Any]:
        """
        Get detailed information for a specific deposit

        Args:
            deposit_id: The deposit ID to retrieve

        Returns:
            Deposit details
        """
        self._ensure_configured()

        try:
            report_builder = ReportingService.deposit_detail(deposit_id)
            response = report_builder.execute()

            if not response:
                raise ApiException('Deposit not found')

            return {
                'success': True,
                'data': self._format_deposit_details(response),
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }

        except ApiException as e:
            raise ApiException(f'Failed to retrieve deposit details: {str(e)}')

    def get_batch_report(self, filters: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """
        Get batch report with detailed transaction information

        Args:
            filters: Batch search filters

        Returns:
            Batch report results
        """
        self._ensure_configured()

        if filters is None:
            filters = {}

        try:
            report_builder = ReportingService.batch_detail()

            # Apply date range filters
            if filters.get('start_date'):
                start_date = datetime.strptime(filters['start_date'], '%Y-%m-%d')
                report_builder.with_start_date(start_date)

            if filters.get('end_date'):
                end_date = datetime.strptime(filters['end_date'], '%Y-%m-%d')
                report_builder.with_end_date(end_date)

            # Execute the search
            response = report_builder.execute()

            batches = response.results if response.results else []

            return {
                'success': True,
                'data': {
                    'batches': self._format_batch_list(batches),
                    'summary': self._generate_batch_summary(batches)
                },
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }

        except ApiException as e:
            raise ApiException(f'Batch report generation failed: {str(e)}')

    def get_declined_transactions_report(self, filters: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """
        Get declined transactions report

        Args:
            filters: Decline search filters and pagination

        Returns:
            Declined transactions report
        """
        self._ensure_configured()

        if filters is None:
            filters = {}

        try:
            # Use transaction search with declined status filter
            decline_filters = {**filters, 'status': 'DECLINED'}
            result = self.search_transactions(decline_filters)

            # Add decline analysis
            if result['success']:
                result['data']['decline_analysis'] = self._analyze_declines(result['data']['transactions'])

            return result

        except ApiException as e:
            raise ApiException(f'Declined transactions report generation failed: {str(e)}')

    def get_date_range_report(self, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """
        Get comprehensive date range report across all transaction types

        Args:
            params: Date range and report parameters

        Returns:
            Comprehensive date range report
        """
        self._ensure_configured()

        if params is None:
            params = {}

        try:
            from datetime import timedelta

            start_date = params.get('start_date', (datetime.now() - timedelta(days=30)).strftime('%Y-%m-%d'))
            end_date = params.get('end_date', datetime.now().strftime('%Y-%m-%d'))

            report = {
                'success': True,
                'data': {
                    'period': {
                        'start_date': start_date,
                        'end_date': end_date
                    },
                    'transactions': {},
                    'settlements': {},
                    'disputes': {},
                    'deposits': {},
                    'summary': {}
                },
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }

            # Get transactions for the period
            try:
                transaction_result = self.search_transactions({
                    'start_date': start_date,
                    'end_date': end_date,
                    'page_size': params.get('transaction_limit', 100)
                })
                if transaction_result['success']:
                    report['data']['transactions'] = transaction_result['data']
            except Exception as e:
                report['data']['transactions'] = {'error': f'Transactions not available: {str(e)}'}

            # Get settlements for the period
            try:
                settlement_result = self.get_settlement_report({
                    'start_date': start_date,
                    'end_date': end_date,
                    'page_size': params.get('settlement_limit', 50)
                })
                if settlement_result['success']:
                    report['data']['settlements'] = settlement_result['data']
            except Exception as e:
                report['data']['settlements'] = {'error': f'Settlements not available: {str(e)}'}

            # Get disputes for the period
            try:
                dispute_result = self.get_dispute_report({
                    'start_date': start_date,
                    'end_date': end_date,
                    'page_size': params.get('dispute_limit', 25)
                })
                if dispute_result['success']:
                    report['data']['disputes'] = dispute_result['data']
            except Exception as e:
                report['data']['disputes'] = {'error': f'Disputes not available: {str(e)}'}

            # Get deposits for the period
            try:
                deposit_result = self.get_deposit_report({
                    'start_date': start_date,
                    'end_date': end_date,
                    'page_size': params.get('deposit_limit', 25)
                })
                if deposit_result['success']:
                    report['data']['deposits'] = deposit_result['data']
            except Exception as e:
                report['data']['deposits'] = {'error': f'Deposits not available: {str(e)}'}

            # Generate comprehensive summary
            report['data']['summary'] = self._generate_comprehensive_summary(report['data'])

            return report

        except Exception as e:
            return {
                'success': False,
                'error': f'Failed to generate date range report: {str(e)}',
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }

    def export_transactions(self, filters: Optional[Dict[str, Any]] = None, format: str = 'json') -> Dict[str, Any]:
        """
        Export transaction data in specified format

        Args:
            filters: Search filters
            format: Export format ('json', 'csv', or 'xml')

        Returns:
            Export data
        """
        self._ensure_configured()

        if filters is None:
            filters = {}

        try:
            # Get all transactions (remove pagination for export)
            export_filters = {**filters, 'page_size': 1000}

            transactions = self.search_transactions(export_filters)

            if format == 'csv':
                return self._export_to_csv(transactions['data']['transactions'])
            elif format == 'xml':
                return self._export_to_xml(transactions['data']['transactions'])

            return {
                'success': True,
                'data': transactions['data']['transactions'],
                'format': 'json',
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }

        except ApiException as e:
            raise ApiException(f'Export failed: {str(e)}')

    def get_summary_stats(self, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """
        Get reporting summary statistics

        Args:
            params: Summary parameters

        Returns:
            Summary statistics
        """
        self._ensure_configured()

        if params is None:
            params = {}

        try:
            from datetime import timedelta

            start_date = params.get('start_date', (datetime.now() - timedelta(days=30)).strftime('%Y-%m-%d'))
            end_date = params.get('end_date', datetime.now().strftime('%Y-%m-%d'))

            # Get transaction summary
            transactions = self.search_transactions({
                'start_date': start_date,
                'end_date': end_date,
                'page_size': 1000
            })

            return {
                'success': True,
                'data': self._calculate_summary_stats(transactions['data']['transactions']),
                'period': {
                    'start_date': start_date,
                    'end_date': end_date
                },
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }

        except Exception as e:
            return {
                'success': False,
                'error': f'Failed to generate summary statistics: {str(e)}',
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }

    # Helper methods for mapping and formatting

    def _map_payment_type(self, payment_type: str) -> Optional[PaymentType]:
        """Map payment type string to PaymentType enum"""
        mapping = {
            'sale': PaymentType.Sale,
            'refund': PaymentType.Refund,
            'authorize': PaymentType.Auth,
            'capture': PaymentType.Capture
        }
        return mapping.get(payment_type.lower())

    def _format_transaction_list(self, transactions: List[Any]) -> List[Dict[str, Any]]:
        """Format transaction list for API response"""
        return [
            {
                'transaction_id': getattr(t, 'transaction_id', ''),
                'timestamp': str(getattr(t, 'transaction_date', '')),
                'amount': getattr(t, 'amount', 0),
                'currency': getattr(t, 'currency', 'USD'),
                'status': getattr(t, 'transaction_status', ''),
                'payment_method': getattr(t, 'payment_type', ''),
                'card_last_four': getattr(t, 'masked_card_number', ''),
                'auth_code': getattr(t, 'auth_code', ''),
                'reference_number': getattr(t, 'reference_number', '')
            }
            for t in transactions
        ]

    def _format_transaction_details(self, transaction: Any) -> Dict[str, Any]:
        """Format detailed transaction information"""
        return {
            'transaction_id': getattr(transaction, 'transaction_id', ''),
            'timestamp': str(getattr(transaction, 'transaction_date', '')),
            'amount': getattr(transaction, 'amount', 0),
            'currency': getattr(transaction, 'currency', 'USD'),
            'status': getattr(transaction, 'transaction_status', ''),
            'payment_method': getattr(transaction, 'payment_type', ''),
            'card_details': {
                'masked_number': getattr(transaction, 'masked_card_number', ''),
                'card_type': getattr(transaction, 'card_type', ''),
                'entry_mode': getattr(transaction, 'entry_mode', '')
            },
            'auth_code': getattr(transaction, 'auth_code', ''),
            'reference_number': getattr(transaction, 'reference_number', ''),
            'gateway_response_code': getattr(transaction, 'gateway_response_code', ''),
            'gateway_response_message': getattr(transaction, 'gateway_response_message', '')
        }

    def _format_settlement_list(self, settlements: List[Any]) -> List[Dict[str, Any]]:
        """Format settlement list for API response"""
        return [
            {
                'settlement_id': getattr(s, 'settlement_id', ''),
                'settlement_date': str(getattr(s, 'settlement_date', '')),
                'transaction_count': getattr(s, 'transaction_count', 0),
                'total_amount': getattr(s, 'total_amount', 0),
                'currency': getattr(s, 'currency', 'USD'),
                'status': getattr(s, 'status', '')
            }
            for s in settlements
        ]

    def _generate_settlement_summary(self, settlements: List[Any]) -> Dict[str, Any]:
        """Generate settlement summary statistics"""
        total_amount = sum(getattr(s, 'total_amount', 0) for s in settlements)
        total_transactions = sum(getattr(s, 'transaction_count', 0) for s in settlements)

        return {
            'total_settlements': len(settlements),
            'total_amount': total_amount,
            'total_transactions': total_transactions,
            'average_settlement_amount': total_amount / len(settlements) if settlements else 0
        }

    def _format_dispute_list(self, disputes: List[Any]) -> List[Dict[str, Any]]:
        """Format dispute list for API response"""
        return [
            {
                'dispute_id': getattr(d, 'case_id', ''),
                'transaction_id': getattr(d, 'transaction_id', ''),
                'case_number': getattr(d, 'case_number', ''),
                'dispute_stage': getattr(d, 'case_stage', ''),
                'dispute_status': getattr(d, 'case_status', ''),
                'case_amount': getattr(d, 'case_amount', 0),
                'currency': getattr(d, 'case_currency', 'USD'),
                'reason_code': getattr(d, 'reason_code', ''),
                'reason_description': getattr(d, 'reason', ''),
                'case_time': str(getattr(d, 'case_time', '')),
                'last_adjustment_time': str(getattr(d, 'last_adjustment_time', ''))
            }
            for d in disputes
        ]

    def _format_dispute_details(self, dispute: Any) -> Dict[str, Any]:
        """Format detailed dispute information"""
        return {
            'dispute_id': getattr(dispute, 'case_id', ''),
            'transaction_id': getattr(dispute, 'transaction_id', ''),
            'case_number': getattr(dispute, 'case_number', ''),
            'dispute_stage': getattr(dispute, 'case_stage', ''),
            'dispute_status': getattr(dispute, 'case_status', ''),
            'case_amount': getattr(dispute, 'case_amount', 0),
            'currency': getattr(dispute, 'case_currency', 'USD'),
            'reason_code': getattr(dispute, 'reason_code', ''),
            'reason_description': getattr(dispute, 'reason', ''),
            'case_time': str(getattr(dispute, 'case_time', '')),
            'last_adjustment_time': str(getattr(dispute, 'last_adjustment_time', '')),
            'case_description': getattr(dispute, 'case_description', ''),
            'documents': getattr(dispute, 'documents', []),
            'transaction_details': {
                'amount': getattr(dispute, 'transaction_amount', 0),
                'currency': getattr(dispute, 'transaction_currency', 'USD'),
                'masked_card_number': getattr(dispute, 'transaction_masked_card_number', ''),
                'arn': getattr(dispute, 'transaction_arn', '')
            }
        }

    def _format_deposit_list(self, deposits: List[Any]) -> List[Dict[str, Any]]:
        """Format deposit list for API response"""
        return [
            {
                'deposit_id': getattr(d, 'deposit_id', ''),
                'deposit_date': str(getattr(d, 'deposit_date', '')),
                'deposit_reference': getattr(d, 'deposit_reference', ''),
                'deposit_status': getattr(d, 'status', ''),
                'deposit_amount': getattr(d, 'amount', 0),
                'currency': getattr(d, 'currency', 'USD'),
                'merchant_number': getattr(d, 'merchant_number', ''),
                'merchant_hierarchy': getattr(d, 'merchant_hierarchy', ''),
                'sales_count': getattr(d, 'sales_count', 0),
                'sales_amount': getattr(d, 'sales_amount', 0),
                'refunds_count': getattr(d, 'refunds_count', 0),
                'refunds_amount': getattr(d, 'refunds_amount', 0)
            }
            for d in deposits
        ]

    def _format_deposit_details(self, deposit: Any) -> Dict[str, Any]:
        """Format detailed deposit information"""
        return {
            'deposit_id': getattr(deposit, 'deposit_id', ''),
            'deposit_date': str(getattr(deposit, 'deposit_date', '')),
            'deposit_reference': getattr(deposit, 'deposit_reference', ''),
            'deposit_status': getattr(deposit, 'status', ''),
            'deposit_amount': getattr(deposit, 'amount', 0),
            'currency': getattr(deposit, 'currency', 'USD'),
            'merchant_number': getattr(deposit, 'merchant_number', ''),
            'merchant_hierarchy': getattr(deposit, 'merchant_hierarchy', ''),
            'bank_account': {
                'masked_account_number': getattr(deposit, 'masked_account_number', ''),
                'bank_name': getattr(deposit, 'bank_name', '')
            },
            'transaction_summary': {
                'sales_count': getattr(deposit, 'sales_count', 0),
                'sales_amount': getattr(deposit, 'sales_amount', 0),
                'refunds_count': getattr(deposit, 'refunds_count', 0),
                'refunds_amount': getattr(deposit, 'refunds_amount', 0),
                'chargebacks_count': getattr(deposit, 'chargebacks_count', 0),
                'chargebacks_amount': getattr(deposit, 'chargebacks_amount', 0),
                'adjustments_count': getattr(deposit, 'adjustments_count', 0),
                'adjustments_amount': getattr(deposit, 'adjustments_amount', 0)
            }
        }

    def _format_batch_list(self, batches: List[Any]) -> List[Dict[str, Any]]:
        """Format batch list for API response"""
        return [
            {
                'batch_id': getattr(b, 'batch_id', ''),
                'sequence_number': getattr(b, 'sequence_number', ''),
                'transaction_count': getattr(b, 'transaction_count', 0),
                'total_amount': getattr(b, 'total_amount', 0),
                'currency': getattr(b, 'currency', 'USD'),
                'batch_status': getattr(b, 'batch_status', ''),
                'close_time': str(getattr(b, 'close_time', '')),
                'open_time': str(getattr(b, 'open_time', ''))
            }
            for b in batches
        ]

    def _generate_batch_summary(self, batches: List[Any]) -> Dict[str, Any]:
        """Generate batch summary statistics"""
        total_amount = sum(getattr(b, 'total_amount', 0) for b in batches)
        total_transactions = sum(getattr(b, 'transaction_count', 0) for b in batches)

        batch_statuses = {}
        for batch in batches:
            status = getattr(batch, 'batch_status', 'unknown')
            batch_statuses[status] = batch_statuses.get(status, 0) + 1

        return {
            'total_batches': len(batches),
            'total_amount': total_amount,
            'total_transactions': total_transactions,
            'average_batch_amount': total_amount / len(batches) if batches else 0,
            'status_breakdown': batch_statuses
        }

    def _calculate_summary_stats(self, transactions: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Calculate summary statistics from transaction data"""
        total_amount = sum(t.get('amount', 0) for t in transactions)

        status_counts = {}
        payment_type_counts = {}

        for transaction in transactions:
            status = transaction.get('status', 'unknown')
            status_counts[status] = status_counts.get(status, 0) + 1

            payment_type = transaction.get('payment_method', 'unknown')
            payment_type_counts[payment_type] = payment_type_counts.get(payment_type, 0) + 1

        return {
            'total_transactions': len(transactions),
            'total_amount': total_amount,
            'average_amount': total_amount / len(transactions) if transactions else 0,
            'status_breakdown': status_counts,
            'payment_type_breakdown': payment_type_counts
        }

    def _analyze_declines(self, transactions: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Analyze decline patterns from transaction data"""
        decline_reasons = {}
        card_types = {}
        hourly_breakdown = {}
        total_amount = 0

        for transaction in transactions:
            # Analyze decline reasons
            reason = transaction.get('gateway_response_message', 'Unknown')
            decline_reasons[reason] = decline_reasons.get(reason, 0) + 1

            # Analyze card types
            card_type = transaction.get('payment_method', 'Unknown')
            card_types[card_type] = card_types.get(card_type, 0) + 1

            # Analyze hourly patterns
            timestamp = transaction.get('timestamp', '')
            if timestamp:
                try:
                    hour = datetime.strptime(str(timestamp)[:19], '%Y-%m-%d %H:%M:%S').strftime('%H')
                    hourly_breakdown[hour] = hourly_breakdown.get(hour, 0) + 1
                except:
                    pass

            total_amount += transaction.get('amount', 0)

        return {
            'total_declined_transactions': len(transactions),
            'total_declined_amount': total_amount,
            'average_declined_amount': total_amount / len(transactions) if transactions else 0,
            'decline_reasons': decline_reasons,
            'card_type_breakdown': card_types,
            'hourly_breakdown': hourly_breakdown
        }

    def _generate_comprehensive_summary(self, report_data: Dict[str, Any]) -> Dict[str, Any]:
        """Generate comprehensive summary for date range report"""
        summary = {
            'overview': {},
            'financial_summary': {},
            'operational_metrics': {}
        }

        # Transaction overview
        if report_data.get('transactions', {}).get('transactions'):
            transactions = report_data['transactions']['transactions']
            transaction_count = len(transactions)
            total_amount = sum(t.get('amount', 0) for t in transactions)

            summary['overview']['transactions'] = {
                'count': transaction_count,
                'total_amount': total_amount,
                'average_amount': total_amount / transaction_count if transaction_count else 0
            }

        # Settlement summary
        if report_data.get('settlements', {}).get('settlements'):
            settlements = report_data['settlements']['settlements']
            settlement_count = len(settlements)
            settled_amount = sum(s.get('total_amount', 0) for s in settlements)

            summary['financial_summary']['settlements'] = {
                'count': settlement_count,
                'total_amount': settled_amount
            }

        # Dispute summary
        if report_data.get('disputes', {}).get('disputes'):
            disputes = report_data['disputes']['disputes']
            dispute_count = len(disputes)
            dispute_amount = sum(d.get('case_amount', 0) for d in disputes)

            transaction_count = summary.get('overview', {}).get('transactions', {}).get('count', 0)
            dispute_rate = (dispute_count / transaction_count * 100) if transaction_count else 0

            summary['operational_metrics']['disputes'] = {
                'count': dispute_count,
                'total_amount': dispute_amount,
                'dispute_rate': dispute_rate
            }

        # Deposit summary
        if report_data.get('deposits', {}).get('deposits'):
            deposits = report_data['deposits']['deposits']
            deposit_count = len(deposits)
            deposit_amount = sum(d.get('deposit_amount', 0) for d in deposits)

            summary['financial_summary']['deposits'] = {
                'count': deposit_count,
                'total_amount': deposit_amount
            }

        return summary

    def _export_to_csv(self, transactions: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Export transaction data to CSV format"""
        csv_data = "Transaction ID,Timestamp,Amount,Currency,Status,Payment Method,Card Last Four,Auth Code,Reference Number\n"

        for transaction in transactions:
            csv_data += f"{transaction.get('transaction_id', '')},"
            csv_data += f"{transaction.get('timestamp', '')},"
            csv_data += f"{transaction.get('amount', '')},"
            csv_data += f"{transaction.get('currency', '')},"
            csv_data += f"{transaction.get('status', '')},"
            csv_data += f"{transaction.get('payment_method', '')},"
            csv_data += f"{transaction.get('card_last_four', '')},"
            csv_data += f"{transaction.get('auth_code', '')},"
            csv_data += f"{transaction.get('reference_number', '')}\n"

        return {
            'success': True,
            'data': csv_data,
            'format': 'csv',
            'filename': f'transactions_{datetime.now().strftime("%Y-%m-%d_%H-%M-%S")}.csv',
            'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        }

    def _export_to_xml(self, transactions: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Export transaction data to XML format"""
        xml_data = '<?xml version="1.0" encoding="UTF-8"?>\n<transactions>\n'

        for transaction in transactions:
            xml_data += '  <transaction>\n'
            xml_data += f'    <transaction_id>{transaction.get("transaction_id", "")}</transaction_id>\n'
            xml_data += f'    <timestamp>{transaction.get("timestamp", "")}</timestamp>\n'
            xml_data += f'    <amount>{transaction.get("amount", "")}</amount>\n'
            xml_data += f'    <currency>{transaction.get("currency", "")}</currency>\n'
            xml_data += f'    <status>{transaction.get("status", "")}</status>\n'
            xml_data += f'    <payment_method>{transaction.get("payment_method", "")}</payment_method>\n'
            xml_data += f'    <card_last_four>{transaction.get("card_last_four", "")}</card_last_four>\n'
            xml_data += f'    <auth_code>{transaction.get("auth_code", "")}</auth_code>\n'
            xml_data += f'    <reference_number>{transaction.get("reference_number", "")}</reference_number>\n'
            xml_data += '  </transaction>\n'

        xml_data += '</transactions>'

        return {
            'success': True,
            'data': xml_data,
            'format': 'xml',
            'filename': f'transactions_{datetime.now().strftime("%Y-%m-%d_%H-%M-%S")}.xml',
            'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        }


def get_sdk_config_status() -> Dict[str, Any]:
    """Get current SDK configuration status"""
    try:
        has_app_id = bool(os.getenv('GP_API_APP_ID'))
        has_app_key = bool(os.getenv('GP_API_APP_KEY'))
        is_configured = has_app_id and has_app_key

        return {
            'configured': is_configured,
            'has_app_id': has_app_id,
            'has_app_key': has_app_key,
            'environment': 'TEST' if is_configured else 'Not configured',
            'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        }
    except Exception as e:
        return {
            'configured': False,
            'error': str(e),
            'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        }


def validate_environment_config() -> Dict[str, Any]:
    """Validate environment configuration"""
    results = {
        'valid': True,
        'errors': [],
        'warnings': []
    }

    # Check required variables
    required = ['GP_API_APP_ID', 'GP_API_APP_KEY']
    for var in required:
        if not os.getenv(var):
            results['valid'] = False
            results['errors'].append(f"Missing required environment variable: {var}")

    # Check legacy variables and warn if present
    legacy = ['PUBLIC_API_KEY', 'SECRET_API_KEY']
    for var in legacy:
        if os.getenv(var):
            results['warnings'].append(f"Legacy variable {var} found. GP-API uses GP_API_APP_ID and GP_API_APP_KEY.")

    return results