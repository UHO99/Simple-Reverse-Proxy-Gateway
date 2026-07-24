package servers

import (
	"context"
	"fmt"
	"os/exec"
)

type ComposeScaler struct {
	dockerHost  string
	composeFile string
	serviceName string
	projectDir  string
}

func NewComposeScaler(dockerHost, composeFile, serviceName, projectDir string) *ComposeScaler {
	return &ComposeScaler{
		dockerHost:  dockerHost,
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
	)
	cmd.Dir = cs.projectDir
	// cmd.Env = append(cmd.Env, fmt.Sprintf("DOCKER_HOST=%s", cs.dockerHost))

	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("scale failed : %v, output : %s", err, output)
	}

	return nil
}
