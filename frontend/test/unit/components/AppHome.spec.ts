import { describe, expect, it, vi } from 'vitest'

// Mock $fetch before importing index.vue so onMounted fetch is safe
vi.stubGlobal('$fetch', vi.fn().mockResolvedValue({
  totalFilms: 0,
  topGenres: [],
  languageBreakdown: [],
  imdbHistogram: [],
  movieOfTheDay: null,
  recentlyAdded: [],
}))

describe('pages/index.vue', () => {
  it('exports a valid Vue component', async () => {
    const module = await import('~/pages/index.vue')
    expect(module.default).toBeDefined()
    expect(typeof module.default).toBe('object')
  })

  it('module has __name or __file property indicating it is a SFC', async () => {
    const module = await import('~/pages/index.vue')
    const component = module.default
    // Vue SFCs compiled by Vite have __name or setup function
    expect(component.__name || component.setup || component.__hmrId).toBeTruthy()
  })
})
