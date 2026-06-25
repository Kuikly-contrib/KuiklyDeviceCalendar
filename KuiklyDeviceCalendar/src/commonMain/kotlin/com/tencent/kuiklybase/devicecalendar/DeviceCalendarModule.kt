package com.tencent.kuiklybase.devicecalendar

import com.tencent.kuikly.core.module.CallbackFn
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * Kuikly 系统日历 Module。
 *
 * - Kuikly 侧仅定义 API，平台具体逻辑由 Android / iOS / 鸿蒙 三端 Native Module 实现。
 * - 通过 `MODULE_NAME` 与 Native 端注册名严格一致。
 * - 所有 IO 类调用均使用 [asyncToNativeMethod]，避免阻塞 Kuikly 渲染线程。
 *
 * 使用方式：
 * ```
 * val calendar = acquireModule<DeviceCalendarModule>(DeviceCalendarModule.MODULE_NAME)
 * calendar.requestPermissions { status -> ... }
 * ```
 */
class DeviceCalendarModule : Module() {

    override fun moduleName(): String = MODULE_NAME

    // region Permissions

    /**
     * 检查日历授权状态。
     * @param readOnly 仅 Android 生效（iOS 始终为读写）
     * @param callback 回调形如 `{ "status": "authorized" | "denied" | "restricted" | "undetermined" }`
     */
    fun checkPermissions(readOnly: Boolean = false, callback: CallbackFn) {
        val params = JSONObject().apply { put(KEY_READ_ONLY, readOnly) }
        asyncToNativeMethod(METHOD_CHECK_PERMISSIONS, params, callback)
    }

    /**
     * 请求日历授权。
     * @param readOnly 仅 Android 生效
     * @param callback 回调形如 `{ "status": "authorized" | "denied" | "restricted" | "undetermined" }`
     */
    fun requestPermissions(readOnly: Boolean = false, callback: CallbackFn) {
        val params = JSONObject().apply { put(KEY_READ_ONLY, readOnly) }
        asyncToNativeMethod(METHOD_REQUEST_PERMISSIONS, params, callback)
    }

    // endregion

    // region Calendars

    /**
     * 查询设备所有日历。
     * @param callback 回调形如 `{ "calendars": [...], "error": String? }`
     */
    fun findCalendars(callback: CallbackFn) {
        asyncToNativeMethod(METHOD_FIND_CALENDARS, null, callback)
    }

    /**
     * 新建一个日历。
     * @param options 日历参数，详见 [CalendarOptions]
     * @param callback 回调形如 `{ "id": String?, "error": String? }`
     */
    fun saveCalendar(options: JSONObject, callback: CallbackFn) {
        asyncToNativeMethod(METHOD_SAVE_CALENDAR, options, callback)
    }

    /**
     * 删除指定 id 的日历。
     * @param callback 回调形如 `{ "success": Boolean, "error": String? }`
     */
    fun removeCalendar(id: String, callback: CallbackFn) {
        val params = JSONObject().apply { put(KEY_ID, id) }
        asyncToNativeMethod(METHOD_REMOVE_CALENDAR, params, callback)
    }

    // endregion

    // region Events

    /**
     * 拉取时间范围内所有日历事件。
     * @param startDate ISO 时间字符串
     * @param endDate ISO 时间字符串
     * @param calendarIds 可选，过滤指定日历 id（为空则查询全部）
     * @param callback 回调形如 `{ "events": [...], "error": String? }`
     */
    /**
     * 拉取时间范围内所有日历事件。
     * @param startDate ISO 字符串或 Long 毫秒时间戳
     * @param endDate ISO 字符串或 Long 毫秒时间戳
     * @param calendarIds 可选，过滤指定日历 id（为空则查询全部）
     * @param callback 回调形如 `{ "events": [...], "error": String? }`
     */
    fun fetchAllEvents(
        startDate: Any,
        endDate: Any,
        calendarIds: List<String> = emptyList(),
        callback: CallbackFn,
    ) {
        val params = JSONObject().apply {
            put(KEY_START_DATE, startDate)
            put(KEY_END_DATE, endDate)
            put(KEY_CALENDAR_IDS, JSONArray().apply { calendarIds.forEach { put(it) } })
        }
        asyncToNativeMethod(METHOD_FETCH_ALL_EVENTS, params, callback)
    }

    /**
     * 根据 id 查询单个事件。
     * @param callback 回调形如 `{ "event": Object?, "error": String? }`，未找到返回 `event = null`
     */
    fun findEventById(id: String, callback: CallbackFn) {
        val params = JSONObject().apply { put(KEY_ID, id) }
        asyncToNativeMethod(METHOD_FIND_EVENT_BY_ID, params, callback)
    }

    /**
     * 创建或更新事件。
     * - 若 `details.id` 存在则更新该事件，否则创建新事件。
     * @param title 事件标题
     * @param details 事件详情 [CalendarEvent]
     * @param options 可选项 [EventOptions]
     * @param callback 回调形如 `{ "id": String?, "error": String? }`
     */
    fun saveEvent(
        title: String,
        details: JSONObject,
        options: JSONObject? = null,
        callback: CallbackFn,
    ) {
        val params = JSONObject().apply {
            put(KEY_TITLE, title)
            put(KEY_DETAILS, details)
            put(KEY_OPTIONS, options ?: JSONObject())
        }
        asyncToNativeMethod(METHOD_SAVE_EVENT, params, callback)
    }

    /**
     * 删除事件。
     * @param options 可选项，如 `{ "futureEvents": false, "exceptionDate": iso, "sync": false }`
     * @param callback 回调形如 `{ "success": Boolean, "error": String? }`
     */
    fun removeEvent(id: String, options: JSONObject? = null, callback: CallbackFn) {
        val params = JSONObject().apply {
            put(KEY_ID, id)
            put(KEY_OPTIONS, options ?: JSONObject())
        }
        asyncToNativeMethod(METHOD_REMOVE_EVENT, params, callback)
    }

    // endregion

    // region Android only

    /**
     * 仅 Android：跳转到系统日历查看指定事件。
     */
    fun openEventInCalendar(id: String) {
        val params = JSONObject().apply { put(KEY_ID, id) }
        asyncToNativeMethod(METHOD_OPEN_EVENT_IN_CALENDAR, params, null)
    }

    /**
     * 仅 Android：返回日历事件 URI。
     * @param callback 回调形如 `{ "uri": String }`
     */
    fun uriForCalendar(callback: CallbackFn) {
        asyncToNativeMethod(METHOD_URI_FOR_CALENDAR, null, callback)
    }

    // endregion

    companion object {
        /** 全端唯一 Module 名（必须与各平台注册名一致） */
        const val MODULE_NAME = "KuiklyDeviceCalendarModule"

        // Methods
        const val METHOD_CHECK_PERMISSIONS = "checkPermissions"
        const val METHOD_REQUEST_PERMISSIONS = "requestPermissions"
        const val METHOD_FIND_CALENDARS = "findCalendars"
        const val METHOD_SAVE_CALENDAR = "saveCalendar"
        const val METHOD_REMOVE_CALENDAR = "removeCalendar"
        const val METHOD_FETCH_ALL_EVENTS = "fetchAllEvents"
        const val METHOD_FIND_EVENT_BY_ID = "findEventById"
        const val METHOD_SAVE_EVENT = "saveEvent"
        const val METHOD_REMOVE_EVENT = "removeEvent"
        const val METHOD_OPEN_EVENT_IN_CALENDAR = "openEventInCalendar"
        const val METHOD_URI_FOR_CALENDAR = "uriForCalendar"

        // Param keys
        const val KEY_ID = "id"
        const val KEY_READ_ONLY = "readOnly"
        const val KEY_START_DATE = "startDate"
        const val KEY_END_DATE = "endDate"
        const val KEY_CALENDAR_IDS = "calendarIds"
        const val KEY_TITLE = "title"
        const val KEY_DETAILS = "details"
        const val KEY_OPTIONS = "options"

        // Authorization status
        const val STATUS_AUTHORIZED = "authorized"
        const val STATUS_DENIED = "denied"
        const val STATUS_RESTRICTED = "restricted"
        const val STATUS_UNDETERMINED = "undetermined"
    }
}
