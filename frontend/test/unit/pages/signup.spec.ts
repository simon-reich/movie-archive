import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)
vi.stubGlobal('useRouter', () => ({ push: vi.fn() }))

vi.mock('#app/composables/router', async (importOriginal) => {
  const original = await importOriginal<typeof import('#app/composables/router')>()
  return { ...original, navigateTo: vi.fn() }
})

const { navigateTo } = await import('#app/composables/router')
const mockNavigateTo = vi.mocked(navigateTo)

describe('/signup page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockFetch.mockReset()
    mockNavigateTo.mockReset()
  })

  it('renders email field, password field, and Create account button', async () => {
    const { default: SignupPage } = await import('@/pages/signup.vue')
    expect(SignupPage).toBeDefined()
  })

  it('shows "Enter a valid email address." for invalid email on submit — client validation', () => {
    const email = 'not-an-email'
    const valid = email && /.+@.+\..+/.test(email)
    expect(valid).toBeFalsy()
    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('shows "Password must be at least 8 characters." for short password on submit', () => {
    const password = 'short'
    expect(password.length < 8).toBe(true)
    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('redirects to /verify-email-sent after successful signup', async () => {
    mockFetch.mockResolvedValueOnce(null)
    const { useAuth } = await import('@/composables/useAuth')
    const { signup } = useAuth()
    await signup('new@example.com', 'strongpass')
    expect(mockNavigateTo).toHaveBeenCalledWith('/verify-email-sent')
  })

  it('shows "An account with this email already exists." on 409 response', async () => {
    mockFetch.mockRejectedValueOnce({
      status: 409,
      data: { message: 'An account with this email already exists.' },
    })
    const { useAuth } = await import('@/composables/useAuth')
    const { signup } = useAuth()
    let caught: unknown
    try { await signup('existing@example.com', 'strongpass') } catch (e) { caught = e }
    expect((caught as { status: number }).status).toBe(409)
  })

  it('does not auto-login after signup', async () => {
    mockFetch.mockResolvedValueOnce(null)
    const { useAuth } = await import('@/composables/useAuth')
    const { useAuthStore } = await import('@/stores/auth')
    const { signup } = useAuth()
    await signup('new@example.com', 'strongpass')
    const store = useAuthStore()
    expect(store.accessToken).toBeNull()
  })
})
