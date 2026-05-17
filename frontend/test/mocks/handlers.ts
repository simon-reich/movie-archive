import { http, HttpResponse } from 'msw'
import { authHandlers } from './handlers/auth'
import { settingsHandlers } from './handlers/settings'
import { moviesHandlers } from './handlers/movies'
import { searchHandlers } from './handlers/search'

/**
 * Global MSW request handlers.
 * Feature-specific handlers can be added inline in tests via server.use().
 */
export const handlers = [
  http.get('/api/actuator/health', () => {
    return HttpResponse.json({ status: 'UP' })
  }),
  ...authHandlers,
  ...settingsHandlers,
  ...moviesHandlers,
  ...searchHandlers,
]
