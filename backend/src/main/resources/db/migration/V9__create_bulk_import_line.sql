CREATE TABLE bulk_import_line (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(500),
    original_title VARCHAR(500),
    year INTEGER,
    tmdb_id INTEGER,
    status VARCHAR(20) NOT NULL,
    raw_line TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT bulk_import_line_status_check
        CHECK (status IN ('SAVED', 'AMBIGUOUS', 'NOT_FOUND', 'PARSE_ERROR'))
);
CREATE INDEX idx_bulk_import_line_user_id ON bulk_import_line(user_id);
-- Dedup lookup index — NOT a UNIQUE constraint, because a nullable year (PARSE_ERROR
-- lines with an unparseable year) cannot be safely enforced unique in Postgres (SQL
-- NULL = NULL is UNKNOWN, not TRUE). "One row per logical line" is enforced at the
-- application layer via find-then-update, not the database.
CREATE INDEX idx_bulk_import_line_dedup
    ON bulk_import_line(user_id, lower(title), year);
-- Lookup index for lines whose year failed to parse — identified by (user_id, raw_line)
-- instead of (title, year), since a null year is not a reliable identity.
CREATE INDEX idx_bulk_import_line_raw_line
    ON bulk_import_line(user_id, raw_line);
