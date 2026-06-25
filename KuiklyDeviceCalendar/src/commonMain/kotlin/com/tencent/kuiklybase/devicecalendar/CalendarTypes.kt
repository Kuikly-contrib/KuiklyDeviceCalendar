package com.tencent.kuiklybase.devicecalendar

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 数据结构辅助工具：将常用日历对象转换为 [JSONObject]。
 *
 * 业务侧也可直接手写 [JSONObject]，本文件仅做便捷封装。
 */

/** 重复事件频率 */
object RecurrenceFrequency {
    const val DAILY = "daily"
    const val WEEKLY = "weekly"
    const val MONTHLY = "monthly"
    const val YEARLY = "yearly"
}

/** iOS Entity 类型（saveCalendar 用） */
object CalendarEntityType {
    const val EVENT = "event"
    const val REMINDER = "reminder"
}

/** Android 日历访问级别（saveCalendar 用） */
object CalendarAccessLevel {
    const val CONTRIBUTOR = "contributor"
    const val EDITOR = "editor"
    const val FREEBUSY = "freebusy"
    const val OVERRIDE = "override"
    const val OWNER = "owner"
    const val READ = "read"
    const val RESPOND = "respond"
    const val ROOT = "root"
}

/** 事件可用性 */
object EventAvailability {
    const val BUSY = "busy"
    const val FREE = "free"
    const val TENTATIVE = "tentative"
    const val UNAVAILABLE = "unavailable"
}

/**
 * 创建/更新事件用的参数，全部字段可选；最终都会编入 [JSONObject]。
 *
 * 与 RN `CalendarEventWritable` 对齐，平台无关字段在所有端均可使用，平台相关字段会在对应平台生效。
 */
class CalendarEvent(
    var id: String? = null,
    var calendarId: String? = null,
    /**
     * 开始时间，支持两种格式：
     * - ISO 字符串 (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`)
     * - Long 毫秒时间戳（推荐，无需做跨端时间格式化）
     */
    var startDate: Any? = null,
    /** 结束时间，类型同 [startDate] */
    var endDate: Any? = null,
    var allDay: Boolean? = null,
    var location: String? = null,
    var notes: String? = null,           // iOS
    var description: String? = null,     // Android
    var url: String? = null,             // iOS
    var timeZone: String? = null,        // iOS
    var availability: String? = null,
    /** 简单重复频率，详见 [RecurrenceFrequency] */
    var recurrence: String? = null,
    /** 完整重复规则，详见 [RecurrenceRule] */
    var recurrenceRule: RecurrenceRule? = null,
    var alarms: List<Alarm>? = null,
    var attendees: List<Attendee>? = null,
    var structuredLocation: StructuredLocation? = null, // iOS
) {
    fun toJson(): JSONObject = JSONObject().apply {
        id?.let { put("id", it) }
        calendarId?.let { put("calendarId", it) }
        startDate?.let { put("startDate", it) }
        endDate?.let { put("endDate", it) }
        allDay?.let { put("allDay", it) }
        location?.let { put("location", it) }
        notes?.let { put("notes", it) }
        description?.let { put("description", it) }
        url?.let { put("url", it) }
        timeZone?.let { put("timeZone", it) }
        availability?.let { put("availability", it) }
        recurrence?.let { put("recurrence", it) }
        recurrenceRule?.let { put("recurrenceRule", it.toJson()) }
        alarms?.let { list ->
            put("alarms", JSONArray().apply { list.forEach { put(it.toJson()) } })
        }
        attendees?.let { list ->
            put("attendees", JSONArray().apply { list.forEach { put(it.toJson()) } })
        }
        structuredLocation?.let { put("structuredLocation", it.toJson()) }
    }
}

class RecurrenceRule(
    var frequency: String,
    var endDate: String? = null,
    var occurrence: Int? = null,
    var interval: Int? = null,
    var daysOfWeek: List<String>? = null,
    var weekStart: String? = null,
    var weekPositionInMonth: Int? = null,
    var duration: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("frequency", frequency)
        endDate?.let { put("endDate", it) }
        occurrence?.let { put("occurrence", it) }
        interval?.let { put("interval", it) }
        daysOfWeek?.let { days ->
            put("daysOfWeek", JSONArray().apply { days.forEach { put(it) } })
        }
        weekStart?.let { put("weekStart", it) }
        weekPositionInMonth?.let { put("weekPositionInMonth", it) }
        duration?.let { put("duration", it) }
    }
}

class Alarm(
    /** 绝对时间 ISO 字符串，或相对偏移分钟数 (Int)；二选一 */
    var dateIso: String? = null,
    /**
     * 相对事件开始时间的偏移（分钟）。**约定与 iOS / RN 一致**：
     * - 负数 = 事件开始前 N 分钟提醒（如 -30 表示提前 30 分钟）
     * - 正数 = 事件开始后 N 分钟提醒
     *
     * Android `CalendarContract.Reminders.MINUTES` 字段语义是"提前 N 分钟"，
     * 由 Native 侧自动做正负翻转，业务侧无需关心。
     */
    var relativeOffsetMinutes: Int? = null,
    var structuredLocation: StructuredLocation? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        when {
            dateIso != null -> put("date", dateIso)
            relativeOffsetMinutes != null -> put("date", relativeOffsetMinutes)
        }
        structuredLocation?.let { put("structuredLocation", it.toJson()) }
    }
}

class Attendee(
    var name: String? = null,
    var email: String? = null,
    var phone: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        name?.let { put("name", it) }
        email?.let { put("email", it) }
        phone?.let { put("phone", it) }
    }
}

class StructuredLocation(
    var title: String,
    var proximity: String? = null, // "enter" / "leave" / "none"
    var radius: Double? = null,
    var latitude: Double? = null,
    var longitude: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        proximity?.let { put("proximity", it) }
        radius?.let { put("radius", it) }
        if (latitude != null && longitude != null) {
            put("coords", JSONObject().apply {
                put("latitude", latitude!!)
                put("longitude", longitude!!)
            })
        }
    }
}
