// Types
export interface TmdbSearchResult {
  tmdbId: number
  title: string
  year: number | null
  posterPath: string | null
}

export interface MovieStatusResponse {
  id: string
  status: 'PENDING' | 'SUCCESS' | 'ERROR'
  title: string | null
  indexedAt: string | null
}

export type PosterState = 'idle' | 'pending' | 'success' | 'error' | 'saved'

export interface SearchResultItem extends TmdbSearchResult {
  state: PosterState
  movieId?: string
  errorMessage?: string
}

export function useMovies() {
  const accessTokenCookie = useCookie<string | null>('access_token')

  function authHeaders(): Record<string, string> {
    return accessTokenCookie.value
      ? { Authorization: `Bearer ${accessTokenCookie.value}` }
      : {}
  }

  async function searchTmdb(query: string): Promise<TmdbSearchResult[]> {
    return await $fetch<TmdbSearchResult[]>(`/api/movies/search?q=${encodeURIComponent(query)}`, {
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  async function saveMovie(tmdbId: number): Promise<{ id: string }> {
    return await $fetch<{ id: string }>('/api/movies/save', {
      method: 'POST',
      body: { tmdbId },
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  async function getStatus(movieId: string): Promise<MovieStatusResponse> {
    return await $fetch<MovieStatusResponse>(`/api/movies/${movieId}/status`, {
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  async function getSavedTmdbIds(): Promise<number[]> {
    const data = await $fetch<{ tmdbIds: number[] }>('/api/movies/saved-ids', {
      credentials: 'include',
      headers: authHeaders(),
    })
    return data.tmdbIds
  }

  async function uploadBulkImport(file: File): Promise<{ status: string, batchId: string }> {
    const formData = new FormData()
    formData.append('file', file)
    return await $fetch<{ status: string, batchId: string }>('/api/movies/bulk-import', {
      method: 'POST',
      body: formData,
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  return { searchTmdb, saveMovie, getStatus, getSavedTmdbIds, uploadBulkImport }
}
