# MovieArchive

## What This Is

Personal web app to build a searchable film archive. Movies are fetched via TMDB, optionally enriched with OMDB and Wikipedia data, then indexed in OpenSearch for full-text and faceted search. v1.0 shipped all core flows (auth, save, search, detail) on desktop and mobile; v1.1 added reliable Wikipedia enrichment (Wikidata-first lookup, batch reload with pacing/cooldown, manual per-film retry) and a full in-app Bulk Import feature (CSV/semicolon upload, live progress, inline ambiguous-match resolution).

## Core Value

Archivieren und finden — ein Film muss sich in Sekunden speichern und genauso schnell wiederfinden lassen. Beides zusammen macht den Wert, keines davon allein reicht.

## Current State

**v1.1 Enrichment Reliability & Bulk Import — shipped 2026-08-29 (9 phases, 28 plans, 53 tasks).**

Delivered: reliable Wikipedia enrichment (attempt tracking, cooldown-filtered paced batch-reload, manual per-film retry, Wikidata-first SPARQL-batched lookup replacing the fragile URL-guessing cascade) and a complete in-app Bulk Import feature (CSV/semicolon upload, live SSE progress, per-line results with inline ambiguous/not-found resolution, batch history). Both areas went through multiple rounds of live-data UAT that found and fixed real bugs mocked tests couldn't surface — see Context below and the Key Decisions table.

**Trigger for this milestone:** Bulk-import of ~630 films via an external Node script drove Wikipedia into rate-limiting — ~89% of imported films ended up without Wikipedia data, silently failed with no retry path.

## Current Milestone: v2.0 UX Polish, Wiki Enrichment & Personal Lists

**Goal:** Lift Bulk Import and Search onto a consistent, information-dense list UI, upgrade the detail page and rating display, make Wikipedia content richer and readable, expand the dashboard hub into a real overview (incl. batch wiki-reload monitor), add index CSV import/export, and lay the foundation for Personal Lists (PDF export follows in v3).

**Target features:**
- Bulk Import: list view without posters (real table columns); live progress bar directly on the Add Film page + live poster pop-in on the detail page
- Search: Language/Country filter values spelled out instead of codes; reversible sort direction (all criteria); list view without posters (same column pattern as Bulk Import)
- Detail page: show Original Title; clickable IMDB link; clickable Wikipedia link (once loaded)
- Rating: stars → square grid (no gaps between boxes), with number feedback shown in/near the selected box
- Wikipedia enrichment (research first): what fields are fetched today vs. what's available; preserve paragraph/HTML structure instead of a flat plaintext block
- Dashboard/hub: Batch Wiki Reload card (progress + link to its own detail page + "last 5 processed movies" mini-monitor), Language/Genre chips spelled out + grid/flexbox layout
- Index CSV export + import
- Personal Lists (foundation only): mark movies, add to a list, save/view/manage lists — no PDF export yet

**Deferred to v3:** PDF export of Personal Lists (styling, desktop/mobile layout, one film per page with trailer/Wikipedia/IMDB links)

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
- ✓ ENRICH-01: `wiki_last_attempted_at` Zeitstempel pro Film — Phase 8
- ✓ ENRICH-02: Batch-Reload-Endpoint mit Cooldown-Filter, gepaced — Phase 8
- ✓ ENRICH-03: Manueller Retry-Button auf Detailseite für Filme ohne Wiki-Daten — Phase 9
- ✓ IMPORT-01: Bulk-Import (Title;OriginalTitle;Year-Zeilen) als In-App-Feature, Datei-Upload im Bereich "Add Film"; Parse/Match/Save/Dedup + Format-Hinweis und Pre-Flight-Fehlermeldung bei komplett unparsbaren Uploads — Phase 10
- ✓ IMPORT-02: Live-Fortschrittsanzeige während des Imports — Phase 11
- ✓ IMPORT-03: Ergebnisübersicht nach Import — Titel, Poster, Status (gespeichert/mehrdeutig/nicht gefunden) pro Zeile — Phase 11
- ✓ Wikidata-first Wikipedia-Lookup (P345 IMDb-ID Cross-Reference statt URL-Kandidaten-Raten) — Phase 12
- ✓ Batched SPARQL-Wikidata-Auflösung (bis zu 50 IMDb-IDs pro Request) ersetzt die per-movie REST-Suche, die Wikidata's anonymen Rate-Limiter nach 2-3 Filmen traf; `WikiReloadService.batchReload()` und `BulkImportService` prefetchen jetzt einmal pro Lauf statt einmal pro Film — behebt das ~630-Film Rate-Limit-Incident aus dem v1.1-Trigger — Phase 13
- ✓ D-14-01: Deliberate, env-configurable Pacing zwischen Filmen in `batchReload()` (Default via Live-A/B-Test auf 20s justiert) — Phase 14
- ✓ D-14-02: Cooldown (`wikiLastAttemptedAt`) wird nur bei genuinem, erfolgreich ausgeführtem Versuch (Success oder bestätigtes Not-Found) gesetzt, nicht bei technischem/Rate-Limit-Fehler — Phase 14
- ✓ D-14-03: Batch-Reload Progress-UI — Live-SSE-Stream mit Anzahl, Live-Fortschritt pro Film, Rolling-Average-ETA — Phase 14
- ✓ D-14-04: Stop-Control zum sauberen Unterbrechen eines laufenden Batch-Reload-Runs (kein dediziertes Resume nötig, nutzt bestehende Cooldown-Eligibility-Query) — Phase 14
- ✓ D-01–D-07, D-11 (display half): Grid/List-View-Toggle mit localStorage-Persistenz, SAVED-Zeilen komplett klickbar zur Detailseite, PARSE_ERROR-Zeilen zeigen den vollen Rohstring — Phase 15
- ✓ D-08–D-10, D-11 (resolve boundary): Inline-Auflösung mehrdeutiger/nicht-gefundener Treffer direkt in der Ergebnisübersicht (frischer TMDB-Search on-expand, neuer ownership-geprüfter Resolve-Endpoint, Full-Refetch nach Speichern) — löst IMPORT-V2-01 ein — Phase 15
- ✓ D-12–D-17: Echtes komma-getrenntes CSV-Parsing (RFC4180-Quoting via Apache Commons CSV) als zweites unterstütztes Import-Format für die bestehenden drei Spalten (Titel/OriginalTitel/Jahr); Semikolon-Format bleibt unverändert; optionale Header-Zeile wird automatisch übersprungen — löst SET-06 für den bestehenden Spaltenumfang ein (kein CSV-Export, keine zusätzlichen Spalten) — Phase 15
- ✓ Live-UAT (2026-08-28) fand und fixte 3 reale Bugs, die gemockte Tests nicht zeigen konnten: SAVED-Karten navigierten nicht (Nuxt registriert `NuxtLink` nur bei literalem Tag, nicht bei `:is`-String-Binding — Vitest-Stub maskierte das), Resolve-Widget-Kandidaten waren ins schmale Grid-Zellen-Layout gequetscht, und die neuen Kandidaten-Labels (Titel+Jahr) wurden bei langen Titeln per Ellipsis abgeschnitten statt umzubrechen — Phase 15
- ✓ CR-01: Cross-Batch-Zeilen-Reassignment-Bug in `BulkImportService.findExistingRow()` behoben — dedupliziert jetzt nach `userId`+`batchId`+Titel+Jahr statt nur `userId`+Titel+Jahr, damit ein Re-Upload eines überlappenden Titel/Jahr-Paars in einem anderen Batch nicht mehr die Zeile eines bestehenden Batches umbucht — Phase 16
- ✓ WR-02: Wiki-Reload-Progress-UI unterscheidet jetzt "gestoppt" von "vollständig abgeschlossen" (`ProgressState.stopped`, `wikiStatusLabel` zeigt "Stopped at X / Y" vs. "Completed Y / Y") — Phase 16
- ✓ D-10–D-12: Mehrstufiger automatischer TMDB-Match-Algorithmus in `processLine()` — einzelnes Suchergebnis wird direkt übernommen (ignoriert Jahr-Mismatch), mehrere Ergebnisse werden zuerst exakt (Titel-oder-OriginalTitle + Jahr), dann per OriginalTitle-Fallback genarrowt, bevor auf AMBIGUOUS zurückgefallen wird (D-04-Invariante "nie automatisch raten" bleibt unverändert) — Phase 16
- ✓ G-16-2: Per-Movie-History dupliziert nach einem Stop/Abschluss nicht mehr den zuletzt verarbeiteten Filmtitel (Guard `p.lastMovieTitle && !p.complete` in `settings.vue`, da das terminale SSE-Event den letzten `progress`-Eintrag bewusst echot) — Phase 16, live in UAT gefunden und gefixt
- ✓ G-16-3: `MovieRepository.findEligibleForWikiReload` schlüsselt Retry-Eligibility jetzt an `wiki_url IS NULL` statt an `wiki_plot`/`wiki_critics IS NULL` — ein bereits gefundener Wikipedia-Artikel mit nicht-extrahierbarem Plot/Critics-Abschnitt wurde vorher für immer erneut versucht (41/305 betroffene Filme im Live-Datensatz) — Phase 16, live in UAT gefunden und gefixt

### Active

v2.0 requirements being defined — see `.planning/REQUIREMENTS.md` once written.

### v3 candidates (deferred, not in v2.0)

- [ ] PDF-01: PDF-Export von Personal Lists (Styling, Desktop/Mobile-Layout, ein Film pro Seite mit Trailer/Wikipedia/IMDB-Links) — baut auf v2.0's Personal-Lists-Foundation auf
- [ ] SET-06-EXT: CSV-Import mit zusätzlichen Spalten über Titel/OriginalTitel/Jahr hinaus — der bestehende 3-Spalten-Umfang wurde in Phase 15 geliefert (siehe Validated); nur eine Erweiterung des Spaltenumfangs bleibt v3-Kandidat
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

**Current State (v1.1 — shipped 2026-08-29):**
- 16 phases complete across v1.0 + v1.1, 56 plans shipped total
- v1.1 alone: 314 commits, 222 files changed, +33,464/−186 lines, over 8 days (2026-08-22 → 2026-08-29)
- All v1.0 (101 Jira tickets) and v1.1 (15/15 REQUIREMENTS.md items) requirements closed
- Stack unchanged: Spring Boot 3 + Java 25 / Nuxt 3 + Vue 3 + TypeScript + TailwindCSS + shadcn-vue / PostgreSQL 16 / OpenSearch 2.x / Caddy / Docker Compose
- Testing: JUnit 5 + Testcontainers (BE), Vitest + Vue Test Utils + MSW (FE), Playwright E2E
- External APIs: TMDB (required), OMDB (optional), Wikipedia (Wikidata-first SPARQL batch lookup as of Phase 13, always tried)
- Known tech debt (not blocking): Phase 15's pre-existing unused-icon ESLint warning and a full-suite cross-class test-isolation flakiness in `./gradlew check` (both documented, out of scope, tracked in STATE.md Deferred Items)

**v1.1 trigger (2026-08-22):** Bulk-imported ~630 Filme via externes Script gegen die bestehenden `/movies/search` + `/movies/save` Endpoints. 462 gespeichert, aber nur 58 von 526 gespeicherten Filmen (~11%) haben Wikipedia-Daten — OMDB (523/526) unauffällig. Root cause: `enrichmentExecutor` (2 core/5 max Threads, `backend/.../config/AsyncConfig.java`) verarbeitete den Burst mit vielen sequenziellen Wikipedia-Calls pro Film; `WikipediaClient` User-Agent `"MovieArchive/0.1"` vermutlich rate-limited/blockiert. `EnrichmentService.enrich()` schluckt Wikipedia-Fehler bewusst still (D-15) — kein Retry, kein Fehlerstatus. `/movies/save` ist idempotent, re-triggert also kein Re-Enrichment für bereits gespeicherte Filme; `ReindexController` re-synct nur Postgres→OpenSearch, ruft nie externe APIs erneut auf. Live verifiziert: Wikipedia-Lookup-Logik selbst funktioniert (Testabruf für "Whiplash" fand sofort Treffer).

**v1.1 resolution (Phasen 12–13, abgeschlossen 2026-08-27):** Wikipedia-Lookup läuft jetzt Wikidata-first über eine gebatchte SPARQL-Query (`query.wikidata.org/sparql`, bis zu 50 IMDb-IDs/Request) statt der REST-Suche, die Wikidata's anonymen Rate-Limiter nach 2-3 Filmen traf. `WikiReloadService.batchReload()` und `BulkImportService.runImport()` prefetchen die gesamte Wikidata-Auflösung einmal pro Lauf (nicht mehr einmal pro Film) — die one-movie-at-a-time-Exposure, die das ursprüngliche ~630-Film-Incident verursachte, existiert nicht mehr.

**v1.1 Phase-14-Nachlese (abgeschlossen 2026-08-28):** Live-UAT gegen echte Daten (368-382 eligible Filme) fand fünf reale Bugs, die gemockte Tests nicht reproduzieren konnten: der Stop-Button erschien nicht während der Wikidata-Prefetch-Phase; der SPARQL-Prefetch für den gesamten eligible-Satz feuerte alle Chunks in einem Rate-Limit-tripenden Burst statt verteilt mit der Movie-Verarbeitung zu interleaven; die ETA-Berechnung war massiv zu niedrig (fehlendes Pacing-Delay in der Dauer-Messung); der Stop-Button gab kein Feedback während der echten Wartezeit; und ein `disabled`-Prop-Typfehler wurde vom Frontend-Typecheck live während des Tests gefangen. Alle fünf wurden noch in dieser Session gefixt. Zusätzlich: Live-A/B-Test von `wiki.retry.pacing-delay-ms` (20s vs. 15s) auf echten Daten zeigte 20s als sauberen Wert (nur 1 harmloser 1s-Backoff über 7 Filme) gegenüber 15s (häufige 9-23s-Backoffs) — neuer Default ist 20s (war 30s).

**v1.1 Phase 15 — Milestone-Abschluss (abgeschlossen 2026-08-28):** Schloss die zwei letzten losen Bulk-Import-Todos (View-Toggle/Movie-Links/Inline-Resolve, echtes CSV-Parsing) ab, die aus Phase 10/11 offen geblieben waren, und zog SET-06 (CSV-Import) sowie das Kernanliegen von IMPORT-V2-01 (manuelle Auflösung mehrdeutiger Treffer) auf Nutzerwunsch aus dem v2-Backlog vor, damit v1.1 mit fertigem Import-Feature schließt statt teilweise. Live-UAT gegen echte Browser-Sessions fand 3 reale Bugs, die die gemockten Vitest-Suiten nicht zeigen konnten: ein Nuxt-3-Compiler-Limit (`resolveDynamicComponent` registriert `NuxtLink` nur bei literalem `<NuxtLink>`-Tag, nicht bei String-Binding über `:is` — der bestehende `global.stubs`-Testdouble maskierte genau diese Lücke), das Resolve-Widget war ins schmale Grid-Zellen-Layout gequetscht statt volle Breite zu nutzen, und die neu ergänzten Kandidaten-Labels (Titel+Jahr) wurden bei langen Titeln per CSS-`truncate` abgeschnitten statt umzubrechen. Alle drei in derselben Session über drei kleine Gap-Closure-Pläne (15-04/05/06) gefixt und erneut live bestätigt. Außerdem im Rahmen des Milestones per Quick-Task (260828-qh2) behoben: Bulk-Import übersprang bislang standardmäßig den Wikipedia-Schritt (nur noch TMDB+OMDB beim Import; `WikiReloadService.batchReload()` holt Wiki-Daten paced im Nachgang nach), da ein 1000-Film-Import sonst wegen Wikipedia-Pacing einen ganzen Tag gedauert hätte. Offen und bewusst nicht in v1.1 gefixt: ein vorbestehender, aus Phase 10 stammender Cross-Batch-Zeilen-Reassignment-Bug in `BulkImportService.findExistingRow()` (dedupliziert nur nach User+Titel+Jahr, nicht nach `batchId`) — als eigenständiger Bugfix-Todo festgehalten, betrifft keinen v1.1-Requirement.

**v1.1 Phase 16 — endgültiger Milestone-Abschluss (abgeschlossen 2026-08-29):** Milestone wurde ein letztes Mal reaktiviert, um drei Punkte vor dem endgültigen Schließen von v1.1 einzuarbeiten: (1) den vorbestehenden Cross-Batch-Dedup-Bug aus Phase 10 (CR-01), (2) die aus Phase 14 zurückgestellte Unterscheidung "gestoppt" vs. "vollständig abgeschlossen" in der Wiki-Reload-Progress-UI (WR-02), und (3) einen neu entschiedenen mehrstufigen automatischen TMDB-Match-Algorithmus. Live-UAT gegen echte Daten fand danach zwei weitere reale Bugs: die Per-Movie-History duplizierte den zuletzt verarbeiteten Filmtitel nach einem Stop/Abschluss (G-16-2 — das terminale SSE-Event echot bewusst den letzten `progress`-Eintrag, ohne dass das Frontend das erkannte), und ein Film mit bereits gefundenem, aber nicht vollständig extrahiertem Wikipedia-Artikel wurde für immer erneut versucht statt als erledigt zu gelten (G-16-3 — betraf 41/305 Filme im Live-Datensatz). Beide noch in derselben Session über zwei Gap-Closure-Pläne (16-03/04) gefixt, in parallelen Git-Worktrees ausgeführt, gemerged und live erneut bestätigt. Security-Threat-Review (16-SECURITY.md) fand keine offenen Bedrohungen (7/7 STRIDE-Threats mitigiert oder akzeptiert).

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
| Cooldown-Zeitstempel statt permanentem "not found"-Flag für Wiki-Lookup | Wikipedia-Seiten entstehen über Zeit neu; permanenter Skip würde das dauerhaft verpassen | ✓ Good — Phase 8 |
| Bulk-Import strikt `Title;OriginalTitle;Year` (Original Title optional leer) | Einfacher, testbarer Parser ohne echtes CSV-Quoting; UAT deckte auf, dass das Format ohne UI-Hinweis nicht selbsterklärend war | ✓ Good — Phase 10: Format-Hinweis + Pre-Flight-400; Phase 15: komma-getrenntes CSV (RFC4180-Quoting) als zweites Format ergänzt, Semikolon-Parser unverändert |
| Wikidata SPARQL statt REST (CirrusSearch + Sitelinks) für Wikipedia-Lookup | REST-Suche traf Wikidata's anonymen Rate-Limiter nach 2-3 Filmen unabhängig vom Pacing (absolutes per-minute Quota, kein Spacing-Problem); SPARQL VALUES-Klausel löst bis zu 50 IMDb-IDs pro Request auf | ✓ Good — Phase 13, live gegen query.wikidata.org verifiziert |
| Chunking-Logik lebt in `WikipediaClient.resolveViaWikidataSparql` statt bei jedem Caller | `WikiReloadService`/`BulkImportService` können beliebig große IMDb-ID-Listen übergeben, ohne die Chunk-Size-Konstante zu kennen — hält das "one client, one method"-Pattern intakt | ✓ Good — Phase 13 |
| Wikidata-Prefetch chunk-weise interleaved mit Movie-Verarbeitung statt gesamten eligible-Satz upfront aufzulösen | Upfront-Auflösung feuerte alle SPARQL-Chunk-Requests (z.B. 8 für 382 Filme) innerhalb von Sekunden hintereinander und trat Wikidata's Rate-Limiter fast sofort — live in UAT gefunden | ✓ Good — Phase 14, live verifiziert |
| ETA-Berechnung inkludiert `pacingDelayMs`, nicht nur die reine Fetch-Call-Dauer | Ohne Pacing-Delay war die ETA massiv zu niedrig (z.B. 40min statt real ~190min+ bei 8/380 verarbeiteten Filmen) — der Pacing-Sleep dominiert die reale Pro-Film-Kadenz unter Normalbedingungen | ✓ Good — Phase 14, live gefunden und verifiziert |
| Stop-Button bleibt in "Stopping..."-Zustand bis das echte Complete-SSE-Event ankommt, nicht nur bis der POST-Request zurückkehrt | POST löst in Millisekunden auf, der echte Halt kann aber bis zu `pacingDelayMs` länger dauern — ohne diesen Fix fühlte sich ein Stop-Klick an, als würde nichts passieren | ✓ Good — Phase 14, User-Feedback live in UAT |
| `wiki.retry.pacing-delay-ms` Default 30s → 20s | Live-A/B-Test auf echten Daten: 20s zeigte nur 1 harmlosen 1s-Backoff über 7 Filme, 15s zeigte häufige 9-23s-Backoffs — 20s ist der bessere Kompromiss aus Durchsatz und Rate-Limit-Sicherheit | ✓ Good — Phase 14, live A/B-getestet |
| `NuxtLink` via `resolveComponent('NuxtLink')` statt `:is="'NuxtLink'"`-String-Ternary | Nuxt 3 registriert eingebaute Komponenten nur bei literalem Tag im Template-AST einer Datei — ein String innerhalb einer Ternary wird nie erkannt (dokumentiertes Nuxt-3-Limit); resultierte in einem inerten `<nuxtlink>`-Element ohne Navigation, das der bestehende `global.stubs`-Testdouble maskierte | ✓ Good — Phase 15, live in UAT gefunden |
| Bulk-Import überspringt Wikipedia standardmäßig, nur noch TMDB+OMDB beim initialen Import | Wikipedia-Pacing (Rate-Limit-Schutz aus Phase 12-14) hätte einen 1000-Film-Import auf bis zu einen Tag verlangsamt; `WikiReloadService.batchReload()` (bereits vorhanden) holt fehlende Wiki-Daten paced im Nachgang nach — kein neuer Mechanismus nötig | ✓ Good — Quick-Task 260828-qh2 |
| Batch-scoped Repository-Queries erfordern IMMER `userId` UND `batchId` (nie `batchId` allein) | Mirrort das bestehende `findByIdAndBatchId`/`loadOwnedBatch()` Defense-in-Depth-Pattern; verhindert Cross-Batch-Zeilen-Reassignment (CR-01) | ✓ Good — Phase 16 |
| Terminales SSE-`complete`-Event darf den letzten `progress`-Eintrag nicht erneut in die History pushen | `WikiReloadProgressService.complete()` echot bewusst `prior.lastMovieTitle()`/`lastMovieStatus()` für die Statusanzeige — der Frontend-History-Push braucht einen expliziten `!p.complete`-Guard, sonst erscheint der letzte Film zweimal | ✓ Good — Phase 16, live in UAT gefunden |
| Wiki-Reload-Eligibility schlüsselt an `wiki_url IS NULL`, nicht an `wiki_plot`/`wiki_critics IS NULL` | Ein gefundener Artikel ohne extrahierbaren Plot/Critics-Abschnitt (z.B. abweichende Section-Namen) wurde vorher für immer erneut versucht — verschwendet gepacetes Wikipedia-Request-Budget auf bereits gefundene Seiten | ✓ Good — Phase 16, live in UAT gefunden (41/305 betroffene Filme) |

## Design System

**Global UI-SPEC:** `.planning/UI-SPEC.md` — shared app-level design contract (approved, Phase 1). All phases with frontend work use this file.

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
*Last updated: 2026-08-29 after starting milestone v2.0*
