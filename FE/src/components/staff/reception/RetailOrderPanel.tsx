/**
 * Bán lẻ tại quầy theo Order (UC66; sales-v1 #1–8; docs/adr/0044, 0051): chọn hồ sơ khách (bắt buộc, hợp đồng A1) →
 * tạo Order RETAIL hoặc làm tiếp Order đang mở của khách → thêm hàng (giá chụp lúc thêm, BR-BH-03; thiếu tồn chỉ cảnh
 * báo, BR-BH-04; thuốc kê đơn bị từ chối, BR-SP-01) → chốt (OPEN → PENDING, Order#5) để quầy thu ngân thu sau (T35),
 * hoặc hủy khi chưa chốt (Order#7). Nút sửa / xóa dòng theo cờ canEdit / canDelete của BE (BR-BH-02); PATCH chỉ giảm
 * số lượng, muốn thêm thì thêm dòng mới để được kiểm tồn (docs/adr/0044 §4.3). Dùng ở quầy lễ tân và /admin/pos.
 */
import { forwardRef, lazy, Suspense, useState, type ForwardedRef, type KeyboardEvent } from 'react';
import { AlertTriangle, Minus, PackageSearch, Search, ShoppingBag, UserPlus, X } from 'lucide-react';
import { toast } from 'sonner';
import { formatCurrency } from '../../../lib/utils';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '../../ui/dialog';
import { useCustomerDetail, useCustomerLookup } from '../../../domains/pets/hooks/useCustomerPet';
import type { CustomerSearchQuery } from '../../../domains/pets/types';
import { salesApi } from '../../../shared/api/sales.api';
import { useProducts } from '../../../shared/hooks/useCatalog';
import { useDebounce } from '../../../shared/hooks/useDebounce';
import { salesErrorMessage, useOrder, useOrders, useSalesMutation } from '../../../shared/hooks/useSales';
import type { Product } from '../../../shared/types/catalog';
import {
  NOT_DELIVERED_TEXT,
  ORDER_STATUS_LABELS,
  type AddOrderLineResult,
  type OrderLine,
  type OrderStatus,
} from '../../../shared/types/sales';
import { announce } from '../../../shared/utils/announce';
import { Kbd, Panel, PanelTitle, QueryError, RowsSkeleton, StatePanel, WsButton } from '../ui';

const CreateCustomerDialog = lazy(() =>
  import('./customers/CreateCustomerDialog').then((m) => ({ default: m.CreateCustomerDialog })),
);

const PHONE = /^0\d{9}$/;
const STATUS_TONE: Record<OrderStatus, string> = { OPEN: 'busy', PENDING: 'wait', PAID: 'ready', CANCELLED: 'done' };
const STATUS_NOTE: Record<Exclude<OrderStatus, 'OPEN'>, string> = {
  PENDING: 'Đã chốt — Order chờ thu tiền tại quầy thu ngân. Chỉ giao hàng sau khi khách thanh toán.',
  PAID: 'Đã thanh toán — được giao hàng cho khách.',
  CANCELLED: 'Order đã hủy.',
};
const inputClass =
  'h-11 w-full rounded-lg border border-(--ws-line-strong) bg-(--ws-raised) text-sm text-(--ws-ink) placeholder:text-(--ws-ink-3) focus:border-(--ws-accent) focus:bg-(--ws-surface)';

interface Props {
  className?: string;
  /** Order đang bán; null = đang chọn khách. Trang giữ state để đổi màn hình không mất Order đang làm. */
  orderId: number | null;
  onOrderChange: (orderId: number | null) => void;
}

/** `ref` trỏ vào ô tìm của bước hiện tại (khách, rồi sản phẩm) để F1 / Ctrl+K focus. */
export const RetailOrderPanel = forwardRef<HTMLInputElement, Props>(function RetailOrderPanel({ className, orderId, onOrderChange }, ref) {
  const [customerId, setCustomerId] = useState<number | null>(null);
  const leave = () => {
    setCustomerId(null);
    onOrderChange(null);
  };
  return (
    <Panel label="Bán lẻ tại quầy" className={className}>
      {orderId != null ? (
        <OrderEditor key={orderId} orderId={orderId} searchRef={ref} onLeave={leave} />
      ) : customerId != null ? (
        <StartOrder customerId={customerId} onBack={() => setCustomerId(null)} onOrder={onOrderChange} />
      ) : (
        <CustomerPicker searchRef={ref} onPick={setCustomerId} />
      )}
    </Panel>
  );
});

// ─── Bước 1: hồ sơ khách ───

/** SĐT tra chính xác (đủ 10 số), có @ là email, còn lại tra họ tên (UC23). */
function lookupOf(term: string): CustomerSearchQuery {
  if (/^\d+$/.test(term)) return { phone: PHONE.test(term) ? term : '' };
  return term.includes('@') ? { email: term } : { name: term };
}

function CustomerPicker({ searchRef, onPick }: { searchRef: ForwardedRef<HTMLInputElement>; onPick: (customerId: number) => void }) {
  const [term, setTerm] = useState('');
  const [creating, setCreating] = useState(false);
  const debounced = useDebounce(term.trim(), 250);
  const lookup = useCustomerLookup(lookupOf(debounced));
  const trimmed = term.trim();
  const waitingForPhone = /^\d+$/.test(trimmed) && !PHONE.test(trimmed);
  const items = trimmed ? (lookup.data?.content ?? []) : [];

  return (
    <>
      <PanelTitle
        aside={
          <WsButton size="sm" onClick={() => setCreating(true)}>
            <UserPlus className="h-4 w-4" aria-hidden />
            Tạo hồ sơ mới
          </WsButton>
        }
      >
        Bán lẻ — chọn khách
      </PanelTitle>
      <div className="border-b border-(--ws-line) p-3">
        <label className="relative block">
          <span className="sr-only">Tìm khách hàng</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-(--ws-ink-3)" aria-hidden />
          <input
            ref={searchRef}
            type="search"
            value={term}
            onChange={(e) => setTerm(e.target.value)}
            placeholder="SĐT, họ tên hoặc email của khách"
            autoComplete="off"
            className={`${inputClass} pl-9 pr-14`}
          />
          <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2">
            <Kbd>F1</Kbd>
          </span>
        </label>
        {waitingForPhone && <p className="mt-1.5 text-xs text-(--ws-ink-3)">Nhập đủ 10 số điện thoại để tra cứu.</p>}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto p-3">
        {lookup.isError ? (
          <p role="alert" className="text-sm text-(--ws-urgent-ink)">
            {salesErrorMessage(lookup.error)}
          </p>
        ) : !trimmed ? (
          <StatePanel icon={ShoppingBag} title="Order bán lẻ cần hồ sơ khách" body="Tìm khách theo SĐT hoặc họ tên. Khách chưa có hồ sơ thì tạo nhanh tại quầy." />
        ) : items.length === 0 && lookup.isSuccess && !lookup.isFetching && !waitingForPhone ? (
          <StatePanel
            title="Không tìm thấy hồ sơ phù hợp"
            action={
              <WsButton size="sm" onClick={() => setCreating(true)}>
                Tạo hồ sơ tại quầy
              </WsButton>
            }
          />
        ) : (
          <ul aria-label="Hồ sơ khách" className="flex flex-col gap-2">
            {items.map((c) => (
              <li key={c.customerId}>
                {/* Cùng SĐT có thể nhiều hồ sơ (BR-KH-10): hiện thêm email đã che để chọn đúng người. */}
                <button
                  type="button"
                  onClick={() => onPick(c.customerId)}
                  className="flex w-full flex-col items-start rounded-lg border border-(--ws-line) px-3 py-2 text-left hover:border-(--ws-accent)"
                >
                  <span className="text-sm font-semibold">{c.fullName}</span>
                  <span className="ws-num text-xs text-(--ws-ink-2)">
                    {c.phone ?? 'Chưa có SĐT'} · {c.maskedEmail ?? 'chưa có email'}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
      <p className="border-t border-(--ws-line) px-4 py-2 text-xs text-(--ws-ink-3)">
        Mỗi Order bán lẻ gắn với một hồ sơ khách; khách vãng lai tạo hồ sơ với họ tên và SĐT.
      </p>
      <Suspense fallback={null}>{creating && <CreateCustomerDialog open onOpenChange={setCreating} onOpenCustomer={onPick} />}</Suspense>
    </>
  );
}

// ─── Bước 2: tạo Order hoặc làm tiếp Order đang mở của khách ───

function StartOrder({ customerId, onBack, onOrder }: { customerId: number; onBack: () => void; onOrder: (orderId: number) => void }) {
  const customer = useCustomerDetail(customerId);
  const open = useOrders({ customerId, status: 'OPEN', source: 'RETAIL', size: 5 });
  const create = useSalesMutation(() => salesApi.createRetailOrder(customerId));
  const [error, setError] = useState('');
  const profile = customer.data?.customer;
  const openOrders = open.data?.content ?? [];

  const start = () => {
    setError('');
    create.mutate(undefined, {
      onSuccess: (order) => {
        announce(`Đã tạo Order ${order.code}`);
        onOrder(order.orderId);
      },
      onError: (e) => setError(salesErrorMessage(e)),
    });
  };

  return (
    <>
      <PanelTitle
        aside={
          <WsButton size="sm" variant="ghost" onClick={onBack}>
            Đổi khách
          </WsButton>
        }
      >
        Bán lẻ — {profile?.fullName ?? 'đang tải khách…'}
      </PanelTitle>
      <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4">
        {customer.isError ? (
          <p role="alert" className="text-sm text-(--ws-urgent-ink)">
            {salesErrorMessage(customer.error)}
          </p>
        ) : (
          profile && <p className="ws-num text-sm text-(--ws-ink-2)">{profile.phone ?? 'Chưa có SĐT'}</p>
        )}
        {openOrders.length > 0 && (
          <div className="flex flex-col gap-2">
            <p className="text-sm font-semibold">Khách đang có Order bán lẻ chưa chốt</p>
            <ul className="flex flex-col gap-2">
              {openOrders.map((o) => (
                <li key={o.orderId} className="flex items-center gap-3 rounded-lg border border-(--ws-line) px-3 py-2">
                  <span className="ws-num mr-auto text-sm">
                    {o.code} · {formatCurrency(o.totalAmount)}
                  </span>
                  <WsButton size="sm" onClick={() => onOrder(o.orderId)} aria-label={`Tiếp tục Order ${o.code}`}>
                    Tiếp tục
                  </WsButton>
                </li>
              ))}
            </ul>
          </div>
        )}
        {error && (
          <p role="alert" className="rounded-md bg-(--ws-urgent-bg) px-3 py-2 text-sm text-(--ws-urgent-ink)">
            {error}
          </p>
        )}
        {/* Không có khóa idempotency (docs/adr/0051 §1.5): khóa nút khi đang tạo để bấm đúp không sinh 2 Order. */}
        <WsButton variant="primary" size="lg" disabled={create.isPending || !profile} onClick={start}>
          {create.isPending ? 'Đang tạo…' : openOrders.length ? 'Tạo Order mới' : 'Tạo Order bán lẻ'}
        </WsButton>
      </div>
    </>
  );
}

// ─── Bước 3: dòng Order, chốt / hủy ───

function OrderEditor({ orderId, searchRef, onLeave }: { orderId: number; searchRef: ForwardedRef<HTMLInputElement>; onLeave: () => void }) {
  const query = useOrder(orderId);
  const [error, setError] = useState('');
  /** Tồn khả dụng theo sản phẩm, lấy từ stockWarning lúc thêm (BR-BH-04). */
  const [available, setAvailable] = useState<Record<number, number>>({});
  const [confirmCancel, setConfirmCancel] = useState(false);
  const decrease = useSalesMutation((l: OrderLine) => salesApi.updateLine(orderId, l.lineId, { quantity: l.quantity - 1 }));
  const remove = useSalesMutation((l: OrderLine) => salesApi.deleteLine(orderId, l.lineId));
  const close = useSalesMutation(() => salesApi.closeOrder(orderId));
  const cancel = useSalesMutation(() => salesApi.cancelOrder(orderId));
  const busy = decrease.isPending || remove.isPending || close.isPending || cancel.isPending;
  const order = query.data;

  if (query.isLoading) return <RowsSkeleton />;
  if (!order) return <QueryError error={query.error} onRetry={() => query.refetch()} what="Order" />;

  const fail = (e: unknown) => setError(salesErrorMessage(e));
  const lineAction = (m: typeof decrease, line: OrderLine) => {
    setError('');
    m.mutate(line, { onError: fail });
  };
  const onAdded = (product: Product, result: AddOrderLineResult) => {
    const warning = result.stockWarning;
    setAvailable((prev) => {
      const next = { ...prev };
      if (warning) next[warning.productId] = warning.available;
      else delete next[product.productId];
      return next;
    });
    announce(warning ? `Đã thêm ${product.name}. Tồn khả dụng còn ${warning.available}` : `Đã thêm ${product.name}`);
  };
  const requested = (productId: number) => order.lines.filter((l) => l.productId === productId).reduce((n, l) => n + l.quantity, 0);
  const shortage = (l: OrderLine) => {
    const left = l.productId != null ? available[l.productId] : undefined;
    return left !== undefined && requested(l.productId!) > left ? left : null;
  };
  const doClose = () => {
    setError('');
    close.mutate(undefined, {
      onSuccess: (o) => toast.success(`Đã chốt Order ${o.code}. Order chờ thu tiền tại quầy thu ngân.`),
      onError: fail,
    });
  };
  const doCancel = () => {
    setError('');
    cancel.mutate(undefined, {
      onSuccess: (o) => {
        setConfirmCancel(false);
        toast.success(`Đã hủy Order ${o.code}`);
        onLeave();
      },
      onError: (e) => {
        setConfirmCancel(false);
        fail(e);
      },
    });
  };

  return (
    <>
      <PanelTitle
        aside={
          <>
            <span className="ws-chip" data-tone={STATUS_TONE[order.status]}>
              {ORDER_STATUS_LABELS[order.status]}
            </span>
            {order.status === 'OPEN' && (
              <WsButton size="sm" variant="ghost" onClick={onLeave} title="Giữ nguyên Order đang mở, làm tiếp sau">
                Để sau
              </WsButton>
            )}
          </>
        }
      >
        <span className="ws-num">{order.code}</span>
      </PanelTitle>
      <p className="px-4 pt-2 text-sm text-(--ws-ink-2)">
        Khách: <strong className="text-(--ws-ink)">{order.customerName}</strong>
      </p>

      {order.status === 'OPEN' && <ProductSearch orderId={orderId} searchRef={searchRef} onAdded={onAdded} onError={fail} onStart={() => setError('')} />}

      <div className="min-h-0 flex-1 overflow-y-auto">
        {order.lines.length === 0 ? (
          <StatePanel icon={PackageSearch} title="Chưa có hàng nào" body="Tìm sản phẩm rồi bấm để thêm vào Order." />
        ) : (
          <ul aria-label="Dòng Order" className="divide-y divide-(--ws-line)">
            {order.lines.map((l) => {
              const left = shortage(l);
              return (
                <li key={l.lineId} className="flex items-start gap-2 px-4 py-2.5">
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium">{l.description}</p>
                    <p className="ws-num text-xs text-(--ws-ink-3)">
                      {formatCurrency(l.unitPrice)} × {l.quantity}
                    </p>
                    {left !== null && (
                      <p className="mt-1 flex items-center gap-1 text-xs font-semibold text-(--ws-wait-ink)">
                        <AlertTriangle className="h-3.5 w-3.5 shrink-0" aria-hidden />
                        Tồn khả dụng còn {left} — chỉ cảnh báo, quầy thu sẽ kiểm lại
                      </p>
                    )}
                  </div>
                  {l.canEdit && (
                    <button
                      type="button"
                      onClick={() => lineAction(decrease, l)}
                      disabled={busy || l.quantity <= 1}
                      aria-label={`Bớt 1 ${l.description}`}
                      className="grid h-8 w-8 place-items-center rounded-md border border-(--ws-line-strong) text-(--ws-ink-2) hover:bg-(--ws-raised) disabled:opacity-40"
                    >
                      <Minus className="h-3.5 w-3.5" aria-hidden />
                    </button>
                  )}
                  <span className="ws-num w-24 pt-1 text-right text-sm font-semibold">{formatCurrency(l.lineTotal)}</span>
                  {l.canDelete && (
                    <button
                      type="button"
                      onClick={() => lineAction(remove, l)}
                      disabled={busy}
                      aria-label={`Xóa ${l.description}`}
                      className="grid h-8 w-8 place-items-center rounded-md text-(--ws-ink-3) hover:bg-(--ws-urgent-bg) hover:text-(--ws-urgent-ink) disabled:opacity-40"
                    >
                      <X className="h-4 w-4" aria-hidden />
                    </button>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </div>

      {error && (
        <p role="alert" className="mx-3 mb-2 rounded-md bg-(--ws-urgent-bg) px-3 py-2 text-sm text-(--ws-urgent-ink)">
          {error}
        </p>
      )}
      <div className="flex flex-col gap-3 border-t border-(--ws-line) bg-(--ws-raised) p-4">
        <div className="flex items-baseline justify-between">
          <span className="text-sm font-medium text-(--ws-ink-2)">Tổng tiền</span>
          <span className="ws-num text-2xl font-bold tracking-tight">{formatCurrency(order.totalAmount)}</span>
        </div>
        {order.status === 'OPEN' ? (
          <>
            <div className="flex gap-2">
              <WsButton variant="danger" onClick={() => setConfirmCancel(true)} disabled={busy}>
                Hủy Order
              </WsButton>
              <WsButton variant="primary" size="lg" className="flex-1" onClick={doClose} disabled={busy || order.lines.length === 0}>
                {close.isPending ? 'Đang chốt…' : 'Chốt Order'}
              </WsButton>
            </div>
            <p className="text-xs text-(--ws-ink-3)">
              {order.lines.length === 0 ? 'Thêm ít nhất một dòng hàng để chốt.' : 'Chốt xong, khách thanh toán ở quầy thu ngân.'}
            </p>
          </>
        ) : (
          <>
            {!order.deliveryAllowed && order.status === 'PENDING' && (
              <p role="status" className="ws-chip justify-center" data-tone="wait">
                {NOT_DELIVERED_TEXT}
              </p>
            )}
            <p className="text-sm text-(--ws-ink-2)">{STATUS_NOTE[order.status]}</p>
            <WsButton variant="primary" onClick={onLeave}>
              Bán đơn mới
            </WsButton>
          </>
        )}
      </div>

      <Dialog open={confirmCancel} onOpenChange={setConfirmCancel}>
        <DialogContent className="ws-portal border-(--ws-line) bg-(--ws-surface) text-(--ws-ink) sm:max-w-sm">
          <DialogTitle>Hủy Order {order.code}?</DialogTitle>
          <DialogDescription className="text-sm text-(--ws-ink-2)">
            Order chưa chốt sẽ chuyển sang Đã hủy. Các dòng được giữ lại làm lịch sử, kho không thay đổi.
          </DialogDescription>
          <div className="flex justify-end gap-2">
            <WsButton onClick={() => setConfirmCancel(false)} disabled={cancel.isPending}>
              Quay lại
            </WsButton>
            <WsButton variant="primary" className="bg-(--ws-urgent-ink)" onClick={doCancel} disabled={cancel.isPending}>
              Xác nhận hủy
            </WsButton>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}

function ProductSearch({
  orderId,
  searchRef,
  onAdded,
  onError,
  onStart,
}: {
  orderId: number;
  searchRef: ForwardedRef<HTMLInputElement>;
  onAdded: (product: Product, result: AddOrderLineResult) => void;
  onError: (e: unknown) => void;
  onStart: () => void;
}) {
  const [q, setQ] = useState('');
  const [qty, setQty] = useState('1');
  const [qtyError, setQtyError] = useState('');
  const debounced = useDebounce(q.trim(), 200);
  // retailOnly bỏ thuốc kê đơn (catalog-v1 A4, BR-SP-01).
  const products = useProducts({ q: debounced || undefined, retailOnly: true, isActive: true, size: 8 });
  const add = useSalesMutation(({ product, quantity }: { product: Product; quantity: number }) =>
    salesApi.addLine(orderId, { lineType: 'GOODS', productId: product.productId, quantity }),
  );
  // Vaccine chỉ vào Order khi ghi mũi tiêm (BR-KB-04): ẩn khỏi danh sách bán lẻ, BE vẫn chặn nếu cố gửi.
  const list = (products.data?.content ?? []).filter((p) => p.productType !== 'VACCINE');
  const settled = debounced === q.trim() && !products.isFetching;

  const addProduct = (product: Product) => {
    const quantity = Number(qty);
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > 1_000_000) {
      setQtyError('Số lượng là số nguyên từ 1');
      return;
    }
    if (add.isPending) return;
    setQtyError('');
    onStart();
    add.mutate(
      { product, quantity },
      {
        onSuccess: (result) => {
          onAdded(product, result);
          setQ('');
          setQty('1');
        },
        onError,
      },
    );
  };

  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    // Chỉ Enter thường; bỏ qua khi kết quả còn là của từ khóa cũ.
    if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey) {
      e.preventDefault();
      if (q.trim() && settled && list[0]) addProduct(list[0]);
    } else if (e.key === 'Escape' && q) {
      e.preventDefault();
      setQ('');
    }
  };

  return (
    <div className="flex flex-col gap-2 border-b border-(--ws-line) p-3">
      <div className="flex gap-2">
        <label className="relative block flex-1">
          <span className="sr-only">Tìm sản phẩm</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-(--ws-ink-3)" aria-hidden />
          <input
            ref={searchRef}
            type="search"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onKeyDown={onKey}
            placeholder="Tìm sản phẩm, Enter để thêm"
            autoComplete="off"
            className={`${inputClass} pl-9 pr-14`}
          />
          <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2">
            <Kbd>F1</Kbd>
          </span>
        </label>
        <input
          type="number"
          inputMode="numeric"
          min={1}
          value={qty}
          onChange={(e) => setQty(e.target.value)}
          aria-label="Số lượng thêm"
          className={`${inputClass} ws-num w-20 px-2 text-center`}
        />
      </div>
      {qtyError && (
        <p role="alert" className="text-xs font-medium text-(--ws-urgent-ink)">
          {qtyError}
        </p>
      )}
      {products.isError ? (
        <QueryError error={products.error} onRetry={() => products.refetch()} what="sản phẩm" />
      ) : (
        <ul aria-label="Sản phẩm bán lẻ" className="grid max-h-52 grid-cols-1 gap-1.5 overflow-y-auto sm:grid-cols-2">
          {list.map((p) => (
            <li key={p.productId}>
              <button
                type="button"
                onClick={() => addProduct(p)}
                disabled={add.isPending}
                aria-label={`Thêm ${p.name}, ${formatCurrency(p.price)} / ${p.unit}`}
                className="flex w-full flex-col items-start gap-0.5 rounded-lg border border-(--ws-line) px-3 py-2 text-left hover:border-(--ws-accent) disabled:opacity-50"
              >
                <span className="line-clamp-1 text-sm font-medium">{p.name}</span>
                <span className="ws-num text-sm font-bold">
                  {formatCurrency(p.price)}
                  <span className="text-xs font-normal text-(--ws-ink-3)"> / {p.unit}</span>
                </span>
              </button>
            </li>
          ))}
          {products.isSuccess && list.length === 0 && <li className="py-3 text-center text-sm text-(--ws-ink-2)">Không có sản phẩm khớp.</li>}
        </ul>
      )}
      <p className="text-xs text-(--ws-ink-3)">Thuốc kê đơn chỉ bán theo đơn của bác sĩ nên không có ở đây.</p>
    </div>
  );
}
