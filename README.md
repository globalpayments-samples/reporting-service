# Reporting Service

A complete transaction reporting service built on the Global Payments Portico gateway. Developers can search, filter, and export transaction data through an interactive web UI and REST API, covering settlements, disputes, deposits, and batch details — alongside live credit card payment processing.

Available in six languages: PHP, Node.js, .NET, Java, Python, and Go.

---

## Available Implementations

| Language | Framework | SDK Version | Port |
|----------|-----------|-------------|------|
| [**PHP**](./php/) | Built-in Server | globalpayments/php-sdk ^13.1 | 8003 |
| [**Node.js**](./nodejs/) | Express.js | globalpayments-api ^3.10.6 | 8001 |
| [**.NET**](./dotnet/) | ASP.NET Core | GlobalPayments.Api 9.0.16 | 8006 |
| [**Java**](./java/) | Jakarta Servlet | globalpayments-sdk 14.2.20 | 8004 |
| [**Python**](./python/) | Flask | globalpayments | latest | 8002 |
| [**Go**](./go/) | net/http | globalpayments-go | latest | 8005 |

Preview links (runs in browser via CodeSandbox):
- [PHP Preview](https://githubbox.com/globalpayments-samples/reporting-service/tree/main/php)
- [Node.js Preview](https://githubbox.com/globalpayments-samples/reporting-service/tree/main/nodejs)
- [.NET Preview](https://githubbox.com/globalpayments-samples/reporting-service/tree/main/dotnet)
- [Java Preview](https://githubbox.com/globalpayments-samples/reporting-service/tree/main/java)

---

## How It Works

The service exposes two categories of endpoints under a three-tab UI:

1. **Payment processing** — charge a card using Portico hosted fields (`POST /process-payment`)
2. **Transaction reporting** — search and export transaction history via the Reporting SDK (`GET /api/reports?action=...`)

```
Browser (three-tab UI)
  │
  ├─ Tab 1: Payment Form
  │   ├─ GET /config ─────► publicApiKey for Heartland.js initialization
  │   └─ POST /process-payment ─────► SDK: CreditCardData.charge().execute()
  │
  ├─ Tab 2: API Documentation
  │   └─ Built-in reference for all reporting endpoints
  │
  └─ Tab 3: Transaction Report
      └─ GET /api/reports?action=search|detail|settlement|export|summary|declines
          └─ SDK: ReportingService queries via Portico
```

---

## Prerequisites

- Global Payments developer account with Portico credentials — [Sign up at developer.globalpayments.com](https://developer.globalpayments.com)
- Two API keys from your Portico account:
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
PUBLIC_API_KEY=pkapi_cert_your_key_here
SECRET_API_KEY=skapi_cert_your_key_here
```

### 3. Install and run

**PHP:**
```bash
composer install
php -S localhost:8003
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
cp .env.sample .env
# Edit .env with your credentials, then:
docker-compose up
```

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

Returns the public API key for Heartland.js initialization on the payment form.

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

All reporting actions use a single endpoint with an `action` query parameter.

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
| `PUBLIC_API_KEY` | Portico public key for hosted fields | `pkapi_cert_jKc1Ft...` |
| `SECRET_API_KEY` | Portico secret key for server-side SDK | `skapi_cert_MTyM...` |

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

**`401 Unauthorized` from Portico**
Verify `PUBLIC_API_KEY` and `SECRET_API_KEY` in `.env` match the `pkapi_cert_` / `skapi_cert_` format for sandbox.

**`Invalid start_date format`**
Dates must be `YYYY-MM-DD`. Example: `2025-01-15`.

**Port conflict**
Check which service is running (`lsof -i :8001`) and update the port in `docker-compose.yml`.

---

## License

MIT — see [LICENSE](./LICENSE).
