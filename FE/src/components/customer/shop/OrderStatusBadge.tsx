import { CircleCheck, CircleX, Clock, PackageCheck, PackageOpen, Truck, TriangleAlert, Wallet, type LucideIcon } from 'lucide-react';
import { ONLINE_ORDER_STATUS_LABELS, type OnlineOrderStatus } from '@/shared/types/shop';

const LOOK: Record<OnlineOrderStatus, { icon: LucideIcon; cls: string }> = {
  PENDING_PAYMENT: { icon: Clock, cls: 'bg-amber-100 text-amber-900' },
  PAID: { icon: Wallet, cls: 'bg-blue-100 text-blue-800' },
  PROCESSING: { icon: PackageOpen, cls: 'bg-blue-100 text-blue-800' },
  SHIPPED: { icon: Truck, cls: 'bg-indigo-100 text-indigo-800' },
  DELIVERY_FAILED: { icon: TriangleAlert, cls: 'bg-red-100 text-red-800' },
  DELIVERED: { icon: PackageCheck, cls: 'bg-emerald-100 text-emerald-800' },
  COMPLETED: { icon: CircleCheck, cls: 'bg-emerald-100 text-emerald-800' },
  CANCELLED: { icon: CircleX, cls: 'bg-gray-100 text-gray-700' },
};

/** Nhãn trạng thái đơn online: luôn kèm chữ và biểu tượng, không chỉ dựa vào màu. */
export function OrderStatusBadge({ status }: { status: OnlineOrderStatus }) {
  const { icon: Icon, cls } = LOOK[status];
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-[12px] font-bold ${cls}`}>
      <Icon size={13} aria-hidden />
      {ONLINE_ORDER_STATUS_LABELS[status]}
    </span>
  );
}
