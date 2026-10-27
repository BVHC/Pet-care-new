import { describe, expect, it } from 'vitest';
import type { PublicService } from '@/shared/types/catalog';
import {
  bookingDateError,
  branchIdsWithBoarding,
  checkInWindow,
  checkOutWindow,
  kennelFitsPet,
  kennelOptions,
  kennelsFor,
  soldOutMessage,
  soldOutNights,
  type KennelOption,
} from './rules';

const service = (over: Partial<PublicService>): PublicService => ({
  serviceId: 5,
  name: 'Chuồng chó nhỏ',
  group: 'BOARDING',
  price: 200_000,
  priceIsFrom: false,
  description: null,
  kennelType: { species: 'DOG', maxWeightKg: 10 },
  branchIds: [1],
  ...over,
});

const dogSmall = service({}) as KennelOption;
const catBox = service({ serviceId: 6, name: 'Chuồng mèo', kennelType: { species: 'CAT', maxWeightKg: 8 }, branchIds: [2] }) as KennelOption;

describe('kennelFitsPet (BR-LT-02: không hiển thị loại không phù hợp)', () => {
  it('đúng loài và cân nặng không vượt tối đa thì vừa; bằng đúng tối đa vẫn vừa', () => {
    expect(kennelFitsPet(dogSmall, { species: 'DOG', currentWeightKg: 6.5 })).toBe(true);
    expect(kennelFitsPet(dogSmall, { species: 'DOG', currentWeightKg: 10 })).toBe(true);
  });

  it('vượt cân nặng tối đa thì không vừa', () => {
    expect(kennelFitsPet(dogSmall, { species: 'DOG', currentWeightKg: 10.01 })).toBe(false);
    expect(kennelFitsPet(dogSmall, { species: 'DOG', currentWeightKg: 18.3 })).toBe(false);
  });

  it('sai loài thì không vừa dù nhẹ cân', () => {
    expect(kennelFitsPet(dogSmall, { species: 'CAT', currentWeightKg: 3 })).toBe(false);
    expect(kennelFitsPet(catBox, { species: 'DOG', currentWeightKg: 3 })).toBe(false);
  });

  it('thú chưa cân (null / vắng) thì cho qua, BE kiểm lại khi nhận', () => {
    expect(kennelFitsPet(dogSmall, { species: 'DOG', currentWeightKg: null })).toBe(true);
    expect(kennelFitsPet(dogSmall, { species: 'DOG' })).toBe(true);
  });
});

describe('loại chuồng từ dịch vụ công khai', () => {
  const medical = service({ serviceId: 1, name: 'Khám tổng quát', group: 'MEDICAL', kennelType: null });
  const noSpec = service({ serviceId: 9, name: 'Lưu trú thiếu thông số', kennelType: null });

  it('chỉ lấy dịch vụ nhóm BOARDING có thông số chuồng', () => {
    expect(kennelOptions([medical, dogSmall, noSpec, catBox]).map((k) => k.serviceId)).toEqual([5, 6]);
  });

  it('chỉ lấy loại được bật tại chi nhánh đang chọn và vừa thú', () => {
    const all = kennelOptions([dogSmall, catBox]);
    expect(kennelsFor(all, 1, { species: 'DOG', currentWeightKg: 6.5 }).map((k) => k.serviceId)).toEqual([5]);
    expect(kennelsFor(all, 1, { species: 'CAT', currentWeightKg: 4 })).toEqual([]);
    expect(kennelsFor(all, 2, { species: 'CAT', currentWeightKg: 4 }).map((k) => k.serviceId)).toEqual([6]);
    expect(kennelsFor(all, 1, { species: 'DOG', currentWeightKg: 18.3 })).toEqual([]);
  });

  it('chi nhánh nhận lưu trú là hợp của branchIds, không trùng, sắp tăng', () => {
    const all = kennelOptions([dogSmall, service({ serviceId: 7, branchIds: [3, 1] }), catBox]);
    expect(branchIdsWithBoarding(all)).toEqual([1, 2, 3]);
  });
});

describe('khoảng ngày khách đặt được (BR-LT-04, giờ Việt Nam)', () => {
  it('ngày nhận từ ngày mai tới hôm nay + 60 ngày', () => {
    expect(checkInWindow('2026-10-23')).toEqual({ min: '2026-10-24', max: '2026-12-22' });
  });

  it('qua tháng và qua năm vẫn đúng', () => {
    expect(checkInWindow('2026-12-31')).toEqual({ min: '2027-01-01', max: '2027-03-01' });
  });

  it('ngày trả từ ngày nhận + 1 tới ngày nhận + 30 (1 đến 30 đêm, BR-LT-02)', () => {
    expect(checkOutWindow('2026-10-24')).toEqual({ min: '2026-10-25', max: '2026-11-23' });
  });
});

describe('bookingDateError', () => {
  const today = '2026-10-23';

  it('chưa chọn ngày nhận thì chưa báo lỗi', () => {
    expect(bookingDateError(null, null, today)).toBeNull();
    expect(bookingDateError(null, '2026-10-25', today)).toBeNull();
  });

  it('ngày nhận là hôm nay hoặc đã qua thì báo phải từ ngày mai', () => {
    expect(bookingDateError('2026-10-23', null, today)).toMatch(/từ ngày mai/);
    expect(bookingDateError('2026-10-01', null, today)).toMatch(/từ ngày mai/);
    expect(bookingDateError('2026-10-24', null, today)).toBeNull();
  });

  it('ngày nhận xa quá 60 ngày thì báo; đúng ngày thứ 60 thì được, ngày thứ 61 thì không', () => {
    expect(bookingDateError('2026-12-22', null, today)).toBeNull();
    expect(bookingDateError('2026-12-23', null, today)).toMatch(/60 ngày/);
  });

  it('số đêm 1 đến 30 hợp lệ; ngày trả không sau ngày nhận hoặc quá 30 đêm thì báo', () => {
    expect(bookingDateError('2026-10-24', '2026-10-25', today)).toBeNull();
    expect(bookingDateError('2026-10-24', '2026-11-23', today)).toBeNull();
    expect(bookingDateError('2026-10-24', '2026-11-24', today)).toMatch(/30 đêm/);
    expect(bookingDateError('2026-10-24', '2026-10-24', today)).toMatch(/sau ngày nhận/);
    expect(bookingDateError('2026-10-24', '2026-10-20', today)).toMatch(/sau ngày nhận/);
  });

  it('ranh giới nửa đêm giờ Việt Nam: cùng một mốc "hôm nay" cho cả hai phía', () => {
    // 23:59 ngày 23/10 và 00:01 ngày 24/10 là hai "hôm nay" khác nhau; hàm chỉ nhận chuỗi ngày nên không dính múi giờ máy.
    expect(bookingDateError('2026-10-24', null, '2026-10-23')).toBeNull();
    expect(bookingDateError('2026-10-24', null, '2026-10-24')).toMatch(/từ ngày mai/);
  });
});

describe('đêm hết chỗ', () => {
  const nights = [
    { date: '2026-10-26', capacity: 3, occupied: 3, remaining: 0 },
    { date: '2026-10-27', capacity: 3, occupied: 2, remaining: 1 },
    { date: '2026-10-28', capacity: 3, occupied: 3, remaining: 0 },
  ];

  it('liệt kê đúng các đêm remaining = 0', () => {
    expect(soldOutNights(nights)).toEqual(['2026-10-26', '2026-10-28']);
    expect(soldOutNights([nights[1]])).toEqual([]);
  });

  it('câu nêu đêm hết chỗ theo dd/MM, cùng văn phong BE', () => {
    expect(soldOutMessage(['2026-10-26', '2026-10-28'])).toBe('Hết chỗ các đêm: 26/10, 28/10');
  });
});
