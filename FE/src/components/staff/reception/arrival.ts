// Lịch hẹn đã tới cửa sổ check-in: gợi ý cho lễ tân khách xếp hạng nào (BR-TN-02, BR-TN-03, BR-LH-08). BE vẫn là nơi quyết định.
export type ArrivalKind = 'ON_TIME' | 'LATE' | 'NO_SHOW';

export interface ArrivalWindow {
  kind: ArrivalKind;
  label: string;
}

const MINUTE = 60_000;
/** [CFG] `visit.late_priority_minutes` (BR-TN-03) và mốc NO_SHOW (BR-LH-08); mock và BE dùng cùng giá trị mặc định. */
const LATE_PRIORITY_MIN = 15;
const NO_SHOW_AFTER_MIN = 30;

/** Khách đến lúc `nowMs` so với giờ hẹn `scheduledMs`: còn ưu tiên lịch hẹn, xếp như vãng lai (trễ 15–30 phút), hay đã tính không đến. */
export function arrivalWindow(scheduledMs: number, nowMs: number): ArrivalWindow {
  const delta = nowMs - scheduledMs;
  const minutes = Math.floor(delta / MINUTE);
  if (delta > NO_SHOW_AFTER_MIN * MINUTE) {
    return { kind: 'NO_SHOW', label: `Quá ${NO_SHOW_AFTER_MIN} phút: lịch tính là không đến, tiếp nhận như khách vãng lai` };
  }
  if (delta > LATE_PRIORITY_MIN * MINUTE) return { kind: 'LATE', label: `Trễ ${minutes} phút: xếp như khách vãng lai` };
  if (minutes >= 1) return { kind: 'ON_TIME', label: `Trễ ${minutes} phút: vẫn giữ ưu tiên lịch hẹn` };
  if (minutes <= -1) return { kind: 'ON_TIME', label: `Đến sớm ${-minutes} phút` };
  return { kind: 'ON_TIME', label: 'Đúng giờ' };
}
