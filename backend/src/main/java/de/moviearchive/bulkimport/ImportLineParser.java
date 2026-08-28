package de.moviearchive.bulkimport;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

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

    /**
     * Parses one raw comma-delimited CSV line — the D-12/D-13 sibling to {@link #parse(String)}
     * for the legacy semicolon format. Returns the IDENTICAL {@link ParsedLine} contract (same
     * null-vs-empty/valid semantics), so every downstream consumer (dedup-check, TMDB search,
     * match, upsert) is unaware of which parser produced a given line.
     *
     * <p>Returns {@code null} for a blank (whitespace-only) line, matching {@link #parse(String)}.
     *
     * <p>Uses {@code CSVFormat.DEFAULT} — comma delimiter and double-quote quoting are its
     * built-in behavior (D-15: a quoted comma-containing title parses as one field with zero
     * extra configuration).
     *
     * <p>A record whose field count is not exactly 3 is invalid — checked via {@code size()}
     * BEFORE any {@code .get(index)} call, so a short/malformed record can never throw an
     * uncaught exception (V5 Input Validation, T-15-05). Any {@link IOException} from the
     * underlying parser (malformed quoting, etc.) is also caught and degrades to an invalid
     * {@code ParsedLine} — {@code parseCsv()} must never throw out of this method, matching
     * {@link #parse(String)}'s established swallow-and-degrade convention.
     */
    public ParsedLine parseCsv(String rawLine) {
        String trimmed = rawLine.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        List<CSVRecord> records;
        try (CSVParser csvParser = CSVParser.parse(trimmed, CSVFormat.DEFAULT)) {
            records = csvParser.getRecords();
        } catch (IOException e) {
            return new ParsedLine(trimmed, null, null, null, false);
        }

        if (records.size() != 1) {
            return new ParsedLine(trimmed, null, null, null, false);
        }

        CSVRecord record = records.get(0);
        if (record.size() != 3) {
            return new ParsedLine(trimmed, null, null, null, false);
        }

        String title = record.get(0).trim();
        String originalTitleRaw = record.get(1).trim();
        String originalTitle = originalTitleRaw.isEmpty() ? null : originalTitleRaw;
        String yearRaw = record.get(2).trim();

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
