package de.moviearchive.indexing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.moviearchive.movie.Movie;
import de.moviearchive.movie.MovieRepository;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.DeleteRequest;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages OpenSearch index lifecycle and document indexing for the movie archive.
 * IndexingService is NOT @Async and NOT @Retryable — it runs synchronously on the
 * enrichment thread. Failures are propagated as exceptions for the caller (EnrichmentService)
 * to handle with D-01 silent-failure semantics.
 */
@Service
@Slf4j
public class IndexingService {

    private final OpenSearchClient client;
    private final MovieRepository movieRepository;
    private final DocumentBuilder documentBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IndexingService(OpenSearchClient client,
                           MovieRepository movieRepository,
                           DocumentBuilder documentBuilder) {
        this.client = client;
        this.movieRepository = movieRepository;
        this.documentBuilder = documentBuilder;
    }

    /**
     * Ensures the index for the given userId exists, creating it with the full mapping
     * and custom analyzer from opensearch/movies-index.json if it does not.
     * Uses the generic client to PUT the index with the raw JSON body (withJson pattern —
     * opensearch-java 2.19.0 typed builder does not expose withJson on CreateIndexRequest.Builder).
     * Concurrent-create race (resource_already_exists_exception) is swallowed (Pitfall 4).
     */
    public void ensureIndexExists(String indexName) throws IOException {
        boolean exists = client.indices()
                .exists(ExistsRequest.of(r -> r.index(indexName)))
                .value();
        if (exists) {
            return;
        }
        InputStream mappingStream = getClass().getClassLoader()
                .getResourceAsStream("opensearch/movies-index.json");
        if (mappingStream == null) {
            throw new IllegalStateException("opensearch/movies-index.json not found on classpath");
        }
        String indexJson = new String(mappingStream.readAllBytes(), StandardCharsets.UTF_8);
        // Use generic client to send the full JSON body (withJson not available on
        // CreateIndexRequest.Builder in opensearch-java 2.19.0 — typed builder bug #1510)
        try (var response = client.generic().execute(
                Requests.builder()
                        .method("PUT")
                        .endpoint("/" + indexName)
                        .json(indexJson)
                        .build())) {
            int status = response.getStatus();
            if (status == 200 || status == 201) {
                log.info("Created OpenSearch index: {}", indexName);
            } else if (status == 400) {
                // Check if it's a concurrent-creation race — resource_already_exists_exception
                String body = response.getBody()
                        .map(b -> {
                            try {
                                return new String(b.body().readAllBytes(), StandardCharsets.UTF_8);
                            } catch (IOException ex) {
                                return "";
                            }
                        })
                        .orElse("");
                JsonNode errorNode = objectMapper.readTree(body);
                String errorType = errorNode.path("error").path("type").asText(null);
                if ("resource_already_exists_exception".equals(errorType)) {
                    log.debug("OpenSearch index already exists (concurrent creation race): {}", indexName);
                } else {
                    throw new IOException("Failed to create OpenSearch index '" + indexName
                            + "': HTTP " + status + " — " + body);
                }
            } else {
                throw new IOException("Failed to create OpenSearch index '" + indexName
                        + "': HTTP " + status);
            }
        }
    }

    /**
     * Indexes a single movie document into movies-{userId}.
     * Ensures the index exists first, then writes the document.
     * Throws on failure — caller (EnrichmentService Step 5) handles with D-01 silent fail.
     */
    public void index(Movie movie) throws IOException {
        String indexName = "movies-" + movie.getUser().getId();
        ensureIndexExists(indexName);
        Map<String, Object> doc = documentBuilder.build(movie);
        client.index(IndexRequest.of(r -> r
                .index(indexName)
                .id(movie.getId().toString())
                .document(doc)));
    }

    /**
     * Full reindex: deletes the existing index, recreates it with fresh analyzer + mapping,
     * and reindexes all Postgres movies for the user. Sets indexed_at on success per movie.
     */
    @Transactional
    public int fullReindex(UUID userId) throws IOException {
        String indexName = "movies-" + userId;

        // 1. Delete existing index (ignore index_not_found)
        try {
            client.indices().delete(
                    org.opensearch.client.opensearch.indices.DeleteIndexRequest.of(
                            r -> r.index(indexName)));
        } catch (OpenSearchException e) {
            if (!"index_not_found_exception".equals(e.error().type())) {
                throw e;
            }
        }

        // 2. Recreate with fresh analyzer + mapping
        ensureIndexExists(indexName);

        // 3. Reindex all movies
        List<Movie> movies = movieRepository.findAllByUserId(userId);
        for (Movie movie : movies) {
            try {
                Map<String, Object> doc = documentBuilder.build(movie);
                client.index(IndexRequest.of(r -> r
                        .index(indexName)
                        .id(movie.getId().toString())
                        .document(doc)));
                movie.setIndexedAt(Instant.now());
                movieRepository.save(movie);
            } catch (Exception e) {
                log.warn("Failed to reindex movieId={}: {}", movie.getId(), e.getMessage());
            }
        }
        return movies.size();
    }

    /**
     * Partial reindex: indexes only movies with indexed_at IS NULL for the user.
     */
    @Transactional
    public int reindexPending(UUID userId) throws IOException {
        String indexName = "movies-" + userId;
        ensureIndexExists(indexName);
        List<Movie> pending = movieRepository.findByUserIdAndIndexedAtIsNull(userId);
        for (Movie movie : pending) {
            try {
                Map<String, Object> doc = documentBuilder.build(movie);
                client.index(IndexRequest.of(r -> r
                        .index(indexName)
                        .id(movie.getId().toString())
                        .document(doc)));
                movie.setIndexedAt(Instant.now());
                movieRepository.save(movie);
            } catch (Exception e) {
                log.warn("Failed to reindex pending movieId={}: {}", movie.getId(), e.getMessage());
            }
        }
        return pending.size();
    }

    /**
     * Removes the OpenSearch document for a movie. Swallows document-missing and
     * index-missing errors because the movie may not have been indexed yet
     * (indexed_at = null) or the index may not exist.
     */
    public void deleteDocument(String indexName, UUID movieId) {
        try {
            client.delete(DeleteRequest.of(r -> r
                    .index(indexName)
                    .id(movieId.toString())
                    .refresh(org.opensearch.client.opensearch._types.Refresh.WaitFor)));
            log.info("Deleted OS document movieId={} from index={}", movieId, indexName);
        } catch (Exception e) {
            // Swallow — document may not exist (never indexed), or index may not exist
            log.warn("OS delete failed (document may not exist) movieId={}: {}", movieId, e.getMessage());
        }
    }
}
