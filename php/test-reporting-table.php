<?php

declare(strict_types=1);

/**
 * Test file to verify the reporting table functionality
 */

require_once 'reporting-service.php';

echo "<!DOCTYPE html>
<html>
<head>
    <title>Test Reporting Table</title>
    <style>
        body { font-family: Arial, sans-serif; padding: 20px; background: #f5f5f5; }
        .container { max-width: 1200px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; }
        h1 { color: #333; }
        .success { color: green; font-weight: bold; }
        .error { color: red; font-weight: bold; }
    </style>
</head>
<body>
    <div class='container'>
        <h1>Reporting Table Test</h1>";

try {
    echo "<p class='success'>✓ reporting-service.php loaded successfully</p>";

    $reportingService = new GlobalPaymentsReportingService();
    echo "<p class='success'>✓ ReportingService initialized successfully</p>";

    $filters = [
        'page' => 1,
        'page_size' => 5
    ];

    $result = $reportingService->searchTransactions($filters);
    echo "<p class='success'>✓ Transaction search completed successfully</p>";

    if ($result['success'] && !empty($result['data']['transactions'])) {
        echo "<p class='success'>✓ Found " . count($result['data']['transactions']) . " transactions</p>";
        echo "<h2>Sample Transaction Data Structure:</h2>";
        echo "<pre style='background: #f8f9fa; padding: 15px; border-radius: 4px; overflow: auto;'>";
        print_r($result['data']['transactions'][0]);
        echo "</pre>";

        echo "<h2>Transaction Properties (Two-Column Table):</h2>";
        $transaction = $result['data']['transactions'][0];
        $ignoredProperties = ['rawResponse', 'balanceAmount', 'token'];

        echo "<table style='width: 100%; border-collapse: collapse; background: white;'>";
        echo "<tbody>";

        $data = (array)$transaction;
        ksort($data);

        foreach ($data as $property => $value) {
            if (in_array($property, $ignoredProperties)) {
                continue;
            }

            // Handle complex objects
            if (is_object($value) || is_array($value)) {
                if ($property === 'timestamp' && is_object($value) && isset($value->date)) {
                    $value = $value->date;
                } else {
                    continue;
                }
            }

            // Format property name
            $formattedProperty = strtoupper(substr($property, 0, 1)) . substr($property, 1);
            $formattedProperty = str_replace('_', ' ', $formattedProperty);

            // Format value
            $formattedValue = ($value === '' || $value === null) ? '-' : htmlspecialchars((string)$value);

            echo "<tr style='border-bottom: 1px solid #e9ecef;'>";
            echo "<td style='padding: 0.75rem; font-weight: 600; width: 40%;'><strong>" . htmlspecialchars($formattedProperty) . "</strong></td>";
            echo "<td style='padding: 0.75rem;'>" . $formattedValue . "</td>";
            echo "</tr>";
        }

        echo "</tbody>";
        echo "</table>";

    } else {
        echo "<p class='error'>✗ No transactions found in the response</p>";
    }

} catch (Exception $e) {
    echo "<p class='error'>✗ Error: " . htmlspecialchars($e->getMessage()) . "</p>";
    echo "<pre style='background: #ffe6e6; padding: 15px; border-radius: 4px;'>";
    echo htmlspecialchars($e->getTraceAsString());
    echo "</pre>";
}

echo "
        <h2>Next Steps:</h2>
        <ul>
            <li>Open <a href='index.html'>index.html</a> in your browser</li>
            <li>Click on the 'Transaction Report' tab</li>
            <li>Verify that the transaction data displays in a two-column table format</li>
        </ul>
    </div>
</body>
</html>";
