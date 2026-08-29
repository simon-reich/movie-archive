package de.moviearchive.bulkimport;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BulkImportLineRepository extends JpaRepository<BulkImportLine, UUID> {

    /**
     * WR-02: the four non-batch-scoped predecessors of the batch-scoped finders below
     * ({@code findByUserIdAndNormalizedTitleAndYearAndStatus},
     * {@code findByUserIdAndNormalizedTitleAndYear}, {@code findByUserIdAndRawLineAndYearIsNull},
     * {@code findByUserIdAndNormalizedTitleAndYearIsNull}) were removed entirely rather than kept
     * as dead code. They are the exact signatures whose absence-of-batch-scoping caused the CR-01
     * cross-batch row-reassignment bug (15-REVIEW.md); leaving them in place — unused but
     * compiling fine, with no {@code @Deprecated} warning — was a foot-gun for a future caller
     * reaching for them by accident (e.g. copy-pasting a "find existing row" snippet) and silently
     * reintroducing that bug with no compiler or test signal.
     */

    /**
     * CR-01 (16-01): batch-scoped sibling of the (now-removed) non-batch-scoped
     * {@code findByUserIdAndNormalizedTitleAndYearAndStatus}
     * — the {@code existingSaved} dedup fast-path in {@code BulkImportService.processLine()} must
     * never treat a title/year SAVED in a DIFFERENT batch as already handled (D-02/D-03), so this
     * query adds {@code b.batch.id = :batchId} to the same WHERE clause.
     */
    @Query("SELECT b FROM BulkImportLine b WHERE b.user.id = :userId AND b.batch.id = :batchId "
            + "AND lower(b.title) = :normalizedTitle AND b.year = :year AND b.status = :status")
    Optional<BulkImportLine> findByUserIdAndBatchIdAndNormalizedTitleAndYearAndStatus(
            @Param("userId") UUID userId,
            @Param("batchId") UUID batchId,
            @Param("normalizedTitle") String normalizedTitle,
            @Param("year") Integer year,
            @Param("status") BulkImportLineStatus status);

    /**
     * CR-01 (16-01): batch-scoped sibling of the (now-removed) non-batch-scoped
     * {@code findByUserIdAndNormalizedTitleAndYear} —
     * used by {@code findExistingRow()} so a re-upload only ever finds/updates a row belonging to
     * THIS batch, never silently reassigning a different batch's row (D-01).
     */
    @Query("SELECT b FROM BulkImportLine b WHERE b.user.id = :userId AND b.batch.id = :batchId "
            + "AND lower(b.title) = :normalizedTitle AND b.year = :year")
    Optional<BulkImportLine> findByUserIdAndBatchIdAndNormalizedTitleAndYear(
            @Param("userId") UUID userId,
            @Param("batchId") UUID batchId,
            @Param("normalizedTitle") String normalizedTitle,
            @Param("year") Integer year);

    /**
     * CR-01 (16-01): batch-scoped sibling of the (now-removed) non-batch-scoped
     * {@code findByUserIdAndNormalizedTitleAndYearIsNull}
     * — the WR-03 stale-PARSE_ERROR probe, but scoped to THIS batch only (D-01).
     */
    @Query("SELECT b FROM BulkImportLine b WHERE b.user.id = :userId AND b.batch.id = :batchId "
            + "AND lower(b.title) = :normalizedTitle AND b.year IS NULL")
    Optional<BulkImportLine> findByUserIdAndBatchIdAndNormalizedTitleAndYearIsNull(
            @Param("userId") UUID userId,
            @Param("batchId") UUID batchId,
            @Param("normalizedTitle") String normalizedTitle);

    /**
     * CR-01 (16-01): batch-scoped sibling of the (now-removed) non-batch-scoped
     * {@code findByUserIdAndRawLineAndYearIsNull} — derived query, matching the existing
     * method's style (D-01).
     */
    Optional<BulkImportLine> findByUserIdAndBatchIdAndRawLineAndYearIsNull(
            UUID userId, UUID batchId, String rawLine);

    /**
     * D-03: batch-detail page — every line belonging to a batch, ordered for stable
     * display. Derived query resolves the nested `batch.id` property.
     */
    List<BulkImportLine> findByBatchIdOrderByTitle(UUID batchId);

    /**
     * D-03: per-status counts for a batch's status-distribution summary. Plain
     * `Object[]` rows (element 0 = BulkImportLineStatus, element 1 = Long) — this
     * codebase has no existing interface-projection convention, so none is introduced
     * here; the controller converts rows to a Map<String, Long>.
     */
    @Query("SELECT b.status, COUNT(b) FROM BulkImportLine b WHERE b.batch.id = :batchId GROUP BY b.status")
    List<Object[]> countByBatchIdGroupByStatus(@Param("batchId") UUID batchId);

    /**
     * D-08/T-15-01: the actual IDOR mitigation for the inline resolve endpoint — proves the
     * line belongs to THIS batch, not merely that a line with that id exists somewhere (even
     * one owned by the same user, in a different batch).
     */
    Optional<BulkImportLine> findByIdAndBatchId(UUID id, UUID batchId);
}
