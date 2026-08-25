#!/usr/bin/env bash
# Phase 1 verification. Exercises every auth path against the running app.
BASE="${SEATFLOW_BASE_URL:-http://127.0.0.1:8080}"
# Python does the JSON parsing. The interpreter is named "python3" on Linux and
# often only "python" on Windows, so resolve it once rather than assuming: these
# suites have to run both from Git Bash on the host and from inside WSL against
# the containerised stack.
PY_BIN="$(command -v python3 || command -v python)"
if [ -z "$PY_BIN" ]; then echo "python3 (or python) is required" >&2; exit 1; fi

TMP="$(mktemp -d)"
trap "rm -rf $TMP" EXIT
PASS=0; FAIL=0
EMAIL="kevin+$(date +%s)@example.com"

hr() { printf '\n---- %s ----\n' "$1"; }

# check <label> <expected-status> <actual-status>
check() {
  if [ "$2" = "$3" ]; then
    printf '  PASS  %-46s (%s)\n' "$1" "$3"; PASS=$((PASS+1))
  else
    printf '  FAIL  %-46s expected %s got %s\n' "$1" "$2" "$3"; FAIL=$((FAIL+1))
  fi
}

skip() { printf '  SKIP  %-46s %s\n' "$1" "$2"; }

# Is /actuator reachable on this origin at all?
#
# It is not proxied through nginx, and nginx answers an unmatched path with the
# SPA - 200, with index.html in the body. Against the containerised stack a
# status code alone is therefore worthless here: the "requires ADMIN" check
# looks like a failure and the health check looks like a pass, both for the
# same wrong reason. Ask whether the body is actually actuator JSON.
actuator_reachable() {
  curl -s "$BASE/actuator/health" | head -c 1 | grep -q '{'
}

jqf() { "$PY_BIN" -c "
import sys,json
try:
    d=json.load(sys.stdin)
except Exception:
    sys.exit(0)
for k in sys.argv[1:]:
    cur=d
    for part in k.split('.'):
        if isinstance(cur,list):
            cur=cur[int(part)]
        elif isinstance(cur,dict):
            cur=cur.get(part)
        else:
            cur=None
        if cur is None: break
    print('%s = %s' % (k, cur))
" "$@"; }

hr "1. health"
if actuator_reachable; then
  BODY=$(curl -s -o $TMP/b -w '%{http_code}' $BASE/actuator/health); check "GET /actuator/health" 200 "$BODY"
  cat $TMP/b | jqf status components.db.status components.redis.status
else
  skip "GET /actuator/health" "actuator is not exposed on this origin"
fi

hr "2. register"
REG=$(curl -s -o $TMP/reg -w '%{http_code}' -X POST $BASE/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"correct-horse-battery\",\"fullName\":\"Kevin Jones\"}")
check "POST /register (new account)" 201 "$REG"
cat $TMP/reg | jqf user.email user.roles.0 tokenType expiresIn
ACCESS=$(cat $TMP/reg | "$PY_BIN" -c "import sys,json;print(json.load(sys.stdin)['accessToken'])" 2>/dev/null)
REFRESH=$(cat $TMP/reg | "$PY_BIN" -c "import sys,json;print(json.load(sys.stdin)['refreshToken'])" 2>/dev/null)

hr "3. duplicate register -> 409"
DUP=$(curl -s -o $TMP/dup -w '%{http_code}' -X POST $BASE/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"correct-horse-battery\",\"fullName\":\"Impostor\"}")
check "POST /register (duplicate email)" 409 "$DUP"
cat $TMP/dup | jqf type title status email

hr "4. validation -> 400 with field list"
VAL=$(curl -s -o $TMP/val -w '%{http_code}' -X POST $BASE/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"not-an-email","password":"short","fullName":""}')
check "POST /register (invalid body)" 400 "$VAL"
cat $TMP/val | jqf title status errors.0.field errors.0.message
echo "  field count: $(cat $TMP/val | "$PY_BIN" -c "import sys,json;print(len(json.load(sys.stdin)['errors']))" 2>/dev/null)"

hr "5. login"
LOG=$(curl -s -o $TMP/log -w '%{http_code}' -X POST $BASE/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"correct-horse-battery\"}")
check "POST /login (correct password)" 200 "$LOG"

hr "6. login with wrong password -> 401"
BAD=$(curl -s -o $TMP/bad -w '%{http_code}' -X POST $BASE/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"wrong-password-here\"}")
check "POST /login (wrong password)" 401 "$BAD"
cat $TMP/bad | jqf type detail

hr "6b. login for unknown account -> 401, identical detail"
UNK=$(curl -s -o $TMP/unk -w '%{http_code}' -X POST $BASE/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"nobody-here@example.com","password":"wrong-password-here"}')
check "POST /login (unknown email)" 401 "$UNK"
D1=$(cat $TMP/bad | "$PY_BIN" -c "import sys,json;print(json.load(sys.stdin)['detail'])" 2>/dev/null)
D2=$(cat $TMP/unk | "$PY_BIN" -c "import sys,json;print(json.load(sys.stdin)['detail'])" 2>/dev/null)
if [ "$D1" = "$D2" ]; then
  printf '  PASS  %-46s (no account enumeration)\n' "wrong-password and unknown-email identical"; PASS=$((PASS+1))
else
  printf '  FAIL  %-46s "%s" vs "%s"\n' "details differ" "$D1" "$D2"; FAIL=$((FAIL+1))
fi

hr "7. /me with token"
ME=$(curl -s -o $TMP/me -w '%{http_code}' $BASE/api/v1/auth/me -H "Authorization: Bearer $ACCESS")
check "GET /me (valid bearer token)" 200 "$ME"
cat $TMP/me | jqf email fullName roles.0

hr "8. /me without token -> 401 problem+json"
NOAUTH=$(curl -s -o $TMP/noauth -w '%{http_code}' $BASE/api/v1/auth/me)
check "GET /me (no token)" 401 "$NOAUTH"
CT=$(curl -s -o /dev/null -w '%{content_type}' $BASE/api/v1/auth/me)
echo "  content-type: $CT"
cat $TMP/noauth | jqf type title

hr "9. refresh rotates the token"
REF=$(curl -s -o $TMP/ref -w '%{http_code}' -X POST $BASE/api/v1/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}")
check "POST /refresh (valid token)" 200 "$REF"
NEWREFRESH=$(cat $TMP/ref | "$PY_BIN" -c "import sys,json;print(json.load(sys.stdin)['refreshToken'])" 2>/dev/null)
if [ -n "$NEWREFRESH" ] && [ "$NEWREFRESH" != "$REFRESH" ]; then
  printf '  PASS  %-46s (rotated)\n' "refresh token changed"; PASS=$((PASS+1))
else
  printf '  FAIL  %-46s\n' "refresh token did not rotate"; FAIL=$((FAIL+1))
fi

hr "10. replaying the OLD refresh token -> 401 + revoke all"
REUSE=$(curl -s -o $TMP/reuse -w '%{http_code}' -X POST $BASE/api/v1/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}")
check "POST /refresh (replayed old token)" 401 "$REUSE"
cat $TMP/reuse | jqf detail

hr "10b. successor token must now be dead too"
AFTER=$(curl -s -o $TMP/after -w '%{http_code}' -X POST $BASE/api/v1/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$NEWREFRESH\"}")
check "POST /refresh (successor after reuse)" 401 "$AFTER"
cat $TMP/after | jqf detail

hr "11. actuator beyond health requires ADMIN"
if actuator_reachable; then
  ACT=$(curl -s -o $TMP/act -w '%{http_code}' $BASE/actuator/metrics -H "Authorization: Bearer $ACCESS")
  check "GET /actuator/metrics (USER role)" 403 "$ACT"
  cat $TMP/act | jqf type title
else
  # Reaching actuator means reaching the backend directly. Against the
  # containerised stack, check it from inside the network instead -
  # docs/VERIFY.md part 11 has the command.
  skip "GET /actuator/metrics (USER role)" "actuator is not exposed on this origin"
fi

printf '\n================================\n'
printf '  PASSED: %d\n  FAILED: %d\n' "$PASS" "$FAIL"
printf '================================\n'
[ "$FAIL" -eq 0 ]
