package com.fitnessapp.summary.sync

/** Server-reported metadata, not proof that its parts have been verified. */
data class ServerSnapshotOverview(
    val schemaVersion: Long,
    val manifestSetSha256: String?,
    val groups: Long?,
    val records: Long?,
    val publishedSeconds: Long?,
    val publishedNanos: Int?
) {
    val supportsVerifiedImport: Boolean get() =
        schemaVersion==ServerSnapshotContract.SCHEMA_VERSION &&
        manifestSetSha256?.matches(Regex("[0-9a-f]{64}"))==true &&
        groups!=null && groups in 0..ServerSnapshotContract.MAX_GROUPS.toLong() &&
        records!=null && records in 0..ServerSnapshotContract.MAX_ROWS &&
        publishedSeconds!=null && publishedSeconds>0 &&
        publishedNanos!=null && publishedNanos in 0..999_999_999
}
