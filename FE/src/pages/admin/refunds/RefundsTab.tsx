import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Inbox } from 'lucide-react';
import { formatVnDateTime } from '@/components/appointments/format';
import { Field, FormError, NoticeBar, selectClass, type Notice } from '@/components/admin/form-ui';
import { QueryError, RowsSkeleton, StatePanel } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { FormModal, StatusBadge } from '@/components/ui/modal-templates';
import { cn, formatCurrency } from '@/lib/utils';
import { toApiError } from '@/shared/api/api-error';
import { returnsApi } from '@/shared/api/returns.api';
import { WORKSPACE_PATHS } from '@/shared/constants/workspaces';
import { useRefunds, useReturnsMutation } from '@/shared/hooks/useReturns';
import { useAccount } from '@/shared/stores/session.store';
import {
  REFUND_METHODS,
  REFUND_METHOD_LABELS,
  REFUND_STATUSES,
  REFUND_STATUS_LABELS,
  RETURNS_LIMITS,
  type Refund,
  type RefundMethod,
  type RefundStatus,
} from '@/shared/types/returns';
import { commerceError } from '../orders/commerce-error';
import { Pager } from '../orders/Pager';
import { REFUND_TONE } from '../orders/tones';

const PAGE_SIZE = 20;

/** Xác nhận đã hoàn tiền tại quầy (#8): cần ca thu ngân đang mở của chính lễ tân (BR-TG-01), nên lỗi này kèm đường tới bàn Lễ tân để mở ca. */
function ConfirmRefundDialog({ refund, onClose, onDone }: { refund: Refund; onClose: () => void; onDone: (message: string) => void }) {
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const [needShift, setNeedShift] = useState(false);
  const confirm = useReturnsMutation((n: string) => returnsApi.confirmRefund(refund.refundId, n ? { note: n } : {}));

  const submit = async () => {
    try {
      await confirm.mutateAsync(note.trim());
      onDone(`Đã xác nhận hoàn tiền ${refund.code} (${formatCurrency(refund.amount)}) tại quầy.`);
    } catch (e) {
      setNeedShift(toApiError(e).ruleId === 'BR-TG-01');
      setError(commerceError(e, 'Khoản hoàn tiền'));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title="Xác nhận đã hoàn tiền"
      description={`${refund.code} · ${REFUND_METHOD_LABELS[refund.method]} · ${formatCurrency(refund.amount)}`}
      onSubmit={submit}
      submitText="Đã hoàn tiền"
      cancelText="Quay lại"
      loading={confirm.isPending}
    >
      <p className="text-sm text-(--text-secondary)">Chỉ bấm khi đã đưa đủ tiền cho khách. Khoản này được ghi vào ca thu ngân đang mở của bạn.</p>
      <Field label="Ghi chú (không bắt buộc)" hint={`${note.length}/${RETURNS_LIMITS.note}`}>
        {(id) => (
          <textarea
            id={id}
            rows={2}
            maxLength={RETURNS_LIMITS.note}
            className="w-full rounded-lg border border-(--color-border-default) px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent"
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        )}
      </Field>
      <FormError message={error} />
      {needShift && (
        <p className="text-sm text-(--text-secondary)">
          Mở ca thu ngân ở{' '}
          <Link to={WORKSPACE_PATHS.reception} className="font-semibold text-(--ws-focus) underline">
            bàn Lễ tân
          </Link>{' '}
          (trong giờ mở cửa của chi nhánh) rồi xác nhận lại.
        </p>
      )}
    </FormModal>
  );
}

/**
 * Hoàn tiền (UC69): lễ tân xác nhận khoản tiền mặt / chuyển khoản đã đưa khách; quản lý chi nhánh thử lại khoản qua cổng bị thất bại.
 * Khoản qua cổng do hệ thống xử lý (ST21) nên không có nút xác nhận tay.
 */
export function RefundsTab() {
  const role = useAccount()?.role;
  const [status, setStatus] = useState<RefundStatus | ''>('');
  const [method, setMethod] = useState<RefundMethod | ''>('');
  const [page, setPage] = useState(0);
  const [confirming, setConfirming] = useState<Refund | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const list = useRefunds({ status: status || undefined, method: method || undefined, page, size: PAGE_SIZE });
  const retry = useReturnsMutation((id: number) => returnsApi.retryRefund(id));
  const data = list.data;
  const stale = list.isPlaceholderData;

  const goTo = (p: number) => {
    setPage(p);
    setNotice(null);
  };
  const reset = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    goTo(0);
  };
  const doRetry = async (r: Refund) => {
    try {
      await retry.mutateAsync(r.refundId);
      setNotice({ kind: 'success', text: `Đã yêu cầu hoàn tiền lại qua cổng cho ${r.code}.` });
    } catch (e) {
      setNotice({ kind: 'error', text: commerceError(e, 'Khoản hoàn tiền') });
    }
  };

  const actionOf = (r: Refund) => {
    if (r.status === 'REQUESTED' && r.method !== 'GATEWAY' && role === 'RECEPTIONIST') {
      return (
        <Button size="sm" disabled={stale} onClick={() => setConfirming(r)} aria-label={`Xác nhận hoàn ${r.code}`}>
          Xác nhận đã hoàn
        </Button>
      );
    }
    if (r.status === 'FAILED' && r.method === 'GATEWAY' && role === 'BRANCH_MANAGER') {
      return (
        <Button size="sm" disabled={stale || retry.isPending} onClick={() => doRetry(r)} aria-label={`Thử lại ${r.code}`}>
          Thử lại qua cổng
        </Button>
      );
    }
    return null;
  };

  let body;
  if (!data && list.isError) {
    body = <QueryError error={list.error} onRetry={() => list.refetch()} what="hoàn tiền" />;
  } else if (!data) {
    body = <RowsSkeleton rows={4} />;
  } else if (data.content.length === 0) {
    body = <StatePanel icon={Inbox} title={status || method ? 'Không có khoản hoàn tiền khớp bộ lọc' : 'Không còn khoản hoàn tiền nào cần xử lý'} />;
  } else {
    body = (
      <>
        <table className={cn('table min-w-[880px]', stale && 'opacity-60')} aria-label="Danh sách hoàn tiền" aria-busy={stale}>
          <thead>
            <tr className="[&>th]:whitespace-nowrap">
              <th>Mã</th>
              <th>Nguồn</th>
              <th>Hình thức</th>
              <th className="text-right!">Số tiền</th>
              <th>Tạo lúc</th>
              <th>Trạng thái</th>
              <th className="text-right!">Thao tác</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((r) => (
              <tr key={r.refundId}>
                <td className="whitespace-nowrap font-medium text-(--text-primary)">{r.code}</td>
                <td className="whitespace-nowrap">{r.returnRequestId != null ? `Trả hàng #${r.returnRequestId}` : `Hủy đơn online #${r.onlineOrderId}`}</td>
                <td>{REFUND_METHOD_LABELS[r.method]}</td>
                <td className="text-right tabular-nums">{formatCurrency(r.amount)}</td>
                <td className="whitespace-nowrap tabular-nums text-(--text-secondary)">{formatVnDateTime(r.createdAt)}</td>
                <td>
                  <StatusBadge status={REFUND_STATUS_LABELS[r.status]} variant={REFUND_TONE[r.status]} />
                  {r.status === 'FAILED' && (
                    <span className="mt-1 block max-w-64 whitespace-normal text-xs text-(--text-secondary)">
                      {r.failureReason} ({r.attempts} lần thử)
                    </span>
                  )}
                </td>
                <td>
                  <div className="flex justify-end">{actionOf(r)}</div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pager data={data} noun="khoản" onPage={goTo} />
      </>
    );
  }

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <select aria-label="Lọc trạng thái hoàn tiền" className={cn(selectClass, 'w-56')} value={status} onChange={(e) => reset(setStatus)(e.target.value as RefundStatus | '')}>
            <option value="">Cần xử lý</option>
            {REFUND_STATUSES.map((s) => (
              <option key={s} value={s}>
                {REFUND_STATUS_LABELS[s]}
              </option>
            ))}
          </select>
          <select aria-label="Lọc hình thức hoàn" className={cn(selectClass, 'w-56')} value={method} onChange={(e) => reset(setMethod)(e.target.value as RefundMethod | '')}>
            <option value="">Mọi hình thức</option>
            {REFUND_METHODS.map((m) => (
              <option key={m} value={m}>
                {REFUND_METHOD_LABELS[m]}
              </option>
            ))}
          </select>
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="overflow-x-auto p-0">{body}</CardContent>
      </Card>

      {confirming && (
        <ConfirmRefundDialog
          refund={confirming}
          onClose={() => setConfirming(null)}
          onDone={(text) => {
            setConfirming(null);
            setNotice({ kind: 'success', text });
          }}
        />
      )}
    </div>
  );
}
