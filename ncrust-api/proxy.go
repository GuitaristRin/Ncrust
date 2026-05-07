package main

import (
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const ncmUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

var httpClient = &http.Client{Timeout: 30 * time.Second}

// eapiPost encrypts payload and POSTs to an eapi endpoint on music.163.com.
func (s *server) eapiPost(eapiPath string, payload map[string]string, cookie string) ([]byte, int, error) {
	params := eapiEncrypt(eapiPath, payload)
	body := "params=" + url.QueryEscape(params)

	req, err := http.NewRequest("POST", s.ncmBase+eapiPath, strings.NewReader(body))
	if err != nil {
		return nil, 0, err
	}
	s.setNCMHeaders(req, cookie)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	return doRequest(req)
}

// formPost POSTs plain form data to a music.163.com /api/ endpoint.
func (s *server) formPost(apiPath string, fields map[string]string, cookie string) ([]byte, int, error) {
	vals := url.Values{}
	for k, v := range fields {
		vals.Set(k, v)
	}
	req, err := http.NewRequest("POST", s.ncmBase+apiPath, strings.NewReader(vals.Encode()))
	if err != nil {
		return nil, 0, err
	}
	s.setNCMHeaders(req, cookie)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	return doRequest(req)
}

// apiGet sends a plain GET to music.163.com.
func (s *server) apiGet(path string, cookie string) ([]byte, int, error) {
	req, err := http.NewRequest("GET", s.ncmBase+path, nil)
	if err != nil {
		return nil, 0, err
	}
	s.setNCMHeaders(req, cookie)
	return doRequest(req)
}

func (s *server) setNCMHeaders(req *http.Request, cookie string) {
	req.Header.Set("User-Agent", ncmUA)
	req.Header.Set("Referer", "https://music.163.com/")
	req.Header.Set("Origin", "https://music.163.com")
	if cookie != "" {
		req.Header.Set("Cookie", cookie)
	}
}

func doRequest(req *http.Request) ([]byte, int, error) {
	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	return data, resp.StatusCode, err
}
