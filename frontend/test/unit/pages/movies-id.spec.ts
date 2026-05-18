import { describe, it } from 'vitest'

describe('/movies/[id] page', () => {
  it.todo('renders film title in hero section')
  it.todo('renders backdrop image with correct TMDB w1280 URL')
  it.todo('renders poster overlay with correct TMDB w342 URL')
  it.todo('renders Wikipedia plot section when wikipediaPlot is non-null')
  it.todo('hides Wikipedia plot section when wikipediaPlot is null')
  it.todo('renders OMDB fields (imdbRating, contentRating) when present')
  it.todo('hides OMDB fields when null (D-10)')
  it.todo('navigates to /search?actors=X when actor chip clicked (D-12)')
  it.todo('navigates to /search?director=X when director chip clicked (D-12)')
  it.todo('navigates to /search?genre=X when genre chip clicked (D-12)')
  it.todo('shows delete confirmation modal on delete button click (D-13)')
  it.todo('calls deleteMovie and redirects to /search on modal confirm (D-13)')
})
