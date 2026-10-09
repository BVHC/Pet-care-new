import { GroomingBoard } from '../../components/staff/grooming/GroomingBoard';
import { WorkspaceHeader } from '../../components/staff/ui';
import { useTodayVisits } from '../../shared/hooks/useClinic';
import { useStaffUser } from '../../shared/hooks/useStaffUser';
import { useGroomingStore } from '../../shared/stores/grooming.store';
import type { BoardScope } from '../../shared/utils/grooming-board';

const SCOPES: { id: BoardScope; label: string }[] = [
  { id: 'mine', label: 'Của tôi' },
  { id: 'all', label: 'Cả khu' },
];

/** Khu grooming: one card per pet, moved along the visit FSM by drag or by its buttons. */
export function GroomingWorkspacePage() {
  const user = useStaffUser();
  const stored = useGroomingStore((s) => s.scope);
  const setScope = useGroomingStore((s) => s.setScope);
  const scope: BoardScope = stored ?? (user.role === 'CARETAKER' ? 'mine' : 'all');
  const visits = useTodayVisits();
  const mineActive =
    visits.data?.filter(
      (v) =>
        v.assignee?.id === user.id &&
        v.services.some((s) => s.kind === 'GROOMING') &&
        (v.status === 'WAITING' || v.status === 'IN_PROGRESS'),
    ).length ?? 0;

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
      <GroomingBoard userId={user.id} scope={scope} />
    </div>
  );
}
