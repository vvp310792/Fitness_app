package com.fitnessapp.summary.sync

import org.junit.Assert.*
import org.junit.Test

class ServerSnapshotOverviewTest {
    private val valid=ServerSnapshotOverview(1,"a".repeat(64),10,100,1700000000,0)
    @Test fun currentFormatCanStartVerification() { assertTrue(valid.supportsVerifiedImport) }
    @Test fun oldPublicationCannotStartImport() { assertFalse(valid.copy(schemaVersion=0).supportsVerifiedImport) }
    @Test fun incompleteMetadataCannotStartImport() {
        assertFalse(valid.copy(manifestSetSha256=null).supportsVerifiedImport)
        assertFalse(valid.copy(records=null).supportsVerifiedImport)
        assertFalse(valid.copy(publishedSeconds=null).supportsVerifiedImport)
    }
    @Test fun invalidMetadataCannotStartImport() {
        assertFalse(valid.copy(manifestSetSha256="bad").supportsVerifiedImport)
        assertFalse(valid.copy(groups=-1).supportsVerifiedImport)
        assertFalse(valid.copy(publishedNanos=-1).supportsVerifiedImport)
    }
}
