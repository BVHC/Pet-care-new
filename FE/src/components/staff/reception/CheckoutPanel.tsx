import { useEffect, useState } from 'react';
import { Minus, Plus, ReceiptText, X } from 'lucide-react';
import { formatCurrency } from '../../../lib/utils';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '../../ui/dialog';
import { METHOD_LABEL } from '../../../shared/constants/clinic-labels';
import type { PaymentReceipt } from '../../../shared/api/clinic.api';
import {
  useCatalog,
  useCollectPayment,
  useCurrentShift,
  useOpenShift,
  usePendingOrders,
} from '../../../shared/hooks/useClinic';
import { useHotkeys } from '../../../shared/hooks/useHotkeys';
import { posTotal, usePOS } from '../../../shared/stores/pos.store';
import type { PaymentMethod } from '../../../shared/types/clinic';
import { formatClock } from '../../../shared/utils/clinic-format';
import { Panel, PanelTitle, Skeleton, StatePanel, Ticket, WsButton } from '../ui';

interface ReceiptView extends PaymentReceipt {
  items: { name: string; qty: number; amount: number }[];
  change: number | null;
}

/** Header badge: is my cashier shift open, and how much has it taken. */
export function ShiftBadge({ receptionistId }: { receptionistId: string }) {
  const shift = useCurrentShift(receptionistId);
  if (shift.isLoading) return <Skeleton className="h-6 w-40 rounded-full" />;
  if (!shift.data) {
    return (
      <span className="ws-chip" data-tone="wait">
        Chưa mở ca thu ngân
      </span>
    );
  }
  return (
    <span className="ws-chip ws-num" data-tone="ready">
      Ca mở lúc {formatClock(shift.data.openedAt)}, đã thu {formatCurrency(shift.data.cashTotal + shift.data.transferTotal)}
    </span>
  );
}

export function CheckoutPanel({
  className,
  receptionistId,
  hotkeysEnabled,
}: {
  className?: string;
  receptionistId: string;
  /** Ctrl+Enter pays only while the receipt is on screen. */
  hotkeysEnabled: boolean;
}) {
  const retail = usePOS((s) => s.retail);
  const orderIds = usePOS((s) => s.orderIds);
  const customerId = usePOS((s) => s.customerId);
  const method = usePOS((s) => s.method);
  const setQty = usePOS((s) => s.setQty);
  const detachOrder = usePOS((s) => s.detachOrder);
  const setMethod = usePOS((s) => s.setMethod);
  const clear = usePOS((s) => s.clear);

  const catalog = useCatalog();
  const pending = usePendingOrders();
  const shift = useCurrentShift(receptionistId);
  const openShift = useOpenShift(receptionistId);
  const collect = useCollectPayment(receptionistId);

  const [received, setReceived] = useState('');
  const [transferChecked, setTransferChecked] = useState(false);
  const [receipt, setReceipt] = useState<ReceiptView | null>(null);

  // An order paid or cancelled from another screen leaves the pending list — drop it here too.
  useEffect(() => {
    const stillPending = pending.data;
    if (!stillPending) return;
    orderIds.filter((id) => !stillPending.some((o) => o.id === id)).forEach(detachOrder);
  }, [pending.data, orderIds, detachOrder]);

  const products = catalog.data?.products ?? [];
  const attached = (pending.data ?? []).filter((o) => orderIds.includes(o.id));
  const total = posTotal({ retail, orderIds }, products, pending.data ?? []);
  const empty = retail.length === 0 && attached.length === 0;
  const customerName = attached[0]?.customer.name ?? 'Khách lẻ';
  const cashGiven = Number(received.replace(/[^0-9]/g, '')) || 0;
  const change = method === 'CASH' && cashGiven >= total && total > 0 ? cashGiven - total : null;

  const blocked = empty
    ? 'Phiếu thu đang trống.'
    : method === 'BANK_TRANSFER' && !transferChecked
      ? 'Kiểm tra tài khoản đã nhận chuyển khoản rồi đánh dấu ô xác nhận.'
      : method === 'CASH' && received && cashGiven < total
        ? 'Tiền khách đưa chưa đủ.'
        : null;

  const pay = () => {
    if (!shift.data || blocked || collect.isPending) return;
    const items = [
      ...attached.flatMap((o) => o.lines.map((l) => ({ name: l.name, qty: l.qty, amount: l.unitPrice * l.qty }))),
      ...retail.map((l) => {
        const p = products.find((x) => x.id === l.productId);
        return { name: p?.name ?? l.productId, qty: l.qty, amount: (p?.price ?? 0) * l.qty };
      }),
    ];
    collect.mutate(
      { orderIds, retailLines: retail, customerId: customerId ?? undefined, method, amount: total },
      {
        onSuccess: (r) => {
          setReceipt({ ...r, items, change });
          clear();
          setReceived('');
          setTransferChecked(false);
        },
      },
    );
  };

  useHotkeys({ 'mod+enter': pay }, hotkeysEnabled);

  return (
    <Panel label="Phiếu thu" className={className}>
      <PanelTitle
        aside={
          !empty && (
            <WsButton size="sm" variant="ghost" onClick={clear}>
              Xóa phiếu
            </WsButton>
          )
        }
      >
        Phiếu thu <span className="font-normal text-(--ws-ink-2)">{customerName}</span>
      </PanelTitle>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {empty ? (
          <StatePanel
            icon={ReceiptText}
            title="Phiếu thu đang trống"
            body="Chọn sản phẩm để bán, hoặc bấm Thu tiền ở tab Chờ thu để lấy đơn từ phòng khám và grooming."
          />
        ) : (
          <ul className="divide-y divide-(--ws-line)">
            {attached.map((o) => (
              <li key={o.id} className="px-4 py-3">
                <div className="flex items-center gap-2">
                  {o.queueNo !== undefined && <Ticket no={o.queueNo} size="sm" />}
                  <p className="min-w-0 flex-1 truncate font-semibold">{o.petName ?? 'Đơn chờ thu'}</p>
                  <button
                    type="button"
                    onClick={() => detachOrder(o.id)}
                    aria-label={`Bỏ đơn ${o.petName ?? ''} khỏi phiếu thu`}
                    className="inline-flex h-8 w-8 items-center justify-center rounded-md text-(--ws-ink-3) hover:bg-(--ws-raised) hover:text-(--ws-ink)"
                  >
                    <X className="h-4 w-4" />
                  </button>
                </div>
                <ul className="mt-1.5 space-y-0.5 pl-[2.6rem] text-[13px] text-(--ws-ink-2)">
                  {o.lines.map((l) => (
                    <li key={l.id} className="flex justify-between gap-3">
                      <span className="truncate">
                        {l.name}
                        {l.qty > 1 && ` × ${l.qty}`}
                      </span>
                      <span className="ws-num shrink-0">{formatCurrency(l.unitPrice * l.qty)}</span>
                    </li>
                  ))}
                </ul>
              </li>
            ))}
            {retail.map((l) => {
              const p = products.find((x) => x.id === l.productId);
              if (!p) return null;
              return (
                <li key={l.productId} className="flex items-center gap-2 px-4 py-2.5">
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium">{p.name}</p>
                    <p className="ws-num text-xs text-(--ws-ink-3)">
                      {formatCurrency(p.price)} / {p.unit}
                    </p>
                  </div>
                  <QtyStepper label={p.name} value={l.qty} max={p.available} onChange={(qty) => setQty(p.id, qty)} />
                  <span className="ws-num w-[5.5rem] text-right text-sm font-semibold">{formatCurrency(p.price * l.qty)}</span>
                </li>
              );
            })}
          </ul>
        )}
      </div>

      <div className="flex flex-col gap-3 border-t border-(--ws-line) bg-(--ws-raised) p-4">
        <div className="flex items-baseline justify-between">
          <span className="text-sm font-medium text-(--ws-ink-2)">Tổng thu</span>
          <span className="ws-num text-2xl font-bold tracking-tight">{formatCurrency(total)}</span>
        </div>

        <div role="radiogroup" aria-label="Phương thức thanh toán" className="grid grid-cols-2 gap-1 rounded-lg border border-(--ws-line) bg-(--ws-canvas) p-1">
          {(['CASH', 'BANK_TRANSFER'] as PaymentMethod[]).map((m) => (
            <button
              key={m}
              type="button"
              role="radio"
              aria-checked={method === m}
              onClick={() => setMethod(m)}
              className="h-9 rounded-md text-sm font-medium text-(--ws-ink-2) aria-checked:bg-(--ws-surface) aria-checked:font-semibold aria-checked:text-(--ws-ink) aria-checked:shadow-[0_1px_2px_rgba(0,0,0,0.08)]"
            >
              {METHOD_LABEL[m]}
            </button>
          ))}
        </div>

        {method === 'CASH' ? (
          <div className="flex items-center gap-3 text-sm">
            <label htmlFor="cash-given" className="w-20 shrink-0 text-(--ws-ink-2)">
              Khách đưa
            </label>
            <input
              id="cash-given"
              inputMode="numeric"
              value={received}
              onChange={(e) => setReceived(e.target.value)}
              placeholder="Không bắt buộc"
              className="ws-num h-9 min-w-0 flex-1 rounded-md border border-(--ws-line-strong) bg-(--ws-surface) px-2.5 text-right text-(--ws-ink)"
            />
            {change !== null && <span className="ws-num shrink-0 font-semibold">Thối {formatCurrency(change)}</span>}
          </div>
        ) : (
          <label className="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              checked={transferChecked}
              onChange={(e) => setTransferChecked(e.target.checked)}
              className="mt-0.5 h-4 w-4 accent-(--ws-accent)"
            />
            Tài khoản đã nhận đủ tiền chuyển khoản
          </label>
        )}

        {shift.isLoading ? (
          <Skeleton className="h-12 w-full" />
        ) : shift.data ? (
          <>
            <WsButton variant="primary" size="lg" disabled={!!blocked || collect.isPending} onClick={pay}>
              {collect.isPending ? 'Đang thu…' : `Thu ${formatCurrency(total)}`}
              <span className="text-xs font-medium opacity-75">Ctrl Enter</span>
            </WsButton>
            {blocked && !empty && <p className="text-xs text-(--ws-ink-2)">{blocked}</p>}
          </>
        ) : (
          <div className="rounded-lg border border-dashed border-(--ws-line-strong) p-3">
            <p className="text-sm font-semibold">Chưa mở ca thu ngân</p>
            <p className="text-[13px] text-(--ws-ink-2)">Mở ca của bạn để bắt đầu thu tiền.</p>
            <WsButton className="mt-2 w-full" variant="primary" disabled={openShift.isPending} onClick={() => openShift.mutate()}>
              Mở ca thu ngân
            </WsButton>
          </div>
        )}
      </div>

      <ReceiptDialog receipt={receipt} onClose={() => setReceipt(null)} />
    </Panel>
  );
}

function QtyStepper({ label, value, max, onChange }: { label: string; value: number; max: number; onChange: (qty: number) => void }) {
  const button =
    'inline-flex h-8 w-8 items-center justify-center text-(--ws-ink-2) hover:bg-(--ws-raised) hover:text-(--ws-ink) disabled:opacity-40';
  return (
    <div role="group" aria-label={`Số lượng ${label}`} className="flex items-center rounded-md border border-(--ws-line-strong)">
      <button type="button" className={button} onClick={() => onChange(value - 1)} aria-label={`Bớt 1 ${label}`}>
        <Minus className="h-3.5 w-3.5" />
      </button>
      <span className="ws-num w-7 text-center text-sm font-semibold">{value}</span>
      <button type="button" className={button} disabled={value >= max} onClick={() => onChange(value + 1)} aria-label={`Thêm 1 ${label}`}>
        <Plus className="h-3.5 w-3.5" />
      </button>
    </div>
  );
}

function ReceiptDialog({ receipt, onClose }: { receipt: ReceiptView | null; onClose: () => void }) {
  return (
    <Dialog open={!!receipt} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="ws-portal border-(--ws-line) bg-(--ws-surface) text-(--ws-ink) sm:max-w-sm">
        {receipt && (
          <div className="flex flex-col gap-4">
            <div>
              <DialogTitle className="ws-num text-xl font-bold">Đã thu {formatCurrency(receipt.total)}</DialogTitle>
              <DialogDescription className="text-sm text-(--ws-ink-2)">
                {receipt.customerName}, {METHOD_LABEL[receipt.method].toLowerCase()} lúc {formatClock(receipt.paidAt)}
              </DialogDescription>
            </div>
            <ul className="divide-y divide-(--ws-line) border-y border-(--ws-line) text-sm">
              {receipt.items.map((item, i) => (
                <li key={i} className="flex justify-between gap-3 py-1.5">
                  <span className="truncate">
                    {item.name}
                    {item.qty > 1 && ` × ${item.qty}`}
                  </span>
                  <span className="ws-num shrink-0">{formatCurrency(item.amount)}</span>
                </li>
              ))}
            </ul>
            {receipt.change !== null && (
              <p className="ws-num flex justify-between text-sm font-semibold">
                <span>Tiền thối</span>
                {formatCurrency(receipt.change)}
              </p>
            )}
            <WsButton variant="primary" autoFocus onClick={onClose}>
              Xong
            </WsButton>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
