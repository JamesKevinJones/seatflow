import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../lib/api'
import { usePay, useReservation } from '../lib/queries'
import { formatMoney } from '../lib/format'
import { Button, Eyebrow, Notice, Skeleton } from '../components/ui'
import { Countdown } from '../components/Countdown'

/**
 * The last screen before the seats are actually yours.
 *
 * The hold is re-read from the server rather than carried through router state,
 * so refreshing here does not lose it, and the countdown is the server's expiry
 * rather than something this page remembered.
 */
export function Checkout() {
  const { reservationId = '' } = useParams()
  const navigate = useNavigate()
  const { data: hold, isPending, error } = useReservation(reservationId)
  const pay = usePay()

  // A stand-in for a card field. Real instruments never touch this codebase.
  const [method, setMethod] = useState('card_visa_4242')

  if (error) {
    return (
      <main className="mx-auto max-w-lg px-5 py-20">
        <h1 className="font-display text-[26px]">That hold is gone</h1>
        <p className="mt-2 text-[15px] text-[var(--color-ink-muted)]">
          It either expired or was released. The seats are back in the pool.
        </p>
        <Link to="/" className="mt-6 inline-block">
          <Button variant="quiet">Back to what's on</Button>
        </Link>
      </main>
    )
  }

  if (isPending) {
    return (
      <main className="mx-auto max-w-lg space-y-4 px-5 py-16">
        <Skeleton className="h-4 w-24" />
        <Skeleton className="h-10 w-2/3" />
        <Skeleton className="h-32 w-full" />
      </main>
    )
  }

  const expired = hold.status !== 'ACTIVE' || hold.secondsRemaining === 0
  const declined = pay.error instanceof ApiError ? pay.error : null

  async function onPay() {
    try {
      const booking = await pay.mutateAsync({ reservationId, paymentMethod: method })
      navigate(`/bookings/${booking.id}`, { replace: true })
    } catch {
      // Rendered below.
    }
  }

  return (
    <main className="mx-auto max-w-lg px-5 py-14 sm:py-20">
      <Eyebrow>Checkout</Eyebrow>
      <h1 className="mt-2 text-[32px] leading-[1.05]">Confirm and pay</h1>

      {expired ? (
        <div className="mt-8">
          <Notice>
            This hold has expired and the seats have gone back to the pool.
            Nothing was charged.
          </Notice>
          <Link to="/" className="mt-5 inline-block">
            <Button variant="quiet">Find another seat</Button>
          </Link>
        </div>
      ) : (
        <>
          {/* The clock is the most important thing on this page. */}
          <div className="mt-6 flex items-baseline gap-3 border-b border-[var(--color-rule)] pb-5">
            <Countdown
              expiresAt={hold.expiresAt}
              className="tnum font-display text-[30px] leading-none font-semibold"
            />
            <span className="text-[14px] text-[var(--color-ink-muted)]">
              left to complete this booking
            </span>
          </div>

          <div className="mt-6 rounded-[var(--radius-panel)] border border-[var(--color-rule)] bg-[var(--color-paper-raised)] p-5">
            <Eyebrow>Your seats</Eyebrow>
            <ul className="mt-3 divide-y divide-[var(--color-rule)]">
              {hold.seats.map((seat) => (
                <li key={seat.eventSeatId} className="flex items-center justify-between py-2.5">
                  <span className="text-[15px]">
                    <span className="font-medium">{seat.label}</span>
                    <span className="ml-2 text-[13px] text-[var(--color-ink-muted)]">
                      {seat.sectionName}
                    </span>
                  </span>
                  <span className="tnum font-mono text-[13px]">
                    {formatMoney(seat.priceCents)}
                  </span>
                </li>
              ))}
            </ul>
            <div className="mt-3 flex items-baseline justify-between border-t border-[var(--color-rule)] pt-3">
              <span className="text-[15px]">Total</span>
              <span className="tnum font-display text-[22px] font-semibold">
                {formatMoney(hold.totalCents)}
              </span>
            </div>
          </div>

          <div className="mt-6">
            <label
              htmlFor="method"
              className="font-mono text-[11px] uppercase tracking-[0.12em] text-[var(--color-ink-muted)]"
            >
              Payment method
            </label>
            <select
              id="method"
              value={method}
              onChange={(e) => setMethod(e.target.value)}
              className="mt-1.5 w-full rounded-[var(--radius-control)] border border-[var(--color-rule)]
                bg-[var(--color-paper-raised)] px-3 py-2.5 text-[15px]"
            >
              <option value="card_visa_4242">Visa ending 4242</option>
              <option value="card_mastercard_5555">Mastercard ending 5555</option>
              <option value="decline_test">Test card that always declines</option>
            </select>
            <p className="mt-2 text-[13px] text-[var(--color-ink-faint)]">
              Simulated. No real payment provider is involved and no money moves.
            </p>
          </div>

          {declined ? (
            <div className="mt-5">
              <Notice>
                {declined.detail}
                {declined.status === 402 ? ' Your seats are still held — try another card.' : ''}
              </Notice>
            </div>
          ) : null}

          <Button
            onClick={() => void onPay()}
            disabled={pay.isPending}
            className="mt-6 w-full"
          >
            {pay.isPending ? 'Taking payment…' : `Pay ${formatMoney(hold.totalCents)}`}
          </Button>
        </>
      )}
    </main>
  )
}
