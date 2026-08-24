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

VENUE_NAME="Chowdiah Memorial Hall"
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

echo "marking a realistic spread of held and sold seats"
wsl.exe -e bash -lc "docker exec -i seatflow-postgres psql -U seatflow -d seatflow -q -v ON_ERROR_STOP=1 << 'SQL'
-- Sold: clustered toward the front, the way a real hall fills.
UPDATE event_seats SET status='BOOKED', booking_id = gen_random_uuid()
 WHERE id IN (
   SELECT es.id FROM event_seats es
     JOIN seats s ON s.id = es.seat_id
     JOIN venue_sections sec ON sec.id = s.venue_section_id
    WHERE es.event_id = '$EVENT_ID' AND es.status='AVAILABLE'
      AND sec.name = 'Stalls' AND s.row_label IN ('A','B','C')
    ORDER BY random() LIMIT 26);

-- Held right now by other people, expiring in a few minutes.
UPDATE event_seats
   SET status='RESERVED', held_by_reservation_id = gen_random_uuid(),
       held_until = now() + interval '7 minutes'
 WHERE id IN (
   SELECT id FROM event_seats
    WHERE event_id = '$EVENT_ID' AND status='AVAILABLE'
    ORDER BY random() LIMIT 19);

SELECT status, count(*) FROM event_seats WHERE event_id='$EVENT_ID' GROUP BY status ORDER BY status;
SQL" 2>&1 | tr -d '\0' | tail -6

echo
echo "done. open http://localhost:5173/events/$EVENT_ID/seats"
