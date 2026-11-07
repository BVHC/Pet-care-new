import { useState } from 'react';
import { CalendarX } from 'lucide-react';
import { type Notice } from '@/components/admin/form-ui';
import { QueryError, RowsSkeleton, StatePanel } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { StatusBadge } from '@/components/ui/modal-templates';
import { toApiError } from '@/shared/api/api-error';
import { useMyShiftAssignments } from '@/shared/hooks/useWorkforce';
import type { ShiftAssignment } from '@/shared/types/workforce';
import { addDays } from '@/shared/utils/branch-schedule';
import { vnDate } from '@/shared/utils/vn-time';
import { ASSIGNMENT_STATUS_LABELS, ASSIGNMENT_STATUS_TONE, dayLabel, shiftTime } from './labels';
import { ShiftRequestDialog, type ShiftOption } from './ShiftRequestDialog';

const dateInput = 'h-10 rounded-lg border border-(--color-border-default) bg-white px-3 text-sm';

/** Ca đích của đổi ca: các ca khác xuất hiện trong lịch đang xem (nhân viên không có API liệt kê ca của chi nhánh, #1 chỉ dành cho quản lý). */
function otherShifts(rows: ShiftAssignment[], current: ShiftAssignment): ShiftOption[] {
  const seen = new Map<number, ShiftOption>();
  for (const r of rows) if (r.shiftDefinitionId !== current.shiftDefinitionId) seen.set(r.shiftDefinitionId, r);
  return [...seen.values()];
}

/** Lịch phân ca của tôi (UC35, workforce-v1 #8) + nút gửi yêu cầu nghỉ / đổi ca (#9) cho ca còn SCHEDULED, chưa có yêu cầu chờ, từ hôm nay. */
export function MyAssignments({ onNotice }: { onNotice: (n: Notice) => void }) {
  const today = vnDate();
  const [from, setFrom] = useState(today);
  const [to, setTo] = useState(() => addDays(today, 13));
  const [requesting, setRequesting] = useState<ShiftAssignment | null>(null);
  const list = useMyShiftAssignments({ from, to }, Boolean(from && to));
  const rows = list.data ?? [];

  let body;
  if (!from || !to) body = <StatePanel icon={CalendarX} title="Chọn khoảng ngày" body="Nhập cả ngày bắt đầu và ngày kết thúc (tối đa 62 ngày)." />;
  else if (!list.data && list.isError) body = <QueryError error={toApiError(list.error)} onRetry={() => list.refetch()} what="lịch làm việc" />;
  else if (!list.data) body = <RowsSkeleton rows={4} />;
  else if (rows.length === 0) body = <StatePanel icon={CalendarX} title="Chưa có ca nào trong khoảng này" body="Quản lý chi nhánh sẽ phân ca cho bạn; đổi khoảng ngày để xem tuần khác." />;
  else {
    body = (
      <table className="table min-w-[640px]" aria-label="Lịch làm việc của tôi">
        <thead>
          <tr>
            <th>Ngày</th>
            <th>Ca</th>
            <th>Trạng thái</th>
            <th className="text-right!">Thao tác</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((a) => (
            <tr key={a.shiftAssignmentId}>
              <td className="whitespace-nowrap align-top">
                <time dateTime={a.workDate}>{dayLabel(a.workDate)}</time>
              </td>
              <td className="align-top">
                <span className="block font-medium text-(--text-primary)">{a.shiftName}</span>
                <span className="text-xs tabular-nums text-(--text-secondary)">{shiftTime(a)}</span>
              </td>
              <td className="align-top">
                <span className="flex flex-wrap items-center gap-1.5">
                  <StatusBadge status={ASSIGNMENT_STATUS_LABELS[a.status]} variant={ASSIGNMENT_STATUS_TONE[a.status]} />
                  {a.pendingRequestId != null && <StatusBadge status="Đã gửi yêu cầu, chờ duyệt" variant="warning" />}
                </span>
                {a.reason && <span className="mt-1 block text-xs text-(--text-secondary)">Lý do: {a.reason}</span>}
              </td>
              <td className="align-top">
                {a.status === 'SCHEDULED' && a.pendingRequestId == null && a.workDate >= today && (
                  <div className="flex justify-end">
                    <Button size="sm" variant="outline" onClick={() => setRequesting(a)} aria-label={`Xin nghỉ hoặc đổi ca: ${a.shiftName}, ${a.workDate}`}>
                      Xin nghỉ / đổi ca
                    </Button>
                  </div>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  }

  return (
    <section aria-labelledby="my-schedule-title" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="my-schedule-title" className="text-lg font-semibold text-(--text-primary)">
          Lịch phân ca
        </h2>
        <div className="flex flex-wrap items-center gap-3">
          <label className="flex items-center gap-2 text-sm text-(--text-secondary)">
            Từ ngày
            <input type="date" className={dateInput} value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label className="flex items-center gap-2 text-sm text-(--text-secondary)">
            Đến ngày
            <input type="date" className={dateInput} value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
          </label>
        </div>
      </div>
      <Card className="card">
        <CardContent className="overflow-x-auto p-0">{body}</CardContent>
      </Card>
      {requesting && (
        <ShiftRequestDialog
          assignment={requesting}
          options={otherShifts(rows, requesting)}
          onClose={() => setRequesting(null)}
          onDone={(text) => {
            setRequesting(null);
            onNotice({ kind: 'success', text });
          }}
        />
      )}
    </section>
  );
}
