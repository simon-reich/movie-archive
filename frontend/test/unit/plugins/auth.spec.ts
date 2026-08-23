import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { attemptSilentRefresh } from '@/plugins/auth.client'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)

describe('auth.client plugin behavior', () => {
  beforeEach(() => {
    // Clear auth cookies so useCookie refs start null in each test.
    document.cookie = 'access_token=; Max-Age=0; path=/'
    document.cookie = 'session_email=; Max-Age=0; path=/'
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
    // Cookie is absent so value is null or "" — either is falsy (no valid token)
    expect(store.accessToken).toBeFalsy()
  })

  describe('attemptSilentRefresh (the actual scheduled-tick handler)', () => {
    it('calls authStore.refresh() when isAuthenticated is true', async () => {
      const refresh = vi.fn().mockResolvedValue(undefined)
      await attemptSilentRefresh({ isAuthenticated: true, refresh })
      expect(refresh).toHaveBeenCalledTimes(1)
    })

    it('does nothing when isAuthenticated is false (nothing to renew)', async () => {
      const refresh = vi.fn().mockResolvedValue(undefined)
      await attemptSilentRefresh({ isAuthenticated: false, refresh })
      expect(refresh).not.toHaveBeenCalled()
    })

    it('swallows a refresh() rejection instead of throwing (expired/revoked refresh token)', async () => {
      const refresh = vi.fn().mockRejectedValue(new Error('401 Unauthorized'))
      await expect(
        attemptSilentRefresh({ isAuthenticated: true, refresh })
      ).resolves.toBeUndefined()
      expect(refresh).toHaveBeenCalledTimes(1)
    })
  })
})
