import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

const NUXT_LINK_STUB = {
  template: '<a :href="to"><slot /></a>',
  props: ['to'],
}

const MOCK_MOVIE = {
  id: '550e8400-e29b-41d4-a716-446655440000',
  title: 'Inception',
  year: 2010,
  posterPath: '/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg',
  genreList: ['Action', 'Science Fiction'],
  directorList: ['Christopher Nolan'],
}

describe('MovieCard component', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('NuxtLink wraps poster with route to /movies/{movie.id}', async () => {
    const { default: MovieCard } = await import('@/components/MovieCard.vue')
    const wrapper = mount(MovieCard, {
      props: { movie: MOCK_MOVIE as any },
      global: { stubs: { NuxtLink: NUXT_LINK_STUB } },
    })
    const link = wrapper.find('a')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe(`/movies/${MOCK_MOVIE.id}`)
  })

  it('posterUrl returns TMDB w300 URL when posterPath starts with "/"', async () => {
    const { default: MovieCard } = await import('@/components/MovieCard.vue')
    const wrapper = mount(MovieCard, {
      props: { movie: MOCK_MOVIE as any },
      global: { stubs: { NuxtLink: NUXT_LINK_STUB } },
    })
    const img = wrapper.find('img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe(
      `https://image.tmdb.org/t/p/w300${MOCK_MOVIE.posterPath}`
    )
  })

  it('posterUrl returns placeholder when posterPath is null', async () => {
    const { default: MovieCard } = await import('@/components/MovieCard.vue')
    const movieWithoutPoster = { ...MOCK_MOVIE, posterPath: null }
    const wrapper = mount(MovieCard, {
      props: { movie: movieWithoutPoster as any },
      global: { stubs: { NuxtLink: NUXT_LINK_STUB } },
    })
    const img = wrapper.find('img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('/placeholder-poster.svg')
  })

  it('genre chip click calls router.push with /search?genre=X', async () => {
    const { default: MovieCard } = await import('@/components/MovieCard.vue')
    const router = useRouter()
    const pushSpy = vi.spyOn(router, 'push')

    const wrapper = mount(MovieCard, {
      props: { movie: MOCK_MOVIE as any },
      global: { stubs: { NuxtLink: NUXT_LINK_STUB } },
    })

    const allButtons = wrapper.findAll('button')
    const genreButton = allButtons.find(b => b.text().trim() === 'Action')
    expect(genreButton).toBeDefined()
    await genreButton!.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith(
      expect.objectContaining({ path: '/search', query: { genre: 'Action' } })
    )
  })

  it('director chip click calls router.push with /search?director=X', async () => {
    const { default: MovieCard } = await import('@/components/MovieCard.vue')
    const router = useRouter()
    const pushSpy = vi.spyOn(router, 'push')

    const wrapper = mount(MovieCard, {
      props: { movie: MOCK_MOVIE as any },
      global: { stubs: { NuxtLink: NUXT_LINK_STUB } },
    })

    const allButtons = wrapper.findAll('button')
    const directorButton = allButtons.find(b => b.text().trim() === 'Christopher Nolan')
    expect(directorButton).toBeDefined()
    await directorButton!.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith(
      expect.objectContaining({ path: '/search', query: { director: 'Christopher Nolan' } })
    )
  })
})
