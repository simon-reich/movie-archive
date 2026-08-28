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

        lenient().when(bulkImportBatchRepository.getReferenceById(any()))
                .thenReturn(new BulkImportBatch(user, 1));

        lenient().when(bulkImportLineRepository.findByUserIdAndNormalizedTitleAndYearAndStatus(
                        any(), any(), any(), eq(BulkImportLineStatus.SAVED)))
                .thenReturn(Optional.empty());
        lenient().when(bulkImportLineRepository.findByUserIdAndNormalizedTitleAndYear(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(bulkImportLineRepository.findByUserIdAndRawLineAndYearIsNull(any(), any()))
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
    void shouldRecordNotFound_whenZeroYearMatchingCandidates() {
        when(tmdbClient.search("Ghost Movie", TMDB_KEY))
                .thenReturn(List.of(item(1, "Ghost Movie", "Ghost Movie", 1999)));

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Ghost Movie;;2010", UUID.randomUUID(), false);

        verify(movieService, never()).initiate(anyString(), anyInt());

        ArgumentCaptor<BulkImportLine> captor = ArgumentCaptor.forClass(BulkImportLine.class);
        verify(bulkImportLineRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BulkImportLineStatus.NOT_FOUND);
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
}
