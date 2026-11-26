# PC publisher v1 integration

The Windows publisher is maintained on the owner's PC; do not copy it (or records, credentials or personal paths) into this repository. The existing publisher's content-addressed parts and manifest format already match the v1 reader. Use the generic helper in tools/server_publication_v1.py to verify the full remote catalog and every referenced part, not just changed writes.

Integration into the existing publish(c):
1. Capture sqlite_version = c.execute("PRAGMA data_version").fetchone()[0] before scanning records.
2. Keep existing writes of immutable parts and manifests, and their readback.
3. After flush() of the manifests, replace the v0 status write with:
   from server_publication_v1 import confirm_and_publish
   confirm_and_publish(c, db, u, manifest_jobs, sqlite_version, firestore.SERVER_TIMESTAMP)
4. Keep the scheduler's existing exclusive lock for the full run. No concurrent manual publishers; use the same lock for an initial verification/activation.
5. Treat an exception as publication failure. Old verified client generation stays active. Unexpected/missing/stale manifest groups need investigation; never delete them automatically. If an old publisher still writes v0, the next run downgrades the status and v1 readers will refuse that publication. Stage and test the helper and the local patch before replacing any scheduled script; keep an exact backup and compare hashes.
6. Verify actual v1 status and counts after publication, test the manual shadow import with the owner's authenticated device, then map fields and compare the five screens. The verifier's readback does not prove that the user's account contains every historical source or that the observations are already deduplicated.

The verification reads all referenced parts on each v1 commit (20 at a time; bounded decompression). Measure Firestore read usage before increasing sync frequency. Fail closed on SQLite version changes, a changed status or catalog, wrong month, non-finite JSON, duplicate native identities and missing parts. Do not change production access rules in this draft.
