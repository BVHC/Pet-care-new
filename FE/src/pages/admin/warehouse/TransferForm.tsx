import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Field, FormError, selectClass } from '../../../components/admin/form-ui';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { FormModal } from '../../../components/ui/modal-templates';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { stockopsApi } from '../../../shared/api/stockops.api';
import { useInventoryItems } from '../../../shared/hooks/useInventory';
import { usePublicBranches } from '../../../shared/hooks/usePublicSite';
import { useStockOpsMutation } from '../../../shared/hooks/useStockOps';
import type { CreateStockTransferRequest, StockTransfer } from '../../../shared/types/stockops';

interface LineDraft {
  productId: string;
  quantity: string;
}

const emptyLine = (): LineDraft => ({ productId: '', quantity: '' });

/**
 * UC77 — tạo phiếu chuyển kho: trừ tồn nguồn ngay (FEFO), hàng đang trên đường cho tới khi chi nhánh đích nhận (BR-KO-08).
 * Chỉ chi nhánh ĐANG HOẠT ĐỘNG khác chi nhánh mình mới là đích; mỗi sản phẩm một dòng, số lượng không vượt tồn khả dụng.
 */
export function TransferForm({ myBranchId, onClose, onSaved }: { myBranchId: number | null; onClose: () => void; onSaved: (saved: StockTransfer) => void }) {
  const branches = usePublicBranches();
  const items = useInventoryItems({ page: 0, size: 100 });
  const [destBranchId, setDestBranchId] = useState('');
  const [lines, setLines] = useState<LineDraft[]>([emptyLine()]);
  const [error, setError] = useState('');
  const save = useStockOpsMutation((body: CreateStockTransferRequest) => stockopsApi.createTransfer(body));
  const dests = (branches.data ?? []).filter((b) => b.branchId !== myBranchId);
  const stocked = (items.data?.content ?? []).filter((it) => it.availableQuantity > 0);
  const set = (i: number, patch: Partial<LineDraft>) => setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)));

  const submit = async () => {
    let problem = '';
    if (!destBranchId) problem = 'Vui lòng chọn chi nhánh đích';
    const seen = new Set<string>();
    lines.forEach((l, i) => {
      if (problem) return;
      const quantity = Number(l.quantity);
      const available = stocked.find((it) => String(it.productId) === l.productId)?.availableQuantity ?? 0;
      if (!l.productId) problem = `Dòng ${i + 1}: chọn sản phẩm`;
      else if (seen.has(l.productId)) problem = `Dòng ${i + 1}: sản phẩm đã chọn ở dòng khác`;
      else if (l.quantity.trim() === '' || !Number.isInteger(quantity) || quantity < 1) problem = `Dòng ${i + 1}: số lượng là số nguyên từ 1 trở lên`;
      else if (quantity > available) problem = `Dòng ${i + 1}: vượt tồn khả dụng (còn ${available})`;
      seen.add(l.productId);
    });
    setError(problem);
    if (problem) return;
    try {
      onSaved(await save.mutateAsync({ destBranchId: Number(destBranchId), lines: lines.map((l) => ({ productId: Number(l.productId), quantity: Number(l.quantity) })) }));
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title="Tạo phiếu chuyển kho"
      description="Tồn của chi nhánh mình bị trừ ngay; chi nhánh đích cộng tồn khi xác nhận nhận hàng."
      onSubmit={submit}
      submitText="Tạo phiếu"
      loading={save.isPending}
      submitDisabled={dests.length === 0}
      size="xl"
    >
      <Field label="Chi nhánh đích">
        {(id) => (
          <select id={id} className={selectClass} value={destBranchId} onChange={(e) => setDestBranchId(e.target.value)}>
            <option value="">Chọn chi nhánh…</option>
            {dests.map((b) => (
              <option key={b.branchId} value={b.branchId}>
                {b.name}
              </option>
            ))}
          </select>
        )}
      </Field>
      {branches.isSuccess && dests.length === 0 && (
        <p role="note" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-800">
          Chưa có chi nhánh nào khác đang hoạt động để nhận hàng.
        </p>
      )}
      <div className="space-y-2">
        <p className="text-sm font-medium text-(--text-primary)">Các dòng hàng</p>
        {items.data && items.data.totalElements > items.data.content.length && (
          <p role="note" className="text-xs text-(--text-tertiary)">{`Chỉ hiện ${items.data.content.length} / ${items.data.totalElements} sản phẩm đầu tiên.`}</p>
        )}
        {lines.map((l, i) => (
          <div key={i} className="grid gap-2 rounded-lg border border-(--border-color) p-3 sm:grid-cols-[3fr_1fr_auto]">
            <select aria-label={`Dòng ${i + 1} — sản phẩm`} className={selectClass} value={l.productId} onChange={(e) => set(i, { productId: e.target.value })}>
              <option value="">Chọn sản phẩm…</option>
              {stocked.map((it) => (
                <option key={it.productId} value={it.productId}>
                  {it.productName} (khả dụng {it.availableQuantity} {it.unit})
                </option>
              ))}
            </select>
            <Input aria-label={`Dòng ${i + 1} — số lượng`} type="number" value={l.quantity} onChange={(e) => set(i, { quantity: e.target.value })} />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label={`Xóa dòng ${i + 1}`}
              disabled={lines.length < 2}
              onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))}
            >
              <Trash2 className="h-4 w-4" />
            </Button>
          </div>
        ))}
        <Button type="button" variant="outline" size="sm" onClick={() => setLines((ls) => [...ls, emptyLine()])}>
          <Plus className="h-4 w-4" /> Thêm dòng
        </Button>
      </div>
      <FormError message={error} />
    </FormModal>
  );
}
