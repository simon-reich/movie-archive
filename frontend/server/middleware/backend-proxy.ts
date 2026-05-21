import { proxyRequest } from 'h3'

// In Docker, BACKEND_INTERNAL_URL=http://backend:8080 lets Nuxt SSR reach the
// backend directly (bypassing Caddy, which isn't reachable from inside the
// frontend container). Without this, SSR $fetch('/api/...') hits localhost:80
// which is not listening inside the container and returns 502.
// In dev mode, BACKEND_INTERNAL_URL is unset — devProxy handles /api → localhost:8080.
export default defineEventHandler(async (event) => {
  const backendUrl = process.env.BACKEND_INTERNAL_URL
  if (!backendUrl || !event.path.startsWith('/api/')) return

  const targetPath = event.path.replace(/^\/api/, '')
  return proxyRequest(event, `${backendUrl}${targetPath}`)
})
