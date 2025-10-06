# Global Payments PHP Integration

Payment processing and transaction reporting using Global Payments PHP SDK.

## Setup

**Requirements:** PHP 7.4+, Composer, Global Payments account

1. **Add credentials** - Copy `.env.sample` to `.env`:
   ```properties
   PUBLIC_API_KEY=pkapi_cert_your_public_key
   SECRET_API_KEY=skapi_cert_your_secret_key
   GP_API_APP_ID=your_app_id_here
   GP_API_APP_KEY=your_app_key_here
   ```

2. **Install dependencies:**
   ```bash
   composer install
   ```

3. **Start server:**
   ```bash
   php -S localhost:8000
   ```

4. **Open browser:** http://localhost:8000/index.html

## What's Included

**Payment Processing**
- Card payment form with tokenization
- Address verification
- Test cards: 4263970000005262 (Visa), 5425230000004415 (MC)

**Transaction Reporting**
- Interactive table with search, filters, pagination
- Click transaction IDs to view details
- Export to CSV or JSON
- Date range and status filters

## Using the Reporting Table

Open the web interface and click the **Transaction Report** tab:

- **View transactions** - Lists recent transactions automatically
- **Filter data** - Click ⚙ Filters to search by date or status
- **View details** - Click any blue transaction ID
- **Navigate pages** - Use Previous/Next buttons
- **Export data** - Click ↓ JSON or ↓ CSV buttons
- **Refresh** - Click ↻ Refresh to reload

## API Examples

**Search transactions:**
```bash
curl "http://localhost:8000/reports.php?action=search&start_date=2025-09-01&page_size=10"
```

**Get details:**
```bash
curl "http://localhost:8000/reports.php?action=detail&transaction_id=TRN_xxx"
```

**Export CSV:**
```bash
curl "http://localhost:8000/reports.php?action=export&format=csv" -o transactions.csv
```

See [REPORTING_README.md](REPORTING_README.md) for all endpoints.

## Problems?

**Port in use?** Use `php -S localhost:8080`

**Missing credentials?** Check `.env` file exists and has all 4 keys

**Payments fail?** Verify PUBLIC_API_KEY and SECRET_API_KEY

**Reporting empty?** Verify GP_API_APP_ID and GP_API_APP_KEY (different from payment keys)
