import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../../components/ui/dialog';
import { Search, Package, Clock, CheckCircle2, Truck, Eye, ShoppingBag } from 'lucide-react';
import { cn } from '../../lib/utils';

type OrderStatus = 'PENDING_PAYMENT' | 'PAID' | 'PROCESSING' | 'READY' | 'DELIVERED' | 'CANCELLED';

interface OrderItem {
  id: string;
  productName: string;
  sku: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
}

interface OnlineOrder {
  id: string;
  orderNumber: string;
  placedAt: string;
  customerName: string;
  customerPhone: string;
  customerAddress: string;
  status: OrderStatus;
  paymentMethod: string;
  subtotal: number;
  discount: number;
  total: number;
  items: OrderItem[];
}

const MOCK_ORDERS: OnlineOrder[] = [
  {
    id: 'ord-1',
    orderNumber: 'ORD-2026-089',
    placedAt: '2026-09-21 09:30',
    customerName: 'Nguyễn Thu Hà',
    customerPhone: '0988123456',
    customerAddress: '124 Hoàng Hoa Thám, Ba Đình, Hà Nội',
    status: 'PROCESSING',
    paymentMethod: 'VNPAY',
    subtotal: 780000,
    discount: 50000,
    total: 730000,
    items: [
      { id: 'it-1', productName: 'Hạt Royal Canin Medium Adult 4kg', sku: 'RC-MED-4K', quantity: 1, unitPrice: 620000, lineTotal: 620000 },
      { id: 'it-2', productName: 'Snack thưởng Dental Chew 150g', sku: 'SNK-DENT-15', quantity: 2, unitPrice: 80000, lineTotal: 160000 },
    ],
  },
  {
    id: 'ord-2',
    orderNumber: 'ORD-2026-090',
    placedAt: '2026-09-21 10:05',
    customerName: 'Trần Đức Minh',
    customerPhone: '0912987654',
    customerAddress: '45 Lê Duẩn, Hoàn Kiếm, Hà Nội',
    status: 'PAID',
    paymentMethod: 'MOMO',
    subtotal: 450000,
    discount: 0,
    total: 450000,
    items: [
      { id: 'it-3', productName: 'Cát vệ sinh Ciao Nhật Bản 10L', sku: 'CAT-CIAO-10L', quantity: 2, unitPrice: 180000, lineTotal: 360000 },
      { id: 'it-4', productName: 'Cần câu mèo gắn lông vũ', sku: 'TOY-FEATH-01', quantity: 1, unitPrice: 90000, lineTotal: 90000 },
    ],
  },
  {
    id: 'ord-3',
    orderNumber: 'ORD-2026-091',
    placedAt: '2026-09-21 10:40',
    customerName: 'Lê Hoàng Nam',
    customerPhone: '0903456789',
    customerAddress: 'Tòa Landmark 81, P.22, Bình Thạnh, TP.HCM',
    status: 'READY',
    paymentMethod: 'COD',
    subtotal: 1250000,
    discount: 100000,
    total: 1150000,
    items: [
      { id: 'it-5', productName: 'Sữa tắm Davis De-Shedding 355ml', sku: 'SHMP-DAV-355', quantity: 1, unitPrice: 450000, lineTotal: 450000 },
      { id: 'it-6', productName: 'Áo giữ ấm mùa đông cho cún cỡ M', sku: 'CLOTH-WIN-M', quantity: 2, unitPrice: 400000, lineTotal: 800000 },
    ],
  },
  {
    id: 'ord-4',
    orderNumber: 'ORD-2026-085',
    placedAt: '2026-09-20 16:15',
    customerName: 'Phạm Mai Anh',
    customerPhone: '0977654321',
    customerAddress: 'Nhận tại cửa hàng (Chi nhánh Cầu Giấy)',
    status: 'DELIVERED',
    paymentMethod: 'VNPAY',
    subtotal: 320000,
    discount: 0,
    total: 320000,
    items: [
      { id: 'it-7', productName: 'Pate Monge Vị Vịt & Cam 100g', sku: 'PATE-MG-100', quantity: 8, unitPrice: 40000, lineTotal: 320000 },
    ],
  },
];

const PIPELINE_TABS = [
  { value: 'ALL', label: 'Tất cả' },
  { value: 'PAID', label: 'Đã thanh toán' },
  { value: 'PROCESSING', label: 'Đang xử lý' },
  { value: 'READY', label: 'Sẵn sàng giao' },
  { value: 'DELIVERED', label: 'Đã giao' },
];

function StatusBadge({ status }: { status: OrderStatus }) {
  const config: Record<OrderStatus, { label: string; className: string }> = {
    PENDING_PAYMENT: { label: 'Chờ thanh toán', className: 'bg-amber-100 text-amber-700' },
    PAID: { label: 'Đã thanh toán', className: 'bg-blue-100 text-blue-700' },
    PROCESSING: { label: 'Đang xử lý', className: 'bg-purple-100 text-purple-700' },
    READY: { label: 'Sẵn sàng', className: 'bg-cyan-100 text-cyan-700' },
    DELIVERED: { label: 'Đã giao', className: 'bg-green-100 text-green-700' },
    CANCELLED: { label: 'Đã hủy', className: 'bg-red-100 text-red-700' },
  };
  const { label, className } = config[status] || { label: status, className: 'bg-gray-100 text-gray-700' };
  return <Badge className={className}>{label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminOrdersPage() {
  const [orders, setOrders] = useState<OnlineOrder[]>(MOCK_ORDERS);
  const [activeTab, setActiveTab] = useState('ALL');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedOrder, setSelectedOrder] = useState<OnlineOrder | null>(null);

  const filteredOrders = orders.filter(ord => {
    const matchTab = activeTab === 'ALL' || ord.status === activeTab;
    const matchSearch = ord.orderNumber.toLowerCase().includes(searchQuery.toLowerCase()) ||
      ord.customerName.toLowerCase().includes(searchQuery.toLowerCase()) ||
      ord.customerPhone.includes(searchQuery);
    return matchTab && matchSearch;
  });

  const updateOrderStatus = (orderId: string, nextStatus: OrderStatus) => {
    setOrders(prev => prev.map(o => o.id === orderId ? { ...o, status: nextStatus } : o));
    if (selectedOrder && selectedOrder.id === orderId) {
      setSelectedOrder(prev => prev ? { ...prev, status: nextStatus } : null);
    }
  };

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Đơn hàng online</h1>
          <p className="text-[var(--text-secondary)]">Quản lý và tiếp nhận xử lý đơn đặt từ ứng dụng khách hàng</p>
        </div>
        <div className="flex items-center gap-2">
          <Badge className="bg-blue-50 text-blue-700 border-blue-200">
            {orders.filter(o => o.status === 'PROCESSING' || o.status === 'PAID').length} đơn cần xử lý
          </Badge>
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-blue-100 flex items-center justify-center text-blue-600">
                <ShoppingBag className="h-5 w-5" />
              </div>
              <div>
                <p className="text-xs text-[var(--text-secondary)]">Tổng đơn</p>
                <p className="text-xl font-bold text-[var(--text-primary)]">{orders.length}</p>
              </div>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-purple-100 flex items-center justify-center text-purple-600">
                <Clock className="h-5 w-5" />
              </div>
              <div>
                <p className="text-xs text-[var(--text-secondary)]">Đang xử lý</p>
                <p className="text-xl font-bold text-purple-600">
                  {orders.filter(o => o.status === 'PROCESSING' || o.status === 'PAID').length}
                </p>
              </div>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-cyan-100 flex items-center justify-center text-cyan-600">
                <Truck className="h-5 w-5" />
              </div>
              <div>
                <p className="text-xs text-[var(--text-secondary)]">Sẵn sàng giao</p>
                <p className="text-xl font-bold text-cyan-600">
                  {orders.filter(o => o.status === 'READY').length}
                </p>
              </div>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-green-100 flex items-center justify-center text-green-600">
                <CheckCircle2 className="h-5 w-5" />
              </div>
              <div>
                <p className="text-xs text-[var(--text-secondary)]">Hoàn tất</p>
                <p className="text-xl font-bold text-green-600">
                  {orders.filter(o => o.status === 'DELIVERED').length}
                </p>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Filter Tabs & Search */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div className="flex flex-wrap gap-2">
          {PIPELINE_TABS.map(t => (
            <button
              key={t.value}
              onClick={() => setActiveTab(t.value)}
              className={cn(
                'px-4 py-2 rounded-xl text-sm font-medium transition-all',
                activeTab === t.value
                  ? 'bg-blue-600 text-white shadow-sm'
                  : 'bg-[var(--bg-secondary)] text-[var(--text-secondary)] hover:bg-[var(--bg-tertiary)]'
              )}
            >
              {t.label}
            </button>
          ))}
        </div>
        <div className="relative w-full sm:w-72">
          <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 h-4 w-4 text-[var(--text-tertiary)]" />
          <Input
            placeholder="Tìm mã đơn, tên, SĐT..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9 h-10 bg-[var(--bg-secondary)] border-[var(--border-color)]"
          />
        </div>
      </div>

      {/* Table */}
      <Card className="card-base overflow-hidden">
        <CardContent className="p-0">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="border-b border-[var(--border-color)] bg-[var(--bg-secondary)]">
                <tr>
                  <th className="py-3 px-4 text-left font-medium text-[var(--text-secondary)]">Mã đơn</th>
                  <th className="py-3 px-4 text-left font-medium text-[var(--text-secondary)]">Thời gian</th>
                  <th className="py-3 px-4 text-left font-medium text-[var(--text-secondary)]">Khách hàng</th>
                  <th className="py-3 px-4 text-left font-medium text-[var(--text-secondary)]">Sản phẩm</th>
                  <th className="py-3 px-4 text-left font-medium text-[var(--text-secondary)]">Phương thức</th>
                  <th className="py-3 px-4 text-right font-medium text-[var(--text-secondary)]">Tổng tiền</th>
                  <th className="py-3 px-4 text-center font-medium text-[var(--text-secondary)]">Trạng thái</th>
                  <th className="py-3 px-4 text-center font-medium text-[var(--text-secondary)]">Thao tác</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[var(--border-color)]">
                {filteredOrders.length === 0 ? (
                  <tr>
                    <td colSpan={8} className="py-8 text-center text-[var(--text-secondary)]">
                      Không có đơn hàng nào phù hợp
                    </td>
                  </tr>
                ) : (
                  filteredOrders.map(order => (
                    <tr key={order.id} className="hover:bg-[var(--bg-secondary)] transition-colors">
                      <td className="py-3 px-4 font-semibold text-blue-600">{order.orderNumber}</td>
                      <td className="py-3 px-4 text-[var(--text-secondary)]">{order.placedAt}</td>
                      <td className="py-3 px-4">
                        <p className="font-medium text-[var(--text-primary)]">{order.customerName}</p>
                        <p className="text-xs text-[var(--text-secondary)]">{order.customerPhone}</p>
                      </td>
                      <td className="py-3 px-4 text-[var(--text-secondary)]">
                        {order.items.length} mặt hàng
                      </td>
                      <td className="py-3 px-4">
                        <Badge variant="outline">{order.paymentMethod}</Badge>
                      </td>
                      <td className="py-3 px-4 text-right font-semibold text-[var(--text-primary)]">
                        {formatCurrency(order.total)}
                      </td>
                      <td className="py-3 px-4 text-center">
                        <StatusBadge status={order.status} />
                      </td>
                      <td className="py-3 px-4 text-center">
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => setSelectedOrder(order)}
                          className="h-8 w-8 p-0"
                        >
                          <Eye className="h-4 w-4" />
                        </Button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>

      {/* Order Detail Modal */}
      <Dialog open={!!selectedOrder} onOpenChange={(open) => !open && setSelectedOrder(null)}>
        <DialogContent className="max-w-2xl">
          {selectedOrder && (
            <div className="space-y-6">
              <DialogHeader>
                <div className="flex items-center justify-between">
                  <DialogTitle className="text-xl font-bold flex items-center gap-2">
                    <Package className="h-5 w-5 text-blue-600" />
                    Chi tiết đơn hàng {selectedOrder.orderNumber}
                  </DialogTitle>
                  <StatusBadge status={selectedOrder.status} />
                </div>
              </DialogHeader>

              {/* Customer Info */}
              <div className="grid grid-cols-2 gap-4 p-4 rounded-xl bg-[var(--bg-secondary)] text-sm">
                <div>
                  <span className="text-[var(--text-secondary)] block">Khách hàng:</span>
                  <span className="font-semibold text-[var(--text-primary)]">{selectedOrder.customerName}</span>
                  <span className="text-[var(--text-secondary)] block">{selectedOrder.customerPhone}</span>
                </div>
                <div>
                  <span className="text-[var(--text-secondary)] block">Địa chỉ giao:</span>
                  <span className="font-medium text-[var(--text-primary)]">{selectedOrder.customerAddress}</span>
                </div>
              </div>

              {/* Items List */}
              <div className="space-y-2">
                <h3 className="font-semibold text-sm text-[var(--text-secondary)] uppercase tracking-wide">
                  Sản phẩm ({selectedOrder.items.length})
                </h3>
                <div className="rounded-xl border border-[var(--border-color)] overflow-hidden">
                  <table className="w-full text-sm">
                    <thead className="bg-[var(--bg-secondary)] border-b border-[var(--border-color)]">
                      <tr>
                        <th className="py-2.5 px-3 text-left font-medium text-[var(--text-secondary)]">Sản phẩm</th>
                        <th className="py-2.5 px-3 text-center font-medium text-[var(--text-secondary)]">SL</th>
                        <th className="py-2.5 px-3 text-right font-medium text-[var(--text-secondary)]">Đơn giá</th>
                        <th className="py-2.5 px-3 text-right font-medium text-[var(--text-secondary)]">Thành tiền</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-[var(--border-color)]">
                      {selectedOrder.items.map(item => (
                        <tr key={item.id}>
                          <td className="py-2.5 px-3">
                            <p className="font-medium text-[var(--text-primary)]">{item.productName}</p>
                            <p className="text-xs text-[var(--text-tertiary)]">{item.sku}</p>
                          </td>
                          <td className="py-2.5 px-3 text-center">{item.quantity}</td>
                          <td className="py-2.5 px-3 text-right">{formatCurrency(item.unitPrice)}</td>
                          <td className="py-2.5 px-3 text-right font-medium">{formatCurrency(item.lineTotal)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>

              {/* Totals */}
              <div className="p-4 rounded-xl bg-[var(--bg-secondary)] space-y-1.5 text-sm">
                <div className="flex justify-between text-[var(--text-secondary)]">
                  <span>Tạm tính</span>
                  <span>{formatCurrency(selectedOrder.subtotal)}</span>
                </div>
                {selectedOrder.discount > 0 && (
                  <div className="flex justify-between text-green-600">
                    <span>Giảm giá</span>
                    <span>-{formatCurrency(selectedOrder.discount)}</span>
                  </div>
                )}
                <div className="flex justify-between font-bold text-base pt-2 border-t border-[var(--border-color)] text-[var(--text-primary)]">
                  <span>Tổng thanh toán</span>
                  <span className="text-blue-600">{formatCurrency(selectedOrder.total)}</span>
                </div>
              </div>

              {/* Action Buttons */}
              <div className="flex items-center justify-end gap-3 pt-2">
                {selectedOrder.status === 'PAID' && (
                  <Button
                    onClick={() => updateOrderStatus(selectedOrder.id, 'PROCESSING')}
                    className="bg-purple-600 hover:bg-purple-700 text-white"
                  >
                    Xác nhận đóng gói
                  </Button>
                )}
                {selectedOrder.status === 'PROCESSING' && (
                  <Button
                    onClick={() => updateOrderStatus(selectedOrder.id, 'READY')}
                    className="bg-cyan-600 hover:bg-cyan-700 text-white"
                  >
                    Sẵn sàng bàn giao / ship
                  </Button>
                )}
                {selectedOrder.status === 'READY' && (
                  <Button
                    onClick={() => updateOrderStatus(selectedOrder.id, 'DELIVERED')}
                    className="bg-green-600 hover:bg-green-700 text-white"
                  >
                    Xác nhận đã giao hàng
                  </Button>
                )}
                <Button variant="outline" onClick={() => setSelectedOrder(null)}>
                  Đóng
                </Button>
              </div>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
