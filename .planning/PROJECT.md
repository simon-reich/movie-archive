# MovieArchive

## What This Is

Personal web app to build a searchable film archive. Movies are fetched via TMDB, optionally enriched with OMDB and Wikipedia data, then indexed in OpenSearch for full-text and faceted search. Designed for personal use today, architected to support multiple users later.

## Core Value

Archivieren und finden — ein Film muss sich in Sekunden speichern und genauso schnell wiederfinden lassen. Beides zusammen macht den Wert, keines davon allein reicht.

## Requirements

### Validated

- ✓ Repo-Setup, Docker Compose, Skeletons, CI — Phase 0
- ✓ User-Entity, Token-Entities, Flyway Migrations (V1-V4) — Phase 1
- ✓ AUTH-01: User kann Account mit E-Mail + Passwort erstellen — Phase 1
- ✓ AUTH-02: User erhält Verifizierungs-E-Mail nach Sign-Up; Account wird erst nach Klick aktiviert — Phase 1
- ✓ AUTH-03: User kann sich mit E-Mail + Passwort einloggen (nur ACTIVE-Accounts) — Phase 1
- ✓ AUTH-04: JWT Access Token (15 min) + Refresh Token als HttpOnly-Cookie (7 Tage) — Phase 1
- ✓ AUTH-05: Refresh Token wird bei /auth/refresh rotiert (altes revoked, neues ausgestellt) — Phase 1
- ✓ AUTH-06: User kann sich ausloggen (Refresh Token wird revoked) — Phase 1
- ✓ AUTH-07: Passwort-Reset per E-Mail anfordern (immer 200 OK — Enumeration-Schutz) — Phase 1
- ✓ AUTH-08: Passwort mit Token zurücksetzen; alle Refresh Tokens werden revoked — Phase 1

### Active

- [ ] Settings: API-Key-Management (TMDB + OMDB), E-Mail-Änderung, Passwort-Änderung
- [ ] Settings: API-Key-Management (TMDB + OMDB), E-Mail-Änderung, Passwort-Änderung
- [ ] Film speichern: TMDB → OMDB (optional) → Wikipedia → Postgres → OpenSearch (async)
- [ ] OpenSearch-Index mit Custom Analyzer (movies-{userId})
- [ ] Suche: Freitext + Advanced Filters
- [ ] Film-Detailseite + persönliche Felder (Bewertung, Notizen, Watched)
- [ ] E2E-Tests, Mobile-Polish, README

### Out of Scope

- Öffentliche Film-Datenbank / Social Features — persönliche Sammlung, kein Social Network
- Eigenes Film-Scraping — immer via TMDB API, nie eigene Crawler
- Offline-Modus — Online-App, kein PWA/Service Worker
- Echtzeit-Kollaboration — kein Multiplayer-Editing

## Context

- **Laufende Entwicklung:** Phase 0 + Phase 1 abgeschlossen. MOV-1 bis MOV-40 auf Jira Done. Nächste Phase: 2 (Settings & API Keys).
- **Stack:** Spring Boot 3 + Java 25 / Nuxt 3 + Vue 3 + TypeScript + TailwindCSS / PostgreSQL 16 / OpenSearch 2.x / Caddy
- **Mono-Repo:** `backend/`, `frontend/`, `docker-compose.yml`
- **API-Strategie:** TMDB = Pflicht (kein Key → kein Speichern). OMDB = optional (kein Key → Film ohne IMDB-Daten). Wikipedia = immer versucht, 6-Step-Fallback.
- **Daten-Ownership:** Rohdaten (TMDB/OMDB/Wikipedia) werden beim Speichern eingefroren (Snapshot). Keine nachträgliche Synchronisation mit externen APIs.
- **Token-Sicherheit:** Tokens immer als SHA-256-Hash gespeichert, Single-Use via `consumed_at`, API-Keys AES-256-GCM-verschlüsselt.
- **Jira:** MOV-Projekt, Kanban, MOV-1..MOV-101
- **Docs:** Confluence "MovieArchive App" Space, `.claude/` Detail-Docs

## Constraints

- **Tech Stack:** Spring Boot 3 + Java 25 + Nuxt 3 — keine Änderungen am Stack, in CLAUDE.md festgelegt
- **English-only:** UI, Code, Logs, Tests, Commits — kein i18n, kein Locale-Switching
- **External APIs:** Immer gemockt in Tests (WireMock BE, MSW FE) — nie echte API-Calls in CI
- **Tests liefern mit dem Feature:** Kein Feature-Merge ohne Tests
- **Single-user-first:** Architektur unterstützt Multi-User (movies-{userId} Index), aber v1 für Einzelnutzer
- **Responsive:** Mobile-first ab Phase 7, Desktop-first vorher akzeptabel

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| OpenSearch statt Elasticsearch | Lizenzfreiheit, kompatible API | — Pending |
| OMDB graceful degradation | Nicht alle Nutzer haben OMDB-Key; Film-Archiv trotzdem nutzbar | — Pending |
| SHA-256-Hash für alle Token | Token nie im Klartext gespeichert → DB-Leak harmlos | ✓ Good — Phase 1 |
| AES-256-GCM für API-Keys | API-Keys at rest verschlüsselt, Master-Key aus ENV | — Pending (Phase 2) |
| Save-Flow async (202 Accepted) | Externe API-Calls (TMDB, OMDB, Wikipedia) nicht synchron → UX bleibt flüssig | — Pending (Phase 3) |
| Snapshot-Strategie für Filmdaten | Keine externe Abhängigkeit zur Laufzeit nach dem Speichern | — Pending |
| movies-{userId} Index-Strategie | Datenisolation auf Infra-Ebene, einfach auf Multi-User erweiterbar | — Pending (Phase 4) |
| JwtAuthFilter ohne @Component | Direkt in SecurityFilterChain instanziieren — verhindert Doppel-Registrierung | ✓ Good — Phase 1 |
| grace_until 5s auf Refresh Token | Race Condition bei parallelen Browser-Tabs sicher abgefangen | ✓ Good — Phase 1 |
| forgotPassword immer 200 OK | Enumeration-Schutz — Angreifer kann nicht prüfen ob E-Mail existiert | ✓ Good — Phase 1 |

## Design System

**Global UI-SPEC:** `.planning/UI-SPEC.md` — shared app-level design contract (approved, phase 1). All phases with frontend work use this file instead of per-phase UI-SPEC files. Plans must read `.planning/UI-SPEC.md` before any UI tasks.

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-05-16 after Phase 1 (Authentication) completion*
