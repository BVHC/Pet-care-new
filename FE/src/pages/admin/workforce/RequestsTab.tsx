import { useState } from 'react';
import { Inbox } from 'lucide-react';
import { formatVnDateTime } from '@/components/appointments/format';
import { NoticeBar, type Notice } from '@/components/admin/form-ui';
import { Pager } from '@/components/admin/Pager';
import { QueryError, RowsSkeleton, StatePanel } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { StatusBadge } from '@/components/ui/modal-templates';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { workforceApi } from '@/shared/api/workforce.api';
import { useShiftRequests, useWorkforceMutation } from '@/shared/hooks/useWorkforce';
import { SHIFT_REQUEST_STATUSES, type RejectShiftRequestRequest, type ShiftRequest, type ShiftRequestStatus } from '@/shared/types/workforce';
import { ReasonDialog } from './FormDialog';
import { REQUEST_STATUS_LABELS, REQUEST_STATUS_TONE, REQUEST_TYPE_LABELS, dayLabel, describeRequest } from './labels';

const PAGE_SIZE = 20;

/**
 * Yêu cầu nghỉ / đổi ca của nhân viên (UC37, workforce-v1 #12–14): mặc định Chờ duyệt (cũ nhất trước). Chỉ yêu cầu chờ duyệt có nút.
 * Duyệt lệch trạng thái (người khác xử lý trước, ca đã bắt đầu, ca đích chồng giờ) là 409 / 400: lỗi hiện ngay, danh sách tự tải lại.
 */
export function RequestsTab() {
  const [status, setStatus] = useState<ShiftRequestStatus>('PENDING');
  const [page, setPage] = useState(0);
  const [rejecting, setRejecting] = useState<ShiftRequest | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const list = useShiftRequests({ status, page, size: PAGE_SIZE });
  const approve = useWorkforceMutation(workforceApi.approveShiftRequest);
  const reject = useWorkforceMutation(({ id, body }: { id: number; body: RejectShiftRequestRequest }) => workforceApi.rejectShiftRequest(id, body));
  const data = list.data;
  const stale = list.isPlaceholderData;

  const approveIt = async (r: ShiftRequest) => {
    try {
      await approve.mutateAsync(r.shiftRequestId);
      setNotice({ kind: 'success', text: `Đã duyệt: ${r.requesterName} · ${describeRequest(r)} ngày ${r.workDate}.` });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  let body;
  if (!data && list.isError) body = <QueryError error={toApiError(list.error)} onRetry={() => list.refetch()} what="yêu cầu nghỉ / đổi ca" />;
  else if (!data) body = <RowsSkeleton rows={4} />;
  else if (data.content.length === 0) {
    body = <StatePanel icon={Inbox} title={`Không có yêu cầu ${REQUEST_STATUS_LABELS[status].toLowerCase()}`} body="Yêu cầu mới của nhân viên sẽ xuất hiện ở tab Chờ duyệt." />;
  } else {
    body = (
      <>
        <table className={cn('table min-w-[900px]', stale && 'opacity-60')} aria-label="Yêu cầu nghỉ hoặc đổi ca" aria-busy={stale}>
          <thead>
            <tr className="[&>th]:whitespace-nowrap">
              <th>Nhân viên</th>
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
                <td className="align-top font-medium text-(--text-primary)">{r.requesterName}</td>
                <td className="align-top">
                  <StatusBadge status={REQUEST_TYPE_LABELS[r.type]} variant="neutral" />
                  <span className="mt-1 block text-sm">{describeRequest(r)}</span>
                  <span className="block text-xs text-(--text-secondary)">{dayLabel(r.workDate)}</span>
                </td>
                <td className="max-w-64 align-top text-sm">{r.reason}</td>
                <td className="whitespace-nowrap align-top text-sm text-(--text-secondary)">{formatVnDateTime(r.createdAt)}</td>
                <td className="align-top">
                  <StatusBadge status={REQUEST_STATUS_LABELS[r.status]} variant={REQUEST_STATUS_TONE[r.status]} />
                  {r.decisionNote && <span className="mt-1 block text-xs text-(--text-secondary)">Ghi chú: {r.decisionNote}</span>}
                </td>
                <td className="align-top">
                  {r.status === 'PENDING' && (
                    <div className="flex justify-end gap-1.5">
                      <Button size="sm" disabled={stale || approve.isPending} onClick={() => approveIt(r)} aria-label={`Duyệt: ${r.requesterName}, ${r.workDate}`}>
                        Duyệt
                      </Button>
                      <Button size="sm" variant="outline" className="border-red-200 text-red-600" disabled={stale} onClick={() => setRejecting(r)} aria-label={`Từ chối: ${r.requesterName}, ${r.workDate}`}>
                        Từ chối
                      </Button>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pager data={data} unit="yêu cầu" onPage={(p) => { setPage(p); setNotice(null); }} />
      </>
    );
  }

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="p-4">
          <Tabs
            value={status}
            onValueChange={(v) => {
              setStatus(v as ShiftRequestStatus);
              setPage(0);
              setNotice(null);
            }}
          >
            <TabsList aria-label="Lọc trạng thái yêu cầu">
              {SHIFT_REQUEST_STATUSES.map((s) => (
                <TabsTrigger key={s} value={s}>
                  {REQUEST_STATUS_LABELS[s]}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>
        </CardContent>
      </Card>
      <NoticeBar notice={notice} />
      <Card className="card">
        <CardContent className="overflow-x-auto p-0">{body}</CardContent>
      </Card>
      {rejecting && (
        <ReasonDialog
          title="Từ chối yêu cầu"
          description={`${rejecting.requesterName} · ${describeRequest(rejecting)} · ${rejecting.workDate}`}
          label="Lý do từ chối"
          submitText="Từ chối"
          onConfirm={(decisionNote) => reject.mutateAsync({ id: rejecting.shiftRequestId, body: { decisionNote } })}
          onClose={() => setRejecting(null)}
          onDone={() => {
            setNotice({ kind: 'success', text: `Đã từ chối yêu cầu của ${rejecting.requesterName}.` });
            setRejecting(null);
          }}
        />
      )}
    </div>
  );
}
