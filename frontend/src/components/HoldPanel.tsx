import { useEffect, useState } from 'react'
import { formatMoney } from '../lib/format'
import { Button } from './ui'
import type { ReservationResponse } from '../lib/types'

/**
 * Seconds left on a hold, recomputed from the absolute expiry rather than
 * decremented. A decremented counter drifts whenever the tab is backgrounded
 * and the interval is throttled, and this number decides whether the user still
 * has their seats.
 */
function useCountdown(expiresAt: string): number {
  const [remaining, setRemaining] = useState(() => secondsUntil(expiresAt))

  useEffect(() => {
    setRemaining(secondsUntil(expiresAt))
    const id = setInterval(() => setRemaining(secondsUntil(expiresAt)), 1000)
    return () => clearInterval(id)
  }, [expiresAt])

  return remaining
}

function secondsUntil(iso: string): number {
  return Math.max(0, Math.round((new Date(iso).getTime() - Date.now()) / 1000))
}

function formatClock(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

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
  const remaining = useCountdown(reservation.expiresAt)
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
              className={`tnum font-display text-[30px] leading-none font-semibold tabular-nums ${
                expired
                  ? 'text-[#e8907c]'
                  : urgent
                    ? 'text-[#e8907c]'
                    : 'text-[var(--color-brass-bright)]'
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
            <p className="mt-1 text-[12px] text-[var(--color-house-muted)]">
              {expired
                ? 'These seats have gone back to the pool. Choose again to try for them.'
                : 'Checkout is not built yet, so these seats return to the pool when the timer runs out.'}
            </p>
          </div>

          <Button variant="quiet" tone="house" onClick={onRelease} disabled={releasing}>
            {releasing ? 'Releasing…' : expired ? 'Start over' : 'Release seats'}
          </Button>
        </div>
      </div>
    </div>
  )
}
