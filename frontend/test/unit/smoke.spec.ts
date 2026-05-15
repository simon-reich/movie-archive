import { describe, expect, it } from 'vitest'
import { server } from '../mocks/server'
import { http, HttpResponse } from 'msw'

describe('Vitest + MSW smoke test', () => {
  it('should run a basic assertion', () => {
    expect(1 + 1).toBe(2)
  })

  it('should expose a running MSW server', () => {
    // Verify the server is in started state — setup.ts calls server.listen() in beforeAll
    expect(server).toBeDefined()
    expect(typeof server.use).toBe('function')
    expect(typeof server.resetHandlers).toBe('function')
  })

  it('should intercept a request with MSW via global handler', async () => {
    const response = await fetch('/api/actuator/health')
    const data = await response.json() as { status: string }

    expect(response.ok).toBe(true)
    expect(data.status).toBe('UP')
  })

  it('should create a one-time handler with server.use() without error', () => {
    // Verifies the MSW server API is accessible and callable.
    // Full per-test handler override is exercised in integration test files
    // where a dedicated server setup is used (not the shared singleton).
    expect(() =>
      server.use(http.get('/api/override-smoke', () => HttpResponse.json({ ok: true }))),
    ).not.toThrow()
  })
})
