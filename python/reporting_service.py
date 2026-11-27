"""
Global Payments Reporting Service

This service class provides comprehensive reporting functionality for
Global Payments transactions including search, filtering, and data export.

Python version 3.11 or higher
"""

import json
import logging
import os
from datetime import datetime
from enum import Enum
from urllib.parse import quote
from xml.sax.saxutils import escape
from typing import Dict, List, Any, Optional
from dotenv import load_dotenv

from globalpayments.api import GpApiConfig, ServicesContainer
from globalpayments.api.builders import TransactionReportBuilder
from globalpayments.api.entities.gp_api.gp_api_request import GpApiRequest
from globalpayments.api.mappings.gpapi_mapping import GpApiMapping
from globalpayments.api.services import ReportingService
from globalpayments.api.entities.enums import (
    Environment,
    CardChannel,
    PaymentType,
    ReportType,
    SearchCriteria
)
from globalpayments.api.entities.exceptions import ApiException

logger = logging.getLogger(__name__)

# The Python SDK does not pick the GP API URL from config.environment,
# so the sandbox URL is set on the config directly
GP_API_SANDBOX_URL = 'https://apis.sandbox.globalpay.com/ucp'


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
        except (ValueError, ApiException) as e:
            # Missing settings and SDK errors are reported to the caller as they are
            raise ValueError(f'Failed to configure SDK: {e}')
        except Exception:
            logger.exception('Failed to configure the SDK')
            raise ValueError('Failed to configure SDK')

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
        config.environment = Environment.Test  # Change to Production for live
        config.service_url = GP_API_SANDBOX_URL  # Use https://apis.globalpay.com/ucp for live
        config.channel = CardChannel.CARD_NOT_PRESENT

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

            # The SDK's transaction search sends an empty amount= parameter,
            # which GP API answers with SYSTEM_ERROR_DOWNSTREAM, so the list is
            # read with the SDK's connector and mapping instead (see _gp_api_list)
            query = {
                'page': page,
                'page_size': page_size,
                'id': filters.get('transaction_id'),
                'from_time_created': filters.get('start_date'),
                'to_time_created': filters.get('end_date'),
                'number_last4': filters.get('card_last_four')
            }

            # Apply payment type filter
            if filters.get('payment_type'):
                payment_type = self._map_payment_type(filters['payment_type'])
                if not payment_type:
                    raise ValueError('Invalid payment_type. Supported values: sale, refund')
                query['type'] = payment_type.value

            response = self._gp_api_list(
                GpApiRequest.TRANSACTION_ENDPOINT, query, ReportType.FindTransactionsPaged
            )

            transactions = self._format_transaction_list(response.result or [])

            # GP API only filters on an exact amount, so the range is applied here
            if filters.get('amount_min'):
                amount_min = float(filters['amount_min'])
                transactions = [t for t in transactions if t['amount'] >= amount_min]

            if filters.get('amount_max'):
                amount_max = float(filters['amount_max'])
                transactions = [t for t in transactions if t['amount'] <= amount_max]

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
                        'original_total_count': response.total_record_count or 0
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
            # The SDK's mapping leaves out the gateway result code, so the
            # transaction is read with the SDK's connector and mapping
            raw = self._gp_api_get(f'{GpApiRequest.TRANSACTION_ENDPOINT}/{quote(transaction_id, safe="")}')
            response = GpApiMapping.map_report_response(raw, ReportType.TransactionDetail)

            details = self._format_transaction_details(response)
            details['gateway_response_code'] = self._text((raw.get('payment_method') or {}).get('result'))

            return {
                'success': True,
                'data': details,
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

            report_builder = TransactionReportBuilder(
                ReportType.FindSettlementTransactionsPaged
            ).with_paging(page, page_size)

            # Apply date range
            self._apply_date_range(report_builder, params)

            response = report_builder.execute()

            settlements = self._format_settlement_list(response.result or [])

            return {
                'success': True,
                'data': {
                    'settlements': settlements,
                    'summary': self._generate_settlement_summary(settlements),
                    'pagination': {
                        'page': page,
                        'page_size': page_size,
                        'total_count': response.total_record_count or 0
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

            # The SDK sends the dispute dates as from/to, which GP API rejects
            # (INVALID_REQUEST_DATA), so the list is read like the transactions
            query = {
                'page': page,
                'page_size': page_size,
                'from_stage_time_created': filters.get('start_date'),
                'to_stage_time_created': filters.get('end_date'),
                'stage': (filters.get('stage') or '').upper(),
                'status': (filters.get('status') or '').upper()
            }

            raw = self._gp_api_get(GpApiRequest.DISPUTES_ENDPOINT, query)
            response = GpApiMapping.map_report_response(raw, ReportType.FindDisputesPaged)
            for dispute, row in zip(response.result or [], raw.get('disputes') or []):
                self._add_dispute_reason(dispute, row)

            return {
                'success': True,
                'data': {
                    'disputes': self._format_dispute_list(response.result or []),
                    'pagination': {
                        'page': page,
                        'page_size': page_size,
                        'total_count': response.total_record_count or 0
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
            # The SDK's mapping leaves out the reason code and description, so
            # the dispute is read with the SDK's connector and mapping
            raw = self._gp_api_get(f'{GpApiRequest.DISPUTES_ENDPOINT}/{quote(dispute_id, safe="")}')
            response = GpApiMapping.map_report_response(raw, ReportType.DisputeDetail)
            self._add_dispute_reason(response, raw)

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

            report_builder = TransactionReportBuilder(
                ReportType.FindDepositsPaged
            ).with_paging(page, page_size)

            # Apply date range filters
            self._apply_date_range(report_builder, filters)

            # Apply deposit ID filter
            if filters.get('deposit_id'):
                report_builder.where(SearchCriteria.DepositId, filters['deposit_id'])

            # Apply status filter
            if filters.get('status'):
                report_builder.where(SearchCriteria.DepositStatus, filters['status'].upper())

            # Execute the search
            response = report_builder.execute()

            return {
                'success': True,
                'data': {
                    'deposits': self._format_deposit_list(response.result or []),
                    'pagination': {
                        'page': page,
                        'page_size': page_size,
                        'total_count': response.total_record_count or 0
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
            # The SDK's mapping leaves out the bank account, so the deposit is
            # read with the SDK's connector and mapping
            raw = self._gp_api_get(f'{GpApiRequest.DEPOSITS_ENDPOINT}/{quote(deposit_id, safe="")}')
            response = GpApiMapping.map_report_response(raw, ReportType.DepositDetail)

            details = self._format_deposit_details(response)
            details['bank_account']['masked_account_number'] = self._text(
                (raw.get('bank_transfer') or {}).get('masked_account_number_last4')
            )

            return {
                'success': True,
                'data': details,
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

        # GP API has no batch report (batch_detail() is a Portico report), so
        # this answers with the same API error the Node.js and PHP services give
        raise ApiException('Batch report generation failed: batch reports are not available through GP API')

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
            except ApiException as e:
                report['data']['transactions'] = {'error': f'Transactions not available: {str(e)}'}
            except Exception:
                logger.exception('Transactions not available for the date range report')
                report['data']['transactions'] = {'error': 'Transactions not available'}

            # Get settlements for the period
            try:
                settlement_result = self.get_settlement_report({
                    'start_date': start_date,
                    'end_date': end_date,
                    'page_size': params.get('settlement_limit', 50)
                })
                if settlement_result['success']:
                    report['data']['settlements'] = settlement_result['data']
            except ApiException as e:
                report['data']['settlements'] = {'error': f'Settlements not available: {str(e)}'}
            except Exception:
                logger.exception('Settlements not available for the date range report')
                report['data']['settlements'] = {'error': 'Settlements not available'}

            # Get disputes for the period
            try:
                dispute_result = self.get_dispute_report({
                    'start_date': start_date,
                    'end_date': end_date,
                    'page_size': params.get('dispute_limit', 25)
                })
                if dispute_result['success']:
                    report['data']['disputes'] = dispute_result['data']
            except ApiException as e:
                report['data']['disputes'] = {'error': f'Disputes not available: {str(e)}'}
            except Exception:
                logger.exception('Disputes not available for the date range report')
                report['data']['disputes'] = {'error': 'Disputes not available'}

            # Get deposits for the period
            try:
                deposit_result = self.get_deposit_report({
                    'start_date': start_date,
                    'end_date': end_date,
                    'page_size': params.get('deposit_limit', 25)
                })
                if deposit_result['success']:
                    report['data']['deposits'] = deposit_result['data']
            except ApiException as e:
                report['data']['deposits'] = {'error': f'Deposits not available: {str(e)}'}
            except Exception:
                logger.exception('Deposits not available for the date range report')
                report['data']['deposits'] = {'error': 'Deposits not available'}

            # Generate comprehensive summary
            report['data']['summary'] = self._generate_comprehensive_summary(report['data'])

            return report

        except ApiException as e:
            return {
                'success': False,
                'error': f'Failed to generate date range report: {str(e)}',
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }
        except Exception:
            logger.exception('Failed to generate date range report')
            return {
                'success': False,
                'error': 'Failed to generate date range report',
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

        except ApiException as e:
            return {
                'success': False,
                'error': f'Failed to generate summary statistics: {str(e)}',
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }
        except Exception:
            logger.exception('Failed to generate summary statistics')
            return {
                'success': False,
                'error': 'Failed to generate summary statistics',
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            }

    # Helper methods for mapping and formatting

    @staticmethod
    def _apply_date_range(report_builder: TransactionReportBuilder, filters: Dict[str, Any]) -> None:
        """Pass the start and end dates to the SDK as search criteria"""
        # with_start_date()/with_end_date() are not read by the GP API request
        # builder, only the StartDate/EndDate search criteria are
        if filters.get('start_date'):
            start_date = datetime.strptime(filters['start_date'], '%Y-%m-%d')
            report_builder.where(SearchCriteria.StartDate, start_date)

        if filters.get('end_date'):
            end_date = datetime.strptime(filters['end_date'], '%Y-%m-%d')
            report_builder.where(SearchCriteria.EndDate, end_date)

    @staticmethod
    def _gp_api_get(endpoint: str, query: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """
        GET a GP API endpoint through the SDK's configured connector, which
        signs in and raises the usual SDK errors. Empty parameters are left out.
        """
        connector = ServicesContainer.instance().get_client('default')
        params = {k: v for k, v in (query or {}).items() if v not in (None, '')}
        raw = connector.do_transaction('GET', endpoint, None, params or None)
        return json.loads(raw or '{}')

    @classmethod
    def _gp_api_list(cls, endpoint: str, query: Dict[str, Any], report_type: ReportType) -> Any:
        """GET a GP API list endpoint and map the answer with the SDK's own report mapping"""
        return GpApiMapping.map_report_response(cls._gp_api_get(endpoint, query), report_type)

    @staticmethod
    def _add_dispute_reason(dispute: Any, row: Dict[str, Any]) -> None:
        """The SDK's dispute mapping leaves out the reason, it is taken from the response"""
        dispute.reason_code = row.get('reason_code')
        dispute.reason = row.get('reason_description')

    @staticmethod
    def _text(value: Any) -> str:
        """Turn an SDK value (enum, datetime or None) into a plain string"""
        if value is None:
            return ''
        if isinstance(value, Enum):
            return str(value.value)
        if isinstance(value, datetime):
            return value.isoformat()
        return str(value)

    @staticmethod
    def _amount(value: Any) -> float:
        """The SDK returns amounts as strings in major units, e.g. '19.99'"""
        try:
            return float(value) if value not in (None, '') else 0
        except (TypeError, ValueError):
            return 0

    def _map_payment_type(self, payment_type: str) -> Optional[PaymentType]:
        """Map payment type string to PaymentType enum"""
        mapping = {
            'sale': PaymentType.SALE,
            'refund': PaymentType.REFUND
        }
        return mapping.get(payment_type.lower())

    def _format_transaction_list(self, transactions: List[Any]) -> List[Dict[str, Any]]:
        """Format transaction list for API response"""
        return [
            {
                'transaction_id': self._text(getattr(t, 'transaction_id', None)),
                'timestamp': self._text(getattr(t, 'transaction_date', None)),
                'amount': self._amount(getattr(t, 'amount', None)),
                'currency': getattr(t, 'currency', None) or 'USD',
                'status': self._text(getattr(t, 'transaction_status', None)),
                'payment_method': self._text(getattr(t, 'payment_type', None)),
                'card_last_four': self._text(getattr(t, 'masked_card_number', None)),
                'auth_code': self._text(getattr(t, 'auth_code', None)),
                'reference_number': self._text(getattr(t, 'reference_number', None))
            }
            for t in transactions
        ]

    def _format_transaction_details(self, transaction: Any) -> Dict[str, Any]:
        """Format detailed transaction information"""
        return {
            'transaction_id': self._text(getattr(transaction, 'transaction_id', None)),
            'timestamp': self._text(getattr(transaction, 'transaction_date', None)),
            'amount': self._amount(getattr(transaction, 'amount', None)),
            'currency': getattr(transaction, 'currency', None) or 'USD',
            'status': self._text(getattr(transaction, 'transaction_status', None)),
            'payment_method': self._text(getattr(transaction, 'payment_type', None)),
            'card_details': {
                'masked_number': self._text(getattr(transaction, 'masked_card_number', None)),
                'card_type': self._text(getattr(transaction, 'card_type', None)),
                'entry_mode': self._text(getattr(transaction, 'entry_mode', None))
            },
            'auth_code': self._text(getattr(transaction, 'auth_code', None)),
            'reference_number': self._text(getattr(transaction, 'reference_number', None)),
            'gateway_response_code': self._text(getattr(transaction, 'gateway_response_code', None)),
            'gateway_response_message': self._text(getattr(transaction, 'gateway_response_message', None))
        }

    def _format_settlement_list(self, settlements: List[Any]) -> List[Dict[str, Any]]:
        """Format settlement list for API response"""
        # GP API returns one settled transaction per row, not settlement totals
        return [
            {
                'settlement_id': self._text(getattr(s, 'transaction_id', None)),
                'settlement_date': self._text(
                    getattr(s, 'deposit_time_created', None) or getattr(s, 'transaction_date', None)
                ),
                'transaction_count': 1,
                'total_amount': self._amount(getattr(s, 'amount', None)),
                'currency': getattr(s, 'currency', None) or 'USD',
                'status': self._text(getattr(s, 'transaction_status', None))
            }
            for s in settlements
        ]

    def _generate_settlement_summary(self, settlements: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Generate settlement summary statistics"""
        total_amount = sum(s['total_amount'] for s in settlements)
        total_transactions = sum(s['transaction_count'] for s in settlements)

        return {
            'total_settlements': len(settlements),
            'total_amount': total_amount,
            'total_transactions': total_transactions,
            'average_settlement_amount': total_amount / len(settlements) if settlements else 0
        }

    def _format_dispute(self, d: Any) -> Dict[str, Any]:
        """Fields shared by the dispute list and the dispute detail"""
        return {
            'dispute_id': self._text(getattr(d, 'case_id', None)),
            'transaction_id': self._text(getattr(d, 'transaction_id', None)),
            'case_number': self._text(getattr(d, 'case_number', None)),
            'dispute_stage': self._text(getattr(d, 'case_stage', None)),
            'dispute_status': self._text(getattr(d, 'case_status', None)),
            'case_amount': self._amount(getattr(d, 'case_amount', None)),
            'currency': getattr(d, 'case_currency', None) or 'USD',
            'reason_code': self._text(getattr(d, 'reason_code', None)),
            'reason_description': self._text(getattr(d, 'reason', None)),
            'case_time': self._text(getattr(d, 'case_id_time', None)),
            'last_adjustment_time': self._text(getattr(d, 'last_adjustment_time', None))
        }

    def _format_dispute_list(self, disputes: List[Any]) -> List[Dict[str, Any]]:
        """Format dispute list for API response"""
        return [self._format_dispute(d) for d in disputes]

    def _format_dispute_details(self, dispute: Any) -> Dict[str, Any]:
        """Format detailed dispute information"""
        return {
            **self._format_dispute(dispute),
            'case_description': self._text(getattr(dispute, 'case_description', None)),
            'documents': [
                {'id': self._text(doc.id), 'type': self._text(getattr(doc, 'type', None))}
                for doc in (getattr(dispute, 'documents', None) or [])
            ],
            'transaction_details': {
                'amount': self._amount(getattr(dispute, 'transaction_amount', None)),
                'currency': getattr(dispute, 'transaction_currency', None) or 'USD',
                'masked_card_number': self._text(getattr(dispute, 'transaction_masked_card_number', None)),
                'arn': self._text(getattr(dispute, 'transaction_arn', None))
            }
        }

    def _format_deposit(self, d: Any) -> Dict[str, Any]:
        """Fields shared by the deposit list and the deposit detail"""
        return {
            'deposit_id': self._text(getattr(d, 'deposit_id', None)),
            'deposit_date': self._text(getattr(d, 'deposit_date', None)),
            'deposit_reference': self._text(getattr(d, 'reference', None)),
            'deposit_status': self._text(getattr(d, 'status', None)),
            'deposit_amount': self._amount(getattr(d, 'amount', None)),
            'currency': getattr(d, 'currency', None) or 'USD',
            'merchant_number': self._text(getattr(d, 'merchant_id', None)),
            'merchant_hierarchy': self._text(getattr(d, 'merchant_hierarchy', None))
        }

    def _format_deposit_list(self, deposits: List[Any]) -> List[Dict[str, Any]]:
        """Format deposit list for API response"""
        return [
            {
                **self._format_deposit(d),
                'sales_count': getattr(d, 'sales_total_count', None) or 0,
                'sales_amount': self._amount(getattr(d, 'sales_total_amount', None)),
                'refunds_count': getattr(d, 'refunds_total_count', None) or 0,
                'refunds_amount': self._amount(getattr(d, 'refunds_total_amount', None))
            }
            for d in deposits
        ]

    def _format_deposit_details(self, deposit: Any) -> Dict[str, Any]:
        """Format detailed deposit information"""
        return {
            **self._format_deposit(deposit),
            'bank_account': {
                'masked_account_number': self._text(getattr(deposit, 'account_number', None)),
                'bank_name': self._text(getattr(deposit, 'bank_name', None))
            },
            'transaction_summary': {
                'sales_count': getattr(deposit, 'sales_total_count', None) or 0,
                'sales_amount': self._amount(getattr(deposit, 'sales_total_amount', None)),
                'refunds_count': getattr(deposit, 'refunds_total_count', None) or 0,
                'refunds_amount': self._amount(getattr(deposit, 'refunds_total_amount', None)),
                'chargebacks_count': getattr(deposit, 'chargeback_total_count', None) or 0,
                'chargebacks_amount': self._amount(getattr(deposit, 'chargeback_total_amount', None)),
                'adjustments_count': getattr(deposit, 'adjustment_total_count', None) or 0,
                'adjustments_amount': self._amount(getattr(deposit, 'adjustment_total_amount', None))
            }
        }

    def _calculate_summary_stats(self, transactions: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Calculate summary statistics from transaction data"""
        total_amount = sum(t.get('amount', 0) for t in transactions)

        status_counts = {}
        payment_type_counts = {}

        for transaction in transactions:
            status = transaction.get('status') or 'unknown'
            status_counts[status] = status_counts.get(status, 0) + 1

            payment_type = transaction.get('payment_method') or 'unknown'
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
                    hour = datetime.fromisoformat(str(timestamp)).strftime('%H')
                    hourly_breakdown[hour] = hourly_breakdown.get(hour, 0) + 1
                except ValueError:
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
            csv_data += ','.join([
                transaction.get('transaction_id', ''),
                transaction.get('timestamp', ''),
                f"{transaction.get('amount', 0):.2f}",
                transaction.get('currency', ''),
                transaction.get('status', ''),
                transaction.get('payment_method', ''),
                transaction.get('card_last_four', ''),
                transaction.get('auth_code', ''),
                transaction.get('reference_number', '')
            ]) + '\n'

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

        fields = ['transaction_id', 'timestamp', 'amount', 'currency', 'status',
                  'payment_method', 'card_last_four', 'auth_code', 'reference_number']
        for transaction in transactions:
            xml_data += '  <transaction>\n'
            for field in fields:
                value = transaction.get(field, '')
                if field == 'amount':
                    value = f'{value or 0:.2f}'
                xml_data += f'    <{field}>{escape(str(value))}</{field}>\n'
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
    except Exception:
        logger.exception('Failed to read the SDK configuration status')
        return {
            'configured': False,
            'error': 'Failed to read the SDK configuration status',
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