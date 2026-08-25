#!/usr/bin/env bash
# Phase 7 verification. Payment, booking, and the ledger that results.
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

register() {
  local email="pay-$1-$(date +%s%N)@example.com"
  curl -s -o "$TMP/u-$1" -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"correct-horse-battery\",\"fullName\":\"Buyer $1\"}"
  jget "$TMP/u-$1" accessToken
}

hr "0. a fresh event with 6 seats"
curl -s -o "$TMP/a" -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
ADMIN=$(jget "$TMP/a" accessToken)
[ -n "$ADMIN" ] || { echo "admin login failed"; exit 1; }

VENUE_BODY=$(python - "Checkout Hall $(date +%s%N)" <<'PYEOF'
import json, sys
print(json.dumps({"name": sys.argv[1], "address": "1 Test St", "city": "Bengaluru",
                  "country": "India", "timezone": "Asia/Kolkata",
                  "sections": [{"name": "Stalls", "displayOrder": 1,
                                "rows": [{"rowLabel": "A", "seatCount": 6}]}]}))
PYEOF
)
curl -s -o "$TMP/v" -X POST "$BASE/api/v1/admin/venues" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "$VENUE_BODY"
EVENT_BODY=$(python - "$(jget "$TMP/v" id)" <<'PYEOF'
import datetime, json, sys
s = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=11)
print(json.dumps({"venueId": sys.argv[1], "name": "Checkout Test " + s.strftime('%H%M%S%f'),
                  "category": "music", "startsAt": s.strftime('%Y-%m-%dT%H:%M:%SZ'),
                  "endsAt": (s + datetime.timedelta(hours=2)).strftime('%Y-%m-%dT%H:%M:%SZ'),
                  "defaultPriceCents": 180000}))
PYEOF
)
curl -s -o "$TMP/e" -X POST "$BASE/api/v1/admin/events" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "$EVENT_BODY"
EVENT_ID=$(jget "$TMP/e" id)
curl -s -o /dev/null -X POST "$BASE/api/v1/admin/events/$EVENT_ID/publish" -H "Authorization: Bearer $ADMIN"
curl -s -o "$TMP/map" "$BASE/api/v1/events/$EVENT_ID/seats"
S1=$(jget "$TMP/map" sections.0.seats.0.id)
S2=$(jget "$TMP/map" sections.0.seats.1.id)
S3=$(jget "$TMP/map" sections.0.seats.2.id)
echo "  event $EVENT_ID"

ALICE=$(register alice)
BOB=$(register bob)

hr "1. alice holds two seats and pays"
curl -s -o "$TMP/h1" -X POST "$BASE/api/v1/reservations" -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -d "{\"eventId\":\"$EVENT_ID\",\"seatIds\":[\"$S1\",\"$S2\"]}"
R1=$(jget "$TMP/h1" id)
CODE=$(curl -s -o "$TMP/b1" -w '%{http_code}' -X POST "$BASE/api/v1/payments" \
  -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d "{\"reservationId\":\"$R1\",\"paymentMethod\":\"card_visa_4242\"}")
check "POST /payments" 201 "$CODE"
B1=$(jget "$TMP/b1" id)
REF1=$(jget "$TMP/b1" bookingReference)
check "total charged" 360000 "$(jget "$TMP/b1" totalCents)"
check "seats on the booking" 2 "$(cat "$TMP/b1" | python -c "import sys,json;print(len(json.load(sys.stdin)['seats']))")"
echo "  reference $REF1, seat labels: $(jget "$TMP/b1" seats.0.label) $(jget "$TMP/b1" seats.1.label)"

hr "2. those seats are now sold, not held"
curl -s -o "$TMP/map2" "$BASE/api/v1/events/$EVENT_ID/seats"
check "booked on the map" 2 "$(jget "$TMP/map2" availability.booked)"
check "nothing left held" 0 "$(jget "$TMP/map2" availability.reserved)"
check "available" 4 "$(jget "$TMP/map2" availability.available)"

hr "3. paying again returns the same booking, it does not charge twice"
CODE=$(curl -s -o "$TMP/b1b" -w '%{http_code}' -X POST "$BASE/api/v1/payments" \
  -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d "{\"reservationId\":\"$R1\",\"paymentMethod\":\"card_visa_4242\"}")
check "POST /payments (replay)" 201 "$CODE"
same "same booking reference" "$REF1" "$(jget "$TMP/b1b" bookingReference)"
curl -s -o "$TMP/map3" "$BASE/api/v1/events/$EVENT_ID/seats"
check "still only 2 seats sold" 2 "$(jget "$TMP/map3" availability.booked)"

hr "4. a declined card leaves the hold intact"
curl -s -o "$TMP/h2" -X POST "$BASE/api/v1/reservations" -H "Authorization: Bearer $BOB" \
  -H 'Content-Type: application/json' -d "{\"eventId\":\"$EVENT_ID\",\"seatIds\":[\"$S3\"]}"
R2=$(jget "$TMP/h2" id)
CODE=$(curl -s -o "$TMP/decl" -w '%{http_code}' -X POST "$BASE/api/v1/payments" \
  -H "Authorization: Bearer $BOB" -H 'Content-Type: application/json' \
  -d "{\"reservationId\":\"$R2\",\"paymentMethod\":\"decline_test\"}")
check "POST /payments (declined card)" 402 "$CODE"
check "problem type" "https://seatflow.dev/problems/payment-declined" "$(jget "$TMP/decl" type)"
curl -s -o "$TMP/map4" "$BASE/api/v1/events/$EVENT_ID/seats"
check "the seat is still held, not sold" 1 "$(jget "$TMP/map4" availability.reserved)"
check "nothing extra was booked" 2 "$(jget "$TMP/map4" availability.booked)"

hr "5. and a retry with a good card works"
CODE=$(curl -s -o "$TMP/b2" -w '%{http_code}' -X POST "$BASE/api/v1/payments" \
  -H "Authorization: Bearer $BOB" -H 'Content-Type: application/json' \
  -d "{\"reservationId\":\"$R2\",\"paymentMethod\":\"card_visa_4242\"}")
check "POST /payments (retry after decline)" 201 "$CODE"
B2=$(jget "$TMP/b2" id)
curl -s -o "$TMP/map5" "$BASE/api/v1/events/$EVENT_ID/seats"
check "now 3 seats sold" 3 "$(jget "$TMP/map5" availability.booked)"

hr "6. booking history"
CODE=$(curl -s -o "$TMP/mine" -w '%{http_code}' "$BASE/api/v1/bookings" -H "Authorization: Bearer $ALICE")
check "GET /bookings" 200 "$CODE"
check "alice has one booking" 1 "$(cat "$TMP/mine" | python -c "import sys,json;print(len(json.load(sys.stdin)))")"
same "and it is hers" "$REF1" "$(jget "$TMP/mine" 0.bookingReference)"

hr "7. a booking is private to its owner"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/bookings/$B1" -H "Authorization: Bearer $BOB")
check "GET someone else's booking" 404 "$CODE"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/bookings/$B1" -H "Authorization: Bearer $ALICE")
check "GET own booking" 200 "$CODE"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/bookings")
check "GET /bookings without a token" 401 "$CODE"

hr "8. a cancelled hold cannot be paid for"
curl -s -o "$TMP/h3" -X POST "$BASE/api/v1/reservations" -H "Authorization: Bearer $BOB" \
  -H 'Content-Type: application/json' \
  -d "{\"eventId\":\"$EVENT_ID\",\"seatIds\":[\"$(jget "$TMP/map" sections.0.seats.3.id)\"]}"
R3=$(jget "$TMP/h3" id)
curl -s -o /dev/null -X DELETE "$BASE/api/v1/reservations/$R3" -H "Authorization: Bearer $BOB"
CODE=$(curl -s -o "$TMP/dead" -w '%{http_code}' -X POST "$BASE/api/v1/payments" \
  -H "Authorization: Bearer $BOB" -H 'Content-Type: application/json' \
  -d "{\"reservationId\":\"$R3\",\"paymentMethod\":\"card_visa_4242\"}")
check "POST /payments (released hold)" 422 "$CODE"
echo "  detail: $(jget "$TMP/dead" detail)"

printf '\n================================\n'
printf '  PASSED: %d\n  FAILED: %d\n' "$PASS" "$FAIL"
printf '================================\n'
[ "$FAIL" -eq 0 ]
