import { describe, expect, it } from 'vitest';
import type { OpeningHoursVersion } from '../types/branch';
import { shiftOptions } from './shift-options';

// Giờ mở cửa mẫu của chi nhánh 1 (branch.mock.ts): thứ Hai–thứ Bảy 08:00–12:00 và 13:30–19:00, chủ nhật chỉ 08:00–12:00.
// Khoảng giờ là [mở, đóng): đúng giờ mở cửa là mở, đúng giờ đóng là đã đóng; nghỉ trưa là ngoài giờ.
const MORNING = { open: '08:00', close: '12:00' };
const AFTERNOON = { open: '13:30', close: '19:00' };
const SEED: OpeningHoursVersion[] = [
  { effectiveFrom: '2026-01-01', days: [1, 2, 3, 4, 5, 6, 7].map((d) => ({ dayOfWeek: d, ranges: d === 7 ? [MORNING] : [MORNING, AFTERNOON] })) },
];

const THURSDAY = '2026-10-22';
const SUNDAY = '2026-10-25';
const at = (date: string, hhmm: string) => Date.parse(`${date}T${hhmm}:00+07:00`);

const options = (nowMs: number, over: Partial<Parameters<typeof shiftOptions>[0]> = {}) =>
  shiftOptions({ hoursVersions: SEED, holidays: [], acceptsAfterHours: true, nowMs, ...over });

describe('shiftOptions — nút mở ca (BR-TG-05, BR-CN-05)', () => {
  it.each([
    ['07:59', false],
    ['08:00', true],
    ['11:59', true],
    ['12:00', false],
    ['13:29', false],
    ['13:30', true],
    ['18:59', true],
    ['19:00', false],
  ])('thứ Năm %s: ca thường %s, ca ngoài giờ ngược lại (chi nhánh có cờ)', (hhmm, open) => {
    expect(options(at(THURSDAY, hhmm))).toEqual({ normal: open, afterHours: !open });
  });

  it('chủ nhật chỉ có buổi sáng: 11:59 mở ca thường, chiều 15:00 chỉ mở ca ngoài giờ', () => {
    expect(options(at(SUNDAY, '11:59'))).toEqual({ normal: true, afterHours: false });
    expect(options(at(SUNDAY, '15:00'))).toEqual({ normal: false, afterHours: true });
  });

  it('ngày nghỉ của chi nhánh: giữa giờ làm vẫn không mở ca thường, chỉ mở ca ngoài giờ', () => {
    expect(options(at(THURSDAY, '10:00'), { holidays: [{ holidayDate: THURSDAY }] })).toEqual({ normal: false, afterHours: true });
  });

  it('ngày nghỉ của ngày khác không ảnh hưởng hôm nay', () => {
    expect(options(at(THURSDAY, '10:00'), { holidays: [{ holidayDate: '2026-10-23' }] })).toEqual({ normal: true, afterHours: false });
  });

  it('chi nhánh không có cờ: ngoài giờ không mở được ca nào và nói rõ vì sao', () => {
    const result = options(at(THURSDAY, '20:30'), { acceptsAfterHours: false });
    expect(result).toMatchObject({ normal: false, afterHours: false });
    expect(result.reason).toMatch(/ngoài giờ mở cửa/);
    expect(result.reason).toMatch(/không nhận cấp cứu ngoài giờ/);
  });

  it('chi nhánh không có cờ vẫn mở được ca thường trong giờ, không có lý do thừa', () => {
    expect(options(at(THURSDAY, '10:00'), { acceptsAfterHours: false })).toEqual({ normal: true, afterHours: false });
  });

  it('ngày nghỉ và chi nhánh không có cờ: lý do nhắc ngày nghỉ', () => {
    const result = options(at(THURSDAY, '10:00'), { acceptsAfterHours: false, holidays: [{ holidayDate: THURSDAY }] });
    expect(result).toMatchObject({ normal: false, afterHours: false });
    expect(result.reason).toMatch(/ngày nghỉ/);
  });

  it('dùng bản giờ có hiệu lực vào hôm nay, không dùng bản tương lai', () => {
    const later: OpeningHoursVersion = {
      effectiveFrom: '2026-10-23',
      days: [1, 2, 3, 4, 5, 6, 7].map((d) => ({ dayOfWeek: d, ranges: [{ open: '09:00', close: '10:00' }] })),
    };
    expect(options(at(THURSDAY, '10:30'), { hoursVersions: [...SEED, later] })).toEqual({ normal: true, afterHours: false });
    expect(options(at('2026-10-23', '10:30'), { hoursVersions: [...SEED, later] })).toEqual({ normal: false, afterHours: true });
  });

  it('tính ngày và giờ theo giờ Việt Nam, không theo múi giờ máy (23:30 UTC hôm trước đã là 06:30 sáng +07:00)', () => {
    // 2026-10-21T23:30Z = 2026-10-22 06:30 giờ Việt Nam: chưa mở cửa.
    expect(options(Date.parse('2026-10-21T23:30:00Z'))).toEqual({ normal: false, afterHours: true });
    // 2026-10-22T03:00Z = 10:00 giờ Việt Nam.
    expect(options(Date.parse('2026-10-22T03:00:00Z'))).toEqual({ normal: true, afterHours: false });
  });

  it('chưa có bản giờ nào: coi như đóng cửa (như BE)', () => {
    expect(options(at(THURSDAY, '10:00'), { hoursVersions: [] })).toEqual({ normal: false, afterHours: true });
  });
});
