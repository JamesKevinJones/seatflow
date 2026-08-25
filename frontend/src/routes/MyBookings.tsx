import { Link } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { useBookings } from '../lib/queries'
import { dateParts, formatMoney, formatTime } from '../lib/format'
import { Button, Eyebrow, Skeleton } from '../components/ui'

export function MyBookings() {
  const { user, loading } = useAuth()
  const { data: bookings, isPending, error } = useBookings()

  if (!loading && !user) {
    return (
      <main className="mx-auto max-w-lg px-5 py-20">
        <h1 className="font-display text-[26px]">Sign in to see your bookings</h1>
        <Link to="/sign-in" className="mt-6 inline-block">
          <Button>Sign in</Button>
        </Link>
      </main>
    )
  }

  return (
    <main className="mx-auto max-w-3xl px-5 pb-24">
      <div className="border-b border-[var(--color-rule)] pt-14 pb-6 sm:pt-20">
        <Eyebrow>Your account</Eyebrow>
        <h1 className="mt-2 text-[clamp(2rem,5vw,3rem)] leading-[1.02]">Bookings</h1>
      </div>

      {error ? (
        <p className="py-16 text-[15px] text-[var(--color-alarm)]">
          Couldn't load your bookings. Reload to try again.
        </p>
      ) : isPending ? (
        <div className="space-y-3 py-8">
          <Skeleton className="h-20 w-full" />
          <Skeleton className="h-20 w-full" />
        </div>
      ) : bookings.length === 0 ? (
        <div className="py-20">
          <h2 className="font-display text-[22px]">Nothing booked yet</h2>
          <p className="mt-2 max-w-md text-[15px] text-[var(--color-ink-muted)]">
            Once you buy seats they appear here, with the reference you'll need
            at the door.
          </p>
          <Link to="/" className="mt-6 inline-block">
            <Button>See what's on</Button>
          </Link>
        </div>
      ) : (
        <ul className="divide-y divide-[var(--color-rule)]">
          {bookings.map((booking) => {
            const when = dateParts(booking.eventStartsAt)
            return (
              <li key={booking.id}>
                <Link
                  to={`/bookings/${booking.id}`}
                  className="-mx-3 flex items-center gap-5 rounded-[var(--radius-panel)] px-3 py-5
                    transition-colors duration-150 hover:bg-[var(--color-paper-raised)]"
                >
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
                    <h2 className="truncate font-display text-[19px] font-semibold">
                      {booking.eventName}
                    </h2>
                    <p className="mt-0.5 truncate text-[13px] text-[var(--color-ink-muted)]">
                      {booking.venueName}
                      <span className="mx-2 text-[var(--color-ink-faint)]">·</span>
                      <span className="tnum">{formatTime(booking.eventStartsAt)}</span>
                      <span className="mx-2 text-[var(--color-ink-faint)]">·</span>
                      {booking.seats.length}{' '}
                      {booking.seats.length === 1 ? 'seat' : 'seats'}
                    </p>
                  </div>

                  <div className="shrink-0 text-right">
                    <div className="tnum font-mono text-[12px] tracking-[0.12em] text-[var(--color-ink-muted)]">
                      {booking.bookingReference}
                    </div>
                    <div className="tnum font-display text-[16px] font-semibold">
                      {formatMoney(booking.totalCents)}
                    </div>
                  </div>
                </Link>
              </li>
            )
          })}
        </ul>
      )}
    </main>
  )
}
