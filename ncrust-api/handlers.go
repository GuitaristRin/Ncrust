package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
	"time"
)

func (s *server) routes() *http.ServeMux {
	mux := http.NewServeMux()
	wrap := func(h http.HandlerFunc) http.HandlerFunc {
		return s.withLog(s.withAuth(h))
	}
	mux.HandleFunc("/api/search", wrap(s.search))
	mux.HandleFunc("/api/song/url", wrap(s.songURL))
	mux.HandleFunc("/api/song/detail", wrap(s.songDetail))
	mux.HandleFunc("/api/lyric", wrap(s.lyric))
	mux.HandleFunc("/api/album", wrap(s.album))
	mux.HandleFunc("/api/artist/albums", wrap(s.artistAlbums))
	mux.HandleFunc("/api/artist", wrap(s.artist))
	mux.HandleFunc("/api/playlist/detail", wrap(s.playlistDetail))
	mux.HandleFunc("/api/user/playlist", wrap(s.userPlaylist))
	mux.HandleFunc("/api/user/account", wrap(s.userAccount))
	mux.HandleFunc("/api/user/detail", wrap(s.userDetail))
	mux.HandleFunc("/api/recommend/resource", wrap(s.recommendResource))
	mux.HandleFunc("/api/recommend/songs", wrap(s.recommendSongs))
	mux.HandleFunc("/api/personal_fm", wrap(s.personalFm))
	mux.HandleFunc("/api/fm_trash", wrap(s.fmTrash))
	mux.HandleFunc("/api/personalized", wrap(s.personalized))
	mux.HandleFunc("/api/top/song", wrap(s.topSong))
	return mux
}

// --- middleware ---

func (s *server) withAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s.token != "" {
			tok := r.Header.Get("X-Api-Token")
			if tok == "" {
				tok = strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
			}
			if tok != s.token {
				http.Error(w, `{"code":403,"msg":"forbidden"}`, http.StatusForbidden)
				return
			}
		}
		next(w, r)
	}
}

type statusWriter struct {
	http.ResponseWriter
	code int
}

func (sw *statusWriter) WriteHeader(code int) {
	sw.code = code
	sw.ResponseWriter.WriteHeader(code)
}

func (s *server) withLog(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		sw := &statusWriter{ResponseWriter: w, code: 200}
		t := time.Now()
		next(sw, r)
		log.Printf("%s %s %d %s", r.Method, r.RequestURI, sw.code, time.Since(t).Round(time.Millisecond))
	}
}

// --- response helper ---

func writeJSON(w http.ResponseWriter, data []byte, ncmStatus int) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	// 301 = not logged in on music.163.com → 401 to client
	if ncmStatus == 301 {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"code":401,"msg":"not logged in"}`))
		return
	}
	w.Write(data)
}

func writeErr(w http.ResponseWriter, err error) {
	http.Error(w, fmt.Sprintf(`{"code":500,"msg":%q}`, err.Error()), http.StatusInternalServerError)
}

// --- route handlers ---

func (s *server) search(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	keywords := q.Get("keywords")
	typ := qDefault(q.Get("type"), "1")
	limit := qDefault(q.Get("limit"), "30")
	offset := qDefault(q.Get("offset"), "0")

	data, st, err := s.formPost("/api/cloudsearch/pc", map[string]string{
		"s": keywords, "type": typ, "limit": limit, "offset": offset,
	}, r.Header.Get("Cookie"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, data, st)
}

func (s *server) songURL(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	id := q.Get("id")
	level := brToLevel(q.Get("br"))
	// id may be comma-separated; wrap as JSON array
	ids := idsToJSONArray(id)

	data, st, err := s.eapiPost("/eapi/song/enhance/player/url/v1", map[string]string{
		"ids": ids, "level": level, "encodeType": "flac",
	}, r.Header.Get("Cookie"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, data, st)
}

func (s *server) songDetail(w http.ResponseWriter, r *http.Request) {
	ids := r.URL.Query().Get("ids")
	cookie := r.Header.Get("Cookie")

	key := "song:" + ids
	if hit, ok := s.cache.get(key); ok {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Write(hit)
		return
	}

	// Build c=[{"id":"123"},{"id":"456"}]
	parts := strings.Split(ids, ",")
	arr := make([]map[string]string, len(parts))
	for i, id := range parts {
		arr[i] = map[string]string{"id": strings.TrimSpace(id)}
	}
	cJSON, _ := json.Marshal(arr)

	data, st, err := s.formPost("/api/v3/song/detail", map[string]string{"c": string(cJSON)}, cookie)
	if err != nil {
		writeErr(w, err)
		return
	}
	if st == 200 {
		s.cache.set(key, data, time.Hour)
	}
	writeJSON(w, data, st)
}

func (s *server) lyric(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	cookie := r.Header.Get("Cookie")

	key := "lyric:" + id
	if hit, ok := s.cache.get(key); ok {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Write(hit)
		return
	}

	data, st, err := s.formPost("/api/song/lyric", map[string]string{
		"id": id, "cp": "false", "tv": "0", "lv": "0",
		"rv": "0", "kv": "0", "yv": "0", "ytv": "0", "yrv": "0",
	}, cookie)
	if err != nil {
		writeErr(w, err)
		return
	}
	if st == 200 {
		s.cache.set(key, data, 24*time.Hour)
	}
	writeJSON(w, data, st)
}

func (s *server) album(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	cookie := r.Header.Get("Cookie")

	key := "album:" + id
	if hit, ok := s.cache.get(key); ok {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Write(hit)
		return
	}

	data, st, err := s.apiGet("/api/v1/album/"+id, cookie)
	if err != nil {
		writeErr(w, err)
		return
	}
	if st == 200 {
		s.cache.set(key, data, 30*time.Minute)
	}
	writeJSON(w, data, st)
}

func (s *server) artist(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	cookie := r.Header.Get("Cookie")

	key := "artist:" + id
	if hit, ok := s.cache.get(key); ok {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Write(hit)
		return
	}

	data, st, err := s.eapiPost("/eapi/v1/artist/detail", map[string]string{"id": id}, cookie)
	if err != nil {
		writeErr(w, err)
		return
	}
	if st == 200 {
		s.cache.set(key, data, 30*time.Minute)
	}
	writeJSON(w, data, st)
}

func (s *server) artistAlbums(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	id := q.Get("id")
	limit := qDefault(q.Get("limit"), "50")
	offset := qDefault(q.Get("offset"), "0")
	cookie := r.Header.Get("Cookie")

	key := fmt.Sprintf("artist_albums:%s:%s:%s", id, limit, offset)
	if hit, ok := s.cache.get(key); ok {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Write(hit)
		return
	}

	data, st, err := s.eapiPost("/eapi/artist/albums", map[string]string{
		"id": id, "limit": limit, "offset": offset,
	}, cookie)
	if err != nil {
		writeErr(w, err)
		return
	}
	if st == 200 {
		s.cache.set(key, data, 30*time.Minute)
	}
	writeJSON(w, data, st)
}

func (s *server) playlistDetail(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	data, st, err := s.eapiPost("/eapi/v6/playlist/detail", map[string]string{
		"id": id, "n": "500", "s": "0",
	}, r.Header.Get("Cookie"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, data, st)
}

func (s *server) userPlaylist(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	data, st, err := s.eapiPost("/eapi/user/playlist", map[string]string{
		"uid":          q.Get("uid"),
		"limit":        qDefault(q.Get("limit"), "100"),
		"offset":       qDefault(q.Get("offset"), "0"),
		"includeVideo": "false",
	}, r.Header.Get("Cookie"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, data, st)
}

func (s *server) userAccount(w http.ResponseWriter, r *http.Request) {
	cookie := r.Header.Get("Cookie")
	// Cache per cookie prefix to avoid stale data on logout/login
	key := "account:" + cookiePrefix(cookie)
	if hit, ok := s.cache.get(key); ok {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Write(hit)
		return
	}

	data, st, err := s.eapiPost("/eapi/w/nuser/account/get", map[string]string{}, cookie)
	if err != nil {
		writeErr(w, err)
		return
	}
	if st == 200 {
		s.cache.set(key, data, 5*time.Minute)
	}
	writeJSON(w, data, st)
}

func (s *server) userDetail(w http.ResponseWriter, r *http.Request) {
	uid := r.URL.Query().Get("uid")
	data, st, err := s.apiGet("/api/v1/user/detail/"+uid, r.Header.Get("Cookie"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, data, st)
}

func (s *server) recommendResource(w http.ResponseWriter, r *http.Request) {
	data, st, err := s.eapiPost("/eapi/v1/discovery/recommend/resource", map[string]string{}, r.Header.Get("Cookie"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, data, st)
}

func (s *server) recommendSongs(w http.ResponseWriter, r *http.Request) {
	data, st, err := s.eapiPost("/eapi/v3/discovery/recommend/songs", map[string]string{"total": "true"}, r.Header.Get("Cookie"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, data, st)
}

func (s *server) personalFm(w http.ResponseWriter, r *http.Request) {
	data, st, err := s.eapiPost("/eapi/v1/radio/get", map[string]string{}, r.Header.Get("Cookie"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, data, st)
}

func (s *server) fmTrash(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, `{"code":405}`, http.StatusMethodNotAllowed)
		return
	}
	id := r.URL.Query().Get("id")
	data, st, err := s.eapiPost("/eapi/radio/trash/add", map[string]string{"songId": id}, r.Header.Get("Cookie"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, data, st)
}

func (s *server) personalized(w http.ResponseWriter, r *http.Request) {
	limit := qDefault(r.URL.Query().Get("limit"), "10")
	cookie := r.Header.Get("Cookie")

	key := "personalized:" + limit
	if hit, ok := s.cache.get(key); ok {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Write(hit)
		return
	}

	data, st, err := s.apiGet("/api/personalized?limit="+limit, cookie)
	if err != nil {
		writeErr(w, err)
		return
	}
	if st == 200 {
		s.cache.set(key, data, 30*time.Minute)
	}
	writeJSON(w, data, st)
}

func (s *server) topSong(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	limit := qDefault(q.Get("limit"), "30")
	offset := qDefault(q.Get("offset"), "0")
	path := fmt.Sprintf("/api/v1/discovery/new/songs?limit=%s&offset=%s", limit, offset)
	data, st, err := s.apiGet(path, r.Header.Get("Cookie"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, data, st)
}

// --- helpers ---

func qDefault(v, def string) string {
	if v == "" {
		return def
	}
	return v
}

// idsToJSONArray converts "123,456" → "[123,456]"
func idsToJSONArray(ids string) string {
	return "[" + ids + "]"
}

func brToLevel(br string) string {
	switch br {
	case "999000", "1000000":
		return "super"
	case "999999":
		return "sky"
	case "320000":
		return "exhigh"
	case "192000":
		return "higher"
	case "128000":
		return "standard"
	default:
		return "lossless"
	}
}

func cookiePrefix(cookie string) string {
	if len(cookie) > 40 {
		return cookie[:40]
	}
	return cookie
}
