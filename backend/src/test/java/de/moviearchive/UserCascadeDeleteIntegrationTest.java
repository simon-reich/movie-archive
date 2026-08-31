package de.moviearchive;

import de.moviearchive.bulkimport.BulkImportBatch;
import de.moviearchive.bulkimport.BulkImportBatchRepository;
import de.moviearchive.bulkimport.BulkImportLine;
import de.moviearchive.bulkimport.BulkImportLineRepository;
import de.moviearchive.bulkimport.BulkImportLineStatus;
import de.moviearchive.movie.Movie;
import de.moviearchive.movie.MovieRepository;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression coverage for the "fullsuite-fk-isolation-flakiness" debug session, root cause A:
 * {@code movies.user_id} / {@code bulk_import_line.user_id} / {@code bulk_import_batch.user_id}
 * FKs to {@code users(id)} previously lacked {@code ON DELETE CASCADE} (unlike every other
 * user-owned child table — {@code user_api_keys}, the token tables), so deleting a user while
 * any of these child rows still existed threw a {@code DataIntegrityViolationException} (e.g.
 * {@code bulk_import_line_user_id_fkey}). This happened both in the full test suite (a class
 * leaving bulk-import residue behind for the next class's plain
 * {@code userRepository.deleteAll()}, since all test classes share one Testcontainers Postgres
 * instance for the whole JVM run) and independently in production's {@code TestSetupController}
 * {@code /test/setup} E2E-reset endpoint. Fixed in {@code V11__add_cascade_delete_to_user_owned_tables.sql}.
 *
 * <p>Deliberately does not extend {@code AbstractWireMockTest}/{@code AbstractOpenSearchTest} —
 * this is a pure DB-cascade behavior test and adding an unnecessary WireMock
 * {@code dynamicPort()} would create yet another uniquely-keyed Spring context (root cause B of
 * the same debug session).
 */
class UserCascadeDeleteIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private BulkImportBatchRepository bulkImportBatchRepository;

    @Autowired
    private BulkImportLineRepository bulkImportLineRepository;

    @BeforeEach
    @AfterEach
    void cleanDb() {
        bulkImportLineRepository.deleteAll();
        bulkImportBatchRepository.deleteAll();
        movieRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void shouldCascadeDeleteMoviesAndBulkImportRows_whenUserIsDeleted() {
        User user = userRepository.save(new User("cascade-delete@example.com", "hash"));

        Movie movie = movieRepository.save(new Movie(user, 1001));

        BulkImportBatch batch = bulkImportBatchRepository.save(new BulkImportBatch(user, 1));

        BulkImportLine line = new BulkImportLine(user, "The Matrix (1999)");
        line.setBatch(batch);
        line.setStatus(BulkImportLineStatus.SAVED);
        line = bulkImportLineRepository.save(line);

        UUID userId = user.getId();
        UUID movieId = movie.getId();
        UUID batchId = batch.getId();
        UUID lineId = line.getId();

        // Before the fix, this threw DataIntegrityViolationException: bulk_import_line_user_id_fkey
        // (or bulk_import_batch_user_id_fkey / movies_user_id_fkey, depending on delete order)
        // because none of these FKs had ON DELETE CASCADE.
        assertThatCode(() -> {
            userRepository.deleteById(userId);
            userRepository.flush();
        }).doesNotThrowAnyException();

        assertThat(userRepository.existsById(userId)).isFalse();
        assertThat(movieRepository.existsById(movieId)).isFalse();
        assertThat(bulkImportBatchRepository.existsById(batchId)).isFalse();
        assertThat(bulkImportLineRepository.existsById(lineId)).isFalse();
    }

    @Test
    void shouldCascadeDeleteBulkImportLines_whenBatchIsDeleted() {
        User user = userRepository.save(new User("cascade-batch-delete@example.com", "hash"));

        BulkImportBatch batch = bulkImportBatchRepository.save(new BulkImportBatch(user, 1));

        BulkImportLine line = new BulkImportLine(user, "Inception (2010)");
        line.setBatch(batch);
        line.setStatus(BulkImportLineStatus.SAVED);
        line = bulkImportLineRepository.save(line);

        UUID batchId = batch.getId();
        UUID lineId = line.getId();

        // Before the fix, this threw DataIntegrityViolationException: bulk_import_line_batch_id_fkey.
        assertThatCode(() -> {
            bulkImportBatchRepository.deleteById(batchId);
            bulkImportBatchRepository.flush();
        }).doesNotThrowAnyException();

        assertThat(bulkImportBatchRepository.existsById(batchId)).isFalse();
        assertThat(bulkImportLineRepository.existsById(lineId)).isFalse();
    }
}
