/**
 * API client.
 *
 * The backend speaks RFC 9457 problem+json for every failure, so this parses
 * that shape once and hands the rest of the app a typed error instead of making
 * each caller re-read response bodies.
 */

export interface FieldError {
  field: string
  message: string
}

/** A failure the API reported deliberately. */
export class ApiError extends Error {
  readonly status: number
  readonly type: string
  readonly title: string
  readonly detail: string
  readonly fieldErrors: FieldError[]
  /** Extension members, e.g. unavailableSeatIds on a seat conflict. */
  readonly extras: Record<string, unknown>

  constructor(status: number, body: Record<string, unknown> | null) {
    const detail =
      (body?.detail as string) ?? 'Something went wrong. Try again.'
    super(detail)
    this.name = 'ApiError'
    this.status = status
    this.type = (body?.type as string) ?? 'about:blank'
    this.title = (body?.title as string) ?? 'Request failed'
    this.detail = detail
    this.fieldErrors = (body?.errors as FieldError[]) ?? []
    this.extras = body ?? {}
  }

  /** Message for a specific form field, if the server flagged one. */
  fieldMessage(field: string): string | undefined {
    return this.fieldErrors.find((e) => e.field === field)?.message
  }
}

const ACCESS_KEY = 'seatflow.accessToken'
const REFRESH_KEY = 'seatflow.refreshToken'

/*
 * Tokens live in localStorage. The correct answer is an httpOnly cookie for the
 * refresh token, which the backend does not issue yet - noted rather than
 * pretended otherwise. Access tokens are short-lived, and refresh tokens rotate
 * with replay detection, which limits the blast radius but does not remove it.
 */
export const tokens = {
  access: () => localStorage.getItem(ACCESS_KEY),
  refresh: () => localStorage.getItem(REFRESH_KEY),
  set(access: string, refresh: string) {
    localStorage.setItem(ACCESS_KEY, access)
    localStorage.setItem(REFRESH_KEY, refresh)
  },
  clear() {
    localStorage.removeItem(ACCESS_KEY)
    localStorage.removeItem(REFRESH_KEY)
  },
}

/** Fires when refresh fails, so the app can drop back to signed-out state. */
type SessionEndedListener = () => void
let onSessionEnded: SessionEndedListener = () => {}
export function setSessionEndedHandler(fn: SessionEndedListener) {
  onSessionEnded = fn
}

async function readBody(response: Response): Promise<Record<string, unknown> | null> {
  const text = await response.text()
  if (!text) return null
  try {
    return JSON.parse(text) as Record<string, unknown>
  } catch {
    return null
  }
}

/*
 * Single-flight refresh. Several queries can 401 at the same moment; without
 * this they would each POST /auth/refresh, and the backend treats a replayed
 * refresh token as theft and revokes the whole family. So the first caller
 * refreshes and the rest await the same promise.
 */
let refreshInFlight: Promise<boolean> | null = null

async function refreshTokens(): Promise<boolean> {
  const refreshToken = tokens.refresh()
  if (!refreshToken) return false

  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const response = await fetch('/api/v1/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken }),
        })
        if (!response.ok) return false
        const body = (await response.json()) as {
          accessToken: string
          refreshToken: string
        }
        tokens.set(body.accessToken, body.refreshToken)
        return true
      } catch {
        return false
      } finally {
        // Cleared on the next tick so concurrent awaiters all see this result.
        setTimeout(() => {
          refreshInFlight = null
        }, 0)
      }
    })()
  }
  return refreshInFlight
}

interface RequestOptions {
  method?: string
  body?: unknown
  /** Send the bearer token. Off for public reads and the auth endpoints. */
  auth?: boolean
  headers?: Record<string, string>
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, auth = false, headers = {} } = options

  const send = async (): Promise<Response> => {
    const requestHeaders: Record<string, string> = { ...headers }
    if (body !== undefined) requestHeaders['Content-Type'] = 'application/json'

    const accessToken = tokens.access()
    if (auth && accessToken) {
      requestHeaders.Authorization = `Bearer ${accessToken}`
    }

    return fetch(`/api${path}`, {
      method,
      headers: requestHeaders,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  }

  let response = await send()

  // One retry, and only when we actually hold a refresh token.
  if (response.status === 401 && auth && tokens.refresh()) {
    const refreshed = await refreshTokens()
    if (refreshed) {
      response = await send()
    } else {
      tokens.clear()
      onSessionEnded()
    }
  }

  if (!response.ok) {
    throw new ApiError(response.status, await readBody(response))
  }

  if (response.status === 204) return undefined as T
  return (await readBody(response)) as T
}
