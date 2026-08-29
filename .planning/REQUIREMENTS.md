# Requirements: MovieArchive v2.0

**Defined:** 2026-08-29
**Core Value:** Archivieren und finden — ein Film muss sich in Sekunden speichern und genauso schnell wiederfinden lassen. Beides zusammen macht den Wert, keines davon allein reicht.

## v2.0 Requirements

### Bulk Import

- [ ] **IMPORT-11**: Batch-Historie List-View zeigt keine Poster mehr, sondern eine echte Tabellenzeile pro Film mit Spalten für Titel, Jahr, Status etc.
- [ ] **IMPORT-12**: Add-Film-Page zeigt einen Live-Progress-Balken für einen laufenden Bulk-Import direkt auf der Seite (nicht erst nach Navigation zur Detailseite), mit Link zur bestehenden Detailseite
- [ ] **IMPORT-13**: Bulk-Import-Detailseite zeigt Poster live "einploppend", sobald ein einzelner Film fertig verarbeitet ist (nicht erst nach komplettem Batch-Abschluss)

### Search

- [ ] **SRCH-05**: Language- und Production-Country-Filterwerte werden ausgeschrieben angezeigt (z.B. "German" statt "de", "United States" statt "US") statt als Code
- [ ] **SRCH-06**: Sortier-Richtung ist für jedes Sortierkriterium umkehrbar (z.B. Titel A→Z / Z→A) über einen Umkehr-Button
- [ ] **SRCH-07**: Search-Ergebnisse haben eine List-View ohne Poster — echte Tabellenzeile mit Spalten (gleiches Muster wie IMPORT-11)

### Detail Page

- [ ] **DETAIL-06**: Original Title wird auf der Detailseite angezeigt (Feld existiert bereits im Backend, war bisher nicht im Frontend verdrahtet)
- [ ] **DETAIL-07**: Klickbarer IMDB-Link direkt unter dem Titel (zusätzlich zum bestehenden Link neben dem Rating)
- [ ] **DETAIL-08**: Klickbarer Wikipedia-Link, sobald Wikipedia-Daten geladen sind (Feld `wikiUrl` existiert bereits im Backend)
- [ ] **DETAIL-09**: Wikipedia-Summary/Opener-Absatz wird angezeigt (wird bereits gefetcht und in `wiki_summary` gespeichert, war bisher nicht im Frontend verdrahtet)

### Rating UI

- [ ] **RATE-01**: Sterne-Rating wird durch ein Quadrat-Raster ersetzt (Kästchen ohne Zwischenraum, Karo-Optik)
- [ ] **RATE-02**: Die gewählte Zahl (1–10) erscheint als sichtbares Feedback im oder am ausgewählten Kästchen

### Wikipedia Enrichment

- [ ] **WIKI-01**: `WikipediaClient` wechselt für Summary/Plot/Critical-Reception von `prop=wikitext` (+ eigener Regex-Bereinigung) auf `prop=text` (echtes, vom MediaWiki-Parser gerendertes HTML) — bestehende Section-Resolution-Logik bleibt unverändert
- [ ] **WIKI-02**: Serverseitiges HTML-Sanitizing der Wikipedia-Inhalte vor Speicherung/Auslieferung (Allowlist p/a/b/i/em/strong/ul/li, relative `/wiki/...`-Links zu absoluten `en.wikipedia.org`-URLs umgeschrieben, keine `<script>`/Event-Handler)
- [ ] **WIKI-03**: Frontend rendert das sanitierte Wikipedia-HTML mit echter Absatzstruktur statt eines einzelnen Plaintext-Blocks

### Dashboard / Hub

- [ ] **HUB-01**: Batch-Wiki-Reload-Funktion wandert von Settings auf das Dashboard, dort als eigene Karte
- [ ] **HUB-02**: Batch-Wiki-Reload-Karte zeigt einen Live-Progress-Balken und verlinkt auf eine eigene Detailseite (analog zum Bulk-Import-Muster)
- [ ] **HUB-03**: Batch-Wiki-Reload-Karte zeigt einen "letzte 5 verarbeitete Filme"-Monitor
- [ ] **HUB-04**: Sprachen auf dem Dashboard werden ausgeschrieben und in einem Grid/Flexbox-Chip-Layout dargestellt (statt Liste untereinander)
- [ ] **HUB-05**: Genres auf dem Dashboard werden im gleichen Grid/Flexbox-Chip-Layout dargestellt

### Index Backup

- [ ] **SET-05**: CSV-Export des kompletten Index — alle Felder pro Film inkl. persönlicher Daten (Rating, Notizen, Watched-Status); unabhängig vom bestehenden Bulk-Import-Feature
- [ ] **SET-07**: CSV-Import/Restore des kompletten Index — direkter Restore ohne TMDB-Matching; bei Duplikaten (gleicher Film bereits im Index) werden leere Felder aus der importierten Zeile aufgefüllt (Merge), bestehende befüllte Felder bleiben unverändert

### Personal Lists (Foundation)

- [ ] **LIST-01**: Filme im Index markieren und zu einer Liste hinzufügen
- [ ] **LIST-02**: Neue Listen erstellen und benennen
- [ ] **LIST-03**: Eigene Listen speichern, anzeigen und verwalten (Filme entfernen, Liste umbenennen/löschen)

## Future Requirements

Deferred to a later milestone. Tracked but not in current roadmap.

### Personal Lists

- **PDF-01**: PDF-Export von Personal Lists — visuelle Broschüre, ein Film pro Seite, wahlweise Desktop/Mobile-Layout, mit Links zu Trailer/Wikipedia/IMDB (baut auf LIST-01–03 auf)

### Bulk Import

- **SET-06-EXT**: CSV-Import mit zusätzlichen Spalten über Titel/OriginalTitel/Jahr hinaus

### Other

- **AUTH-V2-01**: OAuth-Login (Google)
- **FEAT-V2-01**: Letterboxd CSV-Import
- **FEAT-V2-02**: Statistik-Dashboard (Genres, Jahrzehnte, Watched-Fortschritt)
- **MULTI-01**: Multi-User (Registration für weitere Accounts)

## Out of Scope

Explicitly excluded from v2.0. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| PDF-Export von Listen | Eigenständiges, aufwändiges Feature (Styling + PDF-Generierung) — v3-Kandidat, v2.0 legt nur die Foundation (Personal Lists) |
| Wiedernutzung der Bulk-Import-Matching-Pipeline für Index-Import | Index-Backup/-Restore (SET-07) ist bewusst unabhängig vom TMDB-Matching-basierten Bulk-Import — unterschiedlicher Zweck (Backup/Merge vs. TMDB-Discovery) |
| Zusätzliche Wikipedia-Sections (Cast, Production, Release, Awards) | Nicht angefragt — nur Summary/Plot/Critical-Reception bleiben im Scope; Erweiterung auf weitere Sections folgt bei Bedarf als eigener Task |
| CSV-Import mit zusätzlichen Spalten über Titel/OriginalTitel/Jahr hinaus (SET-06-EXT) | Bestehender 3-Spalten-Umfang reicht für Bulk-Import; Erweiterung bleibt v3-Kandidat |

## Traceability

Filled during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| IMPORT-11 | TBD | Pending |
| IMPORT-12 | TBD | Pending |
| IMPORT-13 | TBD | Pending |
| SRCH-05 | TBD | Pending |
| SRCH-06 | TBD | Pending |
| SRCH-07 | TBD | Pending |
| DETAIL-06 | TBD | Pending |
| DETAIL-07 | TBD | Pending |
| DETAIL-08 | TBD | Pending |
| DETAIL-09 | TBD | Pending |
| RATE-01 | TBD | Pending |
| RATE-02 | TBD | Pending |
| WIKI-01 | TBD | Pending |
| WIKI-02 | TBD | Pending |
| WIKI-03 | TBD | Pending |
| HUB-01 | TBD | Pending |
| HUB-02 | TBD | Pending |
| HUB-03 | TBD | Pending |
| HUB-04 | TBD | Pending |
| HUB-05 | TBD | Pending |
| SET-05 | TBD | Pending |
| SET-07 | TBD | Pending |
| LIST-01 | TBD | Pending |
| LIST-02 | TBD | Pending |
| LIST-03 | TBD | Pending |

**Coverage:**
- v2.0 requirements: 25 total
- Mapped to phases: 0 (roadmap not yet created)
- Unmapped: 25 ⚠️ (expected — filled by roadmapper next)

---
*Requirements defined: 2026-08-29*
*Last updated: 2026-08-29 after initial definition*
