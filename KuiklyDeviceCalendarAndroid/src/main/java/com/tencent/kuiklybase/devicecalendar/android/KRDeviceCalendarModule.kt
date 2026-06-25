package com.tencent.kuiklybase.devicecalendar.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONArray
import org.json.JSONObject
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Android 原生 DeviceCalendar Module
 *
 * 通过 [CalendarContract] 直接操作系统日历。
 * 调用名与 [DeviceCalendarModule] (commonMain) 严格对齐。
 *
 * 注意事项：
 * 1. 集成方必须在宿主 Manifest 声明 READ_CALENDAR / WRITE_CALENDAR 权限，并在运行时申请。
 * 2. requestPermissions 依赖宿主 Activity，需在 Activity 的 onRequestPermissionsResult 中
 *    回调 [onRequestPermissionsResult]，否则授权回调不会被触发。
 */
class KRDeviceCalendarModule : KuiklyRenderBaseModule() {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        val ctx = context ?: run {
            callback?.invoke(errorResult("context is null"))
            return null
        }
        val paramJson = if (params.isNullOrEmpty()) JSONObject() else try {
            JSONObject(params)
        } catch (e: Exception) {
            JSONObject()
        }

        return when (method) {
            "checkPermissions" -> {
                checkPermissions(ctx, paramJson.optBoolean("readOnly", false), callback)
            }
            "requestPermissions" -> {
                requestPermissions(paramJson.optBoolean("readOnly", false), callback)
            }
            "findCalendars" -> {
                runIO(callback, requireReadPermission = true) { findEventCalendars(ctx) }
            }
            "saveCalendar" -> {
                runIO(callback, requireReadPermission = false) {
                    val id = addCalendar(ctx, paramJson)
                    JSONObject().apply { put("id", id.toString()) }
                }
            }
            "removeCalendar" -> {
                val id = paramJson.optString("id")
                runIO(callback, requireReadPermission = false) {
                    val success = removeCalendar(ctx, id)
                    JSONObject().apply { put("success", success) }
                }
            }
            "fetchAllEvents" -> {
                runIO(callback, requireReadPermission = true) {
                    val events = findEvents(
                        ctx,
                        paramJson.opt("startDate"),
                        paramJson.opt("endDate"),
                        paramJson.optJSONArray("calendarIds") ?: JSONArray(),
                    )
                    JSONObject().apply { put("events", events) }
                }
            }
            "findEventById" -> {
                val id = paramJson.optString("id")
                runIO(callback, requireReadPermission = true) {
                    val event = findEventById(ctx, id)
                    JSONObject().apply {
                        if (event != null) put("event", event) else put("event", JSONObject.NULL)
                    }
                }
            }
            "saveEvent" -> {
                val title = paramJson.optString("title")
                val details = paramJson.optJSONObject("details") ?: JSONObject()
                val options = paramJson.optJSONObject("options") ?: JSONObject()
                runIO(callback, requireReadPermission = false) {
                    val id = addEvent(ctx, title, details, options)
                    if (id > -1) JSONObject().apply { put("id", id.toString()) }
                    else throw IllegalStateException("Unable to save event")
                }
            }
            "removeEvent" -> {
                val id = paramJson.optString("id")
                val options = paramJson.optJSONObject("options") ?: JSONObject()
                runIO(callback, requireReadPermission = false) {
                    val success = removeEvent(ctx, id, options)
                    JSONObject().apply { put("success", success) }
                }
            }
            "openEventInCalendar" -> {
                openEventInCalendar(ctx, paramJson.optString("id"))
                null
            }
            "uriForCalendar" -> {
                callback?.invoke(
                    mapOf("uri" to CalendarContract.Events.CONTENT_URI.toString())
                )
                null
            }
            else -> {
                callback?.invoke(errorResult("method not found: $method"))
                null
            }
        }
    }

    private fun runIO(
        callback: KuiklyRenderCallback?,
        requireReadPermission: Boolean,
        block: () -> JSONObject,
    ) {
        val ctx = context ?: run {
            callback?.invoke(errorResult("context is null"))
            return
        }
        val ok = if (requireReadPermission) {
            haveCalendarPermissions(ctx, readOnly = true)
        } else {
            haveCalendarPermissions(ctx, readOnly = false)
        }
        if (!ok) {
            callback?.invoke(errorResult("unauthorized to access calendar"))
            return
        }
        ioExecutor.execute {
            val resultJson = try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "module call error", t)
                errorResultJson(t.message ?: "unknown error")
            }
            callback?.invoke(jsonObjectToMap(resultJson))
        }
    }

    // region Permissions
    private fun checkPermissions(ctx: Context, readOnly: Boolean, callback: KuiklyRenderCallback?) {
        val sp = ctx.getSharedPreferences(RNC_PREFS, Context.MODE_PRIVATE)
        val permissionRequested = sp.getBoolean(permissionKey(readOnly), false)

        val status = when {
            haveCalendarPermissions(ctx, readOnly) -> "authorized"
            !permissionRequested -> "undetermined"
            shouldShowRequestPermissionRationale(readOnly) -> "denied"
            else -> "restricted"
        }
        callback?.invoke(mapOf("status" to status))
    }

    private fun requestPermissions(readOnly: Boolean, callback: KuiklyRenderCallback?) {
        val ctx = context ?: run {
            callback?.invoke(errorResult("context is null"))
            return
        }
        val sp = ctx.getSharedPreferences(RNC_PREFS, Context.MODE_PRIVATE)
        sp.edit().putBoolean(permissionKey(readOnly), true).apply()

        if (haveCalendarPermissions(ctx, readOnly)) {
            callback?.invoke(mapOf("status" to "authorized"))
            return
        }
        val act = activity
        if (act == null) {
            callback?.invoke(errorResult("activity does not exist"))
            return
        }
        val permissions = if (readOnly) {
            arrayOf(Manifest.permission.READ_CALENDAR)
        } else {
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        }
        REQUEST_CODE_SEQ += 1
        val code = REQUEST_CODE_SEQ
        pendingPermissionCallbacks[code] = callback
        act.requestPermissions(permissions, code)
    }

    private fun haveCalendarPermissions(ctx: Context, readOnly: Boolean): Boolean {
        val read = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR)
        if (readOnly) {
            return read == PackageManager.PERMISSION_GRANTED
        }
        val write = ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR)
        return read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
    }

    private fun shouldShowRequestPermissionRationale(readOnly: Boolean): Boolean {
        val act = activity ?: return false
        val perm = if (readOnly) Manifest.permission.READ_CALENDAR else Manifest.permission.WRITE_CALENDAR
        return act.shouldShowRequestPermissionRationale(perm)
    }

    private fun permissionKey(readOnly: Boolean) =
        if (readOnly) "permissionRequestedRead" else "permissionRequested"

    // endregion

    // region Calendar

    private fun findEventCalendars(ctx: Context): JSONObject {
        val cr = ctx.contentResolver
        val isPrimaryCol = CalendarContract.Calendars.IS_PRIMARY ?: "0"
        val cursor = cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                isPrimaryCol,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.ALLOWED_AVAILABILITY,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.CALENDAR_COLOR,
            ),
            null, null, null,
        )
        val arr = JSONArray()
        cursor?.use {
            while (it.moveToNext()) {
                arr.put(serializeEventCalendar(it))
            }
        }
        return JSONObject().apply { put("calendars", arr) }
    }

    private fun findCalendarById(ctx: Context, calendarId: String): JSONObject? {
        if (calendarId.isEmpty()) return null
        val cr = ctx.contentResolver
        val uri = ContentUris.withAppendedId(
            CalendarContract.Calendars.CONTENT_URI,
            calendarId.toLongOrNull() ?: return null,
        )
        val isPrimaryCol = CalendarContract.Calendars.IS_PRIMARY ?: "0"
        cr.query(
            uri,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                isPrimaryCol,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.ALLOWED_AVAILABILITY,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.CALENDAR_COLOR,
            ),
            null, null, null,
        )?.use {
            if (it.moveToFirst()) return serializeEventCalendar(it)
        }
        return null
    }

    private fun addCalendar(ctx: Context, details: JSONObject): Long {
        val source = details.optJSONObject("source")
            ?: throw IllegalArgumentException("new calendars require `source` object")
        val name = details.optString("name").ifEmpty {
            throw IllegalArgumentException("new calendars require `name`")
        }
        val title = details.optString("title").ifEmpty {
            throw IllegalArgumentException("new calendars require `title`")
        }
        if (!details.has("color")) throw IllegalArgumentException("new calendars require `color`")
        val accessLevel = details.optString("accessLevel").ifEmpty {
            throw IllegalArgumentException("new calendars require `accessLevel`")
        }
        val ownerAccount = details.optString("ownerAccount").ifEmpty {
            throw IllegalArgumentException("new calendars require `ownerAccount`")
        }
        val sourceName = source.optString("name").ifEmpty {
            throw IllegalArgumentException("new calendars require a `source` object with a `name`")
        }
        val isLocalAccount = source.optBoolean("isLocalAccount", false)
        if (!source.has("type") && !isLocalAccount) {
            throw IllegalArgumentException("new calendars require a `source` object with a `type`, or `isLocalAccount`: true")
        }
        val accountType =
            if (isLocalAccount) CalendarContract.ACCOUNT_TYPE_LOCAL else source.optString("type")

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, sourceName)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
            put(CalendarContract.Calendars.CALENDAR_COLOR, details.optInt("color"))
            put(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                calAccessConstantMatchingString(accessLevel),
            )
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ownerAccount)
            put(CalendarContract.Calendars.NAME, name)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, title)
        }

        val uriBuilder = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, sourceName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)

        val calendarUri = ctx.contentResolver.insert(uriBuilder.build(), values)
            ?: throw IllegalStateException("Calendar insert failed")
        return calendarUri.lastPathSegment?.toLong() ?: -1L
    }

    private fun removeCalendar(ctx: Context, calendarId: String): Boolean {
        val id = calendarId.toLongOrNull() ?: return false
        val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id)
        return ctx.contentResolver.delete(uri, null, null) > 0
    }

    private fun calAccessConstantMatchingString(s: String): Int = when (s) {
        "contributor" -> CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
        "editor" -> CalendarContract.Calendars.CAL_ACCESS_EDITOR
        "freebusy" -> CalendarContract.Calendars.CAL_ACCESS_FREEBUSY
        "override" -> CalendarContract.Calendars.CAL_ACCESS_OVERRIDE
        "owner" -> CalendarContract.Calendars.CAL_ACCESS_OWNER
        "read" -> CalendarContract.Calendars.CAL_ACCESS_READ
        "respond" -> CalendarContract.Calendars.CAL_ACCESS_RESPOND
        "root" -> CalendarContract.Calendars.CAL_ACCESS_ROOT
        else -> CalendarContract.Calendars.CAL_ACCESS_NONE
    }

    private fun serializeEventCalendar(cursor: Cursor): JSONObject = JSONObject().apply {
        put("id", cursor.getString(0))
        put("title", cursor.getString(1) ?: "")
        put("source", cursor.getString(2) ?: "")
        put("allowedAvailabilities", calendarAllowedAvailabilities(cursor.getString(5) ?: ""))
        put("type", cursor.getString(6) ?: "")
        val color = try {
            String.format("#%06X", 0xFFFFFF and cursor.getInt(7))
        } catch (e: Exception) {
            "#FFFFFF"
        }
        put("color", color)
        cursor.getString(3)?.let { put("isPrimary", it == "1") }
        val accessLevel = cursor.getInt(4)
        val allowsModifications = accessLevel == CalendarContract.Calendars.CAL_ACCESS_ROOT ||
                accessLevel == CalendarContract.Calendars.CAL_ACCESS_OWNER ||
                accessLevel == CalendarContract.Calendars.CAL_ACCESS_EDITOR ||
                accessLevel == CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
        put("allowsModifications", allowsModifications)
    }

    private fun calendarAllowedAvailabilities(dbString: String): JSONArray {
        val arr = JSONArray()
        for (str in dbString.split(",")) {
            val id = str.toIntOrNull() ?: when (str) {
                "AVAILABILITY_BUSY" -> CalendarContract.Events.AVAILABILITY_BUSY
                "AVAILABILITY_FREE" -> CalendarContract.Events.AVAILABILITY_FREE
                "AVAILABILITY_TENTATIVE" -> CalendarContract.Events.AVAILABILITY_TENTATIVE
                else -> -1
            }
            when (id) {
                CalendarContract.Events.AVAILABILITY_BUSY -> arr.put("busy")
                CalendarContract.Events.AVAILABILITY_FREE -> arr.put("free")
                CalendarContract.Events.AVAILABILITY_TENTATIVE -> arr.put("tentative")
            }
        }
        return arr
    }

    // endregion

    // region Events
    private fun findEvents(
        ctx: Context,
        startDateRaw: Any?,
        endDateRaw: Any?,
        calendarIds: JSONArray,
    ): JSONArray {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        // 同时接受 ISO 字符串和 Long 毫秒
        val startMs = parseDateMillis(startDateRaw, sdf) ?: return JSONArray()
        val endMs = parseDateMillis(endDateRaw, sdf) ?: return JSONArray()
        val startCal = Calendar.getInstance().apply { timeInMillis = startMs }
        val endCal = Calendar.getInstance().apply { timeInMillis = endMs }

        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, startCal.timeInMillis)
        ContentUris.appendId(uriBuilder, endCal.timeInMillis)

        val selection = buildString {
            append("((")
            append(CalendarContract.Instances.BEGIN).append(" < ").append(endCal.timeInMillis)
            append(") AND (")
            append(CalendarContract.Instances.END).append(" >= ").append(startCal.timeInMillis)
            append(") AND (")
            append(CalendarContract.Instances.VISIBLE).append(" = 1) ")
            append("AND (").append(CalendarContract.Instances.STATUS).append(" IS NOT ")
            append(CalendarContract.Events.STATUS_CANCELED).append(") ")

            if (calendarIds.length() > 0) {
                append("AND (")
                for (i in 0 until calendarIds.length()) {
                    append(CalendarContract.Instances.CALENDAR_ID).append(" = ").append(calendarIds.getString(i))
                    if (i != calendarIds.length() - 1) append(" OR ")
                }
                append(")")
            }
            append(")")
        }

        val cursor = ctx.contentResolver.query(
            uriBuilder.build(),
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.RRULE,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.AVAILABILITY,
                CalendarContract.Instances.HAS_ALARM,
                CalendarContract.Instances.ORIGINAL_ID,
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.DURATION,
                CalendarContract.Instances.ORIGINAL_SYNC_ID,
            ),
            selection, null, null,
        )

        val results = JSONArray()
        cursor?.use {
            while (it.moveToNext()) {
                results.put(serializeEvent(ctx, it))
            }
        }
        return results
    }

    private fun findEventById(ctx: Context, eventId: String): JSONObject? {
        val id = eventId.toLongOrNull() ?: return null
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        val selection = "((${CalendarContract.Events.DELETED} != 1))"
        ctx.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.RRULE,
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.AVAILABILITY,
                CalendarContract.Events.HAS_ALARM,
                CalendarContract.Instances.DURATION,
            ),
            selection, null, null,
        )?.use {
            if (it.moveToFirst()) return serializeEvent(ctx, it)
        }
        return null
    }

    private fun addEvent(
        ctx: Context,
        title: String,
        details: JSONObject,
        options: JSONObject,
    ): Long {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        val skipTimezone = details.optBoolean("skipAndroidTimezone", false)
        if (!skipTimezone) sdf.timeZone = TimeZone.getTimeZone("GMT")

        val cr = ctx.contentResolver
        val values = ContentValues()
        if (title.isNotEmpty()) values.put(CalendarContract.Events.TITLE, title)
        if (details.has("description")) values.put(CalendarContract.Events.DESCRIPTION, details.optString("description"))
        if (details.has("location")) values.put(CalendarContract.Events.EVENT_LOCATION, details.optString("location"))

        if (details.has("startDate")) {
            val v = details.opt("startDate")
            val millis = parseDateMillis(v, sdf) ?: 0L
            if (millis > 0) values.put(CalendarContract.Events.DTSTART, millis)
        }
        if (details.has("endDate")) {
            val v = details.opt("endDate")
            val millis = parseDateMillis(v, sdf) ?: 0L
            if (millis > 0) values.put(CalendarContract.Events.DTEND, millis)
        }
        if (details.has("recurrence")) {
            createRecurrenceRule(details.optString("recurrence"), null, null, null, null, null, null)?.let {
                values.put(CalendarContract.Events.RRULE, it)
            }
        }
        if (details.has("recurrenceRule")) {
            val rule = details.optJSONObject("recurrenceRule")
            if (rule != null && rule.has("frequency")) {
                val frequency = rule.optString("frequency")
                val duration = rule.optString("duration", "PT1H")
                val interval = if (rule.has("interval")) rule.optInt("interval") else null
                val occurrence = if (rule.has("occurrence")) rule.optInt("occurrence") else null
                var endDateStr: String? = null
                if (rule.has("endDate")) {
                    val format = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
                    val v = rule.opt("endDate")
                    val millis = parseDateMillis(v, sdf)
                    if (millis != null) {
                        val cal = Calendar.getInstance().apply { timeInMillis = millis }
                        endDateStr = format.format(cal.time)
                    }
                }
                val daysOfWeek = rule.optJSONArray("daysOfWeek")
                val weekStart = rule.optString("weekStart").ifEmpty { null }
                val weekPositionInMonth =
                    if (rule.has("weekPositionInMonth")) rule.optInt("weekPositionInMonth") else null

                val rrule = createRecurrenceRule(
                    frequency,
                    interval,
                    endDateStr,
                    occurrence,
                    daysOfWeek,
                    weekStart,
                    weekPositionInMonth,
                )
                values.put(CalendarContract.Events.DURATION, duration)
                if (rrule != null) values.put(CalendarContract.Events.RRULE, rrule)
            }
        }
        if (details.has("allDay")) {
            values.put(CalendarContract.Events.ALL_DAY, if (details.optBoolean("allDay")) 1 else 0)
        }
        values.put(
            CalendarContract.Events.EVENT_TIMEZONE,
            if (details.has("timeZone")) details.optString("timeZone") else TimeZone.getDefault().id,
        )
        values.put(
            CalendarContract.Events.EVENT_END_TIMEZONE,
            if (details.has("endTimeZone")) details.optString("endTimeZone") else TimeZone.getDefault().id,
        )
        if (details.has("alarms")) values.put(CalendarContract.Events.HAS_ALARM, true)
        if (details.has("availability")) {
            values.put(
                CalendarContract.Events.AVAILABILITY,
                availabilityConstant(details.optString("availability")),
            )
        }

        if (details.has("id")) {
            val eventId = details.optString("id").toLong()
            val eventInstance = findEventById(ctx, details.optString("id"))
            if (eventInstance != null) {
                val eventCalendar = eventInstance.optJSONObject("calendar")
                if (!options.has("exceptionDate")) {
                    var updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                    if (options.optBoolean("sync") && eventCalendar != null) {
                        syncCalendar(cr, eventCalendar.optString("id"))
                        updateUri = eventUriAsSyncAdapter(
                            updateUri, eventCalendar.optString("source"), eventCalendar.optString("type"),
                        )
                    }
                    cr.update(updateUri, values, null, null)
                } else {
                    val v = options.opt("exceptionDate")
                    val millis = parseDateMillis(v, sdf)
                    if (millis != null) values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, millis)
                    var exceptionUri = Uri.withAppendedPath(
                        CalendarContract.Events.CONTENT_EXCEPTION_URI, eventId.toString(),
                    )
                    if (options.optBoolean("sync") && eventCalendar != null) {
                        syncCalendar(cr, eventCalendar.optString("id"))
                        exceptionUri = eventUriAsSyncAdapter(
                            exceptionUri, eventCalendar.optString("source"), eventCalendar.optString("type"),
                        )
                    }
                    cr.insert(exceptionUri, values)
                }
            }
            if (details.has("alarms")) {
                createRemindersForEvent(cr, eventId, details.optJSONArray("alarms") ?: JSONArray())
            }
            return eventId
        }

        // create new
        val calendarObj: JSONObject = if (details.has("calendarId")) {
            findCalendarById(ctx, details.optString("calendarId")) ?: JSONObject().apply { put("id", "1") }
        } else {
            findCalendarById(ctx, "1") ?: JSONObject().apply { put("id", "1") }
        }
        values.put(CalendarContract.Events.CALENDAR_ID, calendarObj.optString("id").toLongOrNull() ?: 1L)

        var createUri = CalendarContract.Events.CONTENT_URI
        if (options.optBoolean("sync")) {
            syncCalendar(cr, calendarObj.optString("id"))
            createUri = eventUriAsSyncAdapter(
                createUri, calendarObj.optString("source"), calendarObj.optString("type"),
            )
        }
        val eventUri = cr.insert(createUri, values) ?: return -1L
        val eventId = eventUri.lastPathSegment?.toLongOrNull() ?: return -1L
        if (details.has("alarms")) {
            createRemindersForEvent(cr, eventId, details.optJSONArray("alarms") ?: JSONArray())
        }
        return eventId
    }

    private fun removeEvent(ctx: Context, eventId: String, options: JSONObject): Boolean {
        val id = eventId.toLongOrNull() ?: return false
        val cr = ctx.contentResolver
        return try {
            val eventInstance = findEventById(ctx, eventId)
            val eventCalendar = eventInstance?.optJSONObject("calendar")
            if (!options.has("exceptionDate")) {
                var uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
                if (options.optBoolean("sync") && eventCalendar != null) {
                    syncCalendar(cr, eventCalendar.optString("id"))
                    uri = eventUriAsSyncAdapter(
                        uri, eventCalendar.optString("source"), eventCalendar.optString("type"),
                    )
                }
                cr.delete(uri, null, null) > 0
            } else {
                val values = ContentValues()
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                val v = options.opt("exceptionDate")
                val millis = parseDateMillis(v, sdf) ?: 0L
                values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, millis)
                values.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
                var uri = Uri.withAppendedPath(CalendarContract.Events.CONTENT_EXCEPTION_URI, eventId)
                if (options.optBoolean("sync") && eventCalendar != null) {
                    uri = eventUriAsSyncAdapter(
                        uri, eventCalendar.optString("source"), eventCalendar.optString("type"),
                    )
                }
                cr.insert(uri, values) != null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "removeEvent error", t)
            false
        }
    }

    private fun parseDateMillis(value: Any?, sdf: SimpleDateFormat): Long? {
        return when (value) {
            is String -> try {
                sdf.parse(value)?.time
            } catch (e: ParseException) {
                null
            }
            is Number -> value.toLong()
            else -> null
        }
    }

    private fun availabilityConstant(s: String): Int = when (s) {
        "free" -> CalendarContract.Events.AVAILABILITY_FREE
        "tentative" -> CalendarContract.Events.AVAILABILITY_TENTATIVE
        else -> CalendarContract.Events.AVAILABILITY_BUSY
    }

    private fun availabilityString(constant: Int): String = when (constant) {
        CalendarContract.Events.AVAILABILITY_FREE -> "free"
        CalendarContract.Events.AVAILABILITY_TENTATIVE -> "tentative"
        else -> "busy"
    }

    private fun createRecurrenceRule(
        recurrence: String,
        interval: Int?,
        endDate: String?,
        occurrence: Int?,
        daysOfWeek: JSONArray?,
        weekStart: String?,
        weekPositionInMonth: Int?,
    ): String? {
        var rrule = when (recurrence) {
            "daily" -> "FREQ=DAILY"
            "weekly" -> "FREQ=WEEKLY"
            "monthly" -> "FREQ=MONTHLY"
            "yearly" -> "FREQ=YEARLY"
            else -> return null
        }
        if (daysOfWeek != null && recurrence == "weekly") {
            rrule += ";BYDAY=" + jsonArrayJoin(daysOfWeek, ",")
        }
        if (recurrence == "monthly" && daysOfWeek != null && weekPositionInMonth != null) {
            rrule += ";BYSETPOS=$weekPositionInMonth"
            rrule += ";BYDAY=" + jsonArrayJoin(daysOfWeek, ",")
        }
        if (weekStart != null) rrule += ";WKST=$weekStart"
        if (interval != null) rrule += ";INTERVAL=$interval"
        if (endDate != null) rrule += ";UNTIL=$endDate"
        else if (occurrence != null) rrule += ";COUNT=$occurrence"
        return rrule
    }

    private fun jsonArrayJoin(arr: JSONArray, sep: String): String {
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            if (i > 0) sb.append(sep)
            sb.append(arr.optString(i))
        }
        return sb.toString()
    }

    private fun eventUriAsSyncAdapter(uri: Uri, accountName: String, accountType: String): Uri =
        uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
            .build()

    private fun syncCalendar(cr: ContentResolver, calendarId: String) {
        val id = calendarId.toLongOrNull() ?: return
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
        }
        cr.update(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id),
            values, null, null,
        )
    }

    private fun createRemindersForEvent(cr: ContentResolver, eventId: Long, reminders: JSONArray) {
        // 先清空旧的；query 可能返回 null（部分 ROM）需要判空
        try {
            val cursor = CalendarContract.Reminders.query(
                cr, eventId, arrayOf(CalendarContract.Reminders._ID),
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val remId = it.getLong(0)
                    try {
                        cr.delete(
                            ContentUris.withAppendedId(CalendarContract.Reminders.CONTENT_URI, remId),
                            null, null,
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "delete reminder $remId failed", t)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "query existing reminders failed", t)
        }

        // 插入新的；CalendarContract.Reminders.MINUTES 语义：事件开始前 N 分钟（必须 >= 0）
        for (i in 0 until reminders.length()) {
            val r = reminders.optJSONObject(i) ?: continue
            val date = r.opt("date") ?: continue
            val rawMinutes = (date as? Number)?.toInt() ?: continue
            // 业务约定：负数表示提前；Android 字段需要正数，做翻转 + 兜底 abs
            val minutes = if (rawMinutes < 0) -rawMinutes else rawMinutes
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            try {
                cr.insert(CalendarContract.Reminders.CONTENT_URI, values)
            } catch (t: Throwable) {
                // 单个 reminder 插入失败不影响事件本身
                Log.w(TAG, "insert reminder for event=$eventId minutes=$minutes failed", t)
            }
        }
    }

    private fun findReminderByEventId(ctx: Context, eventId: String, startDateMillis: Long): JSONArray {
        val arr = JSONArray()
        val cr = ctx.contentResolver
        val selection = "(${CalendarContract.Reminders.EVENT_ID} = ?)"
        cr.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES),
            selection, arrayOf(eventId), null,
        )?.use {
            while (it.moveToNext()) {
                val cal = Calendar.getInstance().apply { timeInMillis = startDateMillis }
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                cal.add(Calendar.MINUTE, it.getInt(0))
                arr.put(JSONObject().apply { put("date", sdf.format(cal.time)) })
            }
        }
        return arr
    }

    @SuppressLint("Range")
    private fun serializeEvent(ctx: Context, cursor: Cursor): JSONObject {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val event = JSONObject()
        var startDateUtc = ""
        var endDateUtc = ""
        var allDay = false
        val foundStart = Calendar.getInstance()
        val foundEnd = Calendar.getInstance()

        cursor.getString(3)?.let {
            foundStart.timeInMillis = it.toLong()
            startDateUtc = sdf.format(foundStart.time)
        }
        cursor.getString(4)?.let {
            foundEnd.timeInMillis = it.toLong()
            endDateUtc = sdf.format(foundEnd.time)
        }
        cursor.getString(5)?.let { allDay = cursor.getInt(5) != 0 }

        // recurrence rule
        cursor.getString(7)?.let { rruleStr ->
            val rrule = JSONObject()
            val pieces = rruleStr.split(";")
            if (pieces.isNotEmpty() && pieces[0].split("=").size > 1) {
                val freq = pieces[0].split("=")[1].lowercase()
                event.put("recurrence", freq)
                rrule.put("frequency", freq)
            }
            val durIdx = cursor.getColumnIndex(CalendarContract.Events.DURATION)
            if (durIdx != -1) cursor.getString(durIdx)?.let { rrule.put("duration", it) }
            if (pieces.size >= 2 && pieces[1].split("=")[0] == "INTERVAL") {
                rrule.put("interval", pieces[1].split("=")[1].toInt())
            }
            if (pieces.size >= 3) {
                val kv = pieces[2].split("=")
                when (kv[0]) {
                    "UNTIL" -> try {
                        val f = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
                        rrule.put("endDate", sdf.format(f.parse(kv[1])!!))
                    } catch (e: Exception) {}
                    "COUNT" -> rrule.put("occurrence", kv[1].toInt())
                }
            }
            event.put("recurrenceRule", rrule)
        }

        event.put("id", cursor.getString(0))
        val calendarIdIdx = cursor.getColumnIndex("calendar_id")
        if (calendarIdIdx != -1) {
            val cal = findCalendarById(ctx, cursor.getString(calendarIdIdx) ?: "")
            if (cal != null) event.put("calendar", cal)
        }
        event.put("title", cursor.getString(cursor.getColumnIndex("title")) ?: "")
        event.put("description", cursor.getString(2) ?: "")
        event.put("startDate", startDateUtc)
        event.put("endDate", endDateUtc)
        event.put("allDay", allDay)
        event.put("location", cursor.getString(6) ?: "")
        event.put("availability", availabilityString(cursor.getInt(9)))

        if (cursor.getInt(10) > 0) {
            event.put("alarms", findReminderByEventId(ctx, cursor.getString(0), foundStart.timeInMillis))
        } else {
            event.put("alarms", JSONArray())
        }

        val origIdIdx = cursor.getColumnIndex(CalendarContract.Events.ORIGINAL_ID)
        if (origIdIdx != -1) cursor.getString(origIdIdx)?.let { event.put("originalId", it) }
        val origSyncIdIdx = cursor.getColumnIndex(CalendarContract.Instances.ORIGINAL_SYNC_ID)
        if (origSyncIdIdx != -1) cursor.getString(origSyncIdIdx)?.let { event.put("syncId", it) }

        return event
    }

    // endregion

    private fun openEventInCalendar(ctx: Context, eventId: String) {
        try {
            val id = eventId.toLongOrNull() ?: return
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
            val intent = Intent(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setData(uri)
            if (intent.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(intent)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "openEventInCalendar error", t)
        }
    }

    // region Result helpers

    private fun errorResult(msg: String): Map<String, Any> = mapOf("error" to msg)
    private fun errorResultJson(msg: String): JSONObject = JSONObject().apply { put("error", msg) }

    /** 把 [JSONObject] 转成 KuiklyRender 可接受的 Map（callback 入参） */
    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            m[k] = unwrap(json.opt(k))
        }
        return m
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        JSONObject.NULL, null -> null
        is JSONObject -> jsonObjectToMap(v)
        is JSONArray -> {
            val list = ArrayList<Any?>(v.length())
            for (i in 0 until v.length()) list.add(unwrap(v.opt(i)))
            list
        }
        else -> v
    }

    // endregion

    companion object {
        const val MODULE_NAME = "KuiklyDeviceCalendarModule"
        private const val TAG = "KRDeviceCalendarModule"
        private const val RNC_PREFS = "KUIKLY_DEVICE_CALENDAR_PREFS"

        // 同进程内递增的请求码，避免与宿主冲突；起点取个相对偏门的值
        @Volatile
        private var REQUEST_CODE_SEQ = 0x7A00
        private val pendingPermissionCallbacks =
            HashMap<Int, KuiklyRenderCallback?>()

        /**
         * 宿主 Activity 的 onRequestPermissionsResult 中需转发给 Module，否则授权回调不会触发。
         * 示例：
         * ```
         * override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
         *     super.onRequestPermissionsResult(requestCode, permissions, grantResults)
         *     KRDeviceCalendarModule.onRequestPermissionsResult(requestCode, grantResults)
         * }
         * ```
         */
        @JvmStatic
        fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
            val cb = pendingPermissionCallbacks.remove(requestCode) ?: return
            val status = when {
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED -> "authorized"
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_DENIED -> "denied"
                else -> "denied"
            }
            cb.invoke(mapOf("status" to status))
        }
    }
}
