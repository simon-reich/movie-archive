package de.moviearchive.bulkimport.dto;

/**
 * D-03/D-05/D-06: a single line in a bulk-import batch's read-only detail view.
 * {@code posterPath} is null for AMBIGUOUS/NOT_FOUND/PARSE_ERROR lines — never
 * fabricated (D-04 only captures a poster for SAVED lines).
 */
public record BulkImportLineResult(
    String title,
    String originalTitle,
    Integer year,
    String status,
    String posterPath
) {}
