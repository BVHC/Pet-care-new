import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Layers, Search, SlidersHorizontal } from 'lucide-react';
import { Checkbox, EmptyRow, Field, FormError, NoticeBar, selectClass, type Notice } from '../../../components/admin/form-ui';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { DetailModal, FormModal, StatusBadge } from '../../../components/ui/modal-templates';
import { cn, formatDate } from '../../../lib/utils';
import { inventoryApi } from '../../../shared/api/inventory.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useDebounce } from '../../../shared/hooks/useDebounce';
import { useInventoryItems, useInventoryMutation, useStockLots } from '../../../shared/hooks/useInventory';
import { PRODUCT_TYPE_LABELS, type ProductType } from '../../../shared/types/catalog';
import { EXPIRY_WARNING_DAYS_DEFAULT, type InventoryItem } from '../../../shared/types/inventory';

const PAGE_SIZE = 20;
const TYPES = Object.keys(PRODUCT_TYPE_LABELS) as ProductType[];

/** UC75 — tồn theo lô / hạn dùng (BR-KO-01) và tồn tối thiểu (BR-KO-07). `canEdit`: quản lý chi nhánh đặt tồn tối thiểu. */
export function StockTab({ canEdit }: { canEdit: boolean }) {
  const [params] = useSearchParams();
  const [q, setQ] = useState('');
  const search = useDebounce(q.trim());
  const [productType, setProductType] = useState(params.get('productType') ?? '');
  const [belowMin, setBelowMin] = useState(false);
  const [expiring, setExpiring] = useState(false);
  const [page, setPage] = useState(0);
  const items = useInventoryItems({
    q: search || undefined,
    productType: (productType || undefined) as ProductType | undefined,
    belowMin: belowMin || undefined,
    expiringWithinDays: expiring ? EXPIRY_WARNING_DAYS_DEFAULT : undefined,
    page,
    size: PAGE_SIZE,
  });
  const [lotsOf, setLotsOf] = useState<InventoryItem | null>(null);
  const [minOf, setMinOf] = useState<InventoryItem | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const reset = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    setPage(0);
  };
  const data = items.data;

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="p-4 flex flex-wrap items-center gap-3">
          <div className="relative min-w-[220px] flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-(--text-tertiary)" />
            <Input
              aria-label="Tìm theo tên hoặc SKU"
              placeholder="Tìm theo tên hoặc SKU…"
              className="pl-10"
              value={q}
              onChange={(e) => reset(setQ)(e.target.value)}
            />
          </div>
          <select aria-label="Lọc loại" className={cn(selectClass, 'w-36')} value={productType} onChange={(e) => reset(setProductType)(e.target.value)}>
            <option value="">Mọi loại</option>
            {TYPES.map((t) => (
              <option key={t} value={t}>
                {PRODUCT_TYPE_LABELS[t]}
              </option>
            ))}
          </select>
          <Checkbox label="Chỉ hàng dưới tồn tối thiểu" checked={belowMin} onChange={reset(setBelowMin)} />
          <Checkbox label={`Có lô hết hạn trong ${EXPIRY_WARNING_DAYS_DEFAULT} ngày`} checked={expiring} onChange={reset(setExpiring)} />
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>SKU</th>
                <th>Sản phẩm</th>
                <th>Loại</th>
                <th className="text-right">Khả dụng / tổng</th>
                <th className="text-right">Đã hết hạn</th>
                <th>Hạn gần nhất</th>
                <th className="text-right">Tồn tối thiểu</th>
                <th>Cảnh báo</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {data?.content.length ? (
                data.content.map((it) => (
                  <tr key={it.productId}>
                    <td className="font-mono text-xs text-(--text-secondary)">{it.sku}</td>
                    <td className="font-medium text-(--text-primary)">{it.productName}</td>
                    <td className="text-(--text-secondary)">{PRODUCT_TYPE_LABELS[it.productType]}</td>
                    <td className="text-right tabular-nums">
                      <span className="font-semibold">{it.availableQuantity}</span> / {it.totalQuantity} {it.unit}
                    </td>
                    <td className="text-right tabular-nums">{it.expiredQuantity || '—'}</td>
                    <td className="text-(--text-secondary)">{it.nearestExpiryDate ? formatDate(it.nearestExpiryDate) : '—'}</td>
                    <td className="text-right tabular-nums">{it.minQuantity}</td>
                    <td>
                      <div className="flex flex-wrap gap-1">
                        {it.belowMin && <StatusBadge status="Dưới tồn tối thiểu" variant="warning" />}
                        {it.expiredQuantity > 0 && <StatusBadge status="Có lô hết hạn" variant="danger" />}
                      </div>
                    </td>
                    <td>
                      <div className="flex justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => setLotsOf(it)} aria-label={`Xem lô ${it.productName}`} title="Xem lô">
                          <Layers className="h-4 w-4" />
                        </Button>
                        {canEdit && (
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => setMinOf(it)}
                            aria-label={`Tồn tối thiểu ${it.productName}`}
                            title="Đặt tồn tối thiểu"
                          >
                            <SlidersHorizontal className="h-4 w-4" />
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                <EmptyRow colSpan={9} loading={items.isLoading} />
              )}
            </tbody>
          </table>
          {data && data.totalPages > 1 && (
            <div className="flex items-center justify-end gap-3 border-t border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
              <span>
                Trang {data.page + 1}/{data.totalPages} · {data.totalElements} sản phẩm
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

      {lotsOf && <LotsModal item={lotsOf} onClose={() => setLotsOf(null)} />}
      {minOf && (
        <MinQuantityForm
          item={minOf}
          onClose={() => setMinOf(null)}
          onSaved={(text) => {
            setMinOf(null);
            setNotice({ kind: 'success', text });
          }}
        />
      )}
    </div>
  );
}

/** Lô theo thứ tự FEFO (hạn gần nhất trước), lô đã hết hạn đánh dấu để lập phiếu điều chỉnh lý do hết hạn. */
function LotsModal({ item, onClose }: { item: InventoryItem; onClose: () => void }) {
  const lots = useStockLots(item.productId);
  return (
    <DetailModal open onOpenChange={(open) => !open && onClose()} title={`Lô hàng — ${item.productName}`} size="lg">
      {lots.isError && <FormError message={plainMessage(toApiError(lots.error))} />}
      <table className="table">
        <thead>
          <tr>
            <th>Số lô</th>
            <th>Hạn dùng</th>
            <th className="text-right">Số lượng</th>
            <th>Tình trạng</th>
          </tr>
        </thead>
        <tbody>
          {lots.data?.length ? (
            lots.data.map((lot) => (
              <tr key={lot.stockLotId}>
                <td className="font-mono text-xs">{lot.isDefault ? 'Lô mặc định' : lot.lotNumber}</td>
                <td>{lot.expiryDate ? formatDate(lot.expiryDate) : '—'}</td>
                <td className="text-right tabular-nums">
                  {lot.quantity} {item.unit}
                </td>
                <td>{lot.expired ? <StatusBadge status="Đã hết hạn" variant="danger" /> : <StatusBadge status="Còn hạn" variant="success" />}</td>
              </tr>
            ))
          ) : (
            <EmptyRow colSpan={4} loading={lots.isLoading} />
          )}
        </tbody>
      </table>
    </DetailModal>
  );
}

function MinQuantityForm({ item, onClose, onSaved }: { item: InventoryItem; onClose: () => void; onSaved: (text: string) => void }) {
  const [value, setValue] = useState(String(item.minQuantity));
  const [error, setError] = useState('');
  const save = useInventoryMutation((minQuantity: number) => inventoryApi.setMinQuantity(item.productId, minQuantity));

  const submit = async () => {
    const minQuantity = Number(value);
    if (value.trim() === '' || !Number.isInteger(minQuantity) || minQuantity < 0) {
      setError('Tồn tối thiểu phải là số nguyên ≥ 0');
      return;
    }
    try {
      await save.mutateAsync(minQuantity);
      onSaved(`Đã đặt tồn tối thiểu của ${item.productName} là ${minQuantity} ${item.unit}`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title="Tồn tối thiểu"
      description={`${item.productName} — dưới mức này sẽ được cảnh báo hằng ngày (BR-KO-07).`}
      onSubmit={submit}
      loading={save.isPending}
      size="sm"
    >
      <Field label={`Tồn tối thiểu (${item.unit})`}>
        {(id) => <Input id={id} type="number" min={0} step={1} value={value} onChange={(e) => setValue(e.target.value)} autoFocus />}
      </Field>
      <FormError message={error} />
    </FormModal>
  );
}
