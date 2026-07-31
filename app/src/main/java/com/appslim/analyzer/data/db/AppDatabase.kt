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

/**
 * 应用列表缓存（MT Manager 式架构的核心）：
 * 只存「列表展示 + 智能排序」所需的最小字段，避免每次启动重扫 APK。
 *
 * - apkPath 用于判断是否「APK 变了」（升级/重装）→ 变了就重新 enrich；
 * - 四大组件计数直接来自 PackageInfo 的 activities/services/receivers/providers 数组长度，
 *   不触发 ManifestScanner 的全量 intent-filter 探针（那是很重的 IO，留到点击时才做）；
 * - installTime / updateTime 供「最近更新」排序；lastLaunch 不入表（来自 UsageStats，运行时取）。
 */
@Entity(tableName = "app_cache")
data class AppInfoEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val versionName: String,
    val apkPath: String,
    val isSystem: Boolean,
    val activityCount: Int,
    val serviceCount: Int,
    val receiverCount: Int,
    val providerCount: Int,
    val installTime: Long,
    val updateTime: Long,
    val cachedAt: Long
) {
    val compCount: Int get() = activityCount + serviceCount + receiverCount + providerCount
    /** 后台组件（服务 + 接收器）＝ 最该被瘦身、最常偷跑的指标 */
    val backgroundCount: Int get() = serviceCount + receiverCount
}

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

@Dao
interface AppCacheDao {
    @Query("SELECT * FROM app_cache")
    suspend fun all(): List<AppInfoEntity>
    @Query("SELECT * FROM app_cache WHERE packageName = :pkg")
    suspend fun get(pkg: String): AppInfoEntity?
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun put(e: AppInfoEntity)
    /** 卸掉已卸载应用对应的缓存行（传入当前已安装包名集合） */
    @Query("DELETE FROM app_cache WHERE packageName NOT IN (:pkgs)")
    suspend fun prune(pkgs: List<String>)
}

@Database(
    entities = [
        ComponentEventEntity::class, DailyUsageEntity::class,
        RuleSetEntity::class, AppInfoEntity::class
    ],
    version = 2, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun usageDao(): UsageDao
    abstract fun ruleSetDao(): RuleSetDao
    abstract fun appCacheDao(): AppCacheDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "appslim.db"
                )
                    // 开发期表结构演进直接重建，避免写一次性 Migration
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
