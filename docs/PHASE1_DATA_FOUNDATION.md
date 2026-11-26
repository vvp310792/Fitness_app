# Phase 1 — normalized fitness data foundation

This branch introduces a pure Kotlin contract for source-aware observations, conservative activity reconciliation, daily value selection, and the agreed 28-day reference (at least 14 measured complete days).

It does not yet consume Firestore server_records or change Room tables or existing UI. Existing source-specific readers and the PC pipeline stay untouched until the real snapshot and transport contract have been audited. All examples and tests are synthetic; no personal records or credentials are included.

Provisional matching tolerances: start within 3 minutes; overlap >=80% of the longer elapsed interval; duration difference <=max(120 seconds, 12%); endurance distance difference <=max(100 meters, 5%). Missing or conflicting evidence remains ambiguous; only explicit verified origin IDs may override missing duration/distance. Same-source records remain separate. A connected chain is not merged unless all pairwise matches are confident and sources unique. These thresholds require calibration on the actual archive before activation. A primary session is the anchor, not a merged payload: use observations for source-specific fields (e.g. GymUp sets, Garmin heart rate). Do not count ambiguous candidates as confirmed independent activity in analytics without expressing uncertainty.

Next: verified server snapshot and source adapters; source-aware field aggregation and a shadow Room reader with transactional validation and offline fallback; migrate screen repositories; then UI and emulator tests. No production activation or APK release until live consistency checks and regression tests pass.
