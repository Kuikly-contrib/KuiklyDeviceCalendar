#import <Foundation/Foundation.h>
#import <OpenKuiklyIOSRender/KRBaseModule.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * iOS 原生 DeviceCalendar Module
 *
 * 对应 Kuikly 侧 DeviceCalendarModule(moduleName = KuiklyDeviceCalendarModule)。
 * 类名必须与 Kuikly 侧 moduleName() 返回值完全一致（KRBaseModule 运行时按类名查找）。
 */
@interface KuiklyDeviceCalendarModule : KRBaseModule

@end

NS_ASSUME_NONNULL_END
