# Transaction Reporting API

Query and export transaction data from Global Payments.

## Setup

Add to `.env` (or set them in the environment):
```properties
GP_API_APP_ID=your_app_id_here
GP_API_APP_KEY=your_app_key_here
```

Start server: `./run.sh`

## Endpoints

Base URL: `http://localhost:8000/reports.php?action=`

### Search Transactions

```bash
curl "http://localhost:8000/reports.php?action=search&start_date=2025-09-01&page_size=20"
```

**Parameters:**
- `page` - Page number (default: 1)
- `page_size` - Items per page (max: 100)
- `start_date` - YYYY-MM-DD
- `end_date` - YYYY-MM-DD
- `status` - CAPTURED, DECLINED, PENDING, REVERSED (applied to the returned page)
- `transaction_id` - Specific transaction
- `payment_type` - `sale` or `refund`
- `amount_min` / `amount_max` - Amount range
- `card_last_four` - Last 4 card digits

### Transaction Details

```bash
curl "http://localhost:8000/reports.php?action=detail&transaction_id=TRN_xxx"
```

### Export Data

```bash
# CSV
curl "http://localhost:8000/reports.php?action=export&format=csv&start_date=2025-09-01" -o file.csv

# XML
curl "http://localhost:8000/reports.php?action=export&format=xml&start_date=2025-09-01" -o file.xml

# JSON
curl "http://localhost:8000/reports.php?action=export&format=json&start_date=2025-09-01" -o file.json
```

Exports up to 1000 records. Use filters to narrow results.

### Summary Stats

```bash
curl "http://localhost:8000/reports.php?action=summary&start_date=2025-09-01&end_date=2025-09-30"
```

Returns totals, averages, and breakdowns by status/payment type.

### Declined Transactions

```bash
curl "http://localhost:8000/reports.php?action=declines&start_date=2025-09-01&page_size=20"
```

Returns declined transactions with analysis (reasons, card types, trends).

### Settlement Report

```bash
curl "http://localhost:8000/reports.php?action=settlement&start_date=2025-09-01"
```

### Disputes

```bash
curl "http://localhost:8000/reports.php?action=disputes&status=UNDER_REVIEW"
```

### Deposits

```bash
curl "http://localhost:8000/reports.php?action=deposits&start_date=2025-09-01"
```

### Batches

```bash
curl "http://localhost:8000/reports.php?action=batches&start_date=2025-09-01"
```

GP API has no batch report, so this answers `API_ERROR` with `Batch report generation failed: batch reports are not available through GP API`, the same in every language of this sample.

## Response Format

All responses follow this structure:

```json
{
  "success": true,
  "data": { ... },
  "timestamp": "2025-10-06 12:00:00"
}
```

Errors:
```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Invalid date format",
    "timestamp": "2025-10-06 12:00:00"
  }
}
```

## Common Filters

All search endpoints support:
- Date ranges: `start_date` and `end_date`
- Pagination: `page` and `page_size`
- Status filtering where applicable

## Notes

- Dates must be YYYY-MM-DD format
- Page size max is 100 (search/disputes/deposits)
- Export limit is 1000 records
- All timestamps are UTC
- Transaction IDs start with `TRN_`
