# Verification

Exact commands to prove a change works. Any agent, any tool, no guessing.

Rule: **do not report work as done without running these.** "It should work" is
not a result.

---

## 0. Toolchain (once per machine, already done)

Temurin 21 is installed and `JAVA_HOME` points at it:

```bash
java -version    # expect: openjdk 21.0.12.1 ... Temurin
```

If a shell was opened *before* `JAVA_HOME` was set, it still holds the old
JetBrains Runtime value. Fix that shell without restarting it:

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"
```

Maven is deliberately not installed. `./mvnw` downloads and runs Maven 3.9.16.

---

## 1. Infrastructure (the step that causes the most wasted time)

Containers run in WSL; the app runs on Windows. Two facts about this machine
matter more than anything else in this file:

1. **WSL terminates seconds after the last command exits**, taking PostgreSQL and
   Redis with it. Without a keep-alive, the database disappears while Maven is
   still compiling.
2. **The WSL port relay binds IPv4 only.** `localhost` resolves to `::1` first
   here, so configs name `127.0.0.1` explicitly.

Start the keep-alive first, and leave it running for the whole session:

```bash
wsl -e bash -lc "sleep infinity" &
```

Then bring the stack up and wait for PostgreSQL to actually accept connections:

```bash
wsl -e bash -lc "cd '/mnt/c/Users/kj638/Kevin codes/seatflow/infra' && docker compose -f docker-compose.dev.yml up -d && until docker exec seatflow-postgres pg_isready -U seatflow -d seatflow >/dev/null 2>&1; do sleep 1; done && echo READY"
```

Prove the ports answer **from Windows**, which is where the app runs:

```bash
powershell -Command "function T($p){$c=New-Object System.Net.Sockets.TcpClient;try{if($c.ConnectAsync('127.0.0.1',$p).Wait(3000)-and $c.Connected){'OPEN'}else{'closed'}}catch{'closed'}finally{$c.Close()}}; foreach($p in 5432,6379){\"$p -> $(T $p)\"}"
```

Both must print `OPEN`. If either is `closed`, check `wsl -l -v` first - a
`Stopped` distro means the keep-alive died, and that is the cause far more often
than any configuration problem. If WSL is Running but ports are closed, the
network path is wedged: `wsl --shutdown`, then repeat from the keep-alive.

---

## 2. Build and unit tests (Windows)

```bash
cd backend && ./mvnw clean verify -DskipITs
```

---

## 3. Integration tests (must run inside WSL)

Testcontainers needs a Docker daemon. Docker Desktop is disabled on this machine,
so **the Windows JVM cannot reach one** - `./mvnw test` from Windows fails with
"Could not find a valid Docker environment". This is expected, not a regression.

Run the suite inside WSL, which has its own Temurin 21 and a working daemon:

```bash
wsl -e bash -lc "cd '/mnt/c/Users/kj638/Kevin codes/seatflow/backend' && ./mvnw test"
```

WSL keeps a separate `~/.m2`, so the first run re-downloads dependencies.

The one that will matter most, from Phase 3:

```bash
wsl -e bash -lc "cd '/mnt/c/Users/kj638/Kevin codes/seatflow/backend' && ./mvnw test -Dtest=ConcurrentReservationIT"
```

Expected: 200 threads contend for one seat, exactly 1 succeeds, 199 fail with
`SeatsUnavailableException`, and the database holds exactly one RESERVED row.
Any other count is a correctness failure, not a flaky test.

---

## 4. Run the backend

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Prove it:

```bash
curl -s http://127.0.0.1:8080/actuator/health
```

Expect `{"status":"UP"}` with `db` and `redis` components also UP.

---

## 5. Auth end-to-end

`scripts/verify-auth.sh` exercises every auth path against a running app and
prints a pass/fail table:

```bash
bash scripts/verify-auth.sh
```

Covers: health, register, duplicate-email conflict, bean validation, login,
wrong password, unknown account (must be indistinguishable from wrong password),
bearer-token access, missing-token rejection, refresh rotation, refresh replay
detection, family revocation after replay, and role-gated actuator access.

All 15 checks must pass. Last full run: 15 passed, 0 failed.

---

## 6. Frontend (from Phase 4)

```bash
cd frontend && npm run dev
```

Note: this project path contains a space (`Kevin codes`), which breaks
`preview_start`. Use the 8.3 short path in `.claude/launch.json`.

---

## 7. Load test (from Phase 9)

```bash
k6 run load/reserve-contention.js
```

Record real numbers in `load/RESULTS.md`. Never write a performance figure that
was not measured.

---

## Known-failing

- `./mvnw test` **from Windows** fails at Docker discovery. By design - run
  integration tests in WSL, per part 3.
