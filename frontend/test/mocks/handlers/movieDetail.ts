import { http, HttpResponse } from 'msw'

export const MOCK_MOVIE_DETAIL = {
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
  genreList: ['Action', 'Science Fiction', 'Adventure'],
  directorList: ['Christopher Nolan'],
  writerList: ['Christopher Nolan'],
  mainCast: 'Leonardo DiCaprio, Joseph Gordon-Levitt, Elliot Page',
  fullCast: [
    { name: 'Leonardo DiCaprio', character: 'Cobb', order: 0, profilePath: '/wo2hJpn04vbtmh0B9utCFdsQhxM.jpg' },
    { name: 'Joseph Gordon-Levitt', character: 'Arthur', order: 1, profilePath: '/pEFcgrQWxHLMARIFgkn9E5hCJfx.jpg' },
  ],
  fullCrew: [
    { name: 'Christopher Nolan', job: 'Director', department: 'Directing', profilePath: '/xuAIuYSkoIAqCz5aAJWKhy1KCQX.jpg' },
    { name: 'Christopher Nolan', job: 'Writer', department: 'Writing', profilePath: '/xuAIuYSkoIAqCz5aAJWKhy1KCQX.jpg' },
  ],
  countryList: ['United States of America', 'United Kingdom'],
  languageList: ['English', 'Japanese', 'French'],
  imdbRating: 8.8,
  imdbVotes: 2400000,
  contentRating: 'PG-13',
  boxOffice: 836836967,
  ratingList: [
    { source: 'Internet Movie Database', value: '8.8/10' },
    { source: 'Rotten Tomatoes', value: '87%' },
    { source: 'Metacritic', value: '74/100' },
  ],
  imdbLink: 'https://www.imdb.com/title/tt1375666/',
  wikipediaPlot: 'Dom Cobb is a skilled thief...',
  wikipediaCritics: 'The film received widespread critical acclaim...',
  wikipediaSummary: 'Inception is a 2010 science fiction action film...',
  wikipediaUrl: 'https://en.wikipedia.org/wiki/Inception',
  watched: false,
  personalRating: null,
  personalNotes: null,
}

export const movieDetailHandlers = [
  // GET /api/movies/:id — full movie detail
  http.get('/api/movies/:id', ({ params }) => {
    return HttpResponse.json({ ...MOCK_MOVIE_DETAIL, id: params.id as string })
  }),

  // PATCH /api/movies/:id/personal — update watched, personalRating, personalNotes
  http.patch('/api/movies/:id/personal', () => {
    return new HttpResponse(null, { status: 204 })
  }),

  // DELETE /api/movies/:id — delete movie from archive
  http.delete('/api/movies/:id', () => {
    return new HttpResponse(null, { status: 204 })
  }),
]
