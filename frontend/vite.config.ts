import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

/*
 * fs.strict is off for one specific reason.
 *
 * This project lives under "Kevin codes", which contains a space. The Claude
 * Code preview launcher cannot pass a path with a space, so it starts the dev
 * server through the 8.3 short form (C:/Users/kj638/KEVINC~1/...). Vite keeps
 * that spelling for request ids but normalises its serving allow list to the
 * real long path, so index.html fails to match its own allow entry and every
 * request returns a bare 403.
 *
 * Listing both spellings in fs.allow does not help - the entries are realpathed
 * before comparison. Pinning `root` fixes index.html but then breaks the
 * /@vite/client URL. Turning off the allow list removes the whole class of
 * mismatch. This affects the development server only; `vite build` output is
 * unchanged, and the server binds to localhost.
 */
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    fs: { strict: false },
    // Same-origin in development, so the browser never makes a cross-origin
    // request and CORS does not come into it. The backend still needs a CORS
    // policy for a real deployment, where the two are served from different
    // origins - that is deliberately not configured yet.
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
      // The live seat feed. ws:true is required - without it Vite proxies the
      // handshake as plain HTTP and the upgrade never completes.
      '/ws': {
        target: 'ws://127.0.0.1:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
})
