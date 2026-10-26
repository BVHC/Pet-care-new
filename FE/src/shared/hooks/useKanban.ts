import { useMemo, useState } from 'react';
import { toast } from 'sonner';
import { useAccount } from '../stores/session.store';
import { visitPermissions, type Visit } from '../types/visit';
import type { BoardScope } from '../utils/grooming-board';
import { useCallVisit, useMyQueue, useVisitQueue, visitErrorMessage } from './useVisits';

/** Bảng grooming trên API visit: WAITING → IN_PROGRESS là gọi lượt (#8). "Xong" (#10 hoàn tất lượt) là T37 nên cột đó chỉ để chỗ. */
export type GroomColumn = 'WAITING' | 'IN_PROGRESS' | 'READY';
export const GROOM_COLUMNS: readonly GroomColumn[] = ['WAITING', 'IN_PROGRESS', 'READY'];
export const GROOM_DONE_HINT = 'Hoàn tất lượt: chưa nối API (T37)';

export interface MoveRejection {
  code: string;
  message: string;
}

/** Vì sao thẻ không sang được cột `to`, hoặc null khi FSM của lượt (BR-TN-09) và người phụ trách (BR-TN-05) cho phép. BE vẫn kiểm lại. */
export function moveRejection(visit: Visit, to: GroomColumn, userId: number): MoveRejection | null {
  const from: GroomColumn = visit.status === 'IN_PROGRESS' ? 'IN_PROGRESS' : 'WAITING';
  if (from === to) return { code: 'NO_MOVE', message: '' };
  if (to === 'WAITING') return { code: 'BR-TN-09', message: 'Thẻ đã bắt đầu không quay lại cột trước được.' };
  if (to === 'READY') {
    if (from === 'WAITING') return { code: 'BR-TN-09', message: 'Bắt đầu làm trước khi báo xong.' };
    if (visit.assigneeId !== userId) return { code: 'ACCESS_DENIED_SCOPE', message: 'Chỉ người phụ trách được báo xong.' };
    return { code: 'T37_PENDING', message: GROOM_DONE_HINT };
  }
  if (visit.assigneeId !== userId) {
    return {
      code: 'BR-TN-05',
      message: visit.assigneeId === null ? 'Lượt chưa được gán. Nhờ lễ tân gán cho bạn trước khi gọi.' : 'Lượt này đã gán cho nhân viên khác.',
    };
  }
  return null;
}

/**
 * Trạng thái bảng grooming: "Của tôi" đọc hàng đợi của tôi (#3), "Cả khu" đọc hàng đợi chi nhánh lọc nhóm Thẩm mỹ (#2). Thứ tự thẻ là thứ tự mảng BE trả.
 * Hook ghi của API không cập nhật lạc quan, nên thẻ vừa bấm Bắt đầu được giữ ở "Đang làm" tại chỗ (`calling`) cho tới khi lượt gọi xong và hàng đợi đã tải lại;
 * BE từ chối thì thẻ tự về cột cũ.
 */
export function useKanban(userId: number, scope: BoardScope) {
  const perms = visitPermissions(useAccount()?.role);
  const mine = useMyQueue(scope === 'mine' && perms.myQueue);
  const all = useVisitQueue({ serviceGroup: 'GROOMING' }, scope === 'all' && perms.queue);
  const query = scope === 'mine' ? mine : all;
  const call = useCallVisit();
  const [calling, setCalling] = useState<ReadonlySet<number>>(new Set());

  const columns = useMemo(() => {
    const grooming = (query.data ?? []).filter((v) => v.serviceGroup === 'GROOMING');
    const columnOf = (v: Visit): GroomColumn => (v.status === 'IN_PROGRESS' || calling.has(v.visitId) ? 'IN_PROGRESS' : 'WAITING');
    return GROOM_COLUMNS.map((id) => ({ id, items: id === 'READY' ? [] : grooming.filter((v) => columnOf(v) === id) }));
  }, [query.data, calling]);

  const moveCard = (visit: Visit, to: GroomColumn): MoveRejection | null => {
    const rejection = moveRejection(visit, to, userId);
    if (rejection) return rejection;
    const { visitId } = visit;
    setCalling((prev) => new Set(prev).add(visitId));
    call.mutate(visitId, {
      onError: (e) => toast.error(visitErrorMessage(e, 'call')),
      onSettled: () =>
        setCalling((prev) => {
          const next = new Set(prev);
          next.delete(visitId);
          return next;
        }),
    });
    return null;
  };

  return { columns, query, moveCard, calling };
}
