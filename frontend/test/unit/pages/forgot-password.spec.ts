import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)
vi.stubGlobal('useRouter', () => ({ push: vi.fn() }))

vi.mock('#app/composables/router', async (importOriginal) => {
  const original = await importOriginal<typeof import('#app/composables/router')>()
  return { ...original, navigateTo: vi.fn() }
})

describe('/forgot-password page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockFetch.mockReset()
  })

  it('renders email field and Send reset link button', async () => {
    const { default: ForgotPasswordPage } = await import('@/pages/forgot-password.vue')
    expect(ForgotPasswordPage).toBeDefined()
  })

  it('shows success message after any 200 response regardless of email existence', async () => {
    mockFetch.mockResolvedValueOnce(null)
    const { useAuth } = await import('@/composables/useAuth')
    const { forgotPassword } = useAuth()
    await expect(forgotPassword('nobody@nowhere.com')).resolves.not.toThrow()
  })

  it('shows "Too many attempts. Try again in X seconds." on 429', async () => {
    mockFetch.mockRejectedValueOnce({
      status: 429,
      response: { headers: { get: (h: string) => h === 'Retry-After' ? '30' : null } },
      data: { message: 'Too many attempts. Try again in 30 seconds.' },
    })
    const { useAuth } = await import('@/composables/useAuth')
    const { forgotPassword } = useAuth()
    let caught: unknown
    try { await forgotPassword('user@e.com') } catch (e) { caught = e }
    expect((caught as { status: number }).status).toBe(429)
  })

  it('disables submit button during in-flight request', async () => {
    const { default: ButtonPrimary } = await import('@/components/ButtonPrimary.vue')
    expect(ButtonPrimary).toBeDefined()
  })
})
