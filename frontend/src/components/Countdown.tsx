import { useEffect, useState } from 'react'

/**
 * Seconds left, recomputed from an absolute expiry rather than decremented.
 *
 * A decremented counter drifts whenever the tab is backgrounded and its timer
 * is throttled, and this number decides whether the user still has their seats.
 * Recomputing means a tab that was asleep for five minutes shows the truth on
 * the first tick after it wakes.
 */
export function useSecondsRemaining(expiresAt: string): number {
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

export function formatClock(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

/** Turns red inside the last minute, which is when it starts to matter. */
export function Countdown({
  expiresAt,
  className = '',
}: {
  expiresAt: string
  className?: string
}) {
  const remaining = useSecondsRemaining(expiresAt)
  const urgent = remaining <= 60

  return (
    <span
      className={`${className} ${urgent ? 'text-[var(--color-alarm)]' : ''}`}
      aria-live={urgent ? 'polite' : 'off'}
    >
      {formatClock(remaining)}
    </span>
  )
}
