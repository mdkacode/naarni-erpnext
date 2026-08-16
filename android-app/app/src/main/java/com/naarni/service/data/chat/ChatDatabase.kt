package com.naarni.service.data.chat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The chat client's local store.
 *
 * Deliberately a separate database from anything else in the app: chat is the
 * only feature with an offline-first requirement, and keeping it isolated means
 * a destructive schema change here can never take job-card state with it.
 */
@Database(
    entities = [ChatRoomEntity::class, ChatMessageEntity::class, ChatUploadEntity::class],
    // 2: mentions, mentionsMe and alertEvent on a message. No migration is
    // written for it — see the destructive-fallback note below.
    version = 3,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao

    companion object {
        private const val NAME = "naarni_chat.db"

        fun build(context: Context): ChatDatabase =
            Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, NAME)
                // Chat history is a cache of server state, recoverable in full by
                // a delta sync from seq 0. Paying for a migration to preserve it
                // is not worth the risk of a bad migration bricking the tab.
                // Queued outbound messages are the exception — see below.
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
