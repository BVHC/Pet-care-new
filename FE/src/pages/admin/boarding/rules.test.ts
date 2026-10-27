import { describe, expect, it } from 'vitest';
import type { Role } from '@/shared/types/auth';
import type { Booking, BookingStatus } from '@/shared/types/boarding';
import { actionsOf, bookingQueryOf, canWriteCareLog, kennelFits, shortDay, vaccineGapText, type BoardingFilters } from './rules';

// Ma trận vai trò × trạng thái của hợp đồng boarding-v1 §B (A07, A08) và cách ghép tab / lọc thành query của GET /boarding-bookings.

const TODAY = '2026-10-23';
const booking = (status: BookingStatus, over: Partial<Booking> = {}): Pick<Booking, 'status' | 'checkInDate' | 'checkOutDate'> => ({
  status,
  checkInDate: '2026-10-30',
  checkOutDate: '2026-11-01',
  ...over,
});

describe('actionsOf — BOOKED', () => {
  const sample = booking('BOOKED');
  it.each<[Role, string[]]>([
    ['RECEPTIONIST', ['checkIn', 'noKennel', 'extend', 'cancel']],
    ['CARETAKER', ['checkIn']],
    ['VET', []],
    ['BRANCH_MANAGER', []],
  ])('%s', (role, expected) => {
    expect(actionsOf(role, sample, TODAY)).toEqual(expected);
  });

  it('đã tới ngày nhận thì lễ tân không hủy được nhưng vẫn gia hạn được (canCancel / canExtend)', () => {
    expect(actionsOf('RECEPTIONIST', booking('BOOKED', { checkInDate: TODAY }), TODAY)).toEqual(['checkIn', 'noKennel', 'extend']);
  });

  it('đã tới ngày trả thì không gia hạn', () => {
    expect(actionsOf('RECEPTIONIST', booking('BOOKED', { checkInDate: TODAY, checkOutDate: TODAY }), TODAY)).toEqual(['checkIn', 'noKennel']);
  });
});

describe('actionsOf — đang lưu trú', () => {
  const stay = booking('CHECKED_IN', { checkInDate: '2026-10-21', checkOutDate: '2026-10-31' });
  it.each<[Role, string[]]>([
    ['RECEPTIONIST', ['careLog', 'checkout', 'deceased', 'extend']],
    ['CARETAKER', ['careLog']],
    ['VET', ['careLog']],
    ['BRANCH_MANAGER', ['careLog']],
  ])('CHECKED_IN: %s', (role, expected) => {
    expect(actionsOf(role, stay, TODAY)).toEqual(expected);
  });

  it('OVERDUE: lễ tân trả thú / thú mất, không gia hạn (A9)', () => {
    expect(actionsOf('RECEPTIONIST', booking('OVERDUE', { checkInDate: '2026-10-10', checkOutDate: '2026-10-20' }), TODAY)).toEqual([
      'careLog',
      'checkout',
      'deceased',
    ]);
  });

  it('CHECKED_IN đúng ngày trả: hết gia hạn', () => {
    expect(actionsOf('RECEPTIONIST', booking('CHECKED_IN', { checkInDate: '2026-10-21', checkOutDate: TODAY }), TODAY)).toEqual([
      'careLog',
      'checkout',
      'deceased',
    ]);
  });
});

describe('actionsOf — ngoài phạm vi', () => {
  it.each<BookingStatus>(['CHECKED_OUT', 'CANCELLED', 'NO_SHOW'])('%s không còn thao tác ghi cho vai trò nào', (status) => {
    for (const role of ['RECEPTIONIST', 'CARETAKER', 'VET', 'BRANCH_MANAGER'] as const) {
      expect(actionsOf(role, booking(status), TODAY)).toEqual([]);
    }
  });

  it.each<Role | undefined>(['CUSTOMER', 'ADMIN', 'SUPER_MANAGER', undefined])('%s không có thao tác nào', (role) => {
    expect(actionsOf(role, booking('BOOKED'), TODAY)).toEqual([]);
    expect(actionsOf(role, booking('CHECKED_IN'), TODAY)).toEqual([]);
  });
});

describe('canWriteCareLog', () => {
  it('chỉ VET và CARETAKER ghi nhật ký (BR-LT-11)', () => {
    expect(canWriteCareLog('VET')).toBe(true);
    expect(canWriteCareLog('CARETAKER')).toBe(true);
    expect(canWriteCareLog('RECEPTIONIST')).toBe(false);
    expect(canWriteCareLog('BRANCH_MANAGER')).toBe(false);
    expect(canWriteCareLog(undefined)).toBe(false);
  });
});

describe('bookingQueryOf', () => {
  const base: BoardingFilters = { tab: 'upcoming', historyStatus: 'CHECKED_OUT', from: '', to: '', missingCareLogToday: false };

  it('Sắp nhận = BOOKED, trang 0, size 20', () => {
    expect(bookingQueryOf(base, 0, 20)).toStrictEqual({ status: 'BOOKED', page: 0, size: 20 });
  });

  it('Đang lưu trú = inStay, không gửi status', () => {
    expect(bookingQueryOf({ ...base, tab: 'inStay' }, 0, 20)).toStrictEqual({ inStay: true, page: 0, size: 20 });
  });

  it('cờ chưa có nhật ký hôm nay chỉ có ở tab Đang lưu trú', () => {
    expect(bookingQueryOf({ ...base, tab: 'inStay', missingCareLogToday: true }, 0, 20)).toStrictEqual({
      inStay: true,
      missingCareLogToday: true,
      page: 0,
      size: 20,
    });
    expect(bookingQueryOf({ ...base, tab: 'upcoming', missingCareLogToday: true }, 0, 20)).toStrictEqual({ status: 'BOOKED', page: 0, size: 20 });
    expect(bookingQueryOf({ ...base, tab: 'history', missingCareLogToday: true }, 0, 20)).toStrictEqual({ status: 'CHECKED_OUT', page: 0, size: 20 });
  });

  it('Lịch sử gửi đúng một status theo bộ lọc trong tab', () => {
    expect(bookingQueryOf({ ...base, tab: 'history', historyStatus: 'CANCELLED' }, 2, 20)).toStrictEqual({ status: 'CANCELLED', page: 2, size: 20 });
    expect(bookingQueryOf({ ...base, tab: 'history', historyStatus: 'NO_SHOW' }, 0, 20)).toStrictEqual({ status: 'NO_SHOW', page: 0, size: 20 });
  });

  it('from / to chỉ gửi khi có giá trị', () => {
    expect(bookingQueryOf({ ...base, from: '2026-10-01', to: '2026-10-31' }, 0, 20)).toStrictEqual({
      status: 'BOOKED',
      from: '2026-10-01',
      to: '2026-10-31',
      page: 0,
      size: 20,
    });
    expect(bookingQueryOf({ ...base, from: '2026-10-01' }, 0, 20)).toStrictEqual({ status: 'BOOKED', from: '2026-10-01', page: 0, size: 20 });
  });
});

describe('kennelFits — BR-LT-02 (loại chuồng phải hợp loài và cân nặng)', () => {
  const small = { kennelType: { species: 'DOG' as const, maxWeightKg: 10 } };
  it('đúng loài, cân nặng trong ngưỡng (kể cả đúng bằng ngưỡng)', () => {
    expect(kennelFits(small, { species: 'DOG', currentWeightKg: 6.5 })).toBe(true);
    expect(kennelFits(small, { species: 'DOG', currentWeightKg: 10 })).toBe(true);
  });
  it('quá cân thì ẩn', () => {
    expect(kennelFits(small, { species: 'DOG', currentWeightKg: 10.01 })).toBe(false);
    expect(kennelFits(small, { species: 'DOG', currentWeightKg: 18.3 })).toBe(false);
  });
  it('sai loài thì ẩn', () => {
    expect(kennelFits(small, { species: 'CAT', currentWeightKg: 4.2 })).toBe(false);
    expect(kennelFits(small, { species: 'OTHER' })).toBe(false);
  });
  it('thú chưa cân thì qua (BE cũng cho qua)', () => {
    expect(kennelFits(small, { species: 'DOG', currentWeightKg: null })).toBe(true);
    expect(kennelFits(small, { species: 'DOG' })).toBe(true);
  });
  it('dịch vụ không có thông số chuồng thì không phù hợp', () => {
    expect(kennelFits({ kennelType: null }, { species: 'DOG' })).toBe(false);
    expect(kennelFits({}, { species: 'DOG' })).toBe(false);
  });
});

describe('vaccineGapText — cảnh báo mũi tiêm không chặn (BR-LT-05)', () => {
  it('chưa tiêm lần nào', () => {
    expect(vaccineGapText({ lastAdministeredOn: null, nextDueDate: null })).toBe('Chưa tiêm');
  });
  it('đã tiêm nhưng hạn trước ngày nhận: nêu lần cuối và hạn', () => {
    expect(vaccineGapText({ lastAdministeredOn: '2025-10-01', nextDueDate: '2026-10-01' })).toBe(
      'Tiêm lần cuối 01/10/2025 · hạn 01/10/2026',
    );
  });
  it('thiếu hạn thì chỉ nêu lần tiêm cuối', () => {
    expect(vaccineGapText({ lastAdministeredOn: '2025-10-01' })).toBe('Tiêm lần cuối 01/10/2025');
  });
});

describe('shortDay', () => {
  it('YYYY-MM-DD → dd/MM như câu BR-LT-03 của BE', () => {
    expect(shortDay('2026-10-30')).toBe('30/10');
  });
});
