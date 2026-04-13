# PHP — Reporting Service

PHP implementation of the Global Payments transaction reporting service using the Portico gateway. Provides credit card payment processing and a full reporting API for searching, filtering, and exporting transaction data.

---

## Requirements

- PHP 8.0+
- Composer
- Global Payments Portico credentials (`PUBLIC_API_KEY`, `SECRET_API_KEY`)

---

## Project Structure

```
php/
├── .env.sample             # Environment variable template
├── composer.json           # Dependencies (globalpayments/php-sdk ^13.1)
├── Dockerfile
├── run.sh
├── sdk-config.php          # Shared PorticoConfig setup
├── config.php              # GET /config
├── process-payment.php     # POST /process-payment
├── reports.php             # GET /reports dispatcher
├── reporting-service.php   # Reporting SDK wrapper
├── documentation.php       # Built-in API docs page
├── index.html              # Three-tab UI frontend
└── REPORTING_README.md     # Full reporting endpoint reference
```

---

## Setup

```bash
cp .env.sample .env
```

Edit `.env`:

```env
PUBLIC_API_KEY=pkapi_cert_your_key_here
SECRET_API_KEY=skapi_cert_your_key_here
```

Install and run:

```bash
composer install
php -S localhost:8003
```

Open: http://localhost:8003

---

## Docker

```bash
docker build -t reporting-service-php .
docker run -p 8003:8000 --env-file ../.env reporting-service-php
```

---

## Implementation

### SDK Configuration (`sdk-config.php`)

```php
use GlobalPayments\Api\ServiceConfigs\Gateways\PorticoConfig;
use GlobalPayments\Api\ServicesContainer;

$config = new PorticoConfig();
$config->secretApiKey = $_ENV['SECRET_API_KEY'];
$config->serviceUrl   = 'https://cert.api2.heartlandportico.com';
ServicesContainer::configureService($config);
```

### Endpoint Routing

PHP uses separate files per endpoint rather than a router:

| URL | File | Method |
|-----|------|--------|
| `/config.php` | `config.php` | GET |
| `/process-payment.php` | `process-payment.php` | POST |
| `/reports.php?action=...` | `reports.php` | GET |

---

## API Endpoints

### `GET /config.php`

Returns public API key for globalpayments.js hosted fields initialization.

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

### `POST /process-payment.php`

Processes a credit card charge via tokenized payment reference.

**Request:**
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

### `GET /reports.php?action=...`

All reporting queries use a single dispatcher file with an `action` parameter.

| Action | Description | Required Params |
|--------|-------------|-----------------|
| `search` | Search transactions | none (all optional) |
| `detail` | Get transaction details | `transaction_id` |
| `settlement` | Settlement report | none |
| `export` | Export data | `format` (json/csv/xml) |
| `summary` | Summary statistics | none |
| `declines` | Declined transactions | none |

**Search example:**
```bash
curl "http://localhost:8003/reports.php?action=search&start_date=2025-01-01&end_date=2025-01-31&page=1&page_size=10"
```

**Export CSV:**
```bash
curl "http://localhost:8003/reports.php?action=export&format=csv&start_date=2025-01-01" -o transactions.csv
```

**Get transaction detail:**
```bash
curl "http://localhost:8003/reports.php?action=detail&transaction_id=1234567890"
```

See [REPORTING_README.md](REPORTING_README.md) for the full endpoint reference.

---

## Using the UI

Open http://localhost:8003 and navigate the three tabs:

1. **Payment Form** — process a test card transaction
2. **Reporting Documentation** — browse all reporting endpoints
3. **Transaction Report** — interactive table with search, filters, pagination, and export

In the Transaction Report tab:
- Click the filter icon to open date/status filters
- Click any transaction ID to view full details
- Use the export buttons to download JSON or CSV
- Use Previous/Next to paginate results

---

## Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `PUBLIC_API_KEY` | Portico public key for hosted fields | `pkapi_cert_jKc1Ft...` |
| `SECRET_API_KEY` | Portico secret key for SDK auth | `skapi_cert_MTyM...` |

---

## Test Cards

| Brand | Card Number | CVV | Expiry |
|-------|-------------|-----|--------|
| Visa | 4012002000060016 | 123 | Any future |
| Mastercard | 5473500000000014 | 123 | Any future |
| Discover | 6011000990156527 | 123 | Any future |

---

## Troubleshooting

**`composer: command not found`**
Install Composer: https://getcomposer.org/download/

**Reports return empty results**
Process a payment in Tab 1 first to generate transaction data, then search in Tab 3.

**`401 Unauthorized`**
Confirm `SECRET_API_KEY` is `skapi_cert_...` format for sandbox.

**Port 8003 in use**
Change the port: `php -S localhost:8004`
