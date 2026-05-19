// Types
export interface SearchResultItem {
  id: string
  tmdbId: number
  title: string
  year: number
  posterPath: string
  directorList: string[]
  genreList: string[]
  imdbRating: number | null
  runtime: number | null
}

export interface FilterCriteria {
  genres?: string[]
  director?: string
  actors?: string
  crew?: string
  yearFrom?: number
  yearTo?: number
  imdbRatingFrom?: number
  imdbRatingTo?: number
  contentRatings?: string[]
  runtimeMax?: number
  notYetWatched?: boolean
  languages?: string[]
  countries?: string[]
}

export interface SearchApiResponse {
  results: SearchResultItem[]
  total: number
  page: number
  totalPages: number
  hasMore: boolean
}

export interface Facets {
  genres: string[]
  contentRatings: string[]
  languages: string[]
  countries: string[]
}

// URL param normalization helpers (per 05-RESEARCH.md Pattern 10)
type QueryParam = string | null | (string | null)[]

function normalizeQueryParam(val: QueryParam): string[] {
  if (!val) return []
  if (Array.isArray(val)) return val.filter((v): v is string => v !== null)
  return [val]
}

function paramAsString(val: QueryParam): string {
  if (Array.isArray(val)) return val.find(v => v !== null) ?? ''
  return val ?? ''
}

function paramAsNumber(val: QueryParam): number | undefined {
  const str = paramAsString(val)
  if (!str) return undefined
  const n = parseFloat(str)
  return isNaN(n) ? undefined : n
}

export function useSearch() {
  const accessTokenCookie = useCookie<string | null>('access_token')

  function authHeaders(): Record<string, string> {
    return accessTokenCookie.value
      ? { Authorization: `Bearer ${accessTokenCookie.value}` }
      : {}
  }

  const route = useRoute()
  const router = useRouter()

  const results = ref<SearchResultItem[]>([])
  const total = ref(0)
  const hasMore = ref(false)
  const isLoading = ref(false)

  // Computed filter state from URL params
  const query = computed(() => paramAsString(route.query.q))
  const page = computed(() => parseInt(paramAsString(route.query.page) || '0'))
  const sort = computed(() => paramAsString(route.query.sort) || 'title_asc')
  const genres = computed(() => normalizeQueryParam(route.query.genre))
  const director = computed(() => paramAsString(route.query.director))
  const actors = computed(() => paramAsString(route.query.actors))
  const crew = computed(() => paramAsString(route.query.crew))
  const yearFrom = computed(() => paramAsNumber(route.query.year_from))
  const yearTo = computed(() => paramAsNumber(route.query.year_to))
  const imdbFrom = computed(() => paramAsNumber(route.query.imdb_from))
  const imdbTo = computed(() => paramAsNumber(route.query.imdb_to))
  const contentRatings = computed(() => normalizeQueryParam(route.query.content_rating))
  const runtimeMax = computed(() => paramAsNumber(route.query.runtime_max))
  const watched = computed(() => route.query.watched === 'false' ? false : undefined)
  const languages = computed(() => normalizeQueryParam(route.query.language))
  const countries = computed(() => normalizeQueryParam(route.query.country))

  // Mutable local ref for text input value — separate from URL so debounce works
  const searchQuery = ref(paramAsString(route.query.q))

  // Manual debounce (VueUse NOT installed — per 05-RESEARCH.md anti-patterns)
  let debounceTimer: ReturnType<typeof setTimeout> | null = null
  watch(searchQuery, (val) => {
    if (debounceTimer) clearTimeout(debounceTimer)
    debounceTimer = setTimeout(() => {
      router.replace({ query: { ...route.query, q: val, page: '0' } })
    }, 300)
  })

  // Watch route query for all changes — immediate fires on mount for D-05 and D-14 URL navigation
  watch(() => route.query, () => { executeSearch() }, { immediate: true, deep: true })

  function buildFilters(): FilterCriteria | undefined {
    const f: FilterCriteria = {}
    if (genres.value.length > 0) f.genres = genres.value
    if (director.value) f.director = director.value
    if (actors.value) f.actors = actors.value
    if (crew.value) f.crew = crew.value
    if (yearFrom.value !== undefined) f.yearFrom = yearFrom.value
    if (yearTo.value !== undefined) f.yearTo = yearTo.value
    if (imdbFrom.value !== undefined) f.imdbRatingFrom = imdbFrom.value
    if (imdbTo.value !== undefined) f.imdbRatingTo = imdbTo.value
    if (contentRatings.value.length > 0) f.contentRatings = contentRatings.value
    if (runtimeMax.value !== undefined) f.runtimeMax = runtimeMax.value
    if (watched.value !== undefined) f.notYetWatched = watched.value === false
    if (languages.value.length > 0) f.languages = languages.value
    if (countries.value.length > 0) f.countries = countries.value

    return Object.keys(f).length > 0 ? f : undefined
  }

  async function executeSearch(): Promise<void> {
    isLoading.value = true
    const currentPage = page.value
    try {
      const body = {
        query: query.value || undefined,
        filters: buildFilters(),
        sort: sort.value,
        page: currentPage,
      }
      const response = await $fetch<SearchApiResponse>('/api/search', {
        method: 'POST',
        body,
        credentials: 'include',
        headers: authHeaders(),
      })
      if (!response) return
      if (currentPage === 0) {
        results.value = response.results ?? []
      } else {
        results.value = [...results.value, ...(response.results ?? [])]
      }
      total.value = response.total ?? 0
      hasMore.value = response.hasMore ?? false
    } finally {
      isLoading.value = false
    }
  }

  // URL update helpers for use by search.vue and components
  function updateFilter(key: string, value: string | string[] | null): void {
    const q = { ...route.query }
    if (value === null || value === '' || (Array.isArray(value) && value.length === 0)) {
      delete q[key]
    } else {
      q[key] = value as string | string[]
    }
    q.page = '0'
    router.replace({ query: q })
  }

  function loadMore(): void {
    router.replace({ query: { ...route.query, page: String(page.value + 1) } })
  }

  const facets = ref<Facets>({ genres: [], contentRatings: [], languages: [], countries: [] })

  async function fetchFacets(): Promise<void> {
    try {
      const data = await $fetch<Facets>('/api/search/facets', {
        credentials: 'include',
        headers: authHeaders(),
      })
      if (data) facets.value = data
    } catch {
      // silently ignore — filter chips stay empty until data is available
    }
  }

  return {
    // State
    results,
    total,
    hasMore,
    isLoading,
    // URL-computed state
    query,
    page,
    sort,
    genres,
    director,
    actors,
    crew,
    yearFrom,
    yearTo,
    imdbFrom,
    imdbTo,
    contentRatings,
    runtimeMax,
    watched,
    languages,
    countries,
    // Mutable input ref (for SearchBar two-way binding)
    searchQuery,
    // Actions
    executeSearch,
    updateFilter,
    loadMore,
    // Facets (dynamic filter options)
    facets,
    fetchFacets,
  }
}
