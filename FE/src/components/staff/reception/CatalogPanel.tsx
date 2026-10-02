import { forwardRef, useImperativeHandle, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { PackageSearch, Search } from 'lucide-react';
import { toast } from 'sonner';
import { cn, formatCurrency } from '../../../lib/utils';
import { CATEGORY_LABEL } from '../../../shared/constants/clinic-labels';
import { useCatalog } from '../../../shared/hooks/useClinic';
import { usePOS } from '../../../shared/stores/pos.store';
import type { CatalogProduct, ProductCategory } from '../../../shared/types/clinic';
import { announce } from '../../../shared/utils/announce';
import { foldVi } from '../../../shared/utils/text';
import { Kbd, Panel, QueryError, Skeleton, StatePanel } from '../ui';

const FILTERS: { id: ProductCategory | 'ALL'; label: string }[] = [
  { id: 'ALL', label: 'Tất cả' },
  ...(Object.keys(CATEGORY_LABEL) as ProductCategory[]).map((id) => ({ id, label: CATEGORY_LABEL[id] })),
];

/** Counter sales. Prescription-only medicine never shows here (BR-SP-01). */
export const CatalogPanel = forwardRef<HTMLInputElement, { className?: string }>(function CatalogPanel(
  { className },
  ref,
) {
  const searchRef = useRef<HTMLInputElement>(null);
  useImperativeHandle(ref, () => searchRef.current as HTMLInputElement);
  const catalog = useCatalog();
  const retail = usePOS((s) => s.retail);
  const addProduct = usePOS((s) => s.addProduct);
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState<ProductCategory | 'ALL'>('ALL');
  const gridRef = useRef<HTMLDivElement>(null);

  const products = useMemo(() => {
    const q = foldVi(query.trim());
    return (catalog.data?.products ?? [])
      .filter((p) => !p.prescriptionOnly)
      .filter((p) => category === 'ALL' || p.category === category)
      .filter((p) => !q || foldVi(p.name).includes(q));
  }, [catalog.data, query, category]);

  const inReceipt = (productId: string) => retail.find((l) => l.productId === productId)?.qty ?? 0;

  const add = (product: CatalogProduct) => {
    if (inReceipt(product.id) >= product.available) {
      toast.error(`${product.name} chỉ còn ${product.available} ${product.unit} có thể bán.`);
      return;
    }
    addProduct(product.id);
    announce(`Đã thêm ${product.name} vào phiếu thu`);
  };

  const onSearchKey = (e: KeyboardEvent<HTMLInputElement>) => {
    // Plain Enter only: Ctrl/Cmd+Enter belongs to "Thu tiền".
    if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey && query.trim() && products[0]) {
      e.preventDefault();
      add(products[0]);
    } else if (e.key === 'Escape' && query) {
      e.preventDefault();
      setQuery('');
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      gridRef.current?.querySelector<HTMLButtonElement>('[data-tile]:not(:disabled)')?.focus();
    }
  };

  // Arrow keys move between tiles; up/down jump a whole row.
  const onGridKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const tiles = Array.from(gridRef.current?.querySelectorAll<HTMLButtonElement>('[data-tile]') ?? []);
    const index = tiles.indexOf(document.activeElement as HTMLButtonElement);
    if (index < 0) return;
    const perRow = tiles.filter((t) => t.offsetTop === tiles[0].offsetTop).length || 1;
    const steps: Record<string, number> = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: perRow, ArrowUp: -perRow };
    const step = steps[e.key];
    if (!step) return;
    e.preventDefault();
    if (index + step < 0 && e.key === 'ArrowUp') {
      searchRef.current?.focus();
      return;
    }
    tiles[Math.min(Math.max(index + step, 0), tiles.length - 1)]?.focus();
  };

  return (
    <Panel label="Bán hàng tại quầy" className={className}>
      <div className="flex flex-col gap-2 border-b border-(--ws-line) p-3">
        <label className="relative block">
          <span className="sr-only">Tìm sản phẩm</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-(--ws-ink-3)" aria-hidden />
          <input
            ref={searchRef}
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={onSearchKey}
            placeholder="Tìm sản phẩm, Enter để thêm"
            className="h-11 w-full rounded-lg border border-(--ws-line-strong) bg-(--ws-raised) pl-9 pr-14 text-sm text-(--ws-ink) placeholder:text-(--ws-ink-3) focus:border-(--ws-accent) focus:bg-(--ws-surface)"
          />
          <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2">
            <Kbd>F1</Kbd>
          </span>
        </label>
        <div role="group" aria-label="Lọc theo nhóm hàng" className="-mx-1 flex gap-1.5 overflow-x-auto px-1 pb-0.5">
          {FILTERS.map((f) => (
            <button
              key={f.id}
              type="button"
              aria-pressed={category === f.id}
              onClick={() => setCategory(f.id)}
              className="h-8 shrink-0 rounded-full border border-(--ws-line) px-3 text-[13px] text-(--ws-ink-2) hover:border-(--ws-ink-3) aria-pressed:border-(--ws-accent) aria-pressed:bg-(--ws-accent-soft) aria-pressed:font-semibold aria-pressed:text-(--ws-accent-ink)"
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto p-3">
        {catalog.isLoading ? (
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3" role="status" aria-label="Đang tải sản phẩm">
            {Array.from({ length: 6 }, (_, i) => (
              <Skeleton key={i} className="h-24" />
            ))}
          </div>
        ) : catalog.isError ? (
          <QueryError error={catalog.error} onRetry={() => catalog.refetch()} what="sản phẩm" />
        ) : products.length === 0 ? (
          <StatePanel icon={PackageSearch} title="Không có sản phẩm khớp" body="Thử tên ngắn hơn hoặc chọn nhóm Tất cả." />
        ) : (
          <div ref={gridRef} onKeyDown={onGridKey} className="grid grid-cols-2 gap-2 sm:grid-cols-3 2xl:grid-cols-4">
            {products.map((p) => {
              const qty = inReceipt(p.id);
              const left = p.available - qty;
              return (
                <button
                  key={p.id}
                  type="button"
                  data-tile
                  disabled={left <= 0}
                  onClick={() => add(p)}
                  aria-label={`${p.name}, ${formatCurrency(p.price)}, còn ${left} ${p.unit}`}
                  className="relative flex min-h-24 flex-col items-start gap-1 rounded-lg border border-(--ws-line) bg-(--ws-surface) p-3 text-left transition-colors hover:border-(--ws-accent) disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <span className="line-clamp-2 pr-6 text-sm font-medium leading-snug">{p.name}</span>
                  <span className="ws-num mt-auto text-[15px] font-bold">{formatCurrency(p.price)}</span>
                  <span className={cn('text-xs', left <= 5 ? 'font-semibold text-(--ws-wait-ink)' : 'text-(--ws-ink-3)')}>
                    {left <= 0 ? 'Hết hàng' : `Còn ${left} ${p.unit}`}
                  </span>
                  {qty > 0 && (
                    <span className="ws-num absolute right-2 top-2 min-w-5 rounded-full bg-(--ws-accent) px-1.5 text-center text-xs font-bold text-white">
                      {qty}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        )}
      </div>
      <p className="border-t border-(--ws-line) px-4 py-2 text-xs text-(--ws-ink-3)">
        Thuốc kê đơn chỉ bán theo đơn của bác sĩ nên không có trong danh sách này.
      </p>
    </Panel>
  );
});
