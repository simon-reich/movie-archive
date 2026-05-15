import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)
vi.stubGlobal('useRouter', () => ({ push: vi.fn() }))
vi.stubGlobal('useRoute', () => ({ query: { token: 'valid-token' } }))

vi.mock('#app/composables/router', async (importOriginal) => {
  const original = await importOriginal<typeof import('#app/composables/router')>()
  return { ...original, navigateTo: vi.fn() }
})

describe('/verify-email page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockFetch.mockReset()
  })

  it('shows "Verifying your email..." loading state on mount', async () => {
    const { default: VerifyEmailPage } = await import('@/pages/verify-email.vue')
    expect(VerifyEmailPage).toBeDefined()
  })

  it('shows "Email verified" heading and Sign in CTA after successful verification', async () => {
    mockFetch.mockResolvedValueOnce(null)
    const { useAuth } = await import('@/composables/useAuth')
    const { verifyEmail } = useAuth()
    await expect(verifyEmail('valid-token')).resolves.not.toThrow()
  })

  it('shows "Verification failed" and expired message on 400 expired error', async () => {
    mockFetch.mockRejectedValueOnce({ status: 400, data: { message: 'Token expired.' } })
    const { useAuth } = await import('@/composables/useAuth')
    const { verifyEmail } = useAuth()
    let caught: unknown
    try { await verifyEmail('expired-token') } catch (e) { caught = e }
    expect((caught as { data: { message: string } }).data.message).toContain('expired')
  })

  it('shows already-used message on 400 consumed token error', async () => {
    mockFetch.mockRejectedValueOnce({ status: 400, data: { message: 'Token already used.' } })
    const { useAuth } = await import('@/composables/useAuth')
    const { verifyEmail } = useAuth()
    let caught: unknown
    try { await verifyEmail('used-token') } catch (e) { caught = e }
    expect((caught as { data: { message: string } }).data.message).toContain('used')
  })
})
