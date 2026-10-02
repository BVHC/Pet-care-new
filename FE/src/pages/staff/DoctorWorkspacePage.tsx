import { useMemo, useState } from 'react';
import { cn } from '../../lib/utils';
import { DoctorQueue } from '../../components/staff/doctor/DoctorQueue';
import { PatientPanel } from '../../components/staff/doctor/PatientPanel';
import { SoapPanel } from '../../components/staff/doctor/SoapPanel';
import { WorkspaceHeader } from '../../components/staff/ui';
import { useMoveVisit, useTodayVisits, useVisitDetail } from '../../shared/hooks/useClinic';
import { useHotkeys } from '../../shared/hooks/useHotkeys';
import { useMediaQuery } from '../../shared/hooks/useMediaQuery';
import { useStaffUser } from '../../shared/hooks/useStaffUser';
import { useConsultation } from '../../shared/stores/consultation.store';
import type { VisitView } from '../../shared/types/clinic';

type Pane = 'queue' | 'patient' | 'soap';

const PANES: { id: Pane; label: string; className?: string }[] = [
  { id: 'queue', label: 'Hàng chờ', className: 'lg:hidden' },
  { id: 'patient', label: 'Bệnh nhân' },
  { id: 'soap', label: 'Bệnh án' },
];

/**
 * Phòng khám: hàng chờ | bệnh nhân | bệnh án (SOAP).
 * ≥1280px three columns; 1024–1279 queue + one switchable column; phones one pane at a time.
 */
export function DoctorWorkspacePage() {
  const user = useStaffUser();
  const visits = useTodayVisits();
  const activeVisitId = useConsultation((s) => s.activeVisitId);
  const select = useConsultation((s) => s.select);
  const move = useMoveVisit(user.id);
  const [pane, setPane] = useState<Pane>('queue');
  const wide = useMediaQuery('(min-width: 1280px)');
  const soapVisible = wide || pane === 'soap';
  // Ctrl+S / Ctrl+Enter act on the record only while it is on screen; otherwise they bring it up.
  useHotkeys(soapVisible ? {} : { 'mod+enter': () => setPane('soap'), 'mod+s': () => setPane('soap') });

  const clinical = useMemo(
    () => visits.data?.filter((v) => v.services.some((s) => s.kind !== 'GROOMING')) ?? [],
    [visits.data],
  );
  const mine = clinical.filter((v) => v.assignee?.id === user.id);
  const waitingCount = mine.filter((v) => v.status === 'WAITING').length;

  // Open on the visit being examined, else the next one waiting.
  const fallback = mine.find((v) => v.status === 'IN_PROGRESS') ?? mine.find((v) => v.status === 'WAITING');
  const selectedId =
    activeVisitId && clinical.some((v) => v.id === activeVisitId) ? activeVisitId : (fallback?.id ?? null);
  const detail = useVisitDetail(selectedId);

  const open = (visit: VisitView) => {
    select(visit.id);
    setPane(visit.status === 'IN_PROGRESS' ? 'soap' : 'patient');
  };

  const call = (visitId: string) => {
    select(visitId);
    move.mutate({ visitId, to: 'IN_PROGRESS' }, { onSuccess: () => setPane('soap') });
  };

  const onCompleted = (visitId: string) => {
    const next = mine.find((v) => v.status === 'WAITING' && v.id !== visitId);
    select(next?.id ?? visitId);
    setPane(next ? 'patient' : 'soap');
  };

  return (
    <div className="flex flex-col lg:h-full">
      <WorkspaceHeader
        title="Phòng khám"
        subtitle={`${user.name}, ${waitingCount === 0 ? 'không có lượt nào đang chờ' : `${waitingCount} lượt đang chờ bạn`}`}
      />

      <div role="group" aria-label="Khu vực phòng khám" className="flex gap-1 border-b border-(--ws-line) bg-(--ws-surface) px-3 py-2 xl:hidden">
        {PANES.map((p) => (
          <button
            key={p.id}
            type="button"
            aria-pressed={pane === p.id || (p.id === 'patient' && pane === 'queue')}
            onClick={() => setPane(p.id)}
            className={cn(
              'h-9 flex-1 rounded-md text-sm font-medium text-(--ws-ink-2) aria-pressed:bg-(--ws-accent-soft) aria-pressed:font-semibold aria-pressed:text-(--ws-accent-ink)',
              p.id === 'patient' && pane === 'queue' && 'max-lg:aria-pressed:bg-transparent max-lg:aria-pressed:font-medium max-lg:aria-pressed:text-(--ws-ink-2)',
              p.className,
            )}
          >
            {p.label}
          </button>
        ))}
      </div>

      <div className="grid min-h-0 flex-1 gap-3 p-3 lg:grid-cols-[272px_minmax(0,1fr)] xl:grid-cols-[272px_minmax(0,0.85fr)_minmax(0,1.2fr)]">
        <DoctorQueue
          className={cn(pane === 'queue' ? 'flex' : 'hidden', 'min-h-[60dvh] lg:flex lg:min-h-0')}
          query={visits}
          visits={clinical}
          userId={user.id}
          selectedId={selectedId}
          onSelect={open}
          onCall={(v) => call(v.id)}
          callingId={move.isPending ? (move.variables?.visitId ?? null) : null}
        />
        <PatientPanel
          className={cn(pane === 'patient' ? 'flex' : 'hidden', pane === 'soap' ? 'lg:hidden' : 'lg:flex', 'min-h-[60dvh] lg:min-h-0 xl:flex')}
          selectedId={selectedId}
          detail={detail}
          userId={user.id}
          onCall={call}
          calling={move.isPending}
        />
        <SoapPanel
          className={cn(pane === 'soap' ? 'flex' : 'hidden', pane === 'soap' && 'lg:flex', 'min-h-[60dvh] lg:min-h-0 xl:flex')}
          selectedId={selectedId}
          detail={detail}
          userId={user.id}
          onCompleted={onCompleted}
          hotkeysEnabled={soapVisible}
        />
      </div>
    </div>
  );
}
