package de.moviearchive.bulkimport.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * D-03: one row in the batch-list page — GET /movies/bulk-import/batches.
 * {@code statusCounts} keys are {@link de.moviearchive.bulkimport.BulkImportLineStatus}
 * names; values sum to the batch's persisted line count (may be less than
 * {@code totalLines} if lines are still being processed).
 */
public record BulkImportBatchSummary(
    UUID batchId,
    Instant createdAt,
    int totalLines,
    Map<String, Long> statusCounts
) {}
