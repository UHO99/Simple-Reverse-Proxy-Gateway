# Simple Reverse Proxy & Gateway

Go 리버스 프록시 게이트웨이 + 로드밸런서 + 오토스케일러, Spring Boot 백엔드로 구성된 프로젝트입니다.

프로젝트 개요, 아키텍처, 개발 과정, 트러블슈팅 관련 내용은 [REVERSE_PROXY_GATEWAY_SERVER.md](./REVERSE_PROXY_GATEWAY_SERVER.md)를 참고하세요.

## 실행 방법 (Quick Start)

### 사전 준비
- Docker Desktop 설치
- WSL(Ubuntu 등)에서 실행할 경우 Docker Desktop → Settings → Resources → WSL Integration에서 해당 배포판 활성화
- WSL(Linux) 사용자 계정이 `docker` 그룹에 속해 있어야 함
	```bash
	sudo usermod -aG docker $USER
	# 적용 안 되면
	wsl --shutdown   # Windows 쪽에서 실행 후 WSL 터미널 재시작
	```
- Go 1.2x 이상

### 1) Spring Boot 이미지 빌드 (최초 1회, 소스 변경 시에만 다시)
```bash
cd spring-server
docker compose build app
```
- 오토스케일러가 스케일 시 이미지를 재빌드하지 않도록 `--no-build`로 동작하기 때문에, 이 단계에서 이미지를 미리 만들어 두어야 합니다.

### 2) DB + 앱 인스턴스 기동
```bash
docker compose up -d
docker ps --filter "label=com.docker.compose.service=app"
```

### 3) Go 게이트웨이 환경설정
`go-server/app.env`를 자신의 환경에 맞게 수정합니다.

| 변수 | 설명 |
|---|---|
| `PROXY_SERVER_ADDRESS` | Spring 컨테이너들이 붙어있는 Docker 호스트 주소 (WSL이면 `localhost`) |
| `COMPOSE_FILE_PATH` | `spring-server/docker-compose.yml`의 절대 경로 |
| `COMPOSE_PROJECT_DIR` | `spring-server` 디렉터리의 절대 경로 |
| `COMPOSE_SERVICE_NAME` | 스케일 대상 서비스명 (`app`) |
| `MIN_INSTANCES` / `MAX_INSTANCES` | 최소/최대 인스턴스 수 |
| `SCALE_OUT_THRESHOLD` / `SCALE_IN_THRESHOLD` | 평균 활성 커넥션 기준 스케일 아웃/인 임계값 |
| `SCALE_COOLDOWN_SECONDS` | 스케일 이벤트 간 최소 대기 시간(초) |

### 4) Go 게이트웨이 실행
```bash
cd go-server
go run cmd/main.go
```
- 시작 시 실행 중인 인스턴스가 `MIN_INSTANCES`보다 적으면 자동으로 `docker compose up --scale`을 호출해 최소 인스턴스까지 올립니다.

### 5) 동작 확인
```bash
curl http://localhost:8080/actuator/health
```
- 게이트웨이(8080)를 거쳐 라운드 로빈으로 백엔드 인스턴스 중 하나가 응답합니다.

### 6) 오토스케일링 확인
- **Scale out**: 게이트웨이에 부하를 걸어 평균 활성 커넥션이 `SCALE_OUT_THRESHOLD`를 넘기면 인스턴스가 늘어납니다. (`ab`, `hey` 등으로 동시 요청을 보내거나, 테스트 목적으로 `app.env`의 임계값을 낮춰서 확인 가능)
- **Scale in**: 트래픽 없이 대기하면 평균 활성 커넥션이 0이 되어 `SCALE_IN_THRESHOLD` 아래로 떨어지고, 쿨다운(`SCALE_COOLDOWN_SECONDS`) 경과 후 자동으로 인스턴스가 `MIN_INSTANCES`까지 줄어듭니다.
- 스케일 이벤트는 Go 게이트웨이 로그에 `scaled app : N -> M instance (avgLoad=...)` 형태로 출력됩니다.
