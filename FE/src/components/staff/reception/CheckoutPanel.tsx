// Phiếu thu của lễ tân (UC70; BR-TG-01…04, BR-BH-04): chọn khách → tick các Order chờ thu của khách (thu gộp, một phiếu = một khách)
// → xem trước tổng tiền và dòng thiếu tồn → chọn phương thức → thu → phiếu thu. Chạy trên sales-v1 #12–14 (T35).
// Số tiền gửi lên luôn là tổng xem trước (không thu một phần); "Khách đưa" chỉ để tính tiền thối.
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ReceiptText } from 'lucide-react';
import { formatCurrency } from '../../../lib/utils';
import { useCustomerDetail } from '../../../domains/pets/hooks/useCustomerPet';
import { useHotkeys } from '../../../shared/hooks/useHotkeys';
import { paymentErrorMessage, useMyShift, usePay, usePayableOrders, usePaymentPreview } from '../../../shared/hooks/useSales';
import { usePOS } from '../../../shared/stores/pos.store';
import { useAccount } from '../../../shared/stores/session.store';
import { ORDER_SOURCE_LABELS, PAYMENT_METHOD_LABELS, type OrderSummary, type PaymentMethod, type PaymentPreview, type ShortageLine } from '../../../shared/types/sales';
import { formatClock } from '../../../shared/utils/clinic-format';
import { Panel, PanelTitle, QueryError, RowsSkeleton, StatePanel, WsButton } from '../ui';
import { CustomerLookup } from './CustomerLookup';
import { PaymentReceiptDialog, type ReceiptView } from './PaymentReceiptDialog';
import { OpenShiftPanel } from './ShiftControls';

/** Một phiếu thu tối đa 20 Order (hợp đồng A7). */
const MAX_ORDERS = 20;
const METHODS: PaymentMethod[] = ['CASH', 'TRANSFER'];

interface Props {
  className?: string;
  /** Ctrl+Enter thu tiền chỉ khi phiếu thu đang hiện trên màn hình. */
  hotkeysEnabled: boolean;
}

/** Thu tiền là việc của lễ tân (A06); quản lý chi nhánh trong bàn lễ tân chỉ thấy lời nhắc, không gửi lệnh gọi thu ngân nào. */
export function CheckoutPanel({ className, hotkeysEnabled }: Props) {
  const role = useAccount()?.role;
  if (role !== 'RECEPTIONIST') return <NotCashier className={className} />;
  return <Cashier className={className} hotkeysEnabled={hotkeysEnabled} />;
}

function NotCashier({ className }: { className?: string }) {
  return (
    <Panel label="Phiếu thu" className={className}>
      <PanelTitle>Phiếu thu</PanelTitle>
      <StatePanel
        icon={ReceiptText}
        title="Thu tiền là việc của lễ tân"
        body="Thu tiền và mở / chốt ca là việc của lễ tân. Đối soát ca ở trang Ca thu ngân."
        action={
          <Link to="/admin/payments" className="text-sm font-semibold text-(--ws-accent-ink) underline">
            Ca thu ngân
          </Link>
        }
      />
    </Panel>
  );
}

function Cashier({ className, hotkeysEnabled }: Props) {
  const customerId = usePOS((s) => s.customerId);
  const orderIds = usePOS((s) => s.orderIds);
  const method = usePOS((s) => s.method);
  const setCustomer = usePOS((s) => s.setCustomer);
  const setOrderIds = usePOS((s) => s.setOrderIds);
  const setMethod = usePOS((s) => s.setMethod);
  const clear = usePOS((s) => s.clear);

  const shiftQuery = useMyShift(true);
  const shift = shiftQuery.data?.shift ?? null;
  const hasShift = !!shiftQuery.data?.open && shift != null;
  const customer = useCustomerDetail(customerId);
  const payable = usePayableOrders(customerId, true);
  const orders = payable.data ?? [];
  const pay = usePay();

  const [received, setReceived] = useState('');
  /** Tổng mà lễ tân đã xác nhận "đã nhận đủ tiền chuyển khoản": đổi tổng thì phải xác nhận lại. */
  const [transferConfirmedFor, setTransferConfirmedFor] = useState<number | null>(null);
  const [receipt, setReceipt] = useState<ReceiptView | null>(null);
  const [payError, setPayError] = useState('');

  // Khách chọn ngay trên phiếu: tick sẵn mọi Order chờ thu (tối đa 20). Order do tab Chờ thu đưa sang thì chỉ tick Order đó.
  const autoSelect = useRef<number | null>(null);
  const pickCustomer = (id: number) => {
    autoSelect.current = id;
    setPayError('');
    setCustomer(id);
  };
  const changeCustomer = () => {
    setPayError('');
    clear();
  };
  useEffect(() => {
    const list = payable.data;
    if (!list || customerId == null) return;
    if (autoSelect.current === customerId) {
      autoSelect.current = null;
      setOrderIds(list.slice(0, MAX_ORDERS).map((o) => o.orderId));
    } else if (orderIds.some((id) => !list.some((o) => o.orderId === id))) {
      // Order đã thu hoặc đã hủy ở nơi khác thì rời khỏi phiếu.
      setOrderIds(orderIds.filter((id) => list.some((o) => o.orderId === id)));
    }
  }, [payable.data, customerId, orderIds, setOrderIds]);

  const selected = payable.data ? orderIds.filter((id) => orders.some((o) => o.orderId === id)) : orderIds;

  // Đang thu thì không xem trước nữa: thu xong, làm mới danh sách sẽ không kéo theo lần xem trước Order vừa thu (400 BR-TG-01).
  const preview = usePaymentPreview(pay.isPending ? [] : selected);
  const lastPreview = useRef<PaymentPreview | undefined>(undefined);
  if (preview.data) lastPreview.current = preview.data;
  const shown = pay.isPending ? lastPreview.current : preview.data;
  const total = shown?.totalAmount;
  const shortages = shown?.shortages ?? [];

  // "Khách đưa" để trống thì không ràng buộc gì. Đã nhập (kể cả 0) mà thiếu so với tổng thì chưa thu đủ nên không được xác nhận (BR-TG-02).
  const entered = /\d/.test(received);
  const cashGiven = Number(received.replace(/[^0-9]/g, '')) || 0;
  const change = method === 'CASH' && total !== undefined && entered && cashGiven >= total ? cashGiven - total : null;
  const short = method === 'CASH' && total !== undefined && entered && cashGiven < total ? total - cashGiven : null;
  const transferOk = method === 'CASH' || (total !== undefined && transferConfirmedFor === total);

  const blocked =
    selected.length === 0
      ? 'Chọn ít nhất một Order để thu.'
      : shortages.length > 0
        ? 'Chưa thu được vì thiếu tồn kho (xem các dòng ở trên).'
        : !transferOk
          ? 'Kiểm tra tài khoản đã nhận chuyển khoản rồi đánh dấu ô xác nhận.'
          : null;
  const canPay = hasShift && total !== undefined && !blocked && short === null && !pay.isPending && !preview.isError;

  const doPay = () => {
    if (!canPay || !shift || total === undefined) return;
    setPayError('');
    const given = change !== null ? cashGiven : null;
    const change0 = change;
    const customerName = customer.data?.customer.fullName ?? orders[0]?.customerName ?? '';
    pay.mutate(
      // `amount` luôn là tổng xem trước, không phải số "Khách đưa" (BR-TG-02).
      { orderIds: [...selected].sort((a, b) => a - b), method, amount: total },
      {
        onSuccess: (payment) => {
          // Xóa phiếu trước, rồi mới hiện phiếu thu.
          clear();
          setReceived('');
          setTransferConfirmedFor(null);
          setReceipt({ payment, cashierName: shift.cashierName, customerName, cashGiven: given, change: change0 });
        },
        // 409 / 400: usePay đã làm mới danh sách nên Order không còn chờ thu sẽ tự rời khỏi phiếu.
        onError: (e) => setPayError(paymentErrorMessage(e)),
      },
    );
  };
  useHotkeys({ 'mod+enter': doPay }, hotkeysEnabled);

  const toggle = (orderId: number, on: boolean) => {
    setPayError('');
    setOrderIds(on ? [...orderIds, orderId] : orderIds.filter((id) => id !== orderId));
  };

  return (
    <Panel label="Phiếu thu" className={className}>
      <PanelTitle
        aside={
          customerId != null && (
            <WsButton size="sm" variant="ghost" onClick={changeCustomer}>
              Đổi khách
            </WsButton>
          )
        }
      >
        Phiếu thu{' '}
        {customerId != null && <span className="font-normal text-(--ws-ink-2)">{customer.data?.customer.fullName ?? 'đang tải khách…'}</span>}
      </PanelTitle>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {shiftQuery.isLoading ? (
          <RowsSkeleton rows={2} />
        ) : shiftQuery.isError ? (
          <QueryError error={shiftQuery.error} onRetry={() => shiftQuery.refetch()} what="ca thu ngân" />
        ) : customerId == null ? (
          hasShift && <CustomerLookup onPick={pickCustomer} />
        ) : payable.isLoading ? (
          <RowsSkeleton rows={3} />
        ) : payable.isError ? (
          <QueryError error={payable.error} onRetry={() => payable.refetch()} what="Order chờ thu của khách" />
        ) : orders.length === 0 ? (
          <StatePanel icon={ReceiptText} title="Khách không có Order chờ thu" body="Order chốt xong ở quầy bán lẻ hoặc phòng khám sẽ hiện ở đây." />
        ) : (
          <OrderChecklist orders={orders} selected={selected} onToggle={toggle} />
        )}
      </div>

      {/* Ngoài phần thu tiền: 409 / BR-TG-01 làm danh sách rỗng hoặc ca đóng thì phần thu tiền ẩn đi nhưng lỗi vẫn phải đọc được. */}
      {payError && (
        <p role="alert" className="mx-3 mb-2 rounded-md bg-(--ws-urgent-bg) px-3 py-2 text-sm text-(--ws-urgent-ink)">
          {payError}
        </p>
      )}

      {hasShift && customerId != null && orders.length > 0 && (
        <div className="flex flex-col gap-3 border-t border-(--ws-line) bg-(--ws-raised) p-4">
          {shortages.length > 0 && <ShortageNotice shortages={shortages} orders={orders} />}
          <div className="flex items-baseline justify-between">
            <span className="text-sm font-medium text-(--ws-ink-2)">Tổng thu</span>
            <span className="ws-num text-2xl font-bold tracking-tight">
              {selected.length === 0 ? formatCurrency(0) : total === undefined ? '…' : formatCurrency(total)}
            </span>
          </div>
          {preview.isError && (
            <p role="alert" className="rounded-md bg-(--ws-urgent-bg) px-3 py-2 text-sm text-(--ws-urgent-ink)">
              {paymentErrorMessage(preview.error)}
            </p>
          )}

          <div role="radiogroup" aria-label="Phương thức thanh toán" className="grid grid-cols-2 gap-1 rounded-lg border border-(--ws-line) bg-(--ws-canvas) p-1">
            {METHODS.map((m) => (
              <button
                key={m}
                type="button"
                role="radio"
                aria-checked={method === m}
                onClick={() => setMethod(m)}
                className="h-9 rounded-md text-sm font-medium text-(--ws-ink-2) aria-checked:bg-(--ws-surface) aria-checked:font-semibold aria-checked:text-(--ws-ink) aria-checked:shadow-[0_1px_2px_rgba(0,0,0,0.08)]"
              >
                {PAYMENT_METHOD_LABELS[m]}
              </button>
            ))}
          </div>

          {method === 'CASH' ? (
            <div className="flex flex-col gap-1 text-sm">
              <div className="flex items-center gap-3">
                <label htmlFor="cash-given" className="w-20 shrink-0 text-(--ws-ink-2)">
                  Khách đưa
                </label>
                <input
                  id="cash-given"
                  inputMode="numeric"
                  value={received}
                  onChange={(e) => setReceived(e.target.value)}
                  placeholder="Không bắt buộc"
                  aria-invalid={short !== null ? true : undefined}
                  className="ws-num h-9 min-w-0 flex-1 rounded-md border border-(--ws-line-strong) bg-(--ws-surface) px-2.5 text-right text-(--ws-ink)"
                />
              </div>
              {change !== null && (
                <p data-testid="cash-change" className="ws-num text-right font-semibold">
                  Thối {formatCurrency(change)}
                </p>
              )}
              {short !== null && <p className="text-right text-xs font-medium text-(--ws-urgent-ink)">{`Khách đưa chưa đủ: còn thiếu ${formatCurrency(short)}`}</p>}
            </div>
          ) : (
            <label className="flex items-start gap-2 text-sm">
              <input
                type="checkbox"
                checked={total !== undefined && transferConfirmedFor === total}
                disabled={total === undefined}
                onChange={(e) => setTransferConfirmedFor(e.target.checked && total !== undefined ? total : null)}
                className="mt-0.5 h-4 w-4 accent-(--ws-accent)"
              />
              Đã nhận đủ tiền chuyển khoản
            </label>
          )}

          <WsButton variant="primary" size="lg" disabled={!canPay} onClick={doPay}>
            {pay.isPending ? 'Đang thu…' : `Thu ${total === undefined || selected.length === 0 ? '' : formatCurrency(total)}`.trim()}
            <span className="text-xs font-medium opacity-75">Ctrl Enter</span>
          </WsButton>
          {blocked && <p className="text-xs text-(--ws-ink-2)">{blocked}</p>}
        </div>
      )}

      {!shiftQuery.isLoading && !shiftQuery.isError && !hasShift && (
        <div className="border-t border-(--ws-line) bg-(--ws-raised) p-4">
          <OpenShiftPanel />
        </div>
      )}

      <PaymentReceiptDialog receipt={receipt} onClose={() => setReceipt(null)} />
    </Panel>
  );
}

function OrderChecklist({ orders, selected, onToggle }: { orders: OrderSummary[]; selected: number[]; onToggle: (orderId: number, on: boolean) => void }) {
  const full = selected.length >= MAX_ORDERS;
  return (
    <>
      <ul aria-label="Order chờ thu của khách" className="divide-y divide-(--ws-line)">
        {orders.map((o) => {
          const checked = selected.includes(o.orderId);
          return (
            <li key={o.orderId}>
              <label className="flex cursor-pointer items-start gap-3 px-4 py-3">
                <input
                  type="checkbox"
                  checked={checked}
                  disabled={!checked && full}
                  onChange={(e) => onToggle(o.orderId, e.target.checked)}
                  aria-label={`Thu Order ${o.code}`}
                  className="mt-1 h-4 w-4 accent-(--ws-accent)"
                />
                <span className="min-w-0 flex-1">
                  <span className="flex flex-wrap items-center gap-1.5">
                    <span className="ws-num font-semibold">{o.code}</span>
                    <span className="ws-chip">{ORDER_SOURCE_LABELS[o.source]}</span>
                  </span>
                  <span className="block text-xs text-(--ws-ink-3)">{o.pendingAt ? `Chốt lúc ${formatClock(o.pendingAt)}` : 'Chờ thu tiền'}</span>
                </span>
                <span className="ws-num shrink-0 text-sm font-semibold">{formatCurrency(o.totalAmount)}</span>
              </label>
            </li>
          );
        })}
      </ul>
      {full && <p className="px-4 py-2 text-xs text-(--ws-ink-3)">Một phiếu thu tối đa {MAX_ORDERS} Order.</p>}
    </>
  );
}

/** BR-BH-04: thiếu tồn lúc thu thì từ chối thu và chỉ rõ dòng thiếu. VACCINE không bao giờ nằm đây (đã trừ khi tiêm). */
function ShortageNotice({ shortages, orders }: { shortages: ShortageLine[]; orders: OrderSummary[] }) {
  const codeOf = (orderId: number) => orders.find((o) => o.orderId === orderId)?.code ?? `Order #${orderId}`;
  return (
    <div role="alert" className="flex flex-col gap-1 rounded-md bg-(--ws-urgent-bg) px-3 py-2 text-sm text-(--ws-urgent-ink)">
      <p className="font-semibold">Kho không đủ hàng cho các dòng sau</p>
      <ul className="list-disc pl-4">
        {shortages.map((s) => (
          <li key={`${s.orderId}-${s.lineId}`}>
            <span className="ws-num">{codeOf(s.orderId)}</span> · {s.productName}: cần {s.requested}, còn {s.available}
          </li>
        ))}
      </ul>
      <p className="text-xs">Bỏ Order thiếu hàng khỏi phiếu thu hoặc nhờ quản lý bổ sung tồn kho rồi thu lại.</p>
    </div>
  );
}
