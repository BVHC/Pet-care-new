// Khung bệnh nhân của lượt đang chọn ở bàn Phòng khám (UC46–UC49, UC53): thông tin lượt đọc từ API visit, gọi lượt / cấp cứu khi còn chờ, rồi bệnh án khi đã gọi hoặc đã hoàn tất.
import { PawPrint } from 'lucide-react';
import { SPECIES_LABEL } from '../../../domains/pets/labels';
import { useNow } from '../../../shared/hooks/useNow';
import { VISIT_GROUP_LABELS, type Visit, type VisitPermissions } from '../../../shared/types/visit';
import { formatClock, formatElapsed } from '../../../shared/utils/clinic-format';
import { Panel, StatePanel, VisitStatusChip, WsButton } from '../ui';
import { VisitBadges, VisitCode } from '../visit-ui';
import { MedicalRecordEditor } from './MedicalRecordEditor';

interface Props {
  className?: string;
  visit: Visit | null;
  userId: number;
  perms: VisitPermissions;
  onCall: (visit: Visit) => void;
  onMarkEmergency: (visit: Visit) => void;
  onCompleted: (visit: Visit) => void;
  busy: boolean;
}

export function ExamPanel({ className, visit, userId, perms, onCall, onMarkEmergency, onCompleted, busy }: Props) {
  const now = useNow();

  if (!visit) {
    return (
      <Panel label="Lượt đang chọn" className={className}>
        <StatePanel icon={PawPrint} title="Chưa chọn bệnh nhân" body="Chọn một lượt trong hàng chờ để xem thông tin." />
      </Panel>
    );
  }

  const waiting = visit.status === 'WAITING';
  const elapsed = waiting
    ? `Đã chờ ${formatElapsed(visit.checkedInAt, now)}`
    : visit.status === 'COMPLETED'
      ? `Hoàn tất lúc ${formatClock(visit.completedAt ?? visit.checkedInAt)}`
      : `Đã khám ${formatElapsed(visit.calledAt ?? visit.checkedInAt, now)}`;

  const summary = (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 px-4 py-4 text-sm">
      <dt className="text-(--ws-ink-2)">Chủ nuôi</dt>
      <dd className="font-medium">{visit.customerName}</dd>
      <dt className="text-(--ws-ink-2)">Dịch vụ</dt>
      <dd>{VISIT_GROUP_LABELS[visit.serviceGroup]}</dd>
      <dt className="text-(--ws-ink-2)">Tiếp nhận</dt>
      <dd className="ws-num">{elapsed}</dd>
      <dt className="text-(--ws-ink-2)">Phụ trách</dt>
      <dd>{visit.assigneeName ?? 'Chưa gán'}</dd>
    </dl>
  );

  return (
    <Panel label="Lượt đang chọn" className={className}>
      <div className="flex items-start gap-4 border-b border-(--ws-line) p-4">
        <div className="min-w-0">
          <h2 className="truncate text-2xl font-bold tracking-tight">{visit.petName}</h2>
          <p className="text-sm text-(--ws-ink-2)">{SPECIES_LABEL[visit.species]}</p>
          <div className="mt-2 flex flex-wrap gap-1.5">
            <VisitCode code={visit.code} urgent={visit.priorityClass === 'EMERGENCY'} />
            <VisitStatusChip status={visit.status} label={visit.status === 'IN_PROGRESS' ? 'Đang khám' : undefined} />
            <VisitBadges visit={visit} />
          </div>
        </div>
      </div>

      {waiting || visit.serviceGroup !== 'MEDICAL' ? (
        <div className="min-h-0 flex-1 overflow-y-auto">
          {summary}
          {waiting && (
            <div className="flex flex-col gap-2 px-4 pb-4">
              {visit.assigneeId === userId && (
                <WsButton variant="primary" size="lg" className="w-full" disabled={busy} onClick={() => onCall(visit)}>
                  Gọi {visit.petName} vào khám
                </WsButton>
              )}
              {perms.markEmergency && visit.priorityClass !== 'EMERGENCY' && (
                <WsButton variant="secondary" className="w-full" disabled={busy} onClick={() => onMarkEmergency(visit)}>
                  Cấp cứu
                </WsButton>
              )}
              <p role="note" className="rounded-lg bg-(--ws-raised) px-3 py-2 text-sm text-(--ws-ink-2)">
                {visit.assigneeId === userId ? 'Gọi lượt để ghi bệnh án.' : 'Lượt chưa gán cho bạn nên chưa ghi được bệnh án.'}
              </p>
            </div>
          )}
        </div>
      ) : (
        <MedicalRecordEditor visit={visit} userId={userId} perms={perms} summary={summary} onCompleted={onCompleted} />
      )}
    </Panel>
  );
}
