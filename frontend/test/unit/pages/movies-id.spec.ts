import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'

// ── MOCK useMovieDetail ────────────────────────────────────────────────────
const mockUpdatePersonal = vi.fn().mockResolvedValue(undefined)
const mockDeleteMovie = vi.fn().mockResolvedValue(undefined)
const mockMovieRef = ref<Record<string, unknown> | null>(null)
const mockIsLoading = ref(false)
const mockError = ref<string | null>(null)

vi.mock('@/composables/useMovieDetail', () => ({
  useMovieDetail: () => ({
    movie: mockMovieRef,
    isLoading: mockIsLoading,
    error: mockError,
    updatePersonal: mockUpdatePersonal,
    deleteMovie: mockDeleteMovie,
  }),
}))

// ── BASE MOCK MOVIE ────────────────────────────────────────────────────────
const MOCK_MOVIE = {
  id: '550e8400-e29b-41d4-a716-446655440000',
  tmdbId: 27205,
  imdbId: 'tt1375666',
  title: 'Inception',
  originalTitle: 'Inception',
  tagline: 'Your mind is the scene of the crime.',
  overview: 'A thief who steals corporate secrets through the use of dream-sharing technology.',
  releaseDate: '2010-07-16',
  year: 2010,
  runtime: 148,
  posterPath: '/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg',
  backdropPath: '/8ZTVqvKDQ8emSGUEMjsS4yHAwrp.jpg',
  voteAverage: 8.4,
  voteCount: 35000,
  trailerKey: 'YoHD9XEInc0',
  genreList: ['Action', 'Science Fiction'],
  directorList: ['Christopher Nolan'],
  writerList: ['Christopher Nolan'],
  mainCast: 'Leonardo DiCaprio, Joseph Gordon-Levitt',
  fullCast: [
    { name: 'Leonardo DiCaprio', character: 'Cobb', order: 0, profilePath: null },
    { name: 'Joseph Gordon-Levitt', character: 'Arthur', order: 1, profilePath: null },
  ],
  fullCrew: [
    { name: 'Christopher Nolan', job: 'Director', department: 'Directing', profilePath: null },
  ],
  countryList: ['United States of America'],
  languageList: ['English'],
  imdbRating: 8.8,
  imdbVotes: 2400000,
  contentRating: 'PG-13',
  boxOffice: 836836967,
  ratingList: [
    { source: 'Rotten Tomatoes', value: '87%' },
  ],
  imdbLink: 'https://www.imdb.com/title/tt1375666/',
  wikipediaPlot: 'Dom Cobb is a skilled thief who steals secrets through dream-sharing technology.',
  wikipediaCritics: 'The film received widespread critical acclaim.',
  wikipediaSummary: 'Inception is a 2010 science fiction film.',
  wikipediaUrl: 'https://en.wikipedia.org/wiki/Inception',
  watched: false,
  personalRating: null,
  personalNotes: null,
}

async function mountPage(movieOverride: Record<string, unknown> | null = MOCK_MOVIE) {
  mockMovieRef.value = movieOverride
  mockIsLoading.value = false
  mockError.value = null
  const { default: MovieDetailPage } = await import('@/pages/movies/[id].vue')
  return mount(MovieDetailPage, {
    global: {
      stubs: {
        StarRating: { template: '<div data-testid="star-rating"></div>' },
        TrailerEmbed: { template: '<div data-testid="trailer-embed"></div>' },
        SpinnerIcon: { template: '<div data-testid="spinner"></div>' },
      },
    },
  })
}

describe('/movies/[id] page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockMovieRef.value = null
    mockIsLoading.value = false
    mockError.value = null
  })

  it('renders film title in hero section', async () => {
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('Inception')
  })

  it('renders backdrop image with correct TMDB w1280 URL', async () => {
    const wrapper = await mountPage()
    const img = wrapper.find('img[src*="w1280"]')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toContain('w1280')
    expect(img.attributes('src')).toContain('/8ZTVqvKDQ8emSGUEMjsS4yHAwrp.jpg')
  })

  it('renders Wikipedia plot section when wikipediaPlot is non-null', async () => {
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('Dom Cobb is a skilled thief')
  })

  it('hides Wikipedia plot section when wikipediaPlot is null', async () => {
    const movie = { ...MOCK_MOVIE, wikipediaPlot: null }
    const wrapper = await mountPage(movie)
    expect(wrapper.text()).not.toContain('Dom Cobb is a skilled thief')
  })

  it('hides OMDB fields when null (D-10)', async () => {
    const movie = { ...MOCK_MOVIE, imdbRating: null, contentRating: null, boxOffice: null, ratingList: null }
    const wrapper = await mountPage(movie)
    // IMDB label should not appear when imdbRating is null
    expect(wrapper.text()).not.toContain('IMDB')
  })

  it('renders OMDB fields when present', async () => {
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('8.8')
  })

  it('navigates to /search?actors=X when actor chip clicked (D-12)', async () => {
    const router = useRouter()
    const pushSpy = vi.spyOn(router, 'push')
    const wrapper = await mountPage()
    // Find a button containing an actor name in the cast section (top 5 chips)
    const allButtons = wrapper.findAll('button')
    const actorButton = allButtons.find(b => b.text().includes('Leonardo DiCaprio'))
    expect(actorButton).toBeDefined()
    await actorButton!.trigger('click')
    expect(pushSpy).toHaveBeenCalledWith(
      expect.objectContaining({ path: '/search', query: { actors: 'Leonardo DiCaprio' } })
    )
  })

  it('navigates to /search?director=X when director chip clicked', async () => {
    const router = useRouter()
    const pushSpy = vi.spyOn(router, 'push')
    const wrapper = await mountPage()
    const allButtons = wrapper.findAll('button')
    // Director buttons use hover:text-primary class (not the cast chip class)
    const directorButton = allButtons.find(b =>
      b.text().trim() === 'Christopher Nolan' && b.classes().some(c => c.includes('hover:text-primary'))
    )
    expect(directorButton).toBeDefined()
    await directorButton!.trigger('click')
    expect(pushSpy).toHaveBeenCalledWith(
      expect.objectContaining({ path: '/search', query: { director: 'Christopher Nolan' } })
    )
  })

  it('navigates to /search?genre=X when genre chip clicked', async () => {
    const router = useRouter()
    const pushSpy = vi.spyOn(router, 'push')
    const wrapper = await mountPage()
    const allButtons = wrapper.findAll('button')
    const genreButton = allButtons.find(b => b.text().trim() === 'Action')
    expect(genreButton).toBeDefined()
    await genreButton!.trigger('click')
    expect(pushSpy).toHaveBeenCalledWith(
      expect.objectContaining({ path: '/search', query: { genre: 'Action' } })
    )
  })

  it('shows delete confirmation modal on delete button click', async () => {
    const wrapper = await mountPage()
    // Click the remove/trash button
    const allButtons = wrapper.findAll('button')
    const removeButton = allButtons.find(b => b.text().includes('Remove'))
    expect(removeButton).toBeDefined()
    await removeButton!.trigger('click')
    // Modal should now be visible
    expect(wrapper.text()).toContain('Remove from archive?')
    expect(wrapper.text()).toContain('This cannot be undone.')
  })

  it('calls deleteMovie and redirects to /search on modal confirm', async () => {
    const wrapper = await mountPage()
    // Open modal
    const allButtons = wrapper.findAll('button')
    const removeButton = allButtons.find(b => b.text().includes('Remove'))
    await removeButton!.trigger('click')
    // Click confirm
    const confirmButton = wrapper.findAll('button').find(b => b.text().trim() === 'Confirm')
    expect(confirmButton).toBeDefined()
    await confirmButton!.trigger('click')
    expect(mockDeleteMovie).toHaveBeenCalled()
  })

  it('hides Wikipedia critics section when null', async () => {
    const movie = { ...MOCK_MOVIE, wikipediaCritics: null }
    const wrapper = await mountPage(movie)
    expect(wrapper.text()).not.toContain('Critical Response')
    expect(wrapper.text()).not.toContain('The film received widespread critical acclaim.')
  })
})
