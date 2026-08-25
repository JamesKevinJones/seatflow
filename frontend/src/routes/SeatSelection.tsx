import { useCallback, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useCancelReservation, useEvent, useReserveSeats, useSeatMap } from '../lib/queries'
import { useSeatUpdates } from '../lib/useSeatUpdates'
import { formatMoney } from '../lib/format'
import { Button } from '../components/ui'
import { Seat } from '../components/Seat'
import { HoldPanel } from '../components/HoldPanel'
import type { ReservationResponse, SeatMapSeat, SeatMapSection } from '../lib/types'

/** Most venues cap a single order. Eight is the usual house limit. */
const MAX_SEATS = 8

export function SeatSelection() {
  const { eventId = '' } = useParams()
  const navigate = useNavigate()
  const { user } = useAuth()

  const { data: event } = useEvent(eventId)
  const { data: seatMap, isPending, error, refetch, isFetching } = useSeatMap(eventId)
  const reserve = useReserveSeats(eventId)
  const cancel = useCancelReservation(eventId)
  // Live deltas from everyone else looking at this map.
  const live = useSeatUpdates(eventId)

  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [limitHit, setLimitHit] = useState(false)
  const [reservation, setReservation] = useState<ReservationResponse | null>(null)
  const [heldLabels, setHeldLabels] = useState<string[]>([])

  const seatsById = useMemo(() => {
    const map = new Map<string, SeatMapSeat>()
    seatMap?.sections.forEach((section) =>
      section.seats.forEach((seat) => map.set(seat.id, seat)),
    )
    return map
  }, [seatMap])

  const selectedSeats = useMemo(
    () => selectedIds.map((id) => seatsById.get(id)).filter((s): s is SeatMapSeat => Boolean(s)),
    [selectedIds, seatsById],
  )

  const totalCents = selectedSeats.reduce((sum, seat) => sum + seat.priceCents, 0)

  const toggleSeat = useCallback(
    (seat: SeatMapSeat) => {
      // A failure from a previous attempt describes a selection that no longer
      // exists. Leaving it up would also mask the seat-limit message, which is
      // about what the user is doing right now.
      if (reserve.error) reserve.reset()

      setSelectedIds((current) => {
        if (current.includes(seat.id)) {
          setLimitHit(false)
          return current.filter((id) => id !== seat.id)
        }
        if (current.length >= MAX_SEATS) {
          setLimitHit(true)
          return current
        }
        return [...current, seat.id]
      })
    },
    [reserve],
  )

  async function onReserve() {
    if (!user) {
      // Come back here afterwards, selection intact in the URL-less state is
      // lost - so send them back to the same screen to re-pick rather than
      // pretending the hold survived a round trip through sign-in.
      navigate('/sign-in', { state: { from: { pathname: `/events/${eventId}/seats` } } })
      return
    }
    try {
      const held = await reserve.mutateAsync(selectedIds)
      // Keep the labels: the seat map refetches immediately and these seats now
      // read as RESERVED, so the hold panel could not derive them afterwards.
      setHeldLabels(selectedSeats.map((seat) => seat.label))
      setReservation(held)
      setSelectedIds([])
    } catch (caught) {
      // A conflict names the seats that were lost. Drop exactly those and keep
      // the rest, so the user adjusts a selection instead of rebuilding it.
      if (caught instanceof ApiError) {
        const lost = (caught.extras.unavailableSeatIds as string[] | undefined) ?? []
        if (lost.length > 0) {
          setSelectedIds((current) => current.filter((id) => !lost.includes(id)))
        }
      }
      // The message itself is rendered from reserve.error in the tray.
    }
  }

  async function onRelease() {
    if (!reservation) return
    try {
      await cancel.mutateAsync(reservation.id)
    } finally {
      setReservation(null)
      setHeldLabels([])
    }
  }

  if (error) {
    return (
      <main className="mx-auto max-w-6xl px-5 py-20">
        <h1 className="font-display text-[26px]">Seat map unavailable</h1>
        <p className="mt-2 text-[15px] text-[var(--color-house-muted)]">
          This event isn't published, or the link is wrong.
        </p>
        <Link to="/" className="mt-6 inline-block">
          <Button variant="quiet" tone="house">
            Back to what's on
          </Button>
        </Link>
      </main>
    )
  }

  const conflictIds = (reserve.error instanceof ApiError
    ? (reserve.error.extras.unavailableSeatIds as string[] | undefined)
    : undefined) ?? []

  return (
    <main className="mx-auto max-w-6xl px-5 pb-40">
      <div className="flex flex-wrap items-end justify-between gap-4 pt-10">
        <div>
          <Link
            to={`/events/${eventId}`}
            className="font-mono text-[11px] uppercase tracking-[0.14em] text-[var(--color-house-muted)]
              transition-colors duration-150 hover:text-[var(--color-house-text)]"
          >
            ← {event?.name ?? 'Back'}
          </Link>
          <h1 className="mt-2 text-[clamp(1.8rem,4vw,2.6rem)] leading-tight text-[var(--color-house-text)]">
            Choose your seats
          </h1>
        </div>

        <div className="flex items-center gap-4">
          {/* Says plainly whether the map is updating itself. A stale map that
              looks live is worse than one that admits it is stale. */}
          <span className="flex items-center gap-2 font-mono text-[11px] uppercase tracking-[0.12em] text-[var(--color-house-muted)]">
            <span
              aria-hidden
              className={`h-1.5 w-1.5 rounded-full ${
                live ? 'bg-[var(--color-brass)]' : 'bg-white/25'
              }`}
            />
            {live ? 'Live' : 'Not live'}
          </span>
          <button
            type="button"
            onClick={() => void refetch()}
            className="font-mono text-[11px] uppercase tracking-[0.12em] text-[var(--color-house-muted)]
              transition-colors duration-150 hover:text-[var(--color-house-text)]"
          >
            {isFetching ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>

      <Legend />

      {/* The stage. Orients the map the way the room does. */}
      <div className="mt-8 mb-10">
        <div
          className="mx-auto h-[3px] w-3/5 rounded-full
            bg-[linear-gradient(to_right,transparent,var(--color-brass),transparent)]"
        />
        <p className="mt-2 text-center font-mono text-[10px] uppercase tracking-[0.3em] text-[var(--color-house-muted)]">
          Stage
        </p>
      </div>

      {isPending ? (
        <p className="py-16 text-center font-mono text-[12px] uppercase tracking-[0.14em] text-[var(--color-house-muted)]">
          Loading seat map…
        </p>
      ) : (
        <div className="flex flex-col gap-12">
          {seatMap.sections.map((section) => (
            <SectionBlock
              key={section.sectionId}
              section={section}
              selectedIds={selectedIds}
              onToggle={toggleSeat}
            />
          ))}
        </div>
      )}

      {/* One bar at a time: pick seats, or hold them. */}
      {reservation ? (
        <HoldPanel
          reservation={reservation}
          seatLabels={heldLabels}
          releasing={cancel.isPending}
          onRelease={() => void onRelease()}
        />
      ) : (
        <SelectionTray
          seats={selectedSeats}
          totalCents={totalCents}
          limitHit={limitHit}
          pending={reserve.isPending}
          error={reserve.error instanceof ApiError ? reserve.error : null}
          conflictIds={conflictIds}
          onClear={() => {
            setSelectedIds([])
            setLimitHit(false)
          }}
          onReserve={() => void onReserve()}
          signedIn={Boolean(user)}
        />
      )}
    </main>
  )
}

function SectionBlock({
  section,
  selectedIds,
  onToggle,
}: {
  section: SeatMapSection
  selectedIds: string[]
  onToggle: (seat: SeatMapSeat) => void
}) {
  // Group into rows so the map reads like a floor plan rather than a bag of seats.
  const rows = useMemo(() => {
    const byRow = new Map<string, SeatMapSeat[]>()
    section.seats.forEach((seat) => {
      const list = byRow.get(seat.rowLabel) ?? []
      list.push(seat)
      byRow.set(seat.rowLabel, list)
    })
    return [...byRow.entries()].sort(([a], [b]) => a.localeCompare(b))
  }, [section.seats])

  const free = section.seats.filter((s) => s.status === 'AVAILABLE').length

  return (
    <section>
      <div className="mb-4 flex items-baseline gap-3 border-b border-[var(--color-house-rule)] pb-2">
        <h2 className="font-display text-[15px] font-semibold text-[var(--color-house-text)]">
          {section.name}
        </h2>
        <span className="tnum font-mono text-[11px] text-[var(--color-house-muted)]">
          {free} of {section.seats.length} free
        </span>
      </div>

      {/*
        The scroller centres its content through an inner w-max block rather
        than items-center. With items-center, a row wider than the viewport is
        centred and the overflow spills equally both ways - scrollLeft: 0 still
        left the first two seats of every row off the left edge and untappable
        on a phone. A w-max child with auto margins centres when it fits and
        collapses those margins to zero when it does not.
      */}
      <div className="overflow-x-auto">
        <div className="mx-auto flex w-max flex-col gap-1.5">
        {rows.map(([rowLabel, seats]) => (
          <div key={rowLabel} className="flex items-center gap-2">
            <span className="tnum w-5 shrink-0 text-right font-mono text-[10px] text-[var(--color-house-muted)]">
              {rowLabel}
            </span>
            <div className="flex gap-1.5">
              {seats
                .slice()
                .sort((a, b) => a.seatNumber - b.seatNumber)
                .map((seat) => (
                  <Seat
                    key={seat.id}
                    seat={seat}
                    selected={selectedIds.includes(seat.id)}
                    onToggle={onToggle}
                  />
                ))}
            </div>
            <span className="w-5 shrink-0" aria-hidden />
          </div>
        ))}
        </div>
      </div>
    </section>
  )
}

function Legend() {
  const items = [
    { label: 'Free', className: 'bg-[rgba(200,137,47,0.14)] border-[rgba(200,137,47,0.45)]' },
    { label: 'Yours', className: 'bg-[var(--color-brass)] border-[var(--color-brass-bright)]' },
    { label: 'Held', className: 'bg-white/[0.055] border-white/12' },
    { label: 'Sold', className: 'bg-white/[0.02] border-transparent' },
  ]

  return (
    <div className="mt-6 flex flex-wrap items-center gap-x-5 gap-y-2">
      {items.map((item) => (
        <span key={item.label} className="flex items-center gap-2">
          <span
            aria-hidden
            className={`h-3 w-3 rounded-[2px] border ${item.className}`}
          />
          <span className="font-mono text-[11px] uppercase tracking-[0.1em] text-[var(--color-house-muted)]">
            {item.label}
          </span>
        </span>
      ))}
    </div>
  )
}

function SelectionTray({
  seats,
  totalCents,
  limitHit,
  pending,
  error,
  conflictIds,
  onClear,
  onReserve,
  signedIn,
}: {
  seats: SeatMapSeat[]
  totalCents: number
  limitHit: boolean
  pending: boolean
  error: ApiError | null
  conflictIds: string[]
  onClear: () => void
  onReserve: () => void
  signedIn: boolean
}) {
  const open = seats.length > 0

  return (
    /*
     * The slide is an inline transform rather than translate-y-* utilities.
     * Tailwind v4 drives those through a --tw-translate-y custom property,
     * which is a level of indirection worth avoiding for the one value that
     * decides whether the primary action is reachable at all.
     *
     * When closed the tray is also made inert - a hidden bar full of focusable
     * buttons is a keyboard trap.
     */
    <div
      aria-hidden={!open}
      style={{ transform: open ? 'translateY(0)' : 'translateY(100%)' }}
      className={`fixed inset-x-0 bottom-0 z-30 border-t border-[var(--color-house-rule)]
        bg-[var(--color-house-raised)]/95 backdrop-blur-md transition-transform duration-200
        ease-[var(--ease-enter)] ${open ? '' : 'pointer-events-none'}`}
    >
      <div className="mx-auto max-w-6xl px-5 py-4">
        {error ? (
          <p role="alert" className="mb-3 text-[13px] text-[#e8907c]">
            {error.detail}
            {conflictIds.length > 0
              ? ' The map has been refreshed — those seats are now shown as taken.'
              : ''}
          </p>
        ) : limitHit ? (
          <p role="status" className="mb-3 text-[13px] text-[var(--color-house-muted)]">
            {MAX_SEATS} seats is the maximum for one booking.
          </p>
        ) : null}

        <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
          <div className="min-w-0 flex-1">
            <div className="tnum font-mono text-[11px] uppercase tracking-[0.12em] text-[var(--color-house-muted)]">
              {seats.length} {seats.length === 1 ? 'seat' : 'seats'}
            </div>
            <div className="mt-0.5 truncate text-[14px] text-[var(--color-house-text)]">
              {seats.map((s) => s.label).join(', ')}
            </div>
          </div>

          <div className="text-right">
            <div className="font-mono text-[10px] uppercase tracking-[0.12em] text-[var(--color-house-muted)]">
              Total
            </div>
            <div className="tnum font-display text-[22px] leading-tight font-semibold text-[var(--color-house-text)]">
              {formatMoney(totalCents)}
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Button variant="ghost" tone="house" onClick={onClear}>
              Clear
            </Button>
            <Button onClick={onReserve} disabled={pending}>
              {pending ? 'Holding…' : signedIn ? 'Hold these seats' : 'Sign in to hold'}
            </Button>
          </div>
        </div>
      </div>
    </div>
  )
}
