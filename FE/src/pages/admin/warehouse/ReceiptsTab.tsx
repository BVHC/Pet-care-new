import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { EmptyRow, Field, FormError, NoticeBar, selectClass, type Notice } from '../../../components/admin/form-ui';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { Modal, ModalBody, ModalFooter, ModalHeader, ModalTitle, StatusBadge } from '../../../components/ui/modal-templates';
import { cn, formatCurrency, formatDate, formatDateTime } from '../../../lib/utils';
import { inventoryApi } from '../../../shared/api/inventory.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useInventoryMutation, useReceipt, useReceipts, useSuppliers } from '../../../shared/hooks/useInventory';
import { RECEIPT_STATUS_LABELS, type ReceiptStatus, type StockReceipt } from '../../../shared/types/inventory';
import { ReceiptForm } from './ReceiptForm';

const PAGE_SIZE = 20;
const STATUS_VARIANT = { DRAFT: 'neutral', CONFIRMED: 'success', CANCELLED: 'danger' } as const;

/** UC74 — phiếu nhập kho của chi nhánh: nháp → xác nhận (cộng tồn) / hủy (03 §9 Phiếu nhập kho #1–#4). */
export function ReceiptsTab() {
  const [status, setStatus] = useState('');
  const [supplierId, setSupplierId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const suppliers = useSuppliers();
  const receipts = useReceipts({
    status: (status || undefined) as ReceiptStatus | undefined,
    supplierId: supplierId ? Number(supplierId) : undefined,
    from: from || undefined,
    to: to || undefined,
    page,
    size: PAGE_SIZE,
  });
  const [openId, setOpenId] = useState<number | null>(null);
  const [editing, setEditing] = useState<StockReceipt | 'new' | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const reset = (set: (v: string) => void) => (v: string) => {
    set(v);
    setPage(0);
  };
  const data = receipts.data;

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="p-4 flex flex-wrap items-center gap-3">
          <select aria-label="Lọc trạng thái" className={cn(selectClass, 'w-40')} value={status} onChange={(e) => reset(setStatus)(e.target.value)}>
            <option value="">Mọi trạng thái</option>
            {(Object.keys(RECEIPT_STATUS_LABELS) as ReceiptStatus[]).map((s) => (
              <option key={s} value={s}>
                {RECEIPT_STATUS_LABELS[s]}
              </option>
            ))}
          </select>
          <select aria-label="Lọc nhà cung cấp" className={cn(selectClass, 'w-56')} value={supplierId} onChange={(e) => reset(setSupplierId)(e.target.value)}>
            <option value="">Mọi nhà cung cấp</option>
            {suppliers.data?.map((s) => (
              <option key={s.supplierId} value={s.supplierId}>
                {s.name}
              </option>
            ))}
          </select>
          <Input aria-label="Từ ngày" type="date" className="w-40" value={from} onChange={(e) => reset(setFrom)(e.target.value)} />
          <Input aria-label="Đến ngày" type="date" className="w-40" value={to} onChange={(e) => reset(setTo)(e.target.value)} />
          <div className="flex-1" />
          <Button onClick={() => setEditing('new')}>
            <Plus className="h-4 w-4" />
            Tạo phiếu nhập
          </Button>
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Mã phiếu</th>
                <th>Ngày nhập</th>
                <th>Nhà cung cấp</th>
                <th className="text-right">Số dòng</th>
                <th className="text-right">Tổng tiền</th>
                <th>Trạng thái</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {data?.content.length ? (
                data.content.map((r) => (
                  <tr key={r.receiptId}>
                    <td className="font-mono text-xs">{r.code}</td>
                    <td>{formatDate(r.receiptDate)}</td>
                    <td className="font-medium text-(--text-primary)">{r.supplierName}</td>
                    <td className="text-right tabular-nums">{r.lineCount}</td>
                    <td className="text-right tabular-nums">{formatCurrency(r.totalCost)}</td>
                    <td>
                      <StatusBadge status={RECEIPT_STATUS_LABELS[r.status]} variant={STATUS_VARIANT[r.status]} />
                    </td>
                    <td className="text-right">
                      <Button size="sm" variant="outline" onClick={() => setOpenId(r.receiptId)} aria-label={`Mở phiếu ${r.code}`}>
                        Mở
                      </Button>
                    </td>
                  </tr>
                ))
              ) : (
                <EmptyRow colSpan={7} loading={receipts.isLoading} />
              )}
            </tbody>
          </table>
          {data && data.totalPages > 1 && (
            <div className="flex items-center justify-end gap-3 border-t border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
              <span>
                Trang {data.page + 1}/{data.totalPages} · {data.totalElements} phiếu
              </span>
              <Button size="sm" variant="outline" disabled={data.page === 0} onClick={() => setPage(data.page - 1)}>
                Trước
              </Button>
              <Button size="sm" variant="outline" disabled={data.page + 1 >= data.totalPages} onClick={() => setPage(data.page + 1)}>
                Sau
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      {openId != null && !editing && (
        <ReceiptDetail
          receiptId={openId}
          onClose={() => setOpenId(null)}
          onEdit={(r) => setEditing(r)}
          onDone={(text) => setNotice({ kind: 'success', text })}
        />
      )}
      {editing && (
        <ReceiptForm
          receipt={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(saved) => {
            setEditing(null);
            setOpenId(saved.receiptId);
            setNotice({ kind: 'success', text: `Đã lưu phiếu nháp ${saved.code}` });
          }}
        />
      )}
    </div>
  );
}

/** Chi tiết phiếu và các chuyển trạng thái được phép: nháp → sửa / xác nhận / hủy; đã nhập → hủy (trừ lại tồn, BR-KO-04). */
function ReceiptDetail({
  receiptId,
  onClose,
  onEdit,
  onDone,
}: {
  receiptId: number;
  onClose: () => void;
  onEdit: (r: StockReceipt) => void;
  onDone: (text: string) => void;
}) {
  const navigate = useNavigate();
  const receipt = useReceipt(receiptId);
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const [needsAdjustment, setNeedsAdjustment] = useState(false);
  const confirm = useInventoryMutation(() => inventoryApi.confirmReceipt(receiptId));
  const cancel = useInventoryMutation(() => inventoryApi.cancelReceipt(receiptId, { reason: reason.trim() || undefined }));
  const r = receipt.data;
  const busy = confirm.isPending || cancel.isPending;

  const run = async (action: () => Promise<StockReceipt>, text: (saved: StockReceipt) => string) => {
    setError('');
    setNeedsAdjustment(false);
    try {
      const saved = await action();
      setCancelling(false);
      onDone(text(saved));
    } catch (e) {
      const apiError = toApiError(e);
      setError(plainMessage(apiError));
      setNeedsAdjustment(apiError.ruleId === 'BR-KO-04');
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()} className="max-w-3xl">
      <ModalHeader onClose={onClose}>
        <div className="flex items-center gap-3">
          <ModalTitle>Phiếu nhập {r?.code ?? ''}</ModalTitle>
          {r && <StatusBadge status={RECEIPT_STATUS_LABELS[r.status]} variant={STATUS_VARIANT[r.status]} />}
        </div>
      </ModalHeader>
      <ModalBody className="space-y-4">
        {receipt.isError && <FormError message={plainMessage(toApiError(receipt.error))} />}
        {r && (
          <>
            <dl className="grid gap-3 text-sm sm:grid-cols-3">
              <div>
                <dt className="text-(--text-secondary)">Nhà cung cấp</dt>
                <dd className="font-medium">{r.supplierName}</dd>
              </div>
              <div>
                <dt className="text-(--text-secondary)">Ngày nhập</dt>
                <dd className="font-medium">{formatDate(r.receiptDate)}</dd>
              </div>
              <div>
                <dt className="text-(--text-secondary)">Ghi chú</dt>
                <dd className="font-medium">{r.note ?? '—'}</dd>
              </div>
              {r.confirmedAt && (
                <div>
                  <dt className="text-(--text-secondary)">Nhập kho lúc</dt>
                  <dd className="font-medium">{formatDateTime(r.confirmedAt)}</dd>
                </div>
              )}
              {r.cancelledAt && (
                <div className="sm:col-span-2">
                  <dt className="text-(--text-secondary)">Hủy lúc</dt>
                  <dd className="font-medium">
                    {formatDateTime(r.cancelledAt)}
                    {r.cancelReason && ` — ${r.cancelReason}`}
                  </dd>
                </div>
              )}
            </dl>
            <table className="table">
              <thead>
                <tr>
                  <th>Sản phẩm</th>
                  <th className="text-right">Số lượng</th>
                  <th className="text-right">Giá nhập</th>
                  <th>Số lô</th>
                  <th>Hạn dùng</th>
                  <th className="text-right">Thành tiền</th>
                </tr>
              </thead>
              <tbody>
                {r.lines.length ? (
                  r.lines.map((l) => (
                    <tr key={l.lineId}>
                      <td>{l.productName}</td>
                      <td className="text-right tabular-nums">{l.quantity}</td>
                      <td className="text-right tabular-nums">{formatCurrency(l.unitCost)}</td>
                      <td className="font-mono text-xs">{l.lotNumber ?? '—'}</td>
                      <td>{l.expiryDate ? formatDate(l.expiryDate) : '—'}</td>
                      <td className="text-right tabular-nums">{formatCurrency(l.quantity * l.unitCost)}</td>
                    </tr>
                  ))
                ) : (
                  <EmptyRow colSpan={6} loading={false} />
                )}
              </tbody>
            </table>
            {cancelling && (
              <Field label="Lý do hủy (không bắt buộc)">
                {(id) => <Input id={id} value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} autoFocus />}
              </Field>
            )}
            <FormError message={error} />
            {needsAdjustment && (
              <Button variant="outline" onClick={() => navigate('/admin/warehouse/adjustments')}>
                Lập phiếu điều chỉnh
              </Button>
            )}
          </>
        )}
      </ModalBody>
      {r && r.status !== 'CANCELLED' && (
        <ModalFooter>
          {cancelling ? (
            <>
              <Button variant="outline" onClick={() => setCancelling(false)} disabled={busy}>
                Quay lại
              </Button>
              <Button variant="destructive" disabled={busy} onClick={() => run(() => cancel.mutateAsync(undefined), (s) => `Đã hủy phiếu ${s.code}`)}>
                Xác nhận hủy phiếu
              </Button>
            </>
          ) : (
            <>
              <Button variant="outline" className="text-red-600" onClick={() => setCancelling(true)} disabled={busy}>
                Hủy phiếu
              </Button>
              {r.status === 'DRAFT' && (
                <>
                  <Button variant="outline" onClick={() => onEdit(r)} disabled={busy}>
                    Sửa
                  </Button>
                  <Button disabled={busy} onClick={() => run(() => confirm.mutateAsync(undefined), (s) => `Đã nhập kho phiếu ${s.code}`)}>
                    Xác nhận nhập kho
                  </Button>
                </>
              )}
            </>
          )}
        </ModalFooter>
      )}
    </Modal>
  );
}
