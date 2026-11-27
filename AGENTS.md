# Global Payments Reporting Service

> Query and export transaction, settlement, dispute and deposit reports via the Global Payments GP API, demonstrated in PHP, Node.js, .NET, Java, Python, and Go. All six give the same answers for the same request (GP API has no batch report, `batches` answers `API_ERROR` everywhere).

## Critical Patterns

1. **The server entry files are scaffolding artifacts, use the reporting module files instead.** `server.js`, `server.py`, `main.go`, and `ProcessPaymentServlet.java` each contain a `PorticoConfig`/`SECRET_API_KEY` stub for a `/process-payment` endpoint unrelated to this sample. The actual reporting logic lives in `reporting-service.php`, `reporting-service.js`, `reporting_service.py`, `ReportingService.java`, `ReportingService.cs`, and `reporting_service.go`, all of which use GP API with `GP_API_APP_ID`/`GP_API_APP_KEY` (`GpApiConfig`, except Go, see below). Adding reporting logic to the stub files will configure the wrong gateway. In Node.js the Portico stub is registered as the named config `portico` (and charges with `execute('portico')`), so it does not replace the GP API `default` config the reports use; .NET registers the reports' GP API config as `reports`.

2. **Transaction status and the amount range are applied to the returned page, not sent to GP API.** The transaction search sends dates (`from_time_created`/`to_time_created`), `id`, `type` (`SALE` or `REFUND`, any other `payment_type` is a `VALIDATION_ERROR`) and `number_last4`. GP API only filters on an exact amount, so `amount_min`/`amount_max` and `status` filter the page in application code in all six languages. Dispute and deposit reports DO send `stage`/`status` to GP API (disputes filter on `from_stage_time_created`/`to_stage_time_created`).

3. **Dates go to the SDKs through `where(SearchCriteria.StartDate, ...)`, not `withStartDate()`.** In the Node.js, Python and .NET SDKs the `withStartDate()` the report builders expose stores the date where the GP API request builder does not read it, so the date filter is silently dropped. Dates are `YYYY-MM-DD` strings converted to native dates (PHP `\DateTime::createFromFormat('!Y-m-d', ...)`, Java `SimpleDateFormat("yyyy-MM-dd")`, .NET `DateTime.ParseExact`, Node.js a UTC date for transactions and a local date for disputes because its SDK formats them differently).

4. **All paged SDK methods take `(page, pageSize)` as the first two positional parameters.** `findTransactionsPaged($page, $pageSize)`, `findSettlementTransactionsPaged()`, `findDisputesPaged()`, and `findDepositsPaged()` all follow this signature. Omitting either argument silently falls back to SDK defaults. GP API only sends `total_record_count` on page 1, so `original_total_count` is `0` from page 2 on in every language.

5. **Where an SDK builder or mapping is broken, the report is read through that SDK's own GP API connector and mapped with its `GpApiMapping`.** Python, PHP, Node.js and Java call the connector's `do_transaction`/`doTransaction`; the .NET connector is internal, so .NET does a small GET with an SDK access token (`GpApiService.GenerateTransactionKey`). Each such place has a comment naming the SDK bug. Responses are compared across the six languages against the real sandbox (same status, content type, values, ISO timestamps, error texts such as `Status Code: RESOURCE_NOT_FOUND - ...`).

## Repository Structure

### PHP (native PHP + Global Payments SDK)
- [`php/reporting-service.php`](php/reporting-service.php) — `GlobalPaymentsReportingService` class; `searchTransactions()`, `getTransactionDetails()`, `getSettlementReport()`, `getDisputeReport()`, `getDisputeDetails()`, `getDepositReport()`, `getDepositDetails()`, `getBatchReport()`, `exportTransactions()`, `getSummaryStats()`
- [`php/reports.php`](php/reports.php) — action-based router; dispatches `?action=search|detail|settlement|export|summary|disputes|dispute_detail|deposits|deposit_detail|batches|declines|date_range|config`
- [`php/sdk-config.php`](php/sdk-config.php: `configureGpApiSdk()`, `getSdkConfigStatus()`, `validateEnvironmentConfig()`; `envValue()` reads `.env` when there is one and the process environment otherwise (Docker)
- The transaction search and detail and the deposit detail go through the SDK connector (`gpApiGet()`): the php-sdk 13.3 transaction mapping never fills the card brand or auth code, and the deposit mapping leaves out the bank account
- [`php/composer.json`](php/composer.json) — `globalpayments/php-sdk` ^13.1

### Node.js (Express + Global Payments SDK)
- [`nodejs/reporting-service.js`](nodejs/reporting-service.js: `GlobalPaymentsReportingService` class; `configureGpApiSdk()`, full reporting method set on the SDK's report builders with `where()`; the transaction detail and the deposit detail are read through the SDK connector (`gpApiGet()`) because the SDK mapping leaves out the gateway result code and the bank account
- [`nodejs/reports.js`](nodejs/reports.js: Express router mounted at `/api/reports`; dispatches via `?action=` or REST sub-paths (`/api/reports/search`, `/api/reports/date-range`, etc.); docs and `config` answer without credentials
- [`nodejs/server.js`](nodejs/server.js) — main entry; mounts reporting router at `/api/reports`; contains a template `/process-payment` Portico stub — see Critical Pattern 1
- [`nodejs/package.json`](nodejs/package.json) — `globalpayments-api` ^3.10.6

### .NET (ASP.NET Core + Global Payments SDK)
- [`dotnet/Services/ReportingService.cs`](dotnet/Services/ReportingService.cs: `GlobalPaymentsReportingService`; `SearchTransactionsAsync()`, `GetSettlementReportAsync()`, `GetDisputeReportAsync()`, `GetDepositReportAsync()`, `ExportTransactionsAsync()` and the rest; configures its own `reports` SDK config on first use; `GpApiGet()` reads the transaction search, transaction detail, dispute detail and deposit detail (the SDK's 3DS mapping throws on `exempt_status`, and its mapping leaves out the result code, the dispute's transaction amount and the bank account)
- [`dotnet/Controllers/ReportsController.cs`](dotnet/Controllers/ReportsController.cs: `ReportsController`; `GET/POST /api/reports?action=` (what the page calls) and `/api/reports/{action}` (`search`, `date-range`, ...), plus `/api/reports/detail/{id}`, `/api/reports/disputes/{id}`, `/api/reports/deposits/{id}`
- [`dotnet/Program.cs`](dotnet/Program.cs) — `ConfigureGlobalPaymentsSDK()`; registers `GlobalPaymentsReportingService` singleton; `GET /config` minimal API endpoint
- [`dotnet/dotnet.csproj`](dotnet/dotnet.csproj) — `GlobalPayments.Api` 9.0.16, net10.0

### Java (Jakarta EE servlet + Global Payments SDK)
- [`java/src/main/java/com/globalpayments/example/ReportingService.java`](java/src/main/java/com/globalpayments/example/ReportingService.java: `configureGpApiSdk()`; full reporting methods including `searchTransactions()`, `getSettlementReport()`, `getDisputeReport()`, `getDepositReport()`; `.env` is optional (`Dotenv.configure().ignoreIfMissing()`); the transaction search and detail and the dispute list and detail go through the SDK connector (`gpApiGet()`) because the SDK fails on 3DS exemptions it has no enum for (`LOW_VALUE`), reports the page size as the total on page 2 on, sends the stage `GOODFAITH` (GP API wants `GOOD_FAITH`) and prefixes errors with the HTTP status
- [`java/src/main/java/com/globalpayments/example/ReportsController.java`](java/src/main/java/com/globalpayments/example/ReportsController.java) — `@WebServlet(urlPatterns = {"/reports"})`; `handleRequest()` dispatches by `?action=`
- [`java/pom.xml`](java/pom.xml: `globalpayments-sdk` 14.2.20; another port with `mvn cargo:run -Dcargo.servlet.port=8001`

### Python (Flask + Global Payments SDK)
- [`python/reporting_service.py`](python/reporting_service.py) — `GlobalPaymentsReportingService` class; `search_transactions()`, `get_settlement_report()`, `get_dispute_report()`, `get_deposit_report()`, `get_batch_report()`, `export_transactions()`, `get_summary_stats()`
- [`python/reports.py`](python/reports.py) — Flask blueprint `reports_bp`; one handler at `/api/reports` (same path as Node.js, used by the page) and `/reports`, dispatching `?action=`; docs and `config` answer without credentials
- [`python/server.py`](python/server.py) — main entry; registers `reports_bp`; contains a template `/process-payment` Portico stub — see Critical Pattern 1
- [`python/requirements.txt`](python/requirements.txt) — Flask 3.1, `globalpayments.api` 2.0.8 (`GpApiConfig` and the GP API connector arrived in 2.0.5; 2.0.4 has no GP API support). The SDK ignores `config.environment` for the URL, so `service_url` is set to the sandbox explicitly. Pass dates, ids and filters with `where(SearchCriteria...)`: `with_start_date()`, `with_dispute_id()` and `with_deposit_id()` are not read by its GP API request builder. The transaction search and the dispute list go through `_gp_api_list()` (the SDK connector and mapping) because the SDK request builder sends an empty `amount=` on `/transactions` (GP API answers `SYSTEM_ERROR_DOWNSTREAM`) and `from`/`to` instead of `from_stage_time_created`/`to_stage_time_created` on `/disputes`

### Go (net/http + Global Payments SDK)
- [`go/gpapi.go`](go/gpapi.go) — small GP API client (access token, `GET` with `X-GP-Version`). The Go SDK has no GP API support at all (only `PorticoConfig`, up to v1.1.5), so the reports call `/transactions`, `/settlement/transactions`, `/disputes` and `/settlement/deposits` directly; settlement and deposit calls send the `DAA_` data account from the token response
- [`go/reporting_service.go`](go/reporting_service.go) — `ReportingService` struct; `SearchTransactions()`, `GetTransactionDetails()`, `GetSettlementReport()`, `GetDisputeReport()`, `GetDisputeDetails()`, `GetDepositReport()`, `GetDepositDetails()`, `GetDateRangeReport()`, `ExportTransactions()`, `GetSummaryStats()`; the response shapes follow `nodejs/reporting-service.js`
- [`go/reports.go`](go/reports.go) — `handleReports()`; `/api/reports?action=` like Node.js (also `/reports?action=`, `/api/reports/{action}`, `/reports/dispute/{id}`, `/reports/deposit/{id}`)
- [`go/main.go`](go/main.go) — the only entry point: page, `/config`, the Portico `/process-payment` stub (see Critical Pattern 1) and the reporting routes; `go run .`
- [`go/go.mod`](go/go.mod) — `github.com/globalpayments/go-sdk` v1.1.5 (used for the Portico payment only)

### Shared
- [`index.html`](index.html) — root reporting dashboard frontend
- [`docker-compose.yml`](docker-compose.yml) — six services: Node.js :8001, Python :8002, PHP :8003, Java :8004, Go :8005, .NET :8006

## API Surface

Routing patterns differ by language; the action set is consistent across all implementations.

| Method | Path | Languages | Purpose |
|--------|------|-----------|---------|
| GET | `/config` | Node.js, Python, Java, .NET, Go | Environment and SDK status |
| GET | `/config.php` | PHP | Environment and SDK status |
| GET/POST | `/reports.php?action=` | PHP | Action-based dispatch |
| GET/POST | `/reports?action=` | Java | Action-based dispatch |
| GET/POST | `/api/reports?action=` | Node.js, Python, Go | Action-based dispatch (Python and Go also answer on `/reports`) |
| GET/POST | `/api/reports/*` | .NET | REST sub-paths via controller |

**Shared actions:** `search`, `detail`, `settlement`, `export`, `summary`, `disputes`, `dispute_detail`, `deposits`, `deposit_detail`, `batches`, `declines`, `date_range`, `config`

## Environment Variables

```bash
GP_API_APP_ID=your_app_id    # GP API application ID
GP_API_APP_KEY=your_app_key  # GP API application key
PORT=8000                     # Optional; defaults to 8000
```

> **Note:** All six `.env.sample` files contain both the reporting-service vars (`GP_API_APP_ID`, `GP_API_APP_KEY`) and the scaffolding-template vars (`PUBLIC_API_KEY`, `SECRET_API_KEY`). The scaffolding vars are not used by the reporting modules. Copy `.env.sample` to `.env` and fill in `GP_API_APP_ID` and `GP_API_APP_KEY` with your sandbox credentials. `.env` is optional in every language; `docker-compose.yml` passes all four variables to every service from the root `.env`.

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

cd go && ./run.sh        # Go — :8000

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

Note: GP API has no batch report: all six languages answer `batches` with `400 API_ERROR` (`Batch report generation failed: batch reports are not available through GP API`).

## SDK Versions

- **PHP**: `globalpayments/php-sdk` ^13.1
- **Node.js**: `globalpayments-api` ^3.10.6
- **Java**: `globalpayments-sdk` 14.2.20
- **.NET**: `GlobalPayments.Api` 9.0.16
- **Python**: `globalpayments.api` 2.0.8
- **Go**: `github.com/globalpayments/go-sdk` v1.1.5 (Portico only; the reports call GP API directly)
