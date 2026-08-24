/** Response shapes from the backend. Kept in step with the Java DTOs. */

export interface UserResponse {
  id: string
  email: string
  fullName: string
  roles: string[]
}

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  accessTokenExpiresAt: string
  user: UserResponse
}

export interface Availability {
  total: number
  available: number
  reserved: number
  booked: number
}

export interface VenueSummary {
  id: string
  name: string
  city: string
  country: string
  timezone: string
}

export interface EventSummary {
  id: string
  name: string
  slug: string
  category: string
  posterUrl: string | null
  startsAt: string
  status: string
  venueName: string
  venueCity: string
  availableSeats: number
  lowestPriceCents: number
}

export interface EventDetail {
  id: string
  name: string
  slug: string
  description: string | null
  category: string
  posterUrl: string | null
  startsAt: string
  endsAt: string
  salesStartAt: string | null
  salesEndAt: string | null
  status: string
  onSale: boolean
  reservationHoldSeconds: number
  venue: VenueSummary
  availability: Availability
}

export type SeatStatus = 'AVAILABLE' | 'RESERVED' | 'BOOKED'

export interface SeatMapSeat {
  /** The EventSeat id. This is what a reservation request sends. */
  id: string
  rowLabel: string
  seatNumber: number
  label: string
  status: SeatStatus
  priceCents: number
  x: number | null
  y: number | null
}

export interface SeatMapSection {
  sectionId: string
  name: string
  displayOrder: number
  seats: SeatMapSeat[]
}

export interface SeatMap {
  eventId: string
  eventName: string
  availability: Availability
  sections: SeatMapSection[]
}

/** Spring Data page envelope. */
export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
  first: boolean
  last: boolean
}

export interface ReservedSeat {
  eventSeatId: string
  priceCents: number
}

export interface ReservationResponse {
  id: string
  eventId: string
  status: 'ACTIVE' | 'EXPIRED' | 'CANCELLED' | 'COMPLETED'
  /** Absolute, so the countdown does not drift with request latency. */
  expiresAt: string
  secondsRemaining: number
  totalCents: number
  seats: ReservedSeat[]
}
