package de.moviearchive.movie;

import com.fasterxml.jackson.databind.JsonNode;
import de.moviearchive.enrichment.WikiReloadService;
import de.moviearchive.indexing.IndexingService;
import de.moviearchive.movie.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MovieDetailService {

    private final MovieRepository movieRepository;
    private final IndexingService indexingService;
    private final WikiReloadService wikiReloadService;

    public MovieDetailResponse getDetail(UUID userId, UUID movieId) {
        Movie movie = movieRepository.findByIdAndUserId(movieId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found"));

        JsonNode tmdb = movie.getRawTmdbJson();  // JsonNode directly from entity (JdbcTypeCode)
        JsonNode omdb = movie.getRawOmdbJson();  // null if no OMDB data

        return new MovieDetailResponse(
            movie.getId(),
            movie.getTmdbId(),
            movie.getImdbId(),
            movie.getTitle(),
            movie.getOriginalTitle(),
            textOrNull(tmdb, "tagline"),
            textOrNull(tmdb, "overview"),
            movie.getReleaseDate() != null ? movie.getReleaseDate().toString() : null,
            movie.getReleaseDate() != null ? movie.getReleaseDate().getYear() : null,
            movie.getRuntime(),
            textOrNull(tmdb, "poster_path"),
            textOrNull(tmdb, "backdrop_path"),
            doubleOrNull(tmdb, "vote_average"),
            intOrNull(tmdb, "vote_count"),
            extractTrailerKey(tmdb),
            extractStringList(tmdb, "genres", "name"),
            extractDirectors(tmdb),
            extractWriters(tmdb),
            omdb != null ? textOrNull(omdb, "Actors") : null,
            extractFullCast(tmdb),
            extractFullCrew(tmdb),
            extractStringList(tmdb, "production_countries", "iso_3166_1"),
            extractStringList(tmdb, "spoken_languages", "iso_639_1"),
            omdb != null ? parseDoubleField(omdb, "imdbRating") : null,
            omdb != null ? parseIntField(omdb, "imdbVotes") : null,
            omdb != null ? textOrNull(omdb, "Rated") : null,
            omdb != null ? parseLongField(omdb, "BoxOffice") : null,
            omdb != null ? extractRatingList(omdb) : null,
            movie.getImdbId() != null ? "https://www.imdb.com/title/" + movie.getImdbId() : null,
            movie.getWikiPlot(),
            movie.getWikiCritics(),
            movie.getWikiSummary(),
            movie.getWikiUrl(),
            movie.getWatched() != null ? movie.getWatched() : false,
            movie.getPersonalRating(),
            movie.getPersonalNotes()
        );
    }

    public void updatePersonal(UUID userId, UUID movieId, Map<String, Object> fields) {
        Movie movie = movieRepository.findByIdAndUserId(movieId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found"));

        if (fields.containsKey("watched")) {
            Object val = fields.get("watched");
            movie.setWatched(val instanceof Boolean b ? b : Boolean.valueOf(String.valueOf(val)));
        }
        if (fields.containsKey("personalRating")) {
            Object val = fields.get("personalRating");
            if (val == null) {
                movie.setPersonalRating(null);
            } else {
                // Jackson deserializes integers as Integer — convert to Short
                movie.setPersonalRating(val instanceof Number n ? n.shortValue() : null);
            }
        }
        if (fields.containsKey("personalNotes")) {
            Object val = fields.get("personalNotes");
            movie.setPersonalNotes(val instanceof String s ? s : null);
        }

        movieRepository.save(movie);

        // Sync to OpenSearch — full re-index (proven pattern, no UpdateRequest API uncertainty)
        if (movie.getIndexedAt() != null) {
            try {
                String indexName = "movies-" + userId;
                indexingService.ensureIndexExists(indexName);
                indexingService.index(movie);
            } catch (IOException e) {
                log.warn("OS sync failed for personal update movieId={}: {}", movieId, e.getMessage());
                // Silent fail — Postgres is source of truth
            }
        }
    }

    /**
     * Retries the Wikipedia step for a single movie, on-demand, bypassing the batch-reload
     * cooldown entirely (manual retry is always callable — ENRICH-04/D-01). Delegates to
     * the existing WikiReloadService.retryWikipedia(Movie), which is @Transactional, sets
     * wikiLastAttemptedAt on every attempt (success or failure), and swallows all Wikipedia
     * failures internally — this method never throws for a Wikipedia miss, only for a
     * missing/not-owned movie (404, same IDOR pattern as getDetail/updatePersonal/deleteMovie).
     */
    public MovieDetailResponse retryWiki(UUID userId, UUID movieId) {
        Movie movie = movieRepository.findByIdAndUserId(movieId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found"));
        wikiReloadService.retryWikipedia(movie);
        return getDetail(userId, movieId);
    }

    public void deleteMovie(UUID userId, UUID movieId) {
        movieRepository.findByIdAndUserId(movieId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found"));

        // Remove from OpenSearch first (idempotent — swallows not-found)
        String indexName = "movies-" + userId;
        indexingService.deleteDocument(indexName, movieId);

        movieRepository.deleteById(movieId);
        log.info("Deleted movie movieId={} for userId={}", movieId, userId);
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        String val = node.get(field).asText();
        return val.isBlank() ? null : val;
    }

    private Double doubleOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        double val = node.get(field).asDouble();
        return val == 0.0 ? null : val;
    }

    private Integer intOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        int val = node.get(field).asInt();
        return val == 0 ? null : val;
    }

    private Double parseDoubleField(JsonNode omdb, String field) {
        String raw = textOrNull(omdb, field);
        if (raw == null || raw.equals("N/A")) return null;
        try { return Double.parseDouble(raw); } catch (NumberFormatException e) { return null; }
    }

    private Integer parseIntField(JsonNode omdb, String field) {
        String raw = textOrNull(omdb, field);
        if (raw == null || raw.equals("N/A")) return null;
        try { return Integer.parseInt(raw.replace(",", "")); } catch (NumberFormatException e) { return null; }
    }

    private Long parseLongField(JsonNode omdb, String field) {
        String raw = textOrNull(omdb, field);
        if (raw == null || raw.equals("N/A")) return null;
        try { return Long.parseLong(raw.replace("$", "").replace(",", "").trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private List<String> extractStringList(JsonNode tmdb, String arrayField, String nameField) {
        if (tmdb == null || !tmdb.has(arrayField)) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : tmdb.get(arrayField)) {
            String val = item.has(nameField) ? item.get(nameField).asText(null) : null;
            if (val != null && !val.isBlank()) result.add(val);
        }
        return result;
    }

    private List<String> extractDirectors(JsonNode tmdb) {
        if (tmdb == null || !tmdb.has("credits")) return List.of();
        JsonNode crew = tmdb.get("credits").get("crew");
        if (crew == null) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode member : crew) {
            if ("Director".equals(member.has("job") ? member.get("job").asText() : null)) {
                String name = member.has("name") ? member.get("name").asText(null) : null;
                if (name != null) result.add(name);
            }
        }
        return result;
    }

    private List<String> extractWriters(JsonNode tmdb) {
        if (tmdb == null || !tmdb.has("credits")) return List.of();
        JsonNode crew = tmdb.get("credits").get("crew");
        if (crew == null) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode member : crew) {
            String dept = member.has("department") ? member.get("department").asText() : "";
            if ("Writing".equals(dept)) {
                String name = member.has("name") ? member.get("name").asText(null) : null;
                if (name != null && !result.contains(name)) result.add(name);
            }
        }
        return result;
    }

    private String extractTrailerKey(JsonNode tmdb) {
        if (tmdb == null || !tmdb.has("videos")) return null;
        JsonNode results = tmdb.get("videos").get("results");
        if (results == null) return null;
        for (JsonNode video : results) {
            String type = video.has("type") ? video.get("type").asText() : "";
            String site = video.has("site") ? video.get("site").asText() : "";
            if ("Trailer".equals(type) && "YouTube".equals(site)) {
                return video.has("key") ? video.get("key").asText(null) : null;
            }
        }
        return null;
    }

    private List<CastMember> extractFullCast(JsonNode tmdb) {
        if (tmdb == null || !tmdb.has("credits")) return List.of();
        JsonNode cast = tmdb.get("credits").get("cast");
        if (cast == null) return List.of();
        List<CastMember> result = new ArrayList<>();
        for (JsonNode member : cast) {
            result.add(new CastMember(
                member.has("name") ? member.get("name").asText(null) : null,
                member.has("character") ? member.get("character").asText(null) : null,
                member.has("order") ? member.get("order").asInt() : null,
                member.has("profile_path") && !member.get("profile_path").isNull()
                    ? member.get("profile_path").asText(null) : null
            ));
        }
        return result;
    }

    private List<CrewMember> extractFullCrew(JsonNode tmdb) {
        if (tmdb == null || !tmdb.has("credits")) return List.of();
        JsonNode crew = tmdb.get("credits").get("crew");
        if (crew == null) return List.of();
        List<CrewMember> result = new ArrayList<>();
        for (JsonNode member : crew) {
            result.add(new CrewMember(
                member.has("name") ? member.get("name").asText(null) : null,
                member.has("job") ? member.get("job").asText(null) : null,
                member.has("department") ? member.get("department").asText(null) : null,
                member.has("profile_path") && !member.get("profile_path").isNull()
                    ? member.get("profile_path").asText(null) : null
            ));
        }
        return result;
    }

    private List<Rating> extractRatingList(JsonNode omdb) {
        if (omdb == null || !omdb.has("Ratings")) return null;
        List<Rating> result = new ArrayList<>();
        for (JsonNode r : omdb.get("Ratings")) {
            String source = r.has("Source") ? r.get("Source").asText(null) : null;
            String value = r.has("Value") ? r.get("Value").asText(null) : null;
            if (source != null && value != null) result.add(new Rating(source, value));
        }
        return result.isEmpty() ? null : result;
    }
}
