import { useState } from 'react';
import { X } from 'lucide-react';
import { formatVnDateTime } from '@/components/appointments/format';
import type { Notice } from '@/components/admin/form-ui';
import { QueryError, RowsSkeleton } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { StatusBadge } from '@/components/ui/modal-templates';
import { formatCurrency } from '@/lib/utils';
import { shopApi } from '@/shared/api/shop.api';
import { useOnlineOrder, useShopMutation } from '@/shared/hooks/useShop';
import { ONLINE_ORDER_STATUS_LABELS, type OnlineOrder } from '@/shared/types/shop';
import { commerceError } from './commerce-error';
import { OrderReasonDialog, ShipOrderDialog } from './OrderActionDialogs';
import { ORDER_TONE } from './tones';

type Dialog = 'ship' | 'redeliver' | 'fail' | 'cancel';

const when = (label: string, iso?: string | null) =>
  iso ? (
    <p key={label}>
      {label}: <span className="tabular-nums text-(--text-primary)">{formatVnDateTime(iso)}</span>
    </p>
  ) : null;

interface PanelProps {
  onlineOrderId: number;
  /** Chỉ lễ tân xử lý đơn (shop-v1 mục B); quản lý chi nhánh chỉ xem. */
  canAct: boolean;
  onClose: () => void;
  onNotice: (notice: Notice) => void;
}

/** Chi tiết đơn (#13) kèm nút thao tác theo trạng thái: PAID → bắt đầu xử lý · PROCESSING → bàn giao · SHIPPED → đã giao / giao thất bại · DELIVERY_FAILED → giao lại / hủy. */
export function OrderDetailPanel({ onlineOrderId, canAct, onClose, onNotice }: PanelProps) {
  const detail = useOnlineOrder(onlineOrderId);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const run = useShopMutation((fn: (id: number) => Promise<OnlineOrder>) => fn(onlineOrderId));
  const order = detail.data;

  const direct = async (fn: (id: number) => Promise<OnlineOrder>, text: string) => {
    try {
      await run.mutateAsync(fn);
      onNotice({ kind: 'success', text });
    } catch (e) {
      onNotice({ kind: 'error', text: commerceError(e, 'Đơn') });
    }
  };
  const done = (text: string) => {
    setDialog(null);
    onNotice({ kind: 'success', text });
  };

  let body;
  if (!order) {
    body = detail.isError ? <QueryError error={detail.error} onRetry={() => detail.refetch()} what="chi tiết đơn" /> : <RowsSkeleton rows={3} />;
  } else {
    const busy = run.isPending;
    body = (
      <div className="space-y-4 p-4 text-sm text-(--text-secondary)">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-base font-semibold text-(--text-primary)">{order.code}</span>
          <StatusBadge status={ONLINE_ORDER_STATUS_LABELS[order.status]} variant={ORDER_TONE[order.status]} />
        </div>
        <div>
          <p className="font-medium text-(--text-primary)">{order.receiverName}</p>
          <p>{order.receiverPhone}</p>
          <p>{order.shipAddress}</p>
          <p className="mt-1 text-xs">Mã Order bán hàng: #{order.salesOrderId} (dùng khi tạo yêu cầu trả hàng)</p>
        </div>
        <table className="table w-full" aria-label="Dòng hàng của đơn">
          <thead>
            <tr>
              <th>Sản phẩm</th>
              <th className="text-right!">SL</th>
              <th className="text-right!">Thành tiền</th>
            </tr>
          </thead>
          <tbody>
            {order.lines.map((l) => (
              <tr key={l.productId}>
                <td>{l.name}</td>
                <td className="text-right tabular-nums">{l.quantity}</td>
                <td className="text-right tabular-nums">{formatCurrency(l.lineTotal)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="text-right tabular-nums">
          <p>Tiền hàng: {formatCurrency(order.subtotal)}</p>
          <p>Phí vận chuyển: {formatCurrency(order.shippingFee)}</p>
          <p className="font-semibold text-(--text-primary)">Tổng: {formatCurrency(order.total)}</p>
        </div>
        {(order.carrier || order.trackingCode) && (
          <p>
            Vận chuyển: <span className="text-(--text-primary)">{[order.carrier, order.trackingCode].filter(Boolean).join(' · ')}</span>
            {order.redeliveryCount > 0 && ` · đã giao lại ${order.redeliveryCount} lần`}
          </p>
        )}
        <div className="space-y-0.5 text-xs">
          {when('Đặt lúc', order.createdAt)}
          {when('Thanh toán lúc', order.paidAt)}
          {when('Bàn giao lúc', order.shippedAt)}
          {when('Giao lúc', order.deliveredAt)}
          {when('Hoàn tất lúc', order.completedAt)}
          {when('Hủy lúc', order.cancelledAt)}
          {order.cancelReason && (
            <p>
              Lý do hủy: <span className="text-(--text-primary)">{order.cancelReason}</span>
            </p>
          )}
        </div>
        {canAct && (
          <div className="flex flex-wrap gap-2 border-t border-(--border-color) pt-3">
            {order.status === 'PAID' && (
              <Button size="sm" disabled={busy} onClick={() => direct(shopApi.startProcessing, `Đã bắt đầu xử lý đơn ${order.code}.`)}>
                Bắt đầu xử lý
              </Button>
            )}
            {order.status === 'PROCESSING' && (
              <Button size="sm" onClick={() => setDialog('ship')}>
                Bàn giao vận chuyển
              </Button>
            )}
            {order.status === 'SHIPPED' && (
              <>
                <Button size="sm" disabled={busy} onClick={() => direct(shopApi.markDelivered, `Đã cập nhật đã giao đơn ${order.code}, bắt đầu đếm hạn trả hàng.`)}>
                  Cập nhật đã giao
                </Button>
                <Button size="sm" variant="outline" className="border-red-200 text-red-600" onClick={() => setDialog('fail')}>
                  Giao thất bại
                </Button>
              </>
            )}
            {order.status === 'DELIVERY_FAILED' && (
              <>
                <Button size="sm" onClick={() => setDialog('redeliver')}>
                  Giao lại
                </Button>
                <Button size="sm" variant="outline" className="border-red-200 text-red-600" onClick={() => setDialog('cancel')}>
                  Hủy đơn
                </Button>
              </>
            )}
          </div>
        )}
        {dialog === 'ship' || dialog === 'redeliver' ? (
          <ShipOrderDialog order={order} redeliver={dialog === 'redeliver'} onClose={() => setDialog(null)} onDone={done} />
        ) : dialog ? (
          <OrderReasonDialog order={order} cancel={dialog === 'cancel'} onClose={() => setDialog(null)} onDone={done} />
        ) : null}
      </div>
    );
  }

  return (
    <Card className="card" aria-label="Chi tiết đơn online">
      <div className="flex items-center justify-between border-b border-(--border-color) px-4 py-3">
        <h2 className="font-semibold text-(--text-primary)">Chi tiết đơn</h2>
        <Button size="sm" variant="ghost" onClick={onClose} aria-label="Đóng chi tiết đơn">
          <X className="h-4 w-4" aria-hidden />
        </Button>
      </div>
      <CardContent className="p-0">{body}</CardContent>
    </Card>
  );
}
