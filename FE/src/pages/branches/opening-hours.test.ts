import { describe, expect, it } from 'vitest';
import { weekRows } from './opening-hours';

// DayHours: dayOfWeek 1 = Thứ Hai … 7 = Chủ nhật; ranges rỗng = nghỉ cả ngày (BR-CN-02).

const day = (dayOfWeek: number, ...ranges: [string, string][]) => ({
  dayOfWeek,
  ranges: ranges.map(([open, close]) => ({ open, close })),
});

describe('weekRows', () => {
  it('sắp từ Thứ Hai đến Chủ nhật dù dữ liệu về lộn xộn', () => {
    const rows = weekRows([day(7, ['08:00', '12:00']), day(3, ['08:00', '17:00']), day(1, ['08:00', '17:00'])]);
    expect(rows.map((r) => r.label)).toEqual(['Thứ Hai', 'Thứ Tư', 'Chủ nhật']);
  });

  it('ngày không có khoảng giờ là "Nghỉ"', () => {
    const [monday] = weekRows([day(1)]);
    expect(monday).toMatchObject({ text: 'Nghỉ', closed: true });
  });

  it('nhiều khoảng giờ nối bằng "và", mỗi khoảng không bị ngắt dòng giữa chừng', () => {
    const [monday] = weekRows([day(1, ['08:00', '12:00'], ['13:30', '19:00'])]);
    expect(monday.closed).toBe(false);
    expect(monday.text).toBe('08:00 - 12:00 và 13:30 - 19:00');
  });

  it('bỏ ngày ngoài 1..7 và không tự thêm ngày thiếu', () => {
    expect(weekRows([day(0, ['08:00', '12:00']), day(8), day(2, ['09:00', '11:00'])]).map((r) => r.dayOfWeek)).toEqual([2]);
    expect(weekRows([])).toEqual([]);
  });
});
