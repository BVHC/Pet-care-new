import { GroomingBoard } from '../../components/staff/grooming/GroomingBoard';
import { WorkspaceHeader } from '../../components/staff/ui';
import { useStaffUser } from '../../shared/hooks/useStaffUser';
import { useMyQueue } from '../../shared/hooks/useVisits';
import { useGroomingStore } from '../../shared/stores/grooming.store';
import { visitPermissions } from '../../shared/types/visit';
import type { BoardScope } from '../../shared/utils/grooming-board';

const SCOPES: { id: BoardScope; label: string }[] = [
  { id: 'mine', label: 'Của tôi' },
  { id: 'all', label: 'Cả khu' },
];

/**
 * Khu grooming: một thẻ mỗi bé theo hàng đợi của API; bấm Bắt đầu hoặc kéo sang "Đang làm" là gọi lượt (visit-v1 #8), bấm Xong hoặc kéo sang "Xong hôm nay" là hoàn tất lượt
 * (#10, T37, UC52): Order chuyển sang chờ thu ở quầy.
 */
export function GroomingWorkspacePage() {
  const user = useStaffUser();
  const stored = useGroomingStore((s) => s.scope);
  const setScope = useGroomingStore((s) => s.setScope);
  const scope: BoardScope = stored ?? (user.role === 'CARETAKER' ? 'mine' : 'all');
  // Hàng đợi của tôi chỉ có lượt WAITING / IN_PROGRESS gán cho mình (cùng khóa cache với bảng "Của tôi").
  const mineActive = useMyQueue(visitPermissions(user.role).myQueue).data?.filter((v) => v.serviceGroup === 'GROOMING').length ?? 0;

  return (
    <div className="flex flex-col lg:h-full">
      <WorkspaceHeader
        title="Khu grooming"
        subtitle={mineActive ? `${user.name}, ${mineActive} bé đang chờ hoặc đang làm` : `${user.name}, chưa có bé nào được gán`}
      >
        <div role="radiogroup" aria-label="Thẻ hiển thị" className="grid grid-cols-2 gap-1 rounded-lg border border-(--ws-line) bg-(--ws-canvas) p-1">
          {SCOPES.map((s) => (
            <button
              key={s.id}
              type="button"
              role="radio"
              aria-checked={scope === s.id}
              onClick={() => setScope(s.id)}
              className="h-8 rounded-md px-3 text-sm font-medium text-(--ws-ink-2) aria-checked:bg-(--ws-surface) aria-checked:font-semibold aria-checked:text-(--ws-ink) aria-checked:shadow-[0_1px_2px_rgba(0,0,0,0.08)]"
            >
              {s.label}
            </button>
          ))}
        </div>
      </WorkspaceHeader>
      <GroomingBoard userId={Number(user.id)} scope={scope} />
    </div>
  );
}
