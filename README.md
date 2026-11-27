# Reporting Service

A transaction reporting service built on GP API. Developers can search, filter, and export transaction data through an interactive web UI and REST API, covering settlements, disputes and deposits, next to a card payment example. The reports are read from GP API with `GP_API_APP_ID` and `GP_API_APP_KEY` (`GpApiConfig` in the SDKs; Go calls GP API directly because the Go SDK has no GP API support). The payment example uses Portico keys. All six languages give the same answers for the same request.

Available in six languages: PHP, Node.js, .NET, Java, Python, and Go.

---

## Available Implementations

| Language | Framework | SDK Version |
|----------|-----------|-------------|
| [**PHP**](./php/) | Built-in Server | globalpayments/php-sdk ^13.1 |
| [**Node.js**](./nodejs/) | Express.js | globalpayments-api ^3.10.6 |
| [**.NET**](./dotnet/) | ASP.NET Core | GlobalPayments.Api 9.0.16 |
| [**Java**](./java/) | Jakarta Servlet | globalpayments-sdk 14.2.20 |
| [**Python**](./python/) | Flask | globalpayments.api 2.0.8 |
| [**Go**](./go/) | net/http | go-sdk v1.1.5 (payments); GP API called directly for reports |

---

## How It Works

The service exposes two categories of endpoints under a three-tab UI:

1. **Payment processing** — charge a card using Portico hosted fields (`POST /process-payment`)
2. **Transaction reporting**: search and export transaction history from GP API (`GET /api/reports?action=...`; PHP answers on `/reports.php?action=...` and Java on `/reports?action=...`)

```
Browser (three-tab UI)
  │
  ├─ Tab 1: Payment Form
  │   ├─ GET /config ─────► publicApiKey for globalpayments.js initialization
  │   └─ POST /process-payment ─────► SDK: CreditCardData.charge().execute()
  │
  ├─ Tab 2: API Documentation
  │   └─ Built-in reference for all reporting endpoints
  │
  └─ Tab 3: Transaction Report
      └─ GET /api/reports?action=search|detail|settlement|export|summary|declines
          └─ SDK: ReportingService queries GP API
```

---

## Prerequisites

- Global Payments developer account: [sign up at developer.globalpayments.com](https://developer.globalpayments.com)
- GP API sandbox app credentials for the reports: `GP_API_APP_ID` and `GP_API_APP_KEY`
- Two API keys from your Portico account for the payment example:
  - `PUBLIC_API_KEY` — prefixed `pkapi_cert_...` (sandbox)
  - `SECRET_API_KEY` — prefixed `skapi_cert_...` (sandbox)
- Docker (for multi-service setup), or a local runtime for your chosen language

---

## Quick Start

### 1. Clone the repository

```bash
git clone https://github.com/globalpayments-samples/reporting-service.git
cd reporting-service
```

### 2. Choose a language and configure credentials

```bash
cd nodejs    # or php, dotnet, java, python, go
cp .env.sample .env
```

Edit `.env`:

```env
GP_API_APP_ID=your_gp_api_app_id
GP_API_APP_KEY=your_gp_api_app_key
PUBLIC_API_KEY=pkapi_cert_your_key_here
SECRET_API_KEY=skapi_cert_your_key_here
```

### 3. Install and run

**PHP:**
```bash
cd php && ./run.sh
```

**Node.js:**
```bash
npm install
npm start
```

**.NET:**
```bash
dotnet restore
dotnet run
```

**Java:**
```bash
mvn clean package
mvn cargo:run
```

**Python:**
```bash
pip install -r requirements.txt
python app.py
```

**Go:**
```bash
go mod download
go run main.go
```

### 4. Explore the UI

Open the app (e.g. http://localhost:8001) and navigate the three tabs:

- **Payment Form** — process a test transaction
- **Reporting Documentation** — browse all available report endpoints
- **Transaction Report** — search, filter, and export transaction history

---

## Docker Setup

Run all six language implementations simultaneously:

```bash
cp nodejs/.env.sample .env
# Edit .env in the repository root with your credentials, then:
docker-compose up
```

`docker-compose.yml` passes `GP_API_APP_ID`, `GP_API_APP_KEY`, `PUBLIC_API_KEY` and `SECRET_API_KEY` from that file to every service.

Individual services:

```bash
docker-compose up nodejs    # http://localhost:8001
docker-compose up python    # http://localhost:8002
docker-compose up php       # http://localhost:8003
docker-compose up java      # http://localhost:8004
docker-compose up go        # http://localhost:8005
docker-compose up dotnet    # http://localhost:8006
```

Run integration tests:

```bash
docker-compose --profile testing up
```

---

## API Endpoints

### `GET /config`

Returns the public API key for globalpayments.js initialization on the payment form.

**Response:**
```json
{
  "success": true,
  "data": {
    "publicApiKey": "pkapi_cert_..."
  }
}
```

---

### `POST /process-payment`

Processes a credit card charge using a tokenized payment reference from the hosted fields form.

**Request body:**
```json
{
  "payment_token": "supt_...",
  "amount": "19.99",
  "billing_zip": "30303"
}
```

**Success (`200`):**
```json
{
  "success": true,
  "data": {
    "transactionId": "1234567890",
    "responseCode": "00",
    "responseMessage": "Approved"
  }
}
```

---

### `GET /api/reports`

All reporting actions use a single endpoint with an `action` query parameter (`/api/reports` in Node.js, Python, Go and .NET, `/reports.php` in PHP, `/reports` in Java). Without an action it answers the API documentation, an unknown action answers `400 VALIDATION_ERROR`.

#### `action=search` — Search transactions

| Parameter | Required | Description |
|-----------|----------|-------------|
| `start_date` | No | `YYYY-MM-DD` |
| `end_date` | No | `YYYY-MM-DD` |
| `transaction_id` | No | Exact transaction ID |
| `status` | No | Transaction status filter |
| `payment_type` | No | Payment method type |
| `amount_min` | No | Minimum amount |
| `amount_max` | No | Maximum amount |
| `card_last_four` | No | Last 4 digits of card |
| `page` | No | Page number (default: `1`) |
| `page_size` | No | Results per page (max: `100`) |

**Example:** `GET /api/reports?action=search&start_date=2025-01-01&end_date=2025-01-31`

---

#### `action=detail` — Get transaction details

| Parameter | Required | Description |
|-----------|----------|-------------|
| `transaction_id` | Yes | Transaction ID to look up |

**Example:** `GET /api/reports?action=detail&transaction_id=1234567890`

---

#### `action=settlement` — Settlement report

| Parameter | Required | Description |
|-----------|----------|-------------|
| `start_date` | No | `YYYY-MM-DD` |
| `end_date` | No | `YYYY-MM-DD` |
| `page` | No | Page number |
| `page_size` | No | Results per page (max: `100`) |

---

#### `action=export` — Export transaction data

| Parameter | Required | Description |
|-----------|----------|-------------|
| `format` | No | `json` (default), `csv`, or `xml` |
| `start_date` | No | `YYYY-MM-DD` |
| `end_date` | No | `YYYY-MM-DD` |
| `transaction_id` | No | Filter by ID |
| `status` | No | Filter by status |

**Example:** `GET /api/reports?action=export&format=csv&start_date=2025-01-01`

---

#### `action=summary` — Transaction summary statistics

Returns aggregate counts and totals across a time range.

---

#### `action=declines` — Declined transaction analysis

Returns declined transactions with decline reason codes.

---

#### Disputes, deposits and the date range report

`action=disputes` (filters `stage`, `status`), `action=dispute_detail&dispute_id=...`, `action=deposits` (filters `deposit_id`, `status`), `action=deposit_detail&deposit_id=...` and `action=date_range` (transactions, settlements, disputes and deposits in one answer).

#### `action=batches`

GP API has no batch report, so every language answers `400 API_ERROR` with `Batch report generation failed: batch reports are not available through GP API`.

---

## Project Structure

```
reporting-service/
├── index.html              # Shared frontend (three-tab UI)
├── docker-compose.yml      # Multi-service Docker config
├── Dockerfile.tests
├── LICENSE
├── README.md
│
├── php/                    # Port 8003
│   ├── config.php          # GET /config
│   ├── process-payment.php # POST /process-payment
│   ├── reports.php         # GET /api/reports
│   ├── reporting-service.php
│   └── documentation.php
│
├── nodejs/                 # Port 8001
│   ├── server.js           # /config, /process-payment
│   ├── reports.js          # /api/reports router
│   └── reporting-service.js
│
├── dotnet/                 # Port 8006
│   └── Program.cs
│
├── java/                   # Port 8004
│   └── src/
│
├── python/                 # Port 8002
│   └── app.py
│
└── go/                     # Port 8005
    └── main.go
```

---

## Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `GP_API_APP_ID` | GP API app ID for the reports | `T6og1tbE...` |
| `GP_API_APP_KEY` | GP API app key for the reports | `qx3V...` |
| `PUBLIC_API_KEY` | Portico public key for hosted fields | `pkapi_cert_jKc1Ft...` |
| `SECRET_API_KEY` | Portico secret key for server-side SDK | `skapi_cert_MTyM...` |

The `.env` file is optional in every language: variables set in the environment (Docker, CI) are read as well.

---

## Test Cards (Sandbox)

| Brand | Card Number | CVV | Expiry |
|-------|-------------|-----|--------|
| Visa | 4012002000060016 | 123 | Any future |
| Mastercard | 5473500000000014 | 123 | Any future |
| Discover | 6011000990156527 | 123 | Any future |
| Amex | 372700699251018 | 1234 | Any future |

---

## Troubleshooting

**Reports return empty results**
Sandbox accounts may have limited transaction history. Process a test payment in Tab 1 first, then search for it in Tab 3.

**`VALIDATION_ERROR: Failed to configure SDK: Missing required environment variable: GP_API_APP_ID`**
Set `GP_API_APP_ID` and `GP_API_APP_KEY` (GP API sandbox credentials). The docs and `action=config` answer without them.

**`401 Unauthorized` from Portico**
Verify `PUBLIC_API_KEY` and `SECRET_API_KEY` in `.env` match the `pkapi_cert_` / `skapi_cert_` format for sandbox.

**`Invalid start_date format`**
Dates must be `YYYY-MM-DD`. Example: `2025-01-15`.

**Port conflict**
Check which service is running (`lsof -i :8001`) and update the port in `docker-compose.yml`.

## Community

- 🌐 **Developer Portal** — [developer.globalpayments.com](https://developer.globalpayments.com)
- 💬 **Discord** — [Join the community](https://discord.gg/myER9G9qkc)
- 📋 **GitHub Discussions** — [github.com/orgs/globalpayments/discussions](https://github.com/orgs/globalpayments/discussions)
- 📧 **Newsletter** — [Subscribe](https://www.globalpayments.com/en-gb/modals/newsletter)
- 💼 **LinkedIn** — [Global Payments for Developers](https://www.linkedin.com/showcase/global-payments-for-developers/posts/?feedView=all)

Have a question or found a bug? [Open an issue](https://github.com/globalpayments-samples/reporting-service/issues) or reach out at [communityexperience@globalpay.com](mailto:communityexperience@globalpay.com).

---

## License

MIT — see [LICENSE](./LICENSE).
