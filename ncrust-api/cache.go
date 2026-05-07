package main

import (
	"sync"
	"time"
)

type cacheEntry struct {
	data    []byte
	expires time.Time
}

type memCache struct {
	mu    sync.RWMutex
	items map[string]cacheEntry
}

func newCache() *memCache {
	c := &memCache{items: make(map[string]cacheEntry)}
	go c.evict()
	return c
}

func (c *memCache) get(key string) ([]byte, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	e, ok := c.items[key]
	if !ok || time.Now().After(e.expires) {
		return nil, false
	}
	return e.data, true
}

func (c *memCache) set(key string, data []byte, ttl time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.items[key] = cacheEntry{data: data, expires: time.Now().Add(ttl)}
}

func (c *memCache) evict() {
	for range time.Tick(5 * time.Minute) {
		now := time.Now()
		c.mu.Lock()
		for k, e := range c.items {
			if now.After(e.expires) {
				delete(c.items, k)
			}
		}
		c.mu.Unlock()
	}
}
