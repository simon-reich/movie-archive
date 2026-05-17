import { http, HttpResponse } from 'msw'

export const searchHandlers = [
  // POST /api/search — full-text + faceted search
  http.post('/api/search', async ({ request: _request }) => {
    return HttpResponse.json({
      results: [
        {
          id: 'test-uuid-1',
          tmdbId: 27205,
          title: 'Inception',
          year: 2010,
          posterPath: '/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg',
          directorList: ['Christopher Nolan'],
          genreList: ['Sci-Fi', 'Thriller'],
          imdbRating: 8.8,
          runtime: 148,
        },
      ],
      total: 1,
      page: 0,
      totalPages: 1,
      hasMore: false,
    })
  }),

  // GET /api/dashboard — aggregate stats for the user's archive
  http.get('/api/dashboard', () => {
    return HttpResponse.json({
      totalFilms: 42,
      topGenres: [
        { name: 'Drama', count: 12 },
        { name: 'Thriller', count: 8 },
      ],
      languageBreakdown: [{ code: 'en', count: 30 }],
      imdbHistogram: [
        { label: '7-8', count: 15 },
        { label: '9-10', count: 5 },
      ],
      movieOfTheDay: {
        id: 'test-uuid-2',
        title: 'The Godfather',
        year: 1972,
        posterPath: '/xxx.jpg',
      },
      recentlyAdded: [
        {
          id: 'test-uuid-1',
          title: 'Inception',
          year: 2010,
          posterPath: '/yyy.jpg',
        },
      ],
    })
  }),

  // GET /api/search/autocomplete?field=&prefix= — autocomplete suggestions
  http.get('/api/search/autocomplete', () => {
    return HttpResponse.json({
      suggestions: ['Christopher Nolan'],
    })
  }),
]
