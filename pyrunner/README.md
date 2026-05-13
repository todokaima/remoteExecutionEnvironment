# PyRunner

A Spring Boot web app that executes Python scripts in isolated Docker containers.

## Architecture

```
Browser  ──POST /run──▶  Spring Boot  ──docker run──▶  python:3.11-slim
                         (port 8080)                   (ephemeral container)
```

Each execution:
1. Writes the submitted code to a temp file
2. Launches `docker run --rm python:3.11-slim python script.py` with the file bind-mounted
3. Captures stdout + stderr with a configurable timeout
4. Returns JSON `{ stdout, stderr, exitCode, durationMs, timedOut }`

Security constraints applied to every container:
- `--network none` — no internet access
- `--memory 64m` — memory cap
- `--cpu-quota 50000` — ~50% of one CPU
- `--read-only` + `--tmpfs /tmp` — read-only filesystem
- `--security-opt no-new-privileges` — privilege escalation blocked
- `--rm` — auto-removed after exit

---

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker (daemon running)
- Pull the Python image once: `docker pull python:3.11-slim`

---

## Running locally

```bash
# 1. Pull the sandbox image (once)
docker pull python:3.11-slim

# 2. Start the Spring Boot app
./mvnw spring-boot:run

# 3. Open http://localhost:8080
```

### Or with Docker Compose

```bash
docker compose up --build
```

> **Note**: The app container needs access to the host Docker socket so it can
> launch sibling containers. The compose file already mounts
> `/var/run/docker.sock:/var/run/docker.sock`.

---

## Configuration

Edit `src/main/resources/application.properties` or pass env vars:

| Property / Env var              | Default          | Description                     |
|---------------------------------|------------------|---------------------------------|
| `runner.docker.image`           | `python:3.11-slim` | Docker image for sandboxing   |
| `runner.timeout-seconds`        | `10`             | Kill container after N seconds  |
| `runner.memory-limit`           | `64m`            | Docker `--memory` value         |
| `runner.cpu-quota`              | `50000`          | Docker `--cpu-quota` value      |

---

## Project structure

```
pyrunner/
├── src/main/java/com/pyrunner/
│   ├── PyRunnerApplication.java          # Entry point
│   ├── controller/RunnerController.java  # GET / and POST /run
│   ├── model/
│   │   ├── CodeRequest.java              # Request body (validated)
│   │   └── ExecutionResult.java          # Result record
│   └── service/DockerExecutionService.java  # Core sandbox logic
├── src/main/resources/
│   ├── application.properties
│   └── templates/index.html              # Thymeleaf editor UI
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

---

## Keyboard shortcuts

| Shortcut          | Action     |
|-------------------|------------|
| `Ctrl+Enter`      | Run script |
| `Tab` in editor   | Insert 4-space indent |
