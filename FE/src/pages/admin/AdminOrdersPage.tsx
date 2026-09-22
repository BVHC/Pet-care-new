import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Package, Eye, Truck, Check, Clock, X } from 'lucide-react';
import { ConfirmModal, DetailModal, InfoRow } from '../../components/ui/modal-templates';

type OrderStatus = 'PENDING' | 'CONFIRMED' | 'PREPARING' | 'SHIPPING' | 'DELIVERED' | 'CANCELLED';

interface Order {
  id: string;
  orderNumber: string;
  date: string;
  customerName: string;
  customerPhone: string;
  customerAddress: string;
  items: { name: string; quantity: number; price: number }[];
  subtotal: number;
  shipping: number;
  total: number;
  status: OrderStatus;
  paymentMethod: 'COD' | 'VNPAY' | 'TRANSFER';
  notes?: string;
}

const MOCK_ORDERS: Order[] = [
  { id: '1', orderNumber: 'ORD-2026-001', date: '2026-09-18 14:30', customerName: 'Nguyễn Văn A', customerPhone: '0901234567', customerAddress: '123 Nguyễn Trãi, Q.1, TP.HCM', items: [{ name: 'Thức ăn Royal Canin (mèo)', quantity: 2, price: 450000 }, { name: 'Sữa tắm diệt ve rận', quantity: 1, price: 120000 }], subtotal: 1020000, shipping: 30000, total: 1050000, status: 'PREPARING', paymentMethod: 'VNPAY' },
  { id: '2', orderNumber: 'ORD-2026-002', date: '2026-09-18 12:00', customerName: 'Trần Thị B', customerPhone: '0912345678', customerAddress: '456 Lê Văn Việt, Q.9, TP.HCM', items: [{ name: 'Thức ăn Pedigree (chó)', quantity: 1, price: 380000 }, { name: 'Bàn chải lông', quantity: 2, price: 65000 }], subtotal: 510000, shipping: 25000, total: 535000, status: 'SHIPPING', paymentMethod: 'COD' },
  { id: '3', orderNumber: 'ORD-2026-003', date: '2026-09-18 10:00', customerName: 'Lê Văn C', customerPhone: '0923456789', customerAddress: '789 Điện Biên Phủ, Q.Bình Thạnh, TP.HCM', items: [{ name: 'Vitamin tổng hợp', quantity: 3, price: 280000 }], subtotal: 840000, shipping: 35000, total: 875000, status: 'DELIVERED', paymentMethod: 'TRANSFER' },
  { id: '4', orderNumber: 'ORD-2026-004', date: '2026-09-17 09:00', customerName: 'Phạm Thị D', customerPhone: '0934567890', customerAddress: '321 Lý Thường Kiệt, Q.10, TP.HCM', items: [{ name: 'Xương gặm cho chó', quantity: 5, price: 50000 }], subtotal: 250000, shipping: 20000, total: 270000, status: 'CANCELLED', paymentMethod: 'COD' },
];

function StatusBadgeLocal({ status }: { status: OrderStatus }) {
  const config: Record<OrderStatus, { label: string; className: string }> = { PENDING: { label: 'Chờ xác nhận', className: 'bg-gray-100 text-gray-700' }, CONFIRMED: { label: 'Đã xác nhận', className: 'bg-blue-100 text-blue-700' }, PREPARING: { label: 'Đang chuẩn bị', className: 'bg-amber-100 text-amber-700' }, SHIPPING: { label: 'Đang giao', className: 'bg-purple-100 text-purple-700' }, DELIVERED: { label: 'Đã giao', className: 'bg-green-100 text-green-700' }, CANCELLED: { label: 'Đã hủy', className: 'bg-red-100 text-red-700' } };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminOrdersPage() {
  const [orders, setOrders] = useState<Order[]>(MOCK_ORDERS);
  const [searchQuery, setSearchQuery] = useState('');

  // Modal states
  const [viewOrder, setViewOrder] = useState<Order | null>(null);
  const [cancelOrder, setCancelOrder] = useState<Order | null>(null);
  const [processOrder, setProcessOrder] = useState<{ order: Order; action: 'confirm' | 'prepare' | 'ship' } | null>(null);

  const filteredOrders = orders.filter(o => o.orderNumber.toLowerCase().includes(searchQuery.toLowerCase()) || o.customerName.toLowerCase().includes(searchQuery.toLowerCase()));
  const pendingCount = orders.filter(o => o.status === 'PENDING' || o.status === 'CONFIRMED').length;
  const shippingCount = orders.filter(o => o.status === 'SHIPPING').length;

  const handleProcessOrder = () => {
    if (!processOrder) return;
    const { order, action } = processOrder;
    const statusMap: Record<string, OrderStatus> = { confirm: 'CONFIRMED', prepare: 'PREPARING', ship: 'SHIPPING' };
    setOrders(prev => prev.map(o => o.id === order.id ? { ...o, status: statusMap[action] } : o));
    setProcessOrder(null);
  };

  const handleCancelOrder = () => {
    if (!cancelOrder) return;
    setOrders(prev => prev.map(o => o.id === cancelOrder.id ? { ...o, status: 'CANCELLED' as OrderStatus } : o));
    setCancelOrder(null);
  };

  const getNextStatus = (status: OrderStatus): { label: string; action: 'confirm' | 'prepare' | 'ship' } | null => {
    if (status === 'PENDING') return { label: 'Xác nhận', action: 'confirm' };
    if (status === 'CONFIRMED') return { label: 'Chuẩn bị', action: 'prepare' };
    if (status === 'PREPARING') return { label: 'Giao hàng', action: 'ship' };
    return null;
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Đơn hàng Online</h1>
        <p className="text-(--text-secondary)">Quản lý đơn hàng từ website và ứng dụng</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-amber-100"><Clock className="h-6 w-6 text-amber-600" /></div><div><p className="text-2xl font-semibold text-amber-600">{pendingCount}</p><p className="text-sm text-(--text-secondary)">Chờ xử lý</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-purple-100"><Truck className="h-6 w-6 text-purple-600" /></div><div><p className="text-2xl font-semibold text-purple-600">{shippingCount}</p><p className="text-sm text-(--text-secondary)">Đang giao</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-green-100"><Check className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-semibold text-green-600">{orders.filter(o => o.status === 'DELIVERED').length}</p><p className="text-sm text-(--text-secondary)">Đã giao</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-blue-100"><Package className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-semibold text-blue-600">{orders.length}</p><p className="text-sm text-(--text-secondary)">Tổng đơn</p></div></CardContent></Card>
      </div>

      {/* Search */}
      <Card className="card"><CardContent className="p-4"><div className="relative max-w-md"><Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" /><Input placeholder="Tìm đơn hàng..." className="pl-10 input" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} /></div></CardContent></Card>

      {/* Orders List */}
      <Card className="card">
        <CardContent className="p-0">
          <div className="divide-y divide-(--border-subtle)">
            {filteredOrders.map(order => (
              <div key={order.id} className="p-5 hover:bg-(--bg-secondary) transition-colors">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-3 mb-2">
                      <span className="font-semibold text-(--text-primary)">{order.orderNumber}</span>
                      <StatusBadgeLocal status={order.status} />
                      <Badge variant="secondary" className="bg-(--bg-tertiary)">{order.paymentMethod}</Badge>
                    </div>
                    <div className="grid grid-cols-2 md:grid-cols-3 gap-4 text-sm mb-3">
                      <div><p className="text-(--text-tertiary)">Khách hàng</p><p className="font-medium text-(--text-primary)">{order.customerName}</p><p className="text-(--text-secondary)">{order.customerPhone}</p></div>
                      <div className="md:col-span-2"><p className="text-(--text-tertiary)">Địa chỉ</p><p className="text-(--text-primary)">{order.customerAddress}</p></div>
                    </div>
                    <div className="text-sm text-(--text-secondary)">{order.items.map((item, idx) => <span key={idx}>{item.quantity}x {item.name}{idx < order.items.length - 1 && ', '}</span>)}</div>
                  </div>
                  <div className="text-right ml-6">
                    <p className="text-xl font-semibold text-(--text-primary)">{formatCurrency(order.total)}</p>
                    <p className="text-sm text-(--text-tertiary)">{order.date}</p>
                    <div className="flex gap-2 mt-3 justify-end">
                      <Button size="sm" variant="outline" onClick={() => setViewOrder(order)}><Eye className="mr-1 h-4 w-4" /> Chi tiết</Button>
                      {!['DELIVERED', 'CANCELLED'].includes(order.status) && (
                        <>
                          {getNextStatus(order.status) && (
                            <Button size="sm" className="bg-blue-600 hover:bg-blue-700" onClick={() => setProcessOrder({ order, action: getNextStatus(order.status)!.action })}>{getNextStatus(order.status)!.label}</Button>
                          )}
                          {order.status !== 'CANCELLED' && <Button size="sm" variant="outline" className="text-red-500 border-red-200 hover:bg-red-50" onClick={() => setCancelOrder(order)}><X className="mr-1 h-4 w-4" /> Hủy</Button>}
                        </>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* MODALS */}

      {/* 1. Detail Modal */}
      <DetailModal open={!!viewOrder} onOpenChange={(o) => !o && setViewOrder(null)} title="Chi tiết đơn hàng" size="lg">
        {viewOrder && (
          <div className="space-y-4">
            <div className="flex items-center justify-between pb-4 border-b border-(--color-border-light)">
              <div><h3 className="text-lg font-semibold">{viewOrder.orderNumber}</h3><p className="text-sm text-(--text-secondary)">{viewOrder.date}</p></div>
              <StatusBadgeLocal status={viewOrder.status} />
            </div>
            <div className="space-y-1"><InfoRow label="Khách hàng" value={viewOrder.customerName} /><InfoRow label="SĐT" value={viewOrder.customerPhone} /><InfoRow label="Địa chỉ" value={viewOrder.customerAddress} /><InfoRow label="Thanh toán" value={viewOrder.paymentMethod} /></div>
            <div className="border-t border-(--color-border-light) pt-4"><h4 className="font-medium mb-2">Sản phẩm</h4>{viewOrder.items.map((item, idx) => <div key={idx} className="flex justify-between py-2 border-b border-(--color-border-light) last:border-0"><span>{item.quantity}x {item.name}</span><span className="font-medium">{formatCurrency(item.price * item.quantity)}</span></div>)}</div>
            <div className="border-t border-(--color-border-light) pt-4 space-y-1"><InfoRow label="Tạm tính" value={formatCurrency(viewOrder.subtotal)} /><InfoRow label="Phí ship" value={formatCurrency(viewOrder.shipping)} /><InfoRow label="Tổng cộng" value={<span className="font-bold text-accent">{formatCurrency(viewOrder.total)}</span>} /></div>
          </div>
        )}
      </DetailModal>

      {/* 2. Process Confirmation */}
      <ConfirmModal open={!!processOrder} onOpenChange={(o) => !o && setProcessOrder(null)} type="info" title="Xác nhận cập nhật trạng thái" description={`Bạn có chắc muốn cập nhật đơn hàng "${processOrder?.order.orderNumber}" sang trạng thái "${processOrder?.action === 'confirm' ? 'Đã xác nhận' : processOrder?.action === 'prepare' ? 'Đang chuẩn bị' : 'Đang giao'}"?`} confirmText="Xác nhận" onConfirm={handleProcessOrder} />

      {/* 3. Cancel Confirmation */}
      <ConfirmModal open={!!cancelOrder} onOpenChange={(o) => !o && setCancelOrder(null)} type="danger" title="Hủy đơn hàng?" description={`Bạn có chắc muốn hủy đơn hàng "${cancelOrder?.orderNumber}" của "${cancelOrder?.customerName}"? Hành động này sẽ thông báo cho khách hàng qua email.`} confirmText="Hủy đơn hàng" onConfirm={handleCancelOrder} />
    </div>
  );
}
