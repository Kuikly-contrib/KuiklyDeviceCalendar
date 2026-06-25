#import "KRDeviceCalendarModule.h"
#import <OpenKuiklyIOSRender/NSObject+KR.h>
#import <EventKit/EventKit.h>
#import <UIKit/UIKit.h>

#pragma mark - Helpers

static NSDateFormatter *KRDC_DateFormatter(void) {
    static NSDateFormatter *df;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        df = [[NSDateFormatter alloc] init];
        df.timeZone = [NSTimeZone timeZoneWithName:@"UTC"];
        df.locale = [NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"];
        df.dateFormat = @"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
    });
    return df;
}

static NSDate * _Nullable KRDC_ParseDate(id _Nullable value) {
    if ([value isKindOfClass:[NSString class]]) {
        return [KRDC_DateFormatter() dateFromString:(NSString *)value];
    }
    if ([value isKindOfClass:[NSNumber class]]) {
        return [NSDate dateWithTimeIntervalSince1970:[(NSNumber *)value doubleValue] / 1000.0];
    }
    return nil;
}

static NSString *KRDC_HexFromCGColor(CGColorRef cgColor) {
    if (cgColor == NULL) return @"#FFFFFF";
    const CGFloat *c = CGColorGetComponents(cgColor);
    CGFloat r = 1, g = 1, b = 1;
    size_t count = CGColorGetNumberOfComponents(cgColor);
    if (count >= 3 && c) {
        r = c[0]; g = c[1]; b = c[2];
    } else if (count >= 1 && c) {
        r = g = b = c[0];
    }
    return [NSString stringWithFormat:@"#%02lX%02lX%02lX",
            (unsigned long)lroundf((float)r * 255.0f),
            (unsigned long)lroundf((float)g * 255.0f),
            (unsigned long)lroundf((float)b * 255.0f)];
}

#pragma mark - Module

@interface KuiklyDeviceCalendarModule ()
@property (nonatomic, strong) EKEventStore *eventStore;
@property (nonatomic, strong) dispatch_queue_t workQueue;
@end

@implementation KuiklyDeviceCalendarModule

@synthesize hr_rootView;

- (instancetype)init {
    self = [super init];
    if (self) {
        _eventStore = [[EKEventStore alloc] init];
        _workQueue = dispatch_queue_create("com.tencent.kuikly.devicecalendar.queue", DISPATCH_QUEUE_SERIAL);
    }
    return self;
}

#pragma mark - Common

- (NSDictionary *)paramsFromArgs:(NSDictionary *)args {
    id raw = args[KR_PARAM_KEY];
    if ([raw isKindOfClass:[NSString class]]) {
        return [raw hr_stringToDictionary] ?: @{};
    }
    if ([raw isKindOfClass:[NSDictionary class]]) {
        return raw;
    }
    return @{};
}

- (KuiklyRenderCallback _Nullable)callbackFromArgs:(NSDictionary *)args {
    id cb = args[KR_CALLBACK_KEY];
    if ([cb isKindOfClass:NSClassFromString(@"NSBlock")]) {
        return cb;
    }
    return nil;
}

- (BOOL)isAuthorized {
    EKAuthorizationStatus status = [EKEventStore authorizationStatusForEntityType:EKEntityTypeEvent];
    return status == EKAuthorizationStatusAuthorized;
}

- (void)resolveError:(KuiklyRenderCallback _Nullable)cb message:(NSString *)msg {
    if (cb) cb(@{ @"error": msg ?: @"unknown error" });
}

#pragma mark - Permissions

- (void)checkPermissions:(NSDictionary *)args {
    KuiklyRenderCallback cb = [self callbackFromArgs:args];
    EKAuthorizationStatus s = [EKEventStore authorizationStatusForEntityType:EKEntityTypeEvent];
    NSString *status;
    switch (s) {
        case EKAuthorizationStatusDenied: status = @"denied"; break;
        case EKAuthorizationStatusRestricted: status = @"restricted"; break;
        case EKAuthorizationStatusAuthorized: status = @"authorized"; break;
        default: status = @"undetermined"; break;
    }
    if (cb) cb(@{ @"status": status });
}

- (void)requestPermissions:(NSDictionary *)args {
    KuiklyRenderCallback cb = [self callbackFromArgs:args];
    [self.eventStore requestAccessToEntityType:EKEntityTypeEvent
                                    completion:^(BOOL granted, NSError * _Nullable error) {
        if (error) {
            if (cb) cb(@{ @"error": error.localizedDescription ?: @"authorization error" });
            return;
        }
        if (cb) cb(@{ @"status": granted ? @"authorized" : @"denied" });
    }];
}

#pragma mark - Calendars

- (void)findCalendars:(NSDictionary *)args {
    KuiklyRenderCallback cb = [self callbackFromArgs:args];
    if (![self isAuthorized]) {
        [self resolveError:cb message:@"unauthorized to access calendar"];
        return;
    }
    __weak typeof(self) ws = self;
    dispatch_async(self.workQueue, ^{
        @try {
            __strong typeof(ws) ss = ws;
            NSArray<EKCalendar *> *cals = [ss.eventStore calendarsForEntityType:EKEntityTypeEvent];
            NSMutableArray *arr = [NSMutableArray array];
            EKCalendar *defaultCal = [ss.eventStore defaultCalendarForNewEvents];
            for (EKCalendar *cal in cals) {
                BOOL isPrimary = [cal isEqual:defaultCal];
                [arr addObject:@{
                    @"id": cal.calendarIdentifier ?: @"",
                    @"title": cal.title ?: @"",
                    @"allowsModifications": @(cal.allowsContentModifications),
                    @"source": (cal.source && cal.source.title) ? cal.source.title : @"",
                    @"isPrimary": @(isPrimary),
                    @"allowedAvailabilities": [ss availabilitiesFromMask:cal.supportedEventAvailabilities],
                    @"color": KRDC_HexFromCGColor(cal.CGColor),
                }];
            }
            if (cb) cb(@{ @"calendars": arr });
        } @catch (NSException *e) {
            [self resolveError:cb message:e.reason];
        }
    });
}

- (void)saveCalendar:(NSDictionary *)args {
    KuiklyRenderCallback cb = [self callbackFromArgs:args];
    NSDictionary *params = [self paramsFromArgs:args];
    if (![self isAuthorized]) {
        [self resolveError:cb message:@"unauthorized to access calendar"];
        return;
    }
    __weak typeof(self) ws = self;
    dispatch_async(self.workQueue, ^{
        @try {
            __strong typeof(ws) ss = ws;
            NSString *title = params[@"title"];
            NSString *type = params[@"entityType"]; // event | reminder
            id color = params[@"color"];

            // 找 source
            EKSource *src = nil;
            for (EKSource *s in ss.eventStore.sources) {
                if (s.sourceType == EKSourceTypeCalDAV && [s.title isEqualToString:@"iCloud"]) {
                    src = s; break;
                }
            }
            if (!src) {
                for (EKSource *s in ss.eventStore.sources) {
                    if (s.sourceType == EKSourceTypeLocal || s.sourceType == EKSourceTypeSubscribed) {
                        src = s;
                        if (s.sourceType == EKSourceTypeLocal) break;
                    }
                }
            }
            if (!src) {
                [self resolveError:cb message:@"no source found to create the calendar"];
                return;
            }
            EKCalendar *calendar = nil;
            if ([type isEqualToString:@"reminder"]) {
                calendar = [EKCalendar calendarForEntityType:EKEntityTypeReminder eventStore:ss.eventStore];
            } else {
                calendar = [EKCalendar calendarForEntityType:EKEntityTypeEvent eventStore:ss.eventStore];
            }
            calendar.source = src;
            if (title) calendar.title = title;
            if ([color isKindOfClass:[NSNumber class]]) {
                UIColor *c = [UIColor colorWithRed:(((NSNumber *)color).integerValue >> 16 & 0xFF) / 255.0
                                              green:(((NSNumber *)color).integerValue >> 8 & 0xFF) / 255.0
                                               blue:(((NSNumber *)color).integerValue & 0xFF) / 255.0
                                              alpha:1.0];
                calendar.CGColor = c.CGColor;
            }
            NSError *err = nil;
            BOOL ok = [ss.eventStore saveCalendar:calendar commit:YES error:&err];
            if (ok) {
                if (cb) cb(@{ @"id": calendar.calendarIdentifier ?: @"" });
            } else {
                [self resolveError:cb message:err.localizedDescription ?: @"saveCalendar failed"];
            }
        } @catch (NSException *e) {
            [self resolveError:cb message:e.reason];
        }
    });
}

- (void)removeCalendar:(NSDictionary *)args {
    KuiklyRenderCallback cb = [self callbackFromArgs:args];
    NSDictionary *params = [self paramsFromArgs:args];
    NSString *calendarId = params[@"id"];
    if (![self isAuthorized]) {
        [self resolveError:cb message:@"unauthorized to access calendar"];
        return;
    }
    __weak typeof(self) ws = self;
    dispatch_async(self.workQueue, ^{
        @try {
            __strong typeof(ws) ss = ws;
            EKCalendar *cal = [ss.eventStore calendarWithIdentifier:calendarId];
            if (!cal) {
                [self resolveError:cb message:@"calendar not found"];
                return;
            }
            NSError *err = nil;
            BOOL ok = [ss.eventStore removeCalendar:cal commit:YES error:&err];
            if (err) {
                [self resolveError:cb message:err.localizedDescription];
                return;
            }
            if (cb) cb(@{ @"success": @(ok) });
        } @catch (NSException *e) {
            [self resolveError:cb message:e.reason];
        }
    });
}

#pragma mark - Events

- (void)fetchAllEvents:(NSDictionary *)args {
    KuiklyRenderCallback cb = [self callbackFromArgs:args];
    NSDictionary *params = [self paramsFromArgs:args];
    if (![self isAuthorized]) {
        [self resolveError:cb message:@"unauthorized to access calendar"];
        return;
    }
    NSDate *start = KRDC_ParseDate(params[@"startDate"]);
    NSDate *end = KRDC_ParseDate(params[@"endDate"]);
    NSArray *calendarIds = params[@"calendarIds"];
    if (!start || !end) {
        [self resolveError:cb message:@"invalid startDate/endDate"];
        return;
    }
    __weak typeof(self) ws = self;
    dispatch_async(self.workQueue, ^{
        @try {
            __strong typeof(ws) ss = ws;
            NSMutableArray<EKCalendar *> *cals = nil;
            if ([calendarIds isKindOfClass:[NSArray class]] && calendarIds.count > 0) {
                cals = [NSMutableArray array];
                NSArray *deviceCals = [ss.eventStore calendarsForEntityType:EKEntityTypeEvent];
                for (EKCalendar *c in deviceCals) {
                    if ([calendarIds containsObject:c.calendarIdentifier]) [cals addObject:c];
                }
            }
            NSPredicate *predicate = [ss.eventStore predicateForEventsWithStartDate:start
                                                                            endDate:end
                                                                          calendars:cals];
            NSArray *events = [[ss.eventStore eventsMatchingPredicate:predicate]
                               sortedArrayUsingSelector:@selector(compareStartDateWithEvent:)];
            NSMutableArray *out = [NSMutableArray array];
            for (EKEvent *e in events) {
                [out addObject:[ss serializeEvent:e]];
            }
            if (cb) cb(@{ @"events": out });
        } @catch (NSException *e) {
            [self resolveError:cb message:e.reason];
        }
    });
}

- (void)findEventById:(NSDictionary *)args {
    KuiklyRenderCallback cb = [self callbackFromArgs:args];
    NSDictionary *params = [self paramsFromArgs:args];
    if (![self isAuthorized]) {
        [self resolveError:cb message:@"unauthorized to access calendar"];
        return;
    }
    NSString *eventId = params[@"id"];
    __weak typeof(self) ws = self;
    dispatch_async(self.workQueue, ^{
        @try {
            __strong typeof(ws) ss = ws;
            EKEvent *e = (EKEvent *)[ss.eventStore calendarItemWithIdentifier:eventId];
            if (e) {
                if (cb) cb(@{ @"event": [ss serializeEvent:e] });
            } else {
                if (cb) cb(@{ @"event": [NSNull null] });
            }
        } @catch (NSException *ex) {
            [self resolveError:cb message:ex.reason];
        }
    });
}

- (void)saveEvent:(NSDictionary *)args {
    KuiklyRenderCallback cb = [self callbackFromArgs:args];
    NSDictionary *params = [self paramsFromArgs:args];
    if (![self isAuthorized]) {
        [self resolveError:cb message:@"unauthorized to access calendar"];
        return;
    }
    NSString *title = params[@"title"];
    NSDictionary *details = params[@"details"] ?: @{};
    NSDictionary *options = params[@"options"] ?: @{};
    __weak typeof(self) ws = self;
    dispatch_async(self.workQueue, ^{
        @try {
            __strong typeof(ws) ss = ws;
            NSDictionary *result = [ss buildAndSaveEvent:title details:details options:options];
            id success = result[@"success"];
            if (success && success != [NSNull null]) {
                if (cb) cb(@{ @"id": success });
            } else {
                [self resolveError:cb message:result[@"error"] ?: @"saveEvent failed"];
            }
        } @catch (NSException *e) {
            [self resolveError:cb message:e.reason];
        }
    });
}

- (void)removeEvent:(NSDictionary *)args {
    KuiklyRenderCallback cb = [self callbackFromArgs:args];
    NSDictionary *params = [self paramsFromArgs:args];
    if (![self isAuthorized]) {
        [self resolveError:cb message:@"unauthorized to access calendar"];
        return;
    }
    NSString *eventId = params[@"id"];
    NSDictionary *options = params[@"options"] ?: @{};
    __weak typeof(self) ws = self;
    dispatch_async(self.workQueue, ^{
        @try {
            __strong typeof(ws) ss = ws;
            BOOL futureEvents = [options[@"futureEvents"] boolValue];
            NSDate *exceptionDate = KRDC_ParseDate(options[@"exceptionDate"]);
            NSError *error = nil;
            BOOL success = NO;
            if (exceptionDate) {
                NSCalendar *cal = [NSCalendar currentCalendar];
                NSDate *endDate = [cal dateByAddingUnit:NSCalendarUnitDay value:1 toDate:exceptionDate options:0];
                NSPredicate *predicate = [ss.eventStore predicateForEventsWithStartDate:exceptionDate
                                                                                endDate:endDate
                                                                              calendars:nil];
                EKEvent *instance = nil;
                for (EKEvent *e in [ss.eventStore eventsMatchingPredicate:predicate]) {
                    if ([e.calendarItemIdentifier isEqualToString:eventId] && [e.startDate isEqualToDate:exceptionDate]) {
                        instance = e; break;
                    }
                }
                if (!instance) {
                    [self resolveError:cb message:@"No event found."];
                    return;
                }
                success = [ss.eventStore removeEvent:instance
                                                span:(futureEvents ? EKSpanFutureEvents : EKSpanThisEvent)
                                              commit:YES error:&error];
            } else {
                EKEvent *e = (EKEvent *)[ss.eventStore calendarItemWithIdentifier:eventId];
                if (!e) {
                    [self resolveError:cb message:@"event not found"];
                    return;
                }
                success = [ss.eventStore removeEvent:e
                                                span:(futureEvents ? EKSpanFutureEvents : EKSpanThisEvent)
                                              commit:YES error:&error];
            }
            if (error) {
                [self resolveError:cb message:error.localizedDescription];
                return;
            }
            if (cb) cb(@{ @"success": @(success) });
        } @catch (NSException *e) {
            [self resolveError:cb message:e.reason];
        }
    });
}

#pragma mark - Event builder

- (NSDictionary *)buildAndSaveEvent:(NSString *)title
                            details:(NSDictionary *)details
                            options:(NSDictionary *)options {
    NSMutableDictionary *resp = [@{ @"success": [NSNull null], @"error": [NSNull null] } mutableCopy];

    EKEvent *calendarEvent = nil;
    NSString *calendarId = details[@"calendarId"];
    NSString *eventId = details[@"id"];
    NSString *eventTitle = title ?: details[@"title"];
    NSString *location = details[@"location"];
    NSDate *startDate = KRDC_ParseDate(details[@"startDate"]);
    NSDate *endDate = KRDC_ParseDate(details[@"endDate"]);
    NSNumber *allDay = details[@"allDay"];
    NSString *notes = details[@"notes"];
    NSString *url = details[@"url"];
    NSArray *alarms = details[@"alarms"];
    NSString *recurrence = details[@"recurrence"];
    NSDictionary *recurrenceRule = details[@"recurrenceRule"];
    NSString *availability = details[@"availability"];
    NSString *timeZone = details[@"timeZone"];

    if (eventId.length > 0) {
        NSDate *exceptionDate = KRDC_ParseDate(options[@"exceptionDate"]);
        if (exceptionDate) {
            NSPredicate *predicate = [self.eventStore predicateForEventsWithStartDate:exceptionDate
                                                                              endDate:endDate ?: exceptionDate
                                                                            calendars:nil];
            for (EKEvent *e in [self.eventStore eventsMatchingPredicate:predicate]) {
                if ([e.calendarItemIdentifier isEqualToString:eventId] && [e.startDate isEqualToDate:exceptionDate]) {
                    calendarEvent = e; break;
                }
            }
        } else {
            calendarEvent = (EKEvent *)[self.eventStore calendarItemWithIdentifier:eventId];
        }
    } else {
        calendarEvent = [EKEvent eventWithEventStore:self.eventStore];
        calendarEvent.calendar = [self.eventStore defaultCalendarForNewEvents];
        calendarEvent.timeZone = [NSTimeZone defaultTimeZone];
        if (calendarId.length > 0) {
            EKCalendar *c = [self.eventStore calendarWithIdentifier:calendarId];
            if (c) calendarEvent.calendar = c;
        }
    }
    if (!calendarEvent) {
        resp[@"error"] = @"event not found";
        return resp;
    }

    if (timeZone.length > 0) calendarEvent.timeZone = [NSTimeZone timeZoneWithName:timeZone];
    if (eventTitle.length > 0) calendarEvent.title = eventTitle;
    if (location) calendarEvent.location = location;
    if (startDate) calendarEvent.startDate = startDate;
    if (endDate) calendarEvent.endDate = endDate;
    if (allDay) calendarEvent.allDay = allDay.boolValue;
    if (notes) calendarEvent.notes = notes;
    if (alarms) calendarEvent.alarms = [self buildAlarms:alarms];

    if (recurrence.length > 0) {
        EKRecurrenceRule *r = [self buildRule:recurrence interval:0 occurrence:0 endDate:nil days:nil weekPositionInMonth:0];
        if (r) calendarEvent.recurrenceRules = @[ r ];
    }
    if (recurrenceRule) {
        NSString *freq = recurrenceRule[@"frequency"];
        NSInteger interval = [recurrenceRule[@"interval"] integerValue];
        NSInteger occurrence = [recurrenceRule[@"occurrence"] integerValue];
        NSDate *rEnd = KRDC_ParseDate(recurrenceRule[@"endDate"]);
        NSArray *days = recurrenceRule[@"daysOfWeek"];
        NSInteger weekPos = [recurrenceRule[@"weekPositionInMonth"] integerValue];
        EKRecurrenceRule *r = [self buildRule:freq interval:interval occurrence:occurrence endDate:rEnd days:days weekPositionInMonth:weekPos];
        calendarEvent.recurrenceRules = r ? @[ r ] : nil;
    }
    if (availability) calendarEvent.availability = [self availabilityFromString:availability];
    if ([url isKindOfClass:[NSString class]]) {
        NSURL *u = [NSURL URLWithString:[url stringByAddingPercentEncodingWithAllowedCharacters:[NSCharacterSet URLQueryAllowedCharacterSet]]];
        if (u) calendarEvent.URL = u;
    }
    NSDictionary *structuredLocation = details[@"structuredLocation"];
    if ([structuredLocation isKindOfClass:[NSDictionary class]] && structuredLocation.count > 0) {
        NSDictionary *geo = structuredLocation[@"coords"];
        EKStructuredLocation *loc = [EKStructuredLocation locationWithTitle:structuredLocation[@"title"] ?: @""];
        if (geo) {
            loc.geoLocation = [[CLLocation alloc]
                               initWithLatitude:[geo[@"latitude"] doubleValue]
                               longitude:[geo[@"longitude"] doubleValue]];
        }
        loc.radius = [structuredLocation[@"radius"] doubleValue];
        calendarEvent.structuredLocation = loc;
    }

    NSDate *exceptionDate = KRDC_ParseDate(options[@"exceptionDate"]);
    EKSpan span = EKSpanFutureEvents;
    if (exceptionDate) {
        calendarEvent.startDate = exceptionDate;
        span = EKSpanThisEvent;
    }
    NSError *err = nil;
    BOOL ok = [self.eventStore saveEvent:calendarEvent span:span commit:YES error:&err];
    if (!ok) {
        resp[@"error"] = err.localizedDescription ?: @"saveEvent failed";
    } else {
        resp[@"success"] = calendarEvent.calendarItemIdentifier ?: @"";
    }
    return resp;
}

#pragma mark - Alarms

- (NSArray *)buildAlarms:(NSArray *)alarms {
    NSMutableArray *arr = [NSMutableArray array];
    for (NSDictionary *a in alarms) {
        if (![a isKindOfClass:[NSDictionary class]]) continue;
        id date = a[@"date"];
        EKAlarm *alarm;
        if ([date isKindOfClass:[NSString class]]) {
            alarm = [EKAlarm alarmWithAbsoluteDate:KRDC_ParseDate(date)];
        } else if ([date isKindOfClass:[NSNumber class]]) {
            alarm = [EKAlarm alarmWithRelativeOffset:60.0 * [date intValue]];
        } else {
            alarm = [[EKAlarm alloc] init];
        }
        NSDictionary *loc = a[@"structuredLocation"];
        if ([loc isKindOfClass:[NSDictionary class]] && loc.count > 0) {
            NSDictionary *geo = loc[@"coords"];
            EKStructuredLocation *sl = [EKStructuredLocation locationWithTitle:loc[@"title"] ?: @""];
            if (geo) {
                sl.geoLocation = [[CLLocation alloc]
                                  initWithLatitude:[geo[@"latitude"] doubleValue]
                                  longitude:[geo[@"longitude"] doubleValue]];
            }
            sl.radius = [loc[@"radius"] doubleValue];
            alarm.structuredLocation = sl;
            NSString *prox = loc[@"proximity"];
            if ([prox isEqualToString:@"enter"]) alarm.proximity = EKAlarmProximityEnter;
            else if ([prox isEqualToString:@"leave"]) alarm.proximity = EKAlarmProximityLeave;
            else alarm.proximity = EKAlarmProximityNone;
        }
        [arr addObject:alarm];
    }
    return arr;
}

#pragma mark - Recurrence

- (EKRecurrenceFrequency)frequencyFromName:(NSString *)name {
    if ([name isEqualToString:@"weekly"]) return EKRecurrenceFrequencyWeekly;
    if ([name isEqualToString:@"monthly"]) return EKRecurrenceFrequencyMonthly;
    if ([name isEqualToString:@"yearly"]) return EKRecurrenceFrequencyYearly;
    return EKRecurrenceFrequencyDaily;
}

- (NSString *)nameFromFrequency:(EKRecurrenceFrequency)f {
    switch (f) {
        case EKRecurrenceFrequencyWeekly: return @"weekly";
        case EKRecurrenceFrequencyMonthly: return @"monthly";
        case EKRecurrenceFrequencyYearly: return @"yearly";
        case EKRecurrenceFrequencyDaily: return @"daily";
        default: return @"";
    }
}

- (EKRecurrenceDayOfWeek *)dayFromString:(NSString *)day {
    NSDictionary *map = @{ @"SU": @1, @"MO": @2, @"TU": @3, @"WE": @4, @"TH": @5, @"FR": @6, @"SA": @7 };
    NSNumber *n = map[day];
    if (n) return [EKRecurrenceDayOfWeek dayOfWeek:n.integerValue];
    return nil;
}

- (NSMutableArray *)daysOfWeek:(NSArray *)days {
    if (!days.count) return nil;
    NSMutableArray *arr = [NSMutableArray array];
    for (NSString *d in days) {
        EKRecurrenceDayOfWeek *wd = [self dayFromString:d];
        if (wd) [arr addObject:wd];
    }
    return arr;
}

- (EKRecurrenceRule *)buildRule:(NSString *)frequency
                       interval:(NSInteger)interval
                     occurrence:(NSInteger)occurrence
                        endDate:(NSDate *)endDate
                           days:(NSArray *)days
            weekPositionInMonth:(NSInteger)weekPos {
    NSArray *valid = @[@"daily", @"weekly", @"monthly", @"yearly"];
    if (!frequency || ![valid containsObject:frequency]) return nil;
    EKRecurrenceEnd *end = nil;
    if (endDate) end = [EKRecurrenceEnd recurrenceEndWithEndDate:endDate];
    else if (occurrence > 0) end = [EKRecurrenceEnd recurrenceEndWithOccurrenceCount:occurrence];
    NSInteger ivl = interval > 1 ? interval : 1;
    NSMutableArray *setPositions = nil;
    if (weekPos > 0) setPositions = [@[ @(weekPos) ] mutableCopy];

    return [[EKRecurrenceRule alloc] initRecurrenceWithFrequency:[self frequencyFromName:frequency]
                                                        interval:ivl
                                                   daysOfTheWeek:[self daysOfWeek:days]
                                                  daysOfTheMonth:nil
                                                 monthsOfTheYear:nil
                                                  weeksOfTheYear:nil
                                                   daysOfTheYear:nil
                                                    setPositions:setPositions
                                                             end:end];
}

#pragma mark - Availability

- (NSArray *)availabilitiesFromMask:(EKCalendarEventAvailabilityMask)mask {
    NSMutableArray *arr = [NSMutableArray array];
    if (mask & EKCalendarEventAvailabilityBusy) [arr addObject:@"busy"];
    if (mask & EKCalendarEventAvailabilityFree) [arr addObject:@"free"];
    if (mask & EKCalendarEventAvailabilityTentative) [arr addObject:@"tentative"];
    if (mask & EKCalendarEventAvailabilityUnavailable) [arr addObject:@"unavailable"];
    return arr;
}

- (NSString *)availabilityString:(EKEventAvailability)v {
    switch (v) {
        case EKEventAvailabilityBusy: return @"busy";
        case EKEventAvailabilityFree: return @"free";
        case EKEventAvailabilityTentative: return @"tentative";
        case EKEventAvailabilityUnavailable: return @"unavailable";
        default: return @"notSupported";
    }
}

- (EKEventAvailability)availabilityFromString:(NSString *)s {
    if ([s isEqualToString:@"busy"]) return EKEventAvailabilityBusy;
    if ([s isEqualToString:@"free"]) return EKEventAvailabilityFree;
    if ([s isEqualToString:@"tentative"]) return EKEventAvailabilityTentative;
    if ([s isEqualToString:@"unavailable"]) return EKEventAvailabilityUnavailable;
    return EKEventAvailabilityNotSupported;
}

#pragma mark - Serialize event

- (NSDictionary *)serializeEvent:(EKEvent *)event {
    NSDateFormatter *df = KRDC_DateFormatter();
    NSMutableDictionary *m = [NSMutableDictionary dictionary];
    m[@"id"] = event.calendarItemIdentifier ?: @"";
    m[@"title"] = event.title ?: @"";
    m[@"notes"] = event.notes ?: @"";
    m[@"url"] = event.URL.absoluteString ?: @"";
    m[@"location"] = event.location ?: @"";
    m[@"timeZone"] = event.timeZone.name ?: @"";
    m[@"allDay"] = @(event.allDay);
    m[@"isDetached"] = @(event.isDetached);
    m[@"startDate"] = event.startDate ? [df stringFromDate:event.startDate] : @"";
    m[@"endDate"] = event.endDate ? [df stringFromDate:event.endDate] : @"";
    if (event.occurrenceDate) m[@"occurrenceDate"] = [df stringFromDate:event.occurrenceDate];
    m[@"availability"] = [self availabilityString:event.availability];

    if (event.calendar) {
        m[@"calendar"] = @{
            @"id": event.calendar.calendarIdentifier ?: @"tempCalendar",
            @"title": event.calendar.title ?: @"",
            @"source": (event.calendar.source && event.calendar.source.title) ? event.calendar.source.title : @"",
            @"allowsModifications": @(event.calendar.allowsContentModifications),
            @"allowedAvailabilities": [self availabilitiesFromMask:event.calendar.supportedEventAvailabilities],
            @"color": KRDC_HexFromCGColor(event.calendar.CGColor),
        };
    }

    // attendees
    @try {
        if (event.attendees) {
            NSMutableArray *atts = [NSMutableArray array];
            for (EKParticipant *p in event.attendees) {
                NSMutableDictionary *kv = [NSMutableDictionary dictionary];
                for (NSString *pair in [p.description componentsSeparatedByString:@";"]) {
                    NSArray *xy = [pair componentsSeparatedByString:@"="];
                    if (xy.count != 2) continue;
                    kv[[xy[0] stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]]] =
                        [xy[1] stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
                }
                NSString *email = kv[@"email"]; if (!email || [email isEqualToString:@"(null)"]) email = @"";
                NSString *phone = kv[@"phone"]; if (!phone || [phone isEqualToString:@"(null)"]) phone = @"";
                NSString *name = kv[@"name"]; if (!name || [name isEqualToString:@"(null)"]) name = @"";
                [atts addObject:@{ @"email": email, @"phone": phone, @"name": name }];
            }
            m[@"attendees"] = atts;
        }
    } @catch (NSException *e) { /* ignore */ }

    // alarms
    @try {
        if (event.hasAlarms) {
            NSMutableArray *list = [NSMutableArray array];
            for (EKAlarm *alarm in event.alarms) {
                NSMutableDictionary *d = [NSMutableDictionary dictionary];
                NSString *iso = nil;
                if (alarm.absoluteDate) {
                    iso = [df stringFromDate:alarm.absoluteDate];
                } else if (alarm.relativeOffset) {
                    NSDate *base = event.startDate ?: [NSDate date];
                    iso = [df stringFromDate:[NSDate dateWithTimeInterval:alarm.relativeOffset sinceDate:base]];
                }
                if (iso) d[@"date"] = iso;
                if (alarm.structuredLocation) {
                    NSString *prox = @"None";
                    if (alarm.proximity == EKAlarmProximityEnter) prox = @"enter";
                    else if (alarm.proximity == EKAlarmProximityLeave) prox = @"leave";
                    NSMutableDictionary *sl = [NSMutableDictionary dictionaryWithDictionary:@{
                        @"title": alarm.structuredLocation.title ?: @"",
                        @"proximity": prox,
                        @"radius": @(alarm.structuredLocation.radius),
                    }];
                    if (alarm.structuredLocation.geoLocation) {
                        sl[@"coords"] = @{
                            @"latitude": @(alarm.structuredLocation.geoLocation.coordinate.latitude),
                            @"longitude": @(alarm.structuredLocation.geoLocation.coordinate.longitude),
                        };
                    }
                    d[@"structuredLocation"] = sl;
                }
                [list addObject:d];
            }
            m[@"alarms"] = list;
        } else {
            m[@"alarms"] = @[];
        }
    } @catch (NSException *e) { /* ignore */ }

    // recurrence
    @try {
        if (event.hasRecurrenceRules && event.recurrenceRules.count > 0) {
            EKRecurrenceRule *r = event.recurrenceRules.firstObject;
            NSString *freq = [self nameFromFrequency:r.frequency];
            m[@"recurrence"] = freq;
            NSMutableDictionary *rule = [@{ @"frequency": freq } mutableCopy];
            if (r.interval) rule[@"interval"] = @(r.interval);
            if (r.recurrenceEnd.endDate) rule[@"endDate"] = [df stringFromDate:r.recurrenceEnd.endDate];
            if (r.recurrenceEnd.occurrenceCount) rule[@"occurrence"] = @(r.recurrenceEnd.occurrenceCount);
            m[@"recurrenceRule"] = rule;
        }
    } @catch (NSException *e) { /* ignore */ }

    // structured location
    @try {
        if (event.structuredLocation && event.structuredLocation.radius) {
            NSMutableDictionary *sl = [NSMutableDictionary dictionaryWithDictionary:@{
                @"title": event.structuredLocation.title ?: @"",
                @"radius": @(event.structuredLocation.radius),
            }];
            if (event.structuredLocation.geoLocation) {
                sl[@"coords"] = @{
                    @"latitude": @(event.structuredLocation.geoLocation.coordinate.latitude),
                    @"longitude": @(event.structuredLocation.geoLocation.coordinate.longitude),
                };
            }
            m[@"structuredLocation"] = sl;
        }
    } @catch (NSException *e) { /* ignore */ }

    return m;
}

#pragma mark - Android-only stubs（保持接口对齐，iOS 直接返回）

- (void)openEventInCalendar:(NSDictionary *)args {
    // iOS 无对应能力，留空
}

- (void)uriForCalendar:(NSDictionary *)args {
    KuiklyRenderCallback cb = [self callbackFromArgs:args];
    if (cb) cb(@{ @"uri": @"" });
}

@end
