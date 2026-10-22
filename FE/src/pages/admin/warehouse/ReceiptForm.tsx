import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Field, FormError, selectClass } from '../../../components/admin/form-ui';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { FormModal } from '../../../components/ui/modal-templates';
import { formatCurrency } from '../../../lib/utils';
import { inventoryApi } from '../../../shared/api/inventory.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useProducts } from '../../../shared/hooks/useCatalog';
import { useInventoryMutation, useSuppliers } from '../../../shared/hooks/useInventory';
import type { Product } from '../../../shared/types/catalog';
import type { StockReceipt, StockReceiptRequest } from '../../../shared/types/inventory';
import { localIsoDate } from '../../../shared/utils/branch-schedule';

interface LineDraft {
  productId: string;
  quantity: string;
  unitCost: string;
  lotNumber: string;
  expiryDate: string;
}

const emptyLine = (): LineDraft => ({ productId: '', quantity: '', unitCost: '', lotNumber: '', expiryDate: '' });
const isWhole = (v: string, min: number) => v.trim() !== '' && Number.isInteger(Number(v)) && Number(v) >= min;

/**
 * UC74 — tạo / sửa phiếu nhập nháp (sửa = gửi lại toàn bộ phiếu, A2). Nháp chưa ảnh hưởng tồn; số lô và hạn dùng
 * của sản phẩm quản lý hạn dùng bắt buộc khi xác nhận (BR-KO-03), nên ở đây chỉ nhắc, không chặn lưu nháp.
 */
export function ReceiptForm({
  receipt,
  onClose,
  onSaved,
}: {
  receipt: StockReceipt | null;
  onClose: () => void;
  onSaved: (saved: StockReceipt) => void;
}) {
  const suppliers = useSuppliers();
  const products = useProducts({ isActive: true, page: 0, size: 100 });
  const [supplierId, setSupplierId] = useState(receipt ? String(receipt.supplierId) : '');
  const [receiptDate, setReceiptDate] = useState(receipt?.receiptDate ?? localIsoDate());
  const [note, setNote] = useState(receipt?.note ?? '');
  const [lines, setLines] = useState<LineDraft[]>(
    receipt?.lines.map((l) => ({
      productId: String(l.productId),
      quantity: String(l.quantity),
      unitCost: String(l.unitCost),
      lotNumber: l.lotNumber ?? '',
      expiryDate: l.expiryDate ?? '',
    })) ?? [emptyLine()],
  );
  const [error, setError] = useState('');
  const save = useInventoryMutation((body: StockReceiptRequest) =>
    receipt ? inventoryApi.updateReceipt(receipt.receiptId, body) : inventoryApi.createReceipt(body),
  );

  // Phiếu nháp cũ có thể có nhà cung cấp đã ngừng hợp tác / sản phẩm đã ngừng bán: vẫn hiện để không mất giá trị đang chọn.
  const supplierOptions = (suppliers.data ?? []).filter((s) => s.isActive || String(s.supplierId) === supplierId);
  const productList: Product[] = products.data?.content ?? [];
  const productOf = (id: string) => productList.find((p) => String(p.productId) === id);
  const set = (i: number, patch: Partial<LineDraft>) => setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  const total = lines.reduce((sum, l) => sum + (isWhole(l.quantity, 1) && isWhole(l.unitCost, 0) ? Number(l.quantity) * Number(l.unitCost) : 0), 0);

  const submit = async () => {
    let problem = '';
    if (!supplierId) problem = 'Vui lòng chọn nhà cung cấp';
    else if (!receiptDate) problem = 'Vui lòng chọn ngày nhập';
    else {
      lines.forEach((l, i) => {
        if (problem) return;
        if (!l.productId) problem = `Dòng ${i + 1}: chọn sản phẩm`;
        else if (!isWhole(l.quantity, 1)) problem = `Dòng ${i + 1}: số lượng phải là số nguyên lớn hơn 0`;
        else if (!isWhole(l.unitCost, 0)) problem = `Dòng ${i + 1}: giá nhập phải là số nguyên ≥ 0`;
      });
    }
    setError(problem);
    if (problem) return;
    const body: StockReceiptRequest = {
      supplierId: Number(supplierId),
      receiptDate,
      note: note.trim() || undefined,
      lines: lines.map((l) => {
        const tracks = productOf(l.productId)?.tracksExpiry ?? true;
        return {
          productId: Number(l.productId),
          quantity: Number(l.quantity),
          unitCost: Number(l.unitCost),
          lotNumber: tracks ? l.lotNumber.trim() || undefined : undefined,
          expiryDate: tracks ? l.expiryDate || undefined : undefined,
        };
      }),
    };
    try {
      onSaved(await save.mutateAsync(body));
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={receipt ? `Sửa phiếu nháp ${receipt.code}` : 'Tạo phiếu nhập'}
      description="Phiếu nháp chưa cộng tồn; xác nhận nhập kho ở bước sau."
      onSubmit={submit}
      submitText="Lưu nháp"
      loading={save.isPending}
      size="xl"
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Nhà cung cấp">
          {(id) => (
            <select id={id} className={selectClass} value={supplierId} onChange={(e) => setSupplierId(e.target.value)}>
              <option value="">Chọn nhà cung cấp…</option>
              {supplierOptions.map((s) => (
                <option key={s.supplierId} value={s.supplierId}>
                  {s.name}
                  {!s.isActive && ' (ngừng hợp tác)'}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Ngày nhập">
          {(id) => <Input id={id} type="date" value={receiptDate} onChange={(e) => setReceiptDate(e.target.value)} />}
        </Field>
      </div>
      <Field label="Ghi chú">
        {(id) => <Input id={id} value={note} maxLength={1000} onChange={(e) => setNote(e.target.value)} />}
      </Field>

      <div className="space-y-2">
        <p className="text-sm font-medium text-(--text-primary)">Các dòng hàng</p>
        {lines.map((l, i) => {
          const product = productOf(l.productId);
          const label = (field: string) => `Dòng ${i + 1} — ${field}`;
          return (
            <div key={i} className="grid gap-2 rounded-lg border border-(--border-color) p-3 sm:grid-cols-[2fr_1fr_1fr_auto]">
              <select aria-label={label('sản phẩm')} className={selectClass} value={l.productId} onChange={(e) => set(i, { productId: e.target.value })}>
                <option value="">Chọn sản phẩm…</option>
                {productList.map((p) => (
                  <option key={p.productId} value={p.productId}>
                    {p.name} ({p.unit})
                  </option>
                ))}
                {l.productId && !product && receipt && (
                  <option value={l.productId}>{receipt.lines.find((x) => String(x.productId) === l.productId)?.productName}</option>
                )}
              </select>
              <Input aria-label={label('số lượng')} type="number" min={1} step={1} placeholder="Số lượng" value={l.quantity} onChange={(e) => set(i, { quantity: e.target.value })} />
              <Input aria-label={label('giá nhập')} type="number" min={0} step={1} placeholder="Giá nhập (đ)" value={l.unitCost} onChange={(e) => set(i, { unitCost: e.target.value })} />
              <Button
                type="button"
                variant="ghost"
                size="icon"
                aria-label={`Xóa dòng ${i + 1}`}
                disabled={lines.length === 1}
                onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))}
              >
                <Trash2 className="h-4 w-4" />
              </Button>
              {product?.tracksExpiry && (
                <div className="grid gap-2 sm:col-span-4 sm:grid-cols-2">
                  <Input aria-label={label('số lô')} placeholder="Số lô" maxLength={50} value={l.lotNumber} onChange={(e) => set(i, { lotNumber: e.target.value })} />
                  <Input aria-label={label('hạn dùng')} type="date" value={l.expiryDate} onChange={(e) => set(i, { expiryDate: e.target.value })} />
                  <p className="text-xs text-(--text-tertiary) sm:col-span-2">Sản phẩm quản lý hạn dùng: cần số lô và hạn dùng sau ngày nhập để xác nhận (BR-KO-03).</p>
                </div>
              )}
            </div>
          );
        })}
        <div className="flex items-center justify-between">
          <Button type="button" variant="outline" size="sm" onClick={() => setLines((ls) => [...ls, emptyLine()])}>
            <Plus className="h-4 w-4" /> Thêm dòng
          </Button>
          <span className="text-sm text-(--text-secondary)">
            Tổng tiền: <strong className="text-(--text-primary)">{formatCurrency(total)}</strong>
          </span>
        </div>
      </div>
      <FormError message={error} />
    </FormModal>
  );
}
