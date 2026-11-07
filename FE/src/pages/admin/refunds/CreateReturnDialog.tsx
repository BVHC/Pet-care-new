import { useState } from 'react';
import { Field, FormError, selectClass } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { FormModal } from '@/components/ui/modal-templates';
import { formatCurrency } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { returnsApi } from '@/shared/api/returns.api';
import { useOrder } from '@/shared/hooks/useSales';
import { useReturnsMutation } from '@/shared/hooks/useReturns';
import { RETURN_REASONS, RETURN_REASON_LABELS, RETURNS_LIMITS, type CreateReturnRequestRequest, type ReturnReason } from '@/shared/types/returns';
import { commerceError } from '../orders/commerce-error';

const textareaClass =
  'w-full rounded-lg border border-(--color-border-default) px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent';

/**
 * #1 Tạo yêu cầu trả hàng (UC68, lễ tân). Dòng hàng lấy từ chi tiết Order bán hàng (sales #2) theo mã Order: Order quầy là `orderId`,
 * đơn online là `salesOrderId` hiện ở chi tiết đơn. Chỉ dòng hàng hóa (GOODS) trả được; số lượng tối đa và hạn 7 ngày do máy chủ kiểm (BR-BH-13).
 */
export function CreateReturnDialog({ onClose, onDone }: { onClose: () => void; onDone: (message: string) => void }) {
  const [orderText, setOrderText] = useState('');
  const [orderId, setOrderId] = useState<number | null>(null);
  const [quantities, setQuantities] = useState<Record<number, string>>({});
  const [reasonCode, setReasonCode] = useState<ReturnReason | ''>('');
  const [reasonNote, setReasonNote] = useState('');
  const [error, setError] = useState('');
  const order = useOrder(orderId);
  const create = useReturnsMutation((body: CreateReturnRequestRequest) => returnsApi.create(body));
  const goods = order.data?.lines.filter((l) => l.lineType === 'GOODS') ?? [];

  const lookup = () => {
    const id = Number(orderText);
    if (!Number.isInteger(id) || id < 1) return setError('Nhập mã Order là số nguyên dương.');
    setError('');
    setQuantities({});
    setOrderId(id);
  };

  const submit = async () => {
    const lines = goods.map((l) => ({ orderLineId: l.lineId, quantity: Number(quantities[l.lineId] || 0) })).filter((l) => l.quantity > 0);
    if (!order.data) return setError('Tra cứu Order cần trả hàng trước.');
    if (lines.length === 0) return setError('Nhập số lượng trả cho ít nhất một dòng.');
    if (!reasonCode) return setError('Chọn lý do trả hàng.');
    try {
      const made = await create.mutateAsync({ orderId: order.data.orderId, lines, reasonCode, reasonNote: reasonNote.trim() || null });
      onDone(`Đã tạo yêu cầu trả hàng ${made.code}, chờ quản lý chi nhánh duyệt.`);
    } catch (e) {
      setError(commerceError(e, 'Yêu cầu'));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title="Tạo yêu cầu trả hàng"
      description="Cho Order quầy hoặc đơn online đã giao, trong 7 ngày."
      onSubmit={submit}
      submitText="Tạo yêu cầu"
      loading={create.isPending}
      size="lg"
    >
      <div className="flex items-end gap-2">
        <div className="flex-1">
          <Field label="Mã Order" hint="Order quầy: mã Order. Đơn online: mã Order bán hàng ở chi tiết đơn.">
            {(id) => (
              <Input
                id={id}
                inputMode="numeric"
                value={orderText}
                onChange={(e) => {
                  setOrderText(e.target.value);
                  setError('');
                }}
              />
            )}
          </Field>
        </div>
        <Button type="button" variant="outline" onClick={lookup} disabled={order.isFetching}>
          Tra cứu
        </Button>
      </div>
      {orderId != null && order.isError && (
        <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm font-medium text-red-700">
          {plainMessage(toApiError(order.error))}
        </p>
      )}
      {order.data && (
        <>
          <p className="text-sm text-(--text-secondary)">
            Order {order.data.code} · {order.data.customerName}
          </p>
          {goods.length === 0 ? (
            <p className="text-sm text-(--text-secondary)">Order này không có hàng hóa nào để trả (dịch vụ, thuốc, vaccine không trả được).</p>
          ) : (
            <table className="table w-full" aria-label="Dòng hàng trả">
              <thead>
                <tr>
                  <th>Sản phẩm</th>
                  <th className="text-right!">Đã mua</th>
                  <th className="text-right!">Đơn giá</th>
                  <th className="w-24 text-right!">Trả</th>
                </tr>
              </thead>
              <tbody>
                {goods.map((l) => (
                  <tr key={l.lineId}>
                    <td>{l.description}</td>
                    <td className="text-right tabular-nums">{l.quantity}</td>
                    <td className="text-right tabular-nums">{formatCurrency(l.unitPrice)}</td>
                    <td>
                      <Input
                        type="number"
                        min={0}
                        max={l.quantity}
                        aria-label={`Số lượng trả ${l.description}`}
                        value={quantities[l.lineId] ?? ''}
                        onChange={(e) => {
                          setQuantities({ ...quantities, [l.lineId]: e.target.value });
                          setError('');
                        }}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
      <Field label="Lý do trả hàng">
        {(id) => (
          <select
            id={id}
            className={selectClass}
            value={reasonCode}
            onChange={(e) => {
              setReasonCode(e.target.value as ReturnReason | '');
              setError('');
            }}
          >
            <option value="">Chọn lý do</option>
            {RETURN_REASONS.map((r) => (
              <option key={r} value={r}>
                {RETURN_REASON_LABELS[r]}
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Ghi chú" hint={`${reasonNote.length}/${RETURNS_LIMITS.reasonNote}`}>
        {(id) => <textarea id={id} rows={2} maxLength={RETURNS_LIMITS.reasonNote} className={textareaClass} value={reasonNote} onChange={(e) => setReasonNote(e.target.value)} />}
      </Field>
      <FormError message={error} />
    </FormModal>
  );
}
