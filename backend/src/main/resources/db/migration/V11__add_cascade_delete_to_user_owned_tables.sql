-- V11__add_cascade_delete_to_user_owned_tables.sql
--
-- movies.user_id (V6), bulk_import_line.user_id (V9), and bulk_import_batch.user_id (V10)
-- were all created as plain `REFERENCES users(id)` without ON DELETE CASCADE — unlike every
-- other user-owned child table (user_api_keys, email_verification_tokens,
-- password_reset_tokens, email_change_tokens, refresh_tokens — all V3/V5, all
-- ON DELETE CASCADE). Deleting a users row while any of these child rows still reference it
-- throws a DataIntegrityViolationException (e.g. bulk_import_line_user_id_fkey), which is the
-- confirmed root cause of the "fullsuite-fk-isolation-flakiness" debug session: the full
-- ./gradlew test suite shares ONE Testcontainers Postgres instance across every test class with
-- no cross-class cleanup, so residual bulk-import rows left behind by BulkImportControllerTest
-- crash the next class's plain userRepository.deleteAll(). The identical bug independently
-- reproduces in production's TestSetupController (/test/setup, @Profile("test"), used by
-- Playwright E2E setup), which already had to manually pre-delete movies/user_api_keys rows
-- before deleting a user — confirming this is a genuine schema defect, not just a test-hygiene
-- gap. Aligning all three tables with the CASCADE pattern already established everywhere else.
--
-- bulk_import_line.batch_id -> bulk_import_batch.id (added in V10) is also given
-- ON DELETE CASCADE so that cascading a user delete through bulk_import_batch cannot leave a
-- bulk_import_line row referencing an about-to-be-deleted batch, regardless of which of the two
-- parallel cascade paths (via bulk_import_line.user_id or via bulk_import_batch.user_id) Postgres
-- happens to process first.
--
-- Constraint names below are Postgres's standard auto-generated {table}_{column}_fkey names for
-- these unnamed inline REFERENCES clauses (confirmed directly by the original bug report's own
-- error text: "bulk_import_line_user_id_fkey").

ALTER TABLE movies
    DROP CONSTRAINT movies_user_id_fkey,
    ADD CONSTRAINT movies_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE bulk_import_line
    DROP CONSTRAINT bulk_import_line_user_id_fkey,
    ADD CONSTRAINT bulk_import_line_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE bulk_import_batch
    DROP CONSTRAINT bulk_import_batch_user_id_fkey,
    ADD CONSTRAINT bulk_import_batch_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE bulk_import_line
    DROP CONSTRAINT bulk_import_line_batch_id_fkey,
    ADD CONSTRAINT bulk_import_line_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES bulk_import_batch(id) ON DELETE CASCADE;
