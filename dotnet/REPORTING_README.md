# Global Payments Reporting Service - .NET Implementation

This is a comprehensive .NET/C# ASP.NET Core implementation of the Global Payments Reporting Service, based on the PHP implementation.

## Overview

The reporting service provides RESTful API endpoints for accessing Global Payments reporting functionality including:
- Transaction search and details
- Settlement reports
- Dispute management
- Deposit tracking
- Batch reporting
- Declined transaction analysis
- Data export (JSON, CSV, XML)
- Summary statistics

## Architecture

### Files Created

1. **Services/ReportingService.cs** - Core reporting service class
   - Handles all reporting operations
   - Interfaces with GlobalPayments.Api SDK
   - Formats data for API responses
   - Implements export functionality (JSON, CSV, XML)

2. **Controllers/ReportsController.cs** - REST API controller
   - Provides RESTful endpoints
   - Handles request validation
   - Manages error responses
   - Supports both GET and POST requests

3. **Program.cs** - Updated to integrate reporting
   - Registers reporting service
   - Configures controller routing
   - Adds CORS support
   - Sets up dependency injection

## API Endpoints

### Base URL: `/api/reports`

#### 1. Search Transactions
- **Endpoints**: `GET /api/reports/search` or `POST /api/reports/search`
- **Parameters**:
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

#### 2. Transaction Details
- **Endpoint**: `GET /api/reports/detail/{transactionId}`
- **Parameters**: `transactionId` (required)

#### 3. Settlement Report
- **Endpoints**: `GET /api/reports/settlement` or `POST /api/reports/settlement`
- **Parameters**:
  - `page` - Page number (default: 1)
  - `page_size` - Results per page (default: 50, max: 100)
  - `start_date` - Start date (YYYY-MM-DD)
  - `end_date` - End date (YYYY-MM-DD)

#### 4. Export Transactions
- **Endpoints**: `GET /api/reports/export` or `POST /api/reports/export`
- **Parameters**:
  - `format` - Export format (json, csv, xml)
  - All search filters from endpoint #1

#### 5. Summary Statistics
- **Endpoints**: `GET /api/reports/summary` or `POST /api/reports/summary`
- **Parameters**:
  - `start_date` - Start date (YYYY-MM-DD)
  - `end_date` - End date (YYYY-MM-DD)

#### 6. Dispute Report
- **Endpoints**: `GET /api/reports/disputes` or `POST /api/reports/disputes`
- **Parameters**:
  - `page` - Page number
  - `page_size` - Results per page
  - `start_date` - Start date (YYYY-MM-DD)
  - `end_date` - End date (YYYY-MM-DD)
  - `stage` - Dispute stage
  - `status` - Dispute status

#### 7. Dispute Details
- **Endpoint**: `GET /api/reports/disputes/{disputeId}`
- **Parameters**: `disputeId` (required)

#### 8. Deposit Report
- **Endpoints**: `GET /api/reports/deposits` or `POST /api/reports/deposits`
- **Parameters**:
  - `page` - Page number
  - `page_size` - Results per page
  - `start_date` - Start date (YYYY-MM-DD)
  - `end_date` - End date (YYYY-MM-DD)
  - `deposit_id` - Specific deposit ID
  - `status` - Deposit status

#### 9. Deposit Details
- **Endpoint**: `GET /api/reports/deposits/{depositId}`
- **Parameters**: `depositId` (required)

#### 10. Batch Report
- **Endpoints**: `GET /api/reports/batches` or `POST /api/reports/batches`
- **Parameters**:
  - `start_date` - Start date (YYYY-MM-DD)
  - `end_date` - End date (YYYY-MM-DD)

#### 11. Declined Transactions
- **Endpoints**: `GET /api/reports/declines` or `POST /api/reports/declines`
- **Parameters**: Same as search endpoint
- **Returns**: Declined transactions with analysis (reasons, card types, hourly breakdown)

#### 12. Date Range Report
- **Endpoints**: `GET /api/reports/date-range` or `POST /api/reports/date-range`
- **Parameters**:
  - `start_date` - Start date (YYYY-MM-DD)
  - `end_date` - End date (YYYY-MM-DD)
  - `transaction_limit` - Max transactions (default: 100, max: 1000)
  - `settlement_limit` - Max settlements (default: 50, max: 500)
  - `dispute_limit` - Max disputes (default: 25, max: 100)
  - `deposit_limit` - Max deposits (default: 25, max: 100)

#### 13. Configuration Status
- **Endpoint**: `GET /api/reports/config`
- **Returns**: SDK configuration status and available endpoints

#### 14. API Information
- **Endpoint**: `GET /api/reports`
- **Returns**: API documentation and endpoint listing

## Features

### Implemented from PHP Version

✅ All 11 main action endpoints (search, detail, settlement, export, summary, disputes, deposits, batches, declines, date_range, config)
✅ GET and POST request support
✅ Request parameter merging from query string and body
✅ Date format validation (YYYY-MM-DD)
✅ Pagination with configurable limits
✅ Multiple export formats (JSON, CSV, XML)
✅ Error handling with standardized error responses
✅ CORS support
✅ Transaction filtering (by ID, type, status, amount, card)
✅ Dispute management with stage and status filters
✅ Deposit tracking and details
✅ Settlement report generation
✅ Declined transaction analysis
✅ Comprehensive date range reports
✅ Summary statistics calculation

### .NET Best Practices

✅ Async/await pattern throughout
✅ Dependency injection for service registration
✅ Controller-based architecture with [ApiController] attribute
✅ Proper route configuration with [Route], [HttpGet], [HttpPost] attributes
✅ Exception handling with try-catch blocks
✅ Logging support via ILogger
✅ Standardized error response format
✅ File download support for CSV/XML exports
✅ Clean separation of concerns (Controller → Service → SDK)

## Configuration

The service uses the existing Global Payments SDK configuration from Program.cs. Make sure your `.env` file contains:

```env
# Global Payments API Configuration
GP_APP_ID=your_app_id
GP_APP_KEY=your_app_key
GP_ENVIRONMENT=sandbox  # or production

# Legacy Portico Configuration (if needed)
SECRET_API_KEY=your_secret_key
PUBLIC_API_KEY=your_public_key
```

## Response Format

### Success Response
```json
{
  "success": true,
  "data": { ... },
  "timestamp": "2025-09-30 12:00:00"
}
```

### Error Response
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

## Error Codes

- `VALIDATION_ERROR` - Invalid parameters or missing required fields
- `API_ERROR` - Global Payments API error
- `INTERNAL_ERROR` - Unexpected server error

## Usage Examples

### Search Transactions (GET)
```bash
curl "http://localhost:8000/api/reports/search?start_date=2025-09-01&end_date=2025-09-30&page_size=20"
```

### Search Transactions (POST)
```bash
curl -X POST "http://localhost:8000/api/reports/search" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "start_date=2025-09-01&end_date=2025-09-30&status=APPROVED"
```

### Get Transaction Details
```bash
curl "http://localhost:8000/api/reports/detail/TXN_123456"
```

### Export to CSV
```bash
curl "http://localhost:8000/api/reports/export?format=csv&start_date=2025-09-01" \
  -o transactions.csv
```

### Get Summary Statistics
```bash
curl "http://localhost:8000/api/reports/summary?start_date=2025-09-01&end_date=2025-09-30"
```

### Get Configuration
```bash
curl "http://localhost:8000/api/reports/config"
```

## Running the Application

```bash
cd dotnet
dotnet restore
dotnet run
```

The application will start on `http://localhost:8000` by default (or the port specified in the `PORT` environment variable).

## Testing

You can test all endpoints using:
1. cURL commands (examples above)
2. Postman or similar API testing tools
3. The built-in Swagger UI (if configured)
4. Browser for GET requests

## Differences from PHP Implementation

1. **Async/Await**: All methods are async for better performance
2. **Dependency Injection**: Service is registered in DI container
3. **Controller Architecture**: Uses ASP.NET Core controllers instead of switch/case routing
4. **Type Safety**: Strong typing throughout the codebase
5. **XML Export**: Added XML export format (not in PHP version)
6. **Logging**: Integrated with ASP.NET Core logging framework
7. **Route Attributes**: Uses attribute routing instead of query parameter action

## Notes

1. The SDK configuration uses PorticoConfig in Program.cs. For GP API reporting, you may need to configure GpApiConfig instead.
2. Batch reporting implementation is simplified - the .NET SDK may have different batch detail methods than PHP.
3. All endpoints support CORS for cross-origin requests.
4. Date parameters must be in YYYY-MM-DD format.
5. Page sizes are capped at 100 for search/list operations and 1000 for exports.

## Future Enhancements

- Add Swagger/OpenAPI documentation
- Implement caching for frequently accessed reports
- Add rate limiting
- Implement authentication/authorization
- Add unit and integration tests
- Support for additional export formats (Excel, PDF)
- Real-time reporting via SignalR/WebSockets