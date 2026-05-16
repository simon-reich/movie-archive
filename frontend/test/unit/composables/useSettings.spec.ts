import { describe, it, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)
vi.stubGlobal('useRouter', () => ({ push: vi.fn() }))

vi.mock('#app/composables/router', async (importOriginal) => {
  const original = await importOriginal<typeof import('#app/composables/router')>()
  return { ...original, navigateTo: vi.fn() }
})

const { useSettings } = await import('@/composables/useSettings')
const { navigateTo } = await import('#app/composables/router')
const mockNavigateTo = vi.mocked(navigateTo)

describe('useSettings composable', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockFetch.mockReset()
    mockNavigateTo.mockReset()
  })

  it.todo('saveApiKey calls PUT /api/settings/api-keys/tmdb with credentials')
  it.todo('saveApiKey calls PUT /api/settings/api-keys/omdb with credentials')
  it.todo('saveApiKey throws on 422 so page can show inline error')
  it.todo('loadApiKeys calls GET /api/settings/api-keys and returns plaintext keys')
  it.todo('changePassword calls POST /api/settings/password')
  it.todo('changePassword throws on 400 so page can show inline error')
  it.todo('changeEmail calls POST /api/settings/email')
})
