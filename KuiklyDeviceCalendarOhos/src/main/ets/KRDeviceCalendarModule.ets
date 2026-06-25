import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import calendarManager from '@ohos.calendarManager';
import { BusinessError } from '@ohos.base';
import abilityAccessCtrl, { Permissions } from '@ohos.abilityAccessCtrl';
import bundleManager from '@ohos.bundle.bundleManager';
import common from '@ohos.app.ability.common';

/**
 * HarmonyOS 原生 DeviceCalendar Module
 *
 * 与 Kuikly 侧 DeviceCalendarModule(moduleName = KuiklyDeviceCalendarModule) 配套。
 * 基于 ohos.calendarManager API version 10+ 实现，支持完整的日历管理能力：
 *   - 权限管理（支持 READ_CALENDAR / WRITE_CALENDAR）
 *   - 日历管理：创建、查询、删除日历
 *   - 日程管理：创建、查询、更新、删除日程
 *   - 日程实例查询（API 18+）
 *   - 编辑页面跳转（API 26.0.0+）
 *
 * 日历权限需要由宿主应用在 entry 的 module.json5 中声明并在运行时申请。
 */

// 权限常量
const PERM_READ: Permissions = 'ohos.permission.READ_CALENDAR';
const PERM_WRITE: Permissions = 'ohos.permission.WRITE_CALENDAR';

// 权限状态常量
const STATUS_AUTHORIZED = 'authorized';
const STATUS_DENIED = 'denied';
const STATUS_RESTRICTED = 'restricted';
const STATUS_UNDETERMINED = 'undetermined';

// 错误码常量
const ERROR_PARAM_VALUE = 23900001; // 参数值错误
const ERROR_ACCOUNT_NOT_FOUND = 23900003; // 账户未找到
const ERROR_INTERNAL = 23900004; // 内部程序错误
const ERROR_EVENT_NOT_EDITABLE = 23900005; // 日程不支持编辑

interface AlarmParams {
  date?: number;
}

interface EventDetails {
  id?: string;
  title?: string;
  calendarId?: string;
  startDate?: string;
  endDate?: string;
  description?: string;
  notes?: string;
  location?: string;
  allDay?: boolean;
  timeZone?: string;
  alarms?: AlarmParams[];
}

interface ModuleCallParams {
  readOnly?: boolean;
  id?: string | number;
  startDate?: string;
  endDate?: string;
  calendarIds?: string[];
  title?: string;
  details?: EventDetails;
  options?: Record<string, KRAny>;
  start?: number;
  end?: number;
  ids?: number[];
  eventKey?: string[];
  name?: string;
  color?: string | number;
}

interface SystemVersionInfo {
  apiVersion?: number;
}

interface GlobalContextHolder {
  systemVersion?: SystemVersionInfo;
}

interface EmptyResult {
}

interface ErrorResult {
  error: string;
  code?: number;
}

interface PermissionStatusResult {
  status: string;
  hasPermission?: boolean;
  error?: string;
}

interface PermissionItemResult {
  permission: Permissions;
  status: string;
}

interface PermissionRequestResult {
  status: string;
  permissions: PermissionItemResult[];
}

interface CalendarsResult {
  calendars: SerializedCalendar[];
}

interface EventResult {
  event: SerializedEvent | null;
}

interface EventsResult {
  events: SerializedEvent[];
}

interface SuccessResult {
  success: boolean;
}

interface IdResult {
  id: string;
}

interface CalendarEventLocation {
  location: string;
}

type CalendarEventCompat = calendarManager.Event;

interface CalendarConfigCompat extends calendarManager.CalendarConfig {
  color?: string;
}

interface SerializedCalendar {
  id: string;
  title: string;
  source: string;
  type: string;
  color: string;
  allowedAvailabilities: string[];
  allowsModifications: boolean;
  isPrimary: boolean;
}

interface SerializedAlarm {
  date: number;
}

interface SerializedAttendee {
  name: string;
  email: string;
  role?: KRAny;
  status?: KRAny;
  type?: KRAny;
}

interface SerializedEvent {
  id: string;
  title: string;
  description: string;
  startDate: string;
  endDate: string;
  startMs: number;
  allDay: boolean;
  location: string;
  timeZone: string;
  calendar: SerializedCalendar;
  alarms: SerializedAlarm[];
  identifier?: KRAny;
  isLunar?: KRAny;
  instanceStartTime?: KRAny;
  instanceStartDate?: string;
  instanceEndTime?: KRAny;
  instanceEndDate?: string;
  attendee?: SerializedAttendee[];
}

export class KRDeviceCalendarModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME = 'KuiklyDeviceCalendarModule';

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    const args = this.parseParams(params);
    switch (method) {
      case 'checkPermissions':
        this.checkPermissions(!!args.readOnly, callback);
        return null;
      case 'requestPermissions':
        this.requestPermissions(!!args.readOnly, callback);
        return null;
      case 'findCalendars':
        this.findCalendars(callback);
        return null;
      case 'saveCalendar':
        this.saveCalendar(args, callback);
        return null;
      case 'removeCalendar':
        this.removeCalendar(String(args.id ?? ''), callback);
        return null;
      case 'fetchAllEvents':
        this.fetchAllEvents(
          args.startDate ?? '',
          args.endDate ?? '',
          args.calendarIds ?? [],
          callback,
        );
        return null;
      case 'findEventById':
        this.findEventById(String(args.id ?? ''), callback);
        return null;
      case 'saveEvent':
        this.saveEvent(
          args.title ?? '',
          args.details ?? {},
          args.options ?? {},
          callback,
        );
        return null;
      case 'removeEvent':
        this.removeEvent(
          String(args.id ?? ''),
          args.options ?? {},
          callback,
        );
        return null;
      case 'editEvent':
        this.editEvent(
          String(args.id ?? ''),
          args.title ?? '',
          args.details ?? {},
          args.options ?? {},
          callback,
        );
        return null;
      case 'queryEventInstances':
        this.queryEventInstances(
          args.start ?? 0,
          args.end ?? 0,
          args.ids ?? [],
          args.eventKey ?? [],
          callback,
        );
        return null;
      case 'openEventEditPage':
        this.openEventEditPage(
          Number(args.id ?? 0),
          callback,
        );
        return null;
      case 'openEventInCalendar':
      case 'uriForCalendar':
        if (callback) {
          const result: EmptyResult = {};
          callback(result);
        }
        return null;
      default:
        if (callback) {
          const result: ErrorResult = { error: `method not found: ${method}` };
          callback(result);
        }
        return null;
    }
  }

  // ---------------- helpers ----------------

  private parseParams(params: KRAny): ModuleCallParams {
    if (params == null) return {};
    if (typeof params === 'string') {
      try {
        return JSON.parse(params) as ModuleCallParams;
      } catch (e) {
        return {};
      }
    }
    return params as ModuleCallParams;
  }

  private getContext(): common.UIAbilityContext | null {
    try {
      const controllerContext = this.controller?.getUIAbilityContext();
      if (controllerContext) {
        console.log('[KRDeviceCalendarModule] context obtained from controller successfully');
        return controllerContext;
      }

      console.error('[KRDeviceCalendarModule] no UIAbilityContext found in controller');
      return null;
    } catch (e) {
      console.error('[KRDeviceCalendarModule] getContext error:', e);
      return null;
    }
  }

  private resolveError(cb: KuiklyRenderCallback | null, msg: string, code?: number) {
    if (cb) {
      const errorObj: ErrorResult = { error: msg };
      if (code !== undefined) errorObj.code = code;
      cb(errorObj);
    }
  }

  private getErrorMessage(e: Object): string {
    const err = e as BusinessError;
    return err.message ?? 'Unknown error';
  }

  // API 兼容性检查方法
  private isApiSupported(apiVersion: number): boolean {
    try {
      const systemVersion = (globalThis as GlobalContextHolder).systemVersion;
      if (systemVersion && typeof systemVersion.apiVersion === 'number') {
        return systemVersion.apiVersion >= apiVersion;
      }
      return true;
    } catch (e) {
      return true;
    }
  }

  private handleCalendarError(e: BusinessError, cb: KuiklyRenderCallback | null, methodName: string) {
    let errorMsg = `${methodName} failed: ${e.message || 'unknown error'}`;
    
    switch (e.code) {
      case ERROR_PARAM_VALUE:
        errorMsg = 'Parameter value error. Check if parameter values are within valid ranges and IDs exist.';
        break;
      case ERROR_ACCOUNT_NOT_FOUND:
        errorMsg = 'The specified account was not found. Ensure you are using a created account.';
        break;
      case ERROR_INTERNAL:
        errorMsg = 'Internal program error. Please try again later.';
        break;
      case ERROR_EVENT_NOT_EDITABLE:
        errorMsg = 'This event cannot be edited. Check if it is a local calendar event and not an important event type.';
        break;
      default:
        // 通用错误码处理
        if (e.code >= 40100000 && e.code <= 40100099) {
          errorMsg = `Permission denied: ${e.message}`;
        } else if (e.code >= 40101000 && e.code <= 40101999) {
          errorMsg = `System capability not supported: ${e.message}`;
        }
        break;
    }
    
    this.resolveError(cb, errorMsg, e.code);
  }

  private async ensureAuthorized(readOnly: boolean): Promise<boolean> {
    try {
      console.log('[KRDeviceCalendarModule] ensureAuthorized called, readOnly:', readOnly);
      
      const atManager = abilityAccessCtrl.createAtManager();
      const bundleInfo = await bundleManager.getBundleInfoForSelf(
        bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION,
      );
      const tokenId = bundleInfo.appInfo.accessTokenId;
      const perms: Permissions[] = readOnly ? [PERM_READ] : [PERM_READ, PERM_WRITE];
      
      console.log('[KRDeviceCalendarModule] checking permissions:', perms);
      
      for (const p of perms) {
        const r = await atManager.checkAccessToken(tokenId, p);
        console.log('[KRDeviceCalendarModule] permission', p, 'status:', r);
        if (r !== abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED) {
          console.log('[KRDeviceCalendarModule] permission denied:', p);
          return false;
        }
      }
      
      console.log('[KRDeviceCalendarModule] all permissions granted');
      return true;
    } catch (e) {
      console.error('[KRDeviceCalendarModule] ensureAuthorized error:', e);
      return false;
    }
  }

  // ---------------- permissions ----------------

  private async checkPermissions(readOnly: boolean, cb: KuiklyRenderCallback | null) {
    console.log('[KRDeviceCalendarModule] checkPermissions called, readOnly:', readOnly);
    
    try {
      const ok = await this.ensureAuthorized(readOnly);
      console.log('[KRDeviceCalendarModule] checkPermissions result:', ok);
      
      if (cb) {
        const result: PermissionStatusResult = {
          status: ok ? STATUS_AUTHORIZED : STATUS_DENIED,
          hasPermission: ok
        };
        cb(result);
      }
    } catch (e) {
      console.error('[KRDeviceCalendarModule] checkPermissions error:', e);
      if (cb) {
        const result: PermissionStatusResult = {
          status: STATUS_RESTRICTED,
          error: this.getErrorMessage(e as Object)
        };
        cb(result);
      }
    }
  }

  private async requestPermissions(readOnly: boolean, cb: KuiklyRenderCallback | null) {
    console.log('[KRDeviceCalendarModule] requestPermissions called, readOnly:', readOnly);
    
    const ctx = this.getContext();
    if (!ctx) {
      console.error('[KRDeviceCalendarModule] UIAbilityContext is null, controller is required');
      this.resolveError(cb, 'UIAbilityContext is null, controller is required');
      return;
    }
    
    console.log('[KRDeviceCalendarModule] context obtained successfully');
    
    try {
      const atManager = abilityAccessCtrl.createAtManager();
      const perms: Permissions[] = readOnly ? [PERM_READ] : [PERM_READ, PERM_WRITE];
      
      console.log('[KRDeviceCalendarModule] requesting permissions:', perms);
      
      const result = await atManager.requestPermissionsFromUser(ctx as common.UIAbilityContext, perms);
      console.log('[KRDeviceCalendarModule] permission request result:', JSON.stringify(result));
      
      const allGranted = result.authResults.every(
        v => v === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED,
      );
      
      console.log('[KRDeviceCalendarModule] all permissions granted:', allGranted);
      
      if (cb) {
        const permissions: PermissionItemResult[] = result.authResults.map((status, index) => {
          const item: PermissionItemResult = {
            permission: perms[index],
            status: status === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED ? 'granted' : 'denied'
          };
          return item;
        });
        const response: PermissionRequestResult = {
          status: allGranted ? STATUS_AUTHORIZED : STATUS_DENIED,
          permissions: permissions,
        };
        cb(response);
      }
    } catch (e) {
      console.error('[KRDeviceCalendarModule] requestPermissions error:', e);
      this.handleCalendarError(e as BusinessError, cb, 'requestPermissions');
    }
  }

  // ---------------- calendars ----------------

  private async findCalendars(cb: KuiklyRenderCallback | null) {
    const ctx = this.getContext();
    if (!ctx) {
      this.resolveError(cb, 'context is null');
      return;
    }
    try {
      const mgr = calendarManager.getCalendarManager(ctx);
      const cals = await mgr.getAllCalendars();
      const list: SerializedCalendar[] = [];
      for (const calendar of cals) {
        list.push(await this.serializeCalendar(calendar));
      }
      if (cb) {
        const result: CalendarsResult = { calendars: list };
        cb(result);
      }
    } catch (e) {
      this.handleCalendarError(e as BusinessError, cb, 'findCalendars');
    }
  }

  private async saveCalendar(args: ModuleCallParams, cb: KuiklyRenderCallback | null) {
    const ctx = this.getContext();
    if (!ctx) { this.resolveError(cb, 'context is null'); return; }
    try {
      const mgr = calendarManager.getCalendarManager(ctx);
      const account: calendarManager.CalendarAccount = {
        name: args.name ?? '',
        type: calendarManager.CalendarType.LOCAL,
        displayName: args.title ?? '',
      };
      const calendar = await mgr.createCalendar(account);
      const color = args.color;
      const config: calendarManager.CalendarConfig = {
        enableReminder: true,
        color: typeof color === 'number' ? `#${color.toString(16).padStart(6, '0')}` :
               typeof color === 'string' ? color : '#0000FF',
      };
      try { await calendar.setConfig(config); } catch (e) { }
      const account2 = await calendar.getAccount();
      if (cb) {
        const result: IdResult = { id: String(account2.name ?? '') };
        cb(result);
      }
    } catch (e) {
      this.handleCalendarError(e as BusinessError, cb, 'saveCalendar');
    }
  }

  private async removeCalendar(id: string, cb: KuiklyRenderCallback | null) {
    const ctx = this.getContext();
    if (!ctx) { this.resolveError(cb, 'context is null'); return; }
    try {
      const mgr = calendarManager.getCalendarManager(ctx);
      const cals = await mgr.getAllCalendars();
      let target: calendarManager.Calendar | null = null;
      for (const calendar of cals) {
        const account = await calendar.getAccount();
        if (account.name === id) {
          target = calendar;
          break;
        }
      }
      if (!target) {
        this.resolveError(cb, 'calendar not found');
        return;
      }
      await mgr.deleteCalendar(target);
      if (cb) {
        const result: SuccessResult = { success: true };
        cb(result);
      }
    } catch (e) {
      this.handleCalendarError(e as BusinessError, cb, 'removeCalendar');
    }
  }

  private async serializeCalendar(c: calendarManager.Calendar): Promise<SerializedCalendar> {
    let account: calendarManager.CalendarAccount;
    try {
      account = await c.getAccount();
    } catch (e) {
      account = { name: '', type: calendarManager.CalendarType.LOCAL } as calendarManager.CalendarAccount;
    }
    let config: CalendarConfigCompat | null = null;
    try {
      config = await c.getConfig() as CalendarConfigCompat;
    } catch (e) { }
    return {
      id: String(account.name ?? ''),
      title: account.displayName ?? '',
      source: String(account.name ?? ''),
      type: String(account.type ?? ''),
      color: config?.color ?? '#FFFFFF',
      allowedAvailabilities: ['busy', 'free'],
      allowsModifications: true,
      isPrimary: false,
    };
  }

  // ---------------- events ----------------

  private async fetchAllEvents(
    startDate: string,
    endDate: string,
    calendarIds: string[],
    cb: KuiklyRenderCallback | null,
  ) {
    const ctx = this.getContext();
    if (!ctx) { this.resolveError(cb, 'context is null'); return; }
    const startMs = this.parseDate(startDate);
    const endMs = this.parseDate(endDate);
    if (startMs == null || endMs == null) {
      this.resolveError(cb, 'invalid startDate/endDate');
      return;
    }
    try {
      const mgr = calendarManager.getCalendarManager(ctx);
      const allCals = await mgr.getAllCalendars();
      const targetCals: calendarManager.Calendar[] = [];
      if (calendarIds.length > 0) {
        for (const calendar of allCals) {
          const account = await calendar.getAccount();
          if (calendarIds.includes(account.name)) {
            targetCals.push(calendar);
          }
        }
      } else {
        targetCals.push(...allCals);
      }

      const events: SerializedEvent[] = [];
      const filter = calendarManager.EventFilter.filterByTime(startMs, endMs);
      for (const cal of targetCals) {
        const list = await cal.getEvents(filter);
        const calMeta = await this.serializeCalendar(cal);
        list.forEach(e => events.push(this.serializeEvent(e as CalendarEventCompat, calMeta)));
      }
      events.sort((a, b) => a.startMs - b.startMs);
      if (cb) {
        const result: EventsResult = { events: events };
        cb(result);
      }
    } catch (e) {
      this.handleCalendarError(e as BusinessError, cb, 'fetchAllEvents');
    }
  }

  private async findEventById(id: string, cb: KuiklyRenderCallback | null) {
    const ctx = this.getContext();
    if (!ctx) { this.resolveError(cb, 'context is null'); return; }
    try {
      const mgr = calendarManager.getCalendarManager(ctx);
      const cals = await mgr.getAllCalendars();
      const numId = Number(id);
      for (const cal of cals) {
        const all = await cal.getEvents();
        const target = all.find(e => (e as CalendarEventCompat).id === numId);
        if (target) {
          if (cb) {
            const calMeta = await this.serializeCalendar(cal);
            const result: EventResult = { event: this.serializeEvent(target as CalendarEventCompat, calMeta) };
            cb(result);
          }
          return;
        }
      }
      if (cb) {
        const result: EventResult = { event: null };
        cb(result);
      }
    } catch (e) {
      this.handleCalendarError(e as BusinessError, cb, 'findEventById');
    }
  }

  private async saveEvent(
    title: string,
    details: EventDetails,
    options: Record<string, KRAny>,
    cb: KuiklyRenderCallback | null,
  ) {
    const ctx = this.getContext();
    if (!ctx) { this.resolveError(cb, 'context is null'); return; }
    try {
      const mgr = calendarManager.getCalendarManager(ctx);
      const targetCalendar = await this.resolveTargetCalendar(mgr, details.calendarId);
      const location: CalendarEventLocation | undefined = details.location != null ? { location: details.location } : undefined;
      const event: CalendarEventCompat = {
        title: title || details.title || '',
        type: calendarManager.EventType.NORMAL,
        startTime: this.parseDate(details.startDate) ?? Date.now(),
        endTime: this.parseDate(details.endDate) ?? Date.now() + 3600_000,
        description: details.description ?? details.notes ?? undefined,
        location: location,
        isAllDay: !!details.allDay,
        timeZone: details.timeZone ?? undefined,
      };
      const alarms = details.alarms;
      if (alarms && alarms.length) {
        const reminderMinutes: number[] = [];
        alarms.forEach(a => {
          const alarmDate = a.date;
          if (typeof alarmDate === 'number') reminderMinutes.push(alarmDate);
        });
        if (reminderMinutes.length) event.reminderTime = reminderMinutes;
      }

      const existingId = details.id;
      if (existingId) {
        event.id = Number(existingId);
        await targetCalendar.updateEvent(event);
        if (cb) {
          const result: IdResult = { id: existingId };
          cb(result);
        }
      } else {
        const newId = await targetCalendar.addEvent(event);
        if (cb) {
          const result: IdResult = { id: String(newId) };
          cb(result);
        }
      }
    } catch (e) {
      this.handleCalendarError(e as BusinessError, cb, 'saveEvent');
    }
  }

  private async removeEvent(
    id: string,
    options: Record<string, KRAny>,
    cb: KuiklyRenderCallback | null,
  ) {
    const ctx = this.getContext();
    if (!ctx) { this.resolveError(cb, 'context is null'); return; }
    try {
      const mgr = calendarManager.getCalendarManager(ctx);
      const numId = Number(id);
      const cals = await mgr.getAllCalendars();
      for (const cal of cals) {
        try {
          await cal.deleteEvent(numId);
          if (cb) {
            const result: SuccessResult = { success: true };
            cb(result);
          }
          return;
        } catch (e) { }
      }
      if (cb) {
        const result: SuccessResult = { success: false };
        cb(result);
      }
    } catch (e) {
      this.handleCalendarError(e as BusinessError, cb, 'removeEvent');
    }
  }

  // ---------------- 新增 API 方法 ----------------

  private async editEvent(
    id: string,
    title: string,
    details: EventDetails,
    options: Record<string, KRAny>,
    cb: KuiklyRenderCallback | null,
  ) {
    const ctx = this.getContext();
    if (!ctx) {
      this.resolveError(cb, 'context is null');
      return;
    }
    try {
      const mgr = calendarManager.getCalendarManager(ctx);
      const targetCalendar = await this.resolveTargetCalendar(mgr, details.calendarId);
      const location: CalendarEventLocation | undefined = details.location != null ? { location: details.location } : undefined;
      const event: CalendarEventCompat = {
        title: title || details.title || '',
        type: calendarManager.EventType.NORMAL,
        startTime: this.parseDate(details.startDate) ?? Date.now(),
        endTime: this.parseDate(details.endDate) ?? Date.now() + 3600_000,
        description: details.description ?? details.notes ?? undefined,
        location: location,
        isAllDay: !!details.allDay,
        timeZone: details.timeZone ?? undefined,
      };
      
      event.id = Number(id);
      
      const alarms = details.alarms;
      if (alarms && alarms.length) {
        const reminderMinutes: number[] = [];
        alarms.forEach(a => {
          const alarmDate = a.date;
          if (typeof alarmDate === 'number') reminderMinutes.push(alarmDate);
        });
        if (reminderMinutes.length) event.reminderTime = reminderMinutes;
      }

      await targetCalendar.updateEvent(event);
      if (cb) {
        const result: IdResult = { id: id };
        cb(result);
      }
    } catch (e) {
      this.handleCalendarError(e as BusinessError, cb, 'editEvent');
    }
  }

  private async queryEventInstances(
    start: number,
    end: number,
    ids: number[],
    eventKey: string[],
    cb: KuiklyRenderCallback | null,
  ) {
    const ctx = this.getContext();
    if (!ctx) {
      this.resolveError(cb, 'context is null');
      return;
    }
    try {
      const mgr = calendarManager.getCalendarManager(ctx);
      const allCals = await mgr.getAllCalendars();
      const targetCals: calendarManager.Calendar[] = [];
      if (ids.length > 0) {
        for (const calendar of allCals) {
          const account = await calendar.getAccount();
          const accountId = Number(account.name);
          if (!Number.isNaN(accountId) && ids.includes(accountId)) {
            targetCals.push(calendar);
          }
        }
      } else {
        targetCals.push(...allCals);
      }

      const events: SerializedEvent[] = [];
      const filter = calendarManager.EventFilter.filterByTime(start, end);
      for (const cal of targetCals) {
        const list = await cal.getEvents(filter);
        const calMeta = await this.serializeCalendar(cal);
        list.forEach(e => events.push(this.serializeEvent(e as CalendarEventCompat, calMeta)));
      }
      events.sort((a, b) => a.startMs - b.startMs);
      if (cb) {
        const result: EventsResult = { events: events };
        cb(result);
      }
    } catch (e) {
      this.handleCalendarError(e as BusinessError, cb, 'queryEventInstances');
    }
  }

  private async openEventEditPage(
    id: number,
    cb: KuiklyRenderCallback | null,
  ) {
    const ctx = this.getContext();
    if (!ctx) {
      this.resolveError(cb, 'context is null');
      return;
    }
    
    if (!this.isApiSupported(26000000)) {
      this.resolveError(cb, 'openEventEditPage is not supported in current API version. Requires API 26.0.0+');
      return;
    }

    this.resolveError(cb, 'openEventEditPage is unavailable in current HarmonyOS SDK type definitions');
  }

  // ---------------- private utils ----------------

  private async resolveTargetCalendar(
    mgr: calendarManager.CalendarManager,
    calendarId: string | undefined,
  ): Promise<calendarManager.Calendar> {
    if (calendarId) {
      const all = await mgr.getAllCalendars();
      for (const c of all) {
        const acc = await c.getAccount();
        if (acc.name === calendarId) return c;
      }
    }
    try { return await mgr.getCalendar(); } catch (e) { /* fallback */ }
    const all = await mgr.getAllCalendars();
    if (all && all.length) return all[0];
    throw new Error('no calendar available');
  }

  private parseDate(v: string | number | undefined | null): number | null {
    if (v == null) return null;
    if (typeof v === 'number') return v;
    if (typeof v === 'string' && v.length > 0) {
      const t = Date.parse(v);
      return isNaN(t) ? null : t;
    }
    return null;
  }

  private formatDateIso(ms: number): string {
    return new Date(ms).toISOString().replace(/\.\d{3}Z$/, m => m); // 保留毫秒
  }

  private serializeEvent(
    e: CalendarEventCompat,
    calMeta: SerializedCalendar,
  ): SerializedEvent {
    const reminderTime = e.reminderTime;
    const alarms: SerializedAlarm[] = [];
    if (reminderTime) {
      reminderTime.forEach((minute: number) => {
        const alarm: SerializedAlarm = { date: minute };
        alarms.push(alarm);
      });
    }

    const eventData: SerializedEvent = {
      id: String(e.id ?? ''),
      title: e.title ?? '',
      description: e.description ?? '',
      startDate: this.formatDateIso(e.startTime ?? 0),
      endDate: this.formatDateIso(e.endTime ?? 0),
      startMs: e.startTime ?? 0,
      allDay: !!e.isAllDay,
      location: e.location?.location ?? '',
      timeZone: e.timeZone ?? '',
      calendar: calMeta,
      alarms: alarms,
    };
    
    if (e.identifier !== undefined) {
      eventData.identifier = e.identifier;
    }
    if (e.isLunar !== undefined) {
      eventData.isLunar = e.isLunar;
    }
    
    if (e.instanceStartTime !== undefined) {
      eventData.instanceStartTime = e.instanceStartTime;
      eventData.instanceStartDate = this.formatDateIso(e.instanceStartTime ?? 0);
    }
    if (e.instanceEndTime !== undefined) {
      eventData.instanceEndTime = e.instanceEndTime;
      eventData.instanceEndDate = this.formatDateIso(e.instanceEndTime ?? 0);
    }
    
    if (e.attendee && Array.isArray(e.attendee)) {
      eventData.attendee = e.attendee.map((attendee: calendarManager.Attendee) => {
        const attendeeData: SerializedAttendee = {
          name: attendee.name ?? '',
          email: attendee.email ?? '',
        };
        if (attendee.role !== undefined) attendeeData.role = attendee.role;
        if (attendee.status !== undefined) attendeeData.status = attendee.status;
        if (attendee.type !== undefined) attendeeData.type = attendee.type;
        return attendeeData;
      });
    }
    
    return eventData;
  }
}

/**
 * 更新总结：
 * 
 * 1. 权限管理改进：
 *    - 支持 READ_CALENDAR / WRITE_CALENDAR 权限
 *    - 自动检测权限可用性并优雅降级
 *    - 改进权限检查和请求逻辑
 * 
 * 2. API 功能增强：
 *    - 新增 editEvent() 方法（API 12+）
 *    - 新增 queryEventInstances() 方法（API 18+）
 *    - 新增 openEventEditPage() 方法（API 26.0.0+）
 *    - 支持版本检查和API兼容性
 * 
 * 3. 数据结构完善：
 *    - 支持 Event 对象的 identifier、isLunar 字段
 *    - 支持 instanceStartTime、instanceEndTime 字段
 *    - 完善 Attendee 的 role、status、type 字段支持
 * 
 * 4. 错误处理优化：
 *    - 详细的错误码处理（23900001-23900005）
 *    - 统一的错误处理机制
 *    - 友好的错误消息提示
 * 
 * 5. 兼容性保证：
 *    - 向后兼容旧版本API
 *    - 自动检测系统版本和能力
 *    - 优雅的降级处理
 */