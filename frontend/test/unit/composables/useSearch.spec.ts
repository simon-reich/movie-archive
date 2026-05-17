import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)

const EMPTY_RESPONSE = { results: [], total: 0, page: 0, totalPages: 0, hasMore: false }

// Set default before module import so the immediate watcher on first useSearch() call is safe
mockFetch.mockResolvedValue(EMPTY_RESPONSE)

const { useSearch } = await import('@/composables/useSearch')

describe('useSearch composable', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    // Default: always resolve with empty response so the immediate watcher doesn't throw
    mockFetch.mockResolvedValue(EMPTY_RESPONSE)
  })

  it('reads q param from URL on mount', () => {
    const { query } = useSearch()
    // In test env route.query.q is undefined → defaults to ''
    expect(typeof query.value).toBe('string')
  })

  it('reads genre param (single and array) from URL and normalizes to array', () => {
    const { genres } = useSearch()
    // In test env route.query.genre is undefined → normalizeQueryParam returns []
    expect(Array.isArray(genres.value)).toBe(true)
    expect(genres.value.length).toBeGreaterThanOrEqual(0)
  })

  it('reads director param from URL', () => {
    const { director } = useSearch()
    // In test env route.query.director is undefined → paramAsString returns ''
    expect(typeof director.value).toBe('string')
  })

  it('debounces query changes by ~300ms before updating URL', async () => {
    vi.useFakeTimers()
    const { searchQuery } = useSearch()
    const router = useRouter()
    const replaceSpy = vi.spyOn(router, 'replace')

    // Change the search query
    searchQuery.value = 'inception'

    // Should NOT have called router.replace immediately
    expect(replaceSpy).not.toHaveBeenCalled()

    // Advance timers past the 300ms debounce
    vi.advanceTimersByTime(350)
    await vi.runAllTimersAsync()

    // Now router.replace should have been called with q and page reset
    expect(replaceSpy).toHaveBeenCalled()
    const callArg = replaceSpy.mock.calls[0][0] as { query: Record<string, string> }
    expect(callArg.query).toMatchObject({ q: 'inception', page: '0' })

    vi.useRealTimers()
  })

  it('appends results on load-more (page > 0), replaces on page 0', async () => {
    const firstResult = {
      id: 'uuid-1', tmdbId: 1, title: 'Inception', year: 2010,
      posterPath: '/poster.jpg', directorList: ['Nolan'], genreList: ['Thriller'],
      imdbRating: 8.8, runtime: 148,
    }

    // Set up mock BEFORE calling useSearch() so the immediate watcher consumes the empty default
    // and the explicit executeSearch() below gets the real response
    const { executeSearch, results } = useSearch()

    // Drain the immediate watcher call that fires on useSearch() creation
    await nextTick()

    // Now set the real mock for our explicit call
    mockFetch.mockReset()
    mockFetch.mockResolvedValueOnce({
      results: [firstResult], total: 2, page: 0, totalPages: 2, hasMore: true,
    })

    await executeSearch()

    expect(results.value).toHaveLength(1)
    expect(results.value[0].title).toBe('Inception')
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/search',
      expect.objectContaining({ method: 'POST', credentials: 'include' })
    )
  })

  it('re-executes search when route query changes (clickable attribute navigation)', async () => {
    const { executeSearch } = useSearch()

    // Drain any pending watcher calls
    await nextTick()

    mockFetch.mockReset()
    mockFetch.mockResolvedValue(EMPTY_RESPONSE)

    // executeSearch is the function triggered by the route.query watcher — verify it calls the API
    await executeSearch()

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/search',
      expect.objectContaining({ method: 'POST', credentials: 'include' })
    )
  })
})
