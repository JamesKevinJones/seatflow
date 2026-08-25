import { Link, useParams } from 'react-router-dom'
import { useBooking } from '../lib/queries'
import { dateParts, formatMoney, formatTime } from '../lib/format'
import { Button, Eyebrow, Skeleton } from '../components/ui'

/**
 * The ticket.
 *
 * The reference is the largest thing on the page because it is the only part
 * anyone needs to read out at a door. Everything else is confirmation that the
 * right thing was bought.
 */
export function BookingConfirmation() {
  const { bookingId = '' } = useParams()
  const { data: booking, isPending, error } = useBooking(bookingId)

  if (error) {
    return (
      <main className="mx-auto max-w-lg px-5 py-20">
        <h1 className="font-display text-[26px]">Booking not found</h1>
        <Link to="/bookings" className="mt-6 inline-block">
          <Button variant="quiet">Your bookings</Button>
        </Link>
      </main>
    )
  }

  if (isPending) {
    return (
      <main className="mx-auto max-w-lg space-y-4 px-5 py-16">
        <Skeleton className="h-4 w-24" />
        <Skeleton className="h-14 w-1/2" />
        <Skeleton className="h-40 w-full" />
      </main>
    )
  }

  const when = dateParts(booking.eventStartsAt)

  return (
    <main className="mx-auto max-w-lg px-5 py-14 sm:py-20">
      <Eyebrow>Confirmed</Eyebrow>
      <h1 className="mt-2 text-[30px] leading-[1.05]">{booking.eventName}</h1>
      <p className="mt-2 text-[15px] text-[var(--color-ink-muted)]">
        {booking.venueName}
        <span className="mx-2 text-[var(--color-ink-faint)]">·</span>
        <span className="tnum">
          {when.weekday} {when.day} {when.month}, {formatTime(booking.eventStartsAt)}
        </span>
      </p>

      {/* The reference, treated like the ticket stub it is. */}
      <div
        className="mt-8 rounded-[var(--radius-panel)] border border-[var(--color-brass)]/40
          bg-[var(--color-paper-raised)] p-6 text-center shadow-[var(--shadow-raise)]"
      >
        <div className="font-mono text-[10px] uppercase tracking-[0.2em] text-[var(--color-ink-muted)]">
          Booking reference
        </div>
        <div className="tnum mt-2 font-mono text-[34px] leading-none font-semibold tracking-[0.14em]">
          {booking.bookingReference}
        </div>
      </div>

      <div className="mt-6 rounded-[var(--radius-panel)] border border-[var(--color-rule)] p-5">
        <Eyebrow>Seats</Eyebrow>
        <ul className="mt-3 divide-y divide-[var(--color-rule)]">
          {booking.seats.map((seat) => (
            <li key={seat.eventSeatId} className="flex items-center justify-between py-2.5">
              <span className="text-[15px]">
                <span className="font-medium">{seat.label}</span>
                <span className="ml-2 text-[13px] text-[var(--color-ink-muted)]">
                  {seat.sectionName}
                </span>
              </span>
              <span className="tnum font-mono text-[13px]">{formatMoney(seat.priceCents)}</span>
            </li>
          ))}
        </ul>
        <div className="mt-3 flex items-baseline justify-between border-t border-[var(--color-rule)] pt-3">
          <span className="text-[15px]">Paid</span>
          <span className="tnum font-display text-[22px] font-semibold">
            {formatMoney(booking.totalCents)}
          </span>
        </div>
      </div>

      <div className="mt-6 flex gap-3">
        <Link to="/bookings">
          <Button variant="quiet">Your bookings</Button>
        </Link>
        <Link to="/">
          <Button variant="ghost">What's on</Button>
        </Link>
      </div>
    </main>
  )
}
