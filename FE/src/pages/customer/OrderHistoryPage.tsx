import { Package, Clock, CheckCircle, Truck, XCircle } from 'lucide-react';
import { Link } from 'react-router-dom';

interface Order {
  id: string;
  date: string;
  items: { name: string; qty: number }[];
  total: number;
  status: 'pending' | 'processing' | 'shipped' | 'delivered' | 'cancelled';
}

const MOCK_ORDERS: Order[] = [
  { id: 'ORD001', date: '25/08/2026', items: [{ name: 'Royal Canin Adult', qty: 1 }, { name: 'Interactive Ball', qty: 2 }], total: 628000, status: 'delivered' },
  { id: 'ORD002', date: '20/08/2026', items: [{ name: 'Whiskas Salmon', qty: 3 }], total: 360000, status: 'shipped' },
  { id: 'ORD003', date: '15/08/2026', items: [{ name: 'Catnip Toy Mouse', qty: 5 }], total: 225000, status: 'delivered' },
  { id: 'ORD004', date: '10/08/2026', items: [{ name: 'Orthopedic Bed', qty: 1 }], total: 890000, status: 'cancelled' },
];

const STATUS_CONFIG = {
  pending: { label: 'Chờ xác nhận', icon: Clock, color: 'bg-yellow-100 text-yellow-700' },
  processing: { label: 'Đang xử lý', icon: Package, color: 'bg-blue-100 text-blue-700' },
  shipped: { label: 'Đang giao', icon: Truck, color: 'bg-purple-100 text-purple-700' },
  delivered: { label: 'Đã giao', icon: CheckCircle, color: 'bg-green-100 text-green-700' },
  cancelled: { label: 'Đã hủy', icon: XCircle, color: 'bg-red-100 text-red-700' },
};

function formatVnd(price: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 }).format(price);
}

export function OrderHistoryPage() {
  return (
    <div className="mx-auto max-w-[1000px] px-4 py-8 sm:px-6">
      <div className="flex items-center justify-between mb-8">
        <h1 className="font-[var(--font-friendly)] text-3xl font-bold text-gray-900">
          Lịch sử đơn hàng
        </h1>
        <Link to="/shop" className="text-sm font-semibold text-[#843122] hover:underline">
          Tiếp tục mua sắm
        </Link>
      </div>

      {MOCK_ORDERS.length === 0 ? (
        <div className="text-center py-20">
          <Package size={48} className="mx-auto mb-4 text-gray-300" />
          <p className="text-gray-500">Chưa có đơn hàng nào</p>
          <Link to="/shop" className="mt-4 inline-block text-[#843122] font-semibold hover:underline">
            Bắt đầu mua sắm
          </Link>
        </div>
      ) : (
        <div className="space-y-4">
          {MOCK_ORDERS.map((order) => {
            const statusConfig = STATUS_CONFIG[order.status];
            const StatusIcon = statusConfig.icon;
            return (
              <div key={order.id} className="overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm hover:shadow-md transition-shadow">
                {/* Header */}
                <div className="flex flex-wrap items-center justify-between gap-4 border-b border-gray-100 bg-gray-50 px-6 py-4">
                  <div className="flex items-center gap-6">
                    <span className="text-sm font-semibold text-gray-500">Mã đơn: <span className="text-gray-900">{order.id}</span></span>
                    <span className="flex items-center gap-1.5 text-sm text-gray-500">
                      <Clock size={14} /> {order.date}
                    </span>
                  </div>
                  <span className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-semibold ${statusConfig.color}`}>
                    <StatusIcon size={12} /> {statusConfig.label}
                  </span>
                </div>
                {/* Items */}
                <div className="px-6 py-4">
                  <div className="space-y-2">
                    {order.items.map((item, i) => (
                      <div key={i} className="flex justify-between text-sm">
                        <span className="text-gray-600">{item.name} <span className="text-gray-400">×{item.qty}</span></span>
                      </div>
                    ))}
                  </div>
                </div>
                {/* Footer */}
                <div className="flex items-center justify-between border-t border-gray-100 bg-gray-50 px-6 py-4">
                  <span className="font-bold text-gray-900">Tổng: {formatVnd(order.total)}</span>
                  <Link
                    to={`/order/${order.id}`}
                    className="text-sm font-semibold text-[#843122] hover:underline"
                  >
                    Xem chi tiết
                  </Link>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
