package de.moviearchive.bulkimport;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface BulkImportLineRepository extends JpaRepository<BulkImportLine, UUID> {

    /**
     * D-08 pre-check: is there already a SAVED row for this normalized (title, year)?
     * Always called with a non-null year. If present, the line is skipped entirely —
     * no TMDB call, no write (D-08/D-10).
     */
    @Query("SELECT b FROM BulkImportLine b WHERE b.user.id = :userId "
            + "AND lower(b.title) = :normalizedTitle AND b.year = :year AND b.status = :status")
    Optional<BulkImportLine> findByUserIdAndNormalizedTitleAndYearAndStatus(
            @Param("userId") UUID userId,
            @Param("normalizedTitle") String normalizedTitle,
            @Param("year") Integer year,
            @Param("status") BulkImportLineStatus status);

    /**
     * Find-or-create lookup for lines with a parseable year, regardless of status —
     * used to update the existing row in place on re-upload (D-13).
     */
    @Query("SELECT b FROM BulkImportLine b WHERE b.user.id = :userId "
            + "AND lower(b.title) = :normalizedTitle AND b.year = :year")
    Optional<BulkImportLine> findByUserIdAndNormalizedTitleAndYear(
            @Param("userId") UUID userId,
            @Param("normalizedTitle") String normalizedTitle,
            @Param("year") Integer year);

    /**
     * Find-or-create lookup for lines whose year failed to parse — (title, year) is not
     * a reliable identity when year is null (SQL NULL = NULL is UNKNOWN, not TRUE), so
     * these rows are identified by their normalized raw line text instead.
     */
    Optional<BulkImportLine> findByUserIdAndRawLineAndYearIsNull(UUID userId, String rawLine);
}
