import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { restoreTimezone, setTimezone, TIMEZONES } from '../../test/timezone';
import type { DayHours, OpeningHoursVersion } from '../types/branch';
import { addDays, hoursError, isoDayOfWeek, localIsoDate, nextWeekday, slotsOn, versionOn } from './branch-schedule';

/** Bản giờ mẫu: mọi ngày giống nhau, trừ các ngày ghi đè. */
function version(effectiveFrom: string, ranges: DayHours['ranges'], override: Record<number, DayHours['ranges']> = {}): OpeningHoursVersion {
  return { effectiveFrom, days: [1, 2, 3, 4, 5, 6, 7].map((d) => ({ dayOfWeek: d, ranges: override[d] ?? ranges })) };
}

// 2026-10-12 là thứ Hai, 2026-10-11 là Chủ nhật.
describe('dates', () => {
  it('numbers the week from Monday = 1 to Sunday = 7 like the BE', () => {
    expect(isoDayOfWeek('2026-10-12')).toBe(1);
    expect(isoDayOfWeek('2026-10-11')).toBe(7);
  });

  it('finds the next given weekday strictly after a date', () => {
    expect(nextWeekday('2026-10-12', 2)).toBe('2026-10-13');
    expect(nextWeekday('2026-10-13', 2)).toBe('2026-10-20');
  });

  it('adds days across a month end', () => {
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01');
    expect(addDays('2026-11-01', -1)).toBe('2026-10-31');
  });
});

// BE tính "hôm nay" theo Asia/Ho_Chi_Minh (TimeConfig); ngày lịch của máy không được lọt vào đây (T49 nhóm 2, hàng 10).
describe.each(TIMEZONES)('ngày theo giờ Việt Nam khi máy ở %s', (tz) => {
  beforeEach(() => {
    setTimezone(tz);
    vi.useFakeTimers({ toFake: ['Date'] });
  });
  afterEach(() => {
    vi.useRealTimers();
    restoreTimezone();
  });

  // Los Angeles và UTC lúc 00:01 giờ VN vẫn ở ngày hôm trước; Auckland lúc 23:59 giờ VN đã sang ngày hôm sau: hai hướng lệch đều có ca.
  it.each([
    ['2026-10-27T23:59:00+07:00', '2026-10-27'],
    ['2026-10-28T00:01:00+07:00', '2026-10-28'],
  ])('lúc %s giờ VN, hôm nay là %s', (instant, vnToday) => {
    vi.setSystemTime(new Date(instant));
    expect(localIsoDate()).toBe(vnToday);
  });

  it('localIsoDate(date) đọc thời điểm đã cho theo giờ VN, không theo ngày lịch của máy', () => {
    expect(localIsoDate(new Date('2026-10-27T16:59:59Z'))).toBe('2026-10-27');
    expect(localIsoDate(new Date('2026-10-27T17:00:00Z'))).toBe('2026-10-28');
  });

  it('addDays, isoDayOfWeek, nextWeekday là phép tính ngày lịch thuần, không lệch dù máy ở múi giờ nào', () => {
    expect(addDays('2026-10-27', 0)).toBe('2026-10-27');
    expect(addDays('2026-10-27', 1)).toBe('2026-10-28');
    expect(addDays('2026-10-31', 2)).toBe('2026-11-02'); // qua ngày Los Angeles lùi giờ (1/11)
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(isoDayOfWeek('2026-10-27')).toBe(2);
    expect(nextWeekday('2026-10-27', 2)).toBe('2026-11-03');
  });
});

describe('slotsOn (BR-LH-02: khung 30 phút nằm trọn trong một khoảng)', () => {
  it('steps 30 minutes from the opening time of the range and stops before closing', () => {
    const v = version('2026-01-01', [{ open: '08:15', close: '10:00' }]);
    expect(slotsOn([v], [], '2026-10-12')).toEqual(['08:15', '08:45', '09:15']);
  });

  it('keeps the slots of both ranges and none across the break', () => {
    const v = version('2026-01-01', [
      { open: '08:00', close: '09:00' },
      { open: '13:30', close: '14:30' },
    ]);
    expect(slotsOn([v], [], '2026-10-12')).toEqual(['08:00', '08:30', '13:30', '14:00']);
  });

  it('has no slot on a holiday or on a day without ranges', () => {
    const v = version('2026-01-01', [{ open: '08:00', close: '09:00' }], { 7: [] });
    expect(slotsOn([v], ['2026-10-12'], '2026-10-12')).toEqual([]);
    expect(slotsOn([v], [], '2026-10-11')).toEqual([]);
  });

  it('uses the version in force on that date', () => {
    const before = version('2026-01-01', [{ open: '08:00', close: '09:00' }]);
    const after = version('2026-10-13', [{ open: '10:00', close: '11:00' }]);
    expect(slotsOn([after, before], [], '2026-10-12')).toEqual(['08:00', '08:30']);
    expect(slotsOn([after, before], [], '2026-10-13')).toEqual(['10:00', '10:30']);
    expect(slotsOn([after], [], '2026-10-12')).toEqual([]);
    expect(versionOn([before, after], '2026-10-20')?.effectiveFrom).toBe('2026-10-13');
  });
});

describe('hoursError (BR-CN-02)', () => {
  const week = (override: Record<number, DayHours['ranges']>) => version('2026-10-12', [], override).days;

  it('accepts two ranges that only touch', () => {
    expect(hoursError(week({ 1: [{ open: '08:00', close: '12:00' }, { open: '12:00', close: '13:00' }] }))).toBeNull();
  });

  it.each([
    [{ 2: [{ open: '08:00', close: '12:00' }, { open: '11:00', close: '13:00' }] }, 'Thứ Ba: các khoảng giờ mở cửa không được chồng nhau'],
    [{ 1: [{ open: '12:00', close: '08:00' }] }, 'Thứ Hai: giờ mở cửa phải trước giờ đóng cửa'],
    [{ 1: [{ open: '08:00', close: '08:00' }] }, 'Thứ Hai: giờ mở cửa phải trước giờ đóng cửa'],
    [{ 7: [{ open: '07:00', close: '08:00' }, { open: '09:00', close: '10:00' }, { open: '11:00', close: '12:00' }] }, 'Chủ nhật có quá 2 khoảng giờ mở cửa'],
    [{ 5: [{ open: '', close: '12:00' }] }, 'Thứ Sáu: nhập đủ giờ mở và giờ đóng cửa'],
  ])('refuses %j', (override, message) => {
    expect(hoursError(week(override))).toBe(message);
  });
});
