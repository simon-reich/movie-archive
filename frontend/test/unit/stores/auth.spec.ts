import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'

// Mock $fetch at module level
const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockFetch.mockReset()
  })

  it('setAuth populates accessToken and userEmail in-memory only', () => {
    const store = useAuthStore()
    store.setAuth('token-abc', 'user@example.com')
    expect(store.accessToken).toBe('token-abc')
    expect(store.userEmail).toBe('user@example.com')
  })

  it('clearAuth nullifies accessToken and userEmail', () => {
    const store = useAuthStore()
    store.setAuth('token-abc', 'user@example.com')
    store.clearAuth()
    expect(store.accessToken).toBeNull()
    expect(store.userEmail).toBeNull()
  })

  it('isAuthenticated is true when accessToken is set', () => {
    const store = useAuthStore()
    expect(store.isAuthenticated).toBe(false)
    store.setAuth('token-abc', 'user@example.com')
    expect(store.isAuthenticated).toBe(true)
  })

  it('refresh calls POST /api/auth/refresh and populates store', async () => {
    const store = useAuthStore()
    mockFetch.mockResolvedValueOnce({ accessToken: 'new-token', email: 'user@example.com' })
    await store.refresh()
    expect(mockFetch).toHaveBeenCalledWith('/api/auth/refresh', {
      method: 'POST',
      credentials: 'include',
    })
    expect(store.accessToken).toBe('new-token')
    expect(store.userEmail).toBe('user@example.com')
  })

  it('accessToken is not persisted to localStorage', () => {
    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem')
    const store = useAuthStore()
    store.setAuth('token-xyz', 'user@example.com')
    expect(setItemSpy).not.toHaveBeenCalled()
    setItemSpy.mockRestore()
  })
})
