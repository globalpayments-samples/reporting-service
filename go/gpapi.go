package main

// A small GP API client for the reporting calls.
//
// The Go SDK (github.com/globalpayments/go-sdk, v1.1.5) only ships the
// Portico gateway: it has no GpApiConfig and no GP API reporting builders.
// The reports in this sample come from GP API, so the few endpoints they need
// are called directly here, the same way the other SDKs call them: an access
// token from /accesstoken, then GET requests with the X-GP-Version header.

import (
	"bytes"
	"context"
	"crypto/sha512"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

const gpAPIVersion = "2021-03-22"

// gpAPIBaseURL is the GP API sandbox. Use https://apis.globalpay.com/ucp for live.
var gpAPIBaseURL = "https://apis.sandbox.globalpay.com/ucp"

// gpAPIError is an error answered by GP API, or a failure to reach it.
type gpAPIError struct {
	Status  int
	Code    string
	Message string
}

func (e *gpAPIError) Error() string {
	if e.Status == 0 {
		return e.Message
	}
	return fmt.Sprintf("Status Code: %s - %s", e.Code, e.Message)
}

type gpAPIClient struct {
	appID   string
	appKey  string
	baseURL string
	http    *http.Client

	mu              sync.Mutex
	token           string
	tokenExpires    time.Time
	dataAccountID   string
	dataAccountName string
}

func newGPAPIClient(appID, appKey string) *gpAPIClient {
	return &gpAPIClient{
		appID:   appID,
		appKey:  appKey,
		baseURL: strings.TrimRight(gpAPIBaseURL, "/"),
		http:    &http.Client{Timeout: 60 * time.Second},
	}
}

// accessToken returns a cached token, signing in again when it is close to expiry.
func (c *gpAPIClient) accessToken(ctx context.Context, renew bool) (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if !renew && c.token != "" && time.Now().Before(c.tokenExpires) {
		return c.token, nil
	}

	nonce := time.Now().UTC().Format("2006-01-02T15:04:05.000Z")
	sum := sha512.Sum512([]byte(nonce + c.appKey))
	body, err := json.Marshal(map[string]string{
		"app_id":     c.appID,
		"nonce":      nonce,
		"secret":     hex.EncodeToString(sum[:]),
		"grant_type": "client_credentials",
	})
	if err != nil {
		return "", err
	}

	var resp struct {
		Token           string `json:"token"`
		SecondsToExpire int    `json:"seconds_to_expire"`
		Scope           struct {
			Accounts []struct {
				ID   string `json:"id"`
				Name string `json:"name"`
			} `json:"accounts"`
		} `json:"scope"`
	}
	if err := c.do(ctx, http.MethodPost, "/accesstoken", nil, body, "", &resp); err != nil {
		return "", err
	}
	if resp.Token == "" {
		return "", &gpAPIError{Message: "GP API did not return an access token"}
	}

	lifetime := time.Duration(resp.SecondsToExpire) * time.Second
	if lifetime <= 0 {
		lifetime = 10 * time.Minute
	}
	c.token = resp.Token
	c.tokenExpires = time.Now().Add(lifetime - lifetime/10)

	// Settlement and deposit reports are read from the data account (DAA_)
	c.dataAccountID, c.dataAccountName = "", ""
	for _, account := range resp.Scope.Accounts {
		if strings.HasPrefix(account.ID, "DAA_") {
			c.dataAccountID, c.dataAccountName = account.ID, account.Name
			break
		}
	}

	return c.token, nil
}

// dataAccount returns the data account picked at sign in.
func (c *gpAPIClient) dataAccount(ctx context.Context) (id, name string, err error) {
	if _, err = c.accessToken(ctx, false); err != nil {
		return "", "", err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.dataAccountID, c.dataAccountName, nil
}

// get calls a GP API endpoint and decodes the JSON answer into out.
func (c *gpAPIClient) get(ctx context.Context, path string, query url.Values, out interface{}) error {
	token, err := c.accessToken(ctx, false)
	if err != nil {
		return err
	}

	err = c.do(ctx, http.MethodGet, path, query, nil, token, out)

	// An expired token is answered with 401, sign in again once
	var apiErr *gpAPIError
	if errors.As(err, &apiErr) && apiErr.Status == http.StatusUnauthorized {
		if token, err = c.accessToken(ctx, true); err != nil {
			return err
		}
		err = c.do(ctx, http.MethodGet, path, query, nil, token, out)
	}
	return err
}

func (c *gpAPIClient) do(ctx context.Context, method, path string, query url.Values, body []byte, token string, out interface{}) error {
	target := c.baseURL + path
	if len(query) > 0 {
		target += "?" + query.Encode()
	}

	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, target, reader)
	if err != nil {
		return err
	}
	req.Header.Set("X-GP-Version", gpAPIVersion)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return &gpAPIError{Message: "Error occurred while communicating with gateway."}
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(io.LimitReader(resp.Body, 10<<20))
	if err != nil {
		return &gpAPIError{Message: "Error occurred while communicating with gateway."}
	}

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent {
		var failure struct {
			ErrorCode   string `json:"error_code"`
			Description string `json:"detailed_error_description"`
		}
		_ = json.Unmarshal(raw, &failure)
		apiErr := &gpAPIError{Status: resp.StatusCode, Code: failure.ErrorCode, Message: failure.Description}
		if apiErr.Code == "" {
			apiErr.Code = strconv.Itoa(resp.StatusCode)
		}
		if apiErr.Message == "" {
			apiErr.Message = http.StatusText(resp.StatusCode)
		}
		return apiErr
	}

	if out == nil || len(raw) == 0 {
		return nil
	}
	if err := json.Unmarshal(raw, out); err != nil {
		return &gpAPIError{Message: "Invalid API Response!"}
	}
	return nil
}

// minorAmount reads a GP API amount (minor units, sent as a string or a
// number) and holds it in major units, e.g. "1999" becomes 19.99.
type minorAmount float64

func (a *minorAmount) UnmarshalJSON(data []byte) error {
	text := strings.Trim(string(data), `"`)
	if text == "" || text == "null" {
		*a = 0
		return nil
	}
	value, err := strconv.ParseFloat(text, 64)
	if err != nil {
		*a = 0
		return nil
	}
	*a = minorAmount(value / 100)
	return nil
}

// GP API resources, only the fields the reports use.

type gpCard struct {
	Brand                   string `json:"brand"`
	MaskedNumberFirst6Last4 string `json:"masked_number_first6last4"`
	Number                  string `json:"number"`
	AuthCode                string `json:"authcode"`
	ARN                     string `json:"arn"`
}

type gpPaymentMethod struct {
	Result        string  `json:"result"`
	Message       string  `json:"message"`
	EntryMode     string  `json:"entry_mode"`
	Card          *gpCard `json:"card"`
	DigitalWallet *struct {
		MaskedTokenFirst6Last4 string `json:"masked_token_first6last4"`
	} `json:"digital_wallet"`
	BankTransfer *struct {
		MaskedAccountNumberLast4 string `json:"masked_account_number_last4"`
	} `json:"bank_transfer"`
	APM *struct {
		Provider string `json:"provider"`
	} `json:"apm"`
}

type gpTransaction struct {
	ID                 string           `json:"id"`
	TimeCreated        string           `json:"time_created"`
	Type               string           `json:"type"`
	Status             string           `json:"status"`
	Amount             minorAmount      `json:"amount"`
	Currency           string           `json:"currency"`
	Reference          string           `json:"reference"`
	DepositID          string           `json:"deposit_id"`
	DepositStatus      string           `json:"deposit_status"`
	DepositTimeCreated string           `json:"deposit_time_created"`
	PaymentMethod      *gpPaymentMethod `json:"payment_method"`
}

type gpSystem struct {
	MID       string `json:"mid"`
	Hierarchy string `json:"hierarchy"`
	Name      string `json:"name"`
}

type gpDispute struct {
	ID                string      `json:"id"`
	TimeCreated       string      `json:"time_created"`
	StageTimeCreated  string      `json:"stage_time_created"`
	Status            string      `json:"status"`
	Stage             string      `json:"stage"`
	Amount            minorAmount `json:"amount"`
	Currency          string      `json:"currency"`
	ReasonCode        string      `json:"reason_code"`
	ReasonDescription string      `json:"reason_description"`
	Description       string      `json:"description"`
	PaymentMethod     *struct {
		Card *gpCard `json:"card"`
	} `json:"payment_method"`
	Transaction *struct {
		ID            string           `json:"id"`
		Amount        minorAmount      `json:"amount"`
		Currency      string           `json:"currency"`
		PaymentMethod *gpPaymentMethod `json:"payment_method"`
	} `json:"transaction"`
	Documents []struct {
		ID   string `json:"id"`
		Type string `json:"type"`
	} `json:"documents"`
}

type gpCountAmount struct {
	Count  int         `json:"count"`
	Amount minorAmount `json:"amount"`
}

type gpDeposit struct {
	ID           string      `json:"id"`
	TimeCreated  string      `json:"time_created"`
	Status       string      `json:"status"`
	Amount       minorAmount `json:"amount"`
	Currency     string      `json:"currency"`
	System       *gpSystem   `json:"system"`
	BankTransfer *struct {
		MaskedAccountNumberLast4 string `json:"masked_account_number_last4"`
		Bank                     *struct {
			Name string `json:"name"`
		} `json:"bank"`
	} `json:"bank_transfer"`
	Sales    gpCountAmount `json:"sales"`
	Refunds  gpCountAmount `json:"refunds"`
	Disputes struct {
		Chargebacks gpCountAmount `json:"chargebacks"`
		Reversals   gpCountAmount `json:"reversals"`
	} `json:"disputes"`
}

// gpPage is the envelope of the paged list endpoints.
type gpPage struct {
	TotalRecordCount int `json:"total_record_count"`
	TotalCount       int `json:"total_count"`
}

func (p gpPage) total() int {
	if p.TotalRecordCount > 0 {
		return p.TotalRecordCount
	}
	return p.TotalCount
}
