package api

import (
	"context"
	"go-proxy-gateway/servers"
	"go-proxy-gateway/util"
	"log"
	"net/http"
	"net/http/httputil"
	"sync/atomic"
	"time"

	"github.com/gin-gonic/gin"
)

type Server struct {
	config util.Config
	router *gin.Engine
	lb     *servers.LoadBalance
	auto   *servers.AutoScaler
}

func NewServer(config util.Config) (*Server, error) {
	server := &Server{
		config: config,
	}

	if err := server.setupServer(config); err != nil {
		return nil, err
	}

	return server, nil
}

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

	router := gin.Default()
	transport := &http.Transport{}

	router.Any("/*proxyPath", func(ctx *gin.Context) {
		backend, err := lb.NextBackend()
		if err != nil {
			ctx.String(http.StatusServiceUnavailable, "No available backend")
			return
		}

		atomic.AddInt64(&backend.ActiveConns, 1)
		defer atomic.AddInt64(&backend.ActiveConns, -1)

		proxy := &httputil.ReverseProxy{
			Rewrite: func(pr *httputil.ProxyRequest) {
				pr.SetURL(backend.URL)
				pr.SetXForwarded()
				pr.Out.Host = backend.URL.Host
			},
			Transport: transport,
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
