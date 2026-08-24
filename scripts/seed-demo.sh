#!/usr/bin/env bash
# Seeds one realistic published event so the frontend has something to render.
#
# The held/sold seats at the end are set directly in the database because the
# reservation and booking services do not exist yet (Phase 3 and Phase 7). They
# are real rows in the real schema, satisfying ck_event_seat_state - not mocked
# API responses. Remove this script once reservations can create those states.
set -euo pipefail

BASE="${SEATFLOW_BASE_URL:-http://127.0.0.1:8080}"
ADMIN_EMAIL="${SEATFLOW_ADMIN_EMAIL:-admin@seatflow.local}"
ADMIN_PASSWORD="${SEATFLOW_ADMIN_PASSWORD:-local-admin-password-change-me}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

jget() { cat "$1" | python -c "
import sys,json
d=json.load(sys.stdin); cur=d
for p in sys.argv[1].split('.'):
    cur = cur[int(p)] if isinstance(cur,list) else (cur or {}).get(p)
print('' if cur is None else cur)
" "$2"; }

echo "signing in as admin"
curl -s -o "$TMP/a" -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
TOKEN=$(jget "$TMP/a" accessToken)
[ -n "$TOKEN" ] || { echo "admin login failed"; exit 1; }

# Suffixed so the script can be run more than once: uq_venues_name_city allows
# one venue of a given name per city.
VENUE_NAME="Chowdiah Memorial Hall $(date +%H%M%S)"
echo "creating venue: $VENUE_NAME"
VENUE_BODY=$(python - "$VENUE_NAME" <<'PYEOF'
import json, sys

def rows(labels, count):
    return [{"rowLabel": l, "seatCount": count} for l in labels]

print(json.dumps({
    "name": sys.argv[1],
    "address": "Gayathri Devi Park Extension, Vyalikaval",
    "city": "Bengaluru",
    "country": "India",
    "timezone": "Asia/Kolkata",
    "sections": [
        {"name": "Stalls",  "displayOrder": 1, "rows": rows(list("ABCDEF"), 16)},
        {"name": "Circle",  "displayOrder": 2, "rows": rows(list("ABCD"), 14)},
        {"name": "Balcony", "displayOrder": 3, "rows": rows(list("ABC"), 12)},
    ],
}))
PYEOF
)
curl -s -o "$TMP/v" -X POST "$BASE/api/v1/admin/venues" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$VENUE_BODY"
VENUE_ID=$(jget "$TMP/v" id)
[ -n "$VENUE_ID" ] || { echo "venue creation failed:"; cat "$TMP/v"; exit 1; }
echo "  venue $VENUE_ID with $(jget "$TMP/v" totalSeats) seats"

echo "creating event"
EVENT_BODY=$(python - "$VENUE_ID" <<'PYEOF'
import datetime, json, sys
start = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=24)
print(json.dumps({
    "venueId": sys.argv[1],
    "name": "Nights at the Hall: Ravi Shankar Centenary",
    "category": "music",
    "description": ("A sitar and orchestra programme marking the centenary, performed "
                    "in the round. Doors open an hour before the first set."),
    "startsAt": start.strftime('%Y-%m-%dT%H:%M:%SZ'),
    "endsAt": (start + datetime.timedelta(hours=3)).strftime('%Y-%m-%dT%H:%M:%SZ'),
    "reservationHoldSeconds": 600,
    "defaultPriceCents": 320000,
    "sectionPrices": [
        {"sectionName": "Circle", "priceCents": 210000},
        {"sectionName": "Balcony", "priceCents": 95000},
    ],
}))
PYEOF
)
curl -s -o "$TMP/e" -X POST "$BASE/api/v1/admin/events" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$EVENT_BODY"
EVENT_ID=$(jget "$TMP/e" id)
[ -n "$EVENT_ID" ] || { echo "event creation failed:"; cat "$TMP/e"; exit 1; }
echo "  event $EVENT_ID with $(jget "$TMP/e" availability.total) seats"

curl -s -o "$TMP/p" -X POST "$BASE/api/v1/admin/events/$EVENT_ID/publish" -H "Authorization: Bearer $TOKEN"
echo "  published: $(jget "$TMP/p" status)"

echo "holding seats through the real reservation API, as other people"
# Two other customers each hold a handful of seats. These are genuine holds -
# real reservations, real expiry - not rows poked into the table. Writing a
# fake holder id straight into event_seats is what left orphaned references
# that V4's foreign key later rejected.
for holder in one two; do
  EMAIL="demo-holder-$holder-$(date +%s%N)@example.com"
  curl -s -o "$TMP/h-$holder" -X POST "$BASE/api/v1/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"correct-horse-battery\",\"fullName\":\"Demo Holder\"}"
  HTOKEN=$(jget "$TMP/h-$holder" accessToken)

  # Take up to 8 seats each - the per-reservation cap.
  SEATS=$(curl -s "$BASE/api/v1/events/$EVENT_ID/seats" | python -c "
import sys, json, random
d = json.load(sys.stdin)
free = [s['id'] for sec in d['sections'] for s in sec['seats'] if s['status'] == 'AVAILABLE']
random.shuffle(free)
print(json.dumps(free[:8]))
")
  curl -s -o /dev/null -X POST "$BASE/api/v1/reservations" \
    -H "Authorization: Bearer $HTOKEN" -H 'Content-Type: application/json' \
    -d "{\"eventId\":\"$EVENT_ID\",\"seatIds\":$SEATS}"
done

echo "marking some seats sold"
# Still direct SQL: bookings do not exist until Phase 7, and booking_id is a
# bare UUID column until V5 adds its foreign key. Replace this the moment
# checkout is real.
wsl.exe -e bash -lc "docker exec -i seatflow-postgres psql -U seatflow -d seatflow -q -v ON_ERROR_STOP=1 << 'SQL'
UPDATE event_seats SET status='BOOKED', booking_id = gen_random_uuid()
 WHERE id IN (
   SELECT es.id FROM event_seats es
     JOIN seats s ON s.id = es.seat_id
     JOIN venue_sections sec ON sec.id = s.venue_section_id
    WHERE es.event_id = '$EVENT_ID' AND es.status='AVAILABLE'
      AND sec.name = 'Stalls' AND s.row_label IN ('A','B','C')
    ORDER BY random() LIMIT 26);

SELECT status, count(*) FROM event_seats WHERE event_id='$EVENT_ID' GROUP BY status ORDER BY status;
SQL" 2>&1 | tr -d '\0' | tail -6

echo
echo "done. open http://localhost:5173/events/$EVENT_ID/seats"
