package de.moviearchive.bulkimport;

import org.springframework.stereotype.Component;

/**
 * Parses a single raw bulk-import line into title/originalTitle/year fields (D-01/D-02/D-03).
 * Pure logic, no dependencies — unit-testable in isolation.
 */
@Component
public class ImportLineParser {

    /**
     * Parses one raw line. Returns {@code null} for a blank (whitespace-only) line — the
     * caller must skip it silently, no row persisted, no exception (D-02).
     *
     * <p>Otherwise splits on {@code ";"} using the {@code -1} limit form so a trailing empty
     * field is preserved (plain {@code split(";")} silently drops it) — this matters for
     * {@code Title;;Year} where the OriginalTitle field is intentionally empty.
     *
     * <p>A line is {@code valid} only if it splits into exactly 3 fields, the title (field 0)
     * is non-blank once trimmed, and the year (field 2) parses as an integer once trimmed.
     * An invalid line always has {@code year=null} — this keeps the null-year identity path in
     * {@link BulkImportLineRepository} exactly aligned with {@code valid=false} rows. Title and
     * originalTitle are still populated when individually parseable, so a bad year with an
     * otherwise-good title keeps its title for later display.
     */
    public ParsedLine parse(String rawLine) {
        String trimmed = rawLine.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String[] fields = trimmed.split(";", -1);
        if (fields.length != 3) {
            return new ParsedLine(trimmed, null, null, null, false);
        }

        String title = fields[0].trim();
        String originalTitleRaw = fields[1].trim();
        String originalTitle = originalTitleRaw.isEmpty() ? null : originalTitleRaw;
        String yearRaw = fields[2].trim();

        if (title.isEmpty()) {
            return new ParsedLine(trimmed, null, originalTitle, null, false);
        }

        Integer year;
        try {
            year = Integer.parseInt(yearRaw);
        } catch (NumberFormatException e) {
            return new ParsedLine(trimmed, title, originalTitle, null, false);
        }

        return new ParsedLine(trimmed, title, originalTitle, year, true);
    }

    public record ParsedLine(String rawLine, String title, String originalTitle, Integer year, boolean valid) {}
}
