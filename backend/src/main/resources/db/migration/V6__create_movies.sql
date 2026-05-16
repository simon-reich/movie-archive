CREATE TABLE movies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    tmdb_id INTEGER NOT NULL,
    imdb_id VARCHAR(20),
    title VARCHAR(500),
    original_title VARCHAR(500),
    release_date DATE,
    runtime INTEGER,
    raw_tmdb_json JSONB,
    raw_omdb_json JSONB,
    wiki_plot TEXT,
    wiki_summary TEXT,
    wiki_critics TEXT,
    wiki_url TEXT,
    indexed_at TIMESTAMPTZ,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT movies_status_check CHECK (status IN ('PENDING', 'SUCCESS', 'ERROR')),
    UNIQUE (user_id, tmdb_id)
);
CREATE INDEX idx_movies_user_id ON movies(user_id);
CREATE INDEX idx_movies_status ON movies(user_id, status);
