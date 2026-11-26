# Phase 2 — verified server read-through (2026-11-26)

This change reads only the active verified v1 generation, scoped to the signed-in Firebase owner. It does not write snapshot rows into AppDatabase, replace legacy tables, upload imported rows to Firestore, delete historical observations or alter the production server publisher.

- Workouts: a single SQL query loads the Garmin activity, GymUp training and Google Health exercise rows from the active generation. Convert explicit units and UTC instants; preserve elapsed vs active/timer duration. Reuse GymUpBackupParser and its continuous numbering rules. Empty plans are not sessions. Exact source/native-ID matches enrich local records without duplication; contradictory IDs are flagged. Across-source uncertain matches stay separate and are excluded from confirmed totals.
- Day / Week / Trends: verified Garmin daily summaries, sleep nights and activities fill missing local history. Existing local measurements/details remain preferred on exact overlapping IDs/days; entire sleep nights are selected, not stage sums. Trends also receives GymUp sets for new session starts; existing local sessions are preserved whole.
- Settings: imports are still explicit through the existing verified snapshot panel. After an active generation is available, supported views read it offline. No automatic periodic refresh yet.

Read-only aggregate schema audit of the live local database: Garmin activities 1533/1533 have valid ID/time and elapsed duration; daily 1488/1488 use the nested schema; sleep summary 567 valid, sleep detail 57 valid and 9 without valid duration; GymUp 610 raw training rows, 598 performed sessions, 12 empty plans, 3894 usable performed sets; Google Health 83/83 valid exercise intervals, 57 explicit distanceMillimeters, 83 activeDuration strings in seconds. These are source-schema counts, not a claim of a successful Android import or number of unique episodes. No personal payloads are included in tests or Git.

Remaining gates:
1. Run JVM and Android emulator CI; test first load, account changes, offline, cancelled/rejected import, and known overlap.
2. Import on the user's phone and compare displayed coverage against the verified v1 publication; do not claim an active phone copy before it has been observed.
3. Normalize the remaining sources/metrics (Google Health interval steps/energy/distance and pulse, weight, HRV, advanced Garmin, etc.) with coverage/provenance; do not sum transport copies.
4. Align canonical confirmed workout totals across Day/Week/Trends (the existing Health Connect workout summary is still local and independent); preserve uncertain episodes.
5. Add persistent/incremental refresh only after verified import works end-to-end. Review owner-only server collection rules separately.
