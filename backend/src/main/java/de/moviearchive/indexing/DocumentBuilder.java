package de.moviearchive.indexing;

import com.fasterxml.jackson.databind.JsonNode;
import de.moviearchive.movie.Movie;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles a Map<String, Object> document from a Movie entity for OpenSearch indexing.
 * All 40+ fields from data-model.md are populated here.
 * OMDB-sourced fields are null when getRawOmdbJson() is null.
 * Personal fields (watched, personal_rating, personal_notes) are always null in Phase 4 (D-05).
 */
@Component
@Slf4j
public class DocumentBuilder {

    public Map<String, Object> build(Movie movie) {
        Map<String, Object> doc = new HashMap<>();
        JsonNode tmdb = movie.getRawTmdbJson(); // non-null after enrichment
        JsonNode omdb = movie.getRawOmdbJson(); // may be null

        // ── Scalar fields from Movie entity ───────────────────────────────
        doc.put("tmdb_id", movie.getTmdbId());
        doc.put("imdb_id", movie.getImdbId());
        doc.put("title", movie.getTitle());
        doc.put("original_title", movie.getOriginalTitle());
        doc.put("release_date", movie.getReleaseDate() != null ? movie.getReleaseDate().toString() : null);
        doc.put("runtime", movie.getRuntime());

        // ── Computed fields ───────────────────────────────────────────────
        int year = (movie.getReleaseDate() != null) ? movie.getReleaseDate().getYear() : 0;
        doc.put("year", year > 0 ? year : null);
        doc.put("imdb_link", movie.getImdbId() != null
                ? "https://www.imdb.com/title/" + movie.getImdbId()
                : null);

        // ── TMDB JSON scalar fields ───────────────────────────────────────
        doc.put("tagline", tmdb.path("tagline").asText(null));
        doc.put("overview", tmdb.path("overview").asText(null));

        double voteAvg = tmdb.path("vote_average").asDouble(0);
        doc.put("vote_average", voteAvg > 0 ? voteAvg : null);

        int voteCount = tmdb.path("vote_count").asInt(0);
        doc.put("vote_count", voteCount > 0 ? voteCount : null);

        String posterPath = tmdb.path("poster_path").asText(null);
        doc.put("poster_path", (posterPath != null && !posterPath.isBlank()) ? posterPath : null);

        String backdropPath = tmdb.path("backdrop_path").asText(null);
        doc.put("backdrop_path", (backdropPath != null && !backdropPath.isBlank()) ? backdropPath : null);

        // ── TMDB list fields ──────────────────────────────────────────────

        // genre_list from genres[].name
        List<String> genreList = new ArrayList<>();
        for (JsonNode g : tmdb.path("genres")) {
            String name = g.path("name").asText(null);
            if (name != null) genreList.add(name);
        }
        doc.put("genre_list", genreList.isEmpty() ? null : genreList);

        // country_list from production_countries[].iso_3166_1
        List<String> countryList = new ArrayList<>();
        for (JsonNode c : tmdb.path("production_countries")) {
            String code = c.path("iso_3166_1").asText(null);
            if (code != null) countryList.add(code);
        }
        doc.put("country_list", countryList.isEmpty() ? null : countryList);

        // language_list from spoken_languages[].iso_639_1
        List<String> languageList = new ArrayList<>();
        for (JsonNode l : tmdb.path("spoken_languages")) {
            String code = l.path("iso_639_1").asText(null);
            if (code != null) languageList.add(code);
        }
        doc.put("language_list", languageList.isEmpty() ? null : languageList);

        // company_list from production_companies[].name
        List<String> companyList = new ArrayList<>();
        for (JsonNode c : tmdb.path("production_companies")) {
            String name = c.path("name").asText(null);
            if (name != null) companyList.add(name);
        }
        doc.put("company_list", companyList.isEmpty() ? null : companyList);

        // keyword_list from keywords.keywords[].name
        List<String> keywordList = new ArrayList<>();
        for (JsonNode k : tmdb.path("keywords").path("keywords")) {
            String name = k.path("name").asText(null);
            if (name != null) keywordList.add(name);
        }
        doc.put("keyword_list", keywordList.isEmpty() ? null : keywordList);

        // full_cast (nested) and full_cast_names from credits.cast
        List<Map<String, Object>> fullCast = new ArrayList<>();
        List<String> fullCastNames = new ArrayList<>();
        for (JsonNode c : tmdb.path("credits").path("cast")) {
            String name = c.path("name").asText(null);
            if (name != null) {
                Map<String, Object> castMember = new HashMap<>();
                castMember.put("name", name);
                castMember.put("character", c.path("character").asText(null));
                int order = c.path("order").asInt(-1);
                castMember.put("order", order >= 0 ? order : null);
                String profilePath = c.path("profile_path").asText(null);
                castMember.put("profile_path", (profilePath != null && !profilePath.isBlank()) ? profilePath : null);
                fullCast.add(castMember);
                fullCastNames.add(name);
            }
        }
        doc.put("full_cast", fullCast.isEmpty() ? null : fullCast);
        doc.put("full_cast_names", fullCastNames.isEmpty() ? null : fullCastNames);

        // full_crew (nested) and full_crew_names from credits.crew
        List<Map<String, Object>> fullCrew = new ArrayList<>();
        List<String> fullCrewNames = new ArrayList<>();
        // Track director and writer names for director_list / writer_list
        List<String> directorList = new ArrayList<>();
        List<String> writerList = new ArrayList<>();
        for (JsonNode c : tmdb.path("credits").path("crew")) {
            String name = c.path("name").asText(null);
            if (name != null) {
                Map<String, Object> crewMember = new HashMap<>();
                crewMember.put("name", name);
                crewMember.put("job", c.path("job").asText(null));
                crewMember.put("department", c.path("department").asText(null));
                String profilePath = c.path("profile_path").asText(null);
                crewMember.put("profile_path", (profilePath != null && !profilePath.isBlank()) ? profilePath : null);
                fullCrew.add(crewMember);
                if (!fullCrewNames.contains(name)) {
                    fullCrewNames.add(name);
                }
                String job = c.path("job").asText(null);
                if ("Director".equals(job)) {
                    directorList.add(name);
                }
                if ("Writer".equals(job) || "Screenplay".equals(job) || "Story".equals(job)) {
                    writerList.add(name);
                }
            }
        }
        doc.put("full_crew", fullCrew.isEmpty() ? null : fullCrew);
        doc.put("full_crew_names", fullCrewNames.isEmpty() ? null : fullCrewNames);
        doc.put("director_list", directorList.isEmpty() ? null : directorList);
        doc.put("writer_list", writerList.isEmpty() ? null : writerList);

        // trailer_key: primary trailer YouTube key from videos.results
        String trailerKey = null;
        for (JsonNode v : tmdb.path("videos").path("results")) {
            if ("Trailer".equals(v.path("type").asText(null)) && "YouTube".equals(v.path("site").asText(null))) {
                trailerKey = v.path("key").asText(null);
                break;
            }
        }
        doc.put("trailer_key", trailerKey);

        // video_list (flattened) from videos.results
        List<Map<String, Object>> videoList = new ArrayList<>();
        for (JsonNode v : tmdb.path("videos").path("results")) {
            Map<String, Object> video = new HashMap<>();
            video.put("key", v.path("key").asText(null));
            video.put("site", v.path("site").asText(null));
            video.put("type", v.path("type").asText(null));
            video.put("name", v.path("name").asText(null));
            videoList.add(video);
        }
        doc.put("video_list", videoList.isEmpty() ? null : videoList);

        // poster_list (flattened) from images.posters
        List<Map<String, Object>> posterList = new ArrayList<>();
        for (JsonNode p : tmdb.path("images").path("posters")) {
            Map<String, Object> poster = new HashMap<>();
            poster.put("file_path", p.path("file_path").asText(null));
            poster.put("width", p.path("width").asInt(0));
            poster.put("height", p.path("height").asInt(0));
            poster.put("vote_average", p.path("vote_average").asDouble(0));
            posterList.add(poster);
        }
        doc.put("poster_list", posterList.isEmpty() ? null : posterList);

        // backdrop_list (flattened) from images.backdrops
        List<Map<String, Object>> backdropList = new ArrayList<>();
        for (JsonNode b : tmdb.path("images").path("backdrops")) {
            Map<String, Object> backdrop = new HashMap<>();
            backdrop.put("file_path", b.path("file_path").asText(null));
            backdrop.put("width", b.path("width").asInt(0));
            backdrop.put("height", b.path("height").asInt(0));
            backdrop.put("vote_average", b.path("vote_average").asDouble(0));
            backdropList.add(backdrop);
        }
        doc.put("backdrop_list", backdropList.isEmpty() ? null : backdropList);

        // ── OMDB fields (all null when no OMDB data) ──────────────────────
        if (omdb != null && !omdb.isNull()) {
            doc.put("imdb_rating", parseDoubleOrNull(omdb.path("imdbRating").asText(null)));
            doc.put("imdb_votes", parseIntOrNull(omdb.path("imdbVotes").asText(null)));
            doc.put("content_rating", omdb.path("Rated").asText(null));
            doc.put("box_office", parseBoxOffice(omdb.path("BoxOffice").asText(null)));
            doc.put("main_cast", omdb.path("Actors").asText(null));

            // rating_list (flattened) from Ratings array
            List<Map<String, Object>> ratingList = new ArrayList<>();
            for (JsonNode r : omdb.path("Ratings")) {
                Map<String, Object> rating = new HashMap<>();
                rating.put("source", r.path("Source").asText(null));
                rating.put("value", r.path("Value").asText(null));
                ratingList.add(rating);
            }
            doc.put("rating_list", ratingList.isEmpty() ? null : ratingList);
        } else {
            doc.put("imdb_rating", null);
            doc.put("imdb_votes", null);
            doc.put("content_rating", null);
            doc.put("box_office", null);
            doc.put("main_cast", null);
            doc.put("rating_list", null);
        }

        // ── Wikipedia fields ──────────────────────────────────────────────
        doc.put("wikipedia_url", movie.getWikiUrl());
        doc.put("wikipedia_summary", movie.getWikiSummary());
        doc.put("wikipedia_plot", movie.getWikiPlot());
        doc.put("wikipedia_critics", movie.getWikiCritics());
        // wikipedia_plot_html and wikipedia_full_html not in Movie entity in Phase 4 — always null
        doc.put("wikipedia_plot_html", null);
        doc.put("wikipedia_full_html", null);

        // ── Personal fields (Phase 4: always null — D-05) ─────────────────
        doc.put("watched", null);
        doc.put("personal_rating", null);
        doc.put("personal_notes", null);

        return doc;
    }

    private Double parseDoubleOrNull(String value) {
        if (value == null || value.isBlank() || "N/A".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseIntOrNull(String value) {
        if (value == null || value.isBlank() || "N/A".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            // OMDB imdbVotes uses commas: "2,500,000"
            return Integer.parseInt(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseBoxOffice(String value) {
        if (value == null || value.isBlank() || "N/A".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            // OMDB BoxOffice format: "$836,836,967"
            return Integer.parseInt(value.replaceAll("[^0-9]", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
