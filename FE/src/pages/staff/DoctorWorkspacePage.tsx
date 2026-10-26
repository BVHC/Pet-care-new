import { useState } from 'react';
import { toast } from 'sonner';
import { cn } from '../../lib/utils';
import { DoctorQueue } from '../../components/staff/doctor/DoctorQueue';
import { ExamPendingPanel } from '../../components/staff/doctor/ExamPendingPanel';
import { WorkspaceHeader } from '../../components/staff/ui';
import { useStaffUser } from '../../shared/hooks/useStaffUser';
import { useCallVisit, useMarkEmergency, useMyQueue, visitErrorMessage } from '../../shared/hooks/useVisits';
import { visitPermissions, type Visit } from '../../shared/types/visit';

type Pane = 'queue' | 'patient';

const PANES: { id: Pane; label: string }[] = [
  { id: 'queue', label: 'Hàng chờ' },
  { id: 'patient', label: 'Bệnh nhân' },
];

/**
 * Phòng khám: hàng chờ của tôi (visit-v1 #3) | lượt đang chọn. Gọi lượt (#8) và đánh dấu cấp cứu (#7) chạy trên API; ghi bệnh án và hoàn tất lượt là T37, nên cột bệnh án
 * (SoapPanel, đang trên clinic-db) chưa đặt vào đây. ≥1024px hai cột; điện thoại một khung mỗi lần.
 */
export function DoctorWorkspacePage() {
  const user = useStaffUser();
  const userId = Number(user.id);
  const perms = visitPermissions(user.role);
  const queue = useMyQueue(perms.myQueue);
  const call = useCallVisit();
  const emergency = useMarkEmergency();
  const [picked, setPicked] = useState<number | null>(null);
  const [pane, setPane] = useState<Pane>('queue');

  const visits = queue.data ?? [];
  const waitingCount = visits.filter((v) => v.status === 'WAITING').length;
  // Open on the visit being examined, else the next one waiting.
  const selected =
    visits.find((v) => v.visitId === picked) ?? visits.find((v) => v.status === 'IN_PROGRESS') ?? visits.find((v) => v.status === 'WAITING') ?? null;

  const open = (visit: Visit) => {
    setPicked(visit.visitId);
    setPane('patient');
  };

  const callVisit = (visit: Visit) => {
    setPicked(visit.visitId);
    call.mutate(visit.visitId, {
      onSuccess: (done) => {
        toast.success(`Đã gọi ${done.petName} (${done.code}) vào khám.`);
        setPane('patient');
      },
      onError: (e) => toast.error(visitErrorMessage(e, 'call')),
    });
  };

  const markEmergency = (visit: Visit) =>
    emergency.mutate(visit.visitId, {
      onSuccess: (done) => toast.success(`Đã xác định ca cấp cứu: ${done.petName} (${done.code}).`),
      onError: (e) => toast.error(visitErrorMessage(e, 'markEmergency')),
    });

  return (
    <div className="flex flex-col lg:h-full">
      <WorkspaceHeader
        title="Phòng khám"
        subtitle={`${user.name}, ${waitingCount === 0 ? 'không có lượt nào đang chờ' : `${waitingCount} lượt đang chờ bạn`}`}
      />

      <div role="group" aria-label="Khu vực phòng khám" className="flex gap-1 border-b border-(--ws-line) bg-(--ws-surface) px-3 py-2 lg:hidden">
        {PANES.map((p) => (
          <button
            key={p.id}
            type="button"
            aria-pressed={pane === p.id}
            onClick={() => setPane(p.id)}
            className="h-9 flex-1 rounded-md text-sm font-medium text-(--ws-ink-2) aria-pressed:bg-(--ws-accent-soft) aria-pressed:font-semibold aria-pressed:text-(--ws-accent-ink)"
          >
            {p.label}
          </button>
        ))}
      </div>

      <div className="grid min-h-0 flex-1 gap-3 p-3 lg:grid-cols-[272px_minmax(0,1fr)]">
        <DoctorQueue
          className={cn(pane === 'queue' ? 'flex' : 'hidden', 'min-h-[60dvh] lg:flex lg:min-h-0')}
          query={queue}
          userId={userId}
          selectedId={selected?.visitId ?? null}
          onSelect={open}
          onCall={callVisit}
          callingId={call.isPending ? (call.variables ?? null) : null}
        />
        <ExamPendingPanel
          className={cn(pane === 'patient' ? 'flex' : 'hidden', 'min-h-[60dvh] lg:flex lg:min-h-0')}
          visit={selected}
          userId={userId}
          canMarkEmergency={perms.markEmergency}
          onCall={callVisit}
          onMarkEmergency={markEmergency}
          busy={call.isPending || emergency.isPending}
        />
      </div>
    </div>
  );
}
