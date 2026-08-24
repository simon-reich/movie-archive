package de.moviearchive.bulkimport.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * D-03/D-05/D-06: the full per-line results view for one bulk-import batch —
 * GET /movies/bulk-import/batches/{batchId}, ownership-checked.
 */
public record BulkImportBatchDetail(
    UUID batchId,
    Instant createdAt,
    int totalLines,
    List<BulkImportLineResult> lines
) {}
