import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { request, setSessionEndedHandler, tokens } from './api'
import type { AuthResponse, UserResponse } from './types'

interface AuthState {
  user: UserResponse | null
  /** True until the initial session check finishes, so routes do not flash. */
  loading: boolean
  isAdmin: boolean
  signIn: (email: string, password: string) => Promise<void>
  register: (email: string, password: string, fullName: string) => Promise<void>
  signOut: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserResponse | null>(null)
  const [loading, setLoading] = useState(true)

  // If a refresh fails, the session is over. Drop the user without a reload.
  useEffect(() => {
    setSessionEndedHandler(() => setUser(null))
  }, [])

  // Resume an existing session on first paint. A stored token may be expired,
  // so the answer comes from the server, not from the token's presence.
  useEffect(() => {
    let cancelled = false

    async function resume() {
      if (!tokens.access()) {
        setLoading(false)
        return
      }
      try {
        const me = await request<UserResponse>('/v1/auth/me', { auth: true })
        if (!cancelled) setUser(me)
      } catch {
        if (!cancelled) {
          tokens.clear()
          setUser(null)
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    void resume()
    return () => {
      cancelled = true
    }
  }, [])

  const adopt = useCallback((response: AuthResponse) => {
    tokens.set(response.accessToken, response.refreshToken)
    setUser(response.user)
  }, [])

  const signIn = useCallback(
    async (email: string, password: string) => {
      const response = await request<AuthResponse>('/v1/auth/login', {
        method: 'POST',
        body: { email, password },
      })
      adopt(response)
    },
    [adopt],
  )

  const register = useCallback(
    async (email: string, password: string, fullName: string) => {
      const response = await request<AuthResponse>('/v1/auth/register', {
        method: 'POST',
        body: { email, password, fullName },
      })
      adopt(response)
    },
    [adopt],
  )

  const signOut = useCallback(async () => {
    try {
      // Revokes every refresh token for this account, not just this device.
      await request<void>('/v1/auth/logout', { method: 'POST', auth: true })
    } catch {
      // Already invalid server-side. Clearing locally is still correct.
    } finally {
      tokens.clear()
      setUser(null)
    }
  }, [])

  const value = useMemo<AuthState>(
    () => ({
      user,
      loading,
      isAdmin: user?.roles.includes('ADMIN') ?? false,
      signIn,
      register,
      signOut,
    }),
    [user, loading, signIn, register, signOut],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside AuthProvider')
  return context
}
