package de.moviearchive.search;

import com.fasterxml.jackson.databind.JsonNode;
import de.moviearchive.movie.Movie;
import de.moviearchive.movie.MovieRepository;
import de.moviearchive.search.dto.DashboardMovieItem;
import de.moviearchive.search.dto.DashboardResponse;
import de.moviearchive.search.dto.HistogramBucket;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.aggregations.Aggregation;
import org.opensearch.client.opensearch._types.aggregations.AggregationRange;
import org.opensearch.client.opensearch._types.aggregations.RangeBucket;
import org.opensearch.client.opensearch._types.aggregations.StringTermsBucket;
import org.opensearch.client.opensearch._types.query_dsl.FunctionBoostMode;
import org.opensearch.client.opensearch._types.query_dsl.FunctionScore;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Provides dashboard data: archive statistics (aggregations), movie of the day
 * (function_score random), and recently indexed films (Postgres query).
 */
@Service
@Transactional(readOnly = true)
@Slf4j
public class DashboardService {

    private final OpenSearchClient client;
    private final MovieRepository movieRepository;

    public DashboardService(OpenSearchClient client, MovieRepository movieRepository) {
        this.client = client;
        this.movieRepository = movieRepository;
    }

    /**
     * Assembles the full dashboard response:
     *  - Single OS request with size=0 for stats aggregations
     *  - Separate OS request for movie of the day (function_score + random_score)
     *  - Postgres query for recently indexed films
     *
     * An empty archive (zero indexed docs) returns totalFilms=0 with empty lists
     * and movieOfTheDay=null — no NPE (05-RESEARCH.md Pitfall 7).
     */
    public DashboardResponse getDashboard(String indexName, UUID userId) throws IOException {

        // ── Stats aggregations (single size=0 request) ─────────────────────────
        org.opensearch.client.opensearch.core.SearchRequest statsReq =
                org.opensearch.client.opensearch.core.SearchRequest.of(r -> r
                        .index(indexName)
                        .size(0)
                        .aggregations("total_count", Aggregation.of(a -> a
                                .valueCount(vc -> vc.field("tmdb_id"))))
                        .aggregations("genres", Aggregation.of(a -> a
                                .terms(t -> t.field("genre_list").size(1000))))
                        .aggregations("languages", Aggregation.of(a -> a
                                .terms(t -> t.field("language_list").size(500))))
                        .aggregations("imdb_histogram", Aggregation.of(a -> a
                                .range(range -> range
                                        .field("imdb_rating")
                                        .ranges(List.of(
                                                AggregationRange.of(ar -> ar.key("1-2").from("1.0").to("3.0")),
                                                AggregationRange.of(ar -> ar.key("3-4").from("3.0").to("5.0")),
                                                AggregationRange.of(ar -> ar.key("5-6").from("5.0").to("7.0")),
                                                AggregationRange.of(ar -> ar.key("7-8").from("7.0").to("9.0")),
                                                AggregationRange.of(ar -> ar.key("9-10").from("9.0").to("10.1"))
                                        ))))));

        SearchResponse<Map> statsResp = client.search(statsReq, Map.class);

        // Parse totalFilms — null-safe (Pitfall 7: empty archive must not NPE)
        long totalFilms = 0L;
        var totalCountAgg = statsResp.aggregations().get("total_count");
        if (totalCountAgg != null && totalCountAgg.isValueCount()) {
            totalFilms = (long) totalCountAgg.valueCount().value();
        }

        // Top genres — null-safe bucket iteration
        List<DashboardResponse.GenreCount> topGenres = new ArrayList<>();
        var genresAgg = statsResp.aggregations().get("genres");
        if (genresAgg != null && genresAgg.isSterms()) {
            List<StringTermsBucket> genreBuckets = genresAgg.sterms().buckets().array();
            if (genreBuckets != null) {
                for (StringTermsBucket bucket : genreBuckets) {
                    topGenres.add(new DashboardResponse.GenreCount(bucket.key(), bucket.docCount()));
                }
            }
        }

        // Language breakdown — null-safe bucket iteration
        List<DashboardResponse.LanguageCount> languageBreakdown = new ArrayList<>();
        var langAgg = statsResp.aggregations().get("languages");
        if (langAgg != null && langAgg.isSterms()) {
            List<StringTermsBucket> langBuckets = langAgg.sterms().buckets().array();
            if (langBuckets != null) {
                for (StringTermsBucket bucket : langBuckets) {
                    languageBreakdown.add(new DashboardResponse.LanguageCount(bucket.key(), bucket.docCount()));
                }
            }
        }

        // IMDB histogram — null-safe bucket iteration
        List<HistogramBucket> imdbHistogram = new ArrayList<>();
        var histAgg = statsResp.aggregations().get("imdb_histogram");
        if (histAgg != null && histAgg.isRange()) {
            List<RangeBucket> histBuckets = histAgg.range().buckets().array();
            if (histBuckets != null) {
                for (RangeBucket bucket : histBuckets) {
                    imdbHistogram.add(new HistogramBucket(bucket.key(), bucket.docCount()));
                }
            }
        }

        // ── Movie of the day (separate request, size=1) ────────────────────────
        DashboardMovieItem movieOfTheDay = null;
        if (totalFilms > 0) {
            // Date seed: ISO date string hashCode → stable integer for the calendar day
            String today = LocalDate.now().toString();
            String dateSeed = String.valueOf(today.hashCode());

            Query motdQuery = Query.of(q -> q
                    .functionScore(fs -> fs
                            .query(inner -> inner.matchAll(m -> m))
                            .functions(FunctionScore.of(f -> f
                                    .randomScore(rs -> rs
                                            .seed(dateSeed)
                                            .field("_seq_no"))))
                            .boostMode(FunctionBoostMode.Replace)));

            org.opensearch.client.opensearch.core.SearchRequest motdReq =
                    org.opensearch.client.opensearch.core.SearchRequest.of(r -> r
                            .index(indexName)
                            .query(motdQuery)
                            .size(1)
                            .source(sf -> sf.filter(f -> f.includes(List.of(
                                    "title", "year", "poster_path", "tmdb_id")))));

            SearchResponse<Map> motdResp = client.search(motdReq, Map.class);
            if (!motdResp.hits().hits().isEmpty()) {
                var hit = motdResp.hits().hits().get(0);
                Map<?, ?> src = hit.source() != null ? hit.source() : Map.of();
                String title = castString(src.get("title"));
                Integer year = castInt(src.get("year"));
                String posterPath = castString(src.get("poster_path"));
                movieOfTheDay = new DashboardMovieItem(hit.id(), title, year, posterPath);
            }
        }

        // ── Recently added films (Postgres, ORDER BY indexed_at DESC) ──────────
        List<Movie> recent = movieRepository.findRecentlyIndexedByUserId(userId, PageRequest.of(0, 10));
        List<DashboardMovieItem> recentlyAdded = new ArrayList<>();
        for (Movie movie : recent) {
            String posterPath = extractPosterPath(movie.getRawTmdbJson());
            Integer year = movie.getReleaseDate() != null ? movie.getReleaseDate().getYear() : null;
            recentlyAdded.add(new DashboardMovieItem(
                    movie.getId().toString(),
                    movie.getTitle(),
                    year,
                    posterPath));
        }

        return new DashboardResponse(totalFilms, topGenres, languageBreakdown,
                imdbHistogram, movieOfTheDay, recentlyAdded);
    }

    /**
     * Extracts poster_path from rawTmdbJson — same logic as DocumentBuilder.
     * Returns null when absent or blank.
     */
    private String extractPosterPath(JsonNode tmdb) {
        if (tmdb == null) return null;
        String path = tmdb.path("poster_path").asText(null);
        return (path != null && !path.isBlank()) ? path : null;
    }

    private String castString(Object val) {
        return val instanceof String s ? s : null;
    }

    private Integer castInt(Object val) {
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        return null;
    }
}
