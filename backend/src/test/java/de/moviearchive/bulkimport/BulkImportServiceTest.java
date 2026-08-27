package de.moviearchive.bulkimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.moviearchive.enrichment.EnrichmentService;
import de.moviearchive.enrichment.TmdbClient;
import de.moviearchive.enrichment.WikipediaClient;
import de.moviearchive.movie.Movie;
import de.moviearchive.movie.MovieRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
    private MovieRepository movieRepository;

    @Mock
    private WikipediaClient wikipediaClient;

    @Mock
    private BulkImportBatchRepository bulkImportBatchRepository;

    @Mock
    private BulkImportProgressService progressService;

    private final ImportLineParser importLineParser = new ImportLineParser();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private BulkImportService bulkImportService;

    private User user;
    private static final String EMAIL = "bulkimport-test@example.com";
    private static final String TMDB_KEY = "test-tmdb-key";

    @BeforeEach
    void setUp() {
        bulkImportService = new BulkImportService(
                bulkImportLineRepository, userRepository, tmdbClient, movieService,
                enrichmentService, movieRepository, wikipediaClient, importLineParser,
                bulkImportBatchRepository, progressService, null);
        // No Spring context in this unit test, so there's no real @Lazy proxy to inject for the
        // "self" self-invocation fix (CR-01) — wire the instance to itself so runImport()'s
        // self.processLine(...)/self.resolveAndPersistImdbId(...) calls resolve the same way
        // the AOP proxy would in production (mirrors WikiReloadServiceTest's convention).
        ReflectionTestUtils.setField(bulkImportService, "self", bulkImportService);

        user = new User(EMAIL, "hash");

        // lenient(): shouldPersistImdbId_whenTmdbDetailReturnsOne() and
        // shouldReturnNull_whenTmdbDetailCallFails() call resolveAndPersistImdbId() directly,
        // which never reaches userRepository.findByEmail() — only processLine()/runImport() do.
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

    private JsonNode tmdbDetailWithImdbId(String imdbId) throws Exception {
        String json = "{\"title\":\"Inception\",\"original_title\":\"Inception\","
                + "\"release_date\":\"2010-07-16\",\"runtime\":148,"
                + "\"external_ids\":{\"imdb_id\":\"" + imdbId + "\"}}";
        return objectMapper.readTree(json);
    }

    @Test
    void shouldRecordNotFound_whenZeroYearMatchingCandidates() {
        when(tmdbClient.search("Ghost Movie", TMDB_KEY))
                .thenReturn(List.of(item(1, "Ghost Movie", "Ghost Movie", 1999)));

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Ghost Movie;;2010", UUID.randomUUID());

        verify(movieService, never()).initiate(anyString(), anyInt());

        ArgumentCaptor<BulkImportLine> captor = ArgumentCaptor.forClass(BulkImportLine.class);
        verify(bulkImportLineRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BulkImportLineStatus.NOT_FOUND);
    }

    @Test
    void shouldSave_whenExactlyOneYearMatchingCandidate() {
        when(tmdbClient.search("Inception", TMDB_KEY))
                .thenReturn(List.of(item(27205, "Inception", "Inception", 2010)));

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Inception;;2010", UUID.randomUUID());

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

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Robin Hood;;2010", UUID.randomUUID());

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

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Robin Hood;Robin des Bois;2010", UUID.randomUUID());

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

        bulkImportService.processLine(EMAIL, TMDB_KEY, "Robin Hood;No Match Title;2010", UUID.randomUUID());

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

        bulkImportService.processLine(EMAIL, TMDB_KEY, longTitle + ";;2020", UUID.randomUUID());

        ArgumentCaptor<BulkImportLine> captor = ArgumentCaptor.forClass(BulkImportLine.class);
        verify(bulkImportLineRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).hasSize(500);
    }

    @Test
    void shouldPersistImdbId_whenTmdbDetailReturnsOne() throws Exception {
        UUID movieId = UUID.randomUUID();
        when(tmdbClient.fetchDetail(27205, TMDB_KEY)).thenReturn(tmdbDetailWithImdbId("tt1375666"));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(new Movie(user, 27205)));
        lenient().when(movieRepository.save(any(Movie.class))).thenAnswer(inv -> inv.getArgument(0));

        String result = bulkImportService.resolveAndPersistImdbId(movieId, 27205, TMDB_KEY);

        assertThat(result).isEqualTo("tt1375666");
        verify(movieRepository).save(argThat(m -> "tt1375666".equals(m.getImdbId())));
    }

    @Test
    void shouldReturnNull_whenTmdbDetailCallFails() {
        UUID movieId = UUID.randomUUID();
        when(tmdbClient.fetchDetail(27205, TMDB_KEY)).thenThrow(new RuntimeException("TMDB detail failed"));

        String result = bulkImportService.resolveAndPersistImdbId(movieId, 27205, TMDB_KEY);

        assertThat(result).isNull();
        verify(movieRepository, never()).save(any());
    }

    @Test
    void shouldCallSparqlBatchOnce_forAllMatchedLinesInOneRun() throws Exception {
        // Distinct MovieInitiateResult per tmdbId — the @BeforeEach lenient stub returns a
        // single fixed UUID for every movieService.initiate() call, which would otherwise
        // collapse both matched lines onto the same movieId key in imdbIdByMovieId.
        when(movieService.initiate(EMAIL, 27205)).thenReturn(new MovieInitiateResult(UUID.randomUUID(), true));
        when(movieService.initiate(EMAIL, 603)).thenReturn(new MovieInitiateResult(UUID.randomUUID(), true));
        when(tmdbClient.search("Inception", TMDB_KEY))
                .thenReturn(List.of(item(27205, "Inception", "Inception", 2010)));
        when(tmdbClient.search("The Matrix", TMDB_KEY))
                .thenReturn(List.of(item(603, "The Matrix", "The Matrix", 1999)));
        when(tmdbClient.fetchDetail(27205, TMDB_KEY)).thenReturn(tmdbDetailWithImdbId("tt1375666"));
        when(tmdbClient.fetchDetail(603, TMDB_KEY)).thenReturn(tmdbDetailWithImdbId("tt0133093"));
        lenient().when(movieRepository.findById(any())).thenReturn(Optional.of(new Movie(user, 1)));
        lenient().when(movieRepository.save(any(Movie.class))).thenAnswer(inv -> inv.getArgument(0));
        when(wikipediaClient.resolveViaWikidataSparql(anyList()))
                .thenReturn(Map.of("tt1375666", "Inception"));

        bulkImportService.runImport(EMAIL, TMDB_KEY,
                List.of("Inception;;2010", "The Matrix;;1999"), UUID.randomUUID());

        verify(wikipediaClient, times(1)).resolveViaWikidataSparql(argThat(ids ->
                ids.containsAll(List.of("tt1375666", "tt0133093")) && ids.size() == 2));
        verify(enrichmentService, times(2)).enrich(any(UUID.class), eq(Map.of("tt1375666", "Inception")));
        verify(enrichmentService, never()).enrich(any(UUID.class));
    }
}
