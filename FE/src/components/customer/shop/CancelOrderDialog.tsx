// Khách hủy đơn (shop-v1 #10, BR-BH-10): được khi PENDING_PAYMENT / PAID / PROCESSING; lý do bắt buộc, tối đa 300 ký tự. 409 khi đơn vừa đổi trạng thái.
import { useState } from 'react';
import { toast } from 'sonner';
import { Field } from '@/components/admin/form-ui';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { shopApi } from '@/shared/api/shop.api';
import { useShopMutation } from '@/shared/hooks/useShop';
import { SHOP_LIMITS, type OnlineOrder } from '@/shared/types/shop';
import { InlineError } from './panels';
import { BTN_DANGER, BTN_OUTLINE, TEXTAREA } from './styles';

export function CancelOrderDialog({ order, open, onClose }: { order: OnlineOrder; open: boolean; onClose: () => void }) {
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto bg-white text-(--color-text-primary) sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Hủy đơn {order.code}</DialogTitle>
          <DialogDescription>Đơn đã hủy không khôi phục được; muốn mua tiếp thì đặt đơn mới.</DialogDescription>
        </DialogHeader>
        <CancelForm order={order} onClose={onClose} />
      </DialogContent>
    </Dialog>
  );
}

function CancelForm({ order, onClose }: { order: OnlineOrder; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const cancel = useShopMutation((text: string) => shopApi.cancelMyOrder(order.onlineOrderId, { reason: text }));
  // Chụp lúc mở hộp thoại: sau khi hủy, đơn tải lại thành CANCELLED nhưng thông báo vẫn phải theo trạng thái trước khi hủy.
  const [paid] = useState(order.status !== 'PENDING_PAYMENT');
  const tooLong = reason.length > SHOP_LIMITS.reason;

  const submit = () => {
    const text = reason.trim();
    if (!text) return setError('Vui lòng nhập lý do hủy đơn');
    if (tooLong) return setError(`Lý do tối đa ${SHOP_LIMITS.reason} ký tự`);
    setError('');
    cancel.mutate(text, {
      onSuccess: () => {
        toast.success(paid ? 'Đã hủy đơn, tiền sẽ được hoàn qua cổng thanh toán' : 'Đã hủy đơn hàng');
        onClose();
      },
      onError: (e) => setError(plainMessage(toApiError(e))),
    });
  };

  return (
    <div className="space-y-4">
      <p role="note" className="rounded-xl bg-(--color-surface-2) p-3 text-[13px] leading-relaxed text-(--color-text-secondary)">
        {paid ? 'Đơn đã thanh toán: hàng được hoàn về kho và toàn bộ số tiền (gồm phí vận chuyển) được hoàn qua cổng thanh toán.' : 'Đơn chưa thanh toán: hàng đang giữ cho bạn sẽ được nhả ra.'}
      </p>
      <Field label="Lý do hủy">{(id) => <textarea id={id} rows={3} value={reason} onChange={(e) => setReason(e.target.value)} className={TEXTAREA} />}</Field>
      <p className="-mt-2 text-right text-[11.5px] text-(--color-text-secondary)">
        {reason.length}/{SHOP_LIMITS.reason}
      </p>
      <InlineError message={error} />
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <button type="button" onClick={onClose} disabled={cancel.isPending} className={BTN_OUTLINE}>
          Giữ đơn hàng
        </button>
        <button type="button" onClick={submit} disabled={cancel.isPending} className={BTN_DANGER}>
          {cancel.isPending ? 'Đang hủy…' : 'Xác nhận hủy'}
        </button>
      </div>
    </div>
  );
}
