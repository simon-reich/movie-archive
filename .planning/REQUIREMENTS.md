# Requirements: MovieArchive

**Defined:** 2026-05-15
**Core Value:** Archivieren und finden — ein Film muss sich in Sekunden speichern und genauso schnell wiederfinden lassen. Beides zusammen macht den Wert.

## v1 Requirements

### Authentication

- [x] **AUTH-01**: User kann Account mit E-Mail + Passwort erstellen
- [x] **AUTH-02**: User erhält Verifizierungs-E-Mail nach Sign-Up; Account wird erst nach Klick aktiviert
- [x] **AUTH-03**: User kann sich mit E-Mail + Passwort einloggen (nur ACTIVE-Accounts)
- [x] **AUTH-04**: User erhält JWT Access Token (15 min) + Refresh Token als HttpOnly-Cookie (7 Tage)
- [x] **AUTH-05**: Refresh Token wird bei /auth/refresh rotiert (altes revoked, neues ausgestellt)
- [x] **AUTH-06**: User kann sich ausloggen (Refresh Token wird revoked)
- [x] **AUTH-07**: User kann Passwort-Reset per E-Mail anfordern (immer 200 OK — Enumeration-Schutz)
- [x] **AUTH-08**: User kann Passwort mit Token zurücksetzen; alle Refresh Tokens des Accounts werden revoked

### Settings

- [ ] **SET-01**: User kann TMDB API-Key speichern und ändern (AES-256-GCM verschlüsselt, Anzeige immer maskiert)
- [ ] **SET-02**: User kann OMDB API-Key speichern und ändern (optional, gleiche Verschlüsselung)
- [ ] **SET-03**: User kann Passwort ändern (erfordert aktuelles Passwort; revoked alle Refresh Tokens)
- [ ] **SET-04**: User kann E-Mail-Adresse ändern (Token-Link an neue Adresse; alte Adresse wird informiert)
- [ ] **SET-05**: User kann alle Filmdaten als CSV exportieren
- [ ] **SET-06**: User kann Filmdaten aus einer CSV-Datei importieren

### Save Movie Flow

- [ ] **SAVE-01**: User kann Film via TMDB-Suche oder direkt per TMDB-ID zur Sammlung hinzufügen → sofort 202 Accepted
- [ ] **SAVE-02**: Backend reichert Film async an: TMDB → OMDB (optional) → Wikipedia → Postgres → OpenSearch
- [ ] **SAVE-03**: OMDB-Anreicherung schlägt graceful fehl wenn kein Key konfiguriert oder kein imdb_id in TMDB-Antwort
- [ ] **SAVE-04**: Wikipedia-Anreicherung schlägt graceful fehl (6-Step-Fallback); Film wird immer gespeichert
- [ ] **SAVE-05**: UI zeigt Save-Status sichtbar an (pending → success / error) — kein silent failure

### OpenSearch Indexing

- [ ] **IDX-01**: Film-Daten werden nach Postgres-Persist in OpenSearch-Index `movies-{userId}` indexiert
- [ ] **IDX-02**: Index wird bei erstem Film eines Users automatisch mit Custom Analyzer und finalem Mapping erstellt
- [ ] **IDX-03**: Custom Analyzer: asciifolding, lowercase, elision, stop_english, kstem
- [ ] **IDX-04**: Admin-Endpoint zum Neuaufbau des Index eines Users (`POST /admin/reindex/{userId}`)

### Search

- [ ] **SRCH-01**: User kann Freitext-Suche über alle indizierten Film-Felder durchführen
- [ ] **SRCH-02**: User kann Advanced Filters kombinieren: Genre, Regisseur, Jahr, IMDB-Rating, Content-Rating, Watched
- [ ] **SRCH-03**: User kann Suchergebnisse sortieren (Titel A–Z, Jahr, persönliches Rating, IMDB-Rating)
- [ ] **SRCH-04**: Klick auf Namen / Attribut (Schauspieler, Regisseur, Genre etc.) öffnet vorgefilterte Suche

### Movie Detail

- [ ] **DETAIL-01**: Film-Detailseite zeigt alle TMDB + OMDB Felder (nullable OMDB-Felder werden ausgeblendet)
- [ ] **DETAIL-02**: Film-Detailseite zeigt Wikipedia Plot und Critics-Section (falls vorhanden)
- [ ] **DETAIL-03**: User kann persönliche Felder setzen: Watched-Status, Rating (0–10), Notizen (Freitext, indiziert)
- [ ] **DETAIL-04**: Film-Detailseite zeigt Trailer als YouTube-Embed (via TMDB trailer_key, falls vorhanden)
- [ ] **DETAIL-05**: Klick auf Schauspieler / Regisseur / Genre etc. öffnet gefilterte Suchergebnisliste

### Polish & Quality

- [ ] **QLTY-01**: App ist responsive und auf Mobilgeräten vollständig nutzbar
- [ ] **QLTY-02**: E2E-Tests mit Playwright (Happy Paths: Sign-Up → Film speichern → suchen → Detail)
- [ ] **QLTY-03**: README mit Setup-Anleitung und Feature-Übersicht

## v2 Requirements

### Enhanced Auth
- **AUTH-V2-01**: OAuth-Login (Google) — für v1 nicht benötigt
- **AUTH-V2-02**: 2FA — für v1 nicht benötigt

### Enhanced Features
- **FEAT-V2-01**: Letterboxd CSV-Import (Mapping auf TMDB-IDs) — nach v1 CSV-Export/Import
- **FEAT-V2-02**: Statistik-Dashboard (Genres, Jahrzehnte, Watched-Fortschritt)
- **FEAT-V2-03**: Index-Rebuild Admin-UI (statt nur API-Endpoint)

### Multi-User
- **MULTI-01**: Mehrere Nutzer (Registration für weitere Accounts öffnen)
- **MULTI-02**: Shared Watch-Lists

## Out of Scope

| Feature | Reason |
|---------|--------|
| Social Features / Public Profiles | Persönliche Sammlung — kein Social Network |
| Streaming-Plattform-Verfügbarkeit | Separates Problem, externe Datenpflege |
| Recommendation Engine | ML-Infrastruktur, separates Projekt |
| Offline-Modus / PWA | Online-App reicht für den Use Case |
| Real-time Collaboration | Kein Multiplayer-Editing geplant |
| Eigenes Film-Scraping | Immer via TMDB API |
| Auto-Sync mit TMDB nach Save | Snapshot-Strategie — eingefroren beim Speichern |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| AUTH-01 | Phase 1 | Complete |
| AUTH-02 | Phase 1 | Complete |
| AUTH-03 | Phase 1 | Complete |
| AUTH-04 | Phase 1 | Complete |
| AUTH-05 | Phase 1 | Complete |
| AUTH-06 | Phase 1 | Complete |
| AUTH-07 | Phase 1 | Complete |
| AUTH-08 | Phase 1 | Complete |
| SET-01 | Phase 2 | Pending |
| SET-02 | Phase 2 | Pending |
| SET-03 | Phase 2 | Pending |
| SET-04 | Phase 2 | Pending |
| SET-05 | Phase 2 | Pending |
| SET-06 | Phase 2 | Pending |
| SAVE-01 | Phase 3 | Pending |
| SAVE-02 | Phase 3 | Pending |
| SAVE-03 | Phase 3 | Pending |
| SAVE-04 | Phase 3 | Pending |
| SAVE-05 | Phase 3 | Pending |
| IDX-01 | Phase 4 | Pending |
| IDX-02 | Phase 4 | Pending |
| IDX-03 | Phase 4 | Pending |
| IDX-04 | Phase 4 | Pending |
| SRCH-01 | Phase 5 | Pending |
| SRCH-02 | Phase 5 | Pending |
| SRCH-03 | Phase 5 | Pending |
| SRCH-04 | Phase 5 | Pending |
| DETAIL-01 | Phase 6 | Pending |
| DETAIL-02 | Phase 6 | Pending |
| DETAIL-03 | Phase 6 | Pending |
| DETAIL-04 | Phase 6 | Pending |
| DETAIL-05 | Phase 6 | Pending |
| QLTY-01 | Phase 7 | Pending |
| QLTY-02 | Phase 7 | Pending |
| QLTY-03 | Phase 7 | Pending |

**Coverage:**
- v1 requirements: 35 total (31 feature + 3 quality + 1 note: AUTH-01..08=8, SET-01..06=6, SAVE-01..05=5, IDX-01..04=4, SRCH-01..04=4, DETAIL-01..05=5, QLTY-01..03=3 = 35)
- Mapped to phases: 35
- Unmapped: 0 ✓

---
*Requirements defined: 2026-05-15*
*Last updated: 2026-05-15 — traceability expanded to per-requirement rows after roadmap creation*
