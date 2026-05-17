// Types
interface DashboardMovieItem {
  id: string
  title: string
  year: number
  posterPath: string
}

interface HistogramBucket {
  label: string
  count: number
}

interface DashboardResponse {
  totalFilms: number
  topGenres: { name: string; count: number }[]
  languageBreakdown: { code: string; count: number }[]
  imdbHistogram: HistogramBucket[]
  movieOfTheDay: DashboardMovieItem | null
  recentlyAdded: DashboardMovieItem[]
}

export function useDashboard() {
  const accessTokenCookie = useCookie<string | null>('access_token')

  function authHeaders(): Record<string, string> {
    return accessTokenCookie.value
      ? { Authorization: `Bearer ${accessTokenCookie.value}` }
      : {}
  }

  const data = ref<DashboardResponse | null>(null)
  const isLoading = ref(false)

  async function fetchDashboard(): Promise<void> {
    isLoading.value = true
    try {
      data.value = await $fetch<DashboardResponse>('/api/dashboard', {
        credentials: 'include',
        headers: authHeaders(),
      })
    } finally {
      isLoading.value = false
    }
  }

  return { data, isLoading, fetchDashboard }
}
