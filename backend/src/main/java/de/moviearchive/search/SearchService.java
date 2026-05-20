package de.moviearchive.search;

import de.moviearchive.search.dto.AutocompleteResponse;
import de.moviearchive.search.dto.FacetsResponse;
import de.moviearchive.search.dto.FilterCriteria;
import de.moviearchive.search.dto.SearchResultItem;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.aggregations.Aggregation;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Executes OpenSearch queries for free-text search, advanced filtering, sorting,
 * pagination, and autocomplete. All queries are scoped to the caller's index
 * (movies-{userId}) — the index name is always derived server-side from the JWT.
 */
@Service
@Slf4j
public class SearchService {

    /** Hard cap on page size — prevents unbounded queries (T-05-02-03 mitigation). */
    static final int PAGE_SIZE = 20;

    private final OpenSearchClient client;

    public SearchService(OpenSearchClient client) {
        this.client = client;
    }

    /**
     * Executes a search against the given user index.
     * Empty/null query = match_all; non-blank query = bool/should with per-field boosts.
     * Non-null FilterCriteria fields are applied as bool.filter clauses (AND across groups,
     * OR within multi-value groups like genres).
     */
    public de.moviearchive.search.dto.SearchResponse search(
            String indexName,
            de.moviearchive.search.dto.SearchRequest request) throws IOException {

        String queryText = request.getQuery();
        int page = request.getPage();

        // Base query: match_all for empty/null, multi-field bool/should otherwise
        Query baseQuery = (queryText == null || queryText.isBlank())
                ? Query.of(q -> q.matchAll(m -> m))
                : buildTextQuery(queryText);

        // Assemble bool: must(baseQuery) + filter clauses for each active criterion
        BoolQuery.Builder bool = new BoolQuery.Builder();
        bool.must(baseQuery);

        FilterCriteria criteria = request.getFilters();
        if (criteria != null) {
            applyFilters(bool, criteria);
        }

        Query combinedQuery = Query.of(q -> q.bool(bool.build()));

        // Sort
        SortOptions sortOptions = buildSort(request.getSort());

        // Pagination — PAGE_SIZE=20 hard cap (T-05-02-03)
        // Use long arithmetic to prevent int overflow on large page values (WR-01)
        long fromLong = (long) page * PAGE_SIZE;
        if (fromLong > 10_000) {
            throw new IllegalArgumentException("Page number exceeds maximum depth (10 000 results).");
        }
        int from = (int) fromLong;

        // Source filter: only return fields needed by the UI — keeps response small
        org.opensearch.client.opensearch.core.SearchRequest searchReq =
                org.opensearch.client.opensearch.core.SearchRequest.of(r -> r
                        .index(indexName)
                        .query(combinedQuery)
                        .sort(sortOptions)
                        .from(from)
                        .size(PAGE_SIZE)
                        .source(sf -> sf.filter(f -> f.includes(List.of(
                                "tmdb_id", "title", "year", "poster_path",
                                "director_list", "genre_list", "imdb_rating", "runtime")))));

        org.opensearch.client.opensearch.core.SearchResponse<Map> response =
                client.search(searchReq, Map.class);

        long total = response.hits().total() != null ? response.hits().total().value() : 0L;
        int totalPages = (int) Math.ceil(total / (double) PAGE_SIZE);
        boolean hasMore = (from + PAGE_SIZE) < total;

        List<SearchResultItem> results = response.hits().hits().stream()
                .map(this::mapHit)
                .collect(Collectors.toList());

        return new de.moviearchive.search.dto.SearchResponse(results, total, page, totalPages, hasMore);
    }

    /**
     * Returns autocomplete suggestions for director or actors prefix queries.
     * Field must be "director" or "actors" — any other value is rejected (T-05-02-04).
     * Autocomplete results capped at 10 suggestions (T-05-02-03 mitigation).
     */
    public AutocompleteResponse autocomplete(
            String indexName, String field, String prefix) throws IOException {

        // Whitelist field param — prevents arbitrary field enumeration (T-05-02-04)
        final String osKeywordField;
        final String osTextField;
        switch (field) {
            case "director" -> {
                osKeywordField = "director_list";
                osTextField = "director_list.text";
            }
            case "actors" -> {
                osKeywordField = "full_cast_names";
                osTextField = "full_cast_names.text";
            }
            default -> throw new IllegalArgumentException(
                    "Invalid field '" + field + "'. Allowed values: director, actors");
        }

        org.opensearch.client.opensearch.core.SearchRequest req =
                org.opensearch.client.opensearch.core.SearchRequest.of(r -> r
                        .index(indexName)
                        .query(q -> q.matchPhrasePrefix(m -> m
                                .field(osTextField)
                                .query(prefix)))
                        .size(20)
                        .source(sf -> sf.filter(f -> f.includes(List.of(osKeywordField)))));

        org.opensearch.client.opensearch.core.SearchResponse<Map> response =
                client.search(req, Map.class);

        // Flatten, dedupe, and cap at 10 distinct suggestions
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Hit<Map> hit : response.hits().hits()) {
            Map<?, ?> source = hit.source();
            if (source == null) continue;
            Object val = source.get(osKeywordField);
            if (val instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof String s) seen.add(s);
                    if (seen.size() >= 10) break;
                }
            } else if (val instanceof String s) {
                seen.add(s);
            }
            if (seen.size() >= 10) break;
        }

        return new AutocompleteResponse(new ArrayList<>(seen));
    }

    /**
     * Returns distinct values for chip-based filter fields from the user's index.
     * Genres and contentRatings are human-readable strings; languages and countries
     * are ISO codes (lowercase ISO-639-1 and uppercase ISO-3166-1 respectively).
     */
    public FacetsResponse getFacets(String indexName) throws IOException {
        org.opensearch.client.opensearch.core.SearchRequest req =
                org.opensearch.client.opensearch.core.SearchRequest.of(r -> r
                        .index(indexName)
                        .size(0)
                        .aggregations("genres", Aggregation.of(a -> a
                                .terms(t -> t.field("genre_list").size(1000))))
                        .aggregations("content_ratings", Aggregation.of(a -> a
                                .terms(t -> t.field("content_rating").size(100))))
                        .aggregations("languages", Aggregation.of(a -> a
                                .terms(t -> t.field("language_list").size(500))))
                        .aggregations("countries", Aggregation.of(a -> a
                                .terms(t -> t.field("country_list").size(500)))));

        org.opensearch.client.opensearch.core.SearchResponse<Map> resp =
                client.search(req, Map.class);

        return new FacetsResponse(
                extractTermKeys(resp, "genres"),
                extractTermKeys(resp, "content_ratings"),
                extractTermKeys(resp, "languages"),
                extractTermKeys(resp, "countries"));
    }

    private List<String> extractTermKeys(
            org.opensearch.client.opensearch.core.SearchResponse<Map> resp, String aggName) {
        var agg = resp.aggregations().get(aggName);
        if (agg == null || !agg.isSterms()) return List.of();
        return agg.sterms().buckets().array().stream()
                .map(org.opensearch.client.opensearch._types.aggregations.StringTermsBucket::key)
                .collect(Collectors.toList());
    }

    // ── Query building ─────────────────────────────────────────────────────────

    /**
     * Builds a bool/should query across all indexed text fields with relevance boosts.
     * Title matches rank highest; wikipedia_critics rank lowest.
     */
    private Query buildTextQuery(String queryText) {
        return Query.of(q -> q.bool(b -> b
                .should(List.of(
                        matchQuery("title",                queryText, 4.0f),
                        matchQuery("original_title",       queryText, 3.0f),
                        matchQuery("director_list.text",   queryText, 3.0f),
                        matchQuery("full_cast_names.text", queryText, 2.5f),
                        matchQuery("genre_list.text",      queryText, 2.0f),
                        matchQuery("tagline",              queryText, 2.0f),
                        matchQuery("overview",             queryText, 1.5f),
                        matchQuery("keyword_list.text",    queryText, 1.5f),
                        matchQuery("full_crew_names.text", queryText, 1.0f),
                        matchQuery("wikipedia_summary",    queryText, 1.0f),
                        matchQuery("wikipedia_plot",       queryText, 0.8f),
                        matchQuery("wikipedia_critics",    queryText, 0.6f),
                        matchQuery("personal_notes",       queryText, 1.0f)
                ))
                .minimumShouldMatch("1")));
    }

    private Query matchQuery(String field, String value, float boost) {
        return Query.of(q -> q.match(m -> m
                .field(field)
                .query(fv -> fv.stringValue(value))
                .boost(boost)));
    }

    /**
     * Applies all non-null/non-empty FilterCriteria fields as bool.filter clauses.
     * OR within a group (terms query); AND across groups (multiple filter clauses).
     * All values passed through typed FieldValue builders — no string interpolation (T-05-02-02).
     */
    private void applyFilters(BoolQuery.Builder bool, FilterCriteria criteria) {
        // Genre (multi-select OR) — terms on genre_list (keyword)
        if (criteria.getGenres() != null && !criteria.getGenres().isEmpty()) {
            bool.filter(Query.of(q -> q.terms(t -> t
                    .field("genre_list")
                    .terms(tv -> tv.value(toFieldValues(criteria.getGenres()))))));
        }

        // Director — match_phrase for exact full-name search
        if (hasText(criteria.getDirector())) {
            bool.filter(Query.of(q -> q.matchPhrase(m -> m
                    .field("director_list.text")
                    .query(criteria.getDirector()))));
        }

        // Actors — match_phrase for exact full-name search
        if (hasText(criteria.getActors())) {
            bool.filter(Query.of(q -> q.matchPhrase(m -> m
                    .field("full_cast_names.text")
                    .query(criteria.getActors()))));
        }

        // Crew — match_phrase on full_crew_names (writers, directors of photography, etc.)
        if (hasText(criteria.getCrew())) {
            bool.filter(Query.of(q -> q.matchPhrase(m -> m
                    .field("full_crew_names.text")
                    .query(criteria.getCrew()))));
        }

        // Year range — integer field
        if (criteria.getYearFrom() != null || criteria.getYearTo() != null) {
            bool.filter(buildIntRangeQuery("year", criteria.getYearFrom(), criteria.getYearTo()));
        }

        // IMDB rating range — float, OMDB-nullable.
        // Documents with imdb_rating=null are intentionally excluded by the range filter —
        // correct behaviour for Phase 5 (05-RESEARCH.md Pitfall 2). No exists workaround needed.
        if (criteria.getImdbRatingFrom() != null || criteria.getImdbRatingTo() != null) {
            bool.filter(buildDoubleRangeQuery("imdb_rating",
                    criteria.getImdbRatingFrom(), criteria.getImdbRatingTo()));
        }

        // Content rating (multi-select OR) — keyword terms
        if (criteria.getContentRatings() != null && !criteria.getContentRatings().isEmpty()) {
            bool.filter(Query.of(q -> q.terms(t -> t
                    .field("content_rating")
                    .terms(tv -> tv.value(toFieldValues(criteria.getContentRatings()))))));
        }

        // Runtime max — integer range upper bound only
        if (criteria.getRuntimeMax() != null) {
            bool.filter(Query.of(q -> q.range(r -> r
                    .field("runtime")
                    .lte(JsonData.of(criteria.getRuntimeMax())))));
        }

        // Not-yet-watched toggle.
        // term(watched=false) matches only explicit false, NOT null.
        // All Phase 4/5 docs have watched=null, so this returns empty until Phase 6 sets watched.
        // Intentional per D-10 and 05-RESEARCH.md Pitfall 6 — do not work around with exists query.
        if (Boolean.TRUE.equals(criteria.getNotYetWatched())) {
            bool.filter(Query.of(q -> q.term(t -> t
                    .field("watched")
                    .value(fv -> fv.booleanValue(false)))));
        }

        // Language (multi-select OR) — keyword terms on language_list (ISO-639-1 codes)
        if (criteria.getLanguages() != null && !criteria.getLanguages().isEmpty()) {
            bool.filter(Query.of(q -> q.terms(t -> t
                    .field("language_list")
                    .terms(tv -> tv.value(toFieldValues(criteria.getLanguages()))))));
        }

        // Production country (multi-select OR) — keyword terms on country_list (ISO-3166-1 codes)
        if (criteria.getCountries() != null && !criteria.getCountries().isEmpty()) {
            bool.filter(Query.of(q -> q.terms(t -> t
                    .field("country_list")
                    .terms(tv -> tv.value(toFieldValues(criteria.getCountries()))))));
        }
    }

    private Query buildIntRangeQuery(String field, Integer from, Integer to) {
        return Query.of(q -> q.range(r -> {
            r.field(field);
            if (from != null) r.gte(JsonData.of(from));
            if (to != null)   r.lte(JsonData.of(to));
            return r;
        }));
    }

    private Query buildDoubleRangeQuery(String field, Double from, Double to) {
        return Query.of(q -> q.range(r -> {
            r.field(field);
            if (from != null) r.gte(JsonData.of(from));
            if (to != null)   r.lte(JsonData.of(to));
            return r;
        }));
    }

    // ── Sort ───────────────────────────────────────────────────────────────────

    /**
     * Builds sort options.
     * String sort fields use .raw keyword sub-field (sorted text fields always need .raw).
     * Nullable numeric fields (personal_rating, imdb_rating) use missing="_last".
     */
    private SortOptions buildSort(String sortParam) {
        if (sortParam == null) sortParam = "title_asc";
        return switch (sortParam) {
            case "title_asc"   -> SortOptions.of(s -> s.field(f -> f
                    .field("title.raw").order(SortOrder.Asc)));
            case "year_desc"   -> SortOptions.of(s -> s.field(f -> f
                    .field("year").order(SortOrder.Desc)
                    .missing(mv -> mv.stringValue("_last"))));
            case "rating_desc" -> SortOptions.of(s -> s.field(f -> f
                    .field("personal_rating").order(SortOrder.Desc)
                    .missing(mv -> mv.stringValue("_last"))));
            case "imdb_desc"   -> SortOptions.of(s -> s.field(f -> f
                    .field("imdb_rating").order(SortOrder.Desc)
                    .missing(mv -> mv.stringValue("_last"))));
            case "relevance"   -> SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc)));
            default            -> SortOptions.of(s -> s.field(f -> f
                    .field("title.raw").order(SortOrder.Asc)));
        };
    }

    // ── Mapping helpers ────────────────────────────────────────────────────────

    private SearchResultItem mapHit(Hit<Map> hit) {
        Map<?, ?> src = hit.source();
        if (src == null) src = Map.of();

        return new SearchResultItem(
                hit.id(),
                castInt(src.get("tmdb_id")),
                castString(src.get("title")),
                castInt(src.get("year")),
                castString(src.get("poster_path")),
                castStringList(src.get("director_list")),
                castStringList(src.get("genre_list")),
                castDouble(src.get("imdb_rating")),
                castInt(src.get("runtime")),
                hit.score() != null ? hit.score().doubleValue() : null);
    }

    private String castString(Object val) {
        return val instanceof String s ? s : null;
    }

    private Integer castInt(Object val) {
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        return null;
    }

    private Double castDouble(Object val) {
        if (val instanceof Double d) return d;
        if (val instanceof Number n) return n.doubleValue();
        return null;
    }

    private List<String> castStringList(Object val) {
        if (val instanceof List<?> list) {
            return list.stream()
                    .filter(e -> e instanceof String)
                    .map(e -> (String) e)
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private List<FieldValue> toFieldValues(List<String> values) {
        return values.stream().map(FieldValue::of).collect(Collectors.toList());
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
