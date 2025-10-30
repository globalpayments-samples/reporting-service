# Global Payments Reporting Service

A complete transaction reporting service for Global Payments, providing interactive search, filtering, and export capabilities across multiple programming languages. Each implementation includes a full-featured web interface with documentation and real-time transaction data visualization.

## Available Implementations

- [.NET Core](./dotnet/) - ([Preview](https://githubbox.com/globalpayments-samples/reporting-service/tree/main/dotnet)) - ASP.NET Core web application
- [Go](./go/) - ([Preview](https://githubbox.com/globalpayments-samples/reporting-service/tree/main/go)) - Go HTTP server application
- [Java](./java/) - ([Preview](https://githubbox.com/globalpayments-samples/reporting-service/tree/main/java)) - Jakarta EE servlet-based web application
- [Node.js](./nodejs/) - ([Preview](https://githubbox.com/globalpayments-samples/reporting-service/tree/main/nodejs)) - Express.js web application
- [PHP](./php/) - ([Preview](https://githubbox.com/globalpayments-samples/reporting-service/tree/main/php)) - PHP web application
- [Python](./python/) - ([Preview](https://githubbox.com/globalpayments-samples/reporting-service/tree/main/python)) - Flask web application

## Features

- **Interactive Transaction Reports** - Search and view transaction data in real-time
- **Advanced Filtering** - Filter by date range, status, amount, and more
- **Data Export** - Export transactions to CSV, JSON, or XML formats
- **Transaction Details** - Click any transaction to view complete details
- **Comprehensive Documentation** - Built-in API documentation for each language
- **Payment Processing** - Process credit card payments with hosted fields
- **Multiple Languages** - Consistent UI/UX across all implementations

## Reporting Capabilities

Each implementation includes comprehensive reporting features:

1. **Transaction Search**
   - Search by date range, status, or transaction ID
   - Paginated results with customizable page size
   - Real-time data loading with loading indicators

2. **Reporting API Endpoints**
   - `GET /reports?action=search` - Search transactions with filters
   - `GET /reports?action=detail` - Get detailed transaction information
   - `GET /reports?action=export` - Export data in CSV/JSON/XML formats
   - `GET /reports?action=summary` - Get transaction summary statistics
   - `GET /reports?action=declines` - Analyze declined transactions
   - Additional endpoints for settlements, disputes, deposits, and batches

3. **Interactive UI Features**
   - Three-tab interface: Payment Form, Documentation, Transaction Report
   - Collapsible filter panel with multiple filter options
   - Clickable transaction IDs for detailed views
   - Export buttons for quick data downloads
   - Responsive design for mobile and desktop

## Quick Start

1. **Choose your language** - Navigate to any implementation directory (nodejs, python, php, java, dotnet, go)
2. **Set up credentials** - Copy `.env.sample` to `.env` and add your Global Payments API keys
3. **Install dependencies** - Run the installation command for your language (see individual READMEs)
4. **Start the server** - Execute `./run.sh` or use the language-specific run command
5. **Access the UI** - Open your browser to the specified port and explore the three tabs:
   - **Payment Form** - Process credit card transactions
   - **Reporting Documentation** - View API documentation
   - **Transaction Report** - Search, filter, and export transaction data

## Use Cases

This reporting service is ideal for:

- **Transaction Monitoring** - Real-time oversight of payment activity
- **Financial Reconciliation** - Export data for accounting and bookkeeping
- **Customer Support** - Quick lookup of transaction details by ID
- **Analytics & Reporting** - Generate summaries and analyze payment trends
- **Dispute Management** - Track and manage chargebacks
- **Settlement Tracking** - Monitor batch settlements and deposits

## Prerequisites

- Global Payments account with API credentials
- Development environment for your chosen language
- Package manager (npm, pip, composer, maven, dotnet, go mod)

## Docker Support

All implementations include Docker support for easy deployment:

```bash
# Run individual service
docker build -t reporting-service-php ./php
docker run -p 8000:8000 --env-file .env reporting-service-php

# Run all services with docker-compose
docker-compose up

# Services will be available at:
# - Node.js: http://localhost:8001
# - Python: http://localhost:8002
# - PHP: http://localhost:8003
# - Java: http://localhost:8004
# - Go: http://localhost:8005
# - .NET: http://localhost:8006
```

## Security Features

All implementations include:
- HTML escaping to prevent XSS attacks
- Secure environment variable management
- Non-root users in Docker containers
- HTTPS support for production deployments
- Input validation and sanitization

## Documentation

Each implementation includes:
- Built-in API documentation page accessible from the UI
- Language-specific README with setup instructions
- Code examples and usage guidelines
- API endpoint reference with request/response formats
