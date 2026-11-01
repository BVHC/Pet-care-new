import { describe, expect, it } from 'vitest';
import { activePreset, formatCount, formatRate, formatRating, parseWithinDays, periodError, presetPeriod, withinDaysError } from './report-format';

describe('presetPeriod (ngày giờ Việt Nam, truyền vào "hôm nay")', () => {
  const today = '2026-10-27';

  it('Hôm nay: một ngày', () => {
    expect(presetPeriod('today', today)).toEqual({ from: '2026-10-27', to: '2026-10-27' });
  });

  it('7 và 30 ngày qua gồm cả hôm nay', () => {
    expect(presetPeriod('last7', today)).toEqual({ from: '2026-10-21', to: '2026-10-27' });
    expect(presetPeriod('last30', today)).toEqual({ from: '2026-09-28', to: '2026-10-27' });
  });

  it('Tháng này: mùng 1 đến hôm nay', () => {
    expect(presetPeriod('thisMonth', today)).toEqual({ from: '2026-10-01', to: '2026-10-27' });
    expect(presetPeriod('thisMonth', '2026-11-01')).toEqual({ from: '2026-11-01', to: '2026-11-01' });
  });

  it('Tháng trước: cả tháng trước, kể cả tháng 30 ngày', () => {
    expect(presetPeriod('lastMonth', today)).toEqual({ from: '2026-09-01', to: '2026-09-30' });
    expect(presetPeriod('lastMonth', '2026-10-31')).toEqual({ from: '2026-09-01', to: '2026-09-30' });
  });

  it('qua ranh giới năm và tháng 2 (nhuận / không nhuận)', () => {
    expect(presetPeriod('lastMonth', '2027-01-05')).toEqual({ from: '2026-12-01', to: '2026-12-31' });
    expect(presetPeriod('last7', '2027-01-05')).toEqual({ from: '2026-12-30', to: '2027-01-05' });
    expect(presetPeriod('lastMonth', '2027-03-01')).toEqual({ from: '2027-02-01', to: '2027-02-28' });
    expect(presetPeriod('lastMonth', '2028-03-01')).toEqual({ from: '2028-02-01', to: '2028-02-29' });
  });

  it('activePreset nhận ra nút khớp đúng kỳ, kỳ tự chọn thì không nút nào', () => {
    expect(activePreset({ from: '2026-10-01', to: '2026-10-27' }, today)).toBe('thisMonth');
    expect(activePreset({ from: '2026-09-01', to: '2026-09-30' }, today)).toBe('lastMonth');
    expect(activePreset({ from: '2026-10-02', to: '2026-10-27' }, today)).toBeUndefined();
  });
});

describe('periodError (chỉ để báo sớm; BE kiểm lại BR-BC-01)', () => {
  it('thiếu ngày', () => {
    expect(periodError('', '2026-10-27')).toMatch(/Chọn đủ/);
    expect(periodError('2026-10-01', '')).toMatch(/Chọn đủ/);
  });

  it('từ > đến là sai, từ = đến là hợp lệ', () => {
    expect(periodError('2026-10-28', '2026-10-27')).toBe('Ngày bắt đầu không được sau ngày kết thúc.');
    expect(periodError('2026-10-27', '2026-10-27')).toBeNull();
  });

  // BE ReportPeriod: `!to.isBefore(from.plusMonths(12))` → 400, tức `to` phải NHỎ HƠN HẲN từ-ngày + 12 tháng
  // (01/01–31/12 hợp lệ, 01/01–01/01 năm sau bị chặn). Đã đo trên BE thật 1/11/2026.
  it('tối đa 12 tháng: ngày cuối còn hợp lệ là trước từ-ngày + 12 tháng một ngày; đúng + 12 tháng là sai', () => {
    expect(periodError('2026-01-01', '2026-12-31')).toBeNull();
    expect(periodError('2026-01-01', '2027-01-01')).toBe('Kỳ báo cáo tối đa 12 tháng.');
    expect(periodError('2026-01-01', '2027-01-02')).toBe('Kỳ báo cáo tối đa 12 tháng.');
    expect(periodError('2026-10-31', '2027-10-30')).toBeNull();
    expect(periodError('2026-10-31', '2027-10-31')).toBe('Kỳ báo cáo tối đa 12 tháng.');
    expect(periodError('2026-10-31', '2027-11-01')).toBe('Kỳ báo cáo tối đa 12 tháng.');
  });

  it('bắt đầu 29/02 thì mốc 12 tháng là 28/02 năm sau, hợp lệ tới 27/02', () => {
    expect(periodError('2024-02-29', '2025-02-27')).toBeNull();
    expect(periodError('2024-02-29', '2025-02-28')).toBe('Kỳ báo cáo tối đa 12 tháng.');
    expect(periodError('2024-02-29', '2025-03-01')).toBe('Kỳ báo cáo tối đa 12 tháng.');
  });

  it('ca đã gặp: 01/11/2025 → 01/11/2026 bị BE từ chối nên FE phải chặn sớm, lùi một ngày thì qua', () => {
    expect(periodError('2025-11-01', '2026-11-01')).toBe('Kỳ báo cáo tối đa 12 tháng.');
    expect(periodError('2025-11-01', '2026-10-31')).toBeNull();
  });
});

// BE: `@Min(0) @Max(365)` trên withinDays (đã đo 1/11/2026: 365 → 200, 366 → 400 VALIDATION_FAILED). FE chặt hơn ở đầu dưới: 0 ngày vô nghĩa.
describe('parseWithinDays', () => {
  it('số nguyên từ 1 đến 365', () => {
    expect(parseWithinDays('30')).toBe(30);
    expect(parseWithinDays(' 7 ')).toBe(7);
    expect(parseWithinDays('007')).toBe(7);
    expect(parseWithinDays('1')).toBe(1);
    expect(parseWithinDays('365')).toBe(365);
  });

  it.each(['', '  ', '0', '-3', '2.5', '1e3', 'abc', '366', '1000', '99999999999999999999'])('"%s" bị chặn', (text) => {
    expect(parseWithinDays(text)).toBeNull();
  });
});

describe('withinDaysError', () => {
  it('hợp lệ thì null', () => {
    expect(withinDaysError('30')).toBeNull();
    expect(withinDaysError('365')).toBeNull();
  });

  it.each(['', '0', '-5', '2.5', 'abc'])('"%s": nhắc nhập số nguyên dương', (text) => {
    expect(withinDaysError(text)).toBe('Nhập số ngày là số nguyên dương.');
  });

  it.each(['366', '1000', '99999999999999999999'])('"%s": vượt 365 thì báo số ngày tối đa', (text) => {
    expect(withinDaysError(text)).toBe('Số ngày tối đa là 365.');
  });
});

describe('định dạng', () => {
  it('tỷ lệ 0–1 nhân 100, làm tròn 1 chữ số, dấu phẩy tiếng Việt', () => {
    expect(formatRate(0.2857, 7)).toBe('28,6%');
    expect(formatRate(0.125, 8)).toBe('12,5%');
    expect(formatRate(0, 10)).toBe('0,0%');
    expect(formatRate(1, 3)).toBe('100,0%');
    expect(formatRate(0.0449, 100)).toBe('4,5%');
  });

  // BE làm tròn tỷ lệ HALF_UP ở 4 chữ số thập phân (ReportService.rate); nhân 100 bằng số thực làm 0.0045 * 100 = 0.44999… → "0,4%".
  it('làm tròn nửa lên theo số thập phân như BE, không trôi theo số thực', () => {
    expect(formatRate(0.0045, 100)).toBe('0,5%');
    expect(formatRate(0.0185, 10)).toBe('1,9%');
    expect(formatRate(0.0295, 10)).toBe('3,0%');
    expect(formatRate(0.0035, 10)).toBe('0,4%');
    expect(formatRate(0.4286, 7)).toBe('42,9%');
  });

  it('mẫu số 0 thì chưa có tỷ lệ', () => {
    expect(formatRate(0, 0)).toBe('—');
    expect(formatRate(0.5, 0)).toBe('—');
  });

  it('điểm trung bình: null / thiếu → "—", có thì 1 chữ số trên thang 5', () => {
    expect(formatRating(null)).toBe('—');
    expect(formatRating(undefined)).toBe('—');
    expect(formatRating(4.5)).toBe('4,5 / 5');
    expect(formatRating(4.333)).toBe('4,3 / 5');
    expect(formatRating(0)).toBe('0,0 / 5');
  });

  it('số đếm có dấu chấm nghìn', () => {
    expect(formatCount(1234)).toBe('1.234');
  });
});
