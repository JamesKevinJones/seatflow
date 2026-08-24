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
 * Holds seats, or fails having held none.
 *
 * A 409 carries `unavailableSeatIds`, naming the seats lost to someone else so
 * the map can grey them out and keep the rest of the selection.
 */
export function useReserveSeats(eventId: string) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (seatIds: string[]) =>
      request<ReservationResponse>('/v1/reservations', {
        method: 'POST',
        auth: true,
        body: { eventId, seatIds },
        // One key per attempt. A retry of *this* request returns the same
        // reservation instead of taking a second set of seats.
        headers: { 'Idempotency-Key': crypto.randomUUID() },
      }),
    onSettled: () => {
      // Refetch on failure too: a conflict means the map is out of date, and
      // that is exactly when the user needs to see the truth.
      void queryClient.invalidateQueries({ queryKey: queryKeys.seatMap(eventId) })
      void queryClient.invalidateQueries({ queryKey: queryKeys.event(eventId) })
    },
  })
}

/** Releases a hold early, returning the seats to the pool. */
export function useCancelReservation(eventId: string) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (reservationId: string) =>
      request<ReservationResponse>(`/v1/reservations/${reservationId}`, {
        method: 'DELETE',
        auth: true,
      }),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.seatMap(eventId) })
      void queryClient.invalidateQueries({ queryKey: queryKeys.event(eventId) })
    },
  })
}
