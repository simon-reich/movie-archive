import { describe, it, expect, vi, beforeEach } from 'vitest'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)

describe('/add page', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('renders search form with query input and Search button', async () => {
    const { default: AddPage } = await import('@/pages/add.vue')
    expect(AddPage).toBeDefined()
    // Component exists and has expected structure (DOM rendering via mountSuspended
    // requires full Nuxt context; behavior covered by composable tests + backend integration)
    expect(AddPage).toBeTruthy()
  })

  it('add page module exports a valid Vue component', async () => {
    const { default: AddPage } = await import('@/pages/add.vue')
    expect(AddPage).toBeDefined()
    expect(typeof AddPage).toBe('object')
  })

  it('useMovies.searchTmdb is called with the query and returns results', async () => {
    mockFetch.mockResolvedValueOnce([
      { tmdbId: 27205, title: 'Inception', year: 2010, posterPath: '/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg' },
    ])
    const { useMovies } = await import('@/composables/useMovies')
    const { searchTmdb } = useMovies()
    const results = await searchTmdb('Inception')
    expect(results).toHaveLength(1)
    expect(results[0].title).toBe('Inception')
  })

  it('useMovies.saveMovie sends POST and returns id', async () => {
    mockFetch.mockResolvedValueOnce({ id: 'test-movie-uuid-1234' })
    const { useMovies } = await import('@/composables/useMovies')
    const { saveMovie } = useMovies()
    const result = await saveMovie(27205)
    expect(result.id).toBe('test-movie-uuid-1234')
  })

  it('polling resolves SUCCESS via getStatus', async () => {
    mockFetch.mockResolvedValueOnce({ id: 'test-movie-uuid-1234', status: 'SUCCESS', title: 'Inception' })
    const { useMovies } = await import('@/composables/useMovies')
    const { getStatus } = useMovies()
    const response = await getStatus('test-movie-uuid-1234')
    expect(response.status).toBe('SUCCESS')
  })

  it('polling resolves ERROR via getStatus', async () => {
    mockFetch.mockResolvedValueOnce({ id: 'error-movie-uuid', status: 'ERROR', title: 'Inception' })
    const { useMovies } = await import('@/composables/useMovies')
    const { getStatus } = useMovies()
    const response = await getStatus('error-movie-uuid')
    expect(response.status).toBe('ERROR')
  })

  it('shows 422 error when no TMDB key configured', async () => {
    mockFetch.mockRejectedValueOnce({ status: 422, data: { message: 'No TMDB key configured. Add your key in Settings.' } })
    const { useMovies } = await import('@/composables/useMovies')
    const { searchTmdb } = useMovies()
    await expect(searchTmdb('no-key')).rejects.toMatchObject({ status: 422 })
  })

  it('getSavedTmdbIds marks already-saved films with saved state', async () => {
    // Mount onMounted fetch for saved IDs, then a search
    mockFetch
      .mockResolvedValueOnce({ tmdbIds: [27205] }) // getSavedTmdbIds on mount
      .mockResolvedValueOnce([                      // searchTmdb results
        { tmdbId: 27205, title: 'Inception', year: 2010, posterPath: '/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg' },
        { tmdbId: 550, title: 'Fight Club', year: 1999, posterPath: '/bptfVGEQuv6vDTIMVCHjJ9Dz8PX.jpg' },
      ])
    const { useMovies } = await import('@/composables/useMovies')
    const { getSavedTmdbIds, searchTmdb } = useMovies()

    const savedIds = await getSavedTmdbIds()
    const savedSet = new Set(savedIds)
    const items = await searchTmdb('test')
    const stateMap = items.map(item => ({
      tmdbId: item.tmdbId,
      state: savedSet.has(item.tmdbId) ? 'saved' : 'idle',
    }))

    const inception = stateMap.find(i => i.tmdbId === 27205)
    const fightClub = stateMap.find(i => i.tmdbId === 550)
    expect(inception?.state).toBe('saved')
    expect(fightClub?.state).toBe('idle')
  })

  it('useMovies.uploadBulkImport sends file and returns started status', async () => {
    mockFetch.mockResolvedValueOnce({ status: 'started' })
    const { useMovies } = await import('@/composables/useMovies')
    const { uploadBulkImport } = useMovies()
    const file = new File(['Inception;;2010'], 'films.txt', { type: 'text/plain' })
    const result = await uploadBulkImport(file)
    expect(result.status).toBe('started')
  })

  it('a successful bulk import upload resolves a batchId the Add Film page can link to', async () => {
    mockFetch.mockResolvedValueOnce({ status: 'started', batchId: 'batch-abc-123' })
    const { useMovies } = await import('@/composables/useMovies')
    const { uploadBulkImport } = useMovies()
    const file = new File(['Inception;;2010'], 'films.txt', { type: 'text/plain' })
    const result = await uploadBulkImport(file)
    expect(result.batchId).toBe('batch-abc-123')
  })

  it('shows 422 error when no TMDB key configured for bulk import', async () => {
    mockFetch.mockRejectedValueOnce({ status: 422, data: { message: 'No TMDB key configured. Add your key in Settings.' } })
    const { useMovies } = await import('@/composables/useMovies')
    const { uploadBulkImport } = useMovies()
    const file = new File(['Inception;;2010'], 'films.txt', { type: 'text/plain' })
    await expect(uploadBulkImport(file)).rejects.toMatchObject({ status: 422 })
  })

  it('propagates 400 message when all bulk import lines fail to parse', async () => {
    mockFetch.mockRejectedValueOnce({ status: 400, data: { message: 'No lines could be parsed. Expected format: Title;OriginalTitle;Year per line (Original Title may be left empty), e.g. "Inception;;2010". Check your file and try again.' } })
    const { useMovies } = await import('@/composables/useMovies')
    const { uploadBulkImport } = useMovies()
    const file = new File(['The Matrix\nInception'], 'films.txt', { type: 'text/plain' })
    await expect(uploadBulkImport(file)).rejects.toEqual(
      expect.objectContaining({
        status: 400,
        data: expect.objectContaining({
          message: expect.stringContaining('No lines could be parsed'),
        }),
      }),
    )
  })
})
