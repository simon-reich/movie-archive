import { describe, it } from 'vitest'

// TODO: implement after useMovies composable is created (Plan 03-05)
describe('useMovies', () => {
  it.todo('searchTmdb returns array of TmdbSearchResult on success')
  it.todo('searchTmdb throws on 422 when no TMDB key configured')
  it.todo('saveMovie sends POST /api/movies/save and returns { id }')
  it.todo('getStatus returns MovieStatusResponse for given movieId')
  it.todo('pollUntilDone resolves SUCCESS and stops polling')
  it.todo('pollUntilDone resolves ERROR state without throwing')
})
