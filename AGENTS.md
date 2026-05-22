# Global Payments Reporting Service

> Query and export transaction, settlement, dispute, deposit, and batch reports via the Global Payments GP API, demonstrated in PHP, Node.js, .NET, Java, Python, and Go.

## Critical Patterns

1. **The server entry files are scaffolding artifacts — use the reporting module files instead.** `server.js`, `server.py`, `main.go`, and `ProcessPaymentServlet.java` each contain a `PorticoConfig`/`SECRET_API_KEY` stub for a `/process-payment` endpoint unrelated to this sample. The actual reporting logic lives in `reporting-service.php`, `reporting-service.js`, `reporting_service.py`, `ReportingService.java`, `ReportingService.cs`, and `reporting_service.go`, all of which correctly use `GpApiConfig` with `GP_API_APP_ID`/`GP_API_APP_KEY`. Adding reporting logic to the stub files will configure the wrong gateway.

2. **Transaction status is not an SDK filter — it is applied client-side after fetching results.** `ReportingService::findTransactionsPaged()` accepts date range, transaction ID, payment type, amount, and card-last-four as SDK-level filters; it does not accept `status`. All six implementations retrieve the transaction result set from the SDK and then filter by status in application code. Passing `status` to the transaction SDK call does not error — it is silently ignored. **Exception:** Dispute and deposit reports DO support SDK-level status filtering — PHP uses `withDisputeStatus()`/`withDepositStatus()`, Python uses `with_dispute_status()`/`with_deposit_status()`, and the equivalent methods in other languages; these pass status directly to the SDK builder rather than filtering client-side.

3. **Date fields require ISO strings (`YYYY-MM-DD`) converted to native date objects before calling the SDK.** PHP wraps with `\DateTime::createFromFormat('Y-m-d', ...)`, Java uses `SimpleDateFormat("yyyy-MM-dd").parse()`, Python uses `datetime.strptime('%Y-%m-%d')`, Go uses `time.Parse("2006-01-02", ...)`. The SDK builder rejects raw strings passed to `.withStartDate()` or `.withEndDate()`.

4. **All paged SDK methods take `(page, pageSize)` as the first two positional parameters.** `findTransactionsPaged($page, $pageSize)`, `findSettlementTransactionsPaged()`, `findDisputesPaged()`, and `findDepositsPaged()` all follow this signature. Omitting either argument silently falls back to SDK defaults.

## Repository Structure

### PHP (native PHP + Global Payments SDK)
- [`php/reporting-service.php`](php/reporting-service.php) — `GlobalPaymentsReportingService` class; `searchTransactions()`, `getTransactionDetails()`, `getSettlementReport()`, `getDisputeReport()`, `getDisputeDetails()`, `getDepositReport()`, `getDepositDetails()`, `getBatchReport()`, `exportTransactions()`, `getSummaryStats()`
- [`php/reports.php`](php/reports.php) — action-based router; dispatches `?action=search|detail|settlement|export|summary|disputes|dispute_detail|deposits|deposit_detail|batches|declines|date_range|config`
- [`php/sdk-config.php`](php/sdk-config.php) — `configureGpApiSdk()`, `getSdkConfigStatus()`, `validateEnvironmentConfig()`
- [`php/composer.json`](php/composer.json) — `globalpayments/php-sdk` ^13.1

### Node.js (Express + Global Payments SDK)
- [`nodejs/reporting-service.js`](nodejs/reporting-service.js) — `GlobalPaymentsReportingService` class; `configureGpApiSdk()`, full reporting method set; uses `findBatchesPaged()` for batch (differs from PHP's `batchDetail()`)
- [`nodejs/reports.js`](nodejs/reports.js) — Express router mounted at `/api/reports`; dispatches via `?action=` or REST sub-paths (`/api/reports/search`, `/api/reports/settlement`, etc.)
- [`nodejs/server.js`](nodejs/server.js) — main entry; mounts reporting router at `/api/reports`; contains a template `/process-payment` Portico stub — see Critical Pattern 1
- [`nodejs/package.json`](nodejs/package.json) — `globalpayments-api` ^3.10.6

### .NET (ASP.NET Core + Global Payments SDK)
- [`dotnet/Services/ReportingService.cs`](dotnet/Services/ReportingService.cs) — `GlobalPaymentsReportingService`; all methods async (`SearchTransactionsAsync()`, `GetSettlementReportAsync()`, `GetDisputeReportAsync()`, `GetDepositReportAsync()`, `ExportTransactionsAsync()`); `GetBatchReportAsync()` is a stub returning empty data
- [`dotnet/Controllers/ReportsController.cs`](dotnet/Controllers/ReportsController.cs) — `ReportsController`; routes `GET/POST /api/reports/search`, `/api/reports/settlement`, `/api/reports/disputes`, `/api/reports/deposits`, `/api/reports/detail/{transactionId}`, `/api/reports/export`, `/api/reports/batches`, `/api/reports/declines`, `/api/reports/date-range`, `/api/reports/summary`, `/api/reports/config`
- [`dotnet/Program.cs`](dotnet/Program.cs) — `ConfigureGlobalPaymentsSDK()`; registers `GlobalPaymentsReportingService` singleton; `GET /config` minimal API endpoint
- [`dotnet/dotnet.csproj`](dotnet/dotnet.csproj) — `GlobalPayments.Api` 9.0.16, net9.0

### Java (Jakarta EE servlet + Global Payments SDK)
- [`java/src/main/java/com/globalpayments/example/ReportingService.java`](java/src/main/java/com/globalpayments/example/ReportingService.java) — `configureGpApiSdk()`; full reporting methods including `searchTransactions()`, `getSettlementReport()`, `getDisputeReport()`, `getDepositReport()`; `getBatchReport()` returns empty placeholder data
- [`java/src/main/java/com/globalpayments/example/ReportsController.java`](java/src/main/java/com/globalpayments/example/ReportsController.java) — `@WebServlet(urlPatterns = {"/reports"})`; `handleRequest()` dispatches by `?action=`
- [`java/pom.xml`](java/pom.xml) — `globalpayments-sdk` 14.2.20

### Python (Flask + Global Payments SDK)
- [`python/reporting_service.py`](python/reporting_service.py) — `GlobalPaymentsReportingService` class; `search_transactions()`, `get_settlement_report()`, `get_dispute_report()`, `get_deposit_report()`, `get_batch_report()`, `export_transactions()`, `get_summary_stats()`
- [`python/reports.py`](python/reports.py) — Flask blueprint `reports_bp`; single route dispatching `?action=`
- [`python/server.py`](python/server.py) — main entry; registers `reports_bp`; contains a template `/process-payment` Portico stub — see Critical Pattern 1
- [`python/requirements.txt`](python/requirements.txt) — Flask 3.0, `globalpayments.api` 2.0.4

### Go (net/http + Global Payments SDK)
- [`go/reporting_service.go`](go/reporting_service.go) — `ReportingService` struct; `NewReportingService()`, `configureSDK()`, `SearchTransactions()`, `GetSettlementReport()`, `GetDisputeReport()`, `GetDepositReport()`, `GetBatchReport()`; response format helpers are stubs returning empty slices
- [`go/reports.go`](go/reports.go) — `InitializeReportingAPI()`; registers REST routes `/reports/search`, `/reports/detail`, `/reports/settlement`, `/reports/export`, `/reports/disputes`, `/reports/dispute/{id}`, `/reports/deposits`, `/reports/deposit/{id}`, `/reports/batches`, `/reports/declines`, `/reports/date-range`, `/reports/config`; also dispatches `?action=` for compatibility
- [`go/main_reporting.go`](go/main_reporting.go) — reporting server entry point; default port `8080`
- [`go/main.go`](go/main.go) — template payment stub; do not run for reporting — see Critical Pattern 1
- [`go/go.mod`](go/go.mod) — `github.com/globalpayments/go-sdk` v1.1.3

### Shared
- [`index.html`](index.html) — root reporting dashboard frontend
- [`docker-compose.yml`](docker-compose.yml) — six services: Node.js :8001, Python :8002, PHP :8003, Java :8004, Go :8005, .NET :8006

## API Surface

Routing patterns differ by language; the action set is consistent across all implementations.

| Method | Path | Languages | Purpose |
|--------|------|-----------|---------|
| GET | `/config` | Node.js, Python, Java, .NET | Environment and SDK status |
| GET | `/config.php` | PHP | Environment and SDK status |
| GET | `/reports/config` | Go | SDK status and endpoint list (no top-level `/config` in reporting server) |
| GET/POST | `/reports.php?action=` | PHP | Action-based dispatch |
| GET/POST | `/reports?action=` | Python, Java | Action-based dispatch |
| GET/POST | `/api/reports?action=` | Node.js | Action-based dispatch |
| GET/POST | `/reports/*` | Go | REST sub-paths; also accepts `?action=` |
| GET/POST | `/api/reports/*` | .NET | REST sub-paths via controller |

**Shared actions:** `search`, `detail`, `settlement`, `export`, `summary`, `disputes`, `dispute_detail`, `deposits`, `deposit_detail`, `batches`, `declines`, `date_range`, `config`

## Environment Variables

```bash
GP_API_APP_ID=your_app_id    # GP API application ID
GP_API_APP_KEY=your_app_key  # GP API application key
PORT=8000                     # Optional; defaults to 8000 (Go reporting entry defaults to 8080)
```

> **Note:** All six `.env.sample` files contain both the scaffolding-template vars (`PUBLIC_API_KEY`, `SECRET_API_KEY`) and the reporting-service vars (`GP_API_APP_ID`, `GP_API_APP_KEY`). The scaffolding vars are not used by the reporting modules. Copy `.env.sample` to `.env` and fill in `GP_API_APP_ID` and `GP_API_APP_KEY` with your sandbox credentials.

Get sandbox credentials at [developer.globalpayments.com](https://developer.globalpayments.com).

## Architecture Summary

**Query flow:** HTML form → `POST /reports?action=search` → reporting service module → `ReportingService::findTransactionsPaged(page, pageSize)` → filters applied (date, payment type, amount, card) → status filtered client-side → JSON response

**Detail flow:** `GET /reports?action=detail&transaction_id={id}` → `ReportingService::transactionDetail($id)` → full transaction object returned

## Security Notes

No authentication on any reporting endpoint. All six implementations expose full transaction history without auth middleware. For production: add auth, restrict results to the authenticated merchant, and use a secrets manager instead of `.env` files.

## How to Run

```bash
cd php && ./run.sh       # PHP — :8000
cd nodejs && ./run.sh    # Node.js — :8000
cd dotnet && ./run.sh    # .NET — :8000
cd java && ./run.sh      # Java — :8000
cd python && ./run.sh    # Python — :8000

# Go: run.sh starts the payment stub (main.go), not the reporting server
cd go && go run reporting_service.go reports.go main_reporting.go  # Go — :8080

# All at once:
docker-compose up
```

## How to Verify

```bash
# Config (Node.js, Python, Java, .NET — same path; PHP uses /config.php)
curl http://localhost:8000/config
# Expected: {"success":true,"data":{"publicApiKey":"..."}}

# Search transactions
curl "http://localhost:8000/reports?action=search&start_date=2024-01-01&end_date=2024-12-31&page=1&page_size=10"
# Expected: {"success":true,"data":{"transactions":[...],"total_count":N,"page":1,"page_size":10}}

# Transaction detail
curl "http://localhost:8000/reports?action=detail&transaction_id=TRN_xxxx"
# Expected: {"success":true,"data":{...}}

# Export as CSV
curl "http://localhost:8000/reports?action=export&format=csv&start_date=2024-01-01&end_date=2024-12-31" -o transactions.csv
```

## Making Changes

All language implementations expose identical reporting behavior. A change to one must be applied to all — each language in a separate commit. Do not modify shared files (`index.html`, `docker-compose.yml`) without confirming the change applies to every implementation.

Note: `GetBatchReportAsync()` (.NET), `getBatchReport()` (Java), and `GetBatchReport()` (Go) are placeholder stubs returning empty data.

## SDK Versions

- **PHP**: `globalpayments/php-sdk` ^13.1
- **Node.js**: `globalpayments-api` ^3.10.6
- **Java**: `globalpayments-sdk` 14.2.20
- **.NET**: `GlobalPayments.Api` 9.0.16
- **Python**: `globalpayments.api` 2.0.4
- **Go**: `github.com/globalpayments/go-sdk` v1.1.3
