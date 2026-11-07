import { useState } from 'react';
import { EmptyRow, Field, FormError } from '../../../components/admin/form-ui';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { Modal, ModalBody, ModalFooter, ModalHeader, ModalTitle, StatusBadge } from '../../../components/ui/modal-templates';
import { formatDate, formatDateTime } from '../../../lib/utils';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { stockopsApi } from '../../../shared/api/stockops.api';
import { useStockOpsMutation, useStockTransfer } from '../../../shared/hooks/useStockOps';
import { STOCK_OPS_LIMITS, STOCK_TRANSFER_STATUS_LABELS, type StockTransfer } from '../../../shared/types/stockops';
import { TRANSFER_STATUS_VARIANT } from './transfer-ui';

/**
 * Chi tiết phiếu chuyển kho. Nhận hàng chỉ ở chi nhánh đích; hủy ở cả hai đầu và bắt buộc lý do (BR-KO-10, BR-KO-11).
 * Nút chỉ có khi phiếu còn IN_TRANSIT; BE vẫn kiểm lại (409 nếu phiếu vừa đổi trạng thái).
 */
export function TransferDetail({
  transferId,
  myBranchId,
  onClose,
  onDone,
}: {
  transferId: number;
  myBranchId: number | null;
  onClose: () => void;
  onDone: (text: string) => void;
}) {
  const transfer = useStockTransfer(transferId);
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const receive = useStockOpsMutation(() => stockopsApi.receiveTransfer(transferId));
  const cancel = useStockOpsMutation((text: string) => stockopsApi.cancelTransfer(transferId, { reason: text }));
  const t = transfer.data;
  const busy = receive.isPending || cancel.isPending;

  const run = async (action: () => Promise<StockTransfer>, text: (saved: StockTransfer) => string) => {
    setError('');
    try {
      const saved = await action();
      onDone(text(saved));
      onClose();
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  const submitCancel = () => {
    if (!reason.trim()) return setError('Vui lòng nhập lý do hủy phiếu chuyển kho');
    return run(() => cancel.mutateAsync(reason.trim()), (s) => `Đã hủy phiếu ${s.code}`);
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()} className="max-w-3xl">
      <ModalHeader onClose={onClose}>
        <div className="flex items-center gap-3">
          <ModalTitle>Phiếu chuyển kho {t?.code ?? ''}</ModalTitle>
          {t && <StatusBadge status={STOCK_TRANSFER_STATUS_LABELS[t.status]} variant={TRANSFER_STATUS_VARIANT[t.status]} />}
        </div>
      </ModalHeader>
      <ModalBody className="space-y-4">
        {transfer.isError && <FormError message={plainMessage(toApiError(transfer.error))} />}
        {t && (
          <>
            <dl className="grid gap-3 text-sm sm:grid-cols-3">
              <div>
                <dt className="text-(--text-secondary)">Chi nhánh nguồn</dt>
                <dd className="font-medium">{t.sourceBranchName}</dd>
              </div>
              <div>
                <dt className="text-(--text-secondary)">Chi nhánh đích</dt>
                <dd className="font-medium">{t.destBranchName}</dd>
              </div>
              <div>
                <dt className="text-(--text-secondary)">Tạo lúc</dt>
                <dd className="font-medium">{formatDateTime(t.createdAt)}</dd>
              </div>
              {t.receivedAt && (
                <div>
                  <dt className="text-(--text-secondary)">Nhận hàng lúc</dt>
                  <dd className="font-medium">{formatDateTime(t.receivedAt)}</dd>
                </div>
              )}
              {t.cancelledAt && (
                <div className="sm:col-span-2">
                  <dt className="text-(--text-secondary)">Hủy lúc</dt>
                  <dd className="font-medium">
                    {formatDateTime(t.cancelledAt)}
                    {t.cancelReason && ` — ${t.cancelReason}`}
                  </dd>
                </div>
              )}
            </dl>
            <table className="table" aria-label="Các dòng của phiếu">
              <thead>
                <tr>
                  <th>Sản phẩm</th>
                  <th>Số lô</th>
                  <th>Hạn dùng</th>
                  <th className="text-right">Số lượng</th>
                </tr>
              </thead>
              <tbody>
                {t.lines.length ? (
                  t.lines.map((l, i) => (
                    <tr key={i}>
                      <td>{l.productName}</td>
                      <td className="font-mono text-xs">{l.lotNumber ?? 'Lô mặc định'}</td>
                      <td>{l.expiryDate ? formatDate(l.expiryDate) : '—'}</td>
                      <td className="text-right tabular-nums">{l.quantity}</td>
                    </tr>
                  ))
                ) : (
                  <EmptyRow colSpan={4} loading={false} />
                )}
              </tbody>
            </table>
            {cancelling && (
              <Field label="Lý do hủy (bắt buộc)">
                {(id) => <Input id={id} value={reason} maxLength={STOCK_OPS_LIMITS.reason} onChange={(e) => setReason(e.target.value)} autoFocus />}
              </Field>
            )}
          </>
        )}
        <FormError message={error} />
      </ModalBody>
      {t?.status === 'IN_TRANSIT' && (
        <ModalFooter>
          {cancelling ? (
            <>
              <Button variant="outline" onClick={() => setCancelling(false)} disabled={busy}>
                Quay lại
              </Button>
              <Button variant="destructive" disabled={busy} onClick={submitCancel}>
                Xác nhận hủy phiếu
              </Button>
            </>
          ) : (
            <>
              <Button variant="outline" className="text-red-600" onClick={() => setCancelling(true)} disabled={busy}>
                Hủy phiếu
              </Button>
              {t.destBranchId === myBranchId && (
                <Button disabled={busy} onClick={() => run(() => receive.mutateAsync(undefined), (s) => `Đã nhận hàng phiếu ${s.code}`)}>
                  Nhận hàng
                </Button>
              )}
            </>
          )}
        </ModalFooter>
      )}
    </Modal>
  );
}
