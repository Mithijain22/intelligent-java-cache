import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Proxies /cache, /benchmark and /ws during `npm run dev` so the frontend
// can just call relative paths (e.g. fetch('/cache/stats')) without hardcoding
// http://localhost:8080 everywhere or fighting CORS in development.
// In production you'd typically serve the built frontend from behind the
// same reverse proxy as the API, or set VITE_API_BASE_URL -- see api.js.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/cache': 'http://localhost:8080',
      '/benchmark': 'http://localhost:8080',
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true
      }
    }
  }
})
