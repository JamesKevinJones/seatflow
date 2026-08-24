import { Link } from 'react-router-dom'
import { useEvents } from '../lib/queries'
import { dateParts, formatMoney, formatTime } from '../lib/format'
import { Eyebrow, Skeleton } from '../components/ui'
import type { EventSummary } from '../lib/types'

/**
 * The catalogue is a schedule, so it is a list, not a grid of cards. Each row
 * carries the four things that decide whether you click: when, what, where, and
 * how much is left.
 */
export function Catalogue() {
  const { data, isPending, error } = useEvents(null)
  const events = data?.content ?? []

  const seatsLeft = events.reduce((sum, e) => sum + e.availableSeats, 0)

  return (
    <main className="mx-auto max-w-6xl px-5 pb-24">
      {/* Masthead. The thesis is scarcity, so the stat line is real counts
          rather than a slogan. */}
      <div className="border-b border-[var(--color-rule)] pt-14 pb-8 sm:pt-20">
        <Eyebrow>Live events</Eyebrow>
        <h1 className="mt-3 max-w-2xl text-[clamp(2.4rem,6vw,4rem)] leading-[0.98]">
          Pick the seat,
          <br />
          not just the ticket.
        </h1>
        {!isPending && !error ? (
          <p className="tnum mt-5 font-mono text-[12px] uppercase tracking-[0.12em] text-[var(--color-ink-muted)]">
            {events.length} {events.length === 1 ? 'event' : 'events'}
            <span className="mx-2 text-[var(--color-ink-faint)]">/</span>
            {seatsLeft.toLocaleString('en-IN')} seats free right now
          </p>
        ) : null}
      </div>

      {error ? (
        <p className="py-16 text-[15px] text-[var(--color-alarm)]">
          Couldn't load events. Check the backend is running, then reload.
        </p>
      ) : isPending ? (
        <ul className="divide-y divide-[var(--color-rule)]">
          {[0, 1, 2].map((i) => (
            <li key={i} className="flex items-center gap-6 py-7">
              <Skeleton className="h-14 w-14 shrink-0" />
              <div className="flex-1 space-y-2.5">
                <Skeleton className="h-5 w-2/5" />
                <Skeleton className="h-3.5 w-1/4" />
              </div>
            </li>
          ))}
        </ul>
      ) : events.length === 0 ? (
        <EmptyCatalogue />
      ) : (
        <ul className="divide-y divide-[var(--color-rule)]">
          {events.map((event) => (
            <EventRow key={event.id} event={event} />
          ))}
        </ul>
      )}
    </main>
  )
}

function EventRow({ event }: { event: EventSummary }) {
  const when = dateParts(event.startsAt)
  const soldOut = event.availableSeats === 0

  return (
    <li>
      <Link
        to={`/events/${event.id}`}
        className="group -mx-3 flex items-center gap-5 rounded-[var(--radius-panel)] px-3 py-6
          transition-colors duration-150 ease-[var(--ease-snap)] hover:bg-[var(--color-paper-raised)]
          sm:gap-7"
      >
        {/* Date block, set in mono. A date is data. */}
        <div
          className="tnum flex w-14 shrink-0 flex-col items-center rounded-[var(--radius-control)]
            border border-[var(--color-rule)] bg-[var(--color-paper-raised)] py-1.5 font-mono"
        >
          <span className="text-[10px] tracking-[0.1em] text-[var(--color-ink-faint)]">
            {when.month}
          </span>
          <span className="text-[20px] leading-tight font-medium">{when.day}</span>
        </div>

        <div className="min-w-0 flex-1">
          <h2 className="truncate font-display text-[20px] leading-tight font-semibold sm:text-[23px]">
            {event.name}
          </h2>
          <p className="mt-1 truncate text-[14px] text-[var(--color-ink-muted)]">
            {event.venueName}, {event.venueCity}
            <span className="mx-2 text-[var(--color-ink-faint)]">·</span>
            <span className="tnum">{formatTime(event.startsAt)}</span>
          </p>
        </div>

        {/* Availability as a proportion, not a badge. The bar is the data. */}
        <div className="hidden w-32 shrink-0 sm:block">
          <div className="tnum font-mono text-[11px] text-[var(--color-ink-muted)]">
            {soldOut ? 'Sold out' : `${event.availableSeats} free`}
          </div>
          <div className="mt-1.5 h-[3px] w-full overflow-hidden rounded-full bg-[var(--color-paper-sunk)]">
            <div
              className="h-full rounded-full bg-[var(--color-brass)] transition-[width] duration-500 ease-[var(--ease-enter)]"
              style={{ width: soldOut ? '0%' : `${Math.max(4, Math.min(100, event.availableSeats))}%` }}
            />
          </div>
        </div>

        <div className="shrink-0 text-right">
          <div className="tnum font-mono text-[10px] uppercase tracking-[0.1em] text-[var(--color-ink-faint)]">
            From
          </div>
          <div className="tnum font-display text-[17px] font-semibold">
            {formatMoney(event.lowestPriceCents)}
          </div>
        </div>
      </Link>
    </li>
  )
}

function EmptyCatalogue() {
  return (
    <div className="py-20">
      <h2 className="font-display text-[22px]">Nothing on sale yet</h2>
      <p className="mt-2 max-w-md text-[15px] text-[var(--color-ink-muted)]">
        Events appear here once an administrator publishes them. A draft event
        stays hidden until it has a seat map and goes live.
      </p>
    </div>
  )
}
