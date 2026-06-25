# KuiklyDeviceCalendarOhos

HarmonyOS 端系统日历模块，基于 `@ohos.calendarManager` 实现，为 Kuikly 框架提供原生日历能力。

## 安装

在 `oh-package.json5` 的 `dependencies` 中添加本地引用：

```json5
{
  "dependencies": {
    "@kuiklybase/kuikly-device-calendar-ohos": "file:../KuiklyDeviceCalendarOhos"
  }
}
```

或项目根目录执行:
`ohpm install @kuiklybase/kuikly-device-calendar-ohos`

### 依赖要求

- `@kuikly-open/render` >= `2.16.0`
- `compatibleSdkVersion` >= `12`
- `compatibleSdkType` = `OpenHarmony`

### 权限配置

在宿主应用的 `module.json5` 中添加日历权限：

```json5
{
  "module": {
    "requestPermissions": [
      {
        "name": "ohos.permission.READ_CALENDAR",
        "reason": "$string:calendar_read_reason",
        "usedScene": {
          "abilities": ["EntryAbility"],
          "when": "inuse"
        }
      },
      {
        "name": "ohos.permission.WRITE_CALENDAR",
        "reason": "$string:calendar_write_reason",
        "usedScene": {
          "abilities": ["EntryAbility"],
          "when": "inuse"
        }
      }
    ]
  }
}
```

> 模块运行时通过 `controller.getUIAbilityContext()` 获取 `UIAbilityContext`，因此需要确保宿主 Render 入口已正确注册 Module，并且调用时能够拿到 `controller`。

## 模块注册

在宿主应用中注册 `KRDeviceCalendarModule`：

```typescript
import { KRDeviceCalendarModule } from '@kuiklybase/kuikly-device-calendar-ohos';
import { getCustomRenderModuleCreatorRegisterMap } from '@kuikly-open/render';

getCustomRenderModuleCreatorRegisterMap().set(
  KRDeviceCalendarModule.MODULE_NAME,
  () => new KRDeviceCalendarModule(),
);
```

模块名：

```typescript
KRDeviceCalendarModule.MODULE_NAME  // => 'KuiklyDeviceCalendarModule'
```

## API

通过 `call(method, params, callback)` 调用，支持以下方法：

### checkPermissions — 检查日历权限

```typescript
call('checkPermissions', JSON.stringify({
  readOnly: false,
}), (result) => {
  // result: { status: 'authorized' | 'denied' | 'restricted', hasPermission?: boolean, error?: string }
});
```

### requestPermissions — 申请日历权限

```typescript
call('requestPermissions', JSON.stringify({
  readOnly: false,
}), (result) => {
  // result: {
  //   status: 'authorized' | 'denied',
  //   permissions: [{ permission: 'ohos.permission.READ_CALENDAR', status: 'granted' | 'denied' }]
  // }
});
```

### findCalendars — 查询设备日历列表

```typescript
call('findCalendars', null, (result) => {
  // result: { calendars: [...] }
});
```

返回的 `calendar` 对象示例：

```json
{
  "id": "local_calendar",
  "title": "我的日历",
  "source": "local_calendar",
  "type": "LOCAL",
  "color": "#0000FF",
  "allowedAvailabilities": ["busy", "free"],
  "allowsModifications": true,
  "isPrimary": false
}
```

### saveCalendar — 新建日历

```typescript
call('saveCalendar', JSON.stringify({
  name: 'demo_calendar',
  title: '测试日历',
  color: '#FF5A5F',
}), (result) => {
  // result: { id: 'demo_calendar' }
});
```

### removeCalendar — 删除日历

```typescript
call('removeCalendar', JSON.stringify({
  id: 'demo_calendar',
}), (result) => {
  // result: { success: true }
});
```

### fetchAllEvents — 查询时间范围内事件

```typescript
call('fetchAllEvents', JSON.stringify({
  startDate: '2026-06-25T00:00:00.000Z',
  endDate: '2026-06-30T00:00:00.000Z',
  calendarIds: ['demo_calendar'],
}), (result) => {
  // result: { events: [...] }
});
```

### findEventById — 根据事件 ID 查询

```typescript
call('findEventById', JSON.stringify({
  id: '123',
}), (result) => {
  // result: { event: {...} } | { event: null }
});
```

### saveEvent — 创建或更新事件

```typescript
call('saveEvent', JSON.stringify({
  title: 'Kuikly Demo Event',
  details: {
    calendarId: 'demo_calendar',
    startDate: '2026-06-25T08:00:00.000Z',
    endDate: '2026-06-25T09:00:00.000Z',
    location: 'Tencent Building',
    description: 'HarmonyOS calendar event demo',
    allDay: false,
    alarms: [
      { date: -30 }
    ]
  },
  options: {},
}), (result) => {
  // result: { id: '123' }
});
```

> `details.startDate` / `details.endDate` 支持 ISO 字符串；内部会转换为时间戳。

### removeEvent — 删除事件

```typescript
call('removeEvent', JSON.stringify({
  id: '123',
  options: {},
}), (result) => {
  // result: { success: true }
});
```

### editEvent — 编辑事件

```typescript
call('editEvent', JSON.stringify({
  id: '123',
  title: 'Updated Event',
  details: {
    calendarId: 'demo_calendar',
    startDate: '2026-06-25T10:00:00.000Z',
    endDate: '2026-06-25T11:00:00.000Z',
    description: 'updated description'
  },
  options: {},
}), (result) => {
  // result: { id: '123' }
});
```

### queryEventInstances — 查询事件实例

```typescript
call('queryEventInstances', JSON.stringify({
  start: 1760000000000,
  end: 1760600000000,
  ids: [],
  eventKey: [],
}), (result) => {
  // result: { events: [...] }
});
```

### openEventEditPage — 打开系统编辑页

```typescript
call('openEventEditPage', JSON.stringify({
  id: 123,
}), (result) => {
  // 当前 SDK 类型定义下会返回 error
});
```

> 该能力设计目标为 API `26.0.0+`，但当前实现中由于 HarmonyOS SDK 类型定义限制，暂时返回不可用错误。

### openEventInCalendar / uriForCalendar — 兼容占位方法

```typescript
call('openEventInCalendar', JSON.stringify({ id: '123' }), (result) => {
  // result: {}
});

call('uriForCalendar', null, (result) => {
  // result: {}
});
```

这两个方法仅为保持跨平台接口兼容，HarmonyOS 当前实现不执行实际逻辑。

## 回调结果

该模块**没有录音那种持续事件流**，所有结果都通过单次 `callback` 返回对象。

### 成功结果示例

```json
{"status":"authorized","hasPermission":true}
{"status":"authorized","permissions":[{"permission":"ohos.permission.READ_CALENDAR","status":"granted"},{"permission":"ohos.permission.WRITE_CALENDAR","status":"granted"}]}
{"calendars":[{"id":"local_calendar","title":"我的日历","source":"local_calendar","type":"LOCAL","color":"#0000FF","allowedAvailabilities":["busy","free"],"allowsModifications":true,"isPrimary":false}]}
{"id":"123"}
{"success":true}
{"event":{"id":"123","title":"Kuikly Demo Event","description":"HarmonyOS calendar event demo","startDate":"2026-06-25T08:00:00.000Z","endDate":"2026-06-25T09:00:00.000Z","startMs":1760000000000,"allDay":false,"location":"Tencent Building","timeZone":"","calendar":{"id":"demo_calendar","title":"测试日历","source":"demo_calendar","type":"LOCAL","color":"#FF5A5F","allowedAvailabilities":["busy","free"],"allowsModifications":true,"isPrimary":false},"alarms":[{"date":-30}]}}
```

### 错误结果示例

```json
{"error":"context is null"}
{"error":"calendar not found"}
{"error":"invalid startDate/endDate"}
{"error":"openEventEditPage is not supported in current API version. Requires API 26.0.0+"}
```

## 事件对象字段

返回的事件对象主要包含以下字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `string` | 事件 ID |
| `title` | `string` | 标题 |
| `description` | `string` | 描述 |
| `startDate` | `string` | ISO 开始时间 |
| `endDate` | `string` | ISO 结束时间 |
| `startMs` | `number` | 开始时间戳（毫秒） |
| `allDay` | `boolean` | 是否全天事件 |
| `location` | `string` | 地点 |
| `timeZone` | `string` | 时区 |
| `calendar` | `object` | 所属日历对象 |
| `alarms` | `array` | 提醒列表，格式为 `{ date: number }` |
| `identifier` | `any` | HarmonyOS 原生扩展字段 |
| `isLunar` | `any` | 是否农历事件 |
| `instanceStartTime` | `any` | 事件实例开始时间 |
| `instanceStartDate` | `string` | 事件实例开始时间 ISO 字符串 |
| `instanceEndTime` | `any` | 事件实例结束时间 |
| `instanceEndDate` | `string` | 事件实例结束时间 ISO 字符串 |
| `attendee` | `array` | 参与人列表 |

## 兼容性说明

- `checkPermissions` / `requestPermissions`：API `12+`
- `findCalendars` / `saveCalendar` / `removeCalendar`：API `10+`
- `fetchAllEvents` / `findEventById` / `saveEvent` / `removeEvent`：API `10+`
- `queryEventInstances`：设计用于更高版本能力场景
- `openEventEditPage`：目标 API `26.0.0+`，当前 SDK 类型定义下不可用

## License

MIT
