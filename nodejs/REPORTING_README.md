# Global Payments Reporting Service - Node.js Implementation

This implementation provides a comprehensive reporting service for Global Payments transactions using the Global Payments Node.js SDK with GP-API credentials.

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
- `GET/POST /api/reports?action=search` - Search transactions
- `GET /api/reports?action=detail&transaction_id={id}` - Get transaction details
- `GET/POST /api/reports?action=settlement` - Settlement reports
- `GET/POST /api/reports?action=export&format={json|csv|xml}` - Export data
- `GET/POST /api/reports?action=summary` - Summary statistics
- `GET /api/reports?action=config` - Configuration status
- `GET /api/reports` - API documentation

## Files Structure

```
nodejs/
├── reports.js              # Express router for API endpoints
├── reporting-service.js    # Core reporting service class
├── server.js               # Main Express application
├── .env                    # Environment variables with GP-API credentials
├── package.json            # Node.js dependencies
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
npm install

# Start the server
npm start
```

## Usage Examples

### 1. Search Transactions

```bash
# Basic search
curl "http://localhost:8000/api/reports?action=search"

# Search with filters
curl "http://localhost:8000/api/reports?action=search&start_date=2025-08-30&page_size=10&status=CAPTURED"

# Search with pagination
curl "http://localhost:8000/api/reports?action=search&page=2&page_size=25"
```

### 2. Get Transaction Details

```bash
curl "http://localhost:8000/api/reports?action=detail&transaction_id=TRN_NivNUvEHgEMH8k0o7y5LoNfDRMdCBv_0c968a480487"
```

### 3. Export Data

```bash
# Export as JSON
curl "http://localhost:8000/api/reports?action=export&format=json&start_date=2025-08-30"

# Export as CSV
curl "http://localhost:8000/api/reports?action=export&format=csv&start_date=2025-08-30" > transactions.csv

# Export as XML
curl "http://localhost:8000/api/reports?action=export&format=xml&start_date=2025-08-30" > transactions.xml
```

### 4. Get Summary Statistics

```bash
curl "http://localhost:8000/api/reports?action=summary&start_date=2025-08-29&end_date=2025-08-30"
```

### 5. Check Configuration

```bash
curl "http://localhost:8000/api/reports?action=config"
```

## Response Formats

### Success Response
```json
{
    "success": true,
    "data": {
        // Response data here
    },
    "timestamp": "2025-09-30T12:48:45.123Z"
}
```

### Error Response
```json
{
    "success": false,
    "error": {
        "code": "API_ERROR",
        "message": "Error description",
        "timestamp": "2025-09-30T12:48:45.123Z"
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

```javascript
// Example: Process payment and verify in reporting system
async function processAndVerifyPayment(paymentToken, billingZip, amount) {
    try {
        // 1. Process the payment
        const paymentResponse = await processPayment(paymentToken, billingZip, amount);

        if (paymentResponse.success) {
            const transactionId = paymentResponse.data.transactionId;

            // 2. Wait for transaction to be available in reporting
            await new Promise(resolve => setTimeout(resolve, 3000));

            // 3. Verify transaction in reporting system
            const reportingResponse = await fetch(
                `http://localhost:8000/api/reports?action=detail&transaction_id=${transactionId}`
            );
            const reportingData = await reportingResponse.json();

            if (reportingData.success) {
                console.log('Payment processed and verified:', transactionId);
                return {
                    success: true,
                    transactionId,
                    paymentData: paymentResponse.data,
                    reportingData: reportingData.data
                };
            }
        }

        return paymentResponse;

    } catch (error) {
        console.error('Payment processing error:', error.message);
        return { success: false, error: error.message };
    }
}
```

## Production Considerations

- **Security**: Input validation and sanitization implemented
- **Error Handling**: Comprehensive error handling with proper HTTP status codes
- **Rate Limiting**: Consider implementing rate limiting for production use
- **Caching**: Consider adding caching for frequently accessed data
- **Logging**: Request/response logging can be enabled
- **HTTPS**: Ensure HTTPS is used in production environment

## Next Steps

The Node.js implementation is **production-ready** and fully functional. Ready for:
1. Manual testing and approval
2. Integration testing with the UI
3. Production deployment