-- V10__create_bulk_import_batch.sql
-- Follows the exact style of V9__create_bulk_import_line.sql:
-- UUID PK with gen_random_uuid() default, FK to users(id), TIMESTAMPTZ created_at.
CREATE TABLE bulk_import_batch (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    total_lines INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Supports "list a user's past batches, most recent first" (D-03 batch-list page).
CREATE INDEX idx_bulk_import_batch_user_id ON bulk_import_batch(user_id, created_at DESC);

ALTER TABLE bulk_import_line
    ADD COLUMN batch_id UUID REFERENCES bulk_import_batch(id),
    ADD COLUMN poster_path VARCHAR(500);

-- Supports "list all lines for a batch" (D-03 batch-detail page).
CREATE INDEX idx_bulk_import_line_batch_id ON bulk_import_line(batch_id);
-- batch_id is nullable: existing Phase-10-era rows (created before this migration) have no batch.
-- They are excluded from the new batch-list/detail views (D-03 scopes "past bulk-import batches",
-- and a NULL-batch row was never part of a trackable batch to begin with). A re-upload of the same
-- line later will assign it to whatever batch triggers the next upsertLine() call, per the existing
-- find-or-update-in-place dedup behavior (BulkImportService.upsertLine()) — no separate backfill
-- migration needed.
