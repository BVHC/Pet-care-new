// Quy tắc thao tác trên hàng đợi mà UI cần để không mời lễ tân bấm thứ BE chắc chắn từ chối. BE vẫn kiểm lại mọi thứ.
import type { Visit } from '../../../shared/types/visit';

/**
 * Đích "Đưa lên trước" (move, BR-TN-04) của `visit`: các lượt đứng TRƯỚC nó trong mảng BE trả, còn WAITING (409 nếu đích đã gọi) và cùng phía ranh giới
 * cấp cứu (400: ca cấp cứu chỉ sắp trong nhóm cấp cứu). Không so `queueSortKey`: hai khóa có thể chỉ khác nhau 1 µs mà `Date.parse` chỉ tới mili giây.
 */
export function moveTargets(queue: Visit[], visit: Visit): Visit[] {
  const at = queue.findIndex((v) => v.visitId === visit.visitId);
  if (at < 0 || visit.status !== 'WAITING') return [];
  const emergency = visit.priorityClass === 'EMERGENCY';
  return queue.slice(0, at).filter((v) => v.status === 'WAITING' && (v.priorityClass === 'EMERGENCY') === emergency);
}

/**
 * Hiện "Gán lại" cho lượt đã gọi (BR-TN-08, A15): BE bật cờ `needsReassign` khi khóa tài khoản, hoặc người phụ trách không còn trong danh sách nhân viên
 * gán được (vô hiệu hóa, đổi chức vụ). `assignable` = null: chưa có danh sách, chỉ tin cờ.
 */
export function reassignable(visit: Visit, assignable: ReadonlySet<number> | null): boolean {
  if (visit.status !== 'IN_PROGRESS') return false;
  if (visit.needsReassign) return true;
  return assignable !== null && visit.assigneeId !== null && !assignable.has(visit.assigneeId);
}
