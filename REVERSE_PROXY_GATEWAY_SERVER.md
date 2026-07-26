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
- 현재 프로젝트를 보면 서버 인스턴스 갯수가 정적이고 따로 Scale In을 진행해줄 인스턴스를 수동으로 확장해야하는 불편함이 존재합니다. 때문에 저는 포트 스캔을 정적으로 하는것이 아닌 docker api를 사용하기로 생각했습니다.

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

## 보안 (Security)

현재 구현은 동작 원리를 학습/실험하는 데 초점을 맞춰서, 프로덕션에 그대로 올리기엔 아래와 같은 보안 공백이 있습니다.

- **평문 자격증명**: `spring-server/docker-compose.yml`에 `MYSQL_ROOT_PASSWORD`, `SPRING_DATASOURCE_PASSWORD`가 `root`로 하드코딩되어 있음. → `.env` 파일 분리 + `.gitignore` 처리, 또는 Docker secrets/Vault 같은 시크릿 매니저로 교체 필요.
- **`app.env`의 `TOKEN_SYMMETRIC_KEY` 평문 노출**: JWT 대칭키가 설정 파일에 평문으로 들어있음. `app.env`는 실제 값, `app.env.example`은 템플릿으로 분리는 돼 있지만, `app.env` 자체가 저장소에 커밋되지 않는지 재확인 필요.
- **Gin "trusted all proxies" 경고**: `router.SetTrustedProxies`를 설정하지 않아 모든 프록시를 신뢰하는 상태. `X-Forwarded-For` 헤더를 클라이언트가 위조하면 실제 IP를 속일 수 있음. → 신뢰할 프록시(게이트웨이 자신의 IP 대역)만 명시적으로 설정.
- **Actuator 엔드포인트 무인증 전체 노출**: `/actuator/health`는 헬스체크용으로 의도된 것이지만, `management.endpoints.web.exposure`를 명시적으로 `health,info` 등으로 제한하지 않으면 `/actuator/env`, `/actuator/beans` 같은 민감한 엔드포인트까지 인증 없이 노출될 수 있음.
- **평문 HTTP 구간**: 클라이언트 ↔ 게이트웨이, 게이트웨이 ↔ 백엔드 구간 모두 TLS 없이 평문 HTTP. → 게이트웨이 앞단에서 TLS 종료(리버스 프록시 레벨 인증서 적용) 필요.
- **Docker 소켓 직접 접근의 권한 범위**: Go 게이트웨이가 `/var/run/docker.sock`에 직접 접근하는데, 이 소켓 접근 권한은 사실상 호스트에 대한 루트 권한과 동급임. 게이트웨이 프로세스가 침해당하면 호스트 전체가 위험해질 수 있음(최소 권한 원칙 위반). → 권한을 제한한 Docker socket proxy(예: `tecnativa/docker-socket-proxy`)를 경유하도록 개선 여지가 있음.
- **게이트웨이 레벨 인가/레이트리밋 부재**: 리버스 프록시가 `/*proxyPath`로 들어오는 모든 요청을 조건 없이 백엔드로 전달함. 인증/인가, IP allowlist, rate limiting 등이 게이트웨이 레벨에 없어서 백엔드(Spring Security 등)에 전적으로 의존하는 구조.
