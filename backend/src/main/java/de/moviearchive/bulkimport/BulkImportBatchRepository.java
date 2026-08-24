package de.moviearchive.bulkimport;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BulkImportBatchRepository extends JpaRepository<BulkImportBatch, UUID> {
}
