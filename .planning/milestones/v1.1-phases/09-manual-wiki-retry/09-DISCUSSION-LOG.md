# Phase 9: Manual Wiki Retry - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-23
**Phase:** 9-manual-wiki-retry
**Areas discussed:** Cooldown vs manual retry, Concurrency with batch reload, Retry button & feedback UI, Batch-reload trigger button (folded)

---

## Fold todo check

**Question:** Confirm folding the pending "Add batch wiki-reload trigger button to UI" todo into Phase 9's discussion?

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, fold it in | Discuss both the per-film retry button AND the batch-reload trigger button in this session | ✓ |
| No, keep it separate | Only discuss the per-film retry button now | |

**User's choice:** Yes, fold it in.

---

## Cooldown vs manual retry

**Question:** Should the manual per-film Retry button bypass the 30-day cooldown, or respect it?

| Option | Description | Selected |
|--------|-------------|----------|
| Always allowed | Manual retry ignores cooldown entirely — deliberate one-off action | ✓ (with addition) |
| Respect cooldown | Button disabled/hidden if retried within 30 days | |

**User's choice:** Always allowed, with a caveat — wants some info shown that the last attempt found no data.
**Notes:** Follow-up question resolved this to a simple "No Wikipedia data found" message (no timestamp) shown alongside the Retry button — see below.

---

## Concurrency with batch reload

**Question:** How should the rare overlap between a manual retry and an in-flight batch-reload run be handled?

| Option | Description | Selected |
|--------|-------------|----------|
| Accept the overlap | No blocking logic; worst case one extra request | ✓ (with addition) |
| Block manual retry during batch | Check executor active state, return 503/disabled | |

**User's choice:** Accept the overlap, but wanted some indicator that a batch is currently running.
**Notes:** Follow-up question resolved this to: skip building a status indicator for now (deferred idea) — the overlap risk itself was judged low enough not to need it.

---

## Last-attempt UI (follow-up)

**Question:** How should the "no wiki data found" info show on the detail page?

| Option | Description | Selected |
|--------|-------------|----------|
| Timestamp + Retry button | Show last-checked date using existing wikiLastAttemptedAt | |
| Just "no data" + Retry, no timestamp | Simpler, no timestamp surfaced | ✓ |

**User's choice:** Just "no data" + Retry, no timestamp.

---

## Batch status indicator (follow-up)

**Question:** How should a "batch currently running" indicator be surfaced?

| Option | Description | Selected |
|--------|-------------|----------|
| New lightweight status check | GET /admin/wiki-reload/status + banner | |
| Skip for now | Defer as future nice-to-have | ✓ |

**User's choice:** Skip for now.

---

## Retry button & feedback UI

**Question 1 — Placement:** Where should the Retry button live when a film has no Wikipedia data?

| Option | Description | Selected |
|--------|-------------|----------|
| Replace hidden section | Show "No Wikipedia data found [Retry]" where the Wikipedia section would normally render | ✓ |
| Small inline badge near title/hero | Compact note separate from the Wikipedia content area | |

**Question 2 — Loading:** What should the button show while the synchronous retry request is in flight?

| Option | Description | Selected |
|--------|-------------|----------|
| Spinner + disabled button | Reuse SpinnerIcon.vue, same pattern as other async actions | ✓ |
| Just disable, no spinner | Text changes to "Retrying...", no icon | |

**Question 3 — Success/Fail:** How should success vs failure be communicated?

| Option | Description | Selected |
|--------|-------------|----------|
| Inline update, no toast | Page re-renders with new sections on success; message stays on failure | ✓ |
| Toast notification | Would require adding a new toast component | |

**User's choice:** All three recommended options selected as-is, no follow-up notes.

---

## Batch-reload trigger button (folded)

**Question 1 — UserId source:** How should the Settings page button get the logged-in user's id for POST /admin/wiki-reload/{userId}?

| Option | Description | Selected |
|--------|-------------|----------|
| Add a lightweight /users/me endpoint | New GET endpoint, reuses MovieDetailController's resolveUserId(auth) pattern | ✓ |
| Change endpoint to derive userId from JWT only | Modify the existing Phase 8 endpoint contract | |

**Question 2 — Feedback:** What should the Settings page show after clicking the button?

| Option | Description | Selected |
|--------|-------------|----------|
| Simple acknowledgement message | "Reload started..." on 202; "already in progress" on 503 | ✓ |
| Just disable the button briefly | No explanatory text | |

**User's choice:** Both recommended options selected.

---

## Claude's Discretion

- Exact response shape of the new per-film retry endpoint (full `MovieDetailResponse` vs. just changed wiki fields + flag).
- Exact wording/placement of an optional "retried and still not found" distinction.
- Whether the new per-film retry endpoint lives on `MovieDetailController` or a new controller (leaning `MovieDetailController` per its closer structural match).
- Exact naming/response shape of the new `GET /users/me` endpoint.
- Whether the Settings page's batch-reload button needs its own disabled/cooldown state (leaning: no, always clickable).

## Deferred Ideas

- Batch-reload running status indicator (`GET /admin/wiki-reload/status` + UI banner) — overlap risk accepted as low, not worth the added surface area now.
- Surfacing `wiki_last_attempted_at` timestamp in the "no wiki data" message — could resurface later if users want to know "when was this last checked."
