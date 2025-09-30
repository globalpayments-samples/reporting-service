# Global Payments Reporting Service - Python Implementation

This implementation provides a comprehensive reporting service for Global Payments transactions using the Global Payments Python SDK with GP-API credentials.

## Features Implemented

### ✅ Core Reporting Capabilities
- **Transaction Search** - Search transactions with multiple filters and pagination
- **Transaction Details** - Retrieve detailed information for specific transactions
- **Settlement Reporting** - Generate settlement reports with summary statistics
- **Data Export** - Export transaction data in JSON, CSV, or XML format
- **Summary Statistics** - Calculate transaction statistics and breakdowns

### ✅ Filtering Options
- Date range filtering (start_date, end_date)
- Transaction ID search
- Payment type filtering (sale, refund, authorize, capture)
- Transaction status filtering
- Amount range filtering (min/max)
- Card last four digits filtering
- Pagination support

### ✅ API Endpoints
- `GET/POST /reports?action=search` - Search transactions
- `GET /reports?action=detail&transaction_id={id}` - Get transaction details
- `GET/POST /reports?action=settlement` - Settlement reports
- `GET/POST /reports?action=export&format={json|csv|xml}` - Export data
- `GET/POST /reports?action=summary` - Summary statistics
- `GET /reports?action=config` - Configuration status
- `GET /reports` - API documentation

## Files Structure

```
python/
├── reports.py              # Flask Blueprint for API endpoints
├── reporting_service.py    # Core reporting service class
├── server.py               # Main Flask application
├── .env                    # Environment variables with GP-API credentials
├── requirements.txt        # Python dependencies
└── REPORTING_README.md     # This documentation
```

## Configuration

The service uses GP-API credentials from the `.env` file:

```bash
GP_API_APP_ID=your_app_id_here
GP_API_APP_KEY=your_app_key_here
```

## Installation

```bash
# Install dependencies
pip install -r requirements.txt

# Start the server
python server.py
```

## Usage Examples

### 1. Search Transactions

```bash
# Basic search
curl "http://localhost:8000/reports?action=search"

# Search with filters
curl "http://localhost:8000/reports?action=search&start_date=2025-08-30&page_size=10&status=CAPTURED"

# Search with pagination
curl "http://localhost:8000/reports?action=search&page=2&page_size=25"
```

### 2. Get Transaction Details

```bash
curl "http://localhost:8000/reports?action=detail&transaction_id=TRN_NivNUvEHgEMH8k0o7y5LoNfDRMdCBv_0c968a480487"
```

### 3. Export Data

```bash
# Export as JSON
curl "http://localhost:8000/reports?action=export&format=json&start_date=2025-08-30"

# Export as CSV
curl "http://localhost:8000/reports?action=export&format=csv&start_date=2025-08-30" > transactions.csv

# Export as XML
curl "http://localhost:8000/reports?action=export&format=xml&start_date=2025-08-30" > transactions.xml
```

### 4. Get Summary Statistics

```bash
curl "http://localhost:8000/reports?action=summary&start_date=2025-08-29&end_date=2025-08-30"
```

### 5. Check Configuration

```bash
curl "http://localhost:8000/reports?action=config"
```

## Response Formats

### Success Response
```json
{
    "success": true,
    "data": {
        // Response data here
    },
    "timestamp": "2025-09-30T12:48:45.123456"
}
```

### Error Response
```json
{
    "success": false,
    "error": {
        "code": "API_ERROR",
        "message": "Error description",
        "timestamp": "2025-09-30T12:48:45.123456"
    }
}
```

## Available Filters

| Filter | Description | Example |
|--------|-------------|---------|
| `page` | Page number (default: 1) | `page=2` |
| `page_size` | Results per page (max: 100) | `page_size=50` |
| `start_date` | Start date (YYYY-MM-DD) | `start_date=2025-08-30` |
| `end_date` | End date (YYYY-MM-DD) | `end_date=2025-09-01` |
| `transaction_id` | Specific transaction ID | `transaction_id=TRN_123...` |
| `payment_type` | Payment type | `payment_type=sale` |
| `status` | Transaction status | `status=CAPTURED` |
| `amount_min` | Minimum amount | `amount_min=10.00` |
| `amount_max` | Maximum amount | `amount_max=100.00` |
| `card_last_four` | Last 4 digits | `card_last_four=1234` |

## Integration with Payment Processing

After processing payments, use the reporting service to track and analyze transactions:

```python
import time
import requests

def process_and_verify_payment(payment_token, billing_zip, amount):
    """Process payment and verify in reporting system"""
    try:
        # 1. Process the payment
        payment_response = process_payment(payment_token, billing_zip, amount)

        if payment_response['success']:
            transaction_id = payment_response['data']['transactionId']

            # 2. Wait for transaction to be available in reporting
            time.sleep(3)

            # 3. Verify transaction in reporting system
            reporting_response = requests.get(
                f"http://localhost:8000/reports?action=detail&transaction_id={transaction_id}"
            )
            reporting_data = reporting_response.json()

            if reporting_data['success']:
                print(f'Payment processed and verified: {transaction_id}')
                return {
                    'success': True,
                    'transaction_id': transaction_id,
                    'payment_data': payment_response['data'],
                    'reporting_data': reporting_data['data']
                }

        return payment_response

    except Exception as e:
        print(f'Payment processing error: {str(e)}')
        return {'success': False, 'error': str(e)}
```

## Production Considerations

- **Security**: Input validation and sanitization implemented
- **Error Handling**: Comprehensive error handling with proper HTTP status codes
- **Rate Limiting**: Consider implementing rate limiting for production use
- **Caching**: Consider adding caching for frequently accessed data
- **Logging**: Request/response logging can be enabled
- **HTTPS**: Ensure HTTPS is used in production environment

## Next Steps

The Python implementation is **production-ready** and fully functional. Ready for:
1. Manual testing and approval
2. Integration testing with the UI
3. Production deployment