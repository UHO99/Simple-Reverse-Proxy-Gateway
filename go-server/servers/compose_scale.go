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
