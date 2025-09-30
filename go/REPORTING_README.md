# Global Payments Reporting Service - Go Implementation

This is a comprehensive Go implementation of the Global Payments reporting service, providing RESTful API endpoints for transaction reporting, analytics, and data export.

## Overview

The Go reporting service is a direct translation of the PHP implementation, providing identical functionality with Go's performance benefits and strong typing. It includes:

- **Transaction Search & Filtering** - Search transactions with multiple filter criteria
- **Transaction Details** - Retrieve detailed information for specific transactions
- **Settlement Reports** - Generate settlement reports with summaries
- **Dispute Management** - Track and manage chargebacks and disputes
- **Deposit Tracking** - Monitor deposits and funding
- **Batch Reports** - View batch processing information
- **Declined Transactions** - Analyze declined transaction patterns
- **Date Range Reports** - Comprehensive reports across all transaction types
- **Data Export** - Export data in JSON, CSV, and XML formats
- **Summary Statistics** - Get aggregate statistics and analytics

## Files

### Core Service Files

1. **`reporting_service.go`** - Core service implementation
   - Service class with all reporting methods
   - SDK integration and configuration
   - Data formatting and transformation
   - Export functionality (CSV, XML, JSON)

2. **`reports.go`** - HTTP handler functions
   - RESTful API endpoints
   - Request parsing and validation
   - Response formatting
   - CORS support
   - Error handling

3. **`main_reporting.go`** - Example standalone server
   - Server initialization
   - Route setup
   - Configuration

## Architecture

### Service Layer (`reporting_service.go`)

The `ReportingService` struct provides methods for:

```go
type ReportingService struct {
    isConfigured bool
}

// Core methods
func NewReportingService() (*ReportingService, error)
func (rs *ReportingService) SearchTransactions(filters map[string]interface{}) (map[string]interface{}, error)
func (rs *ReportingService) GetTransactionDetails(transactionID string) (map[string]interface{}, error)
func (rs *ReportingService) GetSettlementReport(params map[string]interface{}) (map[string]interface{}, error)
func (rs *ReportingService) GetDisputeReport(filters map[string]interface{}) (map[string]interface{}, error)
func (rs *ReportingService) GetDepositReport(filters map[string]interface{}) (map[string]interface{}, error)
func (rs *ReportingService) GetBatchReport(filters map[string]interface{}) (map[string]interface{}, error)
func (rs *ReportingService) GetDeclinedTransactionsReport(filters map[string]interface{}) (map[string]interface{}, error)
func (rs *ReportingService) GetDateRangeReport(params map[string]interface{}) (map[string]interface{}, error)
func (rs *ReportingService) ExportTransactions(filters map[string]interface{}, format string) (map[string]interface{}, error)
func (rs *ReportingService) GetSummaryStats(params map[string]interface{}) (map[string]interface{}, error)
```

### HTTP Handler Layer (`reports.go`)

HTTP handlers that route requests to the service layer:

- `handleReports` - API documentation endpoint
- `handleSearch` - Transaction search
- `handleDetail` - Transaction details
- `handleSettlement` - Settlement reports
- `handleExport` - Data export
- `handleSummary` - Summary statistics
- `handleDisputes` - Dispute reports
- `handleDeposits` - Deposit reports
- `handleBatches` - Batch reports
- `handleDeclines` - Declined transactions
- `handleDateRange` - Comprehensive date range reports
- `handleReportsConfig` - Configuration status

## API Endpoints

### Base Endpoint Styles

The service supports two routing styles for compatibility:

1. **REST-style routes** (recommended):
   - `GET /reports/search?page=1&start_date=2024-01-01`
   - `GET /reports/detail?transaction_id=TXN123`

2. **Action-based routes** (PHP compatibility):
   - `GET /reports?action=search&page=1&start_date=2024-01-01`
   - `GET /reports?action=detail&transaction_id=TXN123`

### Available Endpoints

#### 1. Transaction Search
```
GET/POST /reports/search
```
**Parameters:**
- `page` - Page number (default: 1)
- `page_size` - Results per page (default: 10, max: 100)
- `start_date` - Start date (YYYY-MM-DD)
- `end_date` - End date (YYYY-MM-DD)
- `transaction_id` - Specific transaction ID
- `payment_type` - Payment type (sale, refund, authorize, capture)
- `status` - Transaction status
- `amount_min` - Minimum amount
- `amount_max` - Maximum amount
- `card_last_four` - Last 4 digits of card

**Example:**
```bash
curl "http://localhost:8080/reports/search?start_date=2024-01-01&end_date=2024-01-31&page_size=50"
```

#### 2. Transaction Details
```
GET /reports/detail?transaction_id={id}
```
**Parameters:**
- `transaction_id` - Transaction ID (required)

**Example:**
```bash
curl "http://localhost:8080/reports/detail?transaction_id=TXN_abc123"
```

#### 3. Settlement Report
```
GET/POST /reports/settlement
```
**Parameters:**
- `page` - Page number (default: 1)
- `page_size` - Results per page (default: 50, max: 100)
- `start_date` - Start date (YYYY-MM-DD)
- `end_date` - End date (YYYY-MM-DD)

**Example:**
```bash
curl "http://localhost:8080/reports/settlement?start_date=2024-01-01&end_date=2024-01-31"
```

#### 4. Export Transactions
```
GET/POST /reports/export?format={json|csv|xml}
```
**Parameters:**
- `format` - Export format (json, csv, or xml)
- All search filters from transaction search

**Example:**
```bash
# Export as CSV
curl "http://localhost:8080/reports/export?format=csv&start_date=2024-01-01" -o transactions.csv

# Export as XML
curl "http://localhost:8080/reports/export?format=xml&start_date=2024-01-01" -o transactions.xml

# Export as JSON
curl "http://localhost:8080/reports/export?format=json&start_date=2024-01-01"
```

#### 5. Summary Statistics
```
GET/POST /reports/summary
```
**Parameters:**
- `start_date` - Start date (YYYY-MM-DD)
- `end_date` - End date (YYYY-MM-DD)

**Example:**
```bash
curl "http://localhost:8080/reports/summary?start_date=2024-01-01&end_date=2024-01-31"
```

#### 6. Dispute Report
```
GET/POST /reports/disputes
```
**Parameters:**
- `page` - Page number (default: 1)
- `page_size` - Results per page (default: 10, max: 100)
- `start_date` - Start date (YYYY-MM-DD)
- `end_date` - End date (YYYY-MM-DD)
- `stage` - Dispute stage
- `status` - Dispute status

**Example:**
```bash
curl "http://localhost:8080/reports/disputes?start_date=2024-01-01&status=PENDING"
```

#### 7. Dispute Details
```
GET /reports/dispute/{id}
```
**Example:**
```bash
curl "http://localhost:8080/reports/dispute/DIS_abc123"
```

#### 8. Deposit Report
```
GET/POST /reports/deposits
```
**Parameters:**
- `page` - Page number (default: 1)
- `page_size` - Results per page (default: 10, max: 100)
- `start_date` - Start date (YYYY-MM-DD)
- `end_date` - End date (YYYY-MM-DD)
- `deposit_id` - Specific deposit ID
- `status` - Deposit status

**Example:**
```bash
curl "http://localhost:8080/reports/deposits?start_date=2024-01-01"
```

#### 9. Deposit Details
```
GET /reports/deposit/{id}
```
**Example:**
```bash
curl "http://localhost:8080/reports/deposit/DEP_abc123"
```

#### 10. Batch Report
```
GET/POST /reports/batches
```
**Parameters:**
- `start_date` - Start date (YYYY-MM-DD)
- `end_date` - End date (YYYY-MM-DD)

**Example:**
```bash
curl "http://localhost:8080/reports/batches?start_date=2024-01-01"
```

#### 11. Declined Transactions
```
GET/POST /reports/declines
```
**Parameters:**
- All search parameters plus decline analysis

**Example:**
```bash
curl "http://localhost:8080/reports/declines?start_date=2024-01-01&end_date=2024-01-31"
```

#### 12. Date Range Report
```
GET/POST /reports/date-range
```
**Parameters:**
- `start_date` - Start date (YYYY-MM-DD)
- `end_date` - End date (YYYY-MM-DD)
- `transaction_limit` - Max transactions (default: 100, max: 1000)
- `settlement_limit` - Max settlements (default: 50, max: 500)
- `dispute_limit` - Max disputes (default: 25, max: 100)
- `deposit_limit` - Max deposits (default: 25, max: 100)

**Example:**
```bash
curl "http://localhost:8080/reports/date-range?start_date=2024-01-01&end_date=2024-01-31"
```

#### 13. Configuration Status
```
GET /reports/config
```
**Example:**
```bash
curl "http://localhost:8080/reports/config"
```

## Setup and Configuration

### 1. Environment Variables

Create a `.env` file with your Global Payments credentials:

```env
# GP-API Credentials (for reporting)
GP_API_APP_ID=your_app_id_here
GP_API_APP_KEY=your_app_key_here

# Server Configuration
PORT=8080
```

### 2. Install Dependencies

```bash
cd go
go mod tidy
```

This will install:
- `github.com/globalpayments/go-sdk` - Global Payments SDK
- `github.com/gorilla/mux` - HTTP router
- `github.com/joho/godotenv` - Environment variable loader

### 3. Run the Server

#### Standalone Reporting Server:
```bash
go run reporting_service.go reports.go main_reporting.go
```

#### Integration with Existing Server:
Add to your existing `main.go`:

```go
import (
    "github.com/gorilla/mux"
)

func main() {
    router := mux.NewRouter()

    // Initialize reporting API
    if err := InitializeReportingAPI(router); err != nil {
        log.Fatal(err)
    }

    // Your other routes...

    http.ListenAndServe(":8080", router)
}
```

### 4. Verify Configuration

```bash
curl http://localhost:8080/reports/config
```

Should return:
```json
{
  "success": true,
  "data": {
    "sdk_status": {
      "configured": true,
      "has_app_id": true,
      "has_app_key": true,
      "environment": "TEST"
    },
    "environment_validation": {
      "valid": true,
      "errors": [],
      "warnings": []
    }
  }
}
```

## Response Format

All endpoints return a standardized JSON response:

### Success Response:
```json
{
  "success": true,
  "data": {
    // Response data
  },
  "timestamp": "2024-01-15 12:34:56"
}
```

### Error Response:
```json
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "Error description",
    "timestamp": "2024-01-15 12:34:56"
  },
  "timestamp": "2024-01-15 12:34:56"
}
```

## Error Codes

- `VALIDATION_ERROR` - Invalid parameters or missing required fields
- `API_ERROR` - Global Payments API error
- `PARSE_ERROR` - Request parsing error
- `INTERNAL_ERROR` - Internal server error

## Data Structures

### TransactionInfo
```go
type TransactionInfo struct {
    TransactionID   string  `json:"transaction_id"`
    Timestamp       string  `json:"timestamp"`
    Amount          float64 `json:"amount"`
    Currency        string  `json:"currency"`
    Status          string  `json:"status"`
    PaymentMethod   string  `json:"payment_method"`
    CardLastFour    string  `json:"card_last_four"`
    AuthCode        string  `json:"auth_code"`
    ReferenceNumber string  `json:"reference_number"`
}
```

### Pagination
```go
type Pagination struct {
    Page       int `json:"page"`
    PageSize   int `json:"page_size"`
    TotalCount int `json:"total_count"`
}
```

## Testing

### Example Test Script

```bash
#!/bin/bash

BASE_URL="http://localhost:8080"

# Test configuration
echo "Testing configuration..."
curl -s "$BASE_URL/reports/config" | jq

# Test transaction search
echo -e "\nTesting transaction search..."
curl -s "$BASE_URL/reports/search?start_date=2024-01-01&page_size=5" | jq

# Test summary statistics
echo -e "\nTesting summary statistics..."
curl -s "$BASE_URL/reports/summary?start_date=2024-01-01&end_date=2024-01-31" | jq

# Test export
echo -e "\nTesting CSV export..."
curl -s "$BASE_URL/reports/export?format=csv&start_date=2024-01-01" -o transactions.csv
echo "Exported to transactions.csv"
```

## Deployment

### Docker

Create a `Dockerfile`:

```dockerfile
FROM golang:1.23-alpine AS builder

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN go build -o reporting-service reporting_service.go reports.go main_reporting.go

FROM alpine:latest
RUN apk --no-cache add ca-certificates

WORKDIR /root/
COPY --from=builder /app/reporting-service .
COPY .env .

EXPOSE 8080
CMD ["./reporting-service"]
```

Build and run:
```bash
docker build -t gp-reporting-service .
docker run -p 8080:8080 --env-file .env gp-reporting-service
```

### Production Considerations

1. **Environment**: Change `environment.TEST` to `environment.PRODUCTION` in `reporting_service.go`
2. **Security**: Use proper secrets management for API credentials
3. **Logging**: Add comprehensive logging for production monitoring
4. **Rate Limiting**: Implement rate limiting for API endpoints
5. **Authentication**: Add authentication/authorization middleware
6. **HTTPS**: Use TLS certificates for secure communication

## Differences from PHP Implementation

1. **Strong Typing**: Go's type system provides compile-time safety
2. **Performance**: Generally faster execution and lower memory usage
3. **Concurrency**: Built-in goroutines for concurrent operations
4. **Error Handling**: Explicit error returns vs PHP exceptions
5. **Routing**: Using gorilla/mux instead of query parameters
6. **SDK Integration**: Direct Go SDK calls vs PHP SDK

## TODO / Implementation Notes

The current implementation includes complete handler structure and API routing. Some areas that need completion based on actual SDK response structures:

1. **SDK Response Parsing**: The `formatTransactionList`, `formatSettlementList`, etc. functions need to be completed based on actual SDK response types from the Global Payments Go SDK.

2. **Type Assertions**: Some type conversions in the formatting functions should be updated once the exact SDK response structures are known.

3. **Additional Validation**: May need additional validation based on specific business requirements.

These TODOs are marked in the code with `// TODO:` comments.

## Support

For issues or questions:
- Global Payments SDK: https://github.com/globalpayments/go-sdk
- Go Documentation: https://golang.org/doc/

## License

MIT License - See LICENSE file for details