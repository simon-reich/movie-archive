import { http, HttpResponse } from 'msw'

export const MOCK_MOVIE_DETAIL = {
  id: '550e8400-e29b-41d4-a716-446655440000',
  tmdbId: 27205,
  title: 'Inception',
  originalTitle: 'Inception',
  tagline: 'Your mind is the scene of the crime.',
  overview: 'A thief who steals corporate secrets through dream-sharing technology.',
  releaseDate: '2010-07-16',
  year: 2010,
  runtime: 148,
  posterPath: '/9gk7adHYeDvHkCSEqAvQNLV5Uge.jpg',
  backdropPath: '/s3TBrRGB1iav7gFOCNx3H31MoES.jpg',
  voteAverage: 8.4,
  voteCount: 35000,
  trailerKey: 'YoHD9XEInc0',
  genreList: ['Action', 'Sci-Fi', 'Thriller'],
  directorList: ['Christopher Nolan'],
  writerList: ['Christopher Nolan'],
  mainCast: 'Leonardo DiCaprio, Joseph Gordon-Levitt, Ellen Page',
  fullCast: [
    { name: 'Leonardo DiCaprio', character: 'Cobb', order: 0, profilePath: null },
    { name: 'Joseph Gordon-Levitt', character: 'Arthur', order: 1, profilePath: null },
  ],
  fullCrew: [
    { name: 'Christopher Nolan', job: 'Director', department: 'Directing', profilePath: null },
  ],
  countryList: ['US', 'GB'],
  languageList: ['en', 'ja', 'fr'],
  imdbRating: 8.8,
  imdbVotes: 2400000,
  contentRating: 'PG-13',
  boxOffice: 836836967,
  ratingList: [
    { Source: 'Internet Movie Database', Value: '8.8/10' },
    { Source: 'Rotten Tomatoes', Value: '87%' },
  ],
  imdbLink: 'https://www.imdb.com/title/tt1375666',
  wikipediaPlot: 'Dom Cobb is a skilled thief...',
  wikipediaCritics: 'Critics praised the film...',
  wikipediaSummary: 'Inception is a 2010 film...',
  wikipediaUrl: 'https://en.wikipedia.org/wiki/Inception',
  watched: false,
  personalRating: null,
  personalNotes: null,
}

export const movieDetailHandlers = [
  http.get('/api/movies/:id', ({ params }) => {
    return HttpResponse.json({ ...MOCK_MOVIE_DETAIL, id: params.id as string })
  }),
  http.patch('/api/movies/:id/personal', () => {
    return new HttpResponse(null, { status: 204 })
  }),
  http.delete('/api/movies/:id', () => {
    return new HttpResponse(null, { status: 204 })
  }),
]
