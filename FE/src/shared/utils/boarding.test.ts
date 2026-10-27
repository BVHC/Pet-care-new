import { describe, expect, it } from 'vitest';
import {
  BOOKING_STATUS_TONE,
  canCancel,
  canEditCareLog,
  canExtend,
  isActiveBooking,
  isFinished,
  isInStay,
  isLateCancel,
  nightDates,
  nightsBetween,
  shiftDate,
  stayAmount,
} from './boarding';
import { BOOKING_STATUSES, BOOKING_STATUS_LABELS } from '../types/boarding';

describe('shiftDate / nightsBetween / nightDates', () => {
  it('cộng ngày qua ranh giới tháng, năm và năm nhuận', () => {
    expect(shiftDate('2026-10-31', 1)).toBe('2026-11-01');
    expect(shiftDate('2026-12-31', 1)).toBe('2027-01-01');
    expect(shiftDate('2028-02-28', 1)).toBe('2028-02-29');
    expect(shiftDate('2027-02-28', 1)).toBe('2027-03-01');
    expect(shiftDate('2026-11-01', -1)).toBe('2026-10-31');
  });

  it('số đêm = ngày trả − ngày nhận; ≤ 0 khi nhập sai', () => {
    expect(nightsBetween('2026-11-10', '2026-11-13')).toBe(3);
    expect(nightsBetween('2026-10-30', '2026-11-02')).toBe(3);
    expect(nightsBetween('2026-11-10', '2026-11-10')).toBe(0);
    expect(nightsBetween('2026-11-10', '2026-11-08')).toBe(-2);
  });

  it('đêm = ngày nhận … ngày trả − 1 (A1)', () => {
    expect(nightDates('2026-10-30', '2026-11-02')).toEqual(['2026-10-30', '2026-10-31', '2026-11-01']);
    expect(nightDates('2026-11-10', '2026-11-10')).toEqual([]);
    expect(nightDates('2026-11-10', '2026-11-08')).toEqual([]);
  });
});

describe('isLateCancel (BR-LT-06, 00:00 giờ VN ngày nhận − 24 h)', () => {
  const checkIn = '2026-11-10';
  it('1 giây trước 00:00 ngày D−1 giờ VN: chưa muộn', () => {
    expect(isLateCancel(checkIn, Date.parse('2026-11-08T23:59:59+07:00'))).toBe(false);
  });
  it('đúng 00:00 ngày D−1: muộn', () => {
    expect(isLateCancel(checkIn, Date.parse('2026-11-09T00:00:00+07:00'))).toBe(true);
  });
  it('23:59 ngày D−1: muộn', () => {
    expect(isLateCancel(checkIn, Date.parse('2026-11-09T23:59:59+07:00'))).toBe(true);
  });
  it('không phụ thuộc UTC: 17:00Z ngày 08 = 00:00 giờ VN ngày 09', () => {
    expect(isLateCancel(checkIn, Date.parse('2026-11-08T16:59:59Z'))).toBe(false);
    expect(isLateCancel(checkIn, Date.parse('2026-11-08T17:00:00Z'))).toBe(true);
  });
  it('theo số giờ [CFG] truyền vào', () => {
    expect(isLateCancel(checkIn, Date.parse('2026-11-08T12:00:00+07:00'), 48)).toBe(true);
    expect(isLateCancel(checkIn, Date.parse('2026-11-08T12:00:00+07:00'), 0)).toBe(false);
  });
});

describe('canExtend / canCancel', () => {
  it('gia hạn: BOOKED hoặc CHECKED_IN, trước ngày trả dự kiến; OVERDUE và trạng thái cuối thì không', () => {
    const base = { checkOutDate: '2026-11-13' };
    expect(canExtend({ ...base, status: 'BOOKED' }, '2026-11-12')).toBe(true);
    expect(canExtend({ ...base, status: 'CHECKED_IN' }, '2026-11-12')).toBe(true);
    expect(canExtend({ ...base, status: 'CHECKED_IN' }, '2026-11-13')).toBe(false);
    expect(canExtend({ ...base, status: 'OVERDUE' }, '2026-11-12')).toBe(false);
    expect(canExtend({ ...base, status: 'CHECKED_OUT' }, '2026-11-12')).toBe(false);
    expect(canExtend({ ...base, status: 'CANCELLED' }, '2026-11-12')).toBe(false);
  });

  it('hủy: chỉ BOOKED và trước ngày nhận', () => {
    const base = { checkInDate: '2026-11-10' };
    expect(canCancel({ ...base, status: 'BOOKED' }, '2026-11-09')).toBe(true);
    expect(canCancel({ ...base, status: 'BOOKED' }, '2026-11-10')).toBe(false);
    expect(canCancel({ ...base, status: 'CHECKED_IN' }, '2026-11-09')).toBe(false);
  });
});

describe('trạng thái', () => {
  it('nhóm trạng thái đúng SM #6', () => {
    expect(BOOKING_STATUSES.filter(isInStay)).toEqual(['CHECKED_IN', 'OVERDUE']);
    expect(BOOKING_STATUSES.filter(isActiveBooking)).toEqual(['BOOKED', 'CHECKED_IN', 'OVERDUE']);
    expect(BOOKING_STATUSES.filter(isFinished)).toEqual(['CHECKED_OUT', 'CANCELLED', 'NO_SHOW']);
  });

  it('mọi trạng thái có nhãn và tông', () => {
    for (const s of BOOKING_STATUSES) {
      expect(BOOKING_STATUS_LABELS[s]).toBeTruthy();
      expect(BOOKING_STATUS_TONE[s]).toBeTruthy();
    }
  });
});

describe('canEditCareLog (BR-LT-11)', () => {
  const log = { editableUntil: '2026-11-10T09:00:00+07:00' };
  it('sửa được tới mốc, hết hạn thì không', () => {
    expect(canEditCareLog(log, Date.parse('2026-11-10T08:59:59+07:00'))).toBe(true);
    expect(canEditCareLog(log, Date.parse('2026-11-10T09:00:00+07:00'))).toBe(false);
  });
});

describe('stayAmount', () => {
  it('đêm × giá đêm; âm thành 0', () => {
    expect(stayAmount(3, 200_000)).toBe(600_000);
    expect(stayAmount(-1, 200_000)).toBe(0);
  });
});
