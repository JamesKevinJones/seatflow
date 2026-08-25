# Concurrency and the Double-Booking Problem

The most important document in this repo. If you change anything in
`reservation/` or `event/`, read this first.

**The invariant:** a seat is never successfully sold twice, under any interleaving,
at any level of contention, even if the application layer has a bug.

---

## 1. The race condition

Check-then-act (TOCTOU). Two requests for seat A12:

```
T1: SELECT status FROM event_seats WHERE id=A12   ->  AVAILABLE
T2: SELECT status FROM event_seats WHERE id=A12   ->  AVAILABLE
T1: UPDATE event_seats SET status='RESERVED' WHERE id=A12
T2: UPDATE event_seats SET status='RESERVED' WHERE id=A12
                          ^ both commit. Both clients believe they won A12.
```

The gap between the read and the write is where the money is lost.

No isolation level fixes this as written. READ COMMITTED and REPEATABLE READ both
permit it, because the second UPDATE carries no predicate that can fail. Raising
the isolation level is not the answer; changing the shape of the statement is.

---

## 2. The strategy: four layers

### Layer 1 - atomic conditional UPDATE (primary mechanism)

Collapse check and act into one statement and let the database arbitrate:

```sql
UPDATE event_seats
   SET status = 'RESERVED',
       held_by_reservation_id = :reservationId,
       held_until = :expiresAt,
       version = version + 1,
       updated_at = now()
 WHERE event_id = :eventId
   AND id = ANY(:seatIds)
   AND ( status = 'AVAILABLE'
      OR (status = 'RESERVED' AND held_until < now()) );
```

In Java, the row count is the verdict:

```java
int claimed = seatRepository.tryHold(eventId, seatIds, reservationId, expiresAt);
if (claimed != seatIds.size()) {
    throw new SeatsUnavailableException(eventId, seatIds);  // rolls back ALL seats
}
```

Because the whole operation is one transaction, a partial win is impossible. You
get every seat you asked for or none of them, which is what "reserve A12 and A13
together" actually means to a user.

Two implementation notes that are easy to get wrong:

- Sort the seat id list before binding. Cheap insurance, and it keeps the query
  plan stable.
- Use `@Modifying(clearAutomatically = true, flushAutomatically = true)`. A bulk
  UPDATE bypasses the persistence context, so stale entities linger without it.

### Layer 2 - the structural backstop

```sql
CREATE UNIQUE INDEX uq_booking_seat_once ON booking_seats (event_seat_id);
```

A `booking_seats` row exists only for a confirmed booking, so a seat can appear
in at most one booking, ever. Even with a bug in every layer above, the second
sale raises a constraint violation instead of taking someone's money.

This is the only guarantee that holds regardless of application correctness, and
it is the reason the invariant at the top of this file can be stated absolutely.

### Layer 3 - pessimistic locking, used in exactly one place

The payment path takes `SELECT ... FROM reservations WHERE id = :id FOR UPDATE`.
This serializes concurrent payment attempts against the same reservation, so the
second one waits and then observes the first one's outcome rather than racing it.

Row-level, short-lived, scoped to a single row. That is what pessimistic locking
is genuinely for. It is deliberately not used on the seat-hold path.

### Layer 4 - idempotency

The `Idempotency-Key` header maps to `UNIQUE (user_id, idempotency_key)` on
`reservations`. A retried POST returns the existing reservation rather than
creating a second one.

### What we deliberately do not do

**No Redis distributed lock on the reservation path.** It would move the source
of truth off the database and introduce the classic failure mode: the lock TTL
expires mid-transaction and two holders believe they own the seat.

**No SERIALIZABLE isolation.** It would work, but it forces a retry loop on
serialization failures to solve a problem that one WHERE-clause predicate already
solves at lower cost.

**Not JPA optimistic locking for the hold itself.** The `@Version` approach works,
but it costs a read round-trip per seat, only reports staleness rather than
availability (it cannot express "and the seat is not already BOOKED"), and
surfaces the failure late, at flush time. The `version` column is retained for
auditing and for ordinary JPA-managed edits elsewhere.

---

## 3. Why this actually prevents double booking

The mechanism is a specific, documented PostgreSQL behaviour.

Under READ COMMITTED, when an UPDATE reaches a row that a concurrent uncommitted
transaction has locked, it **blocks**. When that transaction commits, PostgreSQL
**re-evaluates the UPDATE's WHERE clause against the newly committed version of
the row**. If the row no longer satisfies the predicate, it is skipped and not
counted in the affected-row total.

That is the whole trick, and it hinges on one thing:

> **The status column must appear in the WHERE clause.**
> Remove that predicate and you get silent last-writer-wins. Keep it and the
> loser's UPDATE matches zero rows.

If you ever find yourself "simplifying" the query by dropping the status check,
you have reintroduced the bug this entire project exists to solve.

---

## 4. Two users, same seat, same instant

```
t0  T1 BEGIN                          T2 BEGIN
t1  UPDATE ... A12 AND status='AVAILABLE'
t2      -> acquires row lock on A12
t3                                    UPDATE ... A12 AND status='AVAILABLE'
t4                                        -> BLOCKS on T1 row lock
t5  COMMIT  (rows = 1) -------------+
t6                                  +-> T2 wakes, re-checks the predicate against
                                        the new row: status is now 'RESERVED'
                                        -> row skipped -> rows = 0
t7                                    claimed(0) != requested(1) -> ROLLBACK
```

T1 gets `201 Created`. T2 gets `409 Conflict` with `unavailableSeatIds: ["A12"]`.

**Reporting which seats lost.** The UPDATE returns a count, not identities. The
exception handler runs one follow-up SELECT of those seats' current status
*after* the rollback, on the failure path only. It costs nothing in the happy path
and lets the client re-render the seat map instead of discarding the selection.

**Deadlocks.** A multi-seat request is a single statement, and PostgreSQL locks
rows in scan order, which is identical across sessions. Two overlapping requests
therefore cannot acquire locks in opposing orders. This is a real advantage over
a multi-statement `SELECT ... FOR UPDATE` sequence, which can deadlock when two
transactions lock A12 and A13 in different orders.

---

## 5. Reservation expiry

Two independent mechanisms, because depending on a scheduler for correctness is
fragile.

**Lazy expiry carries correctness.** The clause
`OR (status='RESERVED' AND held_until < now())` means a lapsed hold is already
reservable by the next request, whether or not any cleanup job ever runs.
Correctness never depends on the sweeper.

**The sweeper carries UX.** A `@Scheduled` bulk UPDATE every 10 seconds or so
flips lapsed holds back to AVAILABLE, marks reservations EXPIRED, and emits the
WebSocket and Kafka events so the seat map visibly repopulates for people who are
staring at it.

For multi-instance safety the sweeper takes `pg_try_advisory_lock` - deliberately
PostgreSQL, not Redis, so cleanup has no dependency on the cache tier.

---

## 6. If Redis goes down

**No booking becomes incorrect**, because Redis is never the arbiter.

| Redis feature | On failure |
| --- | --- |
| Event and seat-map cache | Cache miss, read PostgreSQL. Slower, correct. |
| Hold mirror and TTL keyspace hints | Sweeper still runs on the DB clock. No impact. |
| Rate limiting | Fails open, or degrades to per-instance in-memory. |
| WebSocket fan-out across instances | Single-instance broadcast still works; clients re-sync via REST on reconnect. |

If Redis were instead the lock, its failure would be a correctness incident. That
inversion is the most defensible property of this design.

---

## 7. Payment and booking partial failures

| Failure | Handling |
| --- | --- |
| Payment succeeds, booking creation fails | The gateway call sits between two transactions. Tx-B (record result, create booking, flip seats to BOOKED) is atomic. If Tx-B fails the payment row is stranded in PROCESSING and a reconciliation job retries it. Documented, not hidden. |
| Duplicate payment requests | `FOR UPDATE` on the reservation serializes them; `uq_payment_inflight` makes a second live payment impossible. The second caller receives the same booking with 200, not a new one. |
| Reservation expires mid-payment | Before calling the gateway, atomically push `held_until` forward by a grace window. The confirm step then requires `status='RESERVED' AND held_by_reservation_id = :rid AND held_until > now()`. If the count comes up short the hold lapsed, the simulated payment reverses, and the caller gets 409. |

---

## 8. Domain events, and the dual write

Everything above is about one database. This part is about the moment a second
system enters the picture, because that is where the same class of bug reappears
wearing different clothes.

A booking has to be announced: a confirmation to send, a revenue figure to
update, whatever gets added later. The obvious implementation publishes to Kafka
inside the booking transaction. It has no correct ordering:

```
send to Kafka, then COMMIT   ->  the commit fails, and consumers have already
                                 acted on a booking that never happened
COMMIT, then send to Kafka   ->  the process dies in between, and the event is
                                 gone with nothing left to say it was owed
```

Neither is a race that better locking fixes. There are two systems with two
independent failure modes and no shared commit, so there is no instant at which
both are known to have succeeded. Retrying the send does not help either: the
retry itself can be the thing that dies.

**The fix is to stop having two systems in the transaction.** The event is
written to an ordinary table, `outbox`, in the same transaction as the booking:

```sql
INSERT INTO outbox (message_id, topic, partition_key, payload, ...) VALUES (...);
```

One commit, one outcome. If the booking rolls back the message was never
written, so a rolled-back sale cannot be announced. If the booking commits the
message committed with it, so a sale cannot go unannounced. A separate relay
moves rows to Kafka afterwards, and the transaction never waited for a broker.

### What this costs

Delivery becomes **at-least-once**. The relay sends, the broker acknowledges,
and the relay marks the row published - and it can die between those last two
steps. On restart the row still reads unpublished, so it goes again. Closing
that window would require the send and the mark to be atomic across two systems,
which is exactly the thing that does not exist.

So the duplicate is accepted and pushed to the consumers, which is the right
place for it. Every message carries a `messageId` assigned once at record time,
and a consumer with a side effect that is not naturally repeatable checks it
before acting. Revenue is the sharp case: adding the same payment twice inflates
a number with nothing anywhere to indicate it happened.

### Why the relay tracks no cursor

The relay asks `WHERE published_at IS NULL ORDER BY id`, never "everything after
the last id I saw". The watermark version looks obviously better and is quietly
broken: ids come from a sequence and are handed out at INSERT, but rows become
visible at COMMIT. A transaction can take id 100 and commit *after* one that
took 101, so a relay that had advanced past 101 would step over 100 forever.
Asking what is still unpublished cannot have that bug, whatever order things
commit in.

### Multiple relays

The claim is `SELECT ... FOR UPDATE SKIP LOCKED`, so every instance can run a
relay and each takes a disjoint batch. There is no leader, no advisory lock, and
no designated node - PostgreSQL partitions the work by construction. This is the
same tool named as out of scope for seat selection in part 10; it is the right
tool here, because "any N rows nobody else is working on" is exactly the
question.

### Kafka is not on the correctness path

The same rule as Redis, for the same reason. Selling a seat writes database rows
and nothing else. Stop the broker entirely and reservations, payments, bookings
and expiry all keep working; the outbox backlog grows and drains when it returns.

Measured, not assumed: with the broker stopped, the full checkout suite passes
22 of 22, four bookings complete, and no seat is sold twice. On restart the
backlog drained in about nine seconds, with `attempts` reaching 3 on the rows
that had been retried.

---

## 9. How this gets tested

**Testcontainers with real PostgreSQL. Never H2.** H2 does not reproduce the
row-lock-and-re-check semantics described in part 3, so an H2 concurrency test
would pass while proving nothing. A green meaningless test is worse than no test.

The centerpiece, `ConcurrentReservationIT`:

- 200 threads parked on a `CountDownLatch`, released simultaneously at one seat.
- Assert exactly 1 success and 199 `SeatsUnavailableException`.
- Assert the database holds exactly 1 RESERVED row for that seat.
- In the booking variant, assert `SELECT count(*) FROM booking_seats` is exactly 1.

**The test method must not be `@Transactional`.** A test-managed transaction would
hold locks and mask the very behaviour under test.

---

## 10. Related but out of scope for v1

`SELECT ... FOR UPDATE SKIP LOCKED` is the right tool for "give me any N of
these that nobody else is working on". It is not used for the pick-your-own-seat
flow, where the customer names the row and the answer has to be about that row -
skipping a locked seat would silently hand them a different one. It is the
natural extension if general admission or best-available assignment is added.

It *is* used, in this codebase, by the outbox relay (part 8), where "any N rows
nobody else has claimed" is precisely the question being asked.

---

## 11. Where this lives, as implemented

| Concept | File |
| --- | --- |
| The conditional UPDATE (Layer 1) | `event/infrastructure/EventSeatRepository.tryHold` |
| Module seam | `event/application/SeatAllocationPort` |
| Row-count verdict and rollback | `reservation/application/ReservationService.reserve` |
| Conflict report, read after rollback | `reservation/presentation/ReservationExceptionHandler` |
| Lazy expiry | the second branch of the `tryHold` predicate |
| Sweeper, under advisory lock | `reservation/application/ReservationExpirySweeper` |
| The proof | `reservation/ConcurrentReservationIT` |
| Outbox write, atomic with the booking | `messaging/application/OutboxRecorder` (`Propagation.MANDATORY`) |
| Relay, claiming with SKIP LOCKED | `messaging/infrastructure/OutboxRelay` |
| Duplicate suppression | `messaging/consumer/ProcessedMessages` |
| The outbox proof | `messaging/OutboxIT` |

Two details that were decided while building, and are easy to get wrong again:

**The conflict report cannot run inside the failed transaction.** It would read
that transaction's own doomed writes. It also cannot run in a nested
transaction: that takes a second pooled connection while still holding the
first, so at 200 concurrent losers the pool deadlocks - precisely under the load
the feature exists for. It runs in the exception handler, after rollback has
released the connection.

**The seat FK is `NO ACTION`, not `SET NULL`.** Nulling `held_by_reservation_id`
while `status` stayed `RESERVED` would violate `ck_event_seat_state`. The
database refuses to delete a reservation that still holds seats; release them
first.
