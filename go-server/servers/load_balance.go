package servers

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"sync"
	"sync/atomic"
	"time"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/filters"
	"github.com/docker/docker/client"
)

type Backend struct {
	URL         *url.URL
	ActiveConns int64
}

type LoadBalance struct {
	mu       sync.RWMutex
	backends []*Backend
	counter  uint64
}

func NewLoadBalance() *LoadBalance {
	return &LoadBalance{}
}

func DiscoverBackends(ctx context.Context, cli *client.Client, host, composeService string) ([]*url.URL, error) {
	containers, err := cli.ContainerList(ctx, types.ContainerListOptions{
		Filters: filters.NewArgs(
			filters.Arg("label", fmt.Sprintf("com.docker.compose.service=%s", composeService)),
			filters.Arg("status", "running"),
		),
	})
	if err != nil {
		return nil, err
	}

	var backends []*url.URL
	for _, c := range containers {
		for _, p := range c.Ports {
			if p.PrivatePort == 8000 && p.PublicPort != 0 {
				addr := fmt.Sprintf("http://%s:%d", host, p.PublicPort)
				u, err := url.Parse(addr)
				if err == nil {
					backends = append(backends, u)
				}
			}
		}
	}

	return backends, nil
}

func (lb *LoadBalance) RefreshFromDocker(ctx context.Context, cli *client.Client, host, serviceName, healthPath string) {
	candidates, err := DiscoverBackends(ctx, cli, host, serviceName)
	if err != nil {
		log.Printf("[Discover] failed : %v", err)
		return
	}

	lb.mu.RLock()
	existing := make(map[string]*Backend, len(lb.backends))
	for _, b := range lb.backends {
		existing[b.URL.String()] = b
	}
	lb.mu.RUnlock()

	httpClient := &http.Client{Timeout: 2 * time.Second}
	var healthy []*Backend
	var wg sync.WaitGroup
	var mu sync.Mutex

	for _, u := range candidates {
		wg.Add(1)
		go func(u *url.URL) {
			defer wg.Done()

			res, err := httpClient.Get(u.String() + healthPath)
			if err != nil || res.StatusCode != http.StatusOK {
				return
			}
			defer res.Body.Close()

			mu.Lock()
			defer mu.Unlock()
			if b, ok := existing[u.String()]; ok {
				healthy = append(healthy, b)
			} else {
				healthy = append(healthy, &Backend{URL: u})
			}
		}(u)
	}
	wg.Wait()

	lb.mu.Lock()
	lb.backends = healthy
	lb.mu.Unlock()

	log.Printf("[Discover] %d instance discover, %d healthy", len(candidates), len(healthy))
}

func (lb *LoadBalance) NextBackend() (*Backend, error) {
	lb.mu.RLock()
	defer lb.mu.RUnlock()

	if len(lb.backends) == 0 {
		return nil, fmt.Errorf("no available backend")
	}

	idx := atomic.AddUint64(&lb.counter, 1) - 1
	return lb.backends[idx%uint64(len(lb.backends))], nil
}

func (lb *LoadBalance) AvgLoad() float64 {
	lb.mu.RLock()
	defer lb.mu.RUnlock()

	if len(lb.backends) == 0 {
		return 0
	}

	var total int64
	for _, b := range lb.backends {
		total += atomic.LoadInt64(&b.ActiveConns)
	}

	return float64(total) / float64(len(lb.backends))
}

func (lb *LoadBalance) Count() int {
	lb.mu.RLock()
	defer lb.mu.RUnlock()
	return len(lb.backends)
}
