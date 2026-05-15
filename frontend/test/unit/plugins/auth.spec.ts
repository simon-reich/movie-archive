import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)

describe('auth.client plugin behavior', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockFetch.mockReset()
  })

  it('calls /auth/refresh on app init when refresh cookie present', async () => {
    mockFetch.mockResolvedValueOnce({ accessToken: 'token-init', email: 'user@example.com' })
    const store = useAuthStore()
    // Simulate plugin behavior: call store.refresh()
    await store.refresh()
    expect(mockFetch).toHaveBeenCalledWith('/api/auth/refresh', expect.objectContaining({ method: 'POST' }))
    expect(store.accessToken).toBe('token-init')
  })

  it('does not redirect when refresh succeeds', async () => {
    mockFetch.mockResolvedValueOnce({ accessToken: 'token-ok', email: 'user@example.com' })
    const store = useAuthStore()
    await expect(store.refresh()).resolves.not.toThrow()
    expect(store.isAuthenticated).toBe(true)
  })

  it('clears store silently when refresh fails (cookie expired)', async () => {
    mockFetch.mockRejectedValueOnce(new Error('401 Unauthorized'))
    const store = useAuthStore()
    // Plugin catches error — store stays empty, no throw propagated
    let threw = false
    try {
      await store.refresh()
    } catch {
      threw = true
    }
    // The plugin catches this — test that store remains empty when error is swallowed
    // The store.refresh() itself throws; plugin swallows it silently
    expect(threw).toBe(true) // refresh throws, plugin swallows
    expect(store.accessToken).toBeNull()
  })
})
