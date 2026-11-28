package com.fitnessapp.summary.sync

import com.fitnessapp.summary.analytics.ReviewedWorkoutPlan
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

/** Read-only owner-scoped policy. No personal IDs in the application package.
 * The SHA validates the exact content; the Firebase owner path provides access control.
 * A cached, verified policy remains safe because every affected input is rechecked. */
object ReviewedWorkoutPolicyGateway {
    fun observe(uid:String?):Flow<ReviewedWorkoutPlan?> {
        if(uid==null || !FirebaseSetup.isConfigured)return flowOf(null)
        return callbackFlow {
            val auth=FirebaseAuth.getInstance()
            trySend(null)
            if(auth.currentUser?.uid!=uid){close();return@callbackFlow}
            val listener=FirebaseFirestore.getInstance().collection("users").document(uid)
                .collection("canonical_review").document("current")
                .addSnapshotListener(MetadataChanges.INCLUDE){doc,error->
                    val plan=if(error==null && doc!=null && doc.exists() && !doc.metadata.hasPendingWrites() &&
                        auth.currentUser?.uid==uid && doc.getString("ownerUid")==uid && doc.getLong("schemaVersion")==1L) {
                        val payload=doc.getString("payloadJson");val sha=doc.getString("sha256")
                        if(payload!=null && sha!=null)ReviewedWorkoutPlan.decode(payload,sha) else null
                    } else null
                    trySend(plan)
                }
            awaitClose{listener.remove()}
        }
    }
}
