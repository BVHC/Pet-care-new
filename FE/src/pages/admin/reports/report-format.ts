// Hàm thuần của trang báo cáo UC89: chọn nhanh kỳ, kiểm kỳ ở FE (chỉ để UX; BE kiểm lại — BR-BC-01) và định dạng số / tỷ lệ.
// Ngày là chuỗi 'YYYY-MM-DD' giờ Việt Nam; "hôm nay" do người gọi truyền vào (todayVn), không tính ở đây.
import { addDays } from '@/shared/utils/branch-schedule';
import type { ReportSource } from '@/shared/types/report';

/** [CFG] report.max_period_months (BR-BC-01). */
export const MAX_PERIOD_MONTHS = 12;

export interface Period {
  from: string;
  to: string;
}

export type PeriodPreset = 'today' | 'last7' | 'last30' | 'thisMonth' | 'lastMonth';

export const PRESET_LABELS: Record<PeriodPreset, string> = {
  today: 'Hôm nay',
  last7: '7 ngày qua',
  last30: '30 ngày qua',
  thisMonth: 'Tháng này',
  lastMonth: 'Tháng trước',
};

/** Kỳ của một nút chọn nhanh. "N ngày qua" gồm cả hôm nay; "Tháng này" từ mùng 1 đến hôm nay. */
export function presetPeriod(preset: PeriodPreset, today: string): Period {
  const monthStart = `${today.slice(0, 8)}01`;
  switch (preset) {
    case 'today':
      return { from: today, to: today };
    case 'last7':
      return { from: addDays(today, -6), to: today };
    case 'last30':
      return { from: addDays(today, -29), to: today };
    case 'thisMonth':
      return { from: monthStart, to: today };
    case 'lastMonth': {
      const end = addDays(monthStart, -1);
      return { from: `${end.slice(0, 8)}01`, to: end };
    }
  }
}

/** Nút chọn nhanh nào đang khớp đúng kỳ hiện tại (suy ra, không lưu thêm state). */
export const activePreset = (period: Period, today: string) =>
  (Object.keys(PRESET_LABELS) as PeriodPreset[]).find((p) => {
    const range = presetPeriod(p, today);
    return range.from === period.from && range.to === period.to;
  });

const pad = (n: number) => String(n).padStart(2, '0');

/** Cộng tháng theo lịch; ngày vượt cuối tháng đích thì lùi về ngày cuối (31/08 + 6 tháng = 28/02). */
function addMonths(iso: string, months: number): string {
  const [y, m, d] = iso.split('-').map(Number);
  const total = y * 12 + (m - 1) + months;
  const year = Math.floor(total / 12);
  const month = (total % 12) + 1;
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return `${year}-${pad(month)}-${pad(Math.min(d, lastDay))}`;
}

/** Câu báo khi kỳ sai, null nếu hợp lệ. Kỳ tối đa tính từ ngày bắt đầu cộng đủ 12 tháng (ASSUMPTION ghi ở trang). */
export function periodError(from: string, to: string): string | null {
  if (!from || !to) return 'Chọn đủ ngày bắt đầu và ngày kết thúc.';
  if (from > to) return 'Ngày bắt đầu không được sau ngày kết thúc.';
  if (to > addMonths(from, MAX_PERIOD_MONTHS)) return `Kỳ báo cáo tối đa ${MAX_PERIOD_MONTHS} tháng.`;
  return null;
}

/** `withinDays` của báo cáo tồn kho: số nguyên dương. Trả về số cần gửi, hoặc null nếu ô nhập chưa hợp lệ. */
export function parseWithinDays(text: string): number | null {
  const value = text.trim();
  if (!/^\d+$/.test(value)) return null;
  const days = Number(value);
  return Number.isSafeInteger(days) && days >= 1 ? days : null;
}

const NUMBER = new Intl.NumberFormat('vi-VN');
const ONE_DECIMAL = new Intl.NumberFormat('vi-VN', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

export const formatCount = (n: number) => NUMBER.format(n);

/** Tỷ lệ API là số thập phân 0–1 (A3): nhân 100, làm tròn 1 chữ số. Mẫu số 0 thì chưa có tỷ lệ → "—". */
export const formatRate = (rate: number, denominator: number) => (denominator > 0 ? `${ONE_DECIMAL.format(rate * 100)}%` : '—');

/** Điểm trung bình (thang 5); chưa có đánh giá nào (null) thì "—". */
export const formatRating = (average: number | null | undefined) => (average == null ? '—' : `${ONE_DECIMAL.format(average)} / 5`);

export const SOURCE_LABELS: Record<ReportSource, string> = { VISIT: 'Khám / dịch vụ', RETAIL: 'Bán lẻ', BOARDING: 'Lưu trú' };
