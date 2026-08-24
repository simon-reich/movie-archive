package de.moviearchive.bulkimport;

import de.moviearchive.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bulk_import_batch")
@Getter
@Setter
@NoArgsConstructor
public class BulkImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "total_lines", nullable = false)
    private Integer totalLines;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public BulkImportBatch(User user, int totalLines) {
        this.user = user;
        this.totalLines = totalLines;
        this.createdAt = Instant.now();
    }
}
