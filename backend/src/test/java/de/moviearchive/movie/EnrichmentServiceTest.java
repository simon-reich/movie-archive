package de.moviearchive.movie;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.moviearchive.enrichment.EnrichmentService;
import de.moviearchive.enrichment.OmdbClient;
import de.moviearchive.enrichment.TmdbClient;
import de.moviearchive.enrichment.WikipediaClient;
import de.moviearchive.enrichment.WikipediaNotFoundException;
import de.moviearchive.enrichment.WikipediaResult;
import de.moviearchive.indexing.IndexingService;
import de.moviearchive.settings.SettingsService;
import de.moviearchive.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Note: Use doThrow().when() (not when().thenThrow()) when re-stubbing a method that
// was already configured to throw in @BeforeEach. when().thenThrow() calls the method
// to register the stub, which triggers the existing throw before Mockito can intercept it.

@ExtendWith(MockitoExtension.class)
class EnrichmentServiceTest {

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private SettingsService settingsService;

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private OmdbClient omdbClient;

    @Mock
    private WikipediaClient wikipediaClient;

    @Mock
    private IndexingService indexingService;

    private EnrichmentService enrichmentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID movieId;
    private Movie movie;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        enrichmentService = new EnrichmentService(
                movieRepository, settingsService, tmdbClient, omdbClient, wikipediaClient, indexingService);

        movieId = UUID.randomUUID();
        user = new User("test@example.com", "hash");
        movie = new Movie(user, 27205);

        when(movieRepository.findByIdWithUser(movieId)).thenReturn(Optional.of(movie));
        when(movieRepository.save(any(Movie.class))).thenAnswer(inv -> inv.getArgument(0));

        // Default Wikipedia: throws not found (silent). lenient() because
        // shouldSetWikiLastAttemptedAt_onWikipediaSuccess() overrides this stub entirely
        // (doReturn) before ever invoking it, which would otherwise trip Mockito's strict
        // unnecessary-stubbing check.
        lenient().when(wikipediaClient.fetch(anyString(), anyString(), anyInt(), nullable(String.class)))
                .thenThrow(new WikipediaNotFoundException("not found"));

        // Default OpenSearch: simulate connection failure (D-01 silent fail — no real OS in unit tests).
        // This ensures EnrichmentService does not perform the second movieRepository.save(indexed_at)
        // in tests that only verify the Postgres enrichment logic.
        doThrow(new IOException("OpenSearch not available in unit test"))
                .when(indexingService).index(any(Movie.class));
    }

    private JsonNode tmdbDetailWithImdbId(String imdbId) throws Exception {
        String json = "{\"title\":\"Inception\",\"original_title\":\"Inception\","
                + "\"release_date\":\"2010-07-16\",\"runtime\":148,"
                + "\"external_ids\":{\"imdb_id\":\"" + imdbId + "\"}}";
        return objectMapper.readTree(json);
    }

    private JsonNode tmdbDetailWithoutImdbId() throws Exception {
        String json = "{\"title\":\"Inception\",\"original_title\":\"Inception\","
                + "\"release_date\":\"2010-07-16\",\"runtime\":148,"
                + "\"external_ids\":{}}";
        return objectMapper.readTree(json);
    }

    @Test
    void shouldSkipOmdb_whenNoOmdbKey() throws Exception {
        when(settingsService.getApiKeys("test@example.com"))
                .thenReturn(Map.of("tmdb", "tmdb-key"));
        when(tmdbClient.fetchDetail(27205, "tmdb-key"))
                .thenReturn(tmdbDetailWithImdbId("tt1375666"));

        enrichmentService.enrich(movieId);

        verify(omdbClient, never()).fetch(anyString(), anyString());

        ArgumentCaptor<Movie> saved = ArgumentCaptor.forClass(Movie.class);
        verify(movieRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MovieStatus.SUCCESS);
    }

    @Test
    void shouldSkipOmdb_whenImdbIdNull() throws Exception {
        when(settingsService.getApiKeys("test@example.com"))
                .thenReturn(Map.of("tmdb", "tmdb-key", "omdb", "omdb-key"));
        when(tmdbClient.fetchDetail(27205, "tmdb-key"))
                .thenReturn(tmdbDetailWithoutImdbId());

        enrichmentService.enrich(movieId);

        verify(omdbClient, never()).fetch(anyString(), anyString());

        ArgumentCaptor<Movie> saved = ArgumentCaptor.forClass(Movie.class);
        verify(movieRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MovieStatus.SUCCESS);
    }

    @Test
    void shouldSaveWithSuccess_whenWikipediaFails() throws Exception {
        when(settingsService.getApiKeys("test@example.com"))
                .thenReturn(Map.of("tmdb", "tmdb-key"));
        when(tmdbClient.fetchDetail(27205, "tmdb-key"))
                .thenReturn(tmdbDetailWithImdbId("tt1375666"));
        // Wikipedia already configured to throw WikipediaNotFoundException in @BeforeEach —
        // no need to re-stub here.

        enrichmentService.enrich(movieId);

        ArgumentCaptor<Movie> saved = ArgumentCaptor.forClass(Movie.class);
        verify(movieRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MovieStatus.SUCCESS);
        assertThat(saved.getValue().getWikiUrl()).isNull();
        assertThat(saved.getValue().getWikiLastAttemptedAt()).isNotNull();
    }

    @Test
    void shouldSetWikiLastAttemptedAt_onWikipediaSuccess() throws Exception {
        when(settingsService.getApiKeys("test@example.com"))
                .thenReturn(Map.of("tmdb", "tmdb-key"));
        when(tmdbClient.fetchDetail(27205, "tmdb-key"))
                .thenReturn(tmdbDetailWithImdbId("tt1375666"));
        // Override the @BeforeEach not-found default for this test only. Use doReturn().when()
        // (not when().thenReturn()) because the mock is already stubbed to throw — calling
        // wikipediaClient.fetch(...) as part of when(...) would trigger that throw first.
        WikipediaResult wiki = new WikipediaResult(
                "https://en.wikipedia.org/wiki/Inception", "summary", "plot", "critics");
        doReturn(wiki).when(wikipediaClient).fetch(anyString(), anyString(), anyInt(), nullable(String.class));

        enrichmentService.enrich(movieId);

        ArgumentCaptor<Movie> saved = ArgumentCaptor.forClass(Movie.class);
        verify(movieRepository).save(saved.capture());
        assertThat(saved.getValue().getWikiLastAttemptedAt()).isNotNull();
        assertThat(saved.getValue().getWikiUrl()).isEqualTo(wiki.url());
    }
}
