# Changelog

## 0.0.1

- **首次发布**：发布 HarmonyOS 原生模块 `@kuiklybase/kuikly-device-calendar-ohos`。
- **基础能力**：提供系统日历权限检查与申请能力，支持 `checkPermissions`、`requestPermissions`。
- **日历管理**：支持查询设备日历、新建日历、删除日历，对应 `findCalendars`、`saveCalendar`、`removeCalendar`。
- **事件管理**：支持事件查询、创建、更新、删除，对应 `fetchAllEvents`、`findEventById`、`saveEvent`、`editEvent`、`removeEvent`。
- **实例查询**：支持 `queryEventInstances` 查询时间范围内的事件实例。
- **跨端兼容**：补齐 `openEventInCalendar`、`uriForCalendar` 等兼容接口，便于与 Android / iOS 统一调用。
- **模块接入**：支持通过 `getCustomRenderModuleCreatorRegisterMap()` 注册 `KRDeviceCalendarModule`，供 Kuikly Render 宿主调用。
- **运行依赖**：依赖 `@kuikly-open/render >= 2.16.0`，最低 `compatibleSdkVersion` 为 `12`，`compatibleSdkType` 为 `OpenHarmony`。
- **已知限制**：`openEventEditPage` 受当前 HarmonyOS SDK 类型定义限制暂不可用；部分高级日历字段能力受系统 API 差异影响。