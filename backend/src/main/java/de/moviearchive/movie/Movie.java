package de.moviearchive.movie;

import com.fasterxml.jackson.databind.JsonNode;
import de.moviearchive.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "movies")
@Getter
@Setter
@NoArgsConstructor
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "tmdb_id", nullable = false)
    private Integer tmdbId;

    @Column(name = "imdb_id")
    private String imdbId;

    @Column(name = "title")
    private String title;

    @Column(name = "original_title")
    private String originalTitle;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "runtime")
    private Integer runtime;

    @Column(name = "raw_tmdb_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode rawTmdbJson;

    @Column(name = "raw_omdb_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode rawOmdbJson;

    @Column(name = "wiki_plot", columnDefinition = "text")
    private String wikiPlot;

    @Column(name = "wiki_summary", columnDefinition = "text")
    private String wikiSummary;

    @Column(name = "wiki_critics", columnDefinition = "text")
    private String wikiCritics;

    @Column(name = "wiki_url")
    private String wikiUrl;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    @Column(name = "watched", nullable = false)
    private Boolean watched = false;

    @Column(name = "personal_rating")
    private Short personalRating;

    @Column(name = "personal_notes", columnDefinition = "text")
    private String personalNotes;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private MovieStatus status = MovieStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Movie(User user, Integer tmdbId) {
        this.user = user;
        this.tmdbId = tmdbId;
        this.status = MovieStatus.PENDING;
        this.createdAt = Instant.now();
    }
}
