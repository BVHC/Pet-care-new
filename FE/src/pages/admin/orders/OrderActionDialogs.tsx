import { useState } from 'react';
import { Field, FormError } from '@/components/admin/form-ui';
import { Input } from '@/components/ui/input';
import { FormModal } from '@/components/ui/modal-templates';
import { commerceError } from './commerce-error';
import { shopApi } from '@/shared/api/shop.api';
import { useShopMutation } from '@/shared/hooks/useShop';
import { SHOP_LIMITS, type OnlineOrder, type ShipRequest } from '@/shared/types/shop';

const textareaClass =
  'w-full rounded-lg border border-(--color-border-default) px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent';

interface DialogProps {
  order: OnlineOrder;
  onClose: () => void;
  /** Đã lưu: trang đóng hộp thoại và báo kết quả. */
  onDone: (message: string) => void;
}

/** #15 bàn giao (PROCESSING → SHIPPED) và #18 giao lại (DELIVERY_FAILED → SHIPPED): cùng ShipRequest. Lỗi của máy chủ hiện ngay trong hộp thoại. */
export function ShipOrderDialog({ order, redeliver, onClose, onDone }: DialogProps & { redeliver?: boolean }) {
  const [carrier, setCarrier] = useState(redeliver ? (order.carrier ?? '') : '');
  const [trackingCode, setTrackingCode] = useState('');
  const [error, setError] = useState('');
  const send = useShopMutation((body: ShipRequest) => (redeliver ? shopApi.redeliver : shopApi.ship)(order.onlineOrderId, body));

  const submit = async () => {
    if (!carrier.trim() || !trackingCode.trim()) return setError('Nhập đơn vị vận chuyển và mã vận đơn.');
    try {
      await send.mutateAsync({ carrier: carrier.trim(), trackingCode: trackingCode.trim() });
      onDone(`${redeliver ? 'Đã chuyển giao lại' : 'Đã bàn giao vận chuyển'} đơn ${order.code}.`);
    } catch (e) {
      setError(commerceError(e, 'Đơn'));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={redeliver ? 'Giao lại' : 'Bàn giao vận chuyển'}
      description={order.code}
      onSubmit={submit}
      submitText={redeliver ? 'Giao lại' : 'Bàn giao'}
      loading={send.isPending}
    >
      <Field label="Đơn vị vận chuyển">
        {(id) => <Input id={id} maxLength={SHOP_LIMITS.carrier} value={carrier} onChange={(e) => { setCarrier(e.target.value); setError(''); }} />}
      </Field>
      <Field label="Mã vận đơn">
        {(id) => <Input id={id} maxLength={SHOP_LIMITS.trackingCode} value={trackingCode} onChange={(e) => { setTrackingCode(e.target.value); setError(''); }} />}
      </Field>
      <FormError message={error} />
    </FormModal>
  );
}

/** #17 ghi nhận giao thất bại (SHIPPED → DELIVERY_FAILED). */
export function OrderReasonDialog({ order, onClose, onDone }: DialogProps) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const send = useShopMutation((body: { reason: string }) => shopApi.failDelivery(order.onlineOrderId, body));

  const submit = async () => {
    if (!reason.trim()) return setError('Nhập lý do giao thất bại.');
    try {
      await send.mutateAsync({ reason: reason.trim() });
      onDone(`Đã ghi nhận giao thất bại đơn ${order.code}.`);
    } catch (e) {
      setError(commerceError(e, 'Đơn'));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title="Ghi nhận giao thất bại"
      description={order.code}
      onSubmit={submit}
      submitText="Ghi nhận"
      cancelText="Quay lại"
      loading={send.isPending}
    >
      <Field label="Lý do giao thất bại" hint={`${reason.length}/${SHOP_LIMITS.reason}`}>
        {(id) => (
          <textarea
            id={id}
            rows={3}
            maxLength={SHOP_LIMITS.reason}
            className={textareaClass}
            value={reason}
            onChange={(e) => {
              setReason(e.target.value);
              setError('');
            }}
          />
        )}
      </Field>
      <FormError message={error} />
    </FormModal>
  );
}
