package com.appslim.analyzer.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** 组件事件（采集层落库的最小单元） */
@Entity(tableName = "component_events")
data class ComponentEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val kind: String,      // activity_start / service_start / provider_init / thread_sample / wakelock
    val detail: String,
    val ts: Long
)

/** 学习模式：7 天使用统计（报告 §12.1） */
@Entity(tableName = "daily_usage", primaryKeys = ["packageName", "day"])
data class DailyUsageEntity(
    val packageName: String,
    val day: String,       // yyyy-MM-dd
    val sessions: Int,
    val usedModules: String // 逗号分隔的组件类名
)

/** 阻断规则集（对应 rules.json 的本地存储） */
@Entity(tableName = "rule_sets")
data class RuleSetEntity(
    @PrimaryKey val packageName: String,
    val rulesJson: String,
    val updatedAt: Long
)

@Dao
interface EventDao {
    @Insert suspend fun insert(e: ComponentEventEntity)
    @Query("SELECT DISTINCT packageName FROM rule_sets") suspend fun watchedPackages(): List<String>
    @Query("SELECT * FROM component_events WHERE packageName = :pkg ORDER BY ts DESC LIMIT :limit")
    fun recent(pkg: String, limit: Int = 200): Flow<List<ComponentEventEntity>>
}

@Dao
interface UsageDao {
    @Insert suspend fun upsert(u: DailyUsageEntity)
    @Query("SELECT * FROM daily_usage WHERE packageName = :pkg ORDER BY day DESC LIMIT 7")
    suspend fun last7Days(pkg: String): List<DailyUsageEntity>
}

@Dao
interface RuleSetDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun save(r: RuleSetEntity)
    @Query("SELECT * FROM rule_sets WHERE packageName = :pkg") suspend fun get(pkg: String): RuleSetEntity?
    @Query("SELECT * FROM rule_sets") fun all(): Flow<List<RuleSetEntity>>
}

@Database(
    entities = [ComponentEventEntity::class, DailyUsageEntity::class, RuleSetEntity::class],
    version = 1, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun usageDao(): UsageDao
    abstract fun ruleSetDao(): RuleSetDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "appslim.db"
                ).build().also { INSTANCE = it }
            }
    }
}
