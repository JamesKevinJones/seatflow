import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from './api'
import type {
  EventDetail,
  EventSummary,
  Page,
  ReservationResponse,
  SeatMap,
} from './types'

export const queryKeys = {
  events: (category: string | null) => ['events', category] as const,
  event: (id: string) => ['event', id] as const,
  seatMap: (id: string) => ['seatMap', id] as const,
}

export function useEvents(category: string | null) {
  return useQuery({
    queryKey: queryKeys.events(category),
    queryFn: () => {
      const params = new URLSearchParams({ size: '20', sort: 'startsAt,asc' })
      if (category) params.set('category', category)
      return request<Page<EventSummary>>(`/v1/events?${params}`)
    },
  })
}

export function useEvent(eventId: string) {
  return useQuery({
    queryKey: queryKeys.event(eventId),
    queryFn: () => request<EventDetail>(`/v1/events/${eventId}`),
    enabled: Boolean(eventId),
  })
}

export function useSeatMap(eventId: string) {
  return useQuery({
    queryKey: queryKeys.seatMap(eventId),
    queryFn: () => request<SeatMap>(`/v1/events/${eventId}/seats`),
    enabled: Boolean(eventId),
    /*
     * Seat state is contended, so a cached map goes stale the moment someone
     * else reserves. Refetching on focus is the cheap approximation until the
     * WebSocket feed lands in Phase 6 and pushes deltas instead.
     */
    staleTime: 10_000,
    refetchOnWindowFocus: true,
  })
}

/**
 * Reserve seats.
 *
 * The endpoint is Phase 3 and does not exist yet, so this currently fails with
 * a 404. The request shape is the one from the Phase 0 design, so it starts
 * working the moment the backend lands - and until then the UI shows the real
 * error rather than pretending the hold succeeded.
 */
export function useReserveSeats(eventId: string) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (seatIds: string[]) =>
      request<ReservationResponse>('/v1/reservations', {
        method: 'POST',
        auth: true,
        body: { eventId, seatIds },
        // Makes an accidental double-submit return the same reservation
        // instead of creating a second one.
        headers: { 'Idempotency-Key': crypto.randomUUID() },
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.seatMap(eventId) })
      void queryClient.invalidateQueries({ queryKey: queryKeys.event(eventId) })
    },
  })
}
