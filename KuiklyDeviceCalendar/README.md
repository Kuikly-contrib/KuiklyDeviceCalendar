# KuiklyDeviceCalendar

跨端系统日历 Module，覆盖 Kuikly 全端（Android / iOS / 鸿蒙）。

## 目录结构

```
KuiklyDeviceCalendar/                    # KMP 公共模块：Kuikly 侧 Module 定义
├── src/commonMain/kotlin/com/tencent/kuiklybase/devicecalendar/
│   ├── DeviceCalendarModule.kt          # Kuikly 侧 API
│   └── CalendarTypes.kt                 # 数据模型 / 枚举常量
├── build.gradle.kts                     # Android/iOS Target
└── build.ohos.gradle.kts                # 鸿蒙 Target

KuiklyDeviceCalendarAndroid/             # Android 原生实现
├── src/main/java/com/tencent/kuiklybase/devicecalendar/android/
│   └── KRDeviceCalendarModule.kt
├── src/main/AndroidManifest.xml         # 已声明 READ/WRITE_CALENDAR 权限
└── build.gradle.kts

KuiklyDeviceCalendarIOS/                 # iOS 原生实现
├── KRDeviceCalendarModule.h
└── KRDeviceCalendarModule.m

KuiklyDeviceCalendarOhos/                # HarmonyOS 原生实现
└── KRDeviceCalendarModule.ets
```

## 一、Module 通信约定

- Module Name：`KuiklyDeviceCalendarModule`（三端注册名严格一致）
- 所有方法均为 **异步调用** (`asyncToNativeMethod`)，参数为 `JSONObject`，回调为 `JSONObject`。

| 方法 | 参数 | 返回 |
|---|---|---|
| `checkPermissions` | `{ readOnly: Boolean }` | `{ status }` |
| `requestPermissions` | `{ readOnly: Boolean }` | `{ status }` |
| `findCalendars` | - | `{ calendars: [...] }` |
| `saveCalendar` | `{...calendar options}` | `{ id?, error? }` |
| `removeCalendar` | `{ id }` | `{ success, error? }` |
| `fetchAllEvents` | `{ startDate, endDate, calendarIds }` | `{ events: [...] }` |
| `findEventById` | `{ id }` | `{ event } / { event: null }` |
| `saveEvent` | `{ title, details, options }` | `{ id?, error? }` |
| `removeEvent` | `{ id, options }` | `{ success, error? }` |
| `openEventInCalendar` | `{ id }` | -（仅 Android） |
| `uriForCalendar` | - | `{ uri }`（仅 Android） |

`status` 取值：`authorized` / `denied` / `restricted` / `undetermined`

## 二、Kuikly 侧用法

```kotlin
import com.tencent.kuiklybase.devicecalendar.DeviceCalendarModule
import com.tencent.kuiklybase.devicecalendar.CalendarEvent
import com.tencent.kuiklybase.devicecalendar.Alarm

// 1) 在 Pager 注册 Module
override fun createExternalModules(): Map<String, Module>? {
    val modules = (super.createExternalModules() as? HashMap) ?: hashMapOf()
    modules[DeviceCalendarModule.MODULE_NAME] = DeviceCalendarModule()
    return modules
}

// 2) 业务使用
val cal = acquireModule<DeviceCalendarModule>(DeviceCalendarModule.MODULE_NAME)

cal.requestPermissions { res ->
    if (res?.optString("status") == "authorized") {
        cal.findCalendars { c ->
            val list = c?.optJSONArray("calendars")
        }
        // 新建事件（1 小时后开始，2 小时后结束，前 30 分钟提醒）
        val event = CalendarEvent(
            startDate = "2026-06-25T14:00:00.000Z",
            endDate = "2026-06-25T15:00:00.000Z",
            location = "Tencent Building",
            notes = "周会",
            description = "周会",
            alarms = listOf(Alarm(relativeOffsetMinutes = -30)),
        )
        cal.saveEvent("周会提醒", event.toJson()) { r ->
            val newId = r?.optString("id")
        }
    }
}
```

## 三、平台接入

### Android

1. 在宿主 App 的 `AndroidManifest.xml` 加上：
   ```xml
   <uses-permission android:name="android.permission.READ_CALENDAR" />
   <uses-permission android:name="android.permission.WRITE_CALENDAR" />
   ```
2. 在 KuiklyRenderActivity 中注册：
   ```kotlin
   import com.tencent.kuiklybase.devicecalendar.android.KRDeviceCalendarModule

   override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) {
       super.registerExternalModule(kuiklyRenderExport)
       kuiklyRenderExport.moduleExport(KRDeviceCalendarModule.MODULE_NAME) {
           KRDeviceCalendarModule()
       }
   }

   // 将权限申请回调转发给 Module
   override fun onRequestPermissionsResult(
       requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
   ) {
       super.onRequestPermissionsResult(requestCode, permissions, grantResults)
       KRDeviceCalendarModule.onRequestPermissionsResult(requestCode, grantResults)
   }
   ```

### iOS

1. `Info.plist` 添加：
   ```xml
   <key>NSCalendarsUsageDescription</key>
   <string>用于在系统日历中创建提醒</string>
   <key>NSRemindersUsageDescription</key>
   <string>用于在系统日历中创建提醒</string>
   ```
2. 引入 `KuiklyDeviceCalendar` Pod（podspec 已配置 `EventKit`）。
3. 类名 `KuiklyDeviceCalendarModule` 必须与 Kuikly 侧 `moduleName()` 一致；KRBaseModule 会在运行时按类名动态实例化，**无需手动注册**。

### HarmonyOS

1. `module.json5` 添加：
   ```json
   "requestPermissions": [
     { "name": "ohos.permission.READ_CALENDAR" },
     { "name": "ohos.permission.WRITE_CALENDAR" }
   ]
   ```
2. 在 KuiklyRenderViewController 等 Compose Render 入口注册：
   ```ets
   getCustomRenderModuleCreatorRegisterMap().set(
     KRDeviceCalendarModule.MODULE_NAME,
     () => new KRDeviceCalendarModule(),
   );
   ```
3. 确保 Module 的 `controller` 可正常提供 `UIAbilityContext`（当前实现通过 `controller.getUIAbilityContext()` 获取上下文）。

## 四、CalendarEvent 字段对照

`CalendarEvent` 字段与 RN `CalendarEventWritable` 对齐：

- 跨端字段：`id`, `calendarId`, `title`, `startDate`, `endDate`, `allDay`, `location`, `availability`, `recurrence`, `recurrenceRule`, `alarms`, `attendees`
- iOS 专属：`notes`, `url`, `timeZone`, `structuredLocation`
- Android 专属：`description`

日期使用 ISO 字符串：`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (UTC)。

## 五、已知限制

- 部分高级能力（iOS structuredLocation 嵌套字段、Android 多账户 Sync）需要业务侧自行扩展。
- 鸿蒙端 `calendarManager` API 在不同 SDK 版本差异较大，本实现使用通用能力子集，未实现 RRULE 复杂规则。
- iOS `processColor` 由 RN 端处理，本 Module 接收的 `color` 期望是 `Int` (0xRRGGBB) 或 hex 字符串。
