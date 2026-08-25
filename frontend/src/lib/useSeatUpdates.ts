import { useEffect, useRef, useState } from 'react'
import { Client } from '@stomp/stompjs'
import { useQueryClient } from '@tanstack/react-query'
import { queryKeys } from './queries'
import type { SeatMap, SeatStatus } from './types'

interface SeatUpdate {
  eventId: string
  seatIds: string[]
  status: SeatStatus
  /** Monotonic per event. A gap means a message was missed. */
  seq: number
  at: string
}

/**
 * Keeps an open seat map in step with everyone else's actions.
 *
 * Deltas are applied straight into the TanStack cache, so a seat someone else
 * takes greys out without a refetch. Two rules keep that from drifting:
 *
 *  - **A gap in `seq` means resync.** Rather than applying a delta to a map that
 *    is already missing a change, the whole map is refetched. Deltas are an
 *    optimisation; REST is the truth.
 *  - **Reconnecting means resync**, for the same reason: anything that happened
 *    while the socket was down was never delivered.
 *
 * @returns whether the live feed is currently connected, so the UI can say so
 *          rather than silently showing a map that has stopped updating.
 */
export function useSeatUpdates(eventId: string): boolean {
  const queryClient = useQueryClient()
  const [connected, setConnected] = useState(false)
  const lastSeq = useRef<number | null>(null)

  useEffect(() => {
    if (!eventId) return

    lastSeq.current = null

    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const client = new Client({
      brokerURL: `${protocol}://${window.location.host}/ws`,
      reconnectDelay: 3000,
      // Silence the default console logging; failures surface as `connected`.
      debug: () => {},
      onConnect: () => {
        setConnected(true)

        // Anything that happened while disconnected was never delivered, so
        // start from a known-good map rather than an assumed one.
        void queryClient.invalidateQueries({ queryKey: queryKeys.seatMap(eventId) })

        client.subscribe(`/topic/events/${eventId}/seats`, (message) => {
          const update = JSON.parse(message.body) as SeatUpdate

          const previous = lastSeq.current
          lastSeq.current = update.seq

          if (previous !== null && update.seq !== previous + 1) {
            // Missed something. Applying this delta would build on a map that
            // is already wrong.
            void queryClient.invalidateQueries({ queryKey: queryKeys.seatMap(eventId) })
            void queryClient.invalidateQueries({ queryKey: queryKeys.event(eventId) })
            return
          }

          applyDelta(queryClient, eventId, update)
        })
      },
      onWebSocketClose: () => setConnected(false),
      onStompError: () => setConnected(false),
    })

    client.activate()
    return () => {
      setConnected(false)
      void client.deactivate()
    }
  }, [eventId, queryClient])

  return connected
}

/** Rewrites just the affected seats, leaving the rest of the map untouched. */
function applyDelta(
  queryClient: ReturnType<typeof useQueryClient>,
  eventId: string,
  update: SeatUpdate,
) {
  const changed = new Set(update.seatIds)

  queryClient.setQueryData<SeatMap>(queryKeys.seatMap(eventId), (current) => {
    if (!current) return current

    let touched = 0
    const sections = current.sections.map((section) => ({
      ...section,
      seats: section.seats.map((seat) => {
        if (!changed.has(seat.id) || seat.status === update.status) return seat
        touched += 1
        return { ...seat, status: update.status }
      }),
    }))

    if (touched === 0) return current

    // Recount rather than adjusting by the delta size: the counts then cannot
    // drift away from the seats they are supposed to describe.
    const all = sections.flatMap((section) => section.seats)
    return {
      ...current,
      sections,
      availability: {
        total: all.length,
        available: all.filter((seat) => seat.status === 'AVAILABLE').length,
        reserved: all.filter((seat) => seat.status === 'RESERVED').length,
        booked: all.filter((seat) => seat.status === 'BOOKED').length,
      },
    }
  })
}
