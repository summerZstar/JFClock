package com.example.jfclock

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room 迁移测试（对应 docs/TEST_CASES.md F01、F02）。
 *
 * 做法：按「当年历史结构」手工建旧版本库（v1 全量建表，v2/v3 按迁移语句 ALTER），
 * 插入旧数据后交给 Room 打开。Room 打开时会顺序执行迁移，并把最终 schema 与
 * 当前实体比对——不一致直接抛异常，因此「能打开」本身就是 schema 校验；
 * 再用 SQL 断言旧数据保留、新列取默认值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** v1 建表语句：与 v1 时代 Room 生成的结构一致（列与当前实体去掉后三次迁移新增的列）。 */
    private val createV1 = """
        CREATE TABLE IF NOT EXISTS `alarms` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `hour` INTEGER NOT NULL,
            `minute` INTEGER NOT NULL,
            `enabled` INTEGER NOT NULL,
            `label` TEXT NOT NULL,
            `repeatType` INTEGER NOT NULL,
            `anchorTime` INTEGER NOT NULL,
            `vibrate` INTEGER NOT NULL,
            `sound` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL
        )
    """.trimIndent()

    /** 创建 version 版本的旧库（1/2/3/4/5/6/7），历史列完全按当年的迁移语句补齐。 */
    private fun createLegacyDb(version: Int): String {
        val name = "migration-test-v$version-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val raw = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            raw.execSQL(createV1)
            if (version >= 2) {
                raw.execSQL("ALTER TABLE alarms ADD COLUMN snoozeMinutes INTEGER NOT NULL DEFAULT 5")
                raw.execSQL("ALTER TABLE alarms ADD COLUMN skipTime INTEGER NOT NULL DEFAULT 0")
            }
            if (version >= 3) {
                raw.execSQL("ALTER TABLE alarms ADD COLUMN intenseAlert INTEGER NOT NULL DEFAULT 0")
                raw.execSQL("ALTER TABLE alarms ADD COLUMN specialNote TEXT NOT NULL DEFAULT ''")
            }
            if (version >= 4) {
                raw.execSQL("ALTER TABLE alarms ADD COLUMN snoozeUntil INTEGER NOT NULL DEFAULT 0")
            }
            if (version >= 5) {
                raw.execSQL(
                    "CREATE TABLE IF NOT EXISTS `timer_presets` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`seconds` INTEGER NOT NULL)"
                )
            }
            if (version >= 6) {
                raw.execSQL("ALTER TABLE timer_presets ADD COLUMN startHour INTEGER NOT NULL DEFAULT -1")
                raw.execSQL("ALTER TABLE timer_presets ADD COLUMN startMinute INTEGER NOT NULL DEFAULT 0")
            }
            if (version >= 7) {
                raw.execSQL("ALTER TABLE alarms ADD COLUMN repeatDays INTEGER NOT NULL DEFAULT 0")
            }
            raw.version = version
        } finally {
            raw.close()
        }
        return name
    }

    private fun rawInsert(name: String, sql: String) {
        val raw = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            raw.execSQL(sql)
        } finally {
            raw.close()
        }
    }

    private fun openWithRoom(name: String): AlarmDatabase =
        Room.databaseBuilder(context, AlarmDatabase::class.java, name)
            .addMigrations(*AlarmDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    private fun query(db: AlarmDatabase, sql: String): Cursor =
        db.openHelper.writableDatabase.query(sql)

    @Test
    fun `v1到v4全链路 - 多行旧数据保留且新列取默认值`() {
        val name = createLegacyDb(1)
        rawInsert(
            name,
            "INSERT INTO alarms (id, hour, minute, enabled, label, repeatType, anchorTime, vibrate, sound, createdAt) " +
                "VALUES (1, 7, 30, 1, '早餐', 0, 1759000000000, 1, 1, 1759000000000)"
        )
        rawInsert(
            name,
            "INSERT INTO alarms (id, hour, minute, enabled, label, repeatType, anchorTime, vibrate, sound, createdAt) " +
                "VALUES (2, 22, 5, 1, '睡觉', -2, 1759000000000, 0, 1, 1759000000000)"
        )
        val db = openWithRoom(name)
        try {
            // Room 打开旧库会自动迁移并校验最终 schema（不一致会抛异常，测试直接失败）
            query(db, "SELECT count(*) FROM alarms").use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
            query(
                db,
                "SELECT label, hour, minute, repeatType, enabled, snoozeMinutes, skipTime, " +
                    "intenseAlert, specialNote, snoozeUntil FROM alarms WHERE id = 1"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("早餐", c.getString(0))
                assertEquals(7, c.getInt(1))
                assertEquals(30, c.getInt(2))
                assertEquals(0, c.getInt(3))        // 每天
                assertEquals(1, c.getInt(4))
                assertEquals(5, c.getInt(5))        // v1→v2 默认 snoozeMinutes=5
                assertEquals(0L, c.getLong(6))      // v1→v2 默认 skipTime=0
                assertEquals(0, c.getInt(7))        // v2→v3 默认 intenseAlert=false
                assertEquals("", c.getString(8))    // v2→v3 默认 specialNote=''
                assertEquals(0L, c.getLong(9))      // v3→v4 默认 snoozeUntil=0
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `v2到v4 - F01 用户已设的snooze与skip保留 intense和note为默认值`() {
        val name = createLegacyDb(2)
        rawInsert(
            name,
            "INSERT INTO alarms (id, hour, minute, enabled, label, repeatType, anchorTime, vibrate, sound, createdAt, snoozeMinutes, skipTime) " +
                "VALUES (2, 6, 0, 1, '晨跑', -4, 1759000000000, 1, 1, 1759000000000, 10, 1759001000000)"
        )
        val db = openWithRoom(name)
        try {
            query(
                db,
                "SELECT label, snoozeMinutes, skipTime, intenseAlert, specialNote, snoozeUntil " +
                    "FROM alarms WHERE id = 2"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("晨跑", c.getString(0))
                assertEquals(10, c.getInt(1))               // 不被迁移覆盖
                assertEquals(1759001000000L, c.getLong(2))  // 不被迁移覆盖
                assertEquals(0, c.getInt(3))
                assertEquals("", c.getString(4))
                assertEquals(0L, c.getLong(5))
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `v4到v5 - F03 新建timer_presets表且旧闹钟数据完整`() {
        val name = createLegacyDb(4)
        rawInsert(
            name,
            "INSERT INTO alarms (id, hour, minute, enabled, label, repeatType, anchorTime, vibrate, sound, createdAt, snoozeMinutes, skipTime, intenseAlert, specialNote, snoozeUntil) " +
                "VALUES (4, 7, 45, 1, '上班', -4, 1759000000000, 1, 1, 1759000000000, 5, 0, 1, '带伞', 1759002000000)"
        )
        val db = openWithRoom(name)
        try {
            // 旧闹钟数据在 4→5 迁移后原样保留（Room 打开时同时校验 alarms 表结构）
            query(
                db,
                "SELECT label, intenseAlert, specialNote, snoozeUntil FROM alarms WHERE id = 4"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("上班", c.getString(0))
                assertEquals(1, c.getInt(1))
                assertEquals("带伞", c.getString(2))
                assertEquals(1759002000000L, c.getLong(3))
            }
            // 新表可用：插入并读回
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO timer_presets (id, name, seconds) VALUES (1, '刷牙', 120)"
            )
            query(db, "SELECT name, seconds FROM timer_presets WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("刷牙", c.getString(0))
                assertEquals(120, c.getInt(1))
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `v5到v6 - F04 预设补定时开始列且旧预设数据完整`() {
        val name = createLegacyDb(5)
        rawInsert(
            name,
            "INSERT INTO timer_presets (id, name, seconds) VALUES (1, '刷牙', 120)"
        )
        rawInsert(
            name,
            "INSERT INTO timer_presets (id, name, seconds) VALUES (2, '蒸蛋', 600)"
        )
        val db = openWithRoom(name)
        try {
            query(db, "SELECT name, seconds, startHour, startMinute FROM timer_presets ORDER BY id")
                .use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("刷牙", c.getString(0))
                    assertEquals(120, c.getInt(1))
                    assertEquals(-1, c.getInt(2))   // 旧预设 = 普通倒计时
                    assertEquals(0, c.getInt(3))
                    assertTrue(c.moveToNext())
                    assertEquals("蒸蛋", c.getString(0))
                    assertEquals(-1, c.getInt(2))
                }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `v6到v7 - F05 闹钟补自定义星期列且旧数据完整`() {
        val name = createLegacyDb(6)
        rawInsert(
            name,
            "INSERT INTO alarms (id, hour, minute, enabled, label, repeatType, anchorTime, vibrate, sound, createdAt, snoozeMinutes, skipTime, intenseAlert, specialNote, snoozeUntil) " +
                "VALUES (6, 6, 30, 1, '晨读', -4, 1759000000000, 1, 1, 1759000000000, 5, 0, 0, '', 0)"
        )
        rawInsert(
            name,
            "INSERT INTO timer_presets (id, name, seconds, startHour, startMinute) VALUES (1, '泡茶', 240, 7, 30)"
        )
        val db = openWithRoom(name)
        try {
            query(db, "SELECT label, repeatType, repeatDays FROM alarms WHERE id = 6").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("晨读", c.getString(0))
                assertEquals(-4, c.getInt(1))   // 工作日类型原样保留
                assertEquals(0, c.getInt(2))    // v6→v7 默认 repeatDays=0
            }
            query(db, "SELECT startHour, startMinute FROM timer_presets WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(7, c.getInt(0))
                assertEquals(30, c.getInt(1))
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `v7到v8 - F06 预设补拖动排序列且旧数据完整`() {
        val name = createLegacyDb(7)
        rawInsert(
            name,
            "INSERT INTO timer_presets (id, name, seconds, startHour, startMinute) VALUES (1, '泡茶', 240, 7, 30)"
        )
        rawInsert(
            name,
            "INSERT INTO timer_presets (id, name, seconds, startHour, startMinute) VALUES (2, '刷牙', 120, -1, 0)"
        )
        rawInsert(
            name,
            "INSERT INTO alarms (id, hour, minute, enabled, label, repeatType, anchorTime, vibrate, sound, createdAt, snoozeMinutes, skipTime, intenseAlert, specialNote, snoozeUntil, repeatDays) " +
                "VALUES (7, 7, 0, 1, '起床', 0, 1759000000000, 1, 1, 1759000000000, 5, 0, 0, '', 0, 0)"
        )
        val db = openWithRoom(name)
        try {
            query(
                db,
                "SELECT name, seconds, startHour, startMinute, sortOrder FROM timer_presets ORDER BY id"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("泡茶", c.getString(0))
                assertEquals(240, c.getInt(1))
                assertEquals(7, c.getInt(2))
                assertEquals(30, c.getInt(3))
                assertEquals(0, c.getInt(4))    // v7→v8 默认 sortOrder=0
                assertTrue(c.moveToNext())
                assertEquals("刷牙", c.getString(0))
                assertEquals(0, c.getInt(4))
            }
            query(db, "SELECT label, repeatDays FROM alarms WHERE id = 7").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("起床", c.getString(0))
                assertEquals(0, c.getInt(1))
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `v3到v4 - F02 intense和note保留 snoozeUntil默认0`() {
        val name = createLegacyDb(3)
        rawInsert(
            name,
            "INSERT INTO alarms (id, hour, minute, enabled, label, repeatType, anchorTime, vibrate, sound, createdAt, snoozeMinutes, skipTime, intenseAlert, specialNote) " +
                "VALUES (3, 8, 15, 1, '吃药', 0, 1759000000000, 1, 1, 1759000000000, 5, 0, 1, '吃降压药')"
        )
        val db = openWithRoom(name)
        try {
            query(
                db,
                "SELECT intenseAlert, specialNote, snoozeUntil, label FROM alarms WHERE id = 3"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))               // 增强提醒保留
                assertEquals("吃降压药", c.getString(1))    // 特殊提醒保留
                assertEquals(0L, c.getLong(2))             // v3→v4 默认 snoozeUntil=0
                assertEquals("吃药", c.getString(3))
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
