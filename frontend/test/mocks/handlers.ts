import { http, HttpResponse } from 'msw'

/**
 * Global MSW request handlers.
 * Feature-specific handlers can be added inline in tests via server.use().
 */
export const handlers = [
  http.get('/api/actuator/health', () => {
    return HttpResponse.json({ status: 'UP' })
  }),
]
