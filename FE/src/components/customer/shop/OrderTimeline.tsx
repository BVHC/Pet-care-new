import type { OnlineOrder } from '@/shared/types/shop';
import { formatDateTime } from './styles';

type Stamp = 'createdAt' | 'paidAt' | 'shippedAt' | 'deliveredAt' | 'completedAt' | 'cancelledAt';

const STEPS: { key: Stamp; label: string }[] = [
  { key: 'createdAt', label: 'Đặt hàng' },
  { key: 'paidAt', label: 'Đã thanh toán' },
  { key: 'shippedAt', label: 'Bàn giao cho đơn vị vận chuyển' },
  { key: 'deliveredAt', label: 'Đã giao hàng' },
  { key: 'completedAt', label: 'Hoàn tất' },
  { key: 'cancelledAt', label: 'Đã hủy' },
];

/** Các mốc thời gian đơn đã qua, đọc thẳng từ các trường thời điểm của hợp đồng (không suy diễn mốc không có dữ liệu). */
export function OrderTimeline({ order }: { order: OnlineOrder }) {
  const done = STEPS.filter((s) => order[s.key]).sort((a, b) => Date.parse(order[a.key]!) - Date.parse(order[b.key]!));
  return (
    <ol aria-label="Tiến trình đơn hàng" className="space-y-3 border-l-2 border-(--color-border-default) pl-4">
      {done.map((s) => (
        <li key={s.key} className="relative text-[13.5px]">
          <span aria-hidden className="absolute top-1.5 -left-[22px] h-2.5 w-2.5 rounded-full bg-accent" />
          <p className="font-bold">{s.label}</p>
          <p className="text-[12.5px] text-(--color-text-secondary)">{formatDateTime(order[s.key])}</p>
          {s.key === 'cancelledAt' && order.cancelReason && <p className="mt-0.5 text-[12.5px] text-(--color-text-secondary)">Lý do: {order.cancelReason}</p>}
        </li>
      ))}
    </ol>
  );
}
