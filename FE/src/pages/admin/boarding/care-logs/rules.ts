// UC57 (BR-LT-11, BR-LT-12): quy tắc thuần của hộp thoại "Nhật ký chăm sóc" — nhóm theo ngày, kiểm dữ liệu, dựng request, quyền sửa trong hạn.
// Chỉ để chặn lỗi sớm ở giao diện: BE / mock kiểm lại và thông điệp của họ là câu cuối cùng.
import { BOARDING_CFG, type BookingStatus, type CareLog, type CareLogRequest, type UpdateCareLogRequest } from '@/shared/types/boarding';
import { canEditCareLog, isInStay } from '@/shared/utils/boarding';

export const TEXT_FIELDS = ['eating', 'drinking', 'hygiene', 'activity'] as const;
export type TextField = (typeof TEXT_FIELDS)[number];
export const FIELD_LABELS: Record<TextField, string> = { eating: 'Ăn', drinking: 'Uống', hygiene: 'Vệ sinh', activity: 'Hoạt động' };

/** Mỗi ô ngắn (ăn / uống / vệ sinh / hoạt động) tối đa 300 ký tự (A7; mock + BE cùng giới hạn). */
export const TEXT_MAX = 300;
/** Bản bổ sung 1–2000 ký tự (A7). */
export const ADDENDUM_MAX = 2000;
export const MAX_PHOTOS = BOARDING_CFG.careLogMaxPhotos;

/** `in-stay`: ghi / sửa được · `finished`: đã trả thú, chỉ xem + bản bổ sung · `none`: chưa vào chuồng hoặc đã hủy, chưa có nhật ký nào. */
export type CareLogMode = 'inStay' | 'finished' | 'none';
export const careLogMode = (status: BookingStatus): CareLogMode => (isInStay(status) ? 'inStay' : status === 'CHECKED_OUT' ? 'finished' : 'none');

// ───── Nhóm theo ngày ─────

export interface DayGroup {
  date: string;
  logs: CareLog[];
}

/** Ngày nhật ký mới → cũ; cùng ngày thì mục ghi sau (id lớn hơn) đứng trước. */
export function groupByDate(logs: CareLog[]): DayGroup[] {
  const sorted = [...logs].sort((a, b) => b.logDate.localeCompare(a.logDate) || b.careLogId - a.careLogId);
  const groups: DayGroup[] = [];
  for (const log of sorted) {
    const last = groups[groups.length - 1];
    if (last?.date === log.logDate) last.logs.push(log);
    else groups.push({ date: log.logDate, logs: [log] });
  }
  return groups;
}

// ───── Sửa trong hạn (BR-LT-11) ─────

const MINUTE = 60_000;
/** Số phút còn lại để sửa, làm tròn lên (còn 1 giây vẫn là "1 phút"); 0 khi đã tới hoặc qua mốc `editableUntil`. */
export const minutesLeft = (log: Pick<CareLog, 'editableUntil'>, now: number): number => Math.max(0, Math.ceil((Date.parse(log.editableUntil) - now) / MINUTE));

/** Nút "Sửa": chính mình ghi (so id phiên với `recordedBy`), thú còn trong chuồng và còn hạn (đúng mốc `editableUntil` là hết). */
export function canEditEntry(log: Pick<CareLog, 'recordedBy' | 'editableUntil'>, ctx: { accountId: number | null; inStay: boolean; now: number }): boolean {
  return ctx.inStay && ctx.accountId !== null && log.recordedBy === ctx.accountId && canEditCareLog(log, ctx.now);
}

// ───── Bản nháp + kiểm dữ liệu ─────

export interface CareLogDraft {
  logDate: string;
  eating: string;
  drinking: string;
  hygiene: string;
  activity: string;
  note: string;
  /** Mỗi phần tử là một ô nhập; ô trống bị bỏ khi gửi. */
  photoUrls: string[];
  isAbnormal: boolean;
}

export type DraftErrors = Partial<Record<'logDate' | TextField | 'note' | 'photoUrls' | 'form', string>>;

export const emptyDraft = (today: string): CareLogDraft => ({ logDate: today, eating: '', drinking: '', hygiene: '', activity: '', note: '', photoUrls: [], isAbnormal: false });

export const draftOf = (log: CareLog): CareLogDraft => ({
  logDate: log.logDate,
  eating: log.eating ?? '',
  drinking: log.drinking ?? '',
  hygiene: log.hygiene ?? '',
  activity: log.activity ?? '',
  note: log.note ?? '',
  photoUrls: [...log.photoUrls],
  isAbnormal: log.isAbnormal,
});

const HTTPS_RE = /^https:\/\/\S+$/;
const HTTPS_MESSAGE = 'Ảnh phải là đường dẫn https (bắt đầu bằng https://)';

/** Ô trống hợp lệ (bị bỏ khi gửi); còn lại phải là `https://…` không chứa khoảng trắng (cùng luật với BE). */
export const photoError = (url: string): string | undefined => (url.trim() === '' || HTTPS_RE.test(url.trim()) ? undefined : HTTPS_MESSAGE);

const photosOf = (d: Pick<CareLogDraft, 'photoUrls'>) => d.photoUrls.map((u) => u.trim()).filter(Boolean);

/**
 * ASSUMPTION (UI): mục hoàn toàn trống (không ô chữ, không ghi chú, không ảnh) bị chặn dù mock / hợp đồng chấp nhận,
 * vì nhật ký trống không chứng minh được thú đã được chăm sóc (BR-LT-11).
 */
export function validateDraft(d: CareLogDraft, ctx: { creating: boolean; today: string }): DraftErrors {
  const errors: DraftErrors = {};
  if (ctx.creating) {
    if (!d.logDate) errors.logDate = 'Vui lòng chọn ngày nhật ký';
    else if (d.logDate > ctx.today) errors.logDate = 'Ngày nhật ký không được ở tương lai';
  }
  for (const field of TEXT_FIELDS) if (d[field].trim().length > TEXT_MAX) errors[field] = `Tối đa ${TEXT_MAX} ký tự`;
  const photos = photosOf(d);
  if (photos.length > MAX_PHOTOS) errors.photoUrls = `Tối đa ${MAX_PHOTOS} ảnh`;
  else if (photos.some((u) => photoError(u))) errors.photoUrls = HTTPS_MESSAGE;
  if (photos.length === 0 && !d.note.trim() && TEXT_FIELDS.every((f) => !d[f].trim())) {
    errors.form = 'Nhật ký trống: nhập ít nhất một nội dung hoặc một ảnh';
  }
  return errors;
}

/** Ghi mới: chỉ gửi ô có chữ; `photoUrls` luôn có (mock từ chối nếu thiếu), `isAbnormal` luôn tường minh. */
export function toCreateRequest(d: CareLogDraft): CareLogRequest {
  const body: CareLogRequest = { logDate: d.logDate, photoUrls: photosOf(d), isAbnormal: d.isAbnormal };
  for (const field of [...TEXT_FIELDS, 'note'] as const) if (d[field].trim()) body[field] = d[field].trim();
  return body;
}

/** Sửa: gửi đủ năm ô chữ (chuỗi trống = xóa ô đó) và danh sách ảnh; request không có `isAbnormal` / `logDate` (A7). */
export function toUpdateRequest(d: CareLogDraft): UpdateCareLogRequest {
  return {
    eating: d.eating.trim(),
    drinking: d.drinking.trim(),
    hygiene: d.hygiene.trim(),
    activity: d.activity.trim(),
    note: d.note.trim(),
    photoUrls: photosOf(d),
  };
}

export const addendumError = (content: string): string | undefined => {
  const length = content.trim().length;
  if (length === 0) return 'Vui lòng nhập nội dung bổ sung';
  if (length > ADDENDUM_MAX) return `Nội dung tối đa ${ADDENDUM_MAX} ký tự`;
  return undefined;
};
