package main

import (
	"crypto/aes"
	"crypto/md5"
	"encoding/json"
	"fmt"
	"strings"
)

const (
	eapiAESKey = "e82ckenh8dichen8"
	eapiSep    = "-36cd479b6b5-"
)

// eapiEncrypt encrypts a payload for an eapi endpoint.
// eapiPath must be the full /eapi/... path (e.g. "/eapi/user/playlist").
// Returns the hex-encoded encrypted params string to be sent as form field "params".
func eapiEncrypt(eapiPath string, payload map[string]string) string {
	// Signing uses /api/... path (eapi → api)
	apiPath := strings.Replace(eapiPath, "/eapi/", "/api/", 1)

	payloadJSON, _ := json.Marshal(payload)
	pStr := string(payloadJSON)

	sign := "nobody" + apiPath + "use" + pStr + "md5forencrypt"
	digest := fmt.Sprintf("%x", md5.Sum([]byte(sign)))

	params := apiPath + eapiSep + pStr + eapiSep + digest
	return fmt.Sprintf("%x", aesECBEncrypt([]byte(params), []byte(eapiAESKey)))
}

func aesECBEncrypt(data, key []byte) []byte {
	block, _ := aes.NewCipher(key)
	bs := block.BlockSize()
	// PKCS5 padding
	pad := bs - len(data)%bs
	padded := make([]byte, len(data)+pad)
	copy(padded, data)
	for i := len(data); i < len(padded); i++ {
		padded[i] = byte(pad)
	}
	out := make([]byte, len(padded))
	for i := 0; i < len(padded); i += bs {
		block.Encrypt(out[i:i+bs], padded[i:i+bs])
	}
	return out
}
