import { describe, it, expect, vi, beforeEach } from 'vitest'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)

const { useMovies } = await import('@/composables/useMovies')

describe('useMovies composable', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('searchTmdb returns array of TmdbSearchResult on success', async () => {
    mockFetch.mockResolvedValueOnce([
      { tmdbId: 27205, title: 'Inception', year: 2010, posterPath: '/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg' },
    ])
    const { searchTmdb } = useMovies()
    const results = await searchTmdb('Inception')
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/movies/search?q=Inception',
      expect.objectContaining({ credentials: 'include' })
    )
    expect(results).toHaveLength(1)
    expect(results[0].tmdbId).toBe(27205)
    expect(results[0].title).toBe('Inception')
    expect(results[0].year).toBe(2010)
  })

  it('searchTmdb throws on 422 when no TMDB key configured', async () => {
    mockFetch.mockRejectedValueOnce({ status: 422, data: { message: 'No TMDB key configured. Add your key in Settings.' } })
    const { searchTmdb } = useMovies()
    await expect(searchTmdb('no-key')).rejects.toMatchObject({ status: 422 })
  })

  it('saveMovie sends POST /api/movies/save and returns { id }', async () => {
    mockFetch.mockResolvedValueOnce({ id: 'test-movie-uuid-1234' })
    const { saveMovie } = useMovies()
    const result = await saveMovie(27205)
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/movies/save',
      expect.objectContaining({ method: 'POST', credentials: 'include', body: { tmdbId: 27205 } })
    )
    expect(result.id).toBe('test-movie-uuid-1234')
  })

  it('getStatus returns MovieStatusResponse for given movieId', async () => {
    mockFetch.mockResolvedValueOnce({ id: 'test-movie-uuid-1234', status: 'SUCCESS', title: 'Inception' })
    const { getStatus } = useMovies()
    const response = await getStatus('test-movie-uuid-1234')
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/movies/test-movie-uuid-1234/status',
      expect.objectContaining({ credentials: 'include' })
    )
    expect(response.status).toBe('SUCCESS')
  })

  it('getStatus returns ERROR for error movieId', async () => {
    mockFetch.mockResolvedValueOnce({ id: 'error-movie-uuid', status: 'ERROR', title: 'Inception' })
    const { getStatus } = useMovies()
    const response = await getStatus('error-movie-uuid')
    expect(response.status).toBe('ERROR')
  })

  it('getSavedTmdbIds returns array of TMDB IDs from server', async () => {
    mockFetch.mockResolvedValueOnce({ tmdbIds: [27205, 550] })
    const { getSavedTmdbIds } = useMovies()
    const ids = await getSavedTmdbIds()
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/movies/saved-ids',
      expect.objectContaining({ credentials: 'include' })
    )
    expect(ids).toEqual([27205, 550])
  })

  it('getSavedTmdbIds returns empty array when user has no saved films', async () => {
    mockFetch.mockResolvedValueOnce({ tmdbIds: [] })
    const { getSavedTmdbIds } = useMovies()
    const ids = await getSavedTmdbIds()
    expect(ids).toEqual([])
  })
})
