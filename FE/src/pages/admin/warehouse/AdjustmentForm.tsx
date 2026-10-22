import { useEffect, useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Field, FormError, selectClass } from '../../../components/admin/form-ui';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { FormModal } from '../../../components/ui/modal-templates';
import { formatDate } from '../../../lib/utils';
import { inventoryApi } from '../../../shared/api/inventory.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useInventoryItems, useInventoryMutation, useStockLots } from '../../../shared/hooks/useInventory';
import {
  ADJUSTMENT_REASON_LABELS,
  type AdjustmentReason,
  type InventoryItem,
  type StockAdjustment,
  type StockAdjustmentRequest,
} from '../../../shared/types/inventory';

interface LineDraft {
  productId: string;
  stockLotId: string;
  /** Số lượng hiện có của lô đã chọn, để chặn giảm quá tồn ngay ở FE (BR-KO-06). */
  lotQuantity: number | null;
  delta: string;
}

const emptyLine = (): LineDraft => ({ productId: '', stockLotId: '', lotQuantity: null, delta: '' });
const REASONS = Object.keys(ADJUSTMENT_REASON_LABELS) as AdjustmentReason[];

/**
 * UC76 — phiếu điều chỉnh tồn, có hiệu lực ngay khi lưu (BR-KO-06): lý do bắt buộc, "Khác" bắt ghi chú,
 * sản phẩm quản lý hạn dùng phải chọn lô, sản phẩm không quản lý hạn dùng dùng lô mặc định (A4); không giảm quá tồn của lô.
 */
export function AdjustmentForm({ onClose, onSaved }: { onClose: () => void; onSaved: (saved: StockAdjustment) => void }) {
  const items = useInventoryItems({ page: 0, size: 100 });
  const [reason, setReason] = useState<AdjustmentReason | ''>('');
  const [note, setNote] = useState('');
  const [lines, setLines] = useState<LineDraft[]>([emptyLine()]);
  const [error, setError] = useState('');
  const save = useInventoryMutation((body: StockAdjustmentRequest) => inventoryApi.createAdjustment(body));
  const set = (i: number, patch: Partial<LineDraft>) => setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)));

  const submit = async () => {
    let problem = '';
    if (!reason) problem = 'Vui lòng chọn lý do điều chỉnh';
    else if (reason === 'OTHER' && !note.trim()) problem = 'Lý do "Khác" bắt buộc ghi chú';
    const remaining = new Map<string, number>();
    lines.forEach((l, i) => {
      if (problem) return;
      const delta = Number(l.delta);
      if (!l.stockLotId) problem = `Dòng ${i + 1}: chọn sản phẩm và lô`;
      else if (l.delta.trim() === '' || !Number.isInteger(delta) || delta === 0) problem = `Dòng ${i + 1}: số điều chỉnh là số nguyên khác 0 (âm = giảm)`;
      else {
        const left = (remaining.get(l.stockLotId) ?? l.lotQuantity ?? 0) + delta;
        remaining.set(l.stockLotId, left);
        if (left < 0) problem = `Dòng ${i + 1}: giảm quá tồn của lô (còn ${l.lotQuantity ?? 0})`;
      }
    });
    setError(problem);
    if (problem || !reason) return;
    try {
      onSaved(
        await save.mutateAsync({
          reason,
          note: note.trim() || undefined,
          lines: lines.map((l) => ({ stockLotId: Number(l.stockLotId), quantityDelta: Number(l.delta) })),
        }),
      );
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title="Lập phiếu điều chỉnh tồn"
      description="Có hiệu lực ngay khi lưu và được ghi nhật ký. Kiểm kê và chuyển kho chưa có ở giai đoạn này."
      onSubmit={submit}
      submitText="Lưu phiếu điều chỉnh"
      loading={save.isPending}
      size="xl"
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Lý do">
          {(id) => (
            <select id={id} className={selectClass} value={reason} onChange={(e) => setReason(e.target.value as AdjustmentReason)}>
              <option value="">Chọn lý do…</option>
              {REASONS.map((r) => (
                <option key={r} value={r}>
                  {ADJUSTMENT_REASON_LABELS[r]}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label={reason === 'OTHER' ? 'Ghi chú (bắt buộc)' : 'Ghi chú'}>
          {(id) => <Input id={id} value={note} maxLength={1000} onChange={(e) => setNote(e.target.value)} />}
        </Field>
      </div>
      <div className="space-y-2">
        <p className="text-sm font-medium text-(--text-primary)">Các dòng điều chỉnh</p>
        {lines.map((l, i) => (
          <AdjustmentLineRow
            key={i}
            index={i}
            line={l}
            items={items.data?.content ?? []}
            onChange={(patch) => set(i, patch)}
            onRemove={lines.length > 1 ? () => setLines((ls) => ls.filter((_, j) => j !== i)) : undefined}
          />
        ))}
        <Button type="button" variant="outline" size="sm" onClick={() => setLines((ls) => [...ls, emptyLine()])}>
          <Plus className="h-4 w-4" /> Thêm dòng
        </Button>
      </div>
      <FormError message={error} />
    </FormModal>
  );
}

function AdjustmentLineRow({
  index,
  line,
  items,
  onChange,
  onRemove,
}: {
  index: number;
  line: LineDraft;
  items: InventoryItem[];
  onChange: (patch: Partial<LineDraft>) => void;
  onRemove?: () => void;
}) {
  const lots = useStockLots(line.productId ? Number(line.productId) : null);
  const label = (field: string) => `Dòng ${index + 1} — ${field}`;

  // Sản phẩm không quản lý hạn dùng chỉ có lô mặc định: chọn sẵn (A4).
  useEffect(() => {
    const only = lots.data?.length === 1 ? lots.data[0] : null;
    if (only?.isDefault && line.stockLotId !== String(only.stockLotId)) {
      onChange({ stockLotId: String(only.stockLotId), lotQuantity: only.quantity });
    }
  }, [lots.data, line.stockLotId, onChange]);

  return (
    <div className="grid gap-2 rounded-lg border border-(--border-color) p-3 sm:grid-cols-[2fr_2fr_1fr_auto]">
      <select
        aria-label={label('sản phẩm')}
        className={selectClass}
        value={line.productId}
        onChange={(e) => onChange({ productId: e.target.value, stockLotId: '', lotQuantity: null })}
      >
        <option value="">Chọn sản phẩm…</option>
        {items.map((it) => (
          <option key={it.productId} value={it.productId}>
            {it.productName}
          </option>
        ))}
      </select>
      <select
        aria-label={label('lô')}
        className={selectClass}
        value={line.stockLotId}
        disabled={!line.productId}
        onChange={(e) => {
          const lot = lots.data?.find((x) => String(x.stockLotId) === e.target.value);
          onChange({ stockLotId: e.target.value, lotQuantity: lot?.quantity ?? null });
        }}
      >
        <option value="">Chọn lô…</option>
        {lots.data?.map((lot) => (
          <option key={lot.stockLotId} value={lot.stockLotId}>
            {lot.isDefault ? 'Lô mặc định' : `${lot.lotNumber} · HSD ${lot.expiryDate ? formatDate(lot.expiryDate) : '—'}`} · còn {lot.quantity}
            {lot.expired && ' · đã hết hạn'}
          </option>
        ))}
      </select>
      <Input
        aria-label={label('số điều chỉnh')}
        type="number"
        step={1}
        placeholder="−2 hoặc 3"
        value={line.delta}
        onChange={(e) => onChange({ delta: e.target.value })}
      />
      <Button type="button" variant="ghost" size="icon" aria-label={`Xóa dòng ${index + 1}`} disabled={!onRemove} onClick={onRemove}>
        <Trash2 className="h-4 w-4" />
      </Button>
    </div>
  );
}
