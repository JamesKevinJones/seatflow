import { memo } from 'react'
import { formatMoneyShort } from '../lib/format'
import type { SeatMapSeat } from '../lib/types'

/**
 * One seat.
 *
 * The colour rule is the whole idea of this screen: a seat you can have is lit,
 * a seat you cannot is dark. Nothing else needs a legend to understand, though
 * one is provided for the states that differ only in why they are unavailable.
 *
 * Memoised because a large venue renders thousands of these and selecting one
 * seat should not re-render the rest.
 */
export const Seat = memo(function Seat({
  seat,
  selected,
  onToggle,
}: {
  seat: SeatMapSeat
  selected: boolean
  onToggle: (seat: SeatMapSeat) => void
}) {
  const taken = seat.status !== 'AVAILABLE'

  const appearance = selected
    ? // Chosen: full brass, unmistakable against everything else.
      'bg-[var(--color-brass)] text-[#1a1206] border-[var(--color-brass-bright)] ' +
      'shadow-[0_0_0_1px_var(--color-brass-bright),0_0_12px_rgba(230,168,74,0.35)]'
    : seat.status === 'AVAILABLE'
      ? // Lit: available. A warm, low glow rather than a flat fill.
        'bg-[rgba(200,137,47,0.14)] border-[rgba(200,137,47,0.45)] text-transparent ' +
        'hover:bg-[rgba(200,137,47,0.28)] hover:border-[var(--color-brass)]'
      : seat.status === 'RESERVED'
        ? // Held by someone else. Present but cold - it may come back.
          'bg-white/[0.055] border-white/12 text-transparent cursor-not-allowed'
        : // Sold. Effectively part of the floor.
          'bg-white/[0.02] border-transparent text-transparent cursor-not-allowed'

  const stateWord =
    seat.status === 'AVAILABLE'
      ? selected
        ? 'selected'
        : 'available'
      : seat.status === 'RESERVED'
        ? 'held by someone else'
        : 'sold'

  return (
    <button
      type="button"
      disabled={taken}
      aria-pressed={selected}
      aria-label={`Row ${seat.rowLabel} seat ${seat.seatNumber}, ${formatMoneyShort(
        seat.priceCents,
      )}, ${stateWord}`}
      title={`${seat.label} · ${formatMoneyShort(seat.priceCents)} · ${stateWord}`}
      onClick={() => onToggle(seat)}
      className={`h-[26px] w-[26px] rounded-[var(--radius-seat)] border text-[9px] font-medium
        transition-[background-color,border-color,box-shadow,transform] duration-150
        ease-[var(--ease-snap)] ${selected ? 'scale-[1.08]' : ''} ${appearance}`}
    >
      {seat.seatNumber}
    </button>
  )
})
