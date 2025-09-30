# Global Payments Reporting Service - Java Implementation

This is a comprehensive Java implementation of the Global Payments Reporting Service, equivalent to the PHP implementation found in `php/reports.php` and `php/reporting-service.php`.

## Files Created

### 1. ReportsController.java
**Location**: `/home/radoslavsheytanov/Documents/reporting-service/reporting-service/java/src/main/java/com/globalpayments/example/ReportsController.java`

A servlet-based REST controller that provides all the reporting API endpoints. This is the Java equivalent of `php/reports.php`.

**Key Features**:
- RESTful API endpoints for all reporting operations
- CORS support for cross-origin requests
- Support for both GET and POST requests
- JSON request/response handling with Jackson
- Comprehensive error handling with proper HTTP status codes
- Request parameter validation

### 2. ReportingService.java
**Location**: `/home/radoslavsheytanov/Documents/reporting-service/reporting-service/java/src/main/java/com/globalpayments/example/ReportingService.java`

The core service class containing all business logic for reporting operations. This is the Java equivalent of `php/reporting-service.php`.

**Key Features**:
- Transaction search with filtering and pagination
- Settlement, dispute, deposit, and batch reporting
- Data export in JSON, CSV, and XML formats
- Summary statistics and analytics
- Comprehensive date range reports
- Decline analysis

## API Endpoints

All endpoints are accessible via `/reports?action={action_name}` and support both GET and POST methods unless otherwise noted.

### Available Actions

1. **search** - Search transactions with filters and pagination
   - Parameters: page, page_size, start_date, end_date, transaction_id, payment_type, status, amount_min, amount_max, card_last_four

2. **detail** - Get detailed transaction information
   - Parameters: transaction_id (required)

3. **settlement** - Get settlement report
   - Parameters: page, page_size, start_date, end_date

4. **export** - Export transaction data
   - Parameters: format (json|csv|xml), plus all search filters
   - Response: Downloads file in specified format

5. **summary** - Get summary statistics
   - Parameters: start_date, end_date

6. **disputes** - Get dispute report
   - Parameters: page, page_size, start_date, end_date, stage, status

7. **dispute_detail** - Get dispute details
   - Parameters: dispute_id (required)

8. **deposits** - Get deposit report
   - Parameters: page, page_size, start_date, end_date, deposit_id, status

9. **deposit_detail** - Get deposit details
   - Parameters: deposit_id (required)

10. **batches** - Get batch report
    - Parameters: start_date, end_date

11. **declines** - Get declined transactions report
    - Parameters: page, page_size, start_date, end_date, payment_type, amount_min, amount_max, card_last_four

12. **date_range** - Get comprehensive date range report
    - Parameters: start_date, end_date, transaction_limit, settlement_limit, dispute_limit, deposit_limit

13. **config** - Get API configuration and status (GET only)
    - No parameters required

14. **(empty)** - API documentation (GET only)
    - Returns full API documentation

## Configuration

### Environment Variables

Create a `.env` file in the java directory with the following variables:

```properties
# GP-API Credentials (for reporting)
GP_API_APP_ID=your_app_id_here
GP_API_APP_KEY=your_app_key_here
GP_API_ENVIRONMENT=TEST  # or PRODUCTION

# Legacy Credentials (for payment processing)
SECRET_API_KEY=your_secret_api_key_here
PUBLIC_API_KEY=your_public_api_key_here
```

### Dependencies Added

Updated `pom.xml` to include:
- **Jackson Databind** (2.15.2) - For JSON processing and serialization

Existing dependencies:
- Global Payments SDK (14.2.20)
- Dotenv Java (3.0.0)
- Jakarta Servlet API (5.0.0)

## Key Differences from PHP Implementation

### 1. Architecture
- **PHP**: Procedural with function-based routing
- **Java**: Object-oriented with servlet-based architecture

### 2. Type Safety
- Java implementation uses strong typing with proper type checking
- Exception handling is more structured with specific exception types

### 3. Date Handling
- PHP uses `DateTime` class
- Java uses `Date`, `SimpleDateFormat`, and `LocalDate` for date operations

### 4. SDK Configuration
- PHP uses Portico/GP-API specific config functions
- Java uses `GpApiConfig` with `ServicesContainer.configureService()`

### 5. Data Export
- Both support JSON, CSV formats
- Java implementation also supports XML export
- CSV/XML generation is done in-memory with StringBuilder for efficiency

### 6. JSON Processing
- PHP uses `json_encode()`/`json_decode()`
- Java uses Jackson ObjectMapper for JSON serialization/deserialization

## Implementation Details

### Request Handling Flow

1. **Request Reception**: `doGet()` or `doPost()` receives the request
2. **Parameter Extraction**: `getRequestParams()` combines GET and POST parameters
3. **Action Routing**: `handleRequest()` routes to appropriate handler
4. **Validation**: Date formats and required parameters are validated
5. **Service Call**: Appropriate `ReportingService` method is invoked
6. **Response Formatting**: Result is serialized to JSON or other format
7. **Error Handling**: Exceptions are caught and formatted as error responses

### Error Handling

The implementation includes three levels of error handling:

1. **Validation Errors** (400): Invalid parameters or date formats
2. **API Errors** (400): Global Payments API errors
3. **Internal Errors** (500): Unexpected server errors

All errors return structured JSON responses:
```json
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "Error description",
    "timestamp": "2025-09-30 12:00:00"
  }
}
```

### Data Formatting

The `ReportingService` class includes comprehensive formatting methods:
- `formatTransactionList()` - Formats transaction summaries
- `formatTransactionDetails()` - Formats detailed transaction data
- `formatSettlementList()` - Formats settlement summaries
- `formatDisputeList()` - Formats dispute summaries
- `formatDepositList()` - Formats deposit summaries

### Analytics Features

- **Summary Statistics**: Transaction counts, amounts, averages, breakdowns by status and payment type
- **Decline Analysis**: Decline reasons, card type breakdown, hourly patterns
- **Comprehensive Summaries**: Multi-faceted reports combining transactions, settlements, disputes, and deposits

## Usage Examples

### Search Transactions
```bash
# GET request
curl "http://localhost:8000/reports?action=search&start_date=2025-09-01&end_date=2025-09-30&page_size=20"

# POST request with JSON
curl -X POST http://localhost:8000/reports \
  -H "Content-Type: application/json" \
  -d '{
    "action": "search",
    "start_date": "2025-09-01",
    "end_date": "2025-09-30",
    "page_size": 20
  }'
```

### Get Transaction Details
```bash
curl "http://localhost:8000/reports?action=detail&transaction_id=TXN_123456"
```

### Export to CSV
```bash
curl "http://localhost:8000/reports?action=export&format=csv&start_date=2025-09-01&end_date=2025-09-30" \
  -o transactions.csv
```

### Export to XML
```bash
curl "http://localhost:8000/reports?action=export&format=xml&start_date=2025-09-01&end_date=2025-09-30" \
  -o transactions.xml
```

### Get Summary Statistics
```bash
curl "http://localhost:8000/reports?action=summary&start_date=2025-09-01&end_date=2025-09-30"
```

### Get Date Range Report
```bash
curl "http://localhost:8000/reports?action=date_range&start_date=2025-09-01&end_date=2025-09-30&transaction_limit=100"
```

### Check Configuration
```bash
curl "http://localhost:8000/reports?action=config"
```

## Building and Running

### Build the Project
```bash
cd /home/radoslavsheytanov/Documents/reporting-service/reporting-service/java
mvn clean package
```

### Run with Cargo
```bash
mvn cargo:run
```

The application will be available at `http://localhost:8000/reports`

## Testing

### Test the API Documentation Endpoint
```bash
curl http://localhost:8000/reports
```

### Test Configuration Status
```bash
curl "http://localhost:8000/reports?action=config"
```

### Test Transaction Search
```bash
curl "http://localhost:8000/reports?action=search&page=1&page_size=10"
```

## Best Practices Implemented

1. **Separation of Concerns**: Controller handles HTTP, Service handles business logic
2. **Input Validation**: All parameters are validated before processing
3. **Error Handling**: Comprehensive exception handling with meaningful error messages
4. **Type Safety**: Strong typing prevents common runtime errors
5. **Resource Management**: Proper handling of streams and connections
6. **CORS Support**: Enables cross-origin requests for API consumption
7. **Pagination**: Limits on page sizes to prevent performance issues
8. **Date Validation**: Strict date format validation (YYYY-MM-DD)
9. **Security**: Parameter sanitization and validation

## Notes

- Date format must be `YYYY-MM-DD` for all date parameters
- Page size is limited to 100 items maximum
- Export limits are enforced (1000 for transactions, 500 for settlements, etc.)
- All timestamps in responses are formatted as `YYYY-MM-DD HH:mm:ss`
- Currency defaults to `USD` where not specified
- The implementation supports the Global Payments GP-API

## Future Enhancements

Potential improvements for future versions:
1. Add Spring Boot framework support for more modern architecture
2. Implement caching for frequently accessed reports
3. Add asynchronous processing for large exports
4. Implement rate limiting to prevent API abuse
5. Add more comprehensive logging
6. Implement pagination tokens for large result sets
7. Add WebSocket support for real-time reporting updates
8. Implement scheduled report generation
9. Add report templates and customization

## Support

For issues or questions:
1. Check the API documentation endpoint (`/reports`)
2. Verify environment configuration (`/reports?action=config`)
3. Review error messages in API responses
4. Check Global Payments SDK documentation