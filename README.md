# KuiklyDeviceCalendar

适配 Kuikly 框架的跨平台系统日历组件，支持 Android / iOS / 鸿蒙三端。

## 功能特性

- **权限管理**：检查日历权限、申请日历权限
- **日历管理**：查询设备日历、新建日历、删除日历
- **事件管理**：创建、更新、查询、删除日历事件
- **时间范围查询**：按开始/结束时间批量拉取事件
- **提醒与参会人**：支持闹钟提醒、参会人、重复规则等常用字段
- **平台能力适配**：兼容 Android / iOS / 鸿蒙各端原生日历 API 差异

## 架构设计

采用 `Module` 模式，KMP 层通过 `asyncToNativeMethod` 与各端原生日历 API 通信，Kuikly 侧只暴露统一接口与数据模型。

| 平台 | 原生 API |
|------|----------|
| Android | `CalendarContract` |
| iOS | `EventKit` |
| 鸿蒙 | `ohos.calendarManager` |

## 项目结构

```text
KuiklyDeviceCalendar/
├── KuiklyDeviceCalendar/                # KMP 公共模块：Kuikly 侧 API / 数据模型
│   ├── src/commonMain/kotlin/com/tencent/kuiklybase/devicecalendar/
│   │   ├── DeviceCalendarModule.kt
│   │   └── CalendarTypes.kt
│   ├── build.gradle.kts
│   └── build.ohos.gradle.kts
├── KuiklyDeviceCalendarAndroid/         # Android 原生实现
├── KuiklyDeviceCalendarIOS/             # iOS 原生实现
├── KuiklyDeviceCalendarOhos/            # HarmonyOS 原生实现
├── shared/                              # Demo 页面与测试代码
├── androidApp/                          # Android 宿主应用
├── iosApp/                              # iOS 宿主应用
├── ohosApp/                             # 鸿蒙宿主应用
├── gradle.properties                    # 发布配置
└── publish-maven.sh                     # Maven 发布脚本
```

## 接入指南

**仓库配置**
```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
    }
}
```

### 1. KMP 层（DSL 侧）

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.tencent.kuiklybase:KuiklyDeviceCalendar:0.0.1-2.1.21")
}
```

### 2. Android 端

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.tencent.kuiklybase:KuiklyDeviceCalendarAndroid:0.0.1-2.1.21")
}
```

**AndroidManifest.xml 声明权限：**
```xml
<uses-permission android:name="android.permission.READ_CALENDAR" />
<uses-permission android:name="android.permission.WRITE_CALENDAR" />
```

**注册 Module（在 KuiklyRenderActivity 中）：**
```kotlin
import com.tencent.kuiklybase.devicecalendar.android.KRDeviceCalendarModule

override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) {
    super.registerExternalModule(kuiklyRenderExport)
    kuiklyRenderExport.moduleExport(KRDeviceCalendarModule.MODULE_NAME) {
        KRDeviceCalendarModule()
    }
}
```

> Android 运行时权限申请依赖宿主 Activity 转发回调：
> ```kotlin
> override fun onRequestPermissionsResult(
>     requestCode: Int,
>     permissions: Array<out String>,
>     grantResults: IntArray,
> ) {
>     super.onRequestPermissionsResult(requestCode, permissions, grantResults)
>     KRDeviceCalendarModule.onRequestPermissionsResult(requestCode, grantResults)
> }
> ```

### 3. iOS 端

在 Podfile 中添加：
```ruby
pod 'KuiklyDeviceCalendarIOS', :git => 'https://github.com/Kuikly-contrib/KuiklyDeviceCalendar.git', :branch => 'main'
```

**Info.plist 声明权限：**
```xml
<key>NSCalendarsUsageDescription</key>
<string>需要访问系统日历以创建和管理事件</string>
<key>NSRemindersUsageDescription</key>
<string>需要访问系统提醒事项以支持相关能力</string>
```

**iOS 无需手动注册 Module**，框架会按类名 `KuiklyDeviceCalendarModule` / `KRDeviceCalendarModule` 自动发现原生实现。

### 4. 鸿蒙端

**KMP 层依赖（在 `build.ohos.gradle.kts` 中）：**
```kotlin
dependencies {
    implementation("com.tencent.kuiklybase:KuiklyDeviceCalendar:0.0.1-2.0.21-KBA-010")
}
```

**原生层依赖（在 `oh-package.json5` 中）：**
```json5
"dependencies": {
  "@kuiklybase/kuiklybase/kuikly-device-calendar-ohos": "latest"
}
```

**`module.json5` 声明权限：**
```json5
"requestPermissions": [
  {
    "name": "ohos.permission.READ_CALENDAR"
  },
  {
    "name": "ohos.permission.WRITE_CALENDAR"
  }
]
```

**注册 Module：**
```typescript
getCustomRenderModuleCreatorRegisterMap().set(
  KRDeviceCalendarModule.MODULE_NAME,
  () => new KRDeviceCalendarModule(),
)
```

## API 文档

### 在 Pager 中注册和使用

```kotlin
import com.tencent.kuiklybase.devicecalendar.Alarm
import com.tencent.kuiklybase.devicecalendar.CalendarEvent
import com.tencent.kuiklybase.devicecalendar.DeviceCalendarModule

@Page("device_calendar_demo")
class DeviceCalendarDemoPage : BasePager() {

    override fun createExternalModules(): Map<String, Module>? {
        val modules = super.createExternalModules()?.toMutableMap() ?: hashMapOf()
        modules[DeviceCalendarModule.MODULE_NAME] = DeviceCalendarModule()
        return modules
    }

    override fun created() {
        super.created()
        val calendarModule = acquireModule<DeviceCalendarModule>(DeviceCalendarModule.MODULE_NAME)

        calendarModule.requestPermissions { permissionRes ->
            if (permissionRes?.optString("status") == "authorized") {
                calendarModule.findCalendars { calendarRes ->
                    val calendars = calendarRes?.optJSONArray("calendars")
                }

                val event = CalendarEvent(
                    startDate = System.currentTimeMillis() + 5 * 60 * 1000,
                    endDate = System.currentTimeMillis() + 65 * 60 * 1000,
                    location = "Tencent Building",
                    notes = "Kuikly DeviceCalendar Demo Event",
                    description = "Kuikly DeviceCalendar Demo Event",
                    alarms = listOf(Alarm(relativeOffsetMinutes = -1)),
                )
                calendarModule.saveEvent("[Kuikly Demo] 测试事件", event.toJson()) { result ->
                    val eventId = result?.optString("id")
                }
            }
        }
    }
}
```

### 权限相关

| 方法 | 说明 |
|------|------|
| `checkPermissions(readOnly = false, callback)` | 检查当前日历权限状态 |
| `requestPermissions(readOnly = false, callback)` | 申请日历权限 |

返回状态：`authorized` / `denied` / `restricted` / `undetermined`

### 日历管理

| 方法 | 说明 |
|------|------|
| `findCalendars(callback)` | 查询设备所有日历 |
| `saveCalendar(options, callback)` | 新建日历 |
| `removeCalendar(id, callback)` | 删除指定日历 |

### 事件管理

| 方法 | 说明 |
|------|------|
| `fetchAllEvents(startDate, endDate, calendarIds, callback)` | 查询时间范围内的事件 |
| `findEventById(id, callback)` | 根据 id 查询单个事件 |
| `saveEvent(title, details, options, callback)` | 创建或更新事件 |
| `removeEvent(id, options, callback)` | 删除事件 |
| `openEventInCalendar(id)` | 仅 Android：跳转系统日历查看事件 |
| `uriForCalendar(callback)` | 仅 Android：返回事件 URI |

## 数据类

### CalendarEvent

```kotlin
class CalendarEvent(
    var id: String? = null,
    var calendarId: String? = null,
    var startDate: Any? = null,
    var endDate: Any? = null,
    var allDay: Boolean? = null,
    var location: String? = null,
    var notes: String? = null,
    var description: String? = null,
    var url: String? = null,
    var timeZone: String? = null,
    var availability: String? = null,
    var recurrence: String? = null,
    var recurrenceRule: RecurrenceRule? = null,
    var alarms: List<Alarm>? = null,
    var attendees: List<Attendee>? = null,
    var structuredLocation: StructuredLocation? = null,
)
```

### Alarm

```kotlin
class Alarm(
    var dateIso: String? = null,
    var relativeOffsetMinutes: Int? = null,
    var structuredLocation: StructuredLocation? = null,
)
```

### Attendee

```kotlin
class Attendee(
    var name: String? = null,
    var email: String? = null,
    var phone: String? = null,
)
```

### RecurrenceRule

```kotlin
class RecurrenceRule(
    var frequency: String,
    var endDate: String? = null,
    var occurrence: Int? = null,
    var interval: Int? = null,
    var daysOfWeek: List<String>? = null,
    var weekStart: String? = null,
    var weekPositionInMonth: Int? = null,
    var duration: String? = null,
)
```

## 常用常量

### RecurrenceFrequency

```kotlin
RecurrenceFrequency.DAILY
RecurrenceFrequency.WEEKLY
RecurrenceFrequency.MONTHLY
RecurrenceFrequency.YEARLY
```

### EventAvailability

```kotlin
EventAvailability.BUSY
EventAvailability.FREE
EventAvailability.TENTATIVE
EventAvailability.UNAVAILABLE
```

### CalendarEntityType

```kotlin
CalendarEntityType.EVENT
CalendarEntityType.REMINDER
```

## 时间格式说明

- **推荐**：直接传 `Long` 毫秒时间戳，避免跨端时区格式化问题
- **也支持**：ISO 字符串 `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`
- `Alarm.relativeOffsetMinutes` 约定与 RN / iOS 一致：
  - **负数**：事件开始前提醒，例如 `-30`
  - **正数**：事件开始后提醒

## Demo 页面

仓库内提供了日历能力验证页 [DeviceCalendarDemoPage.kt](/Users/elixxli/Desktop/0623/test/new/KuiklyDeviceCalendar/shared/src/commonMain/kotlin/com/example/myapplication/DeviceCalendarDemoPage.kt)，用于快速验证：

- **权限检查 / 申请**
- **查询设备日历**
- **创建测试事件**
- **拉取近期事件**
- **查询刚创建的事件**
- **删除测试事件**

路由：`device_calendar_demo`

## 版本号规则

```text
发布坐标: {GROUP_ID}:{MODULE_NAME}:{MAVEN_VERSION}-{kotlinVersion}
示例:     com.tencent.kuiklybase:KuiklyDeviceCalendar:0.0.1-2.1.21
鸿蒙:     com.tencent.kuiklybase:KuiklyDeviceCalendar:0.0.1-2.0.21-KBA-010
```

## Maven 仓库

```kotlin
maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
```

## 已知限制

- **iOS**：部分字段如 `structuredLocation`、`url`、`timeZone` 仅在 iOS 生效
- **Android**：`description`、跳转系统日历等能力仅 Android 支持
- **鸿蒙**：`calendarManager` 在不同 SDK 版本存在差异，复杂 RRULE 能力有限
- **高级同步能力**：如多账户同步、复杂提醒策略等需按业务继续扩展

## License

MIT
