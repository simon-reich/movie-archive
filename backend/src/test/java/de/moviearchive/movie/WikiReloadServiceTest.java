package de.moviearchive.movie;

import de.moviearchive.enrichment.WikiReloadService;
import de.moviearchive.enrichment.WikipediaClient;
import de.moviearchive.enrichment.WikipediaNotFoundException;
import de.moviearchive.enrichment.WikipediaResult;
import de.moviearchive.indexing.IndexingService;
import de.moviearchive.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Note: Use doThrow().when() (not when().thenThrow()) when re-stubbing a method that
// was already configured to throw in @BeforeEach — mirrors EnrichmentServiceTest's convention.

@ExtendWith(MockitoExtension.class)
class WikiReloadServiceTest {

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private WikipediaClient wikipediaClient;

    @Mock
    private IndexingService indexingService;

    private WikiReloadService wikiReloadService;

    private User user;

    @BeforeEach
    void setUp() {
        wikiReloadService = new WikiReloadService(movieRepository, wikipediaClient, indexingService, null);
        // No Spring context in this unit test, so there's no real @Lazy proxy to inject for
        // the "self" self-invocation fix (WR-01) — wire the instance to itself so
        // batchReload()'s self.retryWikipedia(...) call resolves the same way the AOP proxy
        // would in production (transactional semantics aren't exercised by Mockito anyway).
        ReflectionTestUtils.setField(wikiReloadService, "self", wikiReloadService);
        user = new User("test@example.com", "hash");
        when(movieRepository.save(any(Movie.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Movie newMovie() {
        Movie movie = new Movie(user, 27205);
        movie.setTitle("Inception");
        movie.setOriginalTitle("Inception");
        movie.setReleaseDate(LocalDate.of(2010, 7, 16));
        movie.setStatus(MovieStatus.SUCCESS);
        return movie;
    }

    @Test
    void shouldSetTimestampAndWikiFields_onRetrySuccess() throws Exception {
        Movie movie = newMovie();
        WikipediaResult result = new WikipediaResult(
                "https://en.wikipedia.org/wiki/Inception", "summary", "plot", "critics");
        when(wikipediaClient.fetch(anyString(), anyString(), anyInt(), nullable(String.class))).thenReturn(result);

        wikiReloadService.retryWikipedia(movie);

        ArgumentCaptor<Movie> saved = ArgumentCaptor.forClass(Movie.class);
        verify(movieRepository, times(2)).save(saved.capture());
        Movie last = saved.getValue();
        assertThat(last.getWikiLastAttemptedAt()).isNotNull();
        assertThat(last.getWikiUrl()).isEqualTo(result.url());
        verify(indexingService).index(movie);
    }

    @Test
    void shouldSetTimestampOnly_whenWikipediaNotFound() throws Exception {
        Movie movie = newMovie();
        when(wikipediaClient.fetch(anyString(), anyString(), anyInt(), nullable(String.class)))
                .thenThrow(new WikipediaNotFoundException("not found"));

        wikiReloadService.retryWikipedia(movie);

        ArgumentCaptor<Movie> saved = ArgumentCaptor.forClass(Movie.class);
        verify(movieRepository).save(saved.capture());
        assertThat(saved.getValue().getWikiLastAttemptedAt()).isNotNull();
        assertThat(saved.getValue().getWikiUrl()).isNull();
        verify(indexingService, never()).index(any());
    }

    @Test
    void shouldIsolateFailures_inBatchLoop() throws Exception {
        UUID userId = UUID.randomUUID();
        Movie movieA = newMovie();
        Movie movieB = newMovie();
        when(movieRepository.findEligibleForWikiReload(eq(userId), any(Instant.class)))
                .thenReturn(List.of(movieA, movieB));

        WikipediaResult result = new WikipediaResult(
                "https://en.wikipedia.org/wiki/Inception", "summary", "plot", "critics");
        when(wikipediaClient.fetch(anyString(), anyString(), anyInt(), nullable(String.class), any()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(result);

        wikiReloadService.batchReload(userId);

        verify(wikipediaClient, times(2))
                .fetch(anyString(), anyString(), anyInt(), nullable(String.class), any());
    }

    @Test
    void shouldCallResolveViaWikidataSparqlOnce_withAllEligibleImdbIds() throws Exception {
        UUID userId = UUID.randomUUID();
        Movie movieA = newMovie();
        movieA.setImdbId("tt1000001");
        Movie movieB = newMovie();
        movieB.setImdbId("tt1000002");
        when(movieRepository.findEligibleForWikiReload(eq(userId), any(Instant.class)))
                .thenReturn(List.of(movieA, movieB));

        WikipediaResult result = new WikipediaResult(
                "https://en.wikipedia.org/wiki/Inception", "summary", "plot", "critics");
        when(wikipediaClient.fetch(anyString(), anyString(), anyInt(), nullable(String.class), any()))
                .thenReturn(result);

        wikiReloadService.batchReload(userId);

        verify(wikipediaClient, times(1)).resolveViaWikidataSparql(argThat(ids ->
                ids.containsAll(List.of("tt1000001", "tt1000002")) && ids.size() == 2));
        verify(wikipediaClient, times(2))
                .fetch(anyString(), anyString(), anyInt(), nullable(String.class), any());
    }
}
