# Requirements: MovieArchive v1.1

**Defined:** 2026-08-22
**Core Value:** Archivieren und finden — ein Film muss sich in Sekunden speichern und genauso schnell wiederfinden lassen. Beides zusammen macht den Wert, keines davon allein reicht.

## v1.1 Requirements

Requirements for the v1.1 milestone. Each maps to roadmap phases.

### Enrichment Retry

- [x] **ENRICH-01**: System speichert `wiki_last_attempted_at` Zeitstempel pro Film bei jedem Wikipedia-Enrichment-Versuch (Erfolg oder Fehlschlag)
- [x] **ENRICH-02**: Batch-Reload-Endpoint findet alle Filme eines Users ohne Wikipedia-Daten, deren letzter Versuch außerhalb des Cooldown-Fensters liegt (oder nie versucht wurde)
- [x] **ENRICH-03**: Batch-Reload verarbeitet gefundene Filme gepaced (Delay zwischen Wikipedia-Calls), um erneutes Rate-Limiting zu vermeiden
- [x] **ENRICH-04**: User kann auf der Film-Detailseite eines Films ohne Wikipedia-Daten einen Retry-Button klicken, der einen einzelnen Enrichment-Versuch auslöst
- [x] **ENRICH-05**: Retry-Button zeigt Ergebnis an (Erfolg: Wiki-Daten erscheinen; Fehlschlag: Hinweis, `wiki_last_attempted_at` aktualisiert)

### Bulk Import

- [x] **IMPORT-01**: User kann im Bereich "Add Film" eine Textdatei (Titel + optionaler Originaltitel in Klammern + Jahr, eine Zeile pro Film) hochladen
- [x] **IMPORT-02**: System parsed jede Zeile, sucht via TMDB (bestehende `/movies/search`-Logik) nach Titel, filtert nach Jahr
- [x] **IMPORT-03**: Eindeutiger Treffer wird automatisch gespeichert (bestehende `/movies/save`-Logik inkl. Dedup-Idempotenz)
- [x] **IMPORT-04**: Mehrdeutige Treffer (mehrere Kandidaten mit passendem Jahr) werden nicht automatisch geraten, sondern zur manuellen Auswahl markiert
- [x] **IMPORT-05**: User sieht während des laufenden Imports einen Live-Fortschritt (verarbeitet / gesamt)
- [x] **IMPORT-06**: Nach Abschluss zeigt eine Ergebnisübersicht pro Zeile: Titel, Poster (falls gefunden), Status (gespeichert / mehrdeutig / nicht gefunden / Parse-Fehler)
- [x] **IMPORT-07**: Erneutes Hochladen derselben Liste überspringt bereits gespeicherte Filme (keine Duplikate, keine erneuten TMDB-Calls für bereits erledigte Zeilen)
- [x] **IMPORT-08**: Batch-Detail-Seite bietet einen Grid/List-View-Toggle (localStorage-persistiert), SAVED-Zeilen sind vollständig klickbar zur Film-Detailseite, PARSE_ERROR-Zeilen zeigen den vollständigen, unabgeschnittenen Rohstring als eigene Zeile — vier nach Status gruppierte Sektionen (Saved → Ambiguous → Not found → Parse error)
- [x] **IMPORT-09** (vorgezogen aus IMPORT-V2-01): Manuelle Auflösung mehrdeutiger/nicht gefundener Treffer direkt in der Ergebnisübersicht — Kandidat per frischem TMDB-Search auswählen statt externer Override-Datei
- [x] **IMPORT-10** (vorgezogen aus SET-06, Spaltenumfang wie bestehendes Format): Echtes komma-getrenntes CSV-Parsing (RFC4180-Quoting) als zweites unterstütztes Import-Format für Titel/OriginalTitel/Jahr, mit automatischem Header-Zeilen-Überspringen; Semikolon-Format bleibt unverändert

## Future Requirements

Deferred to a later milestone. Tracked but not in current roadmap.

### Bulk Import

- **SET-06-EXT**: CSV-Import mit zusätzlichen Spalten über Titel/OriginalTitel/Jahr hinaus (IMPORT-10 lieferte den bestehenden 3-Spalten-Umfang in v1.1; nur eine Spalten-Erweiterung bleibt offen)

## Out of Scope

Explicitly excluded from v1.1. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Automatisches Best-Match-Raten bei mehrdeutigen Treffern | User hat sich explizit für "immer manuell prüfen" entschieden — Risiko falscher Filme im Index |
| Permanentes "endgültig nicht gefunden"-Flag für Wikipedia | Wikipedia-Seiten entstehen über Zeit neu; Cooldown-Zeitstempel statt Dauerhaft-Flag gewählt |
| Batch-Reload/Retry für OMDB | OMDB-Enrichment ist nicht betroffen (523/526 Filme haben Daten) — nur Wikipedia zeigt das Problem |
| CSV-Export aller Filmdaten (SET-05) | Kein Export-Feature in v1.1; nur Import wurde vorgezogen (IMPORT-10) |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| ENRICH-01 | Phase 8 | Complete |
| ENRICH-02 | Phase 8 | Complete |
| ENRICH-03 | Phase 8 | Complete |
| ENRICH-04 | Phase 9 | Complete |
| ENRICH-05 | Phase 9 | Complete |
| IMPORT-01 | Phase 10 | Complete |
| IMPORT-02 | Phase 10 | Complete |
| IMPORT-03 | Phase 10 | Complete |
| IMPORT-04 | Phase 10 | Complete |
| IMPORT-05 | Phase 11 | Complete |
| IMPORT-06 | Phase 11 | Complete |
| IMPORT-07 | Phase 10 | Complete |
| IMPORT-08 | Phase 15 | Complete |
| IMPORT-09 | Phase 15 | Complete |
| IMPORT-10 | Phase 15 | Complete |

**Coverage:**

- v1.1 requirements: 15 total
- Mapped to phases: 15/15 ✓
- Unmapped: 0

---
*Requirements defined: 2026-08-22*
*Last updated: 2026-08-28 after Phase 15 (v1.1 milestone complete)*
