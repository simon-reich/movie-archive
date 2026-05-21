# MovieArchive

## What This Is

Personal web app to build a searchable film archive. Movies are fetched via TMDB, optionally enriched with OMDB and Wikipedia data, then indexed in OpenSearch for full-text and faceted search. v1.0 shipped — all core flows (auth, save, search, detail) work on desktop and mobile.

## Core Value

Archivieren und finden — ein Film muss sich in Sekunden speichern und genauso schnell wiederfinden lassen. Beides zusammen macht den Wert, keines davon allein reicht.

## Requirements

### Validated

- ✓ Repo-Setup, Docker Compose, Skeletons, CI — Phase 0
- ✓ User-Entity, Token-Entities, Flyway Migrations (V1–V7) — Phases 1–6
- ✓ AUTH-01: User kann Account mit E-Mail + Passwort erstellen — Phase 1
- ✓ AUTH-02: User erhält Verifizierungs-E-Mail nach Sign-Up; Account wird erst nach Klick aktiviert — Phase 1
- ✓ AUTH-03: User kann sich mit E-Mail + Passwort einloggen (nur ACTIVE-Accounts) — Phase 1
- ✓ AUTH-04: JWT Access Token (15 min) + Refresh Token als HttpOnly-Cookie (7 Tage) — Phase 1
- ✓ AUTH-05: Refresh Token wird bei /auth/refresh rotiert (altes revoked, neues ausgestellt) — Phase 1
- ✓ AUTH-06: User kann sich ausloggen (Refresh Token wird revoked) — Phase 1
- ✓ AUTH-07: Passwort-Reset per E-Mail anfordern (immer 200 OK — Enumeration-Schutz) — Phase 1
- ✓ AUTH-08: Passwort mit Token zurücksetzen; alle Refresh Tokens werden revoked — Phase 1
- ✓ SET-01: TMDB API-Key speichern und ändern (AES-256-GCM, maskiert) — Phase 2
- ✓ SET-02: OMDB API-Key speichern und ändern (optional, gleiche Verschlüsselung) — Phase 2
- ✓ SET-03: Passwort ändern (erfordert aktuelles Passwort; revoked alle Sessions) — Phase 2
- ✓ SET-04: E-Mail-Adresse ändern (Token-Link an neue Adresse; alte informiert) — Phase 2
- ✓ SAVE-01: Film via TMDB-Suche hinzufügen → sofort 202 Accepted — Phase 3
- ✓ SAVE-02: Backend async: TMDB → OMDB (optional) → Wikipedia → Postgres → OpenSearch — Phase 3
- ✓ SAVE-03: OMDB graceful fail (kein Key, kein imdb_id) — Phase 3
- ✓ SAVE-04: Wikipedia graceful fail (6-Step-Fallback); Film immer gespeichert — Phase 3
- ✓ SAVE-05: UI Save-Status sichtbar (pending → success / error) — Phase 3
- ✓ IDX-01: Film in OpenSearch-Index `movies-{userId}` nach Persist — Phase 4
- ✓ IDX-02: Index auto-created mit Custom Analyzer + Mapping auf erstem Write — Phase 4
- ✓ IDX-03: Custom Analyzer: asciifolding, lowercase, elision, stop_english, kstem — Phase 4
- ✓ IDX-04: Admin-Endpoint `POST /admin/reindex/{userId}` — Phase 4
- ✓ SRCH-01: Freitext-Suche über alle indizierten Film-Felder — Phase 5
- ✓ SRCH-02: Advanced Filters: Genre, Regisseur, Jahr, IMDB-Rating, Content-Rating, Watched — Phase 5
- ✓ SRCH-03: Sortierung: Titel A–Z, Jahr, persönliches Rating, IMDB-Rating — Phase 5
- ✓ SRCH-04: Klick auf Attribut öffnet vorgefilterte Suche — Phase 5
- ✓ DETAIL-01: Film-Detailseite alle TMDB + OMDB Felder (nullable ausgeblendet) — Phase 6
- ✓ DETAIL-02: Wikipedia Plot und Critics-Section (falls vorhanden) — Phase 6
- ✓ DETAIL-03: Persönliche Felder: Watched-Status, Rating (0–10), Notizen (indiziert) — Phase 6
- ✓ DETAIL-04: Trailer als YouTube-Embed (via trailer_key) — Phase 6
- ✓ DETAIL-05: Klick auf Schauspieler/Regisseur/Genre → gefilterte Suchergebnisliste — Phase 6
- ✓ QLTY-01: Responsive + vollständig nutzbar auf Mobilgeräten — Phase 7
- ✓ QLTY-02: Playwright E2E Happy Paths auf Desktop + Mobile Chrome — Phase 7
- ✓ QLTY-03: GitHub Actions E2E CI + README Setup-Dokumentation — Phase 7

### Active (v2 candidates)

- [ ] SET-05: CSV-Export aller Filmdaten — deferred from v1 (UI placeholder vorhanden)
- [ ] SET-06: CSV-Import aus Datei — deferred from v1 (UI placeholder vorhanden)
- [ ] AUTH-V2-01: OAuth-Login (Google)
- [ ] FEAT-V2-01: Letterboxd CSV-Import
- [ ] FEAT-V2-02: Statistik-Dashboard (Genres, Jahrzehnte, Watched-Fortschritt)
- [ ] MULTI-01: Multi-User (Registration für weitere Accounts)

### Out of Scope

- Öffentliche Film-Datenbank / Social Features — persönliche Sammlung, kein Social Network
- Eigenes Film-Scraping — immer via TMDB API
- Offline-Modus / PWA — Online-App reicht
- Echtzeit-Kollaboration — kein Multiplayer-Editing
- Recommendation Engine — ML-Infrastruktur, separates Projekt
- Streaming-Plattform-Verfügbarkeit — externe Datenpflege

## Context

**Current State (v1.0 — shipped 2026-05-21):**
- All 7 phases complete, all 28 plans shipped
- 258 commits, 369 files, ~62k lines of production + test code
- All 101 Jira tickets (MOV-1–MOV-101) closed
- Stack: Spring Boot 3 + Java 25 / Nuxt 3 + Vue 3 + TypeScript + TailwindCSS + shadcn-vue / PostgreSQL 16 / OpenSearch 2.x / Caddy / Docker Compose
- Testing: JUnit 5 + Testcontainers (BE), Vitest + Vue Test Utils + MSW (FE), Playwright E2E
- External APIs: TMDB (required), OMDB (optional), Wikipedia (always tried)

## Constraints

- **Tech Stack:** Spring Boot 3 + Java 25 + Nuxt 3 — keine Änderungen am Stack
- **English-only:** UI, Code, Logs, Tests, Commits — kein i18n
- **External APIs:** Immer gemockt in Tests (WireMock BE, MSW FE)
- **Tests liefern mit dem Feature:** Kein Feature-Merge ohne Tests
- **Single-user-first:** Architektur unterstützt Multi-User, aber v1 für Einzelnutzer

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| OpenSearch statt Elasticsearch | Lizenzfreiheit, kompatible API | ✓ Good |
| OMDB graceful degradation | Nicht alle Nutzer haben OMDB-Key | ✓ Good |
| SHA-256-Hash für alle Token | Token nie im Klartext gespeichert | ✓ Good — Phase 1 |
| AES-256-GCM für API-Keys | API-Keys at rest verschlüsselt, Master-Key aus ENV | ✓ Good — Phase 2 |
| Save-Flow async (202 Accepted) | Externe API-Calls nicht synchron → UX flüssig | ✓ Good — Phase 3 |
| Snapshot-Strategie für Filmdaten | Keine externe Abhängigkeit nach dem Speichern | ✓ Good — Phase 3 |
| movies-{userId} Index-Strategie | Datenisolation auf Infra-Ebene | ✓ Good — Phase 4 |
| JwtAuthFilter ohne @Component | Verhindert Doppel-Registrierung in SecurityFilterChain | ✓ Good — Phase 1 |
| grace_until 5s auf Refresh Token | Race Condition bei parallelen Browser-Tabs sicher abgefangen | ✓ Good — Phase 1 |
| forgotPassword immer 200 OK | Enumeration-Schutz | ✓ Good — Phase 1 |
| GenericContainer für OS Testcontainers | opensearch-testcontainers 4.x inkompatibel mit OS 2.x | ✓ Good — Phase 4 |
| withJson() für Index-Erstellung | opensearch-java typed builder Bug #1510 | ✓ Good — Phase 4 |
| auth.getName() returns email | UserDetailsServiceImpl username=email — ownership checks via findByEmail | ✓ Good — Phase 4 |
| Refresh.WaitFor auf index() | Document sofort suchbar — behebt E2E Mobile Chrome Race Condition | ✓ Good — Phase 7 |
| Tailwind viewport breakpoints für Star Rating | Container queries scheiterten an flex shrink-to-fit sizing | ✓ Good — Phase 7 |
| useHead html background für overscroll | Browser nutzen \<html\> background für top-overscroll gutter | ✓ Good — Phase 7 |

## Design System

**Global UI-SPEC:** `.planning/UI-SPEC.md` — shared app-level design contract (approved, Phase 1). All phases with frontend work use this file.

---
*Last updated: 2026-05-21 after v1.0 milestone completion*
