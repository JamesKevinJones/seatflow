/// <reference types="vite/client" />

/**
 * Build-time configuration.
 *
 * Everything here is optional and everything defaults to same-origin, because
 * same-origin is the arrangement the app is built for: the Vite dev proxy and
 * the nginx container both provide it, and neither needs any of these set.
 */
interface ImportMetaEnv {
  /**
   * Origin of the live seat feed, e.g. `https://seatflow-api.example.com`.
   *
   * Set this only when the frontend is served from a different host than the
   * backend. Static hosts rewrite HTTP but almost none proxy a WebSocket
   * upgrade, so the socket addresses the backend directly while `/api` is still
   * rewritten to it. Unset means same-origin `/ws`.
   */
  readonly VITE_WS_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
