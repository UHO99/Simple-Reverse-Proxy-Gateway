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
