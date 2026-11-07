// Lịch khám tại nhà của chi nhánh (UC51; homevisit-v1 #3, #5, #6). Lịch tại nhà không vào hàng chờ phòng khám: bác sĩ tự bắt đầu đúng ngày hẹn (tạo Visit đang khám gán cho mình,
// mở Order có dòng phí khám tại nhà) hoặc ghi "không gặp được khách" (tính vào hạn chế đặt online của khách). Nút theo trạng thái và ngày: chỉ lịch BOOKED, chỉ đúng ngày hẹn.
import { useState } from 'react';
import { toast } from 'sonner';
import { Home } from 'lucide-react';
import { Field } from '../../admin/form-ui';
import { ClinicalDialog, textareaClass } from '../../clinical/ClinicalDialog';
import { formatCurrency } from '../../../lib/utils';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useHomeVisits, useMarkHomeVisitUnreachable, useStartHomeVisit } from '../../../shared/hooks/useHomeVisit';
import { HOME_VISIT_LIMITS, HOME_VISIT_STATUS_LABELS, type HomeVisitAppointmentSummary, type HomeVisitStatus } from '../../../shared/types/homevisit';
import { useAccount } from '../../../shared/stores/session.store';
import { vnDate } from '../../../shared/utils/vn-time';
import { Panel, PanelTitle, QueryError, RowsSkeleton, StatePanel, WsButton } from '../ui';

const TONE: Record<HomeVisitStatus, string | undefined> = { BOOKED: 'wait', CHECKED_IN: 'busy', COMPLETED: 'ready', CANCELLED: 'done', NO_SHOW: 'urgent' };

function UnreachableDialog({ visit, onClose, onDone }: { visit: HomeVisitAppointmentSummary; onClose: () => void; onDone: (message: string) => void }) {
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const mark = useMarkHomeVisitUnreachable();
  const submit = async () => {
    if (!note.trim()) return setError('Nhập ghi chú (đã gọi mấy lần, chờ bao lâu…).');
    try {
      await mark.mutateAsync({ appointmentId: visit.appointmentId, note: note.trim() });
      onDone(`Đã ghi không gặp được khách của ${visit.petName}.`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };
  return (
    <ClinicalDialog
      title="Không gặp được khách"
      description={`${visit.petName} · ${visit.homeAddress}. Lịch chuyển sang "Không gặp được khách" và tính vào hạn chế đặt online của khách.`}
      onClose={onClose}
      onSubmit={() => void submit()}
      submitText="Ghi nhận"
      cancelText="Quay lại"
      pending={mark.isPending}
      error={error}
    >
      <Field label="Ghi chú" hint={`${note.length}/${HOME_VISIT_LIMITS.note}`}>
        {(id) => <textarea id={id} rows={3} maxLength={HOME_VISIT_LIMITS.note} className={textareaClass} value={note} onChange={(e) => setNote(e.target.value)} />}
      </Field>
    </ClinicalDialog>
  );
}

export function HomeVisitQueue({ className, onStarted }: { className?: string; onStarted: (visitId: number) => void }) {
  const today = vnDate();
  // Bắt đầu và ghi "không gặp được" là việc của VET (homevisit-v1 #5, #6); vai trò khác chỉ xem danh sách.
  const canAct = useAccount()?.role === 'VET';
  const [date, setDate] = useState(today);
  const [unreachable, setUnreachable] = useState<HomeVisitAppointmentSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const list = useHomeVisits({ date, size: 50 });
  const start = useStartHomeVisit();
  const rows = list.data?.content ?? [];
  const booked = rows.filter((r) => r.status === 'BOOKED').length;

  const startVisit = async (v: HomeVisitAppointmentSummary) => {
    setError(null);
    try {
      const started = await start.mutateAsync(v.appointmentId);
      toast.success(`Đã bắt đầu khám tại nhà cho ${v.petName}.`);
      onStarted(started.visitId);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  let body;
  if (list.isError && !list.data) body = <QueryError error={list.error} onRetry={() => list.refetch()} what="lịch khám tại nhà" />;
  else if (!list.data) body = <RowsSkeleton rows={2} />;
  else if (rows.length === 0) body = <StatePanel icon={Home} title="Không có lịch khám tại nhà" body="Chọn ngày khác để xem lịch." />;
  else {
    body = (
      <ul aria-label="Lịch khám tại nhà" className="divide-y divide-(--ws-line)">
        {rows.map((v) => {
          const actionable = v.status === 'BOOKED' && v.slotDate === today;
          return (
            <li key={v.appointmentId} className="px-4 py-3 text-sm">
              <p className="flex flex-wrap items-center gap-1.5">
                <time className="ws-num font-bold" dateTime={`${v.slotDate}T${v.slotStart}:00+07:00`}>
                  {v.slotStart}
                </time>
                <span className="font-semibold">{v.petName}</span>
                <span className="ws-chip" data-tone={TONE[v.status]}>
                  {HOME_VISIT_STATUS_LABELS[v.status]}
                </span>
              </p>
              <p className="text-[13px] text-(--ws-ink-2)">{v.serviceName}</p>
              <p className="text-[13px] text-(--ws-ink-2)">{v.homeAddress}</p>
              <p className="ws-num text-xs text-(--ws-ink-3)">
                {v.code} · phí tại nhà {formatCurrency(v.homeFee)}
              </p>
              {canAct && v.status === 'BOOKED' && (
                <div className="mt-2 flex flex-wrap gap-2">
                  <WsButton size="sm" variant="primary" disabled={!actionable || start.isPending} aria-label={`Bắt đầu khám tại nhà: ${v.petName}`} onClick={() => void startVisit(v)}>
                    Bắt đầu khám tại nhà
                  </WsButton>
                  <WsButton size="sm" variant="danger" disabled={!actionable} aria-label={`Không gặp được khách: ${v.petName}`} onClick={() => setUnreachable(v)}>
                    Không gặp được khách
                  </WsButton>
                  {!actionable && <p className="w-full text-xs text-(--ws-ink-3)">Chỉ bắt đầu hoặc ghi nhận được đúng ngày hẹn.</p>}
                </div>
              )}
            </li>
          );
        })}
      </ul>
    );
  }

  return (
    <Panel label="Khám tại nhà" className={className}>
      <PanelTitle
        aside={
          <span className="ws-num text-sm text-(--ws-ink-2)">
            <label className="sr-only" htmlFor="home-visit-date">
              Ngày khám tại nhà
            </label>
            <input id="home-visit-date" type="date" value={date} onChange={(e) => { setDate(e.target.value || today); setError(null); }} className="mr-2 h-8 rounded-md border border-(--ws-line-strong) bg-(--ws-surface) px-1.5 text-xs" />
            {booked} chờ khám
          </span>
        }
      >
        Khám tại nhà
      </PanelTitle>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {body}
        {error && (
          <p role="alert" className="border-t border-(--ws-line) px-4 py-3 text-sm font-medium text-(--ws-urgent-ink)">
            {error}
          </p>
        )}
      </div>
      {unreachable && (
        <UnreachableDialog
          visit={unreachable}
          onClose={() => setUnreachable(null)}
          onDone={(message) => {
            setUnreachable(null);
            setError(null);
            toast.success(message);
          }}
        />
      )}
    </Panel>
  );
}
