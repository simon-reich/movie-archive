import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)

const mockRouterPush = vi.fn()
vi.mock('#app/composables/router', () => ({
  useRouter: () => ({ push: mockRouterPush }),
}))

// Import AFTER mocking
const { useMovieDetail } = await import('@/composables/useMovieDetail')

const MOCK_MOVIE = {
  id: '550e8400-e29b-41d4-a716-446655440000',
  title: 'Inception',
  watched: false,
  personalRating: null,
  personalNotes: null,
  genreList: ['Action'],
  directorList: ['Christopher Nolan'],
  fullCast: [],
  fullCrew: [],
}

describe('useMovieDetail composable', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockRouterPush.mockReset()
  })

  it('fetches GET /api/movies/:id on mount and populates movie ref', async () => {
    mockFetch.mockResolvedValue(MOCK_MOVIE)
    const { movie } = useMovieDetail('550e8400-e29b-41d4-a716-446655440000')
    await nextTick()
    await nextTick()
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/movies/550e8400-e29b-41d4-a716-446655440000',
      expect.objectContaining({ credentials: 'include' })
    )
    expect(movie.value?.title).toBe('Inception')
  })

  it('sets isLoading to false after fetch completes', async () => {
    mockFetch.mockResolvedValue(MOCK_MOVIE)
    const { isLoading } = useMovieDetail('test-id')
    expect(isLoading.value).toBe(true)
    await nextTick()
    await nextTick()
    expect(isLoading.value).toBe(false)
  })

  it('sets error ref when GET request fails', async () => {
    mockFetch.mockRejectedValue(new Error('Network error'))
    const { error } = useMovieDetail('test-id')
    await nextTick()
    await nextTick()
    expect(error.value).toBe('Failed to load film.')
  })

  it('updatePersonal sends PATCH with { watched } body', async () => {
    mockFetch.mockResolvedValue(null)
    const { updatePersonal } = useMovieDetail('test-id')
    await updatePersonal({ watched: true })
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/movies/test-id/personal',
      expect.objectContaining({ method: 'PATCH', body: { watched: true } })
    )
  })

  it('updatePersonal sends PATCH with { personalRating: null } to clear rating', async () => {
    mockFetch.mockResolvedValue(null)
    const { updatePersonal } = useMovieDetail('test-id')
    await updatePersonal({ personalRating: null })
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/movies/test-id/personal',
      expect.objectContaining({ method: 'PATCH', body: { personalRating: null } })
    )
  })

  it('updatePersonal sends PATCH with { personalNotes } body', async () => {
    mockFetch.mockResolvedValue(null)
    const { updatePersonal } = useMovieDetail('test-id')
    await updatePersonal({ personalNotes: 'Great film' })
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/movies/test-id/personal',
      expect.objectContaining({ method: 'PATCH', body: { personalNotes: 'Great film' } })
    )
  })

  it('deleteMovie sends DELETE then navigates to /search', async () => {
    mockFetch.mockResolvedValue(null)
    const { deleteMovie } = useMovieDetail('test-id')
    await deleteMovie()
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/movies/test-id',
      expect.objectContaining({ method: 'DELETE' })
    )
    expect(mockRouterPush).toHaveBeenCalledWith('/search')
  })
})
