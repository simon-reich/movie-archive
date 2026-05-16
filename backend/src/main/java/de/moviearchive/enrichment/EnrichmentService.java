package de.moviearchive.enrichment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
public class EnrichmentService {

    @Async("enrichmentExecutor")
    public void enrich(UUID movieId) {
        // Stub: Plan 03-04 implements the full TMDB -> OMDB -> Wikipedia -> Postgres pipeline
        log.info("EnrichmentService.enrich called for movieId={} (stub — Plan 03-04 implements)", movieId);
    }
}
