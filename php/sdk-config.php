<?php

declare(strict_types=1);

/**
 * Enhanced SDK Configuration for Global Payments Reporting
 *
 * This configuration file sets up the Global Payments SDK with GP-API
 * support for both payment processing and reporting functionality.
 *
 * PHP version 7.4 or higher
 *
 * @category  Configuration
 * @package   GlobalPayments_Reporting
 * @author    Global Payments
 * @license   MIT License
 * @link      https://github.com/globalpayments
 */

require_once 'vendor/autoload.php';

use Dotenv\Dotenv;
use GlobalPayments\Api\ServiceConfigs\Gateways\GpApiConfig;
use GlobalPayments\Api\ServicesContainer;
use GlobalPayments\Api\Entities\Enums\Environment;
use GlobalPayments\Api\Entities\Enums\Channel;

/**
 * Load .env when there is one. Docker and CI pass the variables in the
 * environment instead, so a missing file is not an error.
 *
 * @return void
 */
function loadEnvironment(): void
{
    static $loaded = false;
    if (!$loaded) {
        Dotenv::createImmutable(__DIR__)->safeLoad();
        $loaded = true;
    }
}

/**
 * Read an environment variable from .env or the process environment
 *
 * @param string $name Variable name
 * @return string The value, or an empty string when it is not set
 */
function envValue(string $name): string
{
    loadEnvironment();
    $value = $_ENV[$name] ?? getenv($name);
    return is_string($value) ? $value : '';
}

/**
 * Configure the SDK for GP-API with reporting capabilities
 *
 * Sets up the Global Payments SDK with GP-API configuration to enable
 * both payment processing and reporting functionality using credentials
 * from environment variables.
 *
 * @return void
 * @throws \GlobalPayments\Api\Entities\Exceptions\ConfigurationException
 */
function configureGpApiSdk(): void
{
    // Validate required environment variables
    $requiredVars = ['GP_API_APP_ID', 'GP_API_APP_KEY'];
    foreach ($requiredVars as $var) {
        if (envValue($var) === '') {
            throw new \InvalidArgumentException("Missing required environment variable: {$var}");
        }
    }

    $config = new GpApiConfig();

    // Set GP-API credentials
    $config->appId = envValue('GP_API_APP_ID');
    $config->appKey = envValue('GP_API_APP_KEY');

    // Configure environment (sandbox for development)
    $config->environment = Environment::TEST; // Change to PRODUCTION for live

    // Set channel for ecommerce transactions
    $config->channel = Channel::CardNotPresent;

    // Enable request/response logging for debugging (disable in production)
    // $config->requestLogger = new \GlobalPayments\Api\Utils\Logging\SampleRequestLogger();

    // Configure the service container
    ServicesContainer::configureService($config, 'default');
}

/**
 * Get current SDK configuration status
 *
 * @return array Configuration status information
 */
function getSdkConfigStatus(): array
{
    try {
        // Check if the environment variables are set
        $hasAppId = envValue('GP_API_APP_ID') !== '';
        $hasAppKey = envValue('GP_API_APP_KEY') !== '';
        $isConfigured = $hasAppId && $hasAppKey;

        return [
            'configured' => $isConfigured,
            'has_app_id' => $hasAppId,
            'has_app_key' => $hasAppKey,
            'environment' => $isConfigured ? 'TEST' : 'Not configured',
            'timestamp' => date('Y-m-d H:i:s')
        ];
    } catch (\Exception $e) {
        return [
            'configured' => false,
            'error' => $e->getMessage(),
            'timestamp' => date('Y-m-d H:i:s')
        ];
    }
}

/**
 * Validate environment configuration
 *
 * @return array Validation results
 */
function validateEnvironmentConfig(): array
{
    $results = [
        'valid' => true,
        'errors' => [],
        'warnings' => []
    ];

    // Check required variables
    $required = ['GP_API_APP_ID', 'GP_API_APP_KEY'];
    foreach ($required as $var) {
        if (envValue($var) === '') {
            $results['valid'] = false;
            $results['errors'][] = "Missing required environment variable: {$var}";
        }
    }

    // Check legacy variables and warn if present
    $legacy = ['PUBLIC_API_KEY', 'SECRET_API_KEY'];
    foreach ($legacy as $var) {
        if (envValue($var) !== '') {
            $results['warnings'][] = "Legacy variable {$var} found. GP-API uses GP_API_APP_ID and GP_API_APP_KEY.";
        }
    }

    return $results;
}