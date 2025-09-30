"""
Global Payments Reporting API Endpoint

This script provides RESTful API endpoints for accessing Global Payments
reporting functionality including transaction search, details, settlement
reports, and data export capabilities.

Python version 3.7 or higher
"""

from flask import Blueprint, request, jsonify, Response
from datetime import datetime
from typing import Dict, Any

from reporting_service import (
    GlobalPaymentsReportingService,
    get_sdk_config_status,
    validate_environment_config
)
from globalpayments.api.entities.exceptions import ApiException


# Create a Blueprint for reporting routes
reports_bp = Blueprint('reports', __name__)


def get_request_params() -> Dict[str, Any]:
    """
    Get request parameters (supports both GET and POST)

    Returns:
        Combined request parameters
    """
    params = dict(request.args)

    if request.method == 'POST':
        if request.is_json:
            params.update(request.json or {})
        else:
            params.update(request.form.to_dict())

    return params


def validate_required_params(params: Dict[str, Any], required: list) -> None:
    """
    Validate required parameters

    Args:
        params: Request parameters
        required: Required parameter names

    Raises:
        ValueError: If required parameters are missing
    """
    for param in required:
        if param not in params or params[param] == '':
            raise ValueError(f"Missing required parameter: {param}")


def validate_date_format(date_str: str, date_format: str = '%Y-%m-%d') -> bool:
    """
    Validate date format

    Args:
        date_str: Date string
        date_format: Expected date format

    Returns:
        True if valid
    """
    try:
        datetime.strptime(date_str, date_format)
        return True
    except ValueError:
        return False


def handle_error(message: str, status_code: int = 400, error_code: str = 'API_ERROR') -> tuple:
    """
    Handle error responses

    Args:
        message: Error message
        status_code: HTTP status code
        error_code: Error code

    Returns:
        JSON response and status code tuple
    """
    return jsonify({
        'success': False,
        'error': {
            'code': error_code,
            'message': message,
            'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        }
    }), status_code


def filter_empty_params(params: Dict[str, Any]) -> Dict[str, Any]:
    """
    Remove empty parameters from dictionary

    Args:
        params: Parameters dictionary

    Returns:
        Filtered parameters
    """
    return {k: v for k, v in params.items() if v not in ('', None)}


@reports_bp.route('/', methods=['GET', 'POST', 'OPTIONS'])
@reports_bp.route('/reports', methods=['GET', 'POST', 'OPTIONS'])
def reports_handler():
    """Main reports endpoint handler"""
    # Handle preflight OPTIONS request
    if request.method == 'OPTIONS':
        return '', 200

    try:
        # Initialize the reporting service
        reporting_service = GlobalPaymentsReportingService()

        # Get request parameters
        params = get_request_params()
        action = params.get('action', '')

        # Route requests based on action parameter
        if action == 'search':
            # Search transactions
            filters = {
                'page': int(params.get('page', 1)),
                'page_size': min(int(params.get('page_size', 10)), 100),  # Limit page size
                'start_date': params.get('start_date', ''),
                'end_date': params.get('end_date', ''),
                'transaction_id': params.get('transaction_id', ''),
                'payment_type': params.get('payment_type', ''),
                'status': params.get('status', ''),
                'amount_min': params.get('amount_min', ''),
                'amount_max': params.get('amount_max', ''),
                'card_last_four': params.get('card_last_four', '')
            }

            # Validate date formats if provided
            if filters['start_date'] and not validate_date_format(filters['start_date']):
                raise ValueError('Invalid start_date format. Use YYYY-MM-DD.')

            if filters['end_date'] and not validate_date_format(filters['end_date']):
                raise ValueError('Invalid end_date format. Use YYYY-MM-DD.')

            # Remove empty filters
            filters = filter_empty_params(filters)

            result = reporting_service.search_transactions(filters)
            return jsonify(result)

        elif action == 'detail':
            # Get transaction details
            validate_required_params(params, ['transaction_id'])

            result = reporting_service.get_transaction_details(params['transaction_id'])
            return jsonify(result)

        elif action == 'settlement':
            # Get settlement report
            settlement_params = {
                'page': int(params.get('page', 1)),
                'page_size': min(int(params.get('page_size', 50)), 100),
                'start_date': params.get('start_date', ''),
                'end_date': params.get('end_date', '')
            }

            # Validate date formats if provided
            if settlement_params['start_date'] and not validate_date_format(settlement_params['start_date']):
                raise ValueError('Invalid start_date format. Use YYYY-MM-DD.')

            if settlement_params['end_date'] and not validate_date_format(settlement_params['end_date']):
                raise ValueError('Invalid end_date format. Use YYYY-MM-DD.')

            # Remove empty filters
            settlement_params = filter_empty_params(settlement_params)

            result = reporting_service.get_settlement_report(settlement_params)
            return jsonify(result)

        elif action == 'export':
            # Export transaction data
            export_filters = {
                'start_date': params.get('start_date', ''),
                'end_date': params.get('end_date', ''),
                'transaction_id': params.get('transaction_id', ''),
                'payment_type': params.get('payment_type', ''),
                'status': params.get('status', ''),
                'amount_min': params.get('amount_min', ''),
                'amount_max': params.get('amount_max', ''),
                'card_last_four': params.get('card_last_four', '')
            }

            export_format = params.get('format', 'json')
            if export_format not in ['json', 'csv', 'xml']:
                raise ValueError('Invalid format. Supported formats: json, csv, xml')

            # Validate date formats if provided
            if export_filters['start_date'] and not validate_date_format(export_filters['start_date']):
                raise ValueError('Invalid start_date format. Use YYYY-MM-DD.')

            if export_filters['end_date'] and not validate_date_format(export_filters['end_date']):
                raise ValueError('Invalid end_date format. Use YYYY-MM-DD.')

            # Remove empty filters
            export_filters = filter_empty_params(export_filters)

            result = reporting_service.export_transactions(export_filters, export_format)

            if export_format == 'csv':
                return Response(
                    result['data'],
                    mimetype='text/csv',
                    headers={'Content-Disposition': f'attachment; filename={result.get("filename", "transactions.csv")}'}
                )
            elif export_format == 'xml':
                return Response(
                    result['data'],
                    mimetype='application/xml',
                    headers={'Content-Disposition': f'attachment; filename={result.get("filename", "transactions.xml")}'}
                )

            return jsonify(result)

        elif action == 'summary':
            # Get summary statistics
            summary_params = {
                'start_date': params.get('start_date', ''),
                'end_date': params.get('end_date', '')
            }

            # Validate date formats if provided
            if summary_params['start_date'] and not validate_date_format(summary_params['start_date']):
                raise ValueError('Invalid start_date format. Use YYYY-MM-DD.')

            if summary_params['end_date'] and not validate_date_format(summary_params['end_date']):
                raise ValueError('Invalid end_date format. Use YYYY-MM-DD.')

            # Remove empty filters
            summary_params = filter_empty_params(summary_params)

            result = reporting_service.get_summary_stats(summary_params)
            return jsonify(result)

        elif action == 'disputes':
            # Get dispute report
            dispute_filters = {
                'page': int(params.get('page', 1)),
                'page_size': min(int(params.get('page_size', 10)), 100),
                'start_date': params.get('start_date', ''),
                'end_date': params.get('end_date', ''),
                'stage': params.get('stage', ''),
                'status': params.get('status', '')
            }

            # Validate date formats if provided
            if dispute_filters['start_date'] and not validate_date_format(dispute_filters['start_date']):
                raise ValueError('Invalid start_date format. Use YYYY-MM-DD.')

            if dispute_filters['end_date'] and not validate_date_format(dispute_filters['end_date']):
                raise ValueError('Invalid end_date format. Use YYYY-MM-DD.')

            # Remove empty filters
            dispute_filters = filter_empty_params(dispute_filters)

            result = reporting_service.get_dispute_report(dispute_filters)
            return jsonify(result)

        elif action == 'dispute_detail':
            # Get dispute details
            validate_required_params(params, ['dispute_id'])

            result = reporting_service.get_dispute_details(params['dispute_id'])
            return jsonify(result)

        elif action == 'deposits':
            # Get deposit report
            deposit_filters = {
                'page': int(params.get('page', 1)),
                'page_size': min(int(params.get('page_size', 10)), 100),
                'start_date': params.get('start_date', ''),
                'end_date': params.get('end_date', ''),
                'deposit_id': params.get('deposit_id', ''),
                'status': params.get('status', '')
            }

            # Validate date formats if provided
            if deposit_filters['start_date'] and not validate_date_format(deposit_filters['start_date']):
                raise ValueError('Invalid start_date format. Use YYYY-MM-DD.')

            if deposit_filters['end_date'] and not validate_date_format(deposit_filters['end_date']):
                raise ValueError('Invalid end_date format. Use YYYY-MM-DD.')

            # Remove empty filters
            deposit_filters = filter_empty_params(deposit_filters)

            result = reporting_service.get_deposit_report(deposit_filters)
            return jsonify(result)

        elif action == 'deposit_detail':
            # Get deposit details
            validate_required_params(params, ['deposit_id'])

            result = reporting_service.get_deposit_details(params['deposit_id'])
            return jsonify(result)

        elif action == 'batches':
            # Get batch report
            batch_filters = {
                'start_date': params.get('start_date', ''),
                'end_date': params.get('end_date', '')
            }

            # Validate date formats if provided
            if batch_filters['start_date'] and not validate_date_format(batch_filters['start_date']):
                raise ValueError('Invalid start_date format. Use YYYY-MM-DD.')

            if batch_filters['end_date'] and not validate_date_format(batch_filters['end_date']):
                raise ValueError('Invalid end_date format. Use YYYY-MM-DD.')

            # Remove empty filters
            batch_filters = filter_empty_params(batch_filters)

            result = reporting_service.get_batch_report(batch_filters)
            return jsonify(result)

        elif action == 'declines':
            # Get declined transactions report
            decline_filters = {
                'page': int(params.get('page', 1)),
                'page_size': min(int(params.get('page_size', 10)), 100),
                'start_date': params.get('start_date', ''),
                'end_date': params.get('end_date', ''),
                'payment_type': params.get('payment_type', ''),
                'amount_min': params.get('amount_min', ''),
                'amount_max': params.get('amount_max', ''),
                'card_last_four': params.get('card_last_four', '')
            }

            # Validate date formats if provided
            if decline_filters['start_date'] and not validate_date_format(decline_filters['start_date']):
                raise ValueError('Invalid start_date format. Use YYYY-MM-DD.')

            if decline_filters['end_date'] and not validate_date_format(decline_filters['end_date']):
                raise ValueError('Invalid end_date format. Use YYYY-MM-DD.')

            # Remove empty filters
            decline_filters = filter_empty_params(decline_filters)

            result = reporting_service.get_declined_transactions_report(decline_filters)
            return jsonify(result)

        elif action == 'date_range':
            # Get comprehensive date range report
            date_range_params = {
                'start_date': params.get('start_date', ''),
                'end_date': params.get('end_date', ''),
                'transaction_limit': min(int(params.get('transaction_limit', 100)), 1000),
                'settlement_limit': min(int(params.get('settlement_limit', 50)), 500),
                'dispute_limit': min(int(params.get('dispute_limit', 25)), 100),
                'deposit_limit': min(int(params.get('deposit_limit', 25)), 100)
            }

            # Validate date formats if provided
            if date_range_params['start_date'] and not validate_date_format(date_range_params['start_date']):
                raise ValueError('Invalid start_date format. Use YYYY-MM-DD.')

            if date_range_params['end_date'] and not validate_date_format(date_range_params['end_date']):
                raise ValueError('Invalid end_date format. Use YYYY-MM-DD.')

            # Remove empty filters
            date_range_params = filter_empty_params(date_range_params)

            result = reporting_service.get_date_range_report(date_range_params)
            return jsonify(result)

        elif action == 'config':
            # Get configuration status
            config_status = get_sdk_config_status()
            env_validation = validate_environment_config()

            return jsonify({
                'success': True,
                'data': {
                    'sdk_status': config_status,
                    'environment_validation': env_validation,
                    'api_endpoints': {
                        'search': '/reports?action=search',
                        'detail': '/reports?action=detail&transaction_id={id}',
                        'settlement': '/reports?action=settlement',
                        'disputes': '/reports?action=disputes',
                        'dispute_detail': '/reports?action=dispute_detail&dispute_id={id}',
                        'deposits': '/reports?action=deposits',
                        'deposit_detail': '/reports?action=deposit_detail&deposit_id={id}',
                        'batches': '/reports?action=batches',
                        'declines': '/reports?action=declines',
                        'date_range': '/reports?action=date_range',
                        'export': '/reports?action=export&format={json|csv|xml}',
                        'summary': '/reports?action=summary',
                        'config': '/reports?action=config'
                    }
                },
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            })

        elif action == '':
            # Default action - show API documentation
            return jsonify({
                'success': True,
                'data': {
                    'name': 'Global Payments Reporting API',
                    'version': '1.0.0',
                    'description': 'RESTful API for Global Payments transaction reporting and analytics',
                    'endpoints': {
                        'search': {
                            'url': '/reports?action=search',
                            'method': 'GET/POST',
                            'description': 'Search transactions with filters and pagination',
                            'parameters': {
                                'page': 'Page number (default: 1)',
                                'page_size': 'Results per page (default: 10, max: 100)',
                                'start_date': 'Start date (YYYY-MM-DD)',
                                'end_date': 'End date (YYYY-MM-DD)',
                                'transaction_id': 'Specific transaction ID',
                                'payment_type': 'Payment type (sale, refund, authorize, capture)',
                                'status': 'Transaction status',
                                'amount_min': 'Minimum amount',
                                'amount_max': 'Maximum amount',
                                'card_last_four': 'Last 4 digits of card'
                            }
                        },
                        'detail': {
                            'url': '/reports?action=detail&transaction_id={id}',
                            'method': 'GET',
                            'description': 'Get detailed transaction information',
                            'parameters': {
                                'transaction_id': 'Transaction ID (required)'
                            }
                        },
                        'settlement': {
                            'url': '/reports?action=settlement',
                            'method': 'GET/POST',
                            'description': 'Get settlement report',
                            'parameters': {
                                'page': 'Page number (default: 1)',
                                'page_size': 'Results per page (default: 50, max: 100)',
                                'start_date': 'Start date (YYYY-MM-DD)',
                                'end_date': 'End date (YYYY-MM-DD)'
                            }
                        },
                        'export': {
                            'url': '/reports?action=export&format={json|csv|xml}',
                            'method': 'GET/POST',
                            'description': 'Export transaction data',
                            'parameters': {
                                'format': 'Export format (json, csv, or xml)',
                                '...filters': 'Same filters as search endpoint'
                            }
                        },
                        'summary': {
                            'url': '/reports?action=summary',
                            'method': 'GET/POST',
                            'description': 'Get summary statistics',
                            'parameters': {
                                'start_date': 'Start date (YYYY-MM-DD)',
                                'end_date': 'End date (YYYY-MM-DD)'
                            }
                        },
                        'disputes': {
                            'url': '/reports?action=disputes',
                            'method': 'GET/POST',
                            'description': 'Get dispute report',
                            'parameters': {
                                'page': 'Page number (default: 1)',
                                'page_size': 'Results per page (default: 10, max: 100)',
                                'start_date': 'Start date (YYYY-MM-DD)',
                                'end_date': 'End date (YYYY-MM-DD)',
                                'stage': 'Dispute stage',
                                'status': 'Dispute status'
                            }
                        },
                        'deposits': {
                            'url': '/reports?action=deposits',
                            'method': 'GET/POST',
                            'description': 'Get deposit report',
                            'parameters': {
                                'page': 'Page number (default: 1)',
                                'page_size': 'Results per page (default: 10, max: 100)',
                                'start_date': 'Start date (YYYY-MM-DD)',
                                'end_date': 'End date (YYYY-MM-DD)',
                                'deposit_id': 'Specific deposit ID',
                                'status': 'Deposit status'
                            }
                        },
                        'batches': {
                            'url': '/reports?action=batches',
                            'method': 'GET/POST',
                            'description': 'Get batch report',
                            'parameters': {
                                'start_date': 'Start date (YYYY-MM-DD)',
                                'end_date': 'End date (YYYY-MM-DD)'
                            }
                        },
                        'declines': {
                            'url': '/reports?action=declines',
                            'method': 'GET/POST',
                            'description': 'Get declined transactions report',
                            'parameters': {
                                'page': 'Page number (default: 1)',
                                'page_size': 'Results per page (default: 10, max: 100)',
                                'start_date': 'Start date (YYYY-MM-DD)',
                                'end_date': 'End date (YYYY-MM-DD)',
                                'payment_type': 'Payment type',
                                'amount_min': 'Minimum amount',
                                'amount_max': 'Maximum amount',
                                'card_last_four': 'Last 4 digits of card'
                            }
                        },
                        'date_range': {
                            'url': '/reports?action=date_range',
                            'method': 'GET/POST',
                            'description': 'Get comprehensive date range report',
                            'parameters': {
                                'start_date': 'Start date (YYYY-MM-DD)',
                                'end_date': 'End date (YYYY-MM-DD)',
                                'transaction_limit': 'Max transactions (default: 100, max: 1000)',
                                'settlement_limit': 'Max settlements (default: 50, max: 500)',
                                'dispute_limit': 'Max disputes (default: 25, max: 100)',
                                'deposit_limit': 'Max deposits (default: 25, max: 100)'
                            }
                        },
                        'config': {
                            'url': '/reports?action=config',
                            'method': 'GET',
                            'description': 'Get API configuration and status'
                        }
                    }
                },
                'timestamp': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            })

        else:
            raise ValueError(f"Invalid action: {action}")

    except ValueError as e:
        return handle_error(str(e), 400, 'VALIDATION_ERROR')
    except ApiException as e:
        return handle_error(str(e), 400, 'API_ERROR')
    except Exception as e:
        return handle_error(f'An unexpected error occurred: {str(e)}', 500, 'INTERNAL_ERROR')