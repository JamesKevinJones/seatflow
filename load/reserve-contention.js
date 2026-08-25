import http from 'k6/http'
import { check, sleep } from 'k6'
import { Counter, Rate, Trend } from 'k6/metrics'
import exec from 'k6/execution'

/**
 * Contention load test: many people, few seats.
 *
 * This is the scenario the whole project is built around. It creates an event
 * with a fixed number of seats, then throws far more concurrent buyers at it
 * than there are seats to go round, and checks two things:
 *
 *   1. The system stays responsive while losing most of the requests on purpose.
 *   2. The number of successful reservations never exceeds the number of seats.
 *
 * The second is the one that matters. Throughput figures are interesting;
 * "exactly 100 seats were sold from 100 seats" is the point.
 *
 * Run:  k6 run load/reserve-contention.js
 */

const BASE = __ENV.SEATFLOW_BASE_URL || 'http://127.0.0.1:8080'
const ADMIN_EMAIL = __ENV.SEATFLOW_ADMIN_EMAIL || 'admin@seatflow.local'
const ADMIN_PASSWORD = __ENV.SEATFLOW_ADMIN_PASSWORD || 'local-admin-password-change-me'

const SEAT_COUNT = Number(__ENV.SEATS || 100)
const BUYER_POOL = Number(__ENV.BUYERS || 60)

const reservationsSucceeded = new Counter('reservations_succeeded')
const reservationsConflicted = new Counter('reservations_conflicted')
const unexpectedErrors = new Rate('unexpected_errors')
const reserveDuration = new Trend('reserve_duration', true)

export const options = {
  scenarios: {
    stampede: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 200 },
        { duration: '30s', target: 1000 },
        { duration: '20s', target: 1000 },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // A 409 is the system working correctly, not an error. Only genuine
    // failures - timeouts, 5xx - count against this.
    unexpected_errors: ['rate<0.01'],
    http_req_duration: ['p(95)<2000'],
    checks: ['rate>0.99'],
  },
}

function authHeaders(token) {
  return { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } }
}

export function setup() {
  const login = http.post(
    `${BASE}/api/v1/auth/login`,
    JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  )
  if (login.status !== 200) {
    throw new Error(`admin login failed: ${login.status} ${login.body}`)
  }
  const adminToken = login.json('accessToken')

  // A venue whose only row is exactly as long as the seat budget.
  const stamp = Date.now()
  const venue = http.post(
    `${BASE}/api/v1/admin/venues`,
    JSON.stringify({
      name: `Load Test Arena ${stamp}`,
      address: '1 Load Street',
      city: 'Bengaluru',
      country: 'India',
      timezone: 'Asia/Kolkata',
      sections: [
        { name: 'Floor', displayOrder: 1, rows: [{ rowLabel: 'A', seatCount: SEAT_COUNT }] },
      ],
    }),
    authHeaders(adminToken),
  )
  if (venue.status !== 201) {
    throw new Error(`venue creation failed: ${venue.status} ${venue.body}`)
  }

  const startsAt = new Date(Date.now() + 20 * 24 * 3600 * 1000)
  const event = http.post(
    `${BASE}/api/v1/admin/events`,
    JSON.stringify({
      venueId: venue.json('id'),
      name: `Load Test ${stamp}`,
      category: 'music',
      startsAt: startsAt.toISOString().replace(/\.\d+Z$/, 'Z'),
      endsAt: new Date(startsAt.getTime() + 2 * 3600 * 1000).toISOString().replace(/\.\d+Z$/, 'Z'),
      defaultPriceCents: 150000,
      // Long enough that nothing expires mid-run and muddies the count.
      reservationHoldSeconds: 1800,
    }),
    authHeaders(adminToken),
  )
  if (event.status !== 201) {
    throw new Error(`event creation failed: ${event.status} ${event.body}`)
  }
  const eventId = event.json('id')
  http.post(`${BASE}/api/v1/admin/events/${eventId}/publish`, null, authHeaders(adminToken))

  const map = http.get(`${BASE}/api/v1/events/${eventId}/seats`)
  const seatIds = map.json('sections.0.seats').map((s) => s.id)

  // A pool of real accounts. Registration is BCrypt-slow by design, so this is
  // done once here rather than per virtual user.
  const buyers = []
  for (let i = 0; i < BUYER_POOL; i++) {
    const registration = http.post(
      `${BASE}/api/v1/auth/register`,
      JSON.stringify({
        email: `load-${stamp}-${i}@example.com`,
        password: 'correct-horse-battery',
        fullName: `Load Buyer ${i}`,
      }),
      { headers: { 'Content-Type': 'application/json' } },
    )
    if (registration.status === 201) {
      buyers.push(registration.json('accessToken'))
    }
  }
  if (buyers.length === 0) {
    throw new Error('could not register any buyers')
  }

  console.log(`setup: event ${eventId}, ${seatIds.length} seats, ${buyers.length} buyers`)
  return { eventId, seatIds, buyers, adminToken }
}

export default function (data) {
  const token = data.buyers[exec.vu.idInTest % data.buyers.length]
  const seatId = data.seatIds[Math.floor(Math.random() * data.seatIds.length)]

  const response = http.post(
    `${BASE}/api/v1/reservations`,
    JSON.stringify({ eventId: data.eventId, seatIds: [seatId] }),
    { ...authHeaders(token), tags: { name: 'reserve' } },
  )

  reserveDuration.add(response.timings.duration)

  const created = response.status === 201
  const conflict = response.status === 409

  if (created) reservationsSucceeded.add(1)
  if (conflict) reservationsConflicted.add(1)

  // Anything that is neither a hold nor a conflict is a real problem.
  unexpectedErrors.add(!created && !conflict)

  check(response, {
    'reservation resolved cleanly (201 or 409)': () => created || conflict,
  })

  sleep(0.2 + Math.random() * 0.3)
}

/**
 * The assertion that matters. Whatever the throughput was, the database must
 * show no more reservations than there were seats.
 */
export function teardown(data) {
  const map = http.get(`${BASE}/api/v1/events/${data.eventId}/seats`)
  const availability = map.json('availability')

  console.log('---- final seat ledger ----')
  console.log(`total      : ${availability.total}`)
  console.log(`available  : ${availability.available}`)
  console.log(`reserved   : ${availability.reserved}`)
  console.log(`booked     : ${availability.booked}`)

  const held = availability.reserved + availability.booked
  if (held > availability.total) {
    throw new Error(`OVERSOLD: ${held} seats held out of ${availability.total}`)
  }
  console.log(`no overselling: ${held} of ${availability.total} seats held`)
}
