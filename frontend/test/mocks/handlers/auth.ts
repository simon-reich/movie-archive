import { http, HttpResponse } from 'msw'

export const authHandlers = [
  // POST /api/auth/login
  http.post('/api/auth/login', async ({ request }) => {
    const body = await request.json() as { email: string; password: string }
    if (body.email === 'user@example.com' && body.password === 'correct-password') {
      return HttpResponse.json(
        { accessToken: 'mock-access-token-abc', email: 'user@example.com' },
        { status: 200 }
      )
    }
    if (body.email === 'unverified@example.com') {
      return HttpResponse.json({ message: 'Account not verified.' }, { status: 403 })
    }
    return HttpResponse.json({ message: 'Invalid email or password.' }, { status: 401 })
  }),

  // POST /api/auth/signup
  http.post('/api/auth/signup', async ({ request }) => {
    const body = await request.json() as { email: string; password: string }
    if (body.email === 'existing@example.com') {
      return HttpResponse.json(
        { message: 'An account with this email already exists.' },
        { status: 409 }
      )
    }
    return new HttpResponse(null, { status: 201 })
  }),

  // POST /api/auth/refresh
  http.post('/api/auth/refresh', () => {
    return HttpResponse.json(
      { accessToken: 'mock-access-token-refreshed', email: 'user@example.com' },
      { status: 200 }
    )
  }),

  // POST /api/auth/logout
  http.post('/api/auth/logout', () => {
    return new HttpResponse(null, { status: 200 })
  }),

  // POST /api/auth/verify-email
  http.post('/api/auth/verify-email', async ({ request }) => {
    const body = await request.json() as { token: string }
    if (body.token === 'valid-token') {
      return new HttpResponse(null, { status: 200 })
    }
    if (body.token === 'expired-token') {
      return HttpResponse.json({ message: 'Token expired.' }, { status: 400 })
    }
    if (body.token === 'used-token') {
      return HttpResponse.json({ message: 'Token already used.' }, { status: 400 })
    }
    return HttpResponse.json({ message: 'Invalid token.' }, { status: 400 })
  }),

  // POST /api/auth/forgot-password
  http.post('/api/auth/forgot-password', () => {
    return new HttpResponse(null, { status: 200 })
  }),

  // POST /api/auth/reset-password
  http.post('/api/auth/reset-password', async ({ request }) => {
    const body = await request.json() as { token: string; newPassword: string }
    if (body.token === 'expired-reset-token') {
      return HttpResponse.json({ message: 'Token expired.' }, { status: 400 })
    }
    if (body.token === 'used-reset-token') {
      return HttpResponse.json({ message: 'Token already used.' }, { status: 400 })
    }
    return new HttpResponse(null, { status: 200 })
  }),
]
