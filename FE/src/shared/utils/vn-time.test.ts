import { afterEach, describe, expect, it, vi } from 'vitest';
import { vnClock, vnDate } from './vn-time';

afterEach(() => vi.useRealTimers());

describe('giờ Việt Nam cố định +07:00 (docs/time.md)', () => {
  it('qua nửa đêm giờ Việt Nam: ngày, phút trong ngày và thứ lấy theo +07:00, không theo UTC', () => {
    // 17:30Z thứ Hai 26/10 = 00:30 thứ Ba 27/10 giờ Việt Nam.
    expect(vnClock(Date.parse('2026-10-26T17:30:00Z'))).toEqual({ date: '2026-10-27', minutes: 30, isoWeekday: 2 });
  });

  it('thứ ISO: thứ Hai = 1, Chủ nhật = 7; phút cuối ngày = 1439', () => {
    expect(vnClock(Date.parse('2026-10-26T10:00:00+07:00')).isoWeekday).toBe(1);
    expect(vnClock(Date.parse('2026-11-15T23:59:59+07:00'))).toEqual({ date: '2026-11-15', minutes: 1439, isoWeekday: 7 });
  });

  it('mặc định lấy Date.now() lúc gọi (đồng hồ giả hoạt động)', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-27T08:05:59+07:00'));
    expect(vnClock()).toEqual({ date: '2026-10-27', minutes: 485, isoWeekday: 2 });
    expect(vnDate()).toBe('2026-10-27');
  });
});
