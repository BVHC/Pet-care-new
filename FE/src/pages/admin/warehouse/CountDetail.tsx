import { useState } from 'react';
import { EmptyRow, Field, FormError } from '../../../components/admin/form-ui';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { Modal, ModalBody, ModalFooter, ModalHeader, ModalTitle, StatusBadge } from '../../../components/ui/modal-templates';
import { cn, formatDate, formatDateTime } from '../../../lib/utils';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { stockopsApi } from '../../../shared/api/stockops.api';
import { useStockCount, useStockOpsMutation } from '../../../shared/hooks/useStockOps';
import { STOCK_COUNT_STATUS_LABELS, STOCK_OPS_LIMITS, type CountedQtyInput, type StockCount } from '../../../shared/types/stockops';
import { COUNT_STATUS_VARIANT } from './transfer-ui';

/**
 * Phiên kiểm kê: nhập số đếm theo lô (chỉ khi OPEN). Lô để trống không bị đụng tới khi xác nhận. Số nhập chưa lưu được gửi
 * kèm trước khi xác nhận, nếu không BE sẽ bỏ chúng. Xác nhận tạo phiếu điều chỉnh "Chênh lệch kiểm kê" cho lô lệch (BR-KO-12).
 */
export function CountDetail({ countId, onClose, onDone }: { countId: number; onClose: () => void; onDone: (text: string) => void }) {
  const count = useStockCount(countId);
  const [edits, setEdits] = useState<Record<number, string>>({});
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const saveLines = useStockOpsMutation((lines: CountedQtyInput[]) => stockopsApi.saveCountLines(countId, { lines }));
  const confirm = useStockOpsMutation(() => stockopsApi.confirmCount(countId));
  const cancel = useStockOpsMutation((text: string) => stockopsApi.cancelCount(countId, { reason: text }));
  const c = count.data;
  const open = c?.status === 'OPEN';
  const busy = saveLines.isPending || confirm.isPending || cancel.isPending;
  const savedText = (qty?: number | null) => (qty == null ? '' : String(qty));
  // Ô để trống không có nghĩa "bỏ số đã lưu" (BE chỉ ghi đè bằng số ≥ 0), nên chỉ tính ô có chữ và khác số đã lưu.
  const dirty = (c?.lines ?? []).filter((l) => (edits[l.stockLotId] ?? '').trim() !== '' && edits[l.stockLotId] !== savedText(l.countedQty));
  const counted = (c?.lines ?? []).some((l) => l.countedQty != null) || dirty.length > 0;

  const pending = (): CountedQtyInput[] | null => {
    // Xóa trắng số đã lưu không gỡ được số đó ở BE (chỉ ghi đè bằng số ≥ 0): chặn, kẻo xác nhận vẫn điều chỉnh lô theo số cũ.
    const cleared = (c?.lines ?? []).find((l) => l.countedQty != null && edits[l.stockLotId] !== undefined && edits[l.stockLotId].trim() === '');
    if (cleared) {
      setError(`${cleared.productName}${cleared.lotNumber ? ` (${cleared.lotNumber})` : ''} đã có số đếm ${cleared.countedQty}, không để trống được: nhập lại số đếm`);
      return null;
    }
    const bad = dirty.find((l) => !/^\d+$/.test(edits[l.stockLotId].trim()));
    if (bad) {
      setError(`${bad.productName}${bad.lotNumber ? ` (${bad.lotNumber})` : ''}: số đếm là số nguyên từ 0 trở lên`);
      return null;
    }
    return dirty.map((l) => ({ stockLotId: l.stockLotId, countedQty: Number(edits[l.stockLotId].trim()) }));
  };

  const run = async (steps: () => Promise<StockCount | void>, text: string, close: boolean) => {
    setError('');
    try {
      await steps();
      setEdits({});
      onDone(text);
      if (close) onClose();
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  const save = () => {
    const lines = pending();
    if (lines) void run(() => saveLines.mutateAsync(lines), 'Đã lưu số đếm', false);
  };
  const confirmCount = () => {
    const lines = pending();
    if (!lines) return;
    void run(
      async () => {
        if (lines.length) await saveLines.mutateAsync(lines);
        return confirm.mutateAsync(undefined);
      },
      `Đã xác nhận kiểm kê ${c?.code ?? ''}`,
      true,
    );
  };
  const cancelCount = () => {
    if (!reason.trim()) return setError('Vui lòng nhập lý do hủy phiên kiểm kê');
    void run(() => cancel.mutateAsync(reason.trim()), `Đã hủy phiên ${c?.code ?? ''}`, true);
  };

  return (
    <Modal open onOpenChange={(o) => !o && onClose()} className="max-w-4xl">
      <ModalHeader onClose={onClose}>
        <div className="flex items-center gap-3">
          <ModalTitle>Phiên kiểm kê {c?.code ?? ''}</ModalTitle>
          {c && <StatusBadge status={STOCK_COUNT_STATUS_LABELS[c.status]} variant={COUNT_STATUS_VARIANT[c.status]} />}
        </div>
      </ModalHeader>
      <ModalBody className="space-y-4">
        {count.isError && <FormError message={plainMessage(toApiError(count.error))} />}
        {c && (
          <>
            <p className="text-sm text-(--text-secondary)">
              Mở lúc {formatDateTime(c.createdAt)}
              {c.confirmedAt && ` · xác nhận lúc ${formatDateTime(c.confirmedAt)}`}
              {c.cancelReason && ` · lý do hủy: ${c.cancelReason}`}
            </p>
            <table className="table" aria-label="Các lô trong phiên">
              <thead>
                <tr>
                  <th>Sản phẩm</th>
                  <th>Số lô</th>
                  <th>Hạn dùng</th>
                  <th className="text-right">Tồn hệ thống</th>
                  <th className="text-right">Số đếm</th>
                  <th className="text-right">Chênh lệch</th>
                </tr>
              </thead>
              <tbody>
                {c.lines.length ? (
                  c.lines.map((l) => {
                    const typed = edits[l.stockLotId];
                    const shown = typed ?? savedText(l.countedQty);
                    const diff = shown.trim() !== '' && /^\d+$/.test(shown.trim()) ? Number(shown) - l.systemQty : null;
                    const label = `Số đếm ${l.productName} ${l.lotNumber ?? 'lô mặc định'}`;
                    return (
                      <tr key={l.stockLotId}>
                        <td>{l.productName}</td>
                        <td className="font-mono text-xs">{l.lotNumber ?? 'Lô mặc định'}</td>
                        <td>{l.expiryDate ? formatDate(l.expiryDate) : '—'}</td>
                        <td className="text-right tabular-nums">{l.systemQty}</td>
                        <td className="text-right">
                          {open ? (
                            <Input
                              aria-label={label}
                              type="number"
                              min={0}
                              step={1}
                              className="ml-auto w-24 text-right"
                              value={shown}
                              onChange={(e) => setEdits((prev) => ({ ...prev, [l.stockLotId]: e.target.value }))}
                            />
                          ) : (
                            <span className="tabular-nums">{l.countedQty ?? '—'}</span>
                          )}
                        </td>
                        <td className={cn('text-right tabular-nums', diff != null && diff !== 0 && 'font-semibold text-red-600')}>
                          {diff == null ? '—' : diff > 0 ? `+${diff}` : diff}
                        </td>
                      </tr>
                    );
                  })
                ) : (
                  <EmptyRow colSpan={6} loading={false} />
                )}
              </tbody>
            </table>
            {open && <p className="text-xs text-(--text-tertiary)">Lô để trống không bị điều chỉnh khi xác nhận. Xác nhận sẽ tạo phiếu điều chỉnh cho các lô lệch và không hoàn tác được.</p>}
            {cancelling && (
              <Field label="Lý do hủy phiên (bắt buộc)">
                {(id) => <Input id={id} value={reason} maxLength={STOCK_OPS_LIMITS.reason} onChange={(e) => setReason(e.target.value)} autoFocus />}
              </Field>
            )}
          </>
        )}
        <FormError message={error} />
      </ModalBody>
      {open && (
        <ModalFooter>
          {cancelling ? (
            <>
              <Button variant="outline" onClick={() => setCancelling(false)} disabled={busy}>
                Quay lại
              </Button>
              <Button variant="destructive" disabled={busy} onClick={cancelCount}>
                Xác nhận hủy phiên
              </Button>
            </>
          ) : (
            <>
              <Button variant="outline" className="text-red-600" onClick={() => setCancelling(true)} disabled={busy}>
                Hủy phiên
              </Button>
              <Button variant="outline" onClick={save} disabled={busy || dirty.length === 0}>
                Lưu số đếm
              </Button>
              <Button onClick={confirmCount} disabled={busy || !counted}>
                Xác nhận kiểm kê
              </Button>
            </>
          )}
        </ModalFooter>
      )}
    </Modal>
  );
}
