import type { OpeningHoursVersion } from '../../../shared/types/branch';
import { DAY_LABELS, WEEK_DAYS, formatRanges } from '../../../shared/utils/branch-schedule';

/** Bảng giờ mở cửa 7 ngày của một bản (BR-CN-02). */
export function WeekHours({ version }: { version: OpeningHoursVersion }) {
  return (
    <table className="w-full text-sm">
      <tbody>
        {WEEK_DAYS.map((day) => {
          const ranges = version.days.find((d) => d.dayOfWeek === day)?.ranges ?? [];
          return (
            <tr key={day} className="border-b border-(--border-color) last:border-0">
              <th scope="row" className="w-28 py-1.5 text-left font-medium text-(--text-secondary)">
                {DAY_LABELS[day]}
              </th>
              <td className={ranges.length ? 'py-1.5 text-(--text-primary)' : 'py-1.5 text-(--text-tertiary)'}>{formatRanges(ranges)}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
