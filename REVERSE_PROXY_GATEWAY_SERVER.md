# Reverse Proxy Gateway Server

Nginx, 쿠버네티스 같은 기존 솔루션을 쓰기 전에 **리버스 프록시 / 로드밸런싱 / 오토스케일링 / 헬스체크**가 내부적으로 어떻게 동작하는지 직접 구현해보는 것을 목표로 한 프로젝트입니다.

Go로 만든 게이트웨이가 Docker API로 Spring Boot 인스턴스들을 discovery하고, 헬스체크를 통과한 인스턴스로만 라운드 로빈 요청을 분배하며, 부하에 따라 `docker compose --scale`로 인스턴스 수를 자동으로 늘리고 줄입니다.

실행 방법은 [README.md](./README.md)를 참고하세요.

## 아키텍처

```
                     ┌───────────────────────────┐
                     │  Client (curl / Browser)   │
                     └─────────────┬───────────────┘
                                   │ HTTP
                                   ▼
                     ┌───────────────────────────┐
                     │   Go Gateway (Gin, :8080)  │
                     │  - Reverse Proxy           │
                     │  - Round-Robin LoadBalance │
                     │  - AutoScaler (goroutine)  │
                     └──────┬─────────────┬───────┘
                Docker API  │             │ docker compose
              (discover /   │             │   --scale app=N
               healthcheck) │             │
                            ▼             ▼
          ┌───────────────────────────────────────────┐
          │        Docker Compose (spring-server)      │
          │   ┌───────┐   ┌───────┐   ┌───────┐        │
          │   │ app-1 │   │ app-2 │   │ app-N │  ...   │
          │   │Spring │   │Spring │   │Spring │        │
          │   │ Boot  │   │ Boot  │   │ Boot  │        │
          │   └───┬───┘   └───┬───┘   └───┬───┘        │
          │       └───────────┼───────────┘             │
          │                   ▼                          │
          │               ┌───────┐                     │
          │               │ MySQL │                     │
          │               └───────┘                     │
          └───────────────────────────────────────────┘
```

## 기술 스택

| 영역 | 스택 |
|---|---|
| Gateway (Go) | Gin, Viper, Docker Engine SDK (`moby/moby/client`) |
| Backend (Spring Boot) | Spring Boot, JPA, Flyway, Actuator, MySQL |
| Infra | Docker, Docker Compose |

## 개발 일지

> 아래는 이 프로젝트를 만들며 거친 설계 과정과 트러블슈팅 기록입니다.

해당 프로젝트를 기획한 이유는 Nginx, 쿠버네티스를 사용하기 전 어떻게 서버 인스턴스를 오토 스케일링하고, 헬스체크는 어떻게 하는지 리버스 프록시는 어떻게 구현을 진행하는지 네트워크 처리 되는걸 직접 구현해보고 싶어서 진행하게되었습니다.
### 1. Spring Boot 서버
- Go로 Proxy, LoadBalance를 구현하기 이전에 간단하게 서버 인스턴스를 구현하였습니다.
	- 프로젝트 구조
		- Auth
			- 로그인
			- 로그아웃
		- Board
			- 게시판 CRUD
		- Comment
			- 댓글 CRUD
		- User
			- 회원가입
	- 프로젝트 스택
		- MySQL
		- JPA
		- Flyway
		- Actuator
			- Go Lang 서버에서 각 인스턴스의 헬스 체크를 진행하기 위해서 Actuator 의존성을 받았습니다.
	- docker-compose
		- 각 app의 port를 8080-8090:8080으로 설정하여 각 포트 10개를 번갈아서 각 인스턴스가 점유할 수 있도록 진행
### 2. Go Gin 서버
- 프로젝트 구조
	- api
		- server.go
	- cmd
		- main.go
	- util
		- config.go
		- load_balance.go
	- app.env
- 프로젝트 스택
	- Viper
	- Gin
- 먼저 config.go에서 app.env 환경변수를 Load하는 코드를 작성
  Viper를 통해 load하였습니다
```go
type Config struct {
	DBSource                  string `mapstructure:"DB_SOURCE"`
	ServerAddress             string `mapstructure:"SERVER_ADDRESS"`
	ProxyServerAddress        string `mapstructure:"PROXY_SERVER_ADDRESS"`
	LoadBalanceCheckTime      int    `mapstructure:"LOAD_BALANCE_CHECK_TIME"`
	LoadBalanceStartPort      int    `mapstructure:"LOAD_BALANCE_START_PORT"`
	LoadBalanceEndPort        int    `mapstructure:"LOAD_BALANCE_END_PORT"`
	LoadBalanceHealthCheckURL string `mapstructure:"LOAD_BALANCE_HEALTH_CHECK_URL"`
}

func LoadConfig(path string) (config Config, err error) {
	viper.AddConfigPath(path)
	viper.SetConfigName("app")
	viper.SetConfigType("env")
	viper.AutomaticEnv()

	err = viper.ReadInConfig()
	if err != nil {
		return
	}

	err = viper.Unmarshal(&config)
	return
}
```
- PROXY_SERVER_ADDRESS
	- 리버스 프록시 대상이 되는 Host 서버 URL
- LOAD_BALANCE_CHECK_TIME
	- 인스턴스 서버 헬스체크 타임
- LOAD_BALANCE_START_PORT
	- 인스턴스 서버 시작 포트
- LOAD_BALANCE_END_PORT
	- 인스턴스 서버 끝 포트
- LOAD_BALANCE_HEALTH_CHECK_URL
	- 헬스체크 API URL
```go
package goproxygateway

import (
	"go-proxy-gateway/util"
	"net/http"
	"net/http/httputil"
	"net/url"

	"github.com/gin-gonic/gin"
)

type Server struct {
	config util.Config
	router *gin.Engine
}

func NewServer(config util.Config) (*Server, error) {
	server := &Server{
		config: config,
	}

	if err := server.setupRouter(config); err != nil {
		return nil, err
	}

	return server, nil
}

func (server *Server) setupRouter(config util.Config) error {
	router := gin.Default()

	router.Any("/*proxyPath", func(ctx *gin.Context) {
		target, err := url.Parse("http://192.168.0.78:8080")
		if err != nil {
			ctx.String(http.StatusServiceUnavailable, "No available backend")
			return
		}

		proxy := &httputil.ReverseProxy{
			Rewrite: func(pr *httputil.ProxyRequest) {
				pr.SetURL(target)
				pr.SetXForwarded()
				pr.Out.Host = target.Host
			},
		}

		proxy.ServeHTTP(ctx.Writer, ctx.Request)
	})

	server.router = router
	return nil
}

func (server *Server) Start(address string) error {
	return server.router.Run(address)
}

func errorResponse(err error) gin.H {
	return gin.H{"error": err.Error()}
}
```
- 상단 코드처럼 server.go에 각 인스턴스 중 하나를 대상으로 리버스 프록시 요청이 제대로 동작하는지 테스트를 진행해보았습니다.
 
```
[GIN-debug] GET    /*proxyPath               --> main.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] POST   /*proxyPath               --> main.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] PUT    /*proxyPath               --> main.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] PATCH  /*proxyPath               --> main.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] HEAD   /*proxyPath               --> main.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] OPTIONS /*proxyPath               --> main.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] DELETE /*proxyPath               --> main.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] CONNECT /*proxyPath               --> main.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] TRACE  /*proxyPath               --> main.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] [WARNING] You trusted all proxies, this is NOT safe. We recommend you to set a value.
Please check https://github.com/gin-gonic/gin/blob/master/docs/doc.md#dont-trust-all-proxies for details.
[GIN-debug] Listening and serving HTTP on 0.0.0.0:8080
```
서버가 정상 실행이 되었고 요청을 보내보겠습니다.
```
~/go-proxy-gateway$ curl http://192.168.0.78:8080/actuator/health


{"components":{"db":{"details":{"database":"MySQL","validationQuery":"isValid()"},"status":"UP"},"diskSpace":{"details":{"total":1081101176832,"free":1016971206656,"threshold":10485760,"path":"/app/.","exists":true},"status":"UP"},"livenessState":{"status":"UP"},"ping":{"status":"UP"},"readinessState":{"status":"UP"},"ssl":{"details":{"expiringChains":[],"invalidChains":[],"validChains":[]},"status":"UP"}},"groups":["liveness","readiness"],"status":"UP"}
```
- status : UP으로 정확히 헬스체크가 정상적으로 이루어지는 걸 알 수 있었습니다.
### 3. 로드 밸런싱
- 현재 리버스 프록시 동작 방식은
- *url.URL 객체를 받아 router로 등록되어 특정 서버 인스턴스에 요청하는 방식입니다.
- 하지만 저는 추후 각 인스턴스 서버를 로드밸런싱, 부하 시 오토 스케일링이 되도록 진행하고 싶기에 별도 util 코드를 작성할 필요가 존재했습니다.
```go
package util

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"sync"
	"sync/atomic"
	"time"
)

type LoadBalance struct {
	mu       sync.RWMutex
	backends []*url.URL
	counter  uint64
}

type healthResponse struct {
	Status string `json:"status"`
}

func NewLoadBalance(host string, startPort, endPort int, healthPath string) (*LoadBalance, error) {
	lb := &LoadBalance{}

	backends := scanHealthyBackends(host, startPort, endPort, healthPath)
	if len(backends) == 0 {
		return nil, fmt.Errorf("no healthy backend found in port range %d-%d", startPort, endPort)
	}

	lb.backends = backends
	return lb, nil
}

func scanHealthyBackends(host string, startPort, endPort int, healthPath string) []*url.URL {
	var (
		mu       sync.Mutex
		backends []*url.URL
		wg       sync.WaitGroup
	)

	client := &http.Client{Timeout: 2 * time.Second}

	for port := startPort; port <= endPort; port++ {
		wg.Add(1)
		go func(port int, host string) {
			defer wg.Done()

			addr := fmt.Sprintf("http://%s:%d", host, port)
			healthURL := addr + healthPath

			resp, err := client.Get(healthURL)
			if err != nil {
				return
			}
			defer resp.Body.Close()

			if resp.StatusCode != http.StatusOK {
				log.Printf("[HealthCheck] %s -> status %d, skip", healthURL, resp.StatusCode)
				return
			}

			var hr healthResponse
			if err := json.NewDecoder(resp.Body).Decode(&hr); err != nil {
				log.Printf("[HealthCheck] %s -> decode error : %v, skip", healthURL, err)
				return
			}

			if hr.Status != "UP" {
				log.Printf("[HealthCheck] %s -> status : %s, skip", healthURL, hr.Status)
				return
			}

			parsedURL, err := url.Parse(addr)
			if err != nil {
				log.Printf("[HealthCheck] invalid URL %s : %v", addr, err)
				return
			}

			mu.Lock()
			backends = append(backends, parsedURL)
			mu.Unlock()

			log.Printf("[HealthCheck] %s -> UP, append to pool", addr)
		}(port, host)
	}

	wg.Wait()
	return backends
}

func (lb *LoadBalance) NextBackend() (*url.URL, error) {
	lb.mu.RLock()
	defer lb.mu.RUnlock()

	if len(lb.backends) == 0 {
		return nil, fmt.Errorf("no available backend")
	}

	idx := atomic.AddUint64(&lb.counter, 1) - 1
	return lb.backends[idx%uint64(len(lb.backends))], nil
}

func (lb *LoadBalance) StartHealthCheckLoop(host string, startPort, endPort int, healthPath string, interval time.Duration) {
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		for range ticker.C {
			newBackends := scanHealthyBackends(host, startPort, endPort, healthPath)
			if len(newBackends) == 0 {
				log.Printf("[HealthCheck] no healthy backend found")
			}

			lb.mu.Lock()
			lb.backends = newBackends
			lb.mu.Unlock()

			log.Printf("[HealthCheck] backend pool refreshed")
		}
	}()
}
```
- 해당 코드는 LoadBalance라는 객체 클래스를 작성 해당 구조체 내부에는 각 서버 인스턴스 URL이 담길 backends 슬라이스, 각 인스턴스를 선택할 `라운드 로빈` 알고리즘을 사용하기 위한 Read Lock과 counter
- scanHealthyBackends(호스트 address, 시작 포트, 끝 포트, 헬스 체크 API Url) 을 입력받습니다
	- 시작 포트 - 끝 포트까지 병렬로 헬스 체크를 하기 위해 WaitGroup과 헬스 status UP에 성공한 url을 backends 슬라이스에 추가할 때 원자, 독립성 보장을 위한 Mutex를 추가하였습니다.
- StartHealthCheckLoop(호스트 address, 시작 포트, 끝 포트, 헬스 체크 API Url, 인터벌)
	- 헬스체크 메서드를 주기적으로 실행하기 위해서 time.NewTicker 생성자에 인터벌 타임을 매개변수로 주어 30초 마다 헬스체킹을 진행합니다.
### 4. 리버스 프록시 & 로드 밸런싱 통합
```go
package api

import (
	"go-proxy-gateway/util"
	"log"
	"net/http"
	"net/http/httputil"
	"time"

	"github.com/gin-gonic/gin"
)

type Server struct {
	config util.Config
	router *gin.Engine
	lb     *util.LoadBalance
}

func NewServer(config util.Config) (*Server, error) {
	server := &Server{
		config: config,
	}

	if err := server.setupRouter(config); err != nil {
		return nil, err
	}

	return server, nil
}

func (server *Server) setupRouter(config util.Config) error {
	lb, err := util.NewLoadBalance(
		config.ProxyServerAddress,
		config.LoadBalanceStartPort,
		config.LoadBalanceEndPort,
		config.LoadBalanceHealthCheckURL,
	)
	if err != nil {
		log.Fatalf("Failed to init load balance : %v", err)
	}
	server.lb = lb

	lb.StartHealthCheckLoop(
		config.ProxyServerAddress,
		config.LoadBalanceStartPort,
		config.LoadBalanceEndPort,
		config.LoadBalanceHealthCheckURL,
		time.Duration(config.LoadBalanceCheckTime)*time.Second,
	)

	router := gin.Default()

	router.Any("/*proxyPath", func(ctx *gin.Context) {
		target, err := lb.NextBackend()
		if err != nil {
			ctx.String(http.StatusServiceUnavailable, "No available backend")
			return
		}

		proxy := &httputil.ReverseProxy{
			Rewrite: func(pr *httputil.ProxyRequest) {
				pr.SetURL(target)
				pr.SetXForwarded()
				pr.Out.Host = target.Host
			},
		}

		proxy.ServeHTTP(ctx.Writer, ctx.Request)
	})

	server.router = router
	return nil
}

func (server *Server) Start(address string) error {
	return server.router.Run(address)
}

func errorResponse(err error) gin.H {
	return gin.H{"error": err.Error()}
}
```
- 이전 router SetURL에서 작성한 하드 코딩 부분이 아닌 util.load_balance의 NextBackend로 주기적으로 헬스체크 및 URL을 반환해주는 코드로 대체하여 30초 별로 서버를 체킹해주는 리버스 프록시 로드밸런싱 서버가 되었습니다.
```
2026/07/24 04:20:58 [HealthCheck] http://192.168.0.78:8080 -> UP, append to pool
2026/07/24 04:20:58 [HealthCheck] http://192.168.0.78:8081 -> UP, append to pool
2026/07/24 04:20:58 [HealthCheck] http://192.168.0.78:8082 -> UP, append to pool
[GIN-debug] [WARNING] Creating an Engine instance with the Logger and Recovery middleware already attached.

[GIN-debug] [WARNING] Running in "debug" mode. Switch to "release" mode in production.
 - using env:   export GIN_MODE=release
 - using code:  gin.SetMode(gin.ReleaseMode)

[GIN-debug] GET    /*proxyPath               --> go-proxy-gateway/api.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] POST   /*proxyPath               --> go-proxy-gateway/api.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] PUT    /*proxyPath               --> go-proxy-gateway/api.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] PATCH  /*proxyPath               --> go-proxy-gateway/api.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] HEAD   /*proxyPath               --> go-proxy-gateway/api.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] OPTIONS /*proxyPath               --> go-proxy-gateway/api.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] DELETE /*proxyPath               --> go-proxy-gateway/api.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] CONNECT /*proxyPath               --> go-proxy-gateway/api.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] TRACE  /*proxyPath               --> go-proxy-gateway/api.(*Server).setupRouter.func1 (3 handlers)
[GIN-debug] [WARNING] You trusted all proxies, this is NOT safe. We recommend you to set a value.
Please check https://github.com/gin-gonic/gin/blob/master/docs/doc.md#dont-trust-all-proxies for details.
[GIN-debug] Listening and serving HTTP on 0.0.0.0:8080
```
- 결과를 보면 현재 Docker에서 수동으로 실행한 각 인스턴스 서버의 헬스 체킹 후 pool에 넣어서 관리되고 있습니다.
### 5. 오토 스케일링
- 현재 프로젝트를 보면 서버 인스턴스 갯수가 정적이고 따로 Scale In을 진행해줄 인스턴스를 수동으로 확장해야하는 불편함이 존재합니다. 때문에 저는 포트 스캔을 정적으로 하는것이 아닌 docker api를 사용하기로 생각했습니다. 이를 위해 기존 `util.LoadBalance`(포트 스캔 방식)를 대체하는 `servers` 패키지를 새로 만들고, `auto_scaler.go` / `compose_scale.go` / `docker.go`를 추가했습니다.

- **`servers/docker.go`** — Docker Engine API에 붙을 클라이언트를 생성합니다. 처음에는 원격 호스트에 SSH로 붙는 것도 고려했지만(연결 헬퍼로 `WithHost`/`WithDialContext`를 쓰는 방식), 게이트웨이와 컨테이너가 항상 같은 호스트에 있는 구조로 확정하면서 로컬 소켓(`DOCKER_HOST` 미설정 시 OS 기본 소켓)만 쓰도록 정리했습니다.
```go
package servers

import (
	"github.com/docker/docker/client"
)

func NewDockerClient() (*client.Client, error) {
	return client.NewClientWithOpts(
		client.FromEnv,
		client.WithAPIVersionNegotiation(),
	)
}
```

- **`servers/load_balance.go`** — 포트 범위를 순회하며 헬스체크하던 기존 방식 대신, Docker API로 `com.docker.compose.service=<serviceName>` 라벨이 붙은 **실행 중인 컨테이너**를 직접 조회(`DiscoverBackends`)한 뒤 각 컨테이너가 노출한 퍼블릭 포트로 헬스체크를 돌립니다. 그리고 `Backend`에 `ActiveConns`(현재 처리 중인 요청 수)를 들고 있게 해서, 이 값을 오토스케일러가 부하 지표로 그대로 사용합니다.
```go
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
			if p.PrivatePort == 8080 && p.PublicPort != 0 {
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
```
- `DiscoverBackends`는 라벨+상태로 필터링한 컨테이너 중 컨테이너 내부 포트(`PrivatePort`)가 스프링 앱이 리스닝하는 포트와 일치하는 것만 골라 호스트에 매핑된 퍼블릭 포트로 주소를 구성합니다.
- `RefreshFromDocker`는 discovery로 얻은 후보 목록에 대해 고루틴으로 병렬 헬스체크를 돌리고, 기존에 있던 `Backend`는 재사용(`existing` 맵)해서 `ActiveConns` 카운터가 리프레시할 때마다 초기화되지 않도록 합니다.
- `AvgLoad`가 반환하는 평균 활성 커넥션 수가 오토스케일러의 스케일 아웃/인 판단 기준이 됩니다.

- **`servers/compose_scale.go`** — 실제로 인스턴스 수를 늘리고 줄이는 부분입니다. `docker compose up -d --scale <service>=<N>`을 그대로 셸에서 실행합니다.
```go
package servers

import (
	"context"
	"fmt"
	"os/exec"
)

type ComposeScaler struct {
	composeFile string
	serviceName string
	projectDir  string
}

func NewComposeScaler(composeFile, serviceName, projectDir string) *ComposeScaler {
	return &ComposeScaler{
		composeFile: composeFile,
		serviceName: serviceName,
		projectDir:  projectDir,
	}
}

func (cs *ComposeScaler) ScaleTo(ctx context.Context, replicas int) error {
	cmd := exec.CommandContext(ctx, "docker", "compose",
		"-f", cs.composeFile,
		"up", "-d",
		"--scale", fmt.Sprintf("%s=%d", cs.serviceName, replicas),
		"--no-recreate",
		"--no-build",
	)
	cmd.Dir = cs.projectDir

	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("scale failed : %v, output : %s", err, output)
	}

	return nil
}
```
- `--no-recreate`로 이미 떠 있는 컨테이너는 건드리지 않고 개수만 맞추고, `--no-build`로 스케일 시점에는 절대 이미지를 재빌드하지 않도록 강제합니다(빌드는 배포 시 1회만 — 자세한 배경은 [트러블슈팅 2번](#2-docker-composeyml의-build--image-혼동) 참고).

- **`servers/auto_scaler.go`** — 위 세 조각(`LoadBalance`, `ComposeScaler`, Docker client)을 엮어서 주기적으로 discovery하고 부하를 평가해 스케일 여부를 결정합니다.
```go
package servers

import (
	"context"
	"log"
	"sync"
	"time"

	"github.com/docker/docker/client"
)

type AutoScaler struct {
	lb          *LoadBalance
	scaler      *ComposeScaler
	dockerCli   *client.Client
	host        string
	serviceName string
	healthPath  string

	currentReplicas int
	minInstances    int
	maxInstances    int
	scaleOutTh      float64
	scaleInTh       float64
	lastScaleTime   time.Time
	cooldown        time.Duration
	mu              sync.Mutex
}

func NewAutoScaler(
	lb *LoadBalance,
	scaler *ComposeScaler,
	dockerCli *client.Client,
	host, serviceName, healthPath string,
	currentReplicas, minInstances, maxInstances int,
	scaleOutTh, scaleInTh float64,
	cooldown time.Duration,
) *AutoScaler {
	return &AutoScaler{
		lb:              lb,
		scaler:          scaler,
		dockerCli:       dockerCli,
		host:            host,
		serviceName:     serviceName,
		healthPath:      healthPath,
		currentReplicas: currentReplicas,
		minInstances:    minInstances,
		maxInstances:    maxInstances,
		scaleOutTh:      scaleOutTh,
		scaleInTh:       scaleInTh,
		cooldown:        cooldown,
	}
}

func (auto *AutoScaler) Run(ctx context.Context) {
	discoverTicker := time.NewTicker(10 * time.Second)
	evalTicker := time.NewTicker(15 * time.Second)
	defer discoverTicker.Stop()
	defer evalTicker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-discoverTicker.C:
			auto.lb.RefreshFromDocker(ctx, auto.dockerCli, auto.host, auto.serviceName, auto.healthPath)
		case <-evalTicker.C:
			auto.evaluate(ctx)
		}
	}
}

func (auto *AutoScaler) evaluate(ctx context.Context) {
	auto.mu.Lock()
	defer auto.mu.Unlock()

	if time.Since(auto.lastScaleTime) < auto.cooldown {
		return
	}

	avgLoad := auto.lb.AvgLoad()
	desired := auto.currentReplicas

	switch {
	case avgLoad > auto.scaleOutTh && auto.currentReplicas < auto.maxInstances:
		desired = auto.currentReplicas + 1
	case avgLoad < auto.scaleInTh && auto.currentReplicas > auto.minInstances:
		desired = auto.currentReplicas - 1
	default:
		return
	}

	if err := auto.scaler.ScaleTo(ctx, desired); err != nil {
		log.Printf("scale to %d failed : %v", desired, err)
		return
	}

	log.Printf("scaled %s : %d -> %d instance (avgLoad=%.2f)", auto.serviceName, auto.currentReplicas, desired, avgLoad)
	auto.currentReplicas = desired
	auto.lastScaleTime = time.Now()

	time.AfterFunc(10*time.Second, func() {
		auto.lb.RefreshFromDocker(ctx, auto.dockerCli, auto.host, auto.serviceName, auto.healthPath)
	})
}
```
- `Run`은 두 개의 티커로 discovery(10초)와 스케일 평가(15초)를 분리해서 돌립니다 — discovery가 너무 잦으면 Docker API/헬스체크 부하가 커지고, 너무 뜸하면 스케일 직후 상태가 반영되기까지 지연이 생기기 때문에 별도 주기로 나눴습니다.
- `evaluate`는 쿨다운이 지나지 않았으면 즉시 리턴하고, 그렇지 않으면 평균 활성 커넥션(`avgLoad`)과 임계값을 비교해 `desired` 값을 정한 뒤 `ComposeScaler.ScaleTo`를 호출합니다. 스케일 직후에는 10초 뒤 한 번 더 `RefreshFromDocker`를 예약해서, 새로 뜬(또는 내려간) 컨테이너 상태가 빠르게 풀에 반영되도록 합니다.

- **`api/server.go`** — 위 컴포넌트들을 실제로 조립하는 지점입니다. 서버가 시작될 때 Docker 클라이언트 → LoadBalance → (필요 시 최소 인스턴스로 부트스트랩 스케일업) → AutoScaler 순서로 초기화하고, AutoScaler는 별도 고루틴으로 백그라운드에서 계속 돌립니다.
```go
func (server *Server) setupServer(config util.Config) error {
	ctx := context.Background()

	dockerCli, err := servers.NewDockerClient()
	if err != nil {
		log.Fatal("Failed init docker client", err)
	}

	lb := servers.NewLoadBalance()
	server.lb = lb

	lb.RefreshFromDocker(ctx, dockerCli, config.ProxyServerAddress, config.ComposeServiceName, config.LoadBalanceHealthCheckURL)

	scaler := servers.NewComposeScaler(
		config.ComposeFilePath,
		config.ComposeServiceName,
		config.ComposeProjectDir,
	)

	initialReplicas := lb.Count()
	if initialReplicas < config.MinInstances {
		log.Printf("[Bootstrap] running instances (%d) below min (%d), scaling up", initialReplicas, config.MinInstances)
		if err := scaler.ScaleTo(ctx, config.MinInstances); err != nil {
			log.Fatalf("failed to bootstrap min instances : %v", err)
		}
		initialReplicas = config.MinInstances
	}

	autoScaler := servers.NewAutoScaler(
		lb,
		scaler,
		dockerCli,
		config.ProxyServerAddress,
		config.ComposeServiceName,
		config.LoadBalanceHealthCheckURL,
		initialReplicas,
		config.MinInstances,
		config.MaxInstances,
		config.ScaleOutThreshold,
		config.ScaleInThreshold,
		time.Duration(config.ScaleCooldownSeconds)*time.Second,
	)
	server.auto = autoScaler

	go autoScaler.Run(ctx)

	// ... 라우터 설정은 이전과 동일하되, lb.NextBackend()가 반환하는 *Backend의
	// ActiveConns를 요청 처리 전후로 증감시켜 AutoScaler가 참조할 실시간 부하 지표로 사용합니다.
}
```
- 서버가 켜질 때 실행 중인 인스턴스 수가 `MinInstances`보다 적으면 자동으로 최소 인스턴스까지 끌어올리는 부트스트랩 단계가 들어간 것이 핵심 변화입니다 — 이 값을 `AutoScaler`의 초기 `currentReplicas`로 그대로 넘겨야 스케일 인/아웃 판단이 실제 인프라 상태와 어긋나지 않습니다(자세한 배경은 [트러블슈팅 3번](#3-오토스케일러-초기-currentreplicas-카운트-버그로-scale-in-미동작) 참고).
- 정리하면 전체 흐름은 **discovery(Docker API) → 병렬 헬스체크 → 요청마다 ActiveConns 증감으로 부하 측정 → 주기적으로 평균 부하 평가 → 임계값을 벗어나면 `docker compose --scale` 호출 → 재discovery로 최신 상태 반영** 순으로 돌아갑니다.

## 트러블슈팅

### 1) WSL Docker 소켓 권한 (`permission denied`)
- **증상**: `go run cmd/main.go` 실행 시
  ```
  [Discover] failed : permission denied while trying to connect to the Docker daemon socket at unix:///var/run/docker.sock
  ```
- **원인**: Docker Desktop의 WSL 통합(WSL Integration)은 켜져 있었지만, WSL(Ubuntu) 사용자 계정이 `docker` 그룹에 속해있지 않아 소켓 접근 권한이 없었음. (그 이전 단계에서는 통합 자체가 꺼져 있어 `/var/run/docker.sock`이 아예 없는 상태였고, 통합을 켠 뒤에야 이 권한 문제로 넘어옴)
- **해결**:
  ```bash
  sudo usermod -aG docker $USER
  wsl --shutdown   # Windows 쪽에서 실행 후 WSL 터미널 재시작 (그룹 반영)
  ```
- **검증**: `docker ps`가 `sudo` 없이 정상 출력되는지로 확인.

### 2) `docker-compose.yml`의 `build` / `image` 혼동
- **증상**: `docker compose build app` 실행 시 빌드 컨텍스트를 찾지 못해 실패.
- **원인**: `build: spring-server:1.0.0`처럼 이미지 태그 문자열을 `build` 키에 그대로 넣었음. Compose에서 `build`는 **빌드 컨텍스트(디렉터리 경로)**를 받는 키이고, 결과물에 이름/태그를 붙이려면 별도의 `image` 키가 필요함.
- **해결**:
  ```yaml
  app:
    build: .
    image: uho9984/spring-test-server:1.0.0
  ```
  `build`(컨텍스트)와 `image`(태그)를 분리하면 `docker compose build`가 `Dockerfile`로 빌드한 뒤 지정한 태그를 붙여준다.
- **추가 조치**: 오토스케일러가 스케일 아웃 때 이미지가 이미 로컬에 존재한다고 가정하도록 `ComposeScaler.ScaleTo`가 실행하는 명령에 `--no-build`를 추가. 스케일 이벤트 도중에는 절대 재빌드가 발생하지 않게 하고, 빌드는 배포 시 1회만 수행하는 것으로 역할을 분리함.

### 3) 오토스케일러 초기 `currentReplicas` 카운트 버그로 Scale In 미동작
- **증상**: 실제 컨테이너는 2개가 떠 있고 `AvgLoad` 도 `SCALE_IN_THRESHOLD` 아래, 쿨다운도 지났는데 스케일 인이 전혀 발생하지 않음.
- **원인**: `api/server.go`의 `setupServer()`에서 `AutoScaler` 생성 시 넘기는 초기 `currentReplicas` 값을 실제 상태 조회 없이 무조건 `config.MinInstances`로 고정해서 넘기고 있었음. 실제로는 이미 2개가 떠 있는데 오토스케일러 내부는 "현재 1개"라고 착각하고 있어서, `evaluate()`의 스케일 인 조건(`currentReplicas > minInstances`, 즉 `1 > 1`)이 항상 거짓이 되어 절대 줄어들 수 없는 상태였음.
- **해결**: 서버 시작 시 `lb.Count()`로 실제 healthy 인스턴스 수를 먼저 구해 `initialReplicas`에 저장하고, 그 값이 `MinInstances`보다 적을 때만 부트스트랩 스케일업을 수행한 뒤 `initialReplicas`를 갱신 — 이렇게 실측한 값을 `AutoScaler`의 초기 `currentReplicas`로 전달하도록 수정.
  ```go
  initialReplicas := lb.Count()
  if initialReplicas < config.MinInstances {
      scaler.ScaleTo(ctx, config.MinInstances)
      initialReplicas = config.MinInstances
  }
  // ...
  autoScaler := servers.NewAutoScaler(..., initialReplicas, config.MinInstances, ...)
  ```
- **교훈**: 오토스케일링처럼 상태를 들고 있는 로직은 "실제 인프라 상태"와 "애플리케이션이 기억하는 상태"가 어긋나는 순간 조용히 멈춰버린다. 시작 시점에는 항상 실측값으로 내부 상태를 동기화해야 한다.
