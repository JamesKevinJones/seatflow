import { Link, useParams } from 'react-router-dom'
import { useEvent } from '../lib/queries'
// No price shown here on purpose: EventResponse carries availability but not a
// price range, and prices vary by section. The seat map is where price becomes
// a real number attached to a real seat.
import { dateParts, formatTime } from '../lib/format'
import { Button, Eyebrow, Skeleton } from '../components/ui'

export function EventDetail() {
  const { eventId = '' } = useParams()
  const { data: event, isPending, error } = useEvent(eventId)

  if (error) {
    return (
      <main className="mx-auto max-w-6xl px-5 py-20">
        <h1 className="font-display text-[26px]">Event not found</h1>
        <p className="mt-2 text-[15px] text-[var(--color-ink-muted)]">
          It may have been unpublished, or the link is wrong.
        </p>
        <Link to="/" className="mt-6 inline-block">
          <Button variant="quiet">Back to what's on</Button>
        </Link>
      </main>
    )
  }

  if (isPending) {
    return (
      <main className="mx-auto max-w-6xl space-y-4 px-5 py-16">
        <Skeleton className="h-4 w-24" />
        <Skeleton className="h-12 w-2/3" />
        <Skeleton className="h-4 w-1/3" />
      </main>
    )
  }

  const when = dateParts(event.startsAt, event.venue.timezone)
  const { availability } = event
  const soldOut = availability.available === 0
  const pctLeft = availability.total === 0 ? 0 : (availability.available / availability.total) * 100

  return (
    <main className="mx-auto max-w-6xl px-5 pb-24">
      <div className="grid gap-10 pt-12 lg:grid-cols-[1.6fr_1fr] lg:gap-16 lg:pt-16">
        {/* Left: what it is. */}
        <div>
          <Eyebrow>{event.category}</Eyebrow>
          <h1 className="mt-3 text-[clamp(2.1rem,5vw,3.4rem)] leading-[1.02]">{event.name}</h1>

          <div className="mt-6 flex flex-wrap items-center gap-x-6 gap-y-2 font-mono text-[13px] text-[var(--color-ink-muted)]">
            <span className="tnum">
              {when.weekday} {when.day} {when.month} {when.year}
            </span>
            <span className="tnum">
              {formatTime(event.startsAt, event.venue.timezone)}–
              {formatTime(event.endsAt, event.venue.timezone)}
            </span>
          </div>

          <p className="mt-1 font-mono text-[13px] text-[var(--color-ink-muted)]">
            {event.venue.name}, {event.venue.city}
          </p>

          {event.description ? (
            <p className="mt-8 max-w-prose text-[16px] leading-relaxed text-[var(--color-ink-muted)]">
              {event.description}
            </p>
          ) : null}
        </div>

        {/* Right: the decision panel. */}
        <aside className="lg:sticky lg:top-20 lg:self-start">
          <div
            className="rounded-[var(--radius-panel)] border border-[var(--color-rule)]
              bg-[var(--color-paper-raised)] p-6 shadow-[var(--shadow-raise)]"
          >
            <Eyebrow>Seats</Eyebrow>

            <div className="tnum mt-3 flex items-baseline gap-2">
              <span className="font-display text-[40px] leading-none font-semibold">
                {availability.available}
              </span>
              <span className="text-[15px] text-[var(--color-ink-muted)]">
                of {availability.total} free
              </span>
            </div>

            {/* Held seats are worth naming: they are the contention this whole
                product is about, and they come back if nobody pays. */}
            {availability.reserved > 0 ? (
              <p className="tnum mt-2 font-mono text-[12px] text-[var(--color-ink-faint)]">
                {availability.reserved} held by other people right now
              </p>
            ) : null}

            <div className="mt-4 h-[4px] w-full overflow-hidden rounded-full bg-[var(--color-paper-sunk)]">
              <div
                className="h-full rounded-full bg-[var(--color-brass)] transition-[width] duration-500 ease-[var(--ease-enter)]"
                style={{ width: `${pctLeft}%` }}
              />
            </div>

            <div className="mt-6 border-t border-[var(--color-rule)] pt-4">
              {soldOut ? (
                <p className="text-[15px] text-[var(--color-ink-muted)]">
                  Every seat is taken. Held seats are released if payment isn't
                  completed, so it's worth checking back.
                </p>
              ) : !event.onSale ? (
                <p className="text-[15px] text-[var(--color-ink-muted)]">
                  Not on sale yet.
                </p>
              ) : (
                <>
                  <Link to={`/events/${event.id}/seats`}>
                    <Button className="w-full">Choose seats</Button>
                  </Link>
                  <p className="tnum mt-3 text-center font-mono text-[11px] text-[var(--color-ink-faint)]">
                    Seats are held {Math.round(event.reservationHoldSeconds / 60)} minutes
                    while you pay
                  </p>
                </>
              )}
            </div>
          </div>
        </aside>
      </div>
    </main>
  )
}
