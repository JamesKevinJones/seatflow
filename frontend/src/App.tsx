import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { SiteHeader } from './components/SiteHeader'
import { BookingConfirmation } from './routes/BookingConfirmation'
import { Catalogue } from './routes/Catalogue'
import { Checkout } from './routes/Checkout'
import { EventDetail } from './routes/EventDetail'
import { MyBookings } from './routes/MyBookings'
import { Register } from './routes/Register'
import { SeatSelection } from './routes/SeatSelection'
import { SignIn } from './routes/SignIn'

/*
 * Every route here is public. Browsing needs no account; only holding a seat
 * does, and that check lives at the point of action in SeatSelection so a
 * visitor can get all the way to a chosen seat before being asked to sign in.
 */
export default function App() {
  const location = useLocation()
  // Seat selection is the only route that runs with the house lights down.
  const inAuditorium = /^\/events\/[^/]+\/seats$/.test(location.pathname)

  return (
    <div
      className={
        inAuditorium
          ? 'min-h-dvh bg-[var(--color-house)] text-[var(--color-house-text)]'
          : 'min-h-dvh bg-[var(--color-paper)] text-[var(--color-ink)]'
      }
    >
      <SiteHeader tone={inAuditorium ? 'house' : 'paper'} />
      <Routes>
        <Route path="/" element={<Catalogue />} />
        <Route path="/events/:eventId" element={<EventDetail />} />
        <Route path="/events/:eventId/seats" element={<SeatSelection />} />
        <Route path="/checkout/:reservationId" element={<Checkout />} />
        <Route path="/bookings" element={<MyBookings />} />
        <Route path="/bookings/:bookingId" element={<BookingConfirmation />} />
        <Route path="/sign-in" element={<SignIn />} />
        <Route path="/register" element={<Register />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </div>
  )
}
