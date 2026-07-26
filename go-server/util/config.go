package util

import (
	"github.com/spf13/viper"
)

type Config struct {
	DBSource                  string `mapstructure:"DB_SOURCE"`
	ServerAddress             string `mapstructure:"SERVER_ADDRESS"`
	ProxyServerAddress        string `mapstructure:"PROXY_SERVER_ADDRESS"`
	LoadBalanceCheckTime      int    `mapstructure:"LOAD_BALANCE_CHECK_TIME"`
	LoadBalanceHealthCheckURL string `mapstructure:"LOAD_BALANCE_HEALTH_CHECK_URL"`

	// Docker / Compose
	ComposeFilePath    string `mapstructure:"COMPOSE_FILE_PATH"`
	ComposeProjectDir  string `mapstructure:"COMPOSE_PROJECT_DIR"`
	ComposeServiceName string `mapstructure:"COMPOSE_SERVICE_NAME"`

	// AutoScaler
	MinInstances         int     `mapstructure:"MIN_INSTANCES"`
	MaxInstances         int     `mapstructure:"MAX_INSTANCES"`
	ScaleOutThreshold    float64 `mapstructure:"SCALE_OUT_THRESHOLD"`
	ScaleInThreshold     float64 `mapstructure:"SCALE_IN_THRESHOLD"`
	ScaleCooldownSeconds int     `mapstructure:"SCALE_COOLDOWN_SECONDS"`
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
