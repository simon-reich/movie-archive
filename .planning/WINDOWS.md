---
schema_version: 1
open_count: 1
waived_count: 0
fixed_count: 0
total_count: 1
last_updated: 2026-08-28T12:17:17.252Z
---

# Broken Windows Ledger

> Cross-phase defect register. With `workflow.windows_enforce` enabled, `/gsd-ship` blocks while `open_count > 0`.
> Waive with `gsd-tools windows waive <id> "<reason>"` (reason required).
> Mark fixed with `gsd-tools windows fixed <id>`.

| id | phase | kind | file | line | description | status | reason | recorded_at | resolved_at |
|----|-------|------|------|------|-------------|--------|--------|-------------|-------------|
| 1 | 15 | deviation | backend/build.gradle.kts |  | Full-suite ./gradlew check has pre-existing cross-class test isolation flakiness (unrelated to 15-03's changes); see 15-bulk-import-page-completion-view-toggle-movie-links-real-csv/deferred-items.md | open |  | 2026-08-28T12:17:17.252Z |  |

````json
[
  {
    "id": 1,
    "kind": "deviation",
    "phase": "15",
    "file": "backend/build.gradle.kts",
    "line": null,
    "description": "Full-suite ./gradlew check has pre-existing cross-class test isolation flakiness (unrelated to 15-03's changes); see 15-bulk-import-page-completion-view-toggle-movie-links-real-csv/deferred-items.md",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-08-28T12:17:17.252Z",
    "resolved_at": null
  }
]
````
