package de.moviearchive.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private Bucket resolveBucket(String ip) {
        return buckets.computeIfAbsent(ip, k ->
                Bucket.builder()
                        .addLimit(Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1))))
                        .build());
    }

    public boolean tryConsume(String ip) {
        return resolveBucket(ip).tryConsume(1);
    }

    public long estimateRetryAfterSeconds(String ip) {
        return resolveBucket(ip).estimateAbilityToConsume(1).getNanosToWaitForRefill() / 1_000_000_000L;
    }

    /** Clears all rate-limit buckets. Exposed for use in integration tests between test runs. */
    public void resetAll() {
        buckets.clear();
    }
}
