package de.moviearchive;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;

/**
 * Base class for tests that require a live OpenSearch instance.
 *
 * Extends AbstractIntegrationTest (which provides the Postgres container),
 * and adds a singleton OpenSearch GenericContainer started once per JVM run.
 *
 * NOT managed by @Testcontainers/@Container — static-block singleton prevents
 * the container from stopping between test classes (same pattern as Postgres).
 */
public abstract class AbstractOpenSearchTest extends AbstractIntegrationTest {

    // Single shared container for the entire JVM test run.
    // Started once in a static block — NOT managed by @Testcontainers/@Container.
    @SuppressWarnings("resource")
    static final GenericContainer<?> opensearch;

    static {
        opensearch = new GenericContainer<>("opensearchproject/opensearch:2.19.0")
                .withExposedPorts(9200)
                .withEnv("discovery.type", "single-node")
                .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                // Reduce heap to 512 MB for test environment (default is 1 GB)
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                // Reuse container across test runs to avoid re-start overhead
                .withReuse(true)
                .withStartupTimeout(Duration.ofMinutes(5))
                // Wait for the log message indicating the node is ready to accept HTTP requests.
                // HttpWaitStrategy can fail on OpenSearch 2.x when the cluster is still loading
                // plugins after the first YELLOW->GREEN transition.
                .waitingFor(Wait.forLogMessage(".*o\\.o\\.n\\.Node.*started.*\\n", 1)
                        .withStartupTimeout(Duration.ofMinutes(5)));
        opensearch.start();
    }

    @DynamicPropertySource
    static void overrideOpenSearchProperties(DynamicPropertyRegistry registry) {
        registry.add("opensearch.host", opensearch::getHost);
        registry.add("opensearch.port", () -> opensearch.getMappedPort(9200));
    }
}
