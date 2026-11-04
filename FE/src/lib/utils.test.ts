import { afterEach, describe, expect, it } from 'vitest';
import { restoreTimezone, setTimezone, TIMEZONES } from '../test/timezone';
import { formatDate } from './utils';

// L-15 (1): `LocalDate` của BE ("2026-10-28") không có giờ; `new Date("2026-10-28")` là 00:00 UTC nên máy ở phía tây UTC in ra ngày hôm trước.
afterEach(restoreTimezone);

describe('formatDate', () => {
  it.each(TIMEZONES)('chuỗi ngày yyyy-MM-dd giữ nguyên ngày ở múi giờ máy %s', (tz) => {
    setTimezone(tz);
    expect(formatDate('2026-10-28')).toBe('28/10/2026');
    expect(formatDate('2026-01-01')).toBe('01/01/2026');
  });

  it('chuỗi có giờ hoặc đối tượng Date vẫn đổi theo giờ máy như trước', () => {
    setTimezone('Asia/Ho_Chi_Minh');
    expect(formatDate('2026-10-28T23:30:00+07:00')).toBe('28/10/2026');
    expect(formatDate(new Date('2026-10-28T23:30:00+07:00'))).toBe('28/10/2026');
  });
});
