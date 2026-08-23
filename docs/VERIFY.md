# Verification

Exact commands to prove a change works. Any agent, any tool, no guessing.

Rule: **do not report work as done without running these.** "It should work" is
not a result.

---

## 0. Toolchain (run once per machine)

`JAVA_HOME` on this machine points at the JetBrains Runtime that ships with
IntelliJ, and `java` is not on PATH in Git Bash. The Maven Wrapper reads
`JAVA_HOME`, so this must be fixed or every build uses the wrong JDK.

Permanent fix (PowerShell, then reopen the terminal):

```powershell
[Environment]::SetEnvironmentVariable('JAVA_HOME','C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot','User')
```

Per-session fix (Git Bash):

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
```

Prove it:

```bash
java -version   # expect: openjdk version "21.0.12.1" ... Temurin
```

---

## 1. Infrastructure reachability (the load-bearing assumption)

Containers run in WSL; the app runs on Windows. Everything depends on WSL2
forwarding `localhost` between them. Verify this **before** debugging anything
else that looks like a connection problem.

Start the stack from WSL:

```bash
wsl -e bash -lc "cd /mnt/c/Users/kj638/'Kevin codes'/seatflow/infra && docker compose -f docker-compose.dev.yml up -d"
```

Confirm from WSL that the containers are healthy:

```bash
wsl -e bash -lc "docker compose -f /mnt/c/Users/kj638/'Kevin codes'/seatflow/infra/docker-compose.dev.yml ps"
```

Now confirm from **Windows** that the forwarded ports answer:

```bash
powershell -Command "Test-NetConnection -ComputerName localhost -Port 5432 | Select-Object -ExpandProperty TcpTestSucceeded"
powershell -Command "Test-NetConnection -ComputerName localhost -Port 6379 | Select-Object -ExpandProperty TcpTestSucceeded"
```

Both must print `True`. If either prints `False`, do not proceed - see the
fallback in the DECISIONS entry on WSL containers.

---

## 2. Backend build and unit tests

```bash
cd backend && ./mvnw clean verify
```

---

## 3. Integration and concurrency tests

These use Testcontainers and start a real PostgreSQL. Docker must be reachable
from wherever the tests run.

```bash
cd backend && ./mvnw verify -Dtest='*IT'
```

The one that matters:

```bash
cd backend && ./mvnw test -Dtest=ConcurrentReservationIT
```

Expected: 200 threads contend for one seat, exactly 1 succeeds, 199 fail with
`SeatsUnavailableException`, and the database holds exactly one RESERVED row.
A pass with any other count is a correctness failure, not a flaky test.

---

## 4. Run the backend

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Prove it:

```bash
curl -s http://localhost:8080/actuator/health
```

Expect `{"status":"UP"}` with the `db` and `redis` components also UP.

---

## 5. Run the frontend (from Phase 4)

```bash
cd frontend && npm run dev
```

Then check http://localhost:5173 renders the event list against real backend data
with no console errors.

Note: this project path contains a space (`Kevin codes`), which breaks
`preview_start`. Use the 8.3 short path in `.claude/launch.json`.

---

## 6. Load test (from Phase 9)

```bash
k6 run load/reserve-contention.js
```

Record real numbers in `load/RESULTS.md`. Never write a performance figure that
was not measured.

---

## Known-failing

Nothing yet. Phase 0 produced documentation only - no build exists to fail.
