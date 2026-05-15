# MovieArchive — Research Summary

**Synthesized from:** STACK.md, FEATURES.md, ARCHITECTURE.md, PITFALLS.md
**Date:** 2026-05-15
**Overall Confidence:** HIGH

---

## Executive Summary

MovieArchive ist eine Brownfield-App mit gelocketem Stack. Die empfohlene Implementierungsreihenfolge ist strikt sequenziell: Auth (Phase 1) gated alle geschützten Endpoints → Settings/API-Keys (Phase 2) → Save Flow (Phase 3) → OpenSearch-Indexing (Phase 4) → Suche (Phase 5) → Detail + persönliche Felder (Phase 6). Diese Reihenfolge kann nicht geändert werden.

Die drei risikoreichsten Bereiche sind: (1) die async Enrichment-Pipeline (mehrere proxy-bypass und null-guard Pitfalls, die Saves silent droppen), (2) der OpenSearch Index-Lifecycle (Custom Analyzer muss bei der ersten Erstellung angewendet werden — kein retroaktiver Pfad), und (3) die JWT + Refresh-Token-Infrastruktur in Phase 1 (vier unabhängige Pitfalls, die schwer nachzurüsten sind).

---

## Stack

**Critical version-specific knowledge (alle HIGH confidence):**

- **JJWT 0.12.6**: Breaking API change von 0.11.x — `.parseSignedClaims()` nicht `.parseClaimsJws()`, `.subject()` nicht `.setSubject()`
- **opensearch-java 2.19.0**: `ApacheHttpClient5Transport` verwenden — `RestClientTransport` deprecated; `spring-data-opensearch` NICHT verwenden (versteckt Custom Analyzer Config)
- **Spring `@Async` + `@Retryable`**: Beide Annotationen müssen auf einer **separaten Bean** vom Caller leben — Self-Invocation bypassed Spring AOP-Proxy silent
- **Raw JDK AES-256-GCM**: Bevorzugt gegenüber `AesBytesEncryptor` (unnötiger PBKDF2-Overhead für bereits 32-byte ENV-Key)
- **WebClient**: Korrekt für TMDB/OMDB/Wikipedia — `.block()` innerhalb `@Async`-Thread ist idiomatisch; RestTemplate ist Maintenance-Mode
- **`ThreadPoolTaskExecutor`** (core=2, max=5, queue=50): `SimpleAsyncTaskExecutor` (Spring default) = unbegrenzte Threads → ungeeignet
- **MapStruct**: `lombok-mapstruct-binding:0.2.0` muss letzter in `annotationProcessor` — bereits korrekt

---

## Table Stakes (v1 — ohne diese ist die App unbrauchbar)

- Auth: Sign-Up, E-Mail-Verifikation, Login, Logout, Passwort-Reset
- TMDB API-Key Management — App ist ohne Key inoperabel
- Async Save Flow (202-Pattern: TMDB → OMDB optional → Wikipedia → Postgres → OpenSearch)
- **Async Save Status Feedback** — 202 mit silent failure ist inakzeptable UX
- Full-text Suche über gespeicherte Filme
- Watched-Status, persönliches Sterne-Rating, persönliche Notizen (indiziert)
- Film-Detailseite mit Wikipedia-Abschnitten und Multi-Source-Ratings

## Differenzierende Features

- **Advanced Faceted Search** (Genre, Regisseur, Jahr, Content-Rating, Rating-Range, Watched-Filter) — primärer Differenziator vs. Letterboxd
- **Wikipedia-Anreicherung** (Plot + Critics-Sections) — kein Konkurrent bietet das

## Anti-Features (jede davon ist ein separates Projekt)

- Social Sharing / Letterboxd-Import
- Streaming-Plattform-Sync / Recommendation Engine
- Offline-Modus / PWA

---

## Architecture

**Component boundaries:**
- PostgreSQL = Source of Truth; OpenSearch = derived, rebuildable
- Save returns 202 sofort nach Postgres-Stub-Insert; `MovieEnrichmentService` (separate injected Bean) läuft async
- `JwtAuthFilter` skippt auth-bootstrapping paths explizit
- `OpenSearchIndexService` ruft `ensureIndexExists(userId)` vor jedem Write
- Nuxt SSR: Access Token in Pinia-Memory (nie localStorage), Refresh Token in HttpOnly-Cookie

**Build order (hard dependencies):**
1. Auth (JWT-Filter, SecurityConfig, UserDetailsService) → alles andere hängt davon ab
2. Settings (TMDB-Key) → benötigt bevor Save Flow möglich
3. Save Flow → benötigt bevor Index-Content vorhanden
4. OpenSearch Indexing → Custom Analyzer muss vor Phase 3 abgeschlossen sein
5. Suche → benötigt Phase 4
6. Detail + persönliche Felder → benötigt Phase 3-Dokumente

---

## Top 7 Pitfalls (alle HIGH confidence)

| # | Pitfall | Phase | Prevention |
|---|---------|-------|-----------|
| 1 | **JWT Filter Double-Registration** via `@Component` | 1 | Nicht `@Component` auf `JwtAuthFilter` — direkt in `SecurityFilterChain` instantiieren |
| 2 | **Refresh Token Race Condition** (2 concurrent calls) | 1 | `grace_until TIMESTAMPTZ` auf refresh_tokens-Tabelle + client-seitiger Refresh-Queue |
| 3 | **CSRF nicht explizit disabled** | 1 | `csrf(AbstractHttpConfigurer::disable)` explizit — `STATELESS` allein reicht NICHT |
| 4 | **AES-256-GCM Nonce-Reuse** | 2 | IV = `new SecureRandom().nextBytes(iv)` pro Encryption-Call, IV dem Ciphertext prependen |
| 5 | **OMDB Null-Guard Gap** (imdb_id fehlt in TMDB Response) | 3 | Guard: `omdbKey != null && imdbId != null && !imdbId.isBlank()` — sonst NPE bricht gesamte async Pipeline |
| 6 | **Wikipedia retrying auf definitive 404** | 3 | HTTP 200 + `"missing": true` → sofort return, KEIN Retry; nur 5xx/Timeout retrien |
| 7 | **OpenSearch Index ohne Custom Analyzer erstellt** | 4 | `ensureIndexExists()` muss finale Analyzer-Config + alle 40+ Field-Mappings beim ersten Create anwenden |

---

## Roadmap-Implications

**Phases requiring `/gsd-research-phase`:** Phase 3 only (WireMock fixture matrix + enrichment-sequence test design)

**Phases mit gut dokumentierten Patterns (research überspringen):** Phases 1, 2, 4, 5, 6, 7

**Open gaps für Planning:**
- `grace_until TIMESTAMPTZ` auf refresh_tokens-Tabelle fehlt in data-model.md → explizit in Phase 1 Planning adressieren
- Nuxt SSR vs. Client-side-only: explizite Entscheidung in Phase 1 — client-side-only ist simpler und für personal-use v1 akzeptabel
- Wikipedia User-Agent Header: Muss `MovieArchive/0.1 (...)` sein — Wikimedia rate-limitet generic agents
