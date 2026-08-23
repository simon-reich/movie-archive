import { ref } from 'vue'

export interface CastMember {
  name: string | null
  character: string | null
  order: number | null
  profilePath: string | null
}

export interface CrewMember {
  name: string | null
  job: string | null
  department: string | null
  profilePath: string | null
}

export interface Rating {
  source: string
  value: string
}

export interface MovieDetail {
  id: string
  tmdbId: number
  imdbId: string | null
  title: string
  originalTitle: string | null
  tagline: string | null
  overview: string | null
  releaseDate: string | null
  year: number | null
  runtime: number | null
  posterPath: string | null
  backdropPath: string | null
  voteAverage: number | null
  voteCount: number | null
  trailerKey: string | null
  genreList: string[]
  directorList: string[]
  writerList: string[]
  mainCast: string | null
  fullCast: CastMember[]
  fullCrew: CrewMember[]
  countryList: string[]
  languageList: string[]
  imdbRating: number | null
  imdbVotes: number | null
  contentRating: string | null
  boxOffice: number | null
  ratingList: Rating[] | null
  imdbLink: string | null
  wikipediaPlot: string | null
  wikipediaCritics: string | null
  wikipediaSummary: string | null
  wikipediaUrl: string | null
  watched: boolean
  personalRating: number | null
  personalNotes: string | null
}

export function useMovieDetail(movieId: string) {
  const accessTokenCookie = useCookie<string | null>('access_token')

  function authHeaders(): Record<string, string> {
    return accessTokenCookie.value
      ? { Authorization: `Bearer ${accessTokenCookie.value}` }
      : {}
  }

  const movie = ref<MovieDetail | null>(null)
  const isLoading = ref(true)
  const error = ref<string | null>(null)
  const wikiRetrying = ref(false)
  const wikiRetryError = ref(false)

  async function fetchDetail(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      const data = await $fetch<MovieDetail>(`/api/movies/${movieId}`, {
        credentials: 'include',
        headers: authHeaders(),
      })
      movie.value = data
    } catch {
      error.value = 'Failed to load film.'
    } finally {
      isLoading.value = false
    }
  }

  async function updatePersonal(
    fields: Partial<{ watched: boolean; personalRating: number | null; personalNotes: string | null }>
  ): Promise<void> {
    await $fetch(`/api/movies/${movieId}/personal`, {
      method: 'PATCH',
      body: fields,
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  const router = useRouter()

  async function deleteMovie(): Promise<void> {
    await $fetch(`/api/movies/${movieId}`, {
      method: 'DELETE',
      credentials: 'include',
      headers: authHeaders(),
    })
    await router.push('/search')
  }

  async function retryWiki(): Promise<void> {
    wikiRetrying.value = true
    wikiRetryError.value = false
    try {
      const data = await $fetch<MovieDetail>(`/api/movies/${movieId}/retry-wiki`, {
        method: 'POST',
        credentials: 'include',
        headers: authHeaders(),
      })
      // A 200 response with no Wikipedia fields is a genuine "not found" outcome
      // (the backend always returns 200 here, success or not — see WikiReloadService).
      movie.value = data
    } catch {
      // The request itself failed (network/auth/server error) — distinct from a
      // genuine "no Wikipedia page found" outcome, which returns 200 with empty fields.
      wikiRetryError.value = true
    } finally {
      wikiRetrying.value = false
    }
  }

  fetchDetail()

  return { movie, isLoading, error, updatePersonal, deleteMovie, wikiRetrying, wikiRetryError, retryWiki }
}
