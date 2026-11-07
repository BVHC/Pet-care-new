// Y lệnh của một ca nội trú (inpatient-v1 #6–8). Nút theo vai trò và trạng thái: lễ tân chỉ xem; CARETAKER không đụng y lệnh thuốc; ca đã xuất viện không còn nút.
import { useState } from 'react';
import { Check, Plus, X } from 'lucide-react';
import { EmptyRow } from '@/components/admin/form-ui';
import { formatVnDateTime } from '@/components/appointments/format';
import { QueryError } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { StatusBadge } from '@/components/ui/modal-templates';
import { useAdmissionOrders } from '@/shared/hooks/useInpatient';
import { ADMISSION_ORDER_STATUS_LABELS, ADMISSION_ORDER_TYPE_LABELS, type Admission, type AdmissionOrder, type AdmissionOrderStatus } from '@/shared/types/inpatient';
import { CancelOrderDialog, CompleteOrderDialog, CreateOrderDialog } from './OrderDialogs';
import type { InpatientPermissions } from './permissions';

const TONE: Record<AdmissionOrderStatus, 'info' | 'success' | 'neutral'> = { ORDERED: 'info', DONE: 'success', CANCELLED: 'neutral' };

type Dialog = { kind: 'create' } | { kind: 'complete' | 'cancel'; order: AdmissionOrder };

export function OrdersSection({ admission, perms, onNotice }: { admission: Admission; perms: InpatientPermissions; onNotice: (text: string) => void }) {
  const orders = useAdmissionOrders(admission.admissionId);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const active = admission.status === 'ADMITTED';
  const done = (text: string) => {
    setDialog(null);
    onNotice(text);
  };

  return (
    <Card className="card">
      <CardContent className="p-0">
        <div className="flex items-center justify-between gap-3 border-b border-(--border-color) px-4 py-3">
          <h2 className="text-base font-semibold text-(--text-primary)">Y lệnh</h2>
          {active && perms.createOrder && (
            <Button size="sm" onClick={() => setDialog({ kind: 'create' })}>
              <Plus className="h-4 w-4" aria-hidden /> Ra y lệnh
            </Button>
          )}
        </div>
        {orders.isError && !orders.data ? (
          <QueryError error={orders.error} onRetry={() => orders.refetch()} what="y lệnh" />
        ) : (
          <table className="table" aria-label="Y lệnh nội trú">
            <thead>
              <tr>
                <th>Giờ thực hiện</th>
                <th>Loại</th>
                <th>Nội dung</th>
                <th>Trạng thái</th>
                <th className="text-right!">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {!orders.data || orders.data.length === 0 ? (
                <EmptyRow colSpan={5} loading={orders.isPending} />
              ) : (
                orders.data.map((o) => (
                  <tr key={o.admissionOrderId}>
                    <td className="whitespace-nowrap align-top tabular-nums">{formatVnDateTime(o.scheduledAt)}</td>
                    <td className="align-top whitespace-nowrap">{ADMISSION_ORDER_TYPE_LABELS[o.orderType]}</td>
                    <td className="align-top">
                      {o.content}
                      {o.doneNote && <span className="mt-0.5 block text-xs text-(--text-secondary)">Ghi chú: {o.doneNote}</span>}
                      {o.cancelReason && <span className="mt-0.5 block text-xs text-(--text-secondary)">Lý do hủy: {o.cancelReason}</span>}
                    </td>
                    <td className="align-top whitespace-nowrap">
                      <StatusBadge status={ADMISSION_ORDER_STATUS_LABELS[o.status]} variant={TONE[o.status]} />
                    </td>
                    <td className="align-top">
                      {active && o.status === 'ORDERED' && (
                        <div className="flex justify-end gap-1.5">
                          {perms.completeOrder(o.orderType) && (
                            <Button size="sm" aria-label={`Đã thực hiện: ${o.content}`} onClick={() => setDialog({ kind: 'complete', order: o })}>
                              <Check className="h-4 w-4" aria-hidden /> Đã thực hiện
                            </Button>
                          )}
                          {perms.cancelOrder && (
                            <Button size="sm" variant="outline" className="border-red-200 text-red-600" aria-label={`Hủy y lệnh: ${o.content}`} onClick={() => setDialog({ kind: 'cancel', order: o })}>
                              <X className="h-4 w-4" aria-hidden /> Hủy
                            </Button>
                          )}
                        </div>
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        )}
      </CardContent>
      {dialog?.kind === 'create' && <CreateOrderDialog admission={admission} onClose={() => setDialog(null)} onDone={done} />}
      {dialog?.kind === 'complete' && <CompleteOrderDialog order={dialog.order} onClose={() => setDialog(null)} onDone={done} />}
      {dialog?.kind === 'cancel' && <CancelOrderDialog order={dialog.order} onClose={() => setDialog(null)} onDone={done} />}
    </Card>
  );
}
