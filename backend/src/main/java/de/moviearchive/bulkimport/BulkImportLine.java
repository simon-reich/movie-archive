package de.moviearchive.bulkimport;

import de.moviearchive.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bulk_import_line")
@Getter
@Setter
@NoArgsConstructor
public class BulkImportLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private BulkImportBatch batch;

    @Column(name = "title")
    private String title;

    @Column(name = "original_title")
    private String originalTitle;

    @Column(name = "year")
    private Integer year;

    @Column(name = "tmdb_id")
    private Integer tmdbId;

    @Column(name = "poster_path")
    private String posterPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BulkImportLineStatus status;

    @Column(name = "raw_line", columnDefinition = "text")
    private String rawLine;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public BulkImportLine(User user, String rawLine) {
        this.user = user;
        this.rawLine = rawLine;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
