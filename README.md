해당 프로젝트를 기획한 이유는 Nginx, 쿠버네티스를 사용하기 전 어떻게 서버 인스턴스를 오토 스케일링하고, 헬스체크는 어떻게 하는지 리버스 프록시는 어떻게 구현을 진행하는지 네트워크 처리 되는걸 직접 구현해보고 싶어서 진행하게되었습니다.
## 1. Spring Boot 서버
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
			- Go Lang 서버에서 각 인스턴스의 헬스 체크를 진행하기 위해서 Actuator 의존성을 받았습니디.
	- dokcer-compose
		- 각 app의 port를 8080-8090:8080으로 설정하여 각 포트 10개를 번갈아서 각 인스턴스가 점유할 수 있도록 진행
## 2. Go Gin 서버
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

    DBSource                  string `mapstructure:"DB_SOURCE"`

    ServerAddress             string `mapstructure:"SERVER_ADDRESS"`

    ProxyServerAddress        string `mapstructure:"PROXY_SERVER_ADDRESS"`

    LoadBalanceCheckTime      int    `mapstructure:"LOAD_BALANCE_CHECK_TIME"`

    LoadBalanceStartPort      int    `mapstructure:"LOAD_BALANCE_START_PORT"`

    LoadBalanceEndPort        int    `mapstructure:"LOAD_BALANCE_END_PORT"`

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
- 상단 코드처럼 server.go에 각 인스턴중 하나를 대상으로 리버스 프록시 요청이 제대로 동작하는지 테스트를 진행해보았습니다.
 
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
- status : UP으로 정확히 헬스체크가 정상적으로 이루어 지는걸 알 수 있었습니다.
## 3. 로드 밸런싱
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

    mu       sync.RWMutex

    backends []*url.URL

    counter  uint64

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

        mu       sync.Mutex

        backends []*url.URL

        wg       sync.WaitGroup

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
	- 헬스체크 메서드를 주기적으로 실행하기 위해서 time.NewTicker 생성자에 인터벌 타임을 매개변수러 주어 30초 마다 헬스체킹을 진행합니다.
## 4. 리버스 프록시 & 로드 밸런싱 통합
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

    lb     *util.LoadBalance

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
## 5. 오토 스케일링
- 현재 프로젝트를 보면 서버 인스턴스 갯수가 정적이고 따로 Scale In을 진행해줄 인스턴스를 수동으로 확장해야하는 불편함이 존재합니다. 때문에 저는 포트 스캔을 정적으로 하는것이 아닌 docker api를 사용하기로 생각했습니다.