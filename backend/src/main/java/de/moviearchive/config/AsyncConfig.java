package de.moviearchive.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean(name = "enrichmentExecutor")
    public Executor enrichmentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("enrich-");
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated executor for WikiReloadService.batchReload (D-05). Sized core=1/max=1/
     * queue=1 by design (D-07) — a larger pool would allow two batch runs to pace
     * Wikipedia calls simultaneously from different threads, defeating the point of
     * sequential pacing. A third overlapping trigger is rejected (TaskRejectedException)
     * once the single run-slot and single queue-slot are both occupied.
     */
    @Bean(name = "wikiReloadExecutor")
    public Executor wikiReloadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("wiki-reload-");
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated executor for BulkImportService.runImport (D-11). Sized core=1/max=1/
     * queue=1, mirroring wikiReloadExecutor above, per CONTEXT.md's explicit discretion
     * note to follow that sizing pattern unless there's a reason to differ — a single
     * global bounded slot per app instance is intentional. A third overlapping trigger
     * is rejected (TaskRejectedException) once the single run-slot and single queue-slot
     * are both occupied.
     */
    @Bean(name = "bulkImportExecutor")
    public Executor bulkImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("bulk-import-");
        executor.initialize();
        return executor;
    }
}
