package com.example.jfclock

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 法定工作日日历（中国大陆：节假日休息 + 调休补班日上班）。
 *
 * 数据来源说明：国务院办公厅只发布 HTML 公告（无结构化接口，页面改版即失效），
 * 因此这里采用由公告自动同步生成的 holiday-cn 数据集（每年一个 JSON，字段里带
 * 官方公告链接），依次尝试多个镜像域名；全部失败时退回「周一至周五」的本地规则，
 * 并在界面上明确标注当前是离线兜底，避免用户误以为已按法定节假日生效。
 */
object WorkdayCalendar {

    private const val PREFS_NAME = "workday_calendar"
    private const val KEY_DATA_PREFIX = "data_"
    private const val KEY_FETCH_PREFIX = "fetch_"
    private const val KEY_CUSTOM_URL = "custom_url"
    private const val FRESH_MS = 30L * 24 * 60 * 60 * 1000

    /** "yyyy-MM-dd" -> 该日是否放假（true=节假日休，false=调休补班要上班） */
    private val holidayMap = ConcurrentHashMap<String, Boolean>()
    private val loadedYears = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var ready = false

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 从本地缓存装载今年与明年数据；在 Application 启动时调用，纯本地读不联网。 */
    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val sp = prefs(context)
            val year = Calendar.getInstance().get(Calendar.YEAR)
            for (y in year..year + 1) parseYear(sp.getString(KEY_DATA_PREFIX + y, null))?.let {
                holidayMap.putAll(it)
                loadedYears.add(y)
            }
            ready = true
        }
    }

    /** 某日（传当天 00:00 毫秒值）是否为应上班的工作日。 */
    fun isWorkDay(dayStartMs: Long): Boolean {
        val key = dateKey(dayStartMs)
        holidayMap[key]?.let { return !it }
        val dow = Calendar.getInstance().apply { timeInMillis = dayStartMs }
            .get(Calendar.DAY_OF_WEEK)
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY
    }

    fun hasDataFor(year: Int): Boolean = loadedYears.contains(year)

    /** 该年官方公告链接（数据集里的 papers 字段），用于向用户展示来源。 */
    fun paperUrl(context: Context, year: Int): String? =
        prefs(context).getString(KEY_DATA_PREFIX + year, null)?.let { raw ->
            try {
                val arr = JSONObject(raw).optJSONArray("papers")
                arr?.optString(0, "")?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

    /** 今年/明年数据是否已新鲜；不新鲜时界面提示可点刷新。 */
    fun needsRefresh(context: Context): Boolean {
        val sp = prefs(context)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        return (year..year + 1).any {
            val age = System.currentTimeMillis() - sp.getLong(KEY_FETCH_PREFIX + it, 0L)
            age > FRESH_MS
        }
    }

    /**
     * 联网补齐今年与明年数据。必须在后台线程调用。
     *
     * @return 实际更新的年份列表；空列表表示全部命中缓存或全部下载失败。
     * 下载失败时保留旧缓存，判定逻辑继续按已有数据 + 周一至周五兜底。
     */
    fun refresh(context: Context): List<Int> {
        init(context)
        val sp = prefs(context)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val updated = mutableListOf<Int>()
        for (y in year..year + 1) {
            val age = System.currentTimeMillis() - sp.getLong(KEY_FETCH_PREFIX + y, 0L)
            if (age <= FRESH_MS) continue
            val json = fetchYear(context, y) ?: continue
            val parsed = parseYear(json) ?: continue
            holidayMap.keys.filter { it.startsWith("$y-") }.forEach { holidayMap.remove(it) }
            holidayMap.putAll(parsed)
            loadedYears.add(y)
            sp.edit()
                .putString(KEY_DATA_PREFIX + y, json)
                .putLong(KEY_FETCH_PREFIX + y, System.currentTimeMillis())
                .apply()
            updated.add(y)
        }
        return updated
    }

    fun setCustomUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_CUSTOM_URL, url.trim()).apply()
    }

    fun customUrl(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_URL, "").orEmpty()

    /** 上次成功同步时间文案；从未同步过时返回 null，界面据此显示「离线兜底」。 */
    fun lastSyncText(context: Context): String? {
        val sp = prefs(context)
        val newest = (1970..2100).map { sp.getLong(KEY_FETCH_PREFIX + it, 0L) }.maxOrNull() ?: 0L
        if (newest <= 0L) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return fmt.format(java.util.Date(newest))
    }

    private fun parseYear(raw: String?): Map<String, Boolean>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val arr = JSONObject(raw).optJSONArray("days") ?: return null
            val out = HashMap<String, Boolean>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val date = o.optString("date")
                if (date.length != 10) continue
                out[date] = o.optBoolean("isHoliday", true)
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    /** 依次尝试自定义地址与两个公共镜像，任一成功即返回原文。 */
    private fun fetchYear(context: Context, year: Int): String? {
        val candidates = mutableListOf<String>()
        val custom = prefs(context).getString(KEY_CUSTOM_URL, "").orEmpty()
        if (custom.isNotBlank()) candidates.add(custom.replace("{year}", year.toString()))
        candidates.add(
            "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/$year.json"
        )
        candidates.add(
            "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$year.json"
        )
        for (url in candidates) {
            val text = httpGet(url) ?: continue
            if (parseYear(text) != null) return text
        }
        return null
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "JFClock/1.0")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun dateKey(dayStartMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return fmt.format(java.util.Date(dayStartMs))
    }

    // ---- 仅单元测试使用：不改动任何运行时逻辑 ----

    /** 清空单例状态，测试在用例之间重置。 */
    @VisibleForTesting
    internal fun resetForTest() {
        holidayMap.clear()
        loadedYears.clear()
        ready = false
    }

    /** 直接注入节假日数据（绕过网络/缓存），供测试验证工作日判定。 */
    @VisibleForTesting
    internal fun loadForTest(data: Map<String, Boolean>, years: Set<Int>) {
        holidayMap.putAll(data)
        loadedYears.addAll(years)
        ready = true
    }
}
