import { http, HttpResponse } from 'msw'

export const moviesHandlers = [
  // GET /api/movies/search?q= — TMDB search proxy
  http.get('/api/movies/search', ({ request }) => {
    const url = new URL(request.url)
    const q = url.searchParams.get('q') ?? ''
    if (q === 'no-key') {
      return HttpResponse.json(
        { message: 'No TMDB key configured. Add your key in Settings.' },
        { status: 422 },
      )
    }
    return HttpResponse.json([
      { tmdbId: 27205, title: 'Inception', year: 2010, posterPath: '/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg' },
    ])
  }),

  // POST /api/movies/save — returns 202 with movie UUID
  http.post('/api/movies/save', () =>
    HttpResponse.json({ id: 'test-movie-uuid-1234' }, { status: 202 }),
  ),

  // GET /api/movies/saved-ids — returns list of saved TMDB IDs for the current user
  http.get('/api/movies/saved-ids', () =>
    HttpResponse.json({ tmdbIds: [27205] }),
  ),

  // GET /api/movies/:id/status — returns status (default SUCCESS for happy path)
  http.get('/api/movies/:id/status', ({ params }) => {
    if (params.id === 'error-movie-uuid') {
      return HttpResponse.json({ id: params.id, status: 'ERROR', title: 'Inception' })
    }
    if (params.id === 'pending-movie-uuid') {
      return HttpResponse.json({ id: params.id, status: 'PENDING', title: 'Inception' })
    }
    return HttpResponse.json({ id: params.id, status: 'SUCCESS', title: 'Inception' })
  }),
]
