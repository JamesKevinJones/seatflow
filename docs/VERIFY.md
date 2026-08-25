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

**Use `verify`, not `test`.** Integration tests are named `*IT` and run under
Failsafe in the `integration-test` phase. Surefire (`mvn test`) only matches
`*Test` / `*Tests`, so `mvn test` silently skips every `*IT` class and still
reports BUILD SUCCESS. A green `mvn test` proves nothing about the concurrency
guarantees, because those tests are all `*IT`.

```bash
wsl -e bash -lc "cd '/mnt/c/Users/kj638/Kevin codes/seatflow/backend' && ./mvnw verify"
```

WSL keeps a separate `~/.m2`, so the first run re-downloads dependencies.

Current expected output: **3 tests under surefire, 15 under failsafe, 0 failures.**
If failsafe reports 0 tests run, the plugin configuration has been lost - treat
that as a build failure, not a pass.

A single integration test:

```bash
wsl -e bash -lc "cd '/mnt/c/Users/kj638/Kevin codes/seatflow/backend' && ./mvnw verify -Dit.test=EventSeatGenerationIT"
```

The one that matters most:

```bash
wsl -e bash -lc "cd '/mnt/c/Users/kj638/Kevin codes/seatflow/backend' && ./mvnw verify -Dit.test=ConcurrentReservationIT"
```

Four tests, all against real PostgreSQL under READ COMMITTED:

1. 200 threads reach for one seat - exactly 1 succeeds, 199 fail with
   `SeatsUnavailableException`, and the database holds exactly one RESERVED row.
2. 200 threads across 10 seats - exactly 10 held, and no seat appears in two
   live reservations.
3. Two overlapping multi-seat requests - exactly one wins, and both its seats
   belong to the same reservation. A partial hold would show two holders.
4. A lapsed hold is reclaimable **with the sweeper disabled**, proving expiry
   correctness comes from the query predicate rather than the scheduled job.

Any other count is a correctness failure, not a flaky test. If this test ever
needs the sweeper enabled to pass, the separation described in
`docs/CONCURRENCY.md` has been broken.

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

## 5. End-to-end API suites

Both run against a running app and print a pass/fail table. Both are
re-runnable: they generate unique emails and venue names per run, because
`uq_users_email_lower` and `uq_venues_name_city` would otherwise reject a second
pass.

```bash
bash scripts/verify-auth.sh
```

Covers: health, register, duplicate-email conflict, bean validation, login,
wrong password, unknown account (must be indistinguishable from wrong password),
bearer-token access, missing-token rejection, refresh rotation, refresh replay
detection, family revocation after replay, and role-gated actuator access.

All 15 checks must pass. Last full run: 15 passed, 0 failed.

```bash
bash scripts/verify-catalog.sh
```

Covers: admin login, venue creation with generated layout, admin-only
enforcement (403 for USER, 401 unauthenticated), layout validation, event
creation with EventSeat generation, slug collision handling, draft events hidden
from the public (404, not 403), publish, the public catalogue and seat map,
per-section price overrides, and cancel.

All 29 checks must pass. Last full run: 29 passed, 0 failed.

```bash
bash scripts/verify-reservations.sh
```

Covers: authentication required to hold, a successful multi-seat hold with a
correct total and expiry, the seat map reflecting the hold, a conflicting
request rejected with 409 naming exactly the lost seat, all-or-nothing (the
loser's other seat stays free), idempotent replay returning the same
reservation without taking more seats, ownership privacy (404, not 403),
validation, release returning seats to the pool, and a released seat being
takeable by someone else.

All 21 checks must pass. Last full run: 21 passed, 0 failed.

```bash
bash scripts/verify-checkout.sh
```

Covers: payment requiring authentication, a successful charge producing a
booking with the seats marked sold, idempotent replay returning the same
booking rather than charging twice, a declined card leaving the hold intact and
retryable, booking history, ownership privacy, and a released hold refusing
payment.

All 22 checks must pass. Last full run: 22 passed, 0 failed.

---

## 6. Frontend

Needs the backend running (part 4) and some published data. `scripts/seed-demo.sh`
creates one realistic event with a 188 seat venue and a spread of held and sold
seats:

```bash
bash scripts/seed-demo.sh
```

Typecheck and production build - the dev server does not catch unused locals,
`tsc -b` does:

```bash
cd frontend && npm run build
```

Run it:

```bash
cd frontend && npm run dev
```

Then check http://localhost:5173 shows the event list with real counts, and
`/events/{id}/seats` renders the map with four visually distinct seat states.

**Launching from Claude Code**: `.claude/launch.json` at the session root starts
it as `seatflow-web`. Two constraints, both learned the hard way:

- The path in `runtimeArgs` must be the **8.3 short form**
  (`C:/Users/kj638/KEVINC~1/...`). The launcher cannot pass a path containing a
  space, and fails with `'C:\Program' is not recognized`.
- `npm --prefix <path> run dev`, in that order. `npm run dev --prefix <path>`
  passes `--prefix` to Vite instead of npm.
- The short path is why `server.fs.strict` is off - see DECISIONS.

**Measuring layout in the preview browser**: when the Browser pane is not
displayed the page does not composite, so CSS transitions freeze part-way and
`getBoundingClientRect` returns mid-transition values. Inject
`*{transition:none !important}` before measuring, or you will chase a layout bug
that does not exist.

---

## 7. Load test

**Run this inside WSL, with the backend inside WSL too.** Driving load from WSL
at a Windows-hosted process crosses the Hyper-V bridge, and Windows Firewall
starts dropping connections - which measures the network path, not the
application.

```bash
wsl -e bash -lc "cd '/mnt/c/Users/kj638/Kevin codes/seatflow/backend' && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local"
```

Then, in WSL:

```bash
k6 run load/reserve-contention.js
```

k6 lives at `~/bin/k6` in WSL. Smaller runs for a quick check:

```bash
PEAK_VUS=40 HOLD_DURATION=10s SEATS=20 BUYERS=10 k6 run load/reserve-contention.js
```

The test fails itself if the ledger is ever oversold. Results in
`load/RESULTS.md`. Never write a performance figure that was not measured.

---

## 8. Live seat updates

With the app and frontend running, open a seat map and watch the indicator in the
header read **Live**. Then take seats as somebody else and confirm the map moves
without a reload:

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/reservations -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"eventId":"...","seatIds":["..."]}'
```

If the indicator reads **Not live**, check that `/ws` is permitted in
`SecurityConfig` - a 401 on the handshake is the usual cause - and that
`vite.config.ts` proxies `/ws` with `ws: true`.

---

## 9. Redis may be killed

The point of the cache design is that nothing depends on it. Prove it:

```bash
wsl -e bash -lc "docker stop seatflow-redis"
curl -s -o /dev/null -w '%{http_code}
' http://127.0.0.1:8080/api/v1/events/<id>/seats
wsl -e bash -lc "docker start seatflow-redis"
```

The seat map must still return 200 and reservations must still return 201, just
more slowly. Anything else means something has started depending on the cache.

---

## Known-failing

- `./mvnw test` **from Windows** fails at Docker discovery. By design - run
  integration tests in WSL, per part 3.

---

## 10. The whole stack in containers

This is what someone cloning the repository runs. Docker lives in WSL on this
machine, so run it from there.

```bash
cp .env.example .env      # set SEATFLOW_JWT_SECRET and SEATFLOW_ADMIN_PASSWORD
wsl -e bash -lc "cd '/mnt/c/Users/kj638/Kevin codes/seatflow' && docker compose up -d --build"
```

All four services must report **healthy**:

```bash
wsl -e bash -lc "cd '/mnt/c/Users/kj638/Kevin codes/seatflow' && docker compose ps"
```

Then check the app answers on the only published port, through nginx:

```bash
curl -s -o /dev/null -w '%{http_code}
' http://localhost:8088/
curl -s -o /dev/null -w '%{http_code}
' http://localhost:8088/api/v1/events
curl -s -o /dev/null -w '%{http_code}
' http://localhost:8088/docs
```

The API suites run against it unchanged:

```bash
SEATFLOW_BASE_URL=http://localhost:8088 SEATFLOW_ADMIN_PASSWORD=<yours>   bash scripts/verify-checkout.sh
```

Last full run against containers: checkout 22 passed, reservations 21 passed.

**Beware a misleading 200.** nginx serves `index.html` for unknown paths, so
`curl http://localhost:8088/actuator/prometheus` returns 200 with an HTML body.
That is the SPA fallback, not actuator. Check the body, not just the status.

---

## 11. Metrics

Actuator is not proxied through nginx, so metrics are reached inside the
network. Everything except the health probes requires the ADMIN role.

```bash
wsl -e bash -lc "cd '/mnt/c/Users/kj638/Kevin codes/seatflow' && docker compose exec -T backend sh -c 'curl -s -o /dev/null -w \"%{http_code}
\" http://127.0.0.1:8080/actuator/prometheus'"
```

Expect **401** without a token and **200** with an admin one. Five application
metrics must be present, and must move after traffic:

```
seatflow_reservation_requests_total
seatflow_reservation_conflicts_total
seatflow_booking_success_total
seatflow_booking_failures_total
seatflow_reservations_active
```

Last run, after the checkout and reservation suites: 8 requests, 1 conflict,
2 bookings, 1 failure, 2 active holds - which matches exactly what those suites
do. A counter registered but never incremented is worse than no counter.
