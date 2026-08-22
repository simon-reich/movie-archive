# Requirements: MovieArchive v1.1

**Defined:** 2026-08-22
**Core Value:** Archivieren und finden — ein Film muss sich in Sekunden speichern und genauso schnell wiederfinden lassen. Beides zusammen macht den Wert, keines davon allein reicht.

## v1.1 Requirements

Requirements for the v1.1 milestone. Each maps to roadmap phases.

### Enrichment Retry

- [x] **ENRICH-01**: System speichert `wiki_last_attempted_at` Zeitstempel pro Film bei jedem Wikipedia-Enrichment-Versuch (Erfolg oder Fehlschlag)
- [x] **ENRICH-02**: Batch-Reload-Endpoint findet alle Filme eines Users ohne Wikipedia-Daten, deren letzter Versuch außerhalb des Cooldown-Fensters liegt (oder nie versucht wurde)
- [x] **ENRICH-03**: Batch-Reload verarbeitet gefundene Filme gepaced (Delay zwischen Wikipedia-Calls), um erneutes Rate-Limiting zu vermeiden
- [ ] **ENRICH-04**: User kann auf der Film-Detailseite eines Films ohne Wikipedia-Daten einen Retry-Button klicken, der einen einzelnen Enrichment-Versuch auslöst
- [ ] **ENRICH-05**: Retry-Button zeigt Ergebnis an (Erfolg: Wiki-Daten erscheinen; Fehlschlag: Hinweis, `wiki_last_attempted_at` aktualisiert)

### Bulk Import

- [ ] **IMPORT-01**: User kann im Bereich "Add Film" eine Textdatei (Titel + optionaler Originaltitel in Klammern + Jahr, eine Zeile pro Film) hochladen
- [ ] **IMPORT-02**: System parsed jede Zeile, sucht via TMDB (bestehende `/movies/search`-Logik) nach Titel, filtert nach Jahr
- [ ] **IMPORT-03**: Eindeutiger Treffer wird automatisch gespeichert (bestehende `/movies/save`-Logik inkl. Dedup-Idempotenz)
- [ ] **IMPORT-04**: Mehrdeutige Treffer (mehrere Kandidaten mit passendem Jahr) werden nicht automatisch geraten, sondern zur manuellen Auswahl markiert
- [ ] **IMPORT-05**: User sieht während des laufenden Imports einen Live-Fortschritt (verarbeitet / gesamt)
- [ ] **IMPORT-06**: Nach Abschluss zeigt eine Ergebnisübersicht pro Zeile: Titel, Poster (falls gefunden), Status (gespeichert / mehrdeutig / nicht gefunden / Parse-Fehler)
- [ ] **IMPORT-07**: Erneutes Hochladen derselben Liste überspringt bereits gespeicherte Filme (keine Duplikate, keine erneuten TMDB-Calls für bereits erledigte Zeilen)

## Future Requirements

Deferred to a later milestone. Tracked but not in current roadmap.

### Bulk Import

- **IMPORT-V2-01**: Manuelle Auflösung mehrdeutiger Treffer direkt in der Ergebnisübersicht (Kandidat auswählen statt externer Override-Datei)

## Out of Scope

Explicitly excluded from v1.1. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Automatisches Best-Match-Raten bei mehrdeutigen Treffern | User hat sich explizit für "immer manuell prüfen" entschieden — Risiko falscher Filme im Index |
| Permanentes "endgültig nicht gefunden"-Flag für Wikipedia | Wikipedia-Seiten entstehen über Zeit neu; Cooldown-Zeitstempel statt Dauerhaft-Flag gewählt |
| Batch-Reload/Retry für OMDB | OMDB-Enrichment ist nicht betroffen (523/526 Filme haben Daten) — nur Wikipedia zeigt das Problem |
| CSV-Import (strukturiertes Format mit mehr Feldern) | Bestehende Filmliste ist reines Titel+Jahr-Textformat; CSV-Import bleibt v2-Kandidat (SET-06) |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| ENRICH-01 | Phase 8 | Complete |
| ENRICH-02 | Phase 8 | Complete |
| ENRICH-03 | Phase 8 | Complete |
| ENRICH-04 | Phase 9 | Pending |
| ENRICH-05 | Phase 9 | Pending |
| IMPORT-01 | Phase 10 | Pending |
| IMPORT-02 | Phase 10 | Pending |
| IMPORT-03 | Phase 10 | Pending |
| IMPORT-04 | Phase 10 | Pending |
| IMPORT-05 | Phase 11 | Pending |
| IMPORT-06 | Phase 11 | Pending |
| IMPORT-07 | Phase 10 | Pending |

**Coverage:**

- v1.1 requirements: 12 total
- Mapped to phases: 12/12 ✓
- Unmapped: 0

---
*Requirements defined: 2026-08-22*
*Last updated: 2026-08-22 after ROADMAP.md creation (Phases 8–11)*
