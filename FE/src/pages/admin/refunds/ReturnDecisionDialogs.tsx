import { useState } from 'react';
import { Field, FormError } from '@/components/admin/form-ui';
import { formatVnDateTime } from '@/components/appointments/format';
import { DetailModal, FormModal, InfoRow, StatusBadge } from '@/components/ui/modal-templates';
import { cn, formatCurrency } from '@/lib/utils';
import { returnsApi } from '@/shared/api/returns.api';
import { useReturnRequest, useReturnsMutation } from '@/shared/hooks/useReturns';
import {
  RETURN_REASON_LABELS,
  RETURN_STATUS_LABELS,
  RETURNS_LIMITS,
  type ReturnRequest,
  type ReturnRequestSummary,
} from '@/shared/types/returns';
import { commerceError } from '../orders/commerce-error';
import { RETURN_TONE } from '../orders/tones';

const textareaClass =
  'w-full rounded-lg border border-(--color-border-default) px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent';

const RESTOCK_OPTIONS = [
  { value: true, label: 'Nhập lại kho (lô gốc)' },
  { value: false, label: 'Loại bỏ (hàng hỏng, không bán lại)' },
];

function LinesTable({ lines }: { lines: ReturnRequest['lines'] }) {
  return (
    <table className="table w-full" aria-label="Dòng hàng trả">
      <thead>
        <tr>
          <th>Sản phẩm</th>
          <th className="text-right!">SL</th>
          <th className="text-right!">Đơn giá</th>
        </tr>
      </thead>
      <tbody>
        {lines.map((l) => (
          <tr key={l.orderLineId}>
            <td>{l.productName}</td>
            <td className="text-right tabular-nums">{l.quantity}</td>
            <td className="text-right tabular-nums">{formatCurrency(l.unitPrice)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** #3 chi tiết một yêu cầu: dòng hàng, lý do và quyết định (tóm tắt trong danh sách không có dòng hàng). */
export function ReturnDetailDialog({ request, onClose }: { request: ReturnRequestSummary; onClose: () => void }) {
  const detail = useReturnRequest(request.requestId).data;
  return (
    <DetailModal open onOpenChange={(open) => !open && onClose()} title={`Yêu cầu trả hàng ${request.code}`} size="lg">
      <InfoRow label="Order" value={`#${request.orderId}`} />
      <InfoRow label="Trạng thái" value={<StatusBadge status={RETURN_STATUS_LABELS[request.status]} variant={RETURN_TONE[request.status]} />} />
      <InfoRow label="Lý do" value={RETURN_REASON_LABELS[request.reasonCode]} />
      {!detail ? (
        <p className="py-3 text-sm text-(--text-secondary)">Đang tải chi tiết…</p>
      ) : (
        <>
          {detail.reasonNote && <InfoRow label="Ghi chú" value={detail.reasonNote} />}
          <InfoRow label="Tạo lúc" value={formatVnDateTime(detail.createdAt)} />
          {detail.decidedAt && <InfoRow label="Quyết định lúc" value={formatVnDateTime(detail.decidedAt)} />}
          {detail.restock != null && <InfoRow label="Xử lý hàng" value={detail.restock ? 'Nhập lại lô gốc' : 'Loại bỏ'} />}
          {detail.refundAmount != null && <InfoRow label="Tiền hoàn" value={formatCurrency(detail.refundAmount)} />}
          {detail.decisionNote && <InfoRow label="Lý do từ chối" value={detail.decisionNote} />}
          <div className="mt-3">
            <LinesTable lines={detail.lines} />
          </div>
        </>
      )}
    </DetailModal>
  );
}

interface DecisionProps {
  request: ReturnRequestSummary;
  onClose: () => void;
  onDone: (message: string) => void;
}

/** #5 Duyệt (BRANCH_MANAGER): bắt buộc chọn nhập lại lô gốc hay loại bỏ (BR-BH-14), không chọn sẵn vì chọn nhầm làm hàng hỏng quay lại kho. */
export function ApproveReturnDialog({ request, onClose, onDone }: DecisionProps) {
  const [restock, setRestock] = useState<boolean | null>(null);
  const [error, setError] = useState('');
  const lines = useReturnRequest(request.requestId).data?.lines;
  const approve = useReturnsMutation((r: boolean) => returnsApi.approve(request.requestId, { restock: r }));

  const submit = async () => {
    if (restock === null) return setError('Chọn nhập lại kho hoặc loại bỏ hàng trả.');
    try {
      const done = await approve.mutateAsync(restock);
      onDone(`Đã duyệt ${request.code}, sinh yêu cầu hoàn tiền ${formatCurrency(done.refundAmount ?? 0)}.`);
    } catch (e) {
      setError(commerceError(e, 'Yêu cầu'));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title="Duyệt yêu cầu trả hàng"
      description={`${request.code} · Order #${request.orderId}`}
      onSubmit={submit}
      submitText="Duyệt"
      loading={approve.isPending}
    >
      {lines && <LinesTable lines={lines} />}
      <fieldset>
        <legend className="mb-1.5 text-sm font-medium text-(--text-primary)">Xử lý hàng trả</legend>
        {RESTOCK_OPTIONS.map(({ value, label }) => (
          <label
            key={String(value)}
            className={cn(
              'mb-2 flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2.5 text-sm text-(--text-primary)',
              restock === value ? 'border-(--ws-accent) bg-(--ws-accent-soft)' : 'border-(--color-border-default) hover:bg-(--bg-secondary)',
            )}
          >
            <input
              type="radio"
              name="return-restock"
              checked={restock === value}
              onChange={() => {
                setRestock(value);
                setError('');
              }}
              className="h-4 w-4 accent-[#a43324]"
            />
            {label}
          </label>
        ))}
      </fieldset>
      <FormError message={error} />
    </FormModal>
  );
}

/** #6 Từ chối: lý do bắt buộc (BR-BH-14). */
export function RejectReturnDialog({ request, onClose, onDone }: DecisionProps) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const reject = useReturnsMutation((r: string) => returnsApi.reject(request.requestId, { reason: r }));

  const submit = async () => {
    if (!reason.trim()) return setError('Nhập lý do từ chối.');
    try {
      await reject.mutateAsync(reason.trim());
      onDone(`Đã từ chối yêu cầu trả hàng ${request.code}.`);
    } catch (e) {
      setError(commerceError(e, 'Yêu cầu'));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title="Từ chối yêu cầu trả hàng"
      description={`${request.code} · Order #${request.orderId}`}
      onSubmit={submit}
      submitText="Từ chối"
      cancelText="Quay lại"
      loading={reject.isPending}
    >
      <Field label="Lý do từ chối" hint={`${reason.length}/${RETURNS_LIMITS.reason}`}>
        {(id) => (
          <textarea
            id={id}
            rows={3}
            maxLength={RETURNS_LIMITS.reason}
            className={textareaClass}
            value={reason}
            onChange={(e) => {
              setReason(e.target.value);
              setError('');
            }}
          />
        )}
      </Field>
      <FormError message={error} />
    </FormModal>
  );
}
