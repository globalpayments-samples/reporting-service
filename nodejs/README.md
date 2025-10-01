# Global Payments Node.js Integration

A comprehensive Node.js implementation for Global Payments payment processing and transaction reporting using Express.js and the Global Payments Node.js SDK.

## Requirements

- Node.js 14.x or later
- npm (Node Package Manager)
- Global Payments account and API credentials

## Quick Start

### 1. Configure Credentials

Copy `.env.sample` to `.env` and add your credentials:

```properties
# Payment Processing (Portico API)
PUBLIC_API_KEY=pkapi_cert_your_public_key
SECRET_API_KEY=skapi_cert_your_secret_key

# Transaction Reporting (GP-API)
GP_API_APP_ID=your_app_id_here
GP_API_APP_KEY=your_app_key_here
GP_API_ENVIRONMENT=TEST  # or PRODUCTION
```

### 2. Install Dependencies

```bash
npm install
```

### 3. Start the Server

```bash
./run.sh
```

Or manually:
```bash
npm start
```

The application will start on `http://localhost:8000`

### 4. Verify Setup

Open your browser to:
- **Web Interface**: http://localhost:8000/index.html
- **Get Public Key**: http://localhost:8000/config
- **Reporting Config**: http://localhost:8000/api/reports?action=config

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
curl http://localhost:8000/config
```

**Process Payment**
```bash
curl -X POST http://localhost:8000/process-payment \
  -d "payment_token=YOUR_TOKEN" \
  -d "billing_zip=12345" \
  -d "amount=25.00"
```

### Transaction Reporting

See [REPORTING_README.md](REPORTING_README.md) for complete reporting API documentation.

**Quick Example - Search Transactions**
```bash
curl "http://localhost:8000/api/reports?action=search&start_date=2025-09-01&page_size=10"
```

## Project Structure

```
nodejs/
├── server.js                     # Main Express application
├── reports.js                    # Reporting API endpoints
├── reporting-service.js          # Reporting service logic
├── index.html                    # Payment form UI
├── package.json                  # Node.js dependencies
├── .env                          # Your credentials (not in git)
├── .env.sample                   # Credential template
├── README.md                     # This file
└── REPORTING_README.md           # Reporting API documentation
```

## Usage

### Processing a Payment

1. Open http://localhost:8000/index.html in your browser
2. Use test card: `4263970000005262`
3. Enter any future expiration date
4. Enter CVV: `123`
5. Enter billing ZIP code
6. Click "Pay $10.00"

### Searching Transactions

After processing payments, search for them:

```bash
curl "http://localhost:8000/api/reports?action=search&page_size=20"
```

### Exporting Data

Export transactions to CSV:

```bash
curl "http://localhost:8000/api/reports?action=export&format=csv&start_date=2025-09-01" -o transactions.csv
```

## Testing

### Test Card Numbers

| Card Type | Number | CVV | Expiry |
|-----------|--------|-----|--------|
| Visa | 4263970000005262 | 123 | Any future date |
| Mastercard | 5425230000004415 | 123 | Any future date |
| Amex | 374101000000608 | 1234 | Any future date |

### Testing Workflow

1. **Process a payment** using the web interface
2. **Verify transaction** appears in reporting:
   ```bash
   curl "http://localhost:8000/api/reports?action=search"
   ```
3. **Get transaction details**:
   ```bash
   curl "http://localhost:8000/api/reports?action=detail&transaction_id=TRN_xxx"
   ```

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `PUBLIC_API_KEY` | Yes | Portico public API key for payment processing |
| `SECRET_API_KEY` | Yes | Portico secret API key for payment processing |
| `GP_API_APP_ID` | Yes | GP-API App ID for reporting |
| `GP_API_APP_KEY` | Yes | GP-API App Key for reporting |
| `GP_API_ENVIRONMENT` | No | TEST or PRODUCTION (default: TEST) |
| `PORT` | No | Server port (default: 8000) |

## Troubleshooting

**Port already in use?**
```bash
# Use a different port:
PORT=8080 npm start
```

**Missing credentials error?**
- Ensure `.env` file exists in the `nodejs/` directory
- Verify all required credentials are set
- Check for trailing spaces in credential values

**Payment processing fails?**
- Verify `PUBLIC_API_KEY` and `SECRET_API_KEY` are correct
- Ensure using test credentials for test environment

**Reporting not working?**
- Verify `GP_API_APP_ID` and `GP_API_APP_KEY` are correct
- These are different from payment processing credentials

**npm install fails?**
- Ensure Node.js 14+ is installed
- Try removing `node_modules` and `package-lock.json`, then run `npm install` again

## Documentation

- **Payment Processing**: See API Endpoints section above
- **Transaction Reporting**: See [REPORTING_README.md](REPORTING_README.md)
- **Global Payments Docs**: https://developer.globalpay.com

## Support

For issues or questions:
- Global Payments Developer Portal: https://developer.globalpay.com
- SDK Documentation: https://github.com/globalpayments/nodejs-sdk
