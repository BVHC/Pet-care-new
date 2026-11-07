import { useState } from 'react';
import { Inbox, Plus } from 'lucide-react';
import { formatVnDateTime } from '@/components/appointments/format';
import { NoticeBar, type Notice } from '@/components/admin/form-ui';
import { QueryError, RowsSkeleton, StatePanel } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { ConfirmModal, StatusBadge } from '@/components/ui/modal-templates';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn, formatCurrency } from '@/lib/utils';
import { returnsApi } from '@/shared/api/returns.api';
import { useReturnRequests, useReturnsMutation } from '@/shared/hooks/useReturns';
import { useAccount } from '@/shared/stores/session.store';
import {
  RETURN_REASON_LABELS,
  RETURN_REQUEST_STATUSES,
  RETURN_STATUS_LABELS,
  type ReturnRequestStatus,
  type ReturnRequestSummary,
} from '@/shared/types/returns';
import { commerceError } from '../orders/commerce-error';
import { Pager } from '../orders/Pager';
import { RETURN_TONE } from '../orders/tones';
import { CreateReturnDialog } from './CreateReturnDialog';
import { ApproveReturnDialog, RejectReturnDialog, ReturnDetailDialog } from './ReturnDecisionDialogs';

const PAGE_SIZE = 20;

type Acting = { kind: 'create' } | { kind: 'detail' | 'approve' | 'reject' | 'withdraw'; request: ReturnRequestSummary };

/**
 * Yêu cầu trả hàng (UC68, UC69): lễ tân tạo / rút, quản lý chi nhánh duyệt / từ chối. Mỗi vai trò chỉ thấy nút của mình (returns-v1 mục B).
 * Rút yêu cầu chỉ người tạo được (403 nếu lễ tân khác): `ReturnRequest` không có người tạo nên nút hiện cho mọi lễ tân.
 */
export function ReturnRequestsTab() {
  const role = useAccount()?.role;
  const [status, setStatus] = useState<ReturnRequestStatus>('PENDING');
  const [page, setPage] = useState(0);
  const [acting, setActing] = useState<Acting | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const list = useReturnRequests({ status, page, size: PAGE_SIZE });
  const withdraw = useReturnsMutation((id: number) => returnsApi.cancel(id));
  const data = list.data;
  const stale = list.isPlaceholderData;
  const isDesk = role === 'RECEPTIONIST';
  const isManager = role === 'BRANCH_MANAGER';

  const goTo = (p: number) => {
    setPage(p);
    setNotice(null);
  };
  const done = (text: string) => {
    setActing(null);
    setNotice({ kind: 'success', text });
  };
  const doWithdraw = async (r: ReturnRequestSummary) => {
    try {
      await withdraw.mutateAsync(r.requestId);
      done(`Đã rút yêu cầu trả hàng ${r.code}.`);
    } catch (e) {
      setActing(null);
      setNotice({ kind: 'error', text: commerceError(e, 'Yêu cầu') });
    }
  };

  let body;
  if (!data && list.isError) {
    body = <QueryError error={list.error} onRetry={() => list.refetch()} what="yêu cầu trả hàng" />;
  } else if (!data) {
    body = <RowsSkeleton rows={4} />;
  } else if (data.content.length === 0) {
    body = <StatePanel icon={Inbox} title={`Không có yêu cầu nào ở trạng thái "${RETURN_STATUS_LABELS[status]}"`} />;
  } else {
    body = (
      <>
        <table className={cn('table min-w-[820px]', stale && 'opacity-60')} aria-label="Danh sách yêu cầu trả hàng" aria-busy={stale}>
          <thead>
            <tr className="[&>th]:whitespace-nowrap">
              <th>Mã yêu cầu</th>
              <th>Order</th>
              <th>Lý do</th>
              <th className="text-right!">Tiền hoàn</th>
              <th>Tạo lúc</th>
              <th>Trạng thái</th>
              <th className="text-right!">Thao tác</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((r) => (
              <tr key={r.requestId}>
                <td className="whitespace-nowrap font-medium text-(--text-primary)">{r.code}</td>
                <td className="tabular-nums">#{r.orderId}</td>
                <td>{RETURN_REASON_LABELS[r.reasonCode]}</td>
                <td className="text-right tabular-nums">{r.refundAmount != null ? formatCurrency(r.refundAmount) : '—'}</td>
                <td className="whitespace-nowrap tabular-nums text-(--text-secondary)">{formatVnDateTime(r.createdAt)}</td>
                <td className="whitespace-nowrap">
                  <StatusBadge status={RETURN_STATUS_LABELS[r.status]} variant={RETURN_TONE[r.status]} />
                </td>
                <td>
                  <div className="flex justify-end gap-1.5">
                    <Button size="sm" variant="outline" disabled={stale} onClick={() => setActing({ kind: 'detail', request: r })} aria-label={`Xem ${r.code}`}>
                      Chi tiết
                    </Button>
                    {r.status === 'PENDING' && isManager && (
                      <>
                        <Button size="sm" disabled={stale} onClick={() => setActing({ kind: 'approve', request: r })} aria-label={`Duyệt ${r.code}`}>
                          Duyệt
                        </Button>
                        <Button size="sm" variant="outline" className="border-red-200 text-red-600" disabled={stale} onClick={() => setActing({ kind: 'reject', request: r })} aria-label={`Từ chối ${r.code}`}>
                          Từ chối
                        </Button>
                      </>
                    )}
                    {r.status === 'PENDING' && isDesk && (
                      <Button size="sm" variant="outline" disabled={stale} onClick={() => setActing({ kind: 'withdraw', request: r })} aria-label={`Rút ${r.code}`}>
                        Rút
                      </Button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pager data={data} noun="yêu cầu" onPage={goTo} />
      </>
    );
  }

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="flex flex-wrap items-center justify-between gap-3 p-4">
          <Tabs
            value={status}
            onValueChange={(v) => {
              setStatus(v as ReturnRequestStatus);
              goTo(0);
            }}
          >
            <TabsList aria-label="Lọc trạng thái yêu cầu">
              {RETURN_REQUEST_STATUSES.map((s) => (
                <TabsTrigger key={s} value={s}>
                  {RETURN_STATUS_LABELS[s]}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>
          {isDesk && (
            <Button onClick={() => setActing({ kind: 'create' })}>
              <Plus className="h-4 w-4" aria-hidden /> Tạo yêu cầu trả hàng
            </Button>
          )}
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="overflow-x-auto p-0">{body}</CardContent>
      </Card>

      {acting?.kind === 'create' && <CreateReturnDialog onClose={() => setActing(null)} onDone={done} />}
      {acting?.kind === 'detail' && <ReturnDetailDialog request={acting.request} onClose={() => setActing(null)} />}
      {acting?.kind === 'approve' && <ApproveReturnDialog request={acting.request} onClose={() => setActing(null)} onDone={done} />}
      {acting?.kind === 'reject' && <RejectReturnDialog request={acting.request} onClose={() => setActing(null)} onDone={done} />}
      {acting?.kind === 'withdraw' && (
        <ConfirmModal
          open
          onOpenChange={(open) => !open && setActing(null)}
          type="warning"
          title="Rút yêu cầu trả hàng"
          description={`Rút ${acting.request.code} (Order #${acting.request.orderId})? Chỉ người đã tạo yêu cầu mới rút được.`}
          confirmText="Rút yêu cầu"
          cancelText="Quay lại"
          loading={withdraw.isPending}
          onConfirm={() => doWithdraw(acting.request)}
        />
      )}
    </div>
  );
}
