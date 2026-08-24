#!/usr/bin/env bash
# Phase 3 verification. Holds, conflicts, idempotency, expiry, release.
# Requires a running app (local profile) with the bootstrap admin present.
BASE="${SEATFLOW_BASE_URL:-http://127.0.0.1:8080}"
ADMIN_EMAIL="${SEATFLOW_ADMIN_EMAIL:-admin@seatflow.local}"
ADMIN_PASSWORD="${SEATFLOW_ADMIN_PASSWORD:-local-admin-password-change-me}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

hr() { printf '\n---- %s ----\n' "$1"; }
check() {
  if [ "$2" = "$3" ]; then printf '  PASS  %-50s (%s)\n' "$1" "$3"; PASS=$((PASS+1))
  else printf '  FAIL  %-50s expected %s got %s\n' "$1" "$2" "$3"; FAIL=$((FAIL+1)); fi
}
same() {
  if [ "$2" = "$3" ] && [ -n "$2" ]; then printf '  PASS  %-50s (%s)\n' "$1" "$2"; PASS=$((PASS+1))
  else printf '  FAIL  %-50s "%s" vs "%s"\n' "$1" "$2" "$3"; FAIL=$((FAIL+1)); fi
}
jget() { cat "$1" | python -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception: print(''); sys.exit(0)
cur=d
for p in sys.argv[1].split('.'):
    if cur is None: break
    if isinstance(cur,list):
        try: cur=cur[int(p)]
        except Exception: cur=None
    elif isinstance(cur,dict): cur=cur.get(p)
    else: cur=None
print('' if cur is None else cur)
" "$2"; }

register() { # <label> -> prints access token
  local email="resv-$1-$(date +%s%N)@example.com"
  curl -s -o "$TMP/u-$1" -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"correct-horse-battery\",\"fullName\":\"Holder $1\"}"
  jget "$TMP/u-$1" accessToken
}

hr "0. set up a fresh event"
curl -s -o "$TMP/a" -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
ADMIN=$(jget "$TMP/a" accessToken)
[ -n "$ADMIN" ] || { echo "admin login failed"; exit 1; }

VENUE_BODY=$(python - "Hold Test Hall $(date +%s)" <<'PYEOF'
import json, sys
print(json.dumps({
  "name": sys.argv[1], "address": "1 Test St", "city": "Bengaluru", "country": "India",
  "timezone": "Asia/Kolkata",
  "sections": [{"name": "Stalls", "displayOrder": 1,
                "rows": [{"rowLabel": "A", "seatCount": 6}]}]}))
PYEOF
)
curl -s -o "$TMP/v" -X POST "$BASE/api/v1/admin/venues" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "$VENUE_BODY"
VENUE_ID=$(jget "$TMP/v" id)

EVENT_BODY=$(python - "$VENUE_ID" <<'PYEOF'
import datetime, json, sys
s = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=12)
print(json.dumps({
  "venueId": sys.argv[1], "name": "Hold Test " + s.strftime('%H%M%S'), "category": "music",
  "startsAt": s.strftime('%Y-%m-%dT%H:%M:%SZ'),
  "endsAt": (s + datetime.timedelta(hours=2)).strftime('%Y-%m-%dT%H:%M:%SZ'),
  "defaultPriceCents": 180000}))
PYEOF
)
curl -s -o "$TMP/e" -X POST "$BASE/api/v1/admin/events" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "$EVENT_BODY"
EVENT_ID=$(jget "$TMP/e" id)
curl -s -o /dev/null -X POST "$BASE/api/v1/admin/events/$EVENT_ID/publish" -H "Authorization: Bearer $ADMIN"

curl -s -o "$TMP/map" "$BASE/api/v1/events/$EVENT_ID/seats"
SEAT1=$(jget "$TMP/map" sections.0.seats.0.id)
SEAT2=$(jget "$TMP/map" sections.0.seats.1.id)
SEAT3=$(jget "$TMP/map" sections.0.seats.2.id)
echo "  event $EVENT_ID with $(jget "$TMP/map" availability.total) seats"

ALICE=$(register alice)
BOB=$(register bob)

hr "1. authentication is required to hold a seat"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/reservations" \
  -H 'Content-Type: application/json' -d "{\"eventId\":\"$EVENT_ID\",\"seatIds\":[\"$SEAT1\"]}")
check "POST /reservations (no token)" 401 "$CODE"

hr "2. alice holds two seats"
CODE=$(curl -s -o "$TMP/r1" -w '%{http_code}' -X POST "$BASE/api/v1/reservations" \
  -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d "{\"eventId\":\"$EVENT_ID\",\"seatIds\":[\"$SEAT1\",\"$SEAT2\"]}")
check "POST /reservations" 201 "$CODE"
R1=$(jget "$TMP/r1" id)
check "status" ACTIVE "$(jget "$TMP/r1" status)"
check "total is the sum of the two seats" 360000 "$(jget "$TMP/r1" totalCents)"
echo "  expires at $(jget "$TMP/r1" expiresAt), $(jget "$TMP/r1" secondsRemaining)s remaining"

hr "3. the seat map now shows them held"
curl -s -o "$TMP/map2" "$BASE/api/v1/events/$EVENT_ID/seats"
check "reserved count on the map" 2 "$(jget "$TMP/map2" availability.reserved)"
check "available count on the map" 4 "$(jget "$TMP/map2" availability.available)"

hr "4. bob cannot take a seat alice holds"
CODE=$(curl -s -o "$TMP/conflict" -w '%{http_code}' -X POST "$BASE/api/v1/reservations" \
  -H "Authorization: Bearer $BOB" -H 'Content-Type: application/json' \
  -d "{\"eventId\":\"$EVENT_ID\",\"seatIds\":[\"$SEAT2\",\"$SEAT3\"]}")
check "POST /reservations (overlapping seat)" 409 "$CODE"
check "problem type" "https://seatflow.dev/problems/seat-unavailable" "$(jget "$TMP/conflict" type)"
UNAVAIL=$(cat "$TMP/conflict" | python -c "import sys,json;print(len(json.load(sys.stdin).get('unavailableSeatIds',[])))" 2>/dev/null)
check "conflict names exactly one lost seat" 1 "$UNAVAIL"
same "and it is the seat alice holds" "$SEAT2" "$(jget "$TMP/conflict" unavailableSeatIds.0)"
echo "  detail: $(jget "$TMP/conflict" detail)"

hr "5. all-or-nothing: bob's other seat was NOT taken"
curl -s -o "$TMP/map3" "$BASE/api/v1/events/$EVENT_ID/seats"
check "still only 2 seats held in total" 2 "$(jget "$TMP/map3" availability.reserved)"

hr "6. idempotency: the same key returns the same reservation"
KEY="idem-$(date +%s%N)"
curl -s -o "$TMP/i1" -X POST "$BASE/api/v1/reservations" \
  -H "Authorization: Bearer $BOB" -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' -d "{\"eventId\":\"$EVENT_ID\",\"seatIds\":[\"$SEAT3\"]}"
curl -s -o "$TMP/i2" -X POST "$BASE/api/v1/reservations" \
  -H "Authorization: Bearer $BOB" -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' -d "{\"eventId\":\"$EVENT_ID\",\"seatIds\":[\"$SEAT3\"]}"
same "same reservation id on replay" "$(jget "$TMP/i1" id)" "$(jget "$TMP/i2" id)"
curl -s -o "$TMP/map4" "$BASE/api/v1/events/$EVENT_ID/seats"
check "replay did not take a second seat" 3 "$(jget "$TMP/map4" availability.reserved)"

hr "7. a reservation is private to its owner"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/reservations/$R1" -H "Authorization: Bearer $BOB")
check "GET someone else's reservation" 404 "$CODE"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/reservations/$R1" -H "Authorization: Bearer $ALICE")
check "GET own reservation" 200 "$CODE"

hr "8. validation"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/reservations" \
  -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d "{\"eventId\":\"$EVENT_ID\",\"seatIds\":[]}")
check "POST with no seats" 400 "$CODE"

hr "9. releasing returns the seats to the pool"
CODE=$(curl -s -o "$TMP/cancel" -w '%{http_code}' -X DELETE "$BASE/api/v1/reservations/$R1" \
  -H "Authorization: Bearer $ALICE")
check "DELETE /reservations/{id}" 200 "$CODE"
check "status after release" CANCELLED "$(jget "$TMP/cancel" status)"
curl -s -o "$TMP/map5" "$BASE/api/v1/events/$EVENT_ID/seats"
check "alice's 2 seats are free again" 5 "$(jget "$TMP/map5" availability.available)"
check "only bob's seat remains held" 1 "$(jget "$TMP/map5" availability.reserved)"

hr "10. bob can now take a seat alice released"
CODE=$(curl -s -o "$TMP/r2" -w '%{http_code}' -X POST "$BASE/api/v1/reservations" \
  -H "Authorization: Bearer $BOB" -H 'Content-Type: application/json' \
  -d "{\"eventId\":\"$EVENT_ID\",\"seatIds\":[\"$SEAT1\"]}")
check "POST /reservations (previously held seat)" 201 "$CODE"

printf '\n================================\n'
printf '  PASSED: %d\n  FAILED: %d\n' "$PASS" "$FAIL"
printf '================================\n'
[ "$FAIL" -eq 0 ]
