// Đổi múi giờ của tiến trình test để ghim lỗi "ngày theo giờ máy" (T49 nhóm 2, hàng 10). Node nạp lại múi giờ mỗi khi `process.env.TZ` đổi
// (pool `forks` của vitest, mỗi file một tiến trình); `delete process.env.TZ` thì KHÔNG trả về múi giờ gốc, nên phải gán lại tên gốc.
const current = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
// ICU chuẩn hóa tên (Asia/Ho_Chi_Minh → Asia/Saigon), nên so theo tên đã chuẩn hóa.
const canonical = (tz: string) => new Intl.DateTimeFormat('en-US', { timeZone: tz }).resolvedOptions().timeZone;
const SYSTEM_TZ = current();

/** Chạy tiếp với múi giờ `tz`; ném lỗi nếu Node không áp dụng được, để ca test không xanh giả khi múi giờ không đổi. */
export function setTimezone(tz: string): void {
  process.env.TZ = tz;
  if (current() !== canonical(tz)) throw new Error(`Không đổi được múi giờ test sang ${tz} (đang là ${current()})`);
}

/** Trả múi giờ về như lúc nạp file test (gọi ở afterEach). */
export function restoreTimezone(): void {
  process.env.TZ = SYSTEM_TZ;
}

/**
 * Máy ở các múi giờ khác nhau cùng một thời điểm: Los Angeles chậm hơn giờ Việt Nam (00:01 ngày mới ở VN vẫn là ngày cũ trên máy),
 * Auckland nhanh hơn (23:59 ở VN đã sang ngày mới trên máy), UTC ở giữa. Việt Nam là múi giờ đúng, làm mốc.
 */
export const TIMEZONES = ['Asia/Ho_Chi_Minh', 'America/Los_Angeles', 'UTC', 'Pacific/Auckland'] as const;
