# Automatic and incremental verified snapshot sync

The Android client checks the signed-in owner's v1 publication on activity start, on sign-in, and through unique periodic WorkManager work (one hour, network connected; actual time is managed by Android). The manual refresh button enqueues the same unique one-time work. A shared application coordinator serializes periodic/manual refreshes, and checks the current owner before any cloud read or activation. Sign-out cancels scheduled work. Temporary network and publication-change errors use bounded WorkManager retries; a rejected snapshot never replaces the active cache.

## Incremental import

1. Read and validate the v1 catalog status. If the verified local head has the same manifest-set SHA-256, counts, and local row count, acknowledge the current publication without fetching the catalog or any payload.
2. On changed content, read and validate the entire manifest catalog. Each immutable part ID includes the SHA-256 of its uncompressed JSON. Copy rows belonging to unchanged verified part IDs from the active generation into an isolated staging generation; download, hash-check, parse and save only unknown part IDs.
3. Check counts per group and total, recheck the v1 publication and current owner, then switch the active pointer in a Room transaction. A missing, conflicting or corrupted part, network interruption, or failed database transaction preserves the prior generation. Unused old parts are excluded from the new generation. Orphaned staging rows are pruned at the start of a serialized refresh.
4. No raw local fitness tables or Firestore source rows are changed. Acknowledging identical content can advance the publication timestamp, but preserves the time of the actual local import.

The non-destructive Room migration v1 -> v2 adds a partId column and index. Existing v1 records have no trustworthy part mapping. They remain readable offline, and identical content is still acknowledged without downloading it. The first *changed* publication after upgrading may require one full verified download to establish part identities; subsequent refreshes reuse unchanged parts. Local reuse is not an assertion that the remote copy of the same immutable part has been re-read.

## Workout reconciliation

Exact source/native-ID matches merge local and verified server observations only when time and sport agree. A contradictory ID is flagged and excluded from confirmed sums. Google Health records retain originating application identity: different known streams can match using the normal conservative time, sport, elapsed duration and distance tests. Near-identical intervals with distinct IDs in the same stream are marked as possible duplicates, not silently merged. A connected component is confirmed only when every pair is a confident match and every stream is distinct.

The workout archive exposes global exact-ID matches and additional confirmed merged copies separately from selected-period counts. Ambiguous observations remain visible and outside confirmed sums. This is not a complete truth set: historical sources and different boundaries of strength sessions can remain ambiguous. Raw records are never removed.

## Tests and limits

The Android Room test imports a production-scale synthetic archive, skips an identical publication with no catalog/part reads, rejects a corrupted changed part without replacing the cache, and then downloads one changed part while reusing all other parts. A separate on-disk v1 migration test ensures the old active generation survives. JVM tests cover transport-origin matching, same-stream collisions and identity conflicts.

Android may defer periodic work under battery saver, Doze, background restrictions or force-stop. A worker exceeding Android's execution window is cancelled/retried; a failed attempt does not expose partially imported rows. The PC publishes on its own schedule. Confirm real-device background behavior after installing the release; CI cannot prove that the owner's phone runs scheduled work.
