package com.naarni.service.data.inspection

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Where an inspection lives before the server has it.
 *
 * Deliberately its own database, for the same reason chat has one — a schema
 * problem here must not be able to take job-card state with it — but with the
 * opposite migration policy, and that difference is the most important line in
 * this file.
 *
 * **No destructive fallback.** Chat can afford one: every message is on the
 * server and a wipe costs a re-sync. An inspection queued here may exist
 * nowhere else. Dropping these tables to dodge a migration would delete a
 * morning's work from a handset with no way to get it back, and the engineer
 * would find out at the end of the shift. So a schema change here requires a
 * real migration, and if one is ever missing the app will crash on open —
 * loudly, before it can destroy anything — which is the correct trade.
 */
@Database(
	entities = [
		LocalRunEntity::class,
		LocalAnswerEntity::class,
		LocalScanEntity::class,
		LocalPhotoEntity::class,
		CachedDefinitionEntity::class,
		CachedProcessEntity::class,
	],
	version = 1,
	exportSchema = true,
)
abstract class InspectionDatabase : RoomDatabase() {

	abstract fun dao(): InspectionDao

	companion object {
		private const val NAME = "naarni_inspection.db"

		fun build(context: Context): InspectionDatabase =
			Room.databaseBuilder(context.applicationContext, InspectionDatabase::class.java, NAME)
				.build()

		/**
		 * Clear the process catalogue on logout — but never runs.
		 *
		 * Signing out on a shared depot handset must not delete an inspection
		 * the previous engineer has not managed to sync. Their work stays,
		 * queued, and goes up the next time that account signs in. What does go
		 * is the catalogue, which is per-role and would otherwise show the next
		 * person processes they cannot run.
		 */
		suspend fun clearCatalogue(db: InspectionDatabase) {
			db.dao().clearCatalogue()
		}
	}
}
