import { http, HttpResponse } from 'msw'

export const settingsHandlers = [
  // GET /api/settings/api-keys — returns plaintext keys (D-03)
  http.get('/api/settings/api-keys', () => {
    return HttpResponse.json({ tmdb: 'test-tmdb-key-plaintext', omdb: null })
  }),

  // DELETE /api/settings/api-keys/:provider — removes key; 200 OK
  http.delete('/api/settings/api-keys/:provider', () => {
    return new HttpResponse(null, { status: 200 })
  }),

  // PUT /api/settings/api-keys/:provider — validates key; 422 if invalid
  http.put('/api/settings/api-keys/:provider', async ({ request }) => {
    const body = await request.json() as { key: string }
    if (body.key === 'invalid-key') {
      return HttpResponse.json({ message: 'Invalid TMDB API key — check your key and try again.' }, { status: 422 })
    }
    if (body.key === 'invalid-omdb-key') {
      return HttpResponse.json({ message: 'Invalid OMDB API key — check your key and try again.' }, { status: 422 })
    }
    return HttpResponse.json({ message: 'API key saved.' })
  }),

  // POST /api/settings/password — 400 if wrong current password
  http.post('/api/settings/password', async ({ request }) => {
    const body = await request.json() as { currentPassword: string; newPassword: string }
    if (body.currentPassword === 'wrong-password') {
      return HttpResponse.json({ message: 'Current password is incorrect.' }, { status: 400 })
    }
    return new HttpResponse(null, { status: 200 })
  }),

  // POST /api/settings/email — always 200 (enumeration protection)
  http.post('/api/settings/email', () => {
    return new HttpResponse(null, { status: 200 })
  }),

  // GET /api/users/me — returns the caller's own id
  http.get('/api/users/me', () => {
    return HttpResponse.json({ id: 'settings-user-id' })
  }),

  // POST /api/admin/wiki-reload/:userId — 202 Accepted (existing Phase 8 endpoint)
  http.post('/api/admin/wiki-reload/:userId', () => {
    return new HttpResponse(null, { status: 202 })
  }),
]
