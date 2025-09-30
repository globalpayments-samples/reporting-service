// Package main implements an example server that combines payment processing
// and reporting functionality using the Global Payments SDK.
//
// This example shows how to integrate the reporting service alongside
// the existing payment processing endpoints.
//
// Usage:
//   To run the reporting service only:
//     go run reporting_service.go reports.go main_reporting.go
//
//   The server will start on port 8080 by default (configurable via PORT env var)
package main

import (
	"log"
	"net/http"
	"os"

	"github.com/gorilla/mux"
	"github.com/joho/godotenv"
)

func main() {
	// Load environment variables
	if err := godotenv.Load(); err != nil {
		log.Println("Warning: .env file not found, using system environment variables")
	}

	// Create router
	router := mux.NewRouter()

	// Initialize reporting API routes
	if err := InitializeReportingAPI(router); err != nil {
		log.Fatalf("Failed to initialize reporting API: %v", err)
	}

	// Optional: Add a health check endpoint
	router.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"healthy","service":"reporting-api"}`))
	}).Methods("GET")

	// Get port from environment or use default
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	// Start server
	log.Printf("=================================================")
	log.Printf("Global Payments Reporting API Server")
	log.Printf("=================================================")
	log.Printf("Server starting on http://localhost:%s", port)
	log.Printf("API Documentation: http://localhost:%s/reports", port)
	log.Printf("Health Check: http://localhost:%s/health", port)
	log.Printf("=================================================")
	log.Printf("")
	log.Printf("Available Endpoints:")
	log.Printf("  GET  /reports              - API documentation")
	log.Printf("  GET  /reports/config       - Configuration status")
	log.Printf("  GET  /reports/search       - Search transactions")
	log.Printf("  GET  /reports/detail       - Transaction details")
	log.Printf("  GET  /reports/settlement   - Settlement report")
	log.Printf("  GET  /reports/export       - Export transactions")
	log.Printf("  GET  /reports/summary      - Summary statistics")
	log.Printf("  GET  /reports/disputes     - Dispute report")
	log.Printf("  GET  /reports/deposits     - Deposit report")
	log.Printf("  GET  /reports/batches      - Batch report")
	log.Printf("  GET  /reports/declines     - Declined transactions")
	log.Printf("  GET  /reports/date-range   - Date range report")
	log.Printf("=================================================")

	if err := http.ListenAndServe("0.0.0.0:"+port, router); err != nil {
		log.Fatalf("Server failed to start: %v", err)
	}
}