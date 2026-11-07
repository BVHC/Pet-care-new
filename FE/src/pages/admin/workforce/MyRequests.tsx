import { useState } from 'react';
import { Inbox } from 'lucide-react';
import { formatVnDateTime } from '@/components/appointments/format';
import { selectClass, type Notice } from '@/components/admin/form-ui';
import { Pager } from '@/components/admin/Pager';
import { QueryError, RowsSkeleton, StatePanel } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { StatusBadge } from '@/components/ui/modal-templates';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { workforceApi } from '@/shared/api/workforce.api';
import { useMyShiftRequests, useWorkforceMutation } from '@/shared/hooks/useWorkforce';
import { SHIFT_REQUEST_STATUSES, type ShiftRequest, type ShiftRequestStatus } from '@/shared/types/workforce';
import { REQUEST_STATUS_LABELS, REQUEST_STATUS_TONE, dayLabel, describeRequest } from './labels';

const PAGE_SIZE = 20;

/** Yêu cầu nghỉ / đổi ca của tôi (UC36, workforce-v1 #10–11): mới nhất trước; chỉ yêu cầu chờ duyệt rút được (409 nếu quản lý vừa xử lý). */
export function MyRequests({ onNotice }: { onNotice: (n: Notice) => void }) {
  const [status, setStatus] = useState<ShiftRequestStatus | ''>('');
  const [page, setPage] = useState(0);
  const list = useMyShiftRequests({ status: status || undefined, page, size: PAGE_SIZE });
  const withdraw = useWorkforceMutation(workforceApi.withdrawShiftRequest);
  const data = list.data;
  const stale = list.isPlaceholderData;

  const withdrawIt = async (r: ShiftRequest) => {
    try {
      await withdraw.mutateAsync(r.shiftRequestId);
      onNotice({ kind: 'success', text: `Đã rút yêu cầu: ${describeRequest(r)} ngày ${r.workDate}.` });
    } catch (e) {
      onNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  let body;
  if (!data && list.isError) body = <QueryError error={toApiError(list.error)} onRetry={() => list.refetch()} what="yêu cầu của bạn" />;
  else if (!data) body = <RowsSkeleton rows={3} />;
  else if (data.content.length === 0) body = <StatePanel icon={Inbox} title="Chưa có yêu cầu nào" body="Chọn “Xin nghỉ / đổi ca” ở một ca trong lịch phân ca để gửi yêu cầu." />;
  else {
    body = (
      <>
        <table className={cn('table min-w-[720px]', stale && 'opacity-60')} aria-label="Yêu cầu của tôi" aria-busy={stale}>
          <thead>
            <tr className="[&>th]:whitespace-nowrap">
              <th>Yêu cầu</th>
              <th>Lý do</th>
              <th>Gửi lúc</th>
              <th>Trạng thái</th>
              <th className="text-right!">Thao tác</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((r) => (
              <tr key={r.shiftRequestId}>
                <td className="align-top">
                  <span className="block font-medium text-(--text-primary)">{describeRequest(r)}</span>
                  <span className="text-xs text-(--text-secondary)">{dayLabel(r.workDate)}</span>
                </td>
                <td className="max-w-64 align-top text-sm">{r.reason}</td>
                <td className="whitespace-nowrap align-top text-sm text-(--text-secondary)">{formatVnDateTime(r.createdAt)}</td>
                <td className="align-top">
                  <StatusBadge status={REQUEST_STATUS_LABELS[r.status]} variant={REQUEST_STATUS_TONE[r.status]} />
                  {r.decisionNote && <span className="mt-1 block text-xs text-(--text-secondary)">Ghi chú của quản lý: {r.decisionNote}</span>}
                </td>
                <td className="align-top">
                  {r.status === 'PENDING' && (
                    <div className="flex justify-end">
                      <Button size="sm" variant="outline" disabled={stale || withdraw.isPending} onClick={() => withdrawIt(r)} aria-label={`Rút yêu cầu: ${describeRequest(r)}, ${r.workDate}`}>
                        Rút yêu cầu
                      </Button>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pager data={data} unit="yêu cầu" onPage={setPage} />
      </>
    );
  }

  return (
    <section aria-labelledby="my-requests-title" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="my-requests-title" className="text-lg font-semibold text-(--text-primary)">
          Yêu cầu của tôi
        </h2>
        <select
          aria-label="Lọc trạng thái yêu cầu"
          className={cn(selectClass, 'w-44')}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as ShiftRequestStatus | '');
            setPage(0);
          }}
        >
          <option value="">Mọi trạng thái</option>
          {SHIFT_REQUEST_STATUSES.map((s) => (
            <option key={s} value={s}>
              {REQUEST_STATUS_LABELS[s]}
            </option>
          ))}
        </select>
      </div>
      <Card className="card">
        <CardContent className="overflow-x-auto p-0">{body}</CardContent>
      </Card>
    </section>
  );
}
