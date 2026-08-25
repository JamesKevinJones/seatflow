import { Link } from 'react-router-dom'
import { formatMoney } from '../lib/format'
import { formatClock, useSecondsRemaining } from './Countdown'
import { Button } from './ui'
import type { ReservationResponse } from '../lib/types'

/**
 * The state after a successful hold: what you have, what it costs, and how long
 * you have to decide.
 *
 * The countdown is the loudest thing here on purpose. A hold that quietly
 * expires while someone reads the page is the worst outcome this screen can
 * produce, so the time is the largest element and it turns red near the end.
 */
export function HoldPanel({
  reservation,
  seatLabels,
  onRelease,
  releasing,
}: {
  reservation: ReservationResponse
  seatLabels: string[]
  onRelease: () => void
  releasing: boolean
}) {
  const remaining = useSecondsRemaining(reservation.expiresAt)
  const expired = remaining === 0
  const urgent = remaining > 0 && remaining <= 60

  return (
    <div
      role="status"
      className="fixed inset-x-0 bottom-0 z-30 border-t border-[var(--color-house-rule)]
        bg-[var(--color-house-raised)] shadow-[0_-8px_32px_rgba(0,0,0,0.35)]"
    >
      <div className="mx-auto max-w-6xl px-5 py-4">
        <div className="flex flex-wrap items-center gap-x-8 gap-y-3">
          <div>
            <div className="font-mono text-[10px] uppercase tracking-[0.14em] text-[var(--color-house-muted)]">
              {expired ? 'Hold expired' : 'Held for'}
            </div>
            <div
              className={`tnum font-display text-[30px] leading-none font-semibold ${
                expired || urgent ? 'text-[#e8907c]' : 'text-[var(--color-brass-bright)]'
              }`}
            >
              {formatClock(remaining)}
            </div>
          </div>

          <div className="min-w-0 flex-1">
            <div className="font-mono text-[10px] uppercase tracking-[0.14em] text-[var(--color-house-muted)]">
              {seatLabels.length} {seatLabels.length === 1 ? 'seat' : 'seats'} ·{' '}
              {formatMoney(reservation.totalCents)}
            </div>
            <div className="mt-0.5 truncate text-[14px] text-[var(--color-house-text)]">
              {seatLabels.join(', ')}
            </div>
            {expired ? (
              <p className="mt-1 text-[12px] text-[var(--color-house-muted)]">
                These seats have gone back to the pool. Choose again to try for them.
              </p>
            ) : null}
          </div>

          <div className="flex items-center gap-2">
            <Button variant="ghost" tone="house" onClick={onRelease} disabled={releasing}>
              {releasing ? 'Releasing…' : expired ? 'Start over' : 'Release'}
            </Button>
            {expired ? null : (
              <Link to={`/checkout/${reservation.id}`}>
                <Button>Continue to payment</Button>
              </Link>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
