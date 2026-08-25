#!/usr/bin/env bash
# Phase 2 verification. Venues, events, EventSeat generation, and seat maps.
# Requires a running app (local profile) with the bootstrap admin present.
BASE="${SEATFLOW_BASE_URL:-http://127.0.0.1:8080}"
ADMIN_EMAIL="${SEATFLOW_ADMIN_EMAIL:-admin@seatflow.local}"
ADMIN_PASSWORD="${SEATFLOW_ADMIN_PASSWORD:-local-admin-password-change-me}"
# Python does the JSON parsing. The interpreter is named "python3" on Linux and
# often only "python" on Windows, so resolve it once rather than assuming: these
# suites have to run both from Git Bash on the host and from inside WSL against
# the containerised stack.
PY_BIN="$(command -v python3 || command -v python)"
if [ -z "$PY_BIN" ]; then echo "python3 (or python) is required" >&2; exit 1; fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

hr() { printf '\n---- %s ----\n' "$1"; }

check() {
  if [ "$2" = "$3" ]; then
    printf '  PASS  %-48s (%s)\n' "$1" "$3"; PASS=$((PASS+1))
  else
    printf '  FAIL  %-48s expected %s got %s\n' "$1" "$2" "$3"; FAIL=$((FAIL+1))
  fi
}

# jget <file> <dotted.path>
jget() { cat "$1" | "$PY_BIN" -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception: print(''); sys.exit(0)
cur=d
for part in sys.argv[1].split('.'):
    if cur is None: break
    if isinstance(cur,list):
        try: cur=cur[int(part)]
        except Exception: cur=None
    elif isinstance(cur,dict): cur=cur.get(part)
    else: cur=None
print('' if cur is None else cur)
" "$2"; }

# ---------------------------------------------------------------- admin login
hr "1. admin login"
CODE=$(curl -s -o "$TMP/admin" -w '%{http_code}' -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}")
check "POST /auth/login (bootstrap admin)" 200 "$CODE"
ADMIN_TOKEN=$(jget "$TMP/admin" accessToken)
echo "  roles: $(jget "$TMP/admin" user.roles.0)"

hr "2. a plain USER account for negative tests"
USER_EMAIL="catalog+$(date +%s)@example.com"
curl -s -o "$TMP/user" -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$USER_EMAIL\",\"password\":\"correct-horse-battery\",\"fullName\":\"Plain User\"}" >/dev/null
USER_TOKEN=$(jget "$TMP/user" accessToken)
echo "  registered $USER_EMAIL with role $(jget "$TMP/user" user.roles.0)"

# ------------------------------------------------------------------- venue
hr "3. create venue with layout (30 + 16 = 46 seats)"
# Unique per run. uq_venues_name_city allows one "Grand Hall" per city, so a
# fixed name would make this script single-use.
VENUE_NAME="Grand Hall $(date +%s)"
read -r -d '' VENUE_BODY <<JSON
{
  "name": "$VENUE_NAME",
  "address": "1 Residency Road",
  "city": "Bengaluru",
  "country": "India",
  "timezone": "Asia/Kolkata",
  "sections": [
    { "name": "Orchestra", "displayOrder": 1,
      "rows": [ {"rowLabel":"A","seatCount":10}, {"rowLabel":"B","seatCount":10}, {"rowLabel":"C","seatCount":10} ] },
    { "name": "Balcony", "displayOrder": 2,
      "rows": [ {"rowLabel":"A","seatCount":8}, {"rowLabel":"B","seatCount":8} ] }
  ]
}
JSON
CODE=$(curl -s -o "$TMP/venue" -w '%{http_code}' -X POST "$BASE/api/v1/admin/venues" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -d "$VENUE_BODY")
check "POST /admin/venues (as ADMIN)" 201 "$CODE"
VENUE_ID=$(jget "$TMP/venue" id)
TOTAL_SEATS=$(jget "$TMP/venue" totalSeats)
check "venue totalSeats" 46 "$TOTAL_SEATS"
echo "  sections: $(jget "$TMP/venue" sections.0.name)=$(jget "$TMP/venue" sections.0.seatCount), $(jget "$TMP/venue" sections.1.name)=$(jget "$TMP/venue" sections.1.seatCount)"

hr "4. venue creation is admin-only"
CODE=$(curl -s -o "$TMP/deny" -w '%{http_code}' -X POST "$BASE/api/v1/admin/venues" \
  -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' -d "$VENUE_BODY")
check "POST /admin/venues (as USER)" 403 "$CODE"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/admin/venues" \
  -H 'Content-Type: application/json' -d "$VENUE_BODY")
check "POST /admin/venues (no token)" 401 "$CODE"

hr "5. layout validation is enforced"
CODE=$(curl -s -o "$TMP/badvenue" -w '%{http_code}' -X POST "$BASE/api/v1/admin/venues" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"","address":"x","city":"y","country":"z","sections":[]}')
check "POST /admin/venues (empty name + no sections)" 400 "$CODE"
echo "  errors reported: $(cat "$TMP/badvenue" | "$PY_BIN" -c "import sys,json;print(len(json.load(sys.stdin).get('errors',[])))" 2>/dev/null)"

# ------------------------------------------------------------------- event
hr "6. create event (seats generated from venue)"
STARTS=$("$PY_BIN" -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(days=30)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
ENDS=$("$PY_BIN" -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(days=30,hours=3)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
EVENT_BODY=$("$PY_BIN" - "$VENUE_ID" "$STARTS" "$ENDS" <<'PY'
import json,sys
print(json.dumps({
  "venueId": sys.argv[1],
  "name": "Midnight Symphony",
  "category": "music",
  "description": "A late-night orchestral programme.",
  "startsAt": sys.argv[2],
  "endsAt": sys.argv[3],
  "defaultPriceCents": 250000,
  "sectionPrices": [{"sectionName": "Balcony", "priceCents": 120000}]
}))
PY
)
CODE=$(curl -s -o "$TMP/event" -w '%{http_code}' -X POST "$BASE/api/v1/admin/events" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -d "$EVENT_BODY")
check "POST /admin/events" 201 "$CODE"
EVENT_ID=$(jget "$TMP/event" id)
check "event status on create" DRAFT "$(jget "$TMP/event" status)"
check "generated seats == venue seats" 46 "$(jget "$TMP/event" availability.total)"
check "all generated seats AVAILABLE" 46 "$(jget "$TMP/event" availability.available)"
echo "  slug: $(jget "$TMP/event" slug) | onSale: $(jget "$TMP/event" onSale) | hold: $(jget "$TMP/event" reservationHoldSeconds)s"

hr "7. slug collision produces a distinct slug"
CODE=$(curl -s -o "$TMP/event2" -w '%{http_code}' -X POST "$BASE/api/v1/admin/events" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -d "$EVENT_BODY")
check "POST /admin/events (same name again)" 201 "$CODE"
SLUG1=$(jget "$TMP/event" slug); SLUG2=$(jget "$TMP/event2" slug)
if [ -n "$SLUG2" ] && [ "$SLUG1" != "$SLUG2" ]; then
  printf '  PASS  %-48s (%s vs %s)\n' "slugs differ" "$SLUG1" "$SLUG2"; PASS=$((PASS+1))
else
  printf '  FAIL  %-48s both were %s\n' "slug collision not handled" "$SLUG1"; FAIL=$((FAIL+1))
fi

hr "8. a DRAFT event is invisible to the public"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/events/$EVENT_ID")
check "GET /events/{id} while DRAFT" 404 "$CODE"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/admin/events/$EVENT_ID" -H "Authorization: Bearer $ADMIN_TOKEN")
check "GET /admin/events/{id} while DRAFT" 200 "$CODE"

hr "9. publish"
CODE=$(curl -s -o "$TMP/pub" -w '%{http_code}' -X POST "$BASE/api/v1/admin/events/$EVENT_ID/publish" \
  -H "Authorization: Bearer $ADMIN_TOKEN")
check "POST /admin/events/{id}/publish" 200 "$CODE"
check "status after publish" PUBLISHED "$(jget "$TMP/pub" status)"
check "onSale after publish" True "$(jget "$TMP/pub" onSale)"

hr "10. public catalogue"
CODE=$(curl -s -o "$TMP/list" -w '%{http_code}' "$BASE/api/v1/events")
check "GET /events (no auth)" 200 "$CODE"
echo "  events returned: $(cat "$TMP/list" | "$PY_BIN" -c "import sys,json;print(len(json.load(sys.stdin).get('content',[])))" 2>/dev/null)"
echo "  first card: $(jget "$TMP/list" content.0.name) | available=$(jget "$TMP/list" content.0.availableSeats) | from=$(jget "$TMP/list" content.0.lowestPriceCents) cents"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/events/$EVENT_ID")
check "GET /events/{id} after publish" 200 "$CODE"

hr "11. seat map"
CODE=$(curl -s -o "$TMP/map" -w '%{http_code}' "$BASE/api/v1/events/$EVENT_ID/seats")
check "GET /events/{id}/seats (no auth)" 200 "$CODE"
check "seat map total" 46 "$(jget "$TMP/map" availability.total)"
SEC_COUNT=$(cat "$TMP/map" | "$PY_BIN" -c "import sys,json;print(len(json.load(sys.stdin)['sections']))" 2>/dev/null)
check "sections on the map" 2 "$SEC_COUNT"
MAPPED=$(cat "$TMP/map" | "$PY_BIN" -c "import sys,json;d=json.load(sys.stdin);print(sum(len(s['seats']) for s in d['sections']))" 2>/dev/null)
check "seats across all sections" 46 "$MAPPED"
echo "  section 1: $(jget "$TMP/map" sections.0.name) | first seat $(jget "$TMP/map" sections.0.seats.0.label) @ $(jget "$TMP/map" sections.0.seats.0.priceCents) cents ($(jget "$TMP/map" sections.0.seats.0.status))"
echo "  section 2: $(jget "$TMP/map" sections.1.name) | first seat $(jget "$TMP/map" sections.1.seats.0.label) @ $(jget "$TMP/map" sections.1.seats.0.priceCents) cents"

hr "12. per-section pricing was applied"
ORCH_PRICE=$(jget "$TMP/map" sections.0.seats.0.priceCents)
BALC_PRICE=$(jget "$TMP/map" sections.1.seats.0.priceCents)
check "Orchestra uses default price" 250000 "$ORCH_PRICE"
check "Balcony uses section override" 120000 "$BALC_PRICE"

hr "13. every seat carries an EventSeat id, not a physical seat id"
FIRST_ID=$(jget "$TMP/map" sections.0.seats.0.id)
if [ -n "$FIRST_ID" ] && [ "$FIRST_ID" != "None" ]; then
  printf '  PASS  %-48s (%s)\n' "seat id present for reservation" "${FIRST_ID:0:8}..."; PASS=$((PASS+1))
else
  printf '  FAIL  %-48s\n' "seat id missing"; FAIL=$((FAIL+1))
fi

hr "14. cancel"
CODE=$(curl -s -o "$TMP/cancel" -w '%{http_code}' -X POST "$BASE/api/v1/admin/events/$EVENT_ID/cancel" \
  -H "Authorization: Bearer $ADMIN_TOKEN")
check "POST /admin/events/{id}/cancel" 200 "$CODE"
check "status after cancel" CANCELLED "$(jget "$TMP/cancel" status)"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/admin/events/$EVENT_ID/publish" \
  -H "Authorization: Bearer $ADMIN_TOKEN")
check "republishing a cancelled event is refused" 400 "$CODE"

printf '\n================================\n'
printf '  PASSED: %d\n  FAILED: %d\n' "$PASS" "$FAIL"
printf '================================\n'
[ "$FAIL" -eq 0 ]
