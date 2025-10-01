# Global Payments Reporting API - PHP

A comprehensive reporting service for Global Payments transactions with search, filtering, analytics, and data export capabilities.

## Quick Start

### 1. Configure Environment

Add to your `.env` file:

```properties
GP_API_APP_ID=your_app_id_here
GP_API_APP_KEY=your_app_key_here
```

### 2. Start the Server

```bash
cd php
php -S localhost:8000
```

The API will be available at `http://localhost:8000/reports.php`

### 3. Verify Configuration

```bash
curl "http://localhost:8000/reports.php?action=config"
```

## API Endpoints

All endpoints use `/reports.php?action={action_name}` and support both GET and POST requests.

### Search Transactions

Search and filter transactions with pagination.

```bash
curl "http://localhost:8000/reports.php?action=search&start_date=2025-09-01&end_date=2025-09-30&page_size=20"
```

**Parameters**:
- `page` - Page number (default: 1)
- `page_size` - Results per page (default: 10, max: 100)
- `start_date` - Start date (YYYY-MM-DD)
- `end_date` - End date (YYYY-MM-DD)
- `transaction_id` - Specific transaction ID
- `payment_type` - Payment type filter
- `status` - Transaction status (e.g., CAPTURED, DECLINED)
- `amount_min` - Minimum amount
- `amount_max` - Maximum amount
- `card_last_four` - Last 4 digits of card

### Get Transaction Details

Retrieve detailed information for a specific transaction.

```bash
curl "http://localhost:8000/reports.php?action=detail&transaction_id=TRN_123456"
```

**Required**: `transaction_id`

### Settlement Report

Get settlement information for a date range.

```bash
curl "http://localhost:8000/reports.php?action=settlement&start_date=2025-09-01&end_date=2025-09-30"
```

**Parameters**:
- `page`, `page_size` - Pagination
- `start_date`, `end_date` - Date range

### Export Transactions

Export transaction data in JSON or CSV format.

**CSV Export**:
```bash
curl "http://localhost:8000/reports.php?action=export&format=csv&start_date=2025-09-01&end_date=2025-09-30" -o transactions.csv
```

**JSON Export**:
```bash
curl "http://localhost:8000/reports.php?action=export&format=json&start_date=2025-09-01&end_date=2025-09-30"
```

**Parameters**:
- `format` - Export format: `json` or `csv` (required)
- Plus all search filters

### Summary Statistics

Get aggregate statistics for transactions.

```bash
curl "http://localhost:8000/reports.php?action=summary&start_date=2025-09-01&end_date=2025-09-30"
```

**Returns**:
- Total transaction count
- Total amount
- Average amount
- Status breakdown
- Payment type breakdown

### Declined Transactions Report

Get declined transactions with analysis.

```bash
curl "http://localhost:8000/reports.php?action=declines&start_date=2025-09-01&end_date=2025-09-30"
```

**Returns** transaction data plus decline analysis:
- Decline reasons breakdown
- Card type breakdown
- Hourly decline patterns

## Using POST with JSON

All endpoints support POST requests:

```bash
curl -X POST http://localhost:8000/reports.php \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "action=search&start_date=2025-09-01&end_date=2025-09-30&page_size=50&status=CAPTURED"
```

## Response Format

### Success Response

```json
{
  "success": true,
  "data": {
    "transactions": [...],
    "pagination": {
      "page": 1,
      "page_size": 10,
      "total_count": 42
    }
  },
  "timestamp": "2025-10-01 12:00:00"
}
```

### Error Response

```json
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "Error description",
    "timestamp": "2025-10-01 12:00:00"
  }
}
```

## Common Use Cases

### Daily Transaction Report

```bash
curl "http://localhost:8000/reports.php?action=summary&start_date=2025-09-30&end_date=2025-09-30"
```

### Find Specific Transaction

```bash
curl "http://localhost:8000/reports.php?action=search&transaction_id=TRN_abc123"
```

### Export Monthly Transactions

```bash
curl "http://localhost:8000/reports.php?action=export&format=csv&start_date=2025-09-01&end_date=2025-09-30" -o september_transactions.csv
```

### Analyze Declines

```bash
curl "http://localhost:8000/reports.php?action=declines&start_date=2025-09-01&end_date=2025-09-30"
```

### Check Settlement Status

```bash
curl "http://localhost:8000/reports.php?action=settlement&start_date=2025-09-30"
```

## Notes

- **Date Format**: All dates must be in `YYYY-MM-DD` format
- **Pagination**: Page size is limited to 100 items maximum
- **Export Limits**: Exports are capped at 1000 transactions
- **Timestamps**: All response timestamps use `YYYY-MM-DD HH:mm:ss` format

## API Documentation

View complete API documentation:

```bash
curl http://localhost:8000/reports.php
```
