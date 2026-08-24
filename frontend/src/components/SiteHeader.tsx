import { Link, NavLink } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { Button } from './ui'

/**
 * The header changes tone with the room. On paper routes it is light; inside
 * the auditorium it goes dark with the rest of the house, so entering seat
 * selection reads as walking into the room rather than loading another page.
 */
export function SiteHeader({ tone = 'paper' }: { tone?: 'paper' | 'house' }) {
  const { user, signOut, loading } = useAuth()
  const house = tone === 'house'

  const border = house ? 'border-[var(--color-house-rule)]' : 'border-[var(--color-rule)]'
  const bg = house ? 'bg-[var(--color-house)]' : 'bg-[var(--color-paper)]/85'
  const text = house ? 'text-[var(--color-house-text)]' : 'text-[var(--color-ink)]'
  const muted = house ? 'text-[var(--color-house-muted)]' : 'text-[var(--color-ink-muted)]'

  return (
    <header
      className={`sticky top-0 z-40 border-b ${border} ${bg} ${text} backdrop-blur-md
        transition-colors duration-300 ease-[var(--ease-enter)]`}
    >
      <div className="mx-auto flex h-14 max-w-6xl items-center gap-6 px-5">
        <Link to="/" className="flex items-baseline gap-2" aria-label="SeatFlow home">
          {/* The mark: two glyphs, one lit and one dark - the whole product in
              two characters. */}
          <span aria-hidden className="flex items-center gap-[3px]">
            <span className="block h-3 w-3 rounded-[2px] bg-[var(--color-brass)]" />
            <span
              className={`block h-3 w-3 rounded-[2px] ${
                house ? 'bg-white/12' : 'bg-[var(--color-ink)]/15'
              }`}
            />
          </span>
          <span className="font-display text-[19px] font-semibold tracking-[-0.03em]">
            SeatFlow
          </span>
        </Link>

        <nav className="ml-2 hidden sm:block">
          <NavLink
            to="/"
            end
            className={({ isActive }) =>
              `text-sm transition-colors duration-150 ${
                isActive ? text : `${muted} hover:${house ? 'text-white' : 'text-[var(--color-ink)]'}`
              }`
            }
          >
            What's on
          </NavLink>
        </nav>

        <div className="ml-auto flex items-center gap-3">
          {loading ? null : user ? (
            <>
              <span className={`hidden text-sm sm:inline ${muted}`}>{user.fullName}</span>
              <Button variant="quiet" tone={tone} onClick={() => void signOut()}>
                Sign out
              </Button>
            </>
          ) : (
            <>
              <Link
                to="/sign-in"
                className={`text-sm ${muted} transition-colors duration-150 hover:${
                  house ? 'text-white' : 'text-[var(--color-ink)]'
                }`}
              >
                Sign in
              </Link>
              <Link to="/register">
                <Button>Create account</Button>
              </Link>
            </>
          )}
        </div>
      </div>
    </header>
  )
}
