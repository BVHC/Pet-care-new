import { useState } from 'react';
import { Plus } from 'lucide-react';
import { Checkbox, EmptyRow, Field, FormError, NoticeBar, selectClass, type Notice } from '../../components/admin/form-ui';
import { RetailOrderPanel } from '../../components/staff/reception/RetailOrderPanel';
import { Button } from '../../components/ui/button';
import { Card, CardContent } from '../../components/ui/card';
import { Input } from '../../components/ui/input';
import { StatusBadge } from '../../components/ui/modal-templates';
import { cn, formatCurrency, formatDateTime } from '../../lib/utils';
import { salesApi } from '../../shared/api/sales.api';
import { salesErrorMessage, useOrder, useOrders, useSalesMutation } from '../../shared/hooks/useSales';
import { useAccount } from '../../shared/stores/session.store';
import {
  CANCEL_TYPE_LABELS,
  NOT_DELIVERED_TEXT,
  ORDER_LINE_TYPE_LABELS,
  ORDER_SOURCE_LABELS,
  ORDER_STATUS_LABELS,
  type OrderSource,
  type OrderStatus,
} from '../../shared/types/sales';

const PAGE_SIZE = 20;
const STATUS_VARIANT = { OPEN: 'info', PENDING: 'warning', PAID: 'success', CANCELLED: 'neutral' } as const;

/**
 * T34 — Order tại quầy của chi nhánh (UC66; sales-v1 #1–8, #10). Danh sách (lọc trạng thái / nguồn / ngày tạo / chờ thu
 * quá hạn) + chi tiết. Lễ tân: tạo và làm tiếp Order bán lẻ trong RetailOrderPanel, hủy Order bán lẻ chưa chốt.
 * Quản lý chi nhánh: chỉ xem, và hủy Order PENDING khách không thanh toán (BR-BH-05, Order#10). Thu tiền: T35.
 */
export function AdminPOSPage() {
  const role = useAccount()?.role;
  const receptionist = role === 'RECEPTIONIST';
  const manager = role === 'BRANCH_MANAGER';
  const [status, setStatus] = useState('');
  const [source, setSource] = useState('');
  const [date, setDate] = useState('');
  const [overdue, setOverdue] = useState(false);
  const [page, setPage] = useState(0);
  const orders = useOrders({
    status: (status || undefined) as OrderStatus | undefined,
    source: (source || undefined) as OrderSource | undefined,
    date: date || undefined,
    pendingOverdue: overdue || undefined,
    page,
    size: PAGE_SIZE,
  });
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [selling, setSelling] = useState(false);
  const [sellingOrderId, setSellingOrderId] = useState<number | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const data = orders.data;
  const reset = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    setPage(0);
  };
  const openSale = (orderId: number | null) => {
    setNotice(null);
    setSelling(true);
    setSellingOrderId(orderId);
    setSelectedId(orderId);
  };
  const view = (orderId: number) => {
    setSelling(false);
    setSelectedId(orderId);
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-(--text-primary)">Order tại quầy</h1>
          <p className="text-(--text-secondary)">
            Order bán lẻ, lượt tiếp nhận và lưu trú của chi nhánh. Order đã chốt chờ thu tiền tại quầy thu ngân; chưa thanh toán thì chưa giao hàng.
          </p>
        </div>
        {receptionist && (
          <Button onClick={() => openSale(null)}>
            <Plus className="h-4 w-4" />
            Tạo Order bán lẻ
          </Button>
        )}
      </div>

      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <select aria-label="Lọc trạng thái" className={cn(selectClass, 'w-44')} value={status} onChange={(e) => reset(setStatus)(e.target.value)}>
            <option value="">Mọi trạng thái</option>
            {(Object.keys(ORDER_STATUS_LABELS) as OrderStatus[]).map((s) => (
              <option key={s} value={s}>
                {ORDER_STATUS_LABELS[s]}
              </option>
            ))}
          </select>
          <select aria-label="Lọc nguồn" className={cn(selectClass, 'w-44')} value={source} onChange={(e) => reset(setSource)(e.target.value)}>
            <option value="">Mọi nguồn</option>
            {(Object.keys(ORDER_SOURCE_LABELS) as OrderSource[]).map((s) => (
              <option key={s} value={s}>
                {ORDER_SOURCE_LABELS[s]}
              </option>
            ))}
          </select>
          <Input aria-label="Ngày tạo" type="date" className="w-40" value={date} onChange={(e) => reset(setDate)(e.target.value)} />
          {/* Ngưỡng N ngày là [CFG] order.pending_alert_days (BR-BH-05, ST13); BE tính, FE không đoán. */}
          <Checkbox label="Chờ thu quá hạn" checked={overdue} onChange={reset(setOverdue)} />
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <div className="grid items-start gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(360px,420px)]">
        <Card className="card">
          <CardContent className="overflow-x-auto p-0">
            <table className="table">
              <thead>
                <tr>
                  <th>Mã Order</th>
                  <th>Nguồn</th>
                  <th>Khách</th>
                  <th className="text-right">Tổng tiền</th>
                  <th>Trạng thái</th>
                  <th>Tạo lúc</th>
                  <th className="text-right">Thao tác</th>
                </tr>
              </thead>
              <tbody>
                {data?.content.length ? (
                  data.content.map((o) => (
                    <tr key={o.orderId} className={cn(o.orderId === selectedId && 'bg-(--bg-tertiary)')}>
                      <td className="font-mono text-xs">{o.code}</td>
                      <td>{ORDER_SOURCE_LABELS[o.source]}</td>
                      <td className="font-medium text-(--text-primary)">{o.customerName}</td>
                      <td className="text-right tabular-nums">{formatCurrency(o.totalAmount)}</td>
                      <td>
                        <StatusBadge status={ORDER_STATUS_LABELS[o.status]} variant={STATUS_VARIANT[o.status]} />
                      </td>
                      <td className="whitespace-nowrap">{formatDateTime(o.createdAt)}</td>
                      <td className="text-right">
                        <Button size="sm" variant="outline" onClick={() => view(o.orderId)} aria-label={`Xem Order ${o.code}`}>
                          Xem
                        </Button>
                      </td>
                    </tr>
                  ))
                ) : (
                  <EmptyRow colSpan={7} loading={orders.isLoading} />
                )}
              </tbody>
            </table>
            {orders.isError && <FormError message={salesErrorMessage(orders.error)} />}
            {data && data.totalPages > 1 && (
              <div className="flex items-center justify-end gap-3 border-t border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
                <span>
                  Trang {data.page + 1}/{data.totalPages} · {data.totalElements} Order
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

        {selling ? (
          <div className="space-y-2">
            <div className="flex justify-end">
              <Button size="sm" variant="outline" onClick={() => setSelling(false)}>
                Đóng bán hàng
              </Button>
            </div>
            <RetailOrderPanel
              className="h-[640px]"
              orderId={sellingOrderId}
              onOrderChange={(id) => {
                setSellingOrderId(id);
                if (id != null) setSelectedId(id);
              }}
            />
          </div>
        ) : selectedId != null ? (
          <OrderDetail
            key={selectedId}
            orderId={selectedId}
            receptionist={receptionist}
            manager={manager}
            onContinue={() => openSale(selectedId)}
            onDone={(text) => setNotice({ kind: 'success', text })}
          />
        ) : (
          <Card className="card">
            <CardContent className="p-6 text-sm text-(--text-secondary)">Chọn một Order để xem chi tiết.</CardContent>
          </Card>
        )}
      </div>
    </div>
  );
}

/** Chi tiết Order + thao tác theo vai trò; vai trò không làm được thì không thấy nút (BR-BH-05). */
function OrderDetail({
  orderId,
  receptionist,
  manager,
  onContinue,
  onDone,
}: {
  orderId: number;
  receptionist: boolean;
  manager: boolean;
  onContinue: () => void;
  onDone: (text: string) => void;
}) {
  const query = useOrder(orderId);
  const cancel = useSalesMutation(() => salesApi.cancelOrder(orderId));
  const cancelUnpaid = useSalesMutation((reason: string) => salesApi.cancelUnpaid(orderId, reason));
  const [mode, setMode] = useState<'cancel' | 'unpaid' | null>(null);
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const o = query.data;

  if (!o) {
    return (
      <Card className="card">
        <CardContent className="p-6 text-sm text-(--text-secondary)">
          {query.isError ? <FormError message={salesErrorMessage(query.error)} /> : 'Đang tải…'}
        </CardContent>
      </Card>
    );
  }

  const busy = cancel.isPending || cancelUnpaid.isPending;
  const fail = (e: unknown) => setError(salesErrorMessage(e));
  const back = () => {
    setMode(null);
    setError('');
  };
  const doCancel = () =>
    cancel.mutate(undefined, {
      onSuccess: (x) => {
        back();
        onDone(`Đã hủy Order ${x.code}`);
      },
      onError: fail,
    });
  const doCancelUnpaid = () => {
    // BR-BH-05: không cho xác nhận khi chưa có lý do; BE kiểm lại.
    if (!reason.trim()) return setError('Vui lòng nhập lý do hủy Order.');
    cancelUnpaid.mutate(reason.trim(), {
      onSuccess: (x) => {
        back();
        setReason('');
        onDone(`Đã hủy Order ${x.code} — ghi nhận thất thu`);
      },
      onError: fail,
    });
  };

  return (
    <Card className="card">
      <CardContent className="space-y-4 p-5">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="mr-auto text-lg font-semibold text-(--text-primary)">Order {o.code}</h2>
          <StatusBadge status={ORDER_STATUS_LABELS[o.status]} variant={STATUS_VARIANT[o.status]} />
        </div>
        <p className="text-sm text-(--text-secondary)">
          {ORDER_SOURCE_LABELS[o.source]} · <span className="font-medium text-(--text-primary)">{o.customerName}</span>
        </p>
        {!o.deliveryAllowed && o.status === 'PENDING' && (
          <p role="status" className="rounded-lg bg-amber-50 px-3 py-2 text-sm font-medium text-amber-800">
            {NOT_DELIVERED_TEXT}
          </p>
        )}
        <dl className="grid grid-cols-2 gap-3 text-sm">
          <div>
            <dt className="text-(--text-secondary)">Tạo lúc</dt>
            <dd className="font-medium">{formatDateTime(o.createdAt)}</dd>
          </div>
          {o.pendingAt && (
            <div>
              <dt className="text-(--text-secondary)">Chốt lúc</dt>
              <dd className="font-medium">{formatDateTime(o.pendingAt)}</dd>
            </div>
          )}
          {o.paidAt && (
            <div>
              <dt className="text-(--text-secondary)">Thanh toán lúc</dt>
              <dd className="font-medium">{formatDateTime(o.paidAt)}</dd>
            </div>
          )}
          {o.cancelledAt && (
            <div className="col-span-2">
              <dt className="text-(--text-secondary)">Hủy lúc</dt>
              <dd className="font-medium">
                {formatDateTime(o.cancelledAt)}
                {o.cancelType && ` · ${CANCEL_TYPE_LABELS[o.cancelType]}`}
                {o.cancelReason && ` — ${o.cancelReason}`}
              </dd>
            </div>
          )}
        </dl>

        <table className="table">
          <thead>
            <tr>
              <th>Dòng</th>
              <th className="text-right">SL</th>
              <th className="text-right">Thành tiền</th>
            </tr>
          </thead>
          <tbody>
            {o.lines.length ? (
              o.lines.map((l) => (
                <tr key={l.lineId}>
                  <td>
                    <span className="font-medium text-(--text-primary)">{l.description}</span>
                    <span className="block text-xs text-(--text-tertiary)">
                      {ORDER_LINE_TYPE_LABELS[l.lineType]} · {formatCurrency(l.unitPrice)}
                    </span>
                  </td>
                  <td className="text-right tabular-nums">{l.quantity}</td>
                  <td className="text-right tabular-nums">{formatCurrency(l.lineTotal)}</td>
                </tr>
              ))
            ) : (
              <EmptyRow colSpan={3} loading={false} />
            )}
          </tbody>
        </table>
        <p className="flex justify-between text-base font-semibold">
          <span>Tổng tiền</span>
          <span className="tabular-nums">{formatCurrency(o.totalAmount)}</span>
        </p>

        {mode === 'cancel' && (
          <div className="space-y-3 rounded-lg border border-(--border-color) p-3 text-sm">
            <p>Hủy Order chưa chốt {o.code}? Các dòng giữ lại làm lịch sử, kho không thay đổi.</p>
            <FormError message={error} />
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={back} disabled={busy}>
                Quay lại
              </Button>
              <Button variant="destructive" onClick={doCancel} disabled={busy}>
                Xác nhận hủy
              </Button>
            </div>
          </div>
        )}
        {mode === 'unpaid' && (
          <div className="space-y-3 rounded-lg border border-red-200 p-3 text-sm">
            <p>
              Order chuyển sang <b>Đã hủy</b> và được tính là <b>thất thu</b>; thao tác được ghi nhật ký (audit). Kho, bệnh án, lịch hẹn,
              lưu trú giữ nguyên.
            </p>
            <Field label="Lý do hủy" hint="Bắt buộc, tối đa 300 ký tự.">
              {(id) => (
                <textarea
                  id={id}
                  value={reason}
                  maxLength={300}
                  rows={3}
                  onChange={(e) => setReason(e.target.value)}
                  autoFocus
                  className="w-full rounded-lg border border-(--color-border-default) px-3 py-2 text-sm"
                />
              )}
            </Field>
            <FormError message={error} />
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={back} disabled={busy}>
                Quay lại
              </Button>
              <Button variant="destructive" onClick={doCancelUnpaid} disabled={busy || !reason.trim()}>
                Xác nhận hủy Order
              </Button>
            </div>
          </div>
        )}
        {mode === null && (
          <>
            <FormError message={error} />
            <div className="flex flex-wrap justify-end gap-2">
              {receptionist && o.source === 'RETAIL' && o.status === 'OPEN' && (
                <>
                  <Button variant="outline" className="text-red-600" onClick={() => setMode('cancel')}>
                    Hủy Order
                  </Button>
                  <Button onClick={onContinue}>Tiếp tục bán</Button>
                </>
              )}
              {manager && o.status === 'PENDING' && (
                <Button variant="outline" className="text-red-600" onClick={() => setMode('unpaid')}>
                  Hủy – khách không thanh toán
                </Button>
              )}
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
