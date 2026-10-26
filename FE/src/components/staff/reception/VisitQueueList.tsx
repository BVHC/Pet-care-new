// Hàng đợi của chi nhánh (visit-v1 #2) theo đúng thứ tự BE trả (BR-TN-04): không tự sắp lại. Lễ tân gán / đưa lên trước / cấp cứu / hủy lượt chưa gọi;
// BRANCH_MANAGER chỉ xem và gán lại lượt đã gọi mà người phụ trách không còn đủ điều kiện (BR-TN-08). Nút nào không thuộc vai trò thì không có (và không có request).
import { lazy, Suspense, useState } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import { ClipboardList } from 'lucide-react';
import { toast } from 'sonner';
import { cn } from '../../../lib/utils';
import { useFreshIds } from '../../../shared/hooks/useFreshIds';
import { useNow } from '../../../shared/hooks/useNow';
import { useAssignableStaff, useMarkEmergency, visitErrorMessage } from '../../../shared/hooks/useVisits';
import { useAccount } from '../../../shared/stores/session.store';
import { VISIT_GROUP_LABELS, visitPermissions, type Visit, type VisitServiceGroup } from '../../../shared/types/visit';
import { formatElapsed } from '../../../shared/utils/clinic-format';
import { QueryError, RowsSkeleton, StatePanel, VisitStatusChip, WsButton } from '../ui';
import { OnlineDot, VisitBadges, VisitCode } from '../visit-ui';
import { moveTargets, reassignable } from './queue-rules';

// Hộp thoại thao tác chỉ tải khi lễ tân bấm nút, để bàn hiện ra nhanh.
const AssignVisitDialog = lazy(() => import('./VisitActionDialogs').then((m) => ({ default: m.AssignVisitDialog })));
const CancelVisitDialog = lazy(() => import('./VisitActionDialogs').then((m) => ({ default: m.CancelVisitDialog })));
const MoveVisitDialog = lazy(() => import('./VisitActionDialogs').then((m) => ({ default: m.MoveVisitDialog })));
const ReassignVisitDialog = lazy(() => import('./VisitActionDialogs').then((m) => ({ default: m.ReassignVisitDialog })));

type Open = { kind: 'assign' | 'move' | 'cancel' | 'reassign'; visit: Visit } | null;

/** Hiện diện và danh sách nhân viên gán được theo chức vụ: để vẽ chấm trực tuyến và biết người phụ trách có còn đủ điều kiện (cùng khóa cache với hộp thoại gán). */
function useStaffPresence(enabled: boolean) {
  const vets = useAssignableStaff('VET', enabled);
  const caretakers = useAssignableStaff('CARETAKER', enabled);
  const online = new Map([...vets.staff, ...caretakers.staff].map((s) => [s.accountId, s.online]));
  const listOf = (query: typeof vets) => (enabled && !query.isLoading && !query.isError ? new Set(query.staff.map((s) => s.accountId)) : null);
  const assignable: Record<VisitServiceGroup, ReadonlySet<number> | null> = { MEDICAL: listOf(vets), GROOMING: listOf(caretakers) };
  return { online, assignable };
}

export function VisitQueueList({ query }: { query: UseQueryResult<Visit[], unknown> }) {
  const perms = visitPermissions(useAccount()?.role);
  const presence = useStaffPresence(perms.assign || perms.reassign);
  const markEmergency = useMarkEmergency();
  const now = useNow();
  const fresh = useFreshIds(query.data, (v) => String(v.visitId));
  const [open, setOpen] = useState<Open>(null);

  if (query.isLoading) return <RowsSkeleton rows={4} />;
  if (query.isError) return <QueryError error={query.error} onRetry={() => query.refetch()} what="hàng chờ" />;
  const queue = query.data ?? [];
  if (queue.length === 0) {
    return <StatePanel icon={ClipboardList} title="Hàng chờ đang trống" body="Khách đến thì bấm Tiếp nhận ở tab Đón tiếp." />;
  }

  const emergency = (v: Visit) =>
    markEmergency.mutate(v.visitId, {
      onSuccess: (done) => toast.success(`Đã xác định ca cấp cứu: ${done.petName} (${done.code}).`),
      onError: (e) => toast.error(visitErrorMessage(e, 'markEmergency')),
    });

  const close = () => setOpen(null);
  const targets = open?.kind === 'move' ? moveTargets(queue, open.visit) : [];

  return (
    <>
      <ol className="divide-y divide-(--ws-line)" aria-label="Hàng chờ theo thứ tự ưu tiên">
        {queue.map((v) => {
          const waiting = v.status === 'WAITING';
          const online = v.assigneeId === null ? undefined : presence.online.get(v.assigneeId);
          const actions = [
            waiting && perms.assign && { label: v.assigneeId === null ? 'Gán người' : 'Đổi người', run: () => setOpen({ kind: 'assign', visit: v }) },
            perms.move && moveTargets(queue, v).length > 0 && { label: 'Đưa lên trước', run: () => setOpen({ kind: 'move', visit: v }) },
            waiting && perms.markEmergency && v.priorityClass !== 'EMERGENCY' && { label: 'Cấp cứu', run: () => emergency(v) },
            waiting && perms.cancel && { label: 'Hủy lượt', run: () => setOpen({ kind: 'cancel', visit: v }), danger: true },
            perms.reassign && reassignable(v, presence.assignable[v.serviceGroup]) && { label: 'Gán lại', run: () => setOpen({ kind: 'reassign', visit: v }) },
          ].filter((a): a is { label: string; run: () => void; danger?: boolean } => a !== false);

          return (
            <li key={v.visitId} className={cn('px-4 py-3', fresh.has(String(v.visitId)) && 'ws-fresh')}>
              <p className="flex flex-wrap items-center gap-1.5">
                <VisitCode code={v.code} urgent={v.priorityClass === 'EMERGENCY'} />
                <span data-testid="visit-pet" className="font-semibold">
                  {v.petName}
                </span>
                <VisitStatusChip status={v.status} label={v.status === 'IN_PROGRESS' ? 'Đã gọi' : undefined} />
                <VisitBadges visit={v} />
              </p>
              <p className="truncate text-[13px] text-(--ws-ink-2)">
                <span>{v.customerName}</span>, {VISIT_GROUP_LABELS[v.serviceGroup]}
              </p>
              <p className="flex flex-wrap items-center gap-1.5 text-[13px] text-(--ws-ink-2)">
                {v.assigneeId === null ? (
                  <span>Chưa gán</span>
                ) : (
                  <span className="inline-flex items-center gap-1.5">
                    {online !== undefined && <OnlineDot online={online} />}
                    {v.assigneeName}
                  </span>
                )}
                {v.needsReassign && (
                  <span className="ws-chip" data-tone="urgent" title="Người phụ trách bị khóa tài khoản (BR-QT-11): cần gán lại (BR-TN-08)">
                    Cần gán lại
                  </span>
                )}
                <span className="text-xs text-(--ws-ink-3)">
                  {waiting ? `Chờ ${formatElapsed(v.checkedInAt, now)}` : `Đã gọi: ${formatElapsed(v.calledAt ?? v.checkedInAt, now)}`}
                </span>
              </p>
              {actions.length > 0 && (
                <div className="mt-2 flex flex-wrap items-center gap-2">
                  {actions.map((a) => (
                    <WsButton key={a.label} size="sm" variant={a.danger ? 'ghost' : 'secondary'} onClick={a.run}>
                      {a.label}
                    </WsButton>
                  ))}
                </div>
              )}
            </li>
          );
        })}
      </ol>
      <Suspense fallback={null}>
        {open?.kind === 'assign' && <AssignVisitDialog visit={open.visit} onClose={close} />}
        {open?.kind === 'reassign' && <ReassignVisitDialog visit={open.visit} onClose={close} />}
        {open?.kind === 'cancel' && <CancelVisitDialog visit={open.visit} onClose={close} />}
        {open?.kind === 'move' && targets.length > 0 && <MoveVisitDialog visit={open.visit} targets={targets} onClose={close} />}
      </Suspense>
    </>
  );
}
