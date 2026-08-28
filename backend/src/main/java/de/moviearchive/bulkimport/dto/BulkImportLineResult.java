package de.moviearchive.bulkimport.dto;

import java.util.UUID;

/**
 * D-03/D-05/D-06/D-07/D-11: a single line in a bulk-import batch's read-only detail view.
 * {@code posterPath} is null for AMBIGUOUS/NOT_FOUND/PARSE_ERROR lines — never
 * fabricated (D-04 only captures a poster for SAVED lines). {@code movieId} is only
 * ever populated for SAVED lines (D-06/D-07) — every other status gets {@code null}
 * with zero extra query cost. {@code rawLine} is the exact originally-uploaded text,
 * always populated regardless of status, used to display PARSE_ERROR lines verbatim.
 */
public record BulkImportLineResult(
    UUID id,
    String title,
    String originalTitle,
    Integer year,
    String status,
    String posterPath,
    UUID movieId,
    String rawLine
) {}
