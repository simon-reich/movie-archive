import { describe, it, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)
vi.stubGlobal('useRouter', () => ({ push: vi.fn() }))

vi.mock('#app/composables/router', async (importOriginal) => {
  const original = await importOriginal<typeof import('#app/composables/router')>()
  return { ...original, navigateTo: vi.fn() }
})

describe('/settings page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockFetch.mockReset()
  })

  it.todo('renders Account section with email change and password change forms')
  it.todo('renders API Keys section with TMDB and OMDB inputs showing loaded plaintext key (D-03)')
  it.todo('shows inline "Saved" state on TMDB key input after successful save (D-06)')
  it.todo('shows inline error below TMDB key input on 422 response (D-10)')
  it.todo('shows inline "Check your inbox" message after email change submit (D-07)')
  it.todo('shows inline error on password change when current password is wrong (D-10)')
  it.todo('renders Import & Export section with disabled Export CSV and Import CSV buttons (D-08)')
  it.todo('settings page is accessible from navigation (D-02)')
})
