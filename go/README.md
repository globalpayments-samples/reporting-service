# Global Payments Go Integration

A comprehensive Go implementation for Global Payments payment processing and transaction reporting using the Global Payments Go SDK.

## Requirements

- Go 1.21 or later
- Global Payments account and API credentials

## Quick Start

### 1. Configure Credentials

Copy `.env.sample` to `.env` and add your credentials:

```env
PUBLIC_API_KEY=pkapi_cert_your_key_here
SECRET_API_KEY=skapi_cert_your_key_here
```

### 2. Install Dependencies

```bash
go mod download
```

### 3. Start the Server

```bash
go run main.go
```

The application will start on `http://localhost:8000`

### 4. Verify Setup

Open your browser to:
- **Web Interface**: http://localhost:8000/
- **Get Public Key**: http://localhost:8000/config

## Features

### ✅ Payment Processing
- Process card payments with hosted fields tokenization
- Support for billing address verification (AVS)
- Real-time payment processing with Global Payments Portico API

### ✅ Transaction Reporting
- Search and filter transactions
- Export data (JSON, CSV, XML)
- Generate settlement, dispute, and deposit reports
- Transaction analytics and summaries

## API Endpoints

### Payment Processing

**Get Configuration**
```bash
curl http://localhost:8888/config
```

**Process Payment**
```bash
curl -X POST http://localhost:8888/process-payment \
  -H "Content-Type: application/json" \
  -d '{"payment_token":"YOUR_TOKEN","billing_zip":"12345","amount":"25.00"}'
```

### Transaction Reporting

See [REPORTING_README.md](REPORTING_README.md) for complete reporting API documentation.

**Quick Example - Search Transactions**
```bash
curl "http://localhost:8888/reports/search?start_date=2025-09-01&page_size=10"
```

## Project Structure

```
go/
├── main.go                       # Main application
├── reporting_service.go          # Reporting service logic
├── reports.go                    # Reporting API endpoints
├── main_reporting.go             # Reporting server setup
├── static/
│   └── index.html               # Payment form UI
├── go.mod                        # Go dependencies
├── .env                          # Your credentials (not in git)
├── .env.sample                   # Credential template
├── README.md                     # This file
└── REPORTING_README.md           # Reporting API documentation
```

## Usage

### Processing a Payment

1. Open http://localhost:8888/ in your browser
2. Use test card: `4012002000060016`
3. Enter any future expiration date
4. Enter CVV: `123`
5. Enter billing ZIP code
6. Click "Pay $10.00"

### Searching Transactions

After processing payments, search for them:

```bash
curl "http://localhost:8888/reports/search?page_size=20"
```

### Exporting Data

Export transactions to CSV:

```bash
curl "http://localhost:8888/reports/export?format=csv&start_date=2025-09-01" -o transactions.csv
```

## Testing

### Test Card Numbers

| Card Type | Number | CVV | Expiry |
|-----------|--------|-----|--------|
| Visa | 4012002000060016 | 123 | Any future date |
| Mastercard | 5473500000000014 | 123 | Any future date |
| Amex | 372700699251018 | 1234 | Any future date |

### Testing Workflow

1. **Process a payment** using the web interface
2. **Verify transaction** appears in reporting:
   ```bash
   curl "http://localhost:8888/reports/search"
   ```
3. **Get transaction details**:
   ```bash
   curl "http://localhost:8888/reports/detail?transaction_id=TRN_xxx"
   ```

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `PUBLIC_API_KEY` | Yes | Portico public key for payment processing |
| `SECRET_API_KEY` | Yes | Portico secret key for payment processing |

## Troubleshooting

**Port already in use?**
```bash
# Modify the port in main.go or main_reporting.go
```

**Missing credentials error?**
- Ensure `.env` file exists in the `go/` directory
- Verify all required credentials are set
- Check for trailing spaces in credential values

**Payment processing fails?**
- Verify `PUBLIC_API_KEY` and `SECRET_API_KEY` are correct
- Ensure using test credentials for test environment

**Reporting not working?**
- Verify `GP_API_APP_ID` and `GP_API_APP_KEY` are correct
- These are different from payment processing credentials

**Module errors?**
- Run `go mod tidy` to clean up dependencies
- Ensure Go 1.21+ is installed

## Documentation

- **Payment Processing**: See API Endpoints section above
- **Transaction Reporting**: See [REPORTING_README.md](REPORTING_README.md)
- **Global Payments Docs**: https://developer.globalpayments.com

## Support

For issues or questions:
- Global Payments Developer Portal: https://developer.globalpayments.com
- SDK Documentation: https://github.com/globalpayments/go-sdk
