import { useState } from 'react';
import { CalendarX, ChevronLeft, ChevronRight, Plus } from 'lucide-react';
import { NoticeBar, selectClass, type Notice } from '@/components/admin/form-ui';
import { Pager } from '@/components/admin/Pager';
import { QueryError, RowsSkeleton, StatePanel } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { StatusBadge } from '@/components/ui/modal-templates';
import { cn } from '@/lib/utils';
import { toApiError } from '@/shared/api/api-error';
import { workforceApi } from '@/shared/api/workforce.api';
import { useStaff } from '@/shared/hooks/useAdminIdentity';
import { useShiftAssignments, useWorkforceMutation } from '@/shared/hooks/useWorkforce';
import { SHIFT_ASSIGNMENT_STATUSES, type ReasonRequest, type ShiftAssignment, type ShiftAssignmentStatus } from '@/shared/types/workforce';
import { addDays, isoDayOfWeek } from '@/shared/utils/branch-schedule';
import { vnDate } from '@/shared/utils/vn-time';
import { AssignDialog } from './AssignDialog';
import { ReasonDialog } from './FormDialog';
import { ASSIGNMENT_STATUS_LABELS, ASSIGNMENT_STATUS_TONE, dayLabel, hasStarted, shiftTime } from './labels';

const PAGE_SIZE = 100;
const weekStartOf = (iso: string) => addDays(iso, 1 - isoDayOfWeek(iso));
const dateInput = 'h-10 rounded-lg border border-(--color-border-default) bg-white px-3 text-sm';

type Acting = { kind: 'cancel' | 'absent'; a: ShiftAssignment };
type ReasonArgs = { id: number; body: ReasonRequest };

/**
 * Phân ca của chi nhánh (UC34, UC38 — workforce-v1 #4–7): lịch theo khoảng ngày (mặc định tuần này, tối đa 62 ngày), phân ca, hủy phân ca
 * (chỉ khi ca chưa bắt đầu) và ghi vắng đột xuất (chỉ ca đã tới ngày). Hạn 7 ngày của ghi vắng do máy chủ kiểm (BR-NS-07).
 */
export function AssignmentsTab() {
  const today = vnDate();
  const [from, setFrom] = useState(() => weekStartOf(today));
  const [to, setTo] = useState(() => addDays(weekStartOf(today), 6));
  const [staffId, setStaffId] = useState('');
  const [status, setStatus] = useState<ShiftAssignmentStatus | ''>('');
  const [page, setPage] = useState(0);
  const [assigning, setAssigning] = useState(false);
  const [acting, setActing] = useState<Acting | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  // `size` bắt buộc: BE mặc định 20 dòng nên danh sách nhân viên dài sẽ bị cắt.
  const staffList = useStaff({ size: 100, status: 'ACTIVE' });
  const staff = (staffList.data?.content ?? []).filter((s) => !s.isLocked);
  const range = from && to;
  const list = useShiftAssignments({ from, to, staffId: staffId ? Number(staffId) : undefined, status: status || undefined, page, size: PAGE_SIZE }, Boolean(range));
  const cancel = useWorkforceMutation(({ id, body }: ReasonArgs) => workforceApi.cancelShiftAssignment(id, body));
  const absent = useWorkforceMutation(({ id, body }: ReasonArgs) => workforceApi.recordShiftAbsence(id, body));
  const data = list.data;
  const stale = list.isPlaceholderData;

  const reset = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    setPage(0);
    setNotice(null);
  };
  const shiftWeek = (days: number) => {
    setFrom(addDays(from || weekStartOf(today), days));
    setTo(addDays(to || addDays(weekStartOf(today), 6), days));
    setPage(0);
    setNotice(null);
  };
  const done = (text: string) => {
    setActing(null);
    setNotice({ kind: 'success', text });
  };

  let body;
  if (!range) body = <StatePanel icon={CalendarX} title="Chọn khoảng ngày" body="Nhập cả ngày bắt đầu và ngày kết thúc (tối đa 62 ngày)." />;
  else if (!data && list.isError) body = <QueryError error={toApiError(list.error)} onRetry={() => list.refetch()} what="lịch phân ca" />;
  else if (!data) body = <RowsSkeleton rows={5} />;
  else if (data.content.length === 0) {
    body = <StatePanel icon={CalendarX} title="Không có phân ca nào" body={staff.length === 0 && staffList.isSuccess ? 'Chi nhánh chưa có nhân viên để phân ca.' : 'Chưa có ai được phân ca trong khoảng này.'} />;
  } else {
    body = (
      <>
        <table className={cn('table min-w-[820px]', stale && 'opacity-60')} aria-label="Lịch phân ca" aria-busy={stale}>
          <thead>
            <tr className="[&>th]:whitespace-nowrap">
              <th>Ngày</th>
              <th>Ca</th>
              <th>Nhân viên</th>
              <th>Trạng thái</th>
              <th className="text-right!">Thao tác</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((a) => {
              const open = a.status === 'SCHEDULED';
              return (
                <tr key={a.shiftAssignmentId}>
                  <td className="whitespace-nowrap align-top">
                    <time dateTime={a.workDate}>{dayLabel(a.workDate)}</time>
                  </td>
                  <td className="align-top">
                    <span className="block font-medium text-(--text-primary)">{a.shiftName}</span>
                    <span className="text-xs tabular-nums text-(--text-secondary)">{shiftTime(a)}</span>
                  </td>
                  <td className="align-top">{a.staffName}</td>
                  <td className="align-top">
                    <span className="flex flex-wrap items-center gap-1.5">
                      <StatusBadge status={ASSIGNMENT_STATUS_LABELS[a.status]} variant={ASSIGNMENT_STATUS_TONE[a.status]} />
                      {a.pendingRequestId != null && <StatusBadge status="Có yêu cầu chờ duyệt" variant="warning" />}
                    </span>
                    {a.reason && <span className="mt-1 block text-xs text-(--text-secondary)">Lý do: {a.reason}</span>}
                  </td>
                  <td className="align-top">
                    <div className="flex justify-end gap-1.5">
                      {open && !hasStarted(a) && (
                        <Button size="sm" variant="outline" disabled={stale} onClick={() => setActing({ kind: 'cancel', a })} aria-label={`Hủy phân ca: ${a.staffName}, ${a.workDate}`}>
                          Hủy phân ca
                        </Button>
                      )}
                      {open && a.workDate <= today && (
                        <Button size="sm" variant="outline" className="border-red-200 text-red-600" disabled={stale} onClick={() => setActing({ kind: 'absent', a })} aria-label={`Ghi vắng: ${a.staffName}, ${a.workDate}`}>
                          Ghi vắng
                        </Button>
                      )}
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <Pager data={data} unit="phân ca" onPage={(p) => { setPage(p); setNotice(null); }} />
      </>
    );
  }

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <Button size="sm" variant="outline" onClick={() => shiftWeek(-7)} aria-label="Tuần trước">
            <ChevronLeft className="h-4 w-4" aria-hidden /> Tuần trước
          </Button>
          <Button size="sm" variant="outline" onClick={() => { setFrom(weekStartOf(today)); setTo(addDays(weekStartOf(today), 6)); setPage(0); }}>
            Tuần này
          </Button>
          <Button size="sm" variant="outline" onClick={() => shiftWeek(7)} aria-label="Tuần sau">
            Tuần sau <ChevronRight className="h-4 w-4" aria-hidden />
          </Button>
          <label className="flex items-center gap-2 text-sm text-(--text-secondary)">
            Từ ngày
            <input type="date" className={dateInput} value={from} max={to || undefined} onChange={(e) => reset(setFrom)(e.target.value)} />
          </label>
          <label className="flex items-center gap-2 text-sm text-(--text-secondary)">
            Đến ngày
            <input type="date" className={dateInput} value={to} min={from || undefined} onChange={(e) => reset(setTo)(e.target.value)} />
          </label>
          <select aria-label="Lọc nhân viên" className={cn(selectClass, 'w-48')} value={staffId} onChange={(e) => reset(setStaffId)(e.target.value)}>
            <option value="">Mọi nhân viên</option>
            {staff.map((s) => (
              <option key={s.accountId} value={s.accountId}>
                {s.fullName}
              </option>
            ))}
          </select>
          <select aria-label="Lọc trạng thái phân ca" className={cn(selectClass, 'w-40')} value={status} onChange={(e) => reset(setStatus)(e.target.value as ShiftAssignmentStatus | '')}>
            <option value="">Mọi trạng thái</option>
            {SHIFT_ASSIGNMENT_STATUSES.map((s) => (
              <option key={s} value={s}>
                {ASSIGNMENT_STATUS_LABELS[s]}
              </option>
            ))}
          </select>
          <Button className="ml-auto" onClick={() => setAssigning(true)} disabled={staff.length === 0}>
            <Plus className="h-4 w-4" aria-hidden /> Phân ca
          </Button>
        </CardContent>
      </Card>
      <NoticeBar notice={notice} />
      <Card className="card">
        <CardContent className="overflow-x-auto p-0">{body}</CardContent>
      </Card>
      {assigning && <AssignDialog staff={staff} defaultDate={from && from > today ? from : today} onClose={() => setAssigning(false)} onDone={(t) => { setAssigning(false); setNotice({ kind: 'success', text: t }); }} />}
      {acting?.kind === 'cancel' && (
        <ReasonDialog
          title="Hủy phân ca"
          description={`${acting.a.staffName} · ${acting.a.shiftName} · ${acting.a.workDate}`}
          label="Lý do hủy"
          submitText="Hủy phân ca"
          onConfirm={(reason) => cancel.mutateAsync({ id: acting.a.shiftAssignmentId, body: { reason } })}
          onClose={() => setActing(null)}
          onDone={() => done(`Đã hủy phân ca của ${acting.a.staffName}.`)}
        />
      )}
      {acting?.kind === 'absent' && (
        <ReasonDialog
          title="Ghi nhận vắng đột xuất"
          description={`${acting.a.staffName} · ${acting.a.shiftName} · ${acting.a.workDate}`}
          label="Lý do vắng"
          submitText="Ghi vắng"
          onConfirm={(reason) => absent.mutateAsync({ id: acting.a.shiftAssignmentId, body: { reason } })}
          onClose={() => setActing(null)}
          onDone={() => done(`Đã ghi vắng cho ${acting.a.staffName}.`)}
        />
      )}
    </div>
  );
}
