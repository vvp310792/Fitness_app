# Room
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }

# Keep our data model classes - Room's generated code and Firestore's manual
# field extraction (doc.getString/getLong/etc.) both touch these reflectively
# in ways R8 can't always trace statically.
-keep class com.atomichabits.tracker.data.** { *; }
