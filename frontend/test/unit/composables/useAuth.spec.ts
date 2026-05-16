import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)
vi.stubGlobal('useRouter', () => ({ push: vi.fn() }))

// Mock navigateTo from nuxt's router composable
vi.mock('#app/composables/router', async (importOriginal) => {
  const original = await importOriginal<typeof import('#app/composables/router')>()
  return {
    ...original,
    navigateTo: vi.fn(),
  }
})

// Import after mocking
const { useAuth } = await import('@/composables/useAuth')
const { navigateTo } = await import('#app/composables/router')
const mockNavigateTo = vi.mocked(navigateTo)

describe('useAuth composable', () => {
  beforeEach(() => {
    // Clear auth cookies so useCookie refs start null in each test.
    document.cookie = 'access_token=; Max-Age=0; path=/'
    document.cookie = 'session_email=; Max-Age=0; path=/'
    setActivePinia(createPinia())
    mockFetch.mockReset()
    mockNavigateTo.mockReset()
  })

  it('login calls POST /api/auth/login and populates store', async () => {
    mockFetch.mockResolvedValueOnce({ accessToken: 'tok', email: 'u@e.com' })
    const { login } = useAuth()
    await login('u@e.com', 'pass')
    expect(mockFetch).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({ method: 'POST', credentials: 'include' }))
    const store = useAuthStore()
    expect(store.accessToken).toBe('tok')
    expect(mockNavigateTo).toHaveBeenCalledWith('/')
  })

  it('signup calls POST /api/auth/signup and redirects to /verify-email-sent', async () => {
    mockFetch.mockResolvedValueOnce(null)
    const { signup } = useAuth()
    await signup('new@e.com', 'password123')
    expect(mockFetch).toHaveBeenCalledWith('/api/auth/signup', expect.objectContaining({ method: 'POST', credentials: 'include' }))
    expect(mockNavigateTo).toHaveBeenCalledWith('/verify-email-sent')
    // Verify no setAuth was called (no auto-login) — cookie is absent so value is null or ""
    const store = useAuthStore()
    expect(store.accessToken).toBeFalsy()
  })

  it('logout calls POST /api/auth/logout and clears store', async () => {
    const store = useAuthStore()
    store.setAuth('existing-token', 'u@e.com')
    mockFetch.mockResolvedValueOnce(null)
    const { logout } = useAuth()
    await logout()
    expect(mockFetch).toHaveBeenCalledWith('/api/auth/logout', expect.objectContaining({ method: 'POST', credentials: 'include' }))
    expect(store.accessToken).toBeNull()
    expect(mockNavigateTo).toHaveBeenCalledWith('/login')
  })

  it('verifyEmail calls POST /api/auth/verify-email with token from argument', async () => {
    mockFetch.mockResolvedValueOnce(null)
    const { verifyEmail } = useAuth()
    await verifyEmail('my-verification-token')
    expect(mockFetch).toHaveBeenCalledWith('/api/auth/verify-email', expect.objectContaining({
      method: 'POST',
      body: { token: 'my-verification-token' },
      credentials: 'include',
    }))
  })

  it('forgotPassword calls POST /api/auth/forgot-password', async () => {
    mockFetch.mockResolvedValueOnce(null)
    const { forgotPassword } = useAuth()
    await forgotPassword('user@example.com')
    expect(mockFetch).toHaveBeenCalledWith('/api/auth/forgot-password', expect.objectContaining({
      method: 'POST',
      body: { email: 'user@example.com' },
      credentials: 'include',
    }))
  })

  it('resetPassword calls POST /api/auth/reset-password with token and newPassword', async () => {
    mockFetch.mockResolvedValueOnce(null)
    const { resetPassword } = useAuth()
    await resetPassword('reset-tok', 'newSecurePass1')
    expect(mockFetch).toHaveBeenCalledWith('/api/auth/reset-password', expect.objectContaining({
      method: 'POST',
      body: { token: 'reset-tok', newPassword: 'newSecurePass1' },
      credentials: 'include',
    }))
  })
})
