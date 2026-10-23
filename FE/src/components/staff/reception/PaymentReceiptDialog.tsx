// Phiếu thu sau khi thu xong (UC70): mã PT-yyMM-nnnnn, giờ thu, thu ngân (lễ tân của ca), phương thức, từng Order, tổng, tiền khách đưa / thối.
import { formatCurrency } from '../../../lib/utils';
import { PAYMENT_METHOD_LABELS, type Payment } from '../../../shared/types/sales';
import { formatClock } from '../../../shared/utils/clinic-format';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '../../ui/dialog';
import { WsButton } from '../ui';

export interface ReceiptView {
  payment: Payment;
  cashierName: string;
  customerName: string;
  /** Chỉ phiếu tiền mặt, khi lễ tân có nhập "Khách đưa" đủ tiền. */
  cashGiven: number | null;
  change: number | null;
}

export function PaymentReceiptDialog({ receipt, onClose }: { receipt: ReceiptView | null; onClose: () => void }) {
  return (
    <Dialog open={!!receipt} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="ws-portal border-(--ws-line) bg-(--ws-surface) text-(--ws-ink) sm:max-w-sm">
        {receipt && (
          <div className="flex flex-col gap-4">
            <div>
              <DialogTitle className="ws-num text-xl font-bold">Đã thu {formatCurrency(receipt.payment.amount)}</DialogTitle>
              <DialogDescription className="text-sm text-(--ws-ink-2)">
                {receipt.customerName}, {PAYMENT_METHOD_LABELS[receipt.payment.method].toLowerCase()} lúc {formatClock(receipt.payment.paidAt)}
              </DialogDescription>
            </div>
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
              <dt className="text-(--ws-ink-2)">Mã phiếu thu</dt>
              <dd className="ws-num text-right font-semibold">{receipt.payment.code}</dd>
              <dt className="text-(--ws-ink-2)">Thu ngân</dt>
              <dd className="text-right">{receipt.cashierName}</dd>
            </dl>
            <ul aria-label="Order đã thu" className="divide-y divide-(--ws-line) border-y border-(--ws-line) text-sm">
              {receipt.payment.orders.map((o) => (
                <li key={o.orderId} className="flex justify-between gap-3 py-1.5">
                  <span className="ws-num truncate">{o.code}</span>
                  <span className="ws-num shrink-0">{formatCurrency(o.totalAmount)}</span>
                </li>
              ))}
            </ul>
            <p className="ws-num flex justify-between text-sm font-semibold">
              <span>Tổng thu</span>
              {formatCurrency(receipt.payment.amount)}
            </p>
            {receipt.cashGiven !== null && receipt.change !== null && (
              <div className="ws-num flex flex-col gap-1 text-sm">
                <p className="flex justify-between">
                  <span>Khách đưa</span>
                  {formatCurrency(receipt.cashGiven)}
                </p>
                <p className="flex justify-between font-semibold">
                  <span>Tiền thối</span>
                  {formatCurrency(receipt.change)}
                </p>
              </div>
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
