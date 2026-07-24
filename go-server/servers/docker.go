package servers

import (
	"github.com/docker/docker/client"
)

func NewDockerClient() (*client.Client, error) {
	/*
		helper, err := connhelper.GetConnectionHelper(sshHost)
		if err != nil {
			return nil, err
		}

		cli, err := client.NewClientWithOpts(
			client.WithHost(helper.Host),
			client.WithDialContext(helper.Dialer),
			client.WithAPIVersionNegotiation(),
		)
		if err != nil {
			return nil, err

		}

		return cli, nil
	*/

	return client.NewClientWithOpts(
		client.FromEnv,
		client.WithAPIVersionNegotiation(),
	)
}
