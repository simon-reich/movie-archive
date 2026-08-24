package de.moviearchive.bulkimport;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BulkImportBatchRepository extends JpaRepository<BulkImportBatch, UUID> {

    /**
     * D-03: batch-list page — the authenticated user's past bulk-import batches,
     * newest-first. Derived query resolves the nested `user.id` property, the same
     * convention already used by MovieRepository's findByUserId* methods.
     */
    List<BulkImportBatch> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
