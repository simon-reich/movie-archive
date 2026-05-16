---
phase: 03-save-movie-flow
plan: 02
subsystem: backend-data-model
tags: [flyway, jpa, async, dto, postgresql]
dependency_graph:
  requires: []
  provides: [movies-table-schema, Movie-entity, MovieRepository, MovieStatus-enum, AsyncConfig-bean, SaveMovieRequest-dto, MovieStatusResponse-dto, TmdbSearchResultItem-dto]
  affects: [03-03-movie-controller, 03-04-enrichment-service, 03-05-tmdb-client]
tech_stack:
  added: []
  patterns: [JPA-entity-ManyToOne, Spring-Data-JPA-repository, ThreadPoolTaskExecutor, record-DTO, Flyway-migration]
key_files:
  created:
    - backend/src/main/resources/db/migration/V6__create_movies.sql
    - backend/src/main/java/de/moviearchive/movie/Movie.java
    - backend/src/main/java/de/moviearchive/movie/MovieStatus.java
    - backend/src/main/java/de/moviearchive/movie/MovieRepository.java
    - backend/src/main/java/de/moviearchive/movie/dto/SaveMovieRequest.java
    - backend/src/main/java/de/moviearchive/movie/dto/MovieStatusResponse.java
    - backend/src/main/java/de/moviearchive/movie/dto/TmdbSearchResultItem.java
    - backend/src/main/java/de/moviearchive/config/AsyncConfig.java
  modified: []
decisions:
  - "status stored as VARCHAR(10) with CHECK constraint rather than Postgres ENUM — avoids ALTER TYPE DDL migrations when adding states"
  - "UNIQUE(user_id, tmdb_id) at DDL level enforces idempotent saves; DataIntegrityViolationException caught in controller (Plan 03-03)"
  - "AsyncConfig does NOT add @EnableAsync — already present on MovieArchiveApplication to prevent double registration"
  - "existsByUserIdAndTmdbId added to MovieRepository for pre-save duplicate check without loading entity"
metrics:
  duration: ~8 minutes
  completed: "2026-05-16"
  tasks_completed: 2
  files_created: 8
---

# Phase 3 Plan 02: Backend Data Model and AsyncConfig Summary

Movies table DDL, JPA entity with JSONB columns, ownership-scoped repository, three DTOs, and a bounded ThreadPoolTaskExecutor bean that Wave 2 plans (controller, enrichment, clients) can import directly.

## What Was Built

### Task 1: Flyway V6 Migration (commit aacf844)

Created `V6__create_movies.sql` with the exact DDL specified in the plan:
- `movies` table with all 17 columns including JSONB `raw_tmdb_json`/`raw_omdb_json`
- `status VARCHAR(10) NOT NULL DEFAULT 'PENDING'` with CHECK constraint
- `UNIQUE (user_id, tmdb_id)` preventing duplicate saves at database level
- `indexed_at TIMESTAMPTZ` for Phase 4 OpenSearch tracking (null = not yet indexed)
- Two composite indexes: `idx_movies_user_id` and `idx_movies_status(user_id, status)`

HealthSmokeTest confirmed Flyway applies V6 cleanly on Testcontainers Postgres.

### Task 2: Entity, Repository, Enum, DTOs, AsyncConfig (commit c31dde3)

**MovieStatus.java** — enum with PENDING, SUCCESS, ERROR.

**Movie.java** — JPA entity:
- `@ManyToOne(fetch = FetchType.LAZY)` to `User` via `user_id`
- JSONB columns via `@JdbcTypeCode(SqlTypes.JSON)` mapping to `JsonNode`
- Constructor `Movie(User user, Integer tmdbId)` for clean instantiation

**MovieRepository.java** — Spring Data JPA:
- `findByIdAndUserId(UUID id, UUID userId)` — ownership-scoped lookup (cross-user leakage prevention)
- `existsByUserIdAndTmdbId(UUID userId, Integer tmdbId)` — duplicate detection

**DTOs:**
- `SaveMovieRequest` — record with `@Positive` on `tmdbId`
- `MovieStatusResponse` — record `{ id, status, title }`
- `TmdbSearchResultItem` — record `{ tmdbId, title, year, posterPath }`

**AsyncConfig.java** — bounded `ThreadPoolTaskExecutor`:
- `corePoolSize=2`, `maxPoolSize=5`, `queueCapacity=50`, prefix `enrich-`
- Named bean `enrichmentExecutor` for `@Async("enrichmentExecutor")` references in Plan 03-04

## Verification

- `./gradlew compileJava` — BUILD SUCCESSFUL (zero errors)
- `./gradlew test --tests "de.moviearchive.HealthSmokeTest"` — BUILD SUCCESSFUL
- `./gradlew test` (full suite) — BUILD SUCCESSFUL (no regressions)

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None — this plan delivers pure infrastructure (schema + types). No UI rendering paths, no data flow stubs.

## Threat Flags

None — all threat mitigations in the plan's threat model are implemented:
- T-03-02-01: `@Positive` on `SaveMovieRequest.tmdbId`
- T-03-02-02: `findByIdAndUserId` scoped by userId
- T-03-02-03: `UNIQUE (user_id, tmdb_id)` DDL constraint
- T-03-02-04: Bounded `ThreadPoolTaskExecutor` (maxPoolSize=5, queue=50)
- T-03-02-05: JSONB stored as opaque `JsonNode` — accepted, no injection risk

## Self-Check: PASSED

Files exist:
- backend/src/main/resources/db/migration/V6__create_movies.sql — FOUND
- backend/src/main/java/de/moviearchive/movie/Movie.java — FOUND
- backend/src/main/java/de/moviearchive/movie/MovieStatus.java — FOUND
- backend/src/main/java/de/moviearchive/movie/MovieRepository.java — FOUND
- backend/src/main/java/de/moviearchive/movie/dto/SaveMovieRequest.java — FOUND
- backend/src/main/java/de/moviearchive/movie/dto/MovieStatusResponse.java — FOUND
- backend/src/main/java/de/moviearchive/movie/dto/TmdbSearchResultItem.java — FOUND
- backend/src/main/java/de/moviearchive/config/AsyncConfig.java — FOUND

Commits verified:
- aacf844 — feat(03-02): Flyway V6 migration — create movies table
- c31dde3 — feat(03-02): Movie entity, repository, status enum, DTOs, and AsyncConfig
