package de.moviearchive.bulkimport;

import de.moviearchive.enrichment.EnrichmentService;
import de.moviearchive.enrichment.TmdbClient;
import de.moviearchive.movie.MovieService;
import de.moviearchive.movie.dto.MovieInitiateResult;
import de.moviearchive.movie.dto.TmdbSearchResultItem;
import de.moviearchive.user.User;
import de.moviearchive.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BulkImportServiceTest {

    @Mock
    private BulkImportLineRepository bulkImportLineRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private MovieService movieService;

    @Mock
    private EnrichmentService enrichmentService;

    @Mock
    private BulkImportBatchRepository bulkImportBatchRepository;

    @Mock
    private BulkImportProgressService progressService;

    private final ImportLineParser importLineParser = new ImportLineParser();

    private BulkImportService bulkImportService;

    private User user;
    private static final String EMAIL = "bulkimport-test@example.com";
    private static final String TMDB_KEY = "test-tmdb-key";

    @BeforeEach
    void setUp() {
        bulkImportService = new BulkImportService(
                bulkImportLineRepository, userRepository, tmdbClient, movieService,
                enrichmentService, importLineParser,
                bulkImportBatchRepository, progressService, null);
        // No Spring context in this unit test, so there's no real @Lazy proxy to inject for the
        // "self" self-invocation fix (CR-01) — wire the instance to itself so runImport()'s
        // self.processLine(...) calls resolve the same way the AOP proxy would in production
        // (mirrors WikiReloadServiceTest's convention).
        ReflectionTestUtils.setField(bulkImportService, "self", bulkImportService);

        user = new User(EMAIL, "hash");

        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        // CR-01 (16-01): echo back the requested batchId as the returned batch's id — without
        // this, every call would share the same (null) batch id and the new batch-scoped tests
        // below (shouldNotReuseRow_acrossDifferentBatchIds) couldn't distinguish batchIdA from
        // batchIdB at the mock-interaction level.
        lenient().when(bulkImportBatchRepository.getReferenceById(any())).thenAnswer(inv -> {
            BulkImportBatch batch = new BulkImportBatch(user, 1);
            batch.setId(inv.getArgument(0));
            return batch;
        });

        // CR-01 (16-01): stub the batch-scoped repository methods that findExistingRow()/the
        // existingSaved fast-path now call, using any() for both userId and batchId — mirrors
        // the pre-existing non-batch-scoped stubs this replaces.
        lenient().when(bulkImportLineRepository.findByUserIdAndBatchIdAndNormalizedTitleAndYearAndStatus(
                        any(), any(), any(), any(), eq(BulkImportLineStatus.SAVED)))
                .thenReturn(Optional.empty());
        lenient().when(bulkImportLineRepository.findByUserIdAndBatchIdAndNormalizedTitleAndYear(
                        any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(bulkImportLineRepository.findByUserIdAndBatchIdAndNormalizedTitleAndYearIsNull(
                        any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(bulkImportLineRepository.findByUserIdAndBatchIdAndRawLineAndYearIsNull(
                        any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(bulkImportLineRepository.save(any(BulkImportLine.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(movieService.initiate(anyString(), anyInt()))
                .thenReturn(new MovieInitiateResult(UUID.randomUUID(), true));
    }

    private TmdbSearchResultItem item(int tmdbId, String title, String originalTitle, Integer year) {
        return new TmdbSearchResultItem(tmdbId, title, originalTitle, year, "/poster.jpg");
    }

    @Test
    void shouldSave_whenSingleResultRegardlessOfYearMismatch() {
        // D-10: results.size() == 1 short-circuits before any year check — a single overall
        // TMDB result is taken directly and saved even when its year (1999) doesn't match the
        // requested year (2010).
        when(tmdbClient.search("Ghost Movie", TMDB_KEY))
                .thenReturn(List.of(item(1, "Ghost Movie", "Ghost Movie", 1999)));

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Ghost Movie;;2010", UUID.randomUUID(), false);

        verify(movieService).initiate(EMAIL, 1);

        ArgumentCaptor<BulkImportLine> captor = ArgumentCaptor.forClass(BulkImportLine.class);
        verify(bulkImportLineRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BulkImportLineStatus.SAVED);
        assertThat(captor.getValue().getTmdbId()).isEqualTo(1);
    }

    @Test
    void shouldRecordNotFound_whenTmdbSearchReturnsZeroResults() {
        // D-12: NOT_FOUND only fires on a genuine zero-result search now.
        when(tmdbClient.search("Nonexistent Movie", TMDB_KEY)).thenReturn(List.of());

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Nonexistent Movie;;2010", UUID.randomUUID(), false);

        verify(movieService, never()).initiate(anyString(), anyInt());

        ArgumentCaptor<BulkImportLine> captor = ArgumentCaptor.forClass(BulkImportLine.class);
        verify(bulkImportLineRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BulkImportLineStatus.NOT_FOUND);
    }

    @Test
    void shouldSave_whenMultipleResultsButExactlyOneExactTitleAndYearMatch() {
        // D-11: three candidates, only one has BOTH title AND year exactly matching the parsed
        // line — taken directly via the exact-match step, without needing original-title
        // narrowing.
        when(tmdbClient.search("Robin Hood", TMDB_KEY)).thenReturn(List.of(
                item(1001, "Robin Hood", "Robin Hood", 1922),
                item(1002, "Robin Hood", "Robin des Bois", 2010),
                item(1003, "Robin Hood Jr", "Robin Hood Jr", 2010)));

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Robin Hood;;2010", UUID.randomUUID(), false);

        verify(movieService).initiate(EMAIL, 1002);

        ArgumentCaptor<BulkImportLine> captor = ArgumentCaptor.forClass(BulkImportLine.class);
        verify(bulkImportLineRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BulkImportLineStatus.SAVED);
        assertThat(captor.getValue().getTmdbId()).isEqualTo(1002);
    }

    @Test
    void shouldSave_whenParsedTitleMatchesCandidateOriginalTitleField() {
        // D-11's "either field" clause: the parsed title matches a candidate's originalTitle()
        // (not its title()), uniquely among multiple same-year candidates — taken via the
        // exact-match step, not the later original-title-narrowing fallback.
        when(tmdbClient.search("Robin des Bois", TMDB_KEY)).thenReturn(List.of(
                item(1001, "Robin Hood", "Robin des Bois", 2010),
                item(1002, "Robin Hood 2", "Robin Hood 2", 2010)));

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Robin des Bois;;2010", UUID.randomUUID(), false);

        verify(movieService).initiate(EMAIL, 1001);

        ArgumentCaptor<BulkImportLine> captor = ArgumentCaptor.forClass(BulkImportLine.class);
        verify(bulkImportLineRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BulkImportLineStatus.SAVED);
        assertThat(captor.getValue().getTmdbId()).isEqualTo(1001);
    }

    @Test
    void shouldMarkAmbiguous_whenMultipleResultsNoneMatchRequestedYear() {
        // WR-01: before the 16-01 matching rework, "multiple results, zero of them match the
        // requested year" mapped to NOT_FOUND (yearMatches.isEmpty() short-circuit). That branch
        // no longer exists — this case now falls through exactMatches (empty, no year matches at
        // all) and the originalTitle-narrowed yearMatches fallback (also empty) to land on
        // AMBIGUOUS, presenting the user a candidate list none of which actually match their
        // year. This test pins down that this is the actual (if previously undocumented and
        // untested) current behavior.
        when(tmdbClient.search("Old Movie", TMDB_KEY)).thenReturn(List.of(
                item(1, "Old Movie", "Old Movie", 1950),
                item(2, "Old Movie", "Old Movie", 1965)));

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Old Movie;;2010", UUID.randomUUID(), false);

        verify(movieService, never()).initiate(anyString(), anyInt());
        ArgumentCaptor<BulkImportLine> captor = ArgumentCaptor.forClass(BulkImportLine.class);
        verify(bulkImportLineRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BulkImportLineStatus.AMBIGUOUS);
    }

    @Test
    void shouldSave_whenExactlyOneYearMatchingCandidate() {
        when(tmdbClient.search("Inception", TMDB_KEY))
                .thenReturn(List.of(item(27205, "Inception", "Inception", 2010)));

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Inception;;2010", UUID.randomUUID(), false);

        verify(movieService).initiate(EMAIL, 27205);

        ArgumentCaptor<BulkImportLine> captor = ArgumentCaptor.forClass(BulkImportLine.class);
        verify(bulkImportLineRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BulkImportLineStatus.SAVED);
        assertThat(captor.getValue().getTmdbId()).isEqualTo(27205);
        // D-04: poster_path is captured from the already-fetched TMDB match, zero extra calls.
        assertThat(captor.getValue().getPosterPath()).isEqualTo("/poster.jpg");
    }

    @Test
    void shouldMarkAmbiguous_whenMultipleYearMatchesAndNoOriginalTitle() {
        when(tmdbClient.search("Robin Hood", TMDB_KEY)).thenReturn(List.of(
                item(1001, "Robin Hood", "Robin Hood", 2010),
                item(1002, "Robin Hood", "Robin des Bois", 2010)));

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Robin Hood;;2010", UUID.randomUUID(), false);

        verify(movieService, never()).initiate(anyString(), anyInt());

        ArgumentCaptor<BulkImportLine> captor = ArgumentCaptor.forClass(BulkImportLine.class);
        verify(bulkImportLineRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BulkImportLineStatus.AMBIGUOUS);
    }

    @Test
    void shouldSave_whenOriginalTitleNarrowsAmbiguousCandidatesToOne() {
        when(tmdbClient.search("Robin Hood", TMDB_KEY)).thenReturn(List.of(
                item(1001, "Robin Hood", "Robin Hood", 2010),
                item(1002, "Robin Hood", "Robin des Bois", 2010)));

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Robin Hood;Robin des Bois;2010", UUID.randomUUID(), false);

        verify(movieService).initiate(EMAIL, 1002);

        ArgumentCaptor<BulkImportLine> captor = ArgumentCaptor.forClass(BulkImportLine.class);
        verify(bulkImportLineRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BulkImportLineStatus.SAVED);
        assertThat(captor.getValue().getTmdbId()).isEqualTo(1002);
    }

    @Test
    void shouldStayAmbiguous_whenOriginalTitleDoesNotNarrowToOne() {
        when(tmdbClient.search("Robin Hood", TMDB_KEY)).thenReturn(List.of(
                item(1001, "Robin Hood", "Robin Hood", 2010),
                item(1002, "Robin Hood", "Robin des Bois", 2010)));

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Robin Hood;No Match Title;2010", UUID.randomUUID(), false);

        verify(movieService, never()).initiate(anyString(), anyInt());

        ArgumentCaptor<BulkImportLine> captor = ArgumentCaptor.forClass(BulkImportLine.class);
        verify(bulkImportLineRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BulkImportLineStatus.AMBIGUOUS);
    }

    @Test
    void shouldCapTitleAt500Characters_beforeSaving() {
        String longTitle = "A".repeat(600);
        when(tmdbClient.search(longTitle, TMDB_KEY))
                .thenReturn(List.of(item(5000, longTitle, longTitle, 2020)));

        bulkImportService.processLine(EMAIL, TMDB_KEY, longTitle + ";;2020", UUID.randomUUID(), false);

        ArgumentCaptor<BulkImportLine> captor = ArgumentCaptor.forClass(BulkImportLine.class);
        verify(bulkImportLineRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).hasSize(500);
    }

    @Test
    void shouldSkipWikipediaForEveryMatchedMovie_inPass2() {
        // Distinct MovieInitiateResult per tmdbId — the @BeforeEach lenient stub returns a
        // single fixed UUID for every movieService.initiate() call, which would otherwise
        // collapse both matched lines onto the same movieId key.
        when(movieService.initiate(EMAIL, 27205)).thenReturn(new MovieInitiateResult(UUID.randomUUID(), true));
        when(movieService.initiate(EMAIL, 603)).thenReturn(new MovieInitiateResult(UUID.randomUUID(), true));
        when(tmdbClient.search("Inception", TMDB_KEY))
                .thenReturn(List.of(item(27205, "Inception", "Inception", 2010)));
        when(tmdbClient.search("The Matrix", TMDB_KEY))
                .thenReturn(List.of(item(603, "The Matrix", "The Matrix", 1999)));

        bulkImportService.runImport(EMAIL, TMDB_KEY,
                List.of("Inception;;2010", "The Matrix;;1999"), UUID.randomUUID(), false);

        verify(enrichmentService, times(2)).enrich(any(UUID.class), eq(true));
        verify(enrichmentService, never()).enrich(any(UUID.class));
    }

    @Test
    void shouldCallComplete_evenWhenPass1SleepIsInterrupted() {
        // WR-04 regression: an interrupted Thread.sleep during Pass 1 pacing used to `return`
        // immediately, skipping progressService.complete(batchId) entirely and leaving any SSE
        // subscriber frozen on the last publish()'d state forever. Pre-interrupting the test
        // thread before runImport() makes Thread.sleep() throw InterruptedException on its very
        // first call (Thread.sleep checks the interrupt flag regardless of the requested delay),
        // deterministically exercising the interrupted-mid-Pass-1 path without any real waiting.
        UUID batchId = UUID.randomUUID();
        when(tmdbClient.search(anyString(), eq(TMDB_KEY))).thenReturn(List.of());

        Thread.currentThread().interrupt();
        try {
            bulkImportService.runImport(EMAIL, TMDB_KEY,
                    List.of("Movie A;;2020", "Movie B;;2021"), batchId, false);
        } finally {
            Thread.interrupted(); // clear the flag so it never leaks into a later test
        }

        verify(progressService).complete(batchId);
        // Pass 2 must never run once Pass 1 was interrupted — nothing was matched anyway
        // (tmdbClient returns no results here), but this pins down the "abandon Pass 2" behavior.
        verify(enrichmentService, never()).enrich(any(UUID.class), anyBoolean());
    }

    @Test
    void shouldNotReuseRow_acrossDifferentBatchIds() {
        UUID batchIdA = UUID.randomUUID();
        UUID batchIdB = UUID.randomUUID();
        when(tmdbClient.search("Robin Hood", TMDB_KEY)).thenReturn(List.of(
                item(1001, "Robin Hood", "Robin Hood", 2010),
                item(1002, "Robin Hood", "Robin des Bois", 2010)));

        // Both calls land in the AMBIGUOUS branch (no originalTitle to narrow), each triggering
        // upsertLine() -> findExistingRow() -> the batch-scoped repository finder.
        bulkImportService.processLine(EMAIL, TMDB_KEY, "Robin Hood;;2010", batchIdA, false);
        bulkImportService.processLine(EMAIL, TMDB_KEY, "Robin Hood;;2010", batchIdB, false);

        // CR-01: each call's findExistingRow lookup must carry its OWN batchId — proving no
        // cross-batch row lookup occurs at the mock-interaction level.
        verify(bulkImportLineRepository).findByUserIdAndBatchIdAndNormalizedTitleAndYear(
                eq(user.getId()), eq(batchIdA), eq("robin hood"), eq(2010));
        verify(bulkImportLineRepository).findByUserIdAndBatchIdAndNormalizedTitleAndYear(
                eq(user.getId()), eq(batchIdB), eq("robin hood"), eq(2010));

        ArgumentCaptor<UUID> batchIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(bulkImportLineRepository, times(2)).findByUserIdAndBatchIdAndNormalizedTitleAndYear(
                eq(user.getId()), batchIdCaptor.capture(), eq("robin hood"), eq(2010));
        assertThat(batchIdCaptor.getAllValues()).containsExactly(batchIdA, batchIdB);
    }

    @Test
    void shouldReuseRow_onSameBatchReupload() {
        UUID batchId = UUID.randomUUID();
        when(tmdbClient.search("Robin Hood", TMDB_KEY)).thenReturn(List.of(
                item(1001, "Robin Hood", "Robin Hood", 2010),
                item(1002, "Robin Hood", "Robin des Bois", 2010)));

        // Simulates the row the first call creates: absent on the first lookup, present
        // (SAME batch, same normalized title/year) on the second.
        BulkImportLine existingRow = new BulkImportLine(user, "Robin Hood;;2010");
        when(bulkImportLineRepository.findByUserIdAndBatchIdAndNormalizedTitleAndYear(
                        eq(user.getId()), eq(batchId), eq("robin hood"), eq(2010)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingRow));

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Robin Hood;;2010", batchId, false);
        bulkImportService.processLine(EMAIL, TMDB_KEY, "Robin Hood;;2010", batchId, false);

        // The single-batch re-upload dedup path still functions after batch-scoping: the second
        // call's save() is invoked with the SAME row instance, not a freshly-constructed one.
        ArgumentCaptor<BulkImportLine> captor = ArgumentCaptor.forClass(BulkImportLine.class);
        verify(bulkImportLineRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1)).isSameAs(existingRow);
    }
}
