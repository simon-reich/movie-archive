import { describe, it } from 'vitest'

// TODO: implement after add.vue is created (Plan 03-05)
describe('/add page', () => {
  it.todo('renders search form with query input and Search button')
  it.todo('shows poster grid after successful TMDB search')
  it.todo('clicking a poster shows spinner overlay on that poster')
  it.todo('polling resolves SUCCESS: shows checkmark then removes poster from grid')
  it.todo('polling resolves ERROR: shows red X with error message on poster')
  it.todo('navigating away clears all polling intervals')
  it.todo('shows 422 message when no TMDB key configured')
})
