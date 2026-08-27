import { describe, it, expect, vi, beforeEach } from 'vitest'
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

  it('saveApiKey calls PUT /api/settings/api-keys/tmdb with credentials', async () => {
    mockFetch.mockResolvedValueOnce({ message: 'API key saved.' })
    const { saveApiKey } = useSettings()
    await saveApiKey('tmdb', 'my-tmdb-key')
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/settings/api-keys/tmdb',
      expect.objectContaining({ method: 'PUT', credentials: 'include', body: { key: 'my-tmdb-key' } })
    )
  })

  it('saveApiKey calls PUT /api/settings/api-keys/omdb with credentials', async () => {
    mockFetch.mockResolvedValueOnce({ message: 'API key saved.' })
    const { saveApiKey } = useSettings()
    await saveApiKey('omdb', 'my-omdb-key')
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/settings/api-keys/omdb',
      expect.objectContaining({ method: 'PUT', credentials: 'include' })
    )
  })

  it('saveApiKey throws on 422 so page can show inline error', async () => {
    mockFetch.mockRejectedValueOnce({ status: 422, data: { message: 'Invalid TMDB API key — check your key and try again.' } })
    const { saveApiKey } = useSettings()
    await expect(saveApiKey('tmdb', 'bad-key')).rejects.toMatchObject({ status: 422 })
  })

  it('loadApiKeys calls GET /api/settings/api-keys and returns keys', async () => {
    mockFetch.mockResolvedValueOnce({ tmdb: 'my-key', omdb: null })
    const { loadApiKeys } = useSettings()
    const result = await loadApiKeys()
    expect(mockFetch).toHaveBeenCalledWith('/api/settings/api-keys', expect.objectContaining({ credentials: 'include' }))
    expect(result).toEqual({ tmdb: 'my-key', omdb: null })
  })

  it('changePassword calls POST /api/settings/password then clears auth and navigates to /login', async () => {
    mockFetch.mockResolvedValueOnce(null)
    const { changePassword } = useSettings()
    await changePassword('old-pass', 'new-pass-secure')
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/settings/password',
      expect.objectContaining({ method: 'POST', credentials: 'include' })
    )
    expect(mockNavigateTo).toHaveBeenCalledWith('/login')
  })

  it('changePassword throws on 400 so page can show inline error', async () => {
    mockFetch.mockRejectedValueOnce({ status: 400, data: { message: 'Current password is incorrect.' } })
    const { changePassword } = useSettings()
    await expect(changePassword('wrong', 'newpass123')).rejects.toMatchObject({ status: 400 })
  })

  it('deleteApiKey calls DELETE /api/settings/api-keys/tmdb with credentials', async () => {
    mockFetch.mockResolvedValueOnce(null)
    const { deleteApiKey } = useSettings()
    await deleteApiKey('tmdb')
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/settings/api-keys/tmdb',
      expect.objectContaining({ method: 'DELETE', credentials: 'include' })
    )
  })

  it('deleteApiKey calls DELETE /api/settings/api-keys/omdb with credentials', async () => {
    mockFetch.mockResolvedValueOnce(null)
    const { deleteApiKey } = useSettings()
    await deleteApiKey('omdb')
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/settings/api-keys/omdb',
      expect.objectContaining({ method: 'DELETE', credentials: 'include' })
    )
  })

  it('deleteApiKey throws on error so page can show inline error', async () => {
    mockFetch.mockRejectedValueOnce({ status: 400, data: { message: 'Invalid provider.' } })
    const { deleteApiKey } = useSettings()
    await expect(deleteApiKey('tmdb')).rejects.toMatchObject({ status: 400 })
  })

  it('changeEmail calls POST /api/settings/email', async () => {
    mockFetch.mockResolvedValueOnce(null)
    const { changeEmail } = useSettings()
    await changeEmail('new@example.com')
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/settings/email',
      expect.objectContaining({ method: 'POST', credentials: 'include', body: { newEmail: 'new@example.com' } })
    )
  })

  it('getCurrentUserId fetches GET /api/users/me once and caches the result', async () => {
    mockFetch.mockResolvedValueOnce({ id: 'user-abc' })
    const { getCurrentUserId } = useSettings()
    const first = await getCurrentUserId()
    const second = await getCurrentUserId()
    expect(first).toBe('user-abc')
    expect(second).toBe('user-abc')
    expect(mockFetch).toHaveBeenCalledTimes(1)
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/users/me',
      expect.objectContaining({ credentials: 'include' })
    )
  })

  it("triggerWikiReload calls POST /api/admin/wiki-reload/:userId with the resolved id and returns 'started' on success", async () => {
    mockFetch.mockResolvedValueOnce({ id: 'user-abc' })
    mockFetch.mockResolvedValueOnce(undefined)
    const { triggerWikiReload } = useSettings()
    const result = await triggerWikiReload()
    expect(result).toBe('started')
    expect(mockFetch).toHaveBeenNthCalledWith(
      2,
      '/api/admin/wiki-reload/user-abc',
      expect.objectContaining({ method: 'POST', credentials: 'include' })
    )
  })

  it("triggerWikiReload returns 'already-running' when the POST rejects with a 503 response", async () => {
    mockFetch.mockResolvedValueOnce({ id: 'user-abc' })
    mockFetch.mockRejectedValueOnce({ response: { status: 503 } })
    const { triggerWikiReload } = useSettings()
    const result = await triggerWikiReload()
    expect(result).toBe('already-running')
  })

  it('triggerWikiReload rethrows non-503 errors', async () => {
    mockFetch.mockResolvedValueOnce({ id: 'user-abc' })
    mockFetch.mockRejectedValueOnce(new Error('network down'))
    const { triggerWikiReload } = useSettings()
    await expect(triggerWikiReload()).rejects.toThrow()
  })

  it('subscribeToWikiReloadProgress is exported as a function', () => {
    const { subscribeToWikiReloadProgress } = useSettings()
    expect(typeof subscribeToWikiReloadProgress).toBe('function')
  })

  it('stopWikiReload resolves when $fetch resolves', async () => {
    mockFetch.mockResolvedValueOnce({ id: 'user-abc' })
    mockFetch.mockResolvedValueOnce(null)
    const { stopWikiReload } = useSettings()
    await expect(stopWikiReload()).resolves.toBeUndefined()
    expect(mockFetch).toHaveBeenNthCalledWith(
      2,
      '/api/admin/wiki-reload/user-abc/stop',
      expect.objectContaining({ method: 'POST', credentials: 'include' })
    )
  })
})
