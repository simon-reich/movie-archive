# Phase 4: OpenSearch Indexing - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-17
**Phase:** 04-opensearch-indexing
**Areas discussed:** OS write failure mode, Admin reindex security, Personal fields in mapping

---

## OS Write Failure Mode

| Option | Description | Selected |
|--------|-------------|----------|
| Silent — status stays SUCCESS | Log warning, set indexed_at=null, film stays in Postgres. Admin reindex recovers later. | ✓ |
| Blocking — status becomes ERROR | Film marked ERROR. Consistent with TMDB failure handling. | |
| Retry then silent | @Retryable (3 attempts) then fall back to silent. | |

**User's choice:** Silent — status stays SUCCESS
**Notes:** Consistent with OMDB/Wikipedia degradation (Phase 3 D-15). Search is derived data — Postgres is the source of truth.

---

| Option | Description | Selected |
|--------|-------------|----------|
| status=SUCCESS is sufficient | Indexing state is transparent to user. | ✓ |
| Add indexed_at to status response | Expose indexing state so Phase 5 could show warnings. | |

**User's choice:** status=SUCCESS is sufficient
**Notes:** Status endpoint contract from Phase 3 D-13 unchanged.

---

## Admin Reindex Security

| Option | Description | Selected |
|--------|-------------|----------|
| JWT auth + userId == current user | No new role. Controller checks path param matches JWT subject. | ✓ |
| Dedicated ADMIN role | New user_role column, Flyway migration, hasRole("ADMIN"). | |
| Internal-only, no auth | Accept from localhost only or rely on Caddy routing. | |

**User's choice:** JWT auth + userId == current user
**Notes:** Personal single-user app — no role infrastructure needed.

---

| Option | Description | Selected |
|--------|-------------|----------|
| Full rebuild only | Single endpoint, delete+recreate+reindex all. | |
| Upsert only | Reindex without deleting. | |
| Partial unindexed only | Only films where indexed_at IS NULL. | |
| Two separate endpoints | Full rebuild AND partial — as separate paths. | ✓ |

**User's choice:** Two separate endpoints — POST /admin/reindex/{userId} (full rebuild) + POST /admin/reindex/{userId}/pending (partial unindexed only)
**Notes (verbatim):** "hätte gerne 1. und 3. als seperate operationen. so können wir easy den index ändern durch rebuild, aber auch dem user eine gute möglichkeit geben, eventuell filme, die in der db sind, aber noch nicht indexiert, easy in den bestehenden index zu laden"

---

| Option | Description | Selected |
|--------|-------------|----------|
| /admin/reindex/{userId} + /pending sub-path | Two paths, explicit. | ✓ |
| Single endpoint with mode param | ?mode=full\|pending | |
| You decide | Planner picks naming. | |

**User's choice:** POST /admin/reindex/{userId} + POST /admin/reindex/{userId}/pending

---

## Personal Fields in Mapping

| Option | Description | Selected |
|--------|-------------|----------|
| Include in mapping, always null until Phase 6 | Phase 6 just writes values — no mapping migration. | ✓ |
| Omit from Phase 4 mapping, add in Phase 6 | Leaner now, one extra step in Phase 6. | |
| You decide | Planner picks. | |

**User's choice:** Include in mapping, always null until Phase 6
**Notes:** Consistent with data-model.md as source of truth. Avoids a dynamic mapping update in Phase 6.

---

| Option | Description | Selected |
|--------|-------------|----------|
| Phase 6 triggers OS doc update | When personal fields saved, also upsert OS document. | ✓ |
| Manual reindex only | Phase 6 writes Postgres only; notes not searchable until reindex. | |
| You decide | Planner picks. | |

**User's choice:** Yes — Phase 6 triggers OS doc update
**Notes:** Required for DETAIL-03 (notes are indexed for search). Noted as a Phase 6 integration requirement in CONTEXT.md D-06.

---

## Claude's Discretion

- Whether `IndexingService` is its own `@Service` or logic is added inline to `EnrichmentService`
- How document builder assembles 40+ fields from raw JSON blobs at index time
- Specific response body format for reindex endpoints
- OpenSearch Testcontainers image version (must be 2.x)
- Exact field extraction logic for computed fields (year, imdb_link)

## Deferred Ideas

- Reindex frontend UI — flagged as v2 (FEAT-V2-03 in REQUIREMENTS.md). Phase 4 is API-only.
- Zero-downtime reindex (blue/green alias swap) — overkill for single-user personal app.
