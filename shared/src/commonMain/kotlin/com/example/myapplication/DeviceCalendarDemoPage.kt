package com.example.myapplication

import com.example.myapplication.base.BasePager
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.CalendarModule
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button
import com.tencent.kuiklybase.devicecalendar.Alarm
import com.tencent.kuiklybase.devicecalendar.CalendarEvent
import com.tencent.kuiklybase.devicecalendar.DeviceCalendarModule

/**
 * DeviceCalendarModule 能力验证页。
 *
 * 路由：`device_calendar_demo`
 *
 * 使用流程：
 *  1. 启动应用，进入 RouterPage，输入 `device_calendar_demo` 跳转。
 *  2. 顶部按钮依次点击：检查权限 → 申请权限 → 查询日历 → 新建事件 → 拉取事件 → 删除事件。
 *  3. 每个步骤的结果都会展示在下方日志区。
 */
@Page("device_calendar_demo")
internal class DeviceCalendarDemoPage : BasePager() {

    // region 响应式状态

    private var permissionStatus: String by observable("unknown")
    private var lastCreatedEventId: String by observable("")
    private var firstCalendarId: String by observable("")
    internal val logs by observableList<LogItem>()
    internal val events by observableList<EventItem>()
    internal val calendars by observableList<CalendarItem>()

    // endregion

    // 在基类已注册的 Module 基础上追加 DeviceCalendarModule
    override fun createExternalModules(): Map<String, Module>? {
        val modules = HashMap<String, Module>(super.createExternalModules() ?: emptyMap())
        modules[DeviceCalendarModule.MODULE_NAME] = DeviceCalendarModule()
        return modules
    }

    private val calendarModule: DeviceCalendarModule
        get() = acquireModule(DeviceCalendarModule.MODULE_NAME)

    // region 业务逻辑

    private fun appendLog(tag: String, content: String) {
        logs.add(0, LogItem(tag, content))
        while (logs.size > 50) logs.removeAt(logs.size - 1)
    }

    private fun onCheckPermissions() {
        calendarModule.checkPermissions { res ->
            val status = res?.optString("status") ?: "unknown"
            permissionStatus = status
            appendLog("checkPermissions", "status = $status, raw = $res")
        }
    }

    private fun onRequestPermissions() {
        calendarModule.requestPermissions { res ->
            val status = res?.optString("status") ?: "unknown"
            permissionStatus = status
            appendLog("requestPermissions", "status = $status, raw = $res")
        }
    }

    private fun onFindCalendars() {
        calendarModule.findCalendars { res ->
            val arr = res?.optJSONArray("calendars") ?: JSONArray()
            calendars.clear()
            var firstId = ""
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val id = c.optString("id")
                val title = c.optString("title")
                val source = c.optString("source")
                if (firstId.isEmpty()) firstId = id
                calendars.add(CalendarItem(id = id, title = title, source = source))
            }
            firstCalendarId = firstId
            appendLog("findCalendars", "count = ${arr.length()}, firstId = $firstId")
        }
    }

    private fun onCreateEvent() {
        // 创建一个 5 分钟后开始、1 小时后结束的事件，并设置 1 分钟前提醒
        val now = currentTimestampMs()
        val start = now + 5L * 60 * 1000
        val end = start + 60L * 60 * 1000

        val event = CalendarEvent(
            startDate = start,                  // Long 毫秒，跨端 Native 都支持
            endDate = end,
            location = "Tencent Building (Demo)",
            notes = "Kuikly DeviceCalendar Demo Event",
            description = "Kuikly DeviceCalendar Demo Event",
            alarms = listOf(Alarm(relativeOffsetMinutes = -1)),
        )
        if (firstCalendarId.isNotEmpty()) {
            event.calendarId = firstCalendarId
        }
        calendarModule.saveEvent(
            title = "[Kuikly Demo] 测试事件",
            details = event.toJson(),
            options = null,
        ) { res ->
            val id = res?.optString("id") ?: ""
            val err = res?.optString("error")
            if (id.isNotEmpty()) {
                lastCreatedEventId = id
                appendLog("saveEvent", "OK, id = $id")
            } else {
                appendLog("saveEvent", "FAIL, error = $err")
            }
        }
    }

    private fun onFetchAllEvents() {
        val now = currentTimestampMs()
        val start = now - 24L * 3600 * 1000
        val end = now + 7L * 24 * 3600 * 1000
        calendarModule.fetchAllEvents(start, end) { res ->
            val arr = res?.optJSONArray("events") ?: JSONArray()
            events.clear()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                events.add(
                    EventItem(
                        id = e.optString("id"),
                        title = e.optString("title"),
                        startDate = e.optString("startDate"),
                        endDate = e.optString("endDate"),
                        location = e.optString("location"),
                    )
                )
            }
            appendLog("fetchAllEvents", "count = ${arr.length()}")
        }
    }

    private fun onFindLastEvent() {
        if (lastCreatedEventId.isEmpty()) {
            appendLog("findEventById", "no lastCreatedEventId yet")
            return
        }
        calendarModule.findEventById(lastCreatedEventId) { res ->
            val e = res?.optJSONObject("event")
            if (e == null) {
                appendLog("findEventById", "id=$lastCreatedEventId, NOT FOUND")
            } else {
                appendLog(
                    "findEventById",
                    "id=${e.optString("id")}, title=${e.optString("title")}, start=${e.optString("startDate")}"
                )
            }
        }
    }

    private fun onRemoveLastEvent() {
        if (lastCreatedEventId.isEmpty()) {
            appendLog("removeEvent", "no lastCreatedEventId yet")
            return
        }
        val id = lastCreatedEventId
        calendarModule.removeEvent(id) { res ->
            val success = res?.optBoolean("success", false) ?: false
            appendLog("removeEvent", "id=$id, success=$success, raw=$res")
            if (success) lastCreatedEventId = ""
        }
    }

    // endregion

    // region UI

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                backgroundColor(Color(0xFFF5F6FA))
            }

            // 顶部导航
            RouterNavBar {
                attr {
                    title = "DeviceCalendar 能力验证"
                }
            }

            // 主滚动容器
            Scroller {
                attr {
                    flex(1f)
                }

                // 状态卡片
                SectionCard("状态") {
                    Text {
                        attr {
                            fontSize(14f)
                            color(Color(0xFF333333))
                            text("权限状态：${ctx.permissionStatus}")
                            marginBottom(4f)
                        }
                    }
                    Text {
                        attr {
                            fontSize(14f)
                            color(Color(0xFF333333))
                            text("最近创建事件 ID：${ctx.lastCreatedEventId.ifEmpty { "—" }}")
                            marginBottom(4f)
                        }
                    }
                    Text {
                        attr {
                            fontSize(14f)
                            color(Color(0xFF333333))
                            text("默认日历 ID：${ctx.firstCalendarId.ifEmpty { "—" }}")
                        }
                    }
                }

                // 操作区
                SectionCard("操作") {
                    ActionRow(
                        "1. 检查权限", "2. 申请权限",
                        { ctx.onCheckPermissions() }, { ctx.onRequestPermissions() },
                    )
                    ActionRow(
                        "3. 查询日历", "4. 新建事件",
                        { ctx.onFindCalendars() }, { ctx.onCreateEvent() },
                    )
                    ActionRow(
                        "5. 拉取近期事件", "6. 查询刚才创建的事件",
                        { ctx.onFetchAllEvents() }, { ctx.onFindLastEvent() },
                    )
                    ActionRow(
                        "7. 删除刚才创建的事件", "0. 清空日志",
                        { ctx.onRemoveLastEvent() }, { ctx.logs.clear() },
                    )
                }

                // 日历列表
                SectionCard("设备日历 (${ctx.calendars.size})") {
                    vif({ ctx.calendars.isEmpty() }) {
                        Text {
                            attr {
                                fontSize(13f)
                                color(Color(0xFF999999))
                                text("点击「查询日历」获取设备日历")
                            }
                        }
                    }
                    vfor({ ctx.calendars }) { item ->
                        View {
                            attr {
                                flexDirectionRow()
                                paddingTop(6f)
                                paddingBottom(6f)
                            }
                            Text {
                                attr {
                                    flex(1f)
                                    fontSize(13f)
                                    color(Color(0xFF222222))
                                    text("${item.title} (${item.source})")
                                }
                            }
                            Text {
                                attr {
                                    fontSize(12f)
                                    color(Color(0xFF888888))
                                    text("id=${item.id}")
                                }
                            }
                        }
                    }
                }

                // 事件列表
                SectionCard("近期事件 (${ctx.events.size})") {
                    vif({ ctx.events.isEmpty() }) {
                        Text {
                            attr {
                                fontSize(13f)
                                color(Color(0xFF999999))
                                text("点击「拉取近期事件」加载未来 7 天事件")
                            }
                        }
                    }
                    vfor({ ctx.events }) { item ->
                        View {
                            attr {
                                paddingTop(8f)
                                paddingBottom(8f)
                            }
                            Text {
                                attr {
                                    fontSize(14f)
                                    color(Color(0xFF111111))
                                    text(item.title.ifEmpty { "(无标题)" })
                                }
                            }
                            Text {
                                attr {
                                    fontSize(12f)
                                    color(Color(0xFF666666))
                                    marginTop(2f)
                                    text("${item.startDate}  →  ${item.endDate}")
                                }
                            }
                            vif({ item.location.isNotEmpty() }) {
                                Text {
                                    attr {
                                        fontSize(12f)
                                        color(Color(0xFF888888))
                                        marginTop(2f)
                                        text("@ ${item.location}")
                                    }
                                }
                            }
                            Divider()
                        }
                    }
                }

                // 日志区
                SectionCard("调用日志") {
                    vif({ ctx.logs.isEmpty() }) {
                        Text {
                            attr {
                                fontSize(13f)
                                color(Color(0xFF999999))
                                text("(空)")
                            }
                        }
                    }
                    vfor({ ctx.logs }) { item ->
                        View {
                            attr {
                                paddingTop(6f)
                                paddingBottom(6f)
                            }
                            Text {
                                attr {
                                    fontSize(13f)
                                    color(Color(0xFFAD37FE))
                                    text("# ${item.tag}")
                                }
                            }
                            Text {
                                attr {
                                    fontSize(12f)
                                    color(Color(0xFF333333))
                                    marginTop(2f)
                                    text(item.content)
                                }
                            }
                            Divider()
                        }
                    }
                }

                // 底部留白
                View {
                    attr { height(32f) }
                }
            }
        }
    }

    // endregion

    // region 工具方法

    /**
     * 跨端获取当前毫秒时间戳。
     * 使用 Kuikly 内置 `CalendarModule.newCalendarInstance(0L)` —— `timeMillis = 0` 时返回当前时间。
     * Android / iOS / 鸿蒙 / H5 / 小程序均有 native 实现，无需宿主自己实现。
     */
    private fun currentTimestampMs(): Long = try {
        acquireModule<CalendarModule>(CalendarModule.MODULE_NAME)
            .newCalendarInstance(0L)
            .timeInMillis()
    } catch (e: Throwable) {
        0L
    }

    // endregion

    // region 数据模型

    internal data class LogItem(val tag: String, val content: String)
    internal data class CalendarItem(val id: String, val title: String, val source: String)
    internal data class EventItem(
        val id: String,
        val title: String,
        val startDate: String,
        val endDate: String,
        val location: String,
    )

    // endregion
}

// region 顶层 UI 工具扩展（直接在 ViewBuilder 隐式接收者上调用，无需 ctx 前缀）

/** 通用分组卡片 */
internal fun ViewContainer<*, *>.SectionCard(
    title: String,
    children: ViewContainer<*, *>.() -> Unit,
) {
    View {
        attr {
            margin(12f)
            marginBottom(0f)
            padding(12f)
            backgroundColor(Color.WHITE)
            borderRadius(8f)
        }
        Text {
            attr {
                fontSize(15f)
                fontWeightSemisolid()
                color(Color(0xFF111111))
                marginBottom(8f)
                text(title)
            }
        }
        children()
    }
}

/** 双按钮一行 */
internal fun ViewContainer<*, *>.ActionRow(
    leftText: String,
    rightText: String,
    leftClick: () -> Unit,
    rightClick: () -> Unit,
) {
    View {
        attr {
            flexDirectionRow()
            marginTop(6f)
            marginBottom(6f)
        }
        Button {
            attr {
                flex(1f)
                height(40f)
                marginRight(6f)
                borderRadius(6f)
                backgroundColor(Color(0xFFAD37FE))
                titleAttr {
                    text(leftText)
                    fontSize(13f)
                    color(Color.WHITE)
                }
            }
            event {
                click { leftClick() }
            }
        }
        Button {
            attr {
                flex(1f)
                height(40f)
                marginLeft(6f)
                borderRadius(6f)
                backgroundColor(Color(0xFF23D3FD))
                titleAttr {
                    text(rightText)
                    fontSize(13f)
                    color(Color.WHITE)
                }
            }
            event {
                click { rightClick() }
            }
        }
    }
}

/** 1px 分割线 */
internal fun ViewContainer<*, *>.Divider() {
    View {
        attr {
            height(0.5f)
            marginTop(6f)
            backgroundColor(Color(0xFFEEEEEE))
        }
    }
}

// endregion
