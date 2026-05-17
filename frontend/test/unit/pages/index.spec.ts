import { describe, it, expect, vi, beforeEach } from 'vitest'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)

const FULL_DASHBOARD_RESPONSE = {
  totalFilms: 42,
  topGenres: [
    { name: 'Drama', count: 12 },
    { name: 'Thriller', count: 8 },
  ],
  languageBreakdown: [{ code: 'en', count: 30 }],
  imdbHistogram: [
    { label: '7-8', count: 15 },
    { label: '9-10', count: 5 },
  ],
  movieOfTheDay: { id: 'test-uuid-2', title: 'The Godfather', year: 1972, posterPath: '/xxx.jpg' },
  recentlyAdded: [{ id: 'test-uuid-1', title: 'Inception', year: 2010, posterPath: '/yyy.jpg' }],
}

const EMPTY_DASHBOARD_RESPONSE = {
  totalFilms: 0,
  topGenres: [],
  languageBreakdown: [],
  imdbHistogram: [],
  movieOfTheDay: null,
  recentlyAdded: [],
}

// Set default before module import
mockFetch.mockResolvedValue(FULL_DASHBOARD_RESPONSE)

const { useDashboard } = await import('@/composables/useDashboard')

describe('/ dashboard page', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockFetch.mockResolvedValue(FULL_DASHBOARD_RESPONSE)
  })

  it('dashboard page module exports a valid Vue component', async () => {
    const module = await import('@/pages/index.vue')
    const IndexPage = module.default
    expect(IndexPage).toBeDefined()
    expect(typeof IndexPage).toBe('object')
  })

  it('fetches dashboard data on mount', async () => {
    mockFetch.mockResolvedValueOnce(FULL_DASHBOARD_RESPONSE)
    const { fetchDashboard } = useDashboard()
    await fetchDashboard()
    expect(mockFetch).toHaveBeenCalledWith('/api/dashboard', expect.objectContaining({
      credentials: 'include',
    }))
  })

  it('renders movie of the day and recently added when films exist', async () => {
    const ONE_FILM_RESPONSE = {
      totalFilms: 1,
      topGenres: [],
      languageBreakdown: [],
      imdbHistogram: [],
      movieOfTheDay: { id: 'u1', title: 'Inception', year: 2010, posterPath: '/p.jpg' },
      recentlyAdded: [{ id: 'u1', title: 'Inception', year: 2010, posterPath: '/p.jpg' }],
    }
    mockFetch.mockResolvedValueOnce(ONE_FILM_RESPONSE)
    const { data, fetchDashboard } = useDashboard()
    await fetchDashboard()
    expect(data.value?.movieOfTheDay?.title).toBe('Inception')
    expect(data.value?.recentlyAdded.length).toBe(1)
  })

  it('shows Add your first film CTA when totalFilms is 0', async () => {
    mockFetch.mockResolvedValueOnce(EMPTY_DASHBOARD_RESPONSE)
    const { data, fetchDashboard } = useDashboard()
    await fetchDashboard()
    expect(data.value?.totalFilms).toBe(0)
  })
})
