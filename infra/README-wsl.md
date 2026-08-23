# Running the infrastructure

Docker Desktop is disabled on this machine on purpose. The Windows `docker` CLI
still points at the dead `desktop-linux` named pipe, so **every docker command
must run inside WSL**. A failing `docker ps` from PowerShell is expected, not a
problem to debug.

## Start the stack

```bash
wsl -e bash -lc "cd /mnt/c/Users/kj638/'Kevin codes'/seatflow/infra && docker compose -f docker-compose.dev.yml up -d"
```

## Stop it

```bash
wsl -e bash -lc "cd /mnt/c/Users/kj638/'Kevin codes'/seatflow/infra && docker compose -f docker-compose.dev.yml down"
```

## Wipe the data volumes

```bash
wsl -e bash -lc "cd /mnt/c/Users/kj638/'Kevin codes'/seatflow/infra && docker compose -f docker-compose.dev.yml down -v"
```

## How the app reaches the containers

The Spring Boot app runs on **Windows**; the containers run in **WSL**. WSL2
forwards published ports to Windows `localhost`, so the app connects to
`localhost:5432` and `localhost:6379` normally.

If that ever stops working, get the WSL IP and point the app at it instead:

```bash
wsl hostname -I
```

Verification steps are in `docs/VERIFY.md`, part 1. Run them before debugging
anything that looks like a connection failure.
