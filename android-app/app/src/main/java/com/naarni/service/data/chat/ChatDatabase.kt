package com.naarni.service.data.chat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The chat client's local store.
 *
 * Deliberately a separate database from anything else in the app: chat is the
 * only feature with an offline-first requirement, and keeping it isolated means
 * a destructive schema change here can never take job-card state with it.
 */
@Database(
    entities = [ChatRoomEntity::class, ChatMessageEntity::class, ChatUploadEntity::class],
    // 2: mentions, mentionsMe and alertEvent on a message.
    // 3: deliveredUpto and readUpto on a room — the second and third tick.
    // 4: reactions on a message.
    // 5: no shape change — a forced re-sync, because every row written before
    //    this carries the moment it was stored instead of the moment it was
    //    sent, and delta sync only ever returns messages *above* the cursor, so
    //    nothing would ever go back and correct them.
    // 6: deleteSeq and deletedBy on a message — deleting for everyone.
    // Only 6 has a migration written for it; see the note on MIGRATION_5_6.
    version = 6,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao

    companion object {
        private const val NAME = "naarni_chat.db"

        /**
         * Two columns added, nothing rewritten.
         *
         * Written out rather than left to the destructive fallback, which is the
         * exception this database's own note reserves for queued outbound
         * messages: a wipe takes the outbox with it, and a technician who typed
         * a message in a basement would lose it to a schema bump they never saw.
         * The tombstone columns default to "never deleted", which is true of
         * every row that already exists.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_message ADD COLUMN deleteSeq INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chat_message ADD COLUMN deletedBy TEXT")
            }
        }

        fun build(context: Context): ChatDatabase =
            Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, NAME)
                .addMigrations(MIGRATION_5_6)
                // Still the backstop for the older versions, which have no
                // migrations. Chat history is a cache of server state,
                // recoverable in full by a delta sync from seq 0.
                .fallbackToDestructiveMigration()
                .build()

        /**
         * Wipe local chat state on logout. The database is per-device, not
         * per-user, so leaving it in place would show the next person to sign in
         * on a shared depot handset the previous technician's threads.
         */
        fun clear(context: Context) {
            context.applicationContext.deleteDatabase(NAME)
        }
    }
}
