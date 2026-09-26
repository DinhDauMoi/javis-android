package com.dinh.javis.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Cơ sở dữ liệu Room cục bộ của JAVIS (Phiên bản 2)
 * Lưu trữ hoàn toàn trên thiết bị (Local-first, không có máy chủ trung gian).
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
    version = 2,
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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "javis_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
