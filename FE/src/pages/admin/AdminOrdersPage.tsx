import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Package, Eye, Truck, Check, Clock } from 'lucide-react';
import { cn } from '../../lib/utils';

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
}

const MOCK_ORDERS: Order[] = [
  {
    id: '1',
    orderNumber: 'ORD-2026-001',
    date: '2026-09-18 14:30',
    customerName: 'Nguyễn Văn A',
    customerPhone: '0901234567',
    customerAddress: '123 Nguyễn Trãi, Q.1, TP.HCM',
    items: [
      { name: 'Thức ăn Royal Canin (mèo)', quantity: 2, price: 450000 },
      { name: 'Sữa tắm diệt ve rận', quantity: 1, price: 120000 }
    ],
    subtotal: 1020000,
    shipping: 30000,
    total: 1050000,
    status: 'PREPARING',
    paymentMethod: 'VNPAY'
  },
  {
    id: '2',
    orderNumber: 'ORD-2026-002',
    date: '2026-09-18 12:00',
    customerName: 'Trần Thị B',
    customerPhone: '0912345678',
    customerAddress: '456 Lê Văn Việt, Q.9, TP.HCM',
    items: [
      { name: 'Thức ăn Pedigree (chó)', quantity: 1, price: 380000 },
      { name: 'Bàn chải lông', quantity: 2, price: 65000 }
    ],
    subtotal: 510000,
    shipping: 25000,
    total: 535000,
    status: 'SHIPPING',
    paymentMethod: 'COD'
  },
  {
    id: '3',
    orderNumber: 'ORD-2026-003',
    date: '2026-09-18 10:00',
    customerName: 'Lê Văn C',
    customerPhone: '0923456789',
    customerAddress: '789 Điện Biên Phủ, Q.Bình Thạnh, TP.HCM',
    items: [
      { name: 'Vitamin tổng hợp', quantity: 3, price: 280000 }
    ],
    subtotal: 840000,
    shipping: 35000,
    total: 875000,
    status: 'DELIVERED',
    paymentMethod: 'TRANSFER'
  },
];

function StatusBadge({ status }: { status: OrderStatus }) {
  const config: Record<OrderStatus, { label: string; className: string }> = {
    PENDING: { label: 'Chờ xác nhận', className: 'bg-gray-100 text-gray-700' },
    CONFIRMED: { label: 'Đã xác nhận', className: 'bg-blue-100 text-blue-700' },
    PREPARING: { label: 'Đang chuẩn bị', className: 'bg-amber-100 text-amber-700' },
    SHIPPING: { label: 'Đang giao', className: 'bg-purple-100 text-purple-700' },
    DELIVERED: { label: 'Đã giao', className: 'bg-green-100 text-green-700' },
    CANCELLED: { label: 'Đã hủy', className: 'bg-red-100 text-red-700' },
  };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminOrdersPage() {
  const [orders] = useState(MOCK_ORDERS);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredOrders = orders.filter(o =>
    o.orderNumber.toLowerCase().includes(searchQuery.toLowerCase()) ||
    o.customerName.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const pendingCount = orders.filter(o => o.status === 'PENDING' || o.status === 'CONFIRMED').length;
  const shippingCount = orders.filter(o => o.status === 'SHIPPING').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Đơn hàng Online</h1>
        <p className="text-[var(--text-secondary)]">Quản lý đơn hàng từ website và ứng dụng</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-amber-100">
              <Clock className="h-6 w-6 text-amber-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-amber-600">{pendingCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">Chờ xử lý</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-purple-100">
              <Truck className="h-6 w-6 text-purple-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-purple-600">{shippingCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đang giao</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <Check className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{orders.filter(o => o.status === 'DELIVERED').length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đã giao</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100">
              <Package className="h-6 w-6 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-blue-600">{orders.length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Tổng đơn</p>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Search */}
      <Card className="card">
        <CardContent className="p-4">
          <div className="relative max-w-md">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-[var(--text-tertiary)]" />
            <Input
              placeholder="Tìm đơn hàng..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
        </CardContent>
      </Card>

      {/* Orders List */}
      <Card className="card">
        <CardContent className="p-0">
          <div className="divide-y divide-[var(--border-subtle)]">
            {filteredOrders.map(order => (
              <div key={order.id} className="p-5 hover:bg-[var(--bg-secondary)] transition-colors">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-3 mb-2">
                      <h3 className="font-semibold text-[var(--text-primary)]">{order.orderNumber}</h3>
                      <StatusBadge status={order.status} />
                      <Badge variant="secondary" className="bg-[var(--bg-tertiary)]">
                        {order.paymentMethod}
                      </Badge>
                    </div>

                    <div className="grid grid-cols-2 md:grid-cols-3 gap-4 text-sm mb-3">
                      <div>
                        <p className="text-[var(--text-tertiary)]">Khách hàng</p>
                        <p className="font-medium text-[var(--text-primary)]">{order.customerName}</p>
                        <p className="text-[var(--text-secondary)]">{order.customerPhone}</p>
                      </div>
                      <div className="md:col-span-2">
                        <p className="text-[var(--text-tertiary)]">Địa chỉ</p>
                        <p className="text-[var(--text-primary)]">{order.customerAddress}</p>
                      </div>
                    </div>

                    <div className="text-sm text-[var(--text-secondary)]">
                      {order.items.map((item, idx) => (
                        <span key={idx}>
                          {item.quantity}x {item.name}
                          {idx < order.items.length - 1 && ', '}
                        </span>
                      ))}
                    </div>
                  </div>

                  <div className="text-right ml-6">
                    <p className="text-xl font-semibold text-[var(--text-primary)]">{formatCurrency(order.total)}</p>
                    <p className="text-sm text-[var(--text-tertiary)]">{order.date}</p>

                    <div className="flex gap-2 mt-3 justify-end">
                      <Button size="sm" variant="outline">
                        <Eye className="mr-1 h-4 w-4" /> Chi tiết
                      </Button>
                      {order.status === 'PENDING' && (
                        <Button size="sm" className="bg-blue-600 hover:bg-blue-700">
                          Xác nhận
                        </Button>
                      )}
                      {order.status === 'CONFIRMED' && (
                        <Button size="sm" className="bg-amber-600 hover:bg-amber-700">
                          Chuẩn bị
                        </Button>
                      )}
                      {order.status === 'PREPARING' && (
                        <Button size="sm" className="bg-purple-600 hover:bg-purple-700">
                          Giao hàng
                        </Button>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
