package com.dinh.javis.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * JAVIS local Room database (Version 3).
 *
 * v1 → v2: Added task_profiles, ai_profiles, task_runs, action_logs, policy_rules, behavior_aggregates.
 * v2 → v3: Added outcome columns to task_runs and behavior_aggregates;
 *          added unique index on behavior_aggregates(taskKey, date).
 *
 * User commands, profiles, and settings are NEVER deleted by automatic cleanup.
 * Partial migration failure is handled by fallback to destructive migration
 * ONLY if the user explicitly enables it; default is strict migration.
 */
@Database(
    entities = [
        CustomCommand::class,
        TaskProfile::class,
        AiModelProfile::class,
        TaskRun::class,
        ActionLog::class,
        PolicyRule::class,
        BehaviorAggregate::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun customCommandDao(): CustomCommandDao
    abstract fun taskProfileDao(): TaskProfileDao
    abstract fun aiModelProfileDao(): AiModelProfileDao
    abstract fun taskRunDao(): TaskRunDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun policyRuleDao(): PolicyRuleDao
    abstract fun behaviorAggregateDao(): BehaviorAggregateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // ── Migration 1 → 2 (preserved) ────────────────────────────────────
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `task_profiles` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `allowedPackages` TEXT NOT NULL,
                        `maxSteps` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ai_profiles` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `providerType` TEXT NOT NULL,
                        `baseUrl` TEXT NOT NULL,
                        `secretKeyAlias` TEXT NOT NULL,
                        `chatModelId` TEXT NOT NULL,
                        `visionModelId` TEXT NOT NULL,
                        `planningModelId` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `task_runs` (
                        `runId` TEXT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `taskGoal` TEXT NOT NULL,
                        `startTime` INTEGER NOT NULL,
                        `endTime` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `stepCount` INTEGER NOT NULL,
                        `failureReason` TEXT,
                        PRIMARY KEY(`runId`)
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `action_logs` (
                        `actionId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `runId` TEXT NOT NULL,
                        `actionType` TEXT NOT NULL,
                        `targetPackage` TEXT NOT NULL,
                        `sanitizedDetails` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `policy_rules` (
                        `packageName` TEXT NOT NULL,
                        `policy` TEXT NOT NULL,
                        `notes` TEXT,
                        PRIMARY KEY(`packageName`)
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `behavior_aggregates` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `taskKey` TEXT NOT NULL,
                        `date` TEXT NOT NULL,
                        `runCount` INTEGER NOT NULL,
                        `failureCount` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        // ── Migration 2 → 3 ────────────────────────────────────────────────
        // Adds outcome metadata columns and unique index on behavior_aggregates.
        // New columns use DEFAULT 0 / DEFAULT 'general' for backward compatibility.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // task_runs: add outcome metadata columns
                db.execSQL("ALTER TABLE `task_runs` ADD COLUMN `verifiedActionCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `task_runs` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `task_runs` ADD COLUMN `taskCategory` TEXT NOT NULL DEFAULT 'general'")

                // behavior_aggregates: add separate outcome counts and analytics columns
                db.execSQL("ALTER TABLE `behavior_aggregates` ADD COLUMN `cancelledCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `behavior_aggregates` ADD COLUMN `blockedCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `behavior_aggregates` ADD COLUMN `interruptedCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `behavior_aggregates` ADD COLUMN `totalDurationMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `behavior_aggregates` ADD COLUMN `totalVerifiedActions` INTEGER NOT NULL DEFAULT 0")

                // Deduplicate legacy behavior_aggregates before adding unique index (prevent SQLiteConstraintException)
                db.execSQL("""
                    DELETE FROM `behavior_aggregates`
                    WHERE id NOT IN (
                        SELECT MAX(id)
                        FROM `behavior_aggregates`
                        GROUP BY `taskKey`, `date`
                    )
                """.trimIndent())

                // Unique index on behavior_aggregates(taskKey, date) to prevent duplicate buckets
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_behavior_aggregates_taskKey_date` ON `behavior_aggregates` (`taskKey`, `date`)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "javis_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
