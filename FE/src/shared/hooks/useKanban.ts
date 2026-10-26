import { useMemo, useState } from 'react';
import { toast } from 'sonner';
import { useAccount } from '../stores/session.store';
import { visitPermissions, type Visit } from '../types/visit';
import type { BoardScope } from '../utils/grooming-board';
import { useCompleteVisit } from './useExam';
import { useCallVisit, useMyQueue, useVisitQueue, visitErrorMessage } from './useVisits';

/** Bảng grooming trên API visit: WAITING → IN_PROGRESS là gọi lượt (#8), IN_PROGRESS → READY là hoàn tất lượt (#10, UC52). READY chỉ gồm các lượt đã xong HÔM NAY. */
export type GroomColumn = 'WAITING' | 'IN_PROGRESS' | 'READY';
export const GROOM_COLUMNS: readonly GroomColumn[] = ['WAITING', 'IN_PROGRESS', 'READY'];

export interface MoveRejection {
  code: string;
  message: string;
}

/** Vì sao thẻ không sang được cột `to`, hoặc null khi FSM của lượt (BR-TN-09) và người phụ trách (BR-TN-05) cho phép. BE vẫn kiểm lại. */
export function moveRejection(visit: Visit, to: GroomColumn, userId: number): MoveRejection | null {
  const from: GroomColumn = visit.status === 'COMPLETED' ? 'READY' : visit.status === 'IN_PROGRESS' ? 'IN_PROGRESS' : 'WAITING';
  if (from === to) return { code: 'NO_MOVE', message: '' };
  if (from === 'READY') return { code: 'BR-TN-09', message: 'Thẻ đã xong không quay lại cột trước được.' };
  if (to === 'WAITING') return { code: 'BR-TN-09', message: 'Thẻ đã bắt đầu không quay lại cột trước được.' };
  if (to === 'READY') {
    if (from === 'WAITING') return { code: 'BR-TN-09', message: 'Bắt đầu làm trước khi báo xong.' };
    return visit.assigneeId === userId ? null : { code: 'ACCESS_DENIED_SCOPE', message: 'Chỉ người phụ trách được báo xong.' };
  }
  if (visit.assigneeId !== userId) {
    return {
      code: 'BR-TN-05',
      message: visit.assigneeId === null ? 'Lượt chưa được gán. Nhờ lễ tân gán cho bạn trước khi gọi.' : 'Lượt này đã gán cho nhân viên khác.',
    };
  }
  return null;
}

const withoutId = (set: ReadonlySet<number>, id: number) => new Set([...set].filter((x) => x !== id));

/**
 * Trạng thái bảng grooming: "Của tôi" đọc hàng đợi của tôi (#3), "Cả khu" đọc hàng đợi chi nhánh lọc nhóm Thẩm mỹ (#2); cột "Xong hôm nay" đọc các lượt Thẩm mỹ đã COMPLETED trong ngày
 * (#2 `status=COMPLETED`, A1; "Của tôi" thêm `assigneeId`). Thứ tự thẻ là thứ tự mảng BE trả. Hook ghi của API không cập nhật lạc quan, nên thẻ vừa bấm Bắt đầu / Xong được giữ ở
 * "Đang làm" / "Xong hôm nay" tại chỗ (`pending`) cho tới khi lệnh xong và hàng đợi đã tải lại; BE từ chối thì thẻ tự về cột cũ.
 */
export function useKanban(userId: number, scope: BoardScope) {
  const perms = visitPermissions(useAccount()?.role);
  const mine = useMyQueue(scope === 'mine' && perms.myQueue);
  const all = useVisitQueue({ serviceGroup: 'GROOMING' }, scope === 'all' && perms.queue);
  const done = useVisitQueue({ serviceGroup: 'GROOMING', status: 'COMPLETED', ...(scope === 'mine' && { assigneeId: userId }) }, perms.queue);
  const query = scope === 'mine' ? mine : all;
  const call = useCallVisit();
  const complete = useCompleteVisit();
  const [calling, setCalling] = useState<ReadonlySet<number>>(new Set());
  const [completing, setCompleting] = useState<ReadonlySet<number>>(new Set());

  const columns = useMemo(() => {
    const open = (query.data ?? []).filter((v) => v.serviceGroup === 'GROOMING');
    const columnOf = (v: Visit): GroomColumn => (completing.has(v.visitId) ? 'READY' : v.status === 'IN_PROGRESS' || calling.has(v.visitId) ? 'IN_PROGRESS' : 'WAITING');
    const finished = [...open.filter((v) => columnOf(v) === 'READY'), ...(done.data ?? []).filter((v) => v.serviceGroup === 'GROOMING')];
    const items: Record<GroomColumn, Visit[]> = {
      WAITING: open.filter((v) => columnOf(v) === 'WAITING'),
      IN_PROGRESS: open.filter((v) => columnOf(v) === 'IN_PROGRESS'),
      READY: finished.filter((v, i) => finished.findIndex((x) => x.visitId === v.visitId) === i),
    };
    return GROOM_COLUMNS.map((id) => ({ id, items: items[id] }));
  }, [query.data, done.data, calling, completing]);

  const moveCard = (visit: Visit, to: GroomColumn): MoveRejection | null => {
    const rejection = moveRejection(visit, to, userId);
    if (rejection) return rejection;
    const { visitId } = visit;
    if (to === 'READY') {
      setCompleting((prev) => new Set(prev).add(visitId));
      complete.mutate(visitId, {
        onError: (e) => toast.error(visitErrorMessage(e, 'complete')),
        onSettled: () => setCompleting((prev) => withoutId(prev, visitId)),
      });
      return null;
    }
    setCalling((prev) => new Set(prev).add(visitId));
    call.mutate(visitId, {
      onError: (e) => toast.error(visitErrorMessage(e, 'call')),
      onSettled: () => setCalling((prev) => withoutId(prev, visitId)),
    });
    return null;
  };

  /** Thẻ đang chờ BE trả lời (gọi lượt hoặc hoàn tất): khóa nút để không bấm hai lần. */
  const pending = useMemo(() => new Set([...calling, ...completing]), [calling, completing]);

  return { columns, query, moveCard, pending };
}
