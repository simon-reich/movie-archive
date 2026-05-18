import { describe, it, vi, beforeEach } from 'vitest'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)

const { useMovieDetail } = await import('@/composables/useMovieDetail')

describe('useMovieDetail composable', () => {
  beforeEach(() => { mockFetch.mockReset() })

  it.todo('fetches GET /api/movies/:id on mount and populates movie ref')
  it.todo('sets isLoading to false after fetch completes')
  it.todo('sets error ref when GET request fails')
  it.todo('updatePersonal sends PATCH /api/movies/:id/personal with { watched } body')
  it.todo('updatePersonal sends PATCH /api/movies/:id/personal with { personalRating: null } to clear rating')
  it.todo('updatePersonal sends PATCH /api/movies/:id/personal with { personalNotes } body')
  it.todo('deleteMovie sends DELETE /api/movies/:id then navigates to /search')
})
