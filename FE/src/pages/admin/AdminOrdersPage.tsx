/**
 * Xử lý đơn online của chi nhánh (UC65) — shop-v1 #12–19. Lễ tân xử lý, quản lý chi nhánh chỉ xem (nút thao tác ẩn).
 * Danh sách mặc định là "việc cần làm" (PAID + PROCESSING + DELIVERY_FAILED, cũ nhất trước); chọn một đơn để xem chi tiết và thao tác theo trạng thái.
 */
import { useState } from 'react';
import { Inbox } from 'lucide-react';
import { formatVnDateTime } from '@/components/appointments/format';
import { NoticeBar, selectClass, type Notice } from '@/components/admin/form-ui';
import { QueryError, RowsSkeleton, StatePanel } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { StatusBadge } from '@/components/ui/modal-templates';
import { cn, formatCurrency } from '@/lib/utils';
import { useOnlineOrders } from '@/shared/hooks/useShop';
import { useAccount } from '@/shared/stores/session.store';
import { ONLINE_ORDER_STATUSES, ONLINE_ORDER_STATUS_LABELS, type OnlineOrderStatus } from '@/shared/types/shop';
import { OrderDetailPanel } from './orders/OrderDetailPanel';
import { Pager } from './orders/Pager';
import { ORDER_TONE } from './orders/tones';

const PAGE_SIZE = 20;

export function AdminOrdersPage() {
  const canAct = useAccount()?.role === 'RECEPTIONIST';
  const [status, setStatus] = useState<OnlineOrderStatus | ''>('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<number | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const list = useOnlineOrders({ status: status || undefined, page, size: PAGE_SIZE });
  const data = list.data;
  // Đang chờ dữ liệu của bộ lọc mới thì bảng còn là của bộ lọc cũ.
  const stale = list.isPlaceholderData;

  const goTo = (p: number) => {
    setPage(p);
    setNotice(null);
  };

  let body;
  if (!data && list.isError) {
    body = <QueryError error={list.error} onRetry={() => list.refetch()} what="đơn online" />;
  } else if (!data) {
    body = <RowsSkeleton rows={5} />;
  } else if (data.content.length === 0) {
    body = (
      <StatePanel
        icon={Inbox}
        title={status ? 'Không có đơn nào ở trạng thái này' : 'Không còn đơn nào cần xử lý'}
        body={status ? 'Thử chọn trạng thái khác.' : 'Đơn mới đã thanh toán sẽ xuất hiện ở đây.'}
      />
    );
  } else {
    body = (
      <>
        <p role="status" className="border-b border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
          {stale ? 'Đang tải…' : `${data.totalElements} đơn`}
        </p>
        <table className={cn('table', stale && 'opacity-60')} aria-label="Danh sách đơn online" aria-busy={stale}>
          <thead>
            <tr className="[&>th]:whitespace-nowrap">
              <th>Mã đơn</th>
              <th>Đặt lúc</th>
              <th className="text-right!">Tổng tiền</th>
              <th>Trạng thái</th>
              <th className="text-right!">Thao tác</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((o) => (
              <tr key={o.onlineOrderId} className={cn(selected === o.onlineOrderId && 'bg-(--bg-secondary)')}>
                <td className="whitespace-nowrap font-medium text-(--text-primary)">{o.code}</td>
                <td className="whitespace-nowrap tabular-nums text-(--text-secondary)">{formatVnDateTime(o.createdAt)}</td>
                <td className="text-right tabular-nums">
                  {formatCurrency(o.total)}
                  <span className="block text-xs text-(--text-tertiary)">{o.itemCount} dòng hàng</span>
                </td>
                <td className="whitespace-nowrap">
                  <StatusBadge status={ONLINE_ORDER_STATUS_LABELS[o.status]} variant={ORDER_TONE[o.status]} />
                </td>
                <td className="text-right">
                  <Button size="sm" variant="outline" disabled={stale} onClick={() => setSelected(o.onlineOrderId)} aria-label={`Xem đơn ${o.code}`}>
                    Chi tiết
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pager data={data} noun="đơn" onPage={goTo} />
      </>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Đơn online</h1>
        <p className="text-(--text-secondary)">
          Đơn khách đặt qua website tại chi nhánh: chuẩn bị hàng, bàn giao vận chuyển, cập nhật đã giao hoặc giao thất bại.
          {!canAct && ' Bạn chỉ có quyền xem.'}
        </p>
      </div>

      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <select
            aria-label="Lọc trạng thái đơn"
            className={cn(selectClass, 'w-64')}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as OnlineOrderStatus | '');
              goTo(0);
            }}
          >
            <option value="">Việc cần làm</option>
            {ONLINE_ORDER_STATUSES.map((s) => (
              <option key={s} value={s}>
                {ONLINE_ORDER_STATUS_LABELS[s]}
              </option>
            ))}
          </select>
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <div className={cn('grid items-start gap-6', selected != null && 'grid-cols-[minmax(0,1fr)_400px]')}>
        <Card className="card">
          <CardContent className="overflow-x-auto p-0">{body}</CardContent>
        </Card>
        {selected != null && <OrderDetailPanel key={selected} onlineOrderId={selected} canAct={canAct} onClose={() => setSelected(null)} onNotice={setNotice} />}
      </div>
    </div>
  );
}
