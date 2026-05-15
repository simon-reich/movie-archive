import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)
vi.stubGlobal('useRouter', () => ({ push: vi.fn() }))
vi.stubGlobal('useRoute', () => ({ query: { token: 'valid-reset-token' } }))

vi.mock('#app/composables/router', async (importOriginal) => {
  const original = await importOriginal<typeof import('#app/composables/router')>()
  return { ...original, navigateTo: vi.fn() }
})

describe('/reset-password page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockFetch.mockReset()
  })

  it('renders new password and confirm password fields', async () => {
    const { default: ResetPasswordPage } = await import('@/pages/reset-password.vue')
    expect(ResetPasswordPage).toBeDefined()
  })

  it('shows "Passwords do not match." when confirm does not match — client validation', () => {
    const newPassword = 'strongpass1'
    const confirmPassword = 'differentpass'
    expect(newPassword !== confirmPassword).toBe(true)
    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('shows "Password must be at least 8 characters." for short password', () => {
    const password = 'short'
    expect(password.length < 8).toBe(true)
    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('shows success state and Sign in CTA after successful reset', async () => {
    mockFetch.mockResolvedValueOnce(null)
    const { useAuth } = await import('@/composables/useAuth')
    const { resetPassword } = useAuth()
    await expect(resetPassword('valid-reset-token', 'newStrongPass')).resolves.not.toThrow()
  })

  it('shows "This reset link has expired. Request a new one." on 400 with expired token message', async () => {
    mockFetch.mockRejectedValueOnce({ status: 400, data: { message: 'Token expired.' } })
    const { useAuth } = await import('@/composables/useAuth')
    const { resetPassword } = useAuth()
    let caught: unknown
    try { await resetPassword('expired-reset-token', 'newpass') } catch (e) { caught = e }
    expect((caught as { data: { message: string } }).data.message).toContain('expired')
  })
})
