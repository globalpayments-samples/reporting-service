# Global Payments Reporting API - Go

A comprehensive reporting service for Global Payments transactions with search, filtering, analytics, and data export capabilities.

## Quick Start

### 1. Configure Environment

Create a `.env` file in the go directory:

```properties
GP_API_APP_ID=your_app_id_here
GP_API_APP_KEY=your_app_key_here
GP_API_ENVIRONMENT=TEST  # or PRODUCTION
```

### 2. Install Dependencies & Start Server

```bash
cd go
go mod tidy
go run reporting_service.go reports.go main_reporting.go
```

The API will be available at `http://localhost:8080/reports`

### 3. Verify Configuration

```bash
curl "http://localhost:8080/reports/config"
```

## API Endpoints

All endpoints use `/reports/{action}` or `/reports?action={action_name}` and support both GET and POST requests.

### Search Transactions

Search and filter transactions with pagination.

```bash
curl "http://localhost:8080/reports/search?start_date=2025-09-01&end_date=2025-09-30&page_size=20"
```

**Parameters**:
- `page` - Page number (default: 1)
- `page_size` - Results per page (default: 10, max: 100)
- `start_date` - Start date (YYYY-MM-DD)
- `end_date` - End date (YYYY-MM-DD)
- `transaction_id` - Specific transaction ID
- `payment_type` - Payment type filter
- `status` - Transaction status
- `amount_min` - Minimum amount
- `amount_max` - Maximum amount
- `card_last_four` - Last 4 digits of card

### Get Transaction Details

Retrieve detailed information for a specific transaction.

```bash
curl "http://localhost:8080/reports/detail?transaction_id=TRN_123456"
```

**Required**: `transaction_id`

### Settlement Report

Get settlement information for a date range.

```bash
curl "http://localhost:8080/reports/settlement?start_date=2025-09-01&end_date=2025-09-30"
```

**Parameters**:
- `page`, `page_size` - Pagination
- `start_date`, `end_date` - Date range

### Export Transactions

Export transaction data in JSON, CSV, or XML format.

**CSV Export**:
```bash
curl "http://localhost:8080/reports/export?format=csv&start_date=2025-09-01&end_date=2025-09-30" -o transactions.csv
```

**XML Export**:
```bash
curl "http://localhost:8080/reports/export?format=xml&start_date=2025-09-01&end_date=2025-09-30" -o transactions.xml
```

**JSON Export**:
```bash
curl "http://localhost:8080/reports/export?format=json&start_date=2025-09-01&end_date=2025-09-30"
```

**Parameters**:
- `format` - Export format: `json`, `csv`, or `xml` (required)
- Plus all search filters

### Summary Statistics

Get aggregate statistics for transactions.

```bash
curl "http://localhost:8080/reports/summary?start_date=2025-09-01&end_date=2025-09-30"
```

**Returns**:
- Total transaction count
- Total amount
- Average amount
- Status breakdown
- Payment type breakdown

### Dispute Report

Get dispute information with filtering.

```bash
curl "http://localhost:8080/reports/disputes?start_date=2025-09-01&end_date=2025-09-30"
```

**Parameters**:
- `page`, `page_size` - Pagination
- `start_date`, `end_date` - Date range
- `stage` - Dispute stage
- `status` - Dispute status

**Get Dispute Details**:
```bash
curl "http://localhost:8080/reports/dispute/DIS_123456"
```

### Deposit Report

Get deposit information and details.

```bash
curl "http://localhost:8080/reports/deposits?start_date=2025-09-01&end_date=2025-09-30"
```

**Parameters**:
- `page`, `page_size` - Pagination
- `start_date`, `end_date` - Date range
- `deposit_id` - Filter by deposit ID
- `status` - Deposit status

**Get Deposit Details**:
```bash
curl "http://localhost:8080/reports/deposit/DEP_123456"
```

### Declined Transactions Report

Get declined transactions with analysis.

```bash
curl "http://localhost:8080/reports/declines?start_date=2025-09-01&end_date=2025-09-30"
```

**Returns** transaction data plus decline analysis:
- Decline reasons breakdown
- Card type breakdown
- Hourly decline patterns

### Comprehensive Date Range Report

Get a combined report across all transaction types.

```bash
curl "http://localhost:8080/reports/date-range?start_date=2025-09-01&end_date=2025-09-30&transaction_limit=100"
```

**Parameters**:
- `start_date`, `end_date` - Date range
- `transaction_limit` - Max transactions (default: 100, max: 1000)
- `settlement_limit` - Max settlements (default: 50, max: 500)
- `dispute_limit` - Max disputes (default: 25, max: 100)
- `deposit_limit` - Max deposits (default: 25, max: 100)

**Returns**:
- Transactions
- Settlements
- Disputes
- Deposits
- Comprehensive summary

### Batch Report

Get batch report information.

```bash
curl "http://localhost:8080/reports/batches?start_date=2025-09-01&end_date=2025-09-30"
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
curl "http://localhost:8080/reports/summary?start_date=2025-09-30&end_date=2025-09-30"
```

### Find Specific Transaction

```bash
curl "http://localhost:8080/reports/search?transaction_id=TRN_abc123"
```

### Export Monthly Transactions

```bash
curl "http://localhost:8080/reports/export?format=csv&start_date=2025-09-01&end_date=2025-09-30" -o september_transactions.csv
```

### Analyze Declines

```bash
curl "http://localhost:8080/reports/declines?start_date=2025-09-01&end_date=2025-09-30"
```

### Check Settlement Status

```bash
curl "http://localhost:8080/reports/settlement?start_date=2025-09-30"
```

## Notes

- **Date Format**: All dates must be in `YYYY-MM-DD` format
- **Pagination**: Page size is limited to 100 items maximum
- **Export Limits**: Exports are capped at 1000 transactions
- **Timestamps**: All response timestamps use `YYYY-MM-DD HH:mm:ss` format
- **CORS**: Enabled for cross-origin requests

## API Documentation

View complete API documentation:

```bash
curl http://localhost:8080/reports
```
