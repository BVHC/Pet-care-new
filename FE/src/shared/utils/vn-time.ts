// Giờ nghiệp vụ Việt Nam cố định +07:00 (docs/time.md), không phụ thuộc múi giờ máy. Cho mock thu ngân (T35).
const VN_OFFSET_MS = 7 * 60 * 60 * 1000;

export interface VnClock {
  /** `YYYY-MM-DD` giờ Việt Nam. */
  date: string;
  /** Số phút kể từ 00:00 giờ Việt Nam. */
  minutes: number;
  /** Thứ ISO: 1 = thứ Hai … 7 = Chủ nhật. */
  isoWeekday: number;
}

export function vnClock(atMs: number = Date.now()): VnClock {
  const shifted = new Date(atMs + VN_OFFSET_MS);
  return {
    date: shifted.toISOString().slice(0, 10),
    minutes: shifted.getUTCHours() * 60 + shifted.getUTCMinutes(),
    isoWeekday: shifted.getUTCDay() || 7,
  };
}

export const vnDate = (atMs?: number) => vnClock(atMs).date;
