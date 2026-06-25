//package com.tencent.kuiklybase.devicecalendar
//
///**
// * DeviceCalendar 数据模型
// *
// * TODO: 根据你的组件需求修改此文件
// */
//data class DeviceCalendarData(
//    val id: String,
//    val title: String,
//    val startTime: Long = 0L,
//    val endTime: Long = 0L,
//    val location: String = "",
//    val allDay: Boolean = false,
//    val notes: String = "",
//    val type: DeviceCalendarEventType = DeviceCalendarEventType.DEFAULT,
//    val extra: Map<String, String> = emptyMap()
//)
//
///**
// * 事件类型枚举
// */
//enum class DeviceCalendarEventType {
//    DEFAULT,
//    CUSTOM
//}
//
///**
// * 工具类 — 工厂方法
// */
//object DeviceCalendarHelper {
//    private var counter = 0
//
//    fun generateId(): String = "dc_${++counter}_${kotlin.system.getTimeMillis()}"
//
//    fun createDefault(title: String): DeviceCalendarData {
//        return DeviceCalendarData(id = generateId(), title = title)
//    }
//}
