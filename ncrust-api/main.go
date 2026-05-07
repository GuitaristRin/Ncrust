package main

import (
	"log"
	"net/http"
	"os"
)

type server struct {
	ncmBase string
	token   string
	cache   *memCache
}

func main() {
	s := &server{
		ncmBase: getenv("NCM_BASE_URL", "https://music.163.com"),
		token:   getenv("API_TOKEN", ""),
		cache:   newCache(),
	}

	port := getenv("PORT", "8080")
	log.Printf("ncrust-api listening on :%s  ncm=%s  auth=%v", port, s.ncmBase, s.token != "")
	log.Fatal(http.ListenAndServe(":"+port, s.routes()))
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
