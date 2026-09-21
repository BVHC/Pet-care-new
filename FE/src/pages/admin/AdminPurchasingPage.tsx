import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Plus, Truck, Package, Clock, Check } from 'lucide-react';
import { cn } from '../../lib/utils';

type PurchaseStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'ORDERED' | 'RECEIVED' | 'CANCELLED';

interface PurchaseOrder {
  id: string;
  poNumber: string;
  date: string;
  supplier: string;
  supplierPhone: string;
  items: { name: string; quantity: number; unitPrice: number }[];
  totalAmount: number;
  status: PurchaseStatus;
  expectedDate: string;
  receivedDate?: string;
  createdBy: string;
}

const MOCK_PURCHASES: PurchaseOrder[] = [
  { id: '1', poNumber: 'PO-2026-001', date: '2026-09-18', supplier: 'Công ty TNHH Thức ăn Chó Mèo', supplierPhone: '0281234567', items: [{ name: 'Thức ăn Royal Canin', quantity: 100, unitPrice: 350000 }], totalAmount: 35000000, status: 'ORDERED', expectedDate: '2026-09-20', createdBy: 'Store Manager' },
  { id: '2', poNumber: 'PO-2026-002', date: '2026-09-17', supplier: 'Dược phẩm Thú Y ABC', supplierPhone: '0282345678', items: [{ name: 'Vaccine dại', quantity: 50, unitPrice: 150000 }], totalAmount: 7500000, status: 'RECEIVED', expectedDate: '2026-09-19', receivedDate: '2026-09-19', createdBy: 'Inventory Staff' },
  { id: '3', poNumber: 'PO-2026-003', date: '2026-09-16', supplier: 'Vật tư Thú Y XYZ', supplierPhone: '0283456789', items: [{ name: 'Bơm tiêm 5ml', quantity: 200, unitPrice: 15000 }], totalAmount: 3000000, status: 'PENDING', expectedDate: '2026-09-22', createdBy: 'Inventory Staff' },
];

function StatusBadge({ status }: { status: PurchaseStatus }) {
  const config: Record<PurchaseStatus, { label: string; className: string }> = {
    DRAFT: { label: 'Bản nháp', className: 'bg-gray-100 text-gray-700' },
    PENDING: { label: 'Chờ duyệt', className: 'bg-amber-100 text-amber-700' },
    APPROVED: { label: 'Đã duyệt', className: 'bg-blue-100 text-blue-700' },
    ORDERED: { label: 'Đã đặt hàng', className: 'bg-purple-100 text-purple-700' },
    RECEIVED: { label: 'Đã nhận hàng', className: 'bg-green-100 text-green-700' },
    CANCELLED: { label: 'Đã hủy', className: 'bg-red-100 text-red-700' },
  };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminPurchasingPage() {
  const [purchases] = useState(MOCK_PURCHASES);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredPurchases = purchases.filter(p =>
    p.poNumber.toLowerCase().includes(searchQuery.toLowerCase()) ||
    p.supplier.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const pendingCount = purchases.filter(p => p.status === 'PENDING').length;
  const orderedCount = purchases.filter(p => p.status === 'ORDERED').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Mua hàng NCC</h1>
        <p className="text-[var(--text-secondary)]">Quản lý đơn mua hàng từ nhà cung cấp</p>
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
              <p className="text-sm text-[var(--text-secondary)]">Chờ duyệt</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-purple-100">
              <Truck className="h-6 w-6 text-purple-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-purple-600">{orderedCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đang vận chuyển</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <Check className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{purchases.filter(p => p.status === 'RECEIVED').length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đã nhận hàng</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100">
              <Package className="h-6 w-6 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-blue-600">{purchases.length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Tổng đơn</p>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Search */}
      <Card className="card">
        <CardContent className="p-4 flex items-center justify-between">
          <div className="relative max-w-md">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-[var(--text-tertiary)]" />
            <Input
              placeholder="Tìm đơn mua hàng..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <Button><Plus className="mr-2 h-4 w-4" />Tạo đơn mua hàng</Button>
        </CardContent>
      </Card>

      {/* Purchase List */}
      <Card className="card">
        <CardContent className="p-0">
          <div className="divide-y divide-[var(--border-subtle)]">
            {filteredPurchases.map(purchase => (
              <div key={purchase.id} className="p-5 hover:bg-[var(--bg-secondary)] transition-colors">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-3 mb-2">
                      <h3 className="font-semibold text-[var(--text-primary)]">{purchase.poNumber}</h3>
                      <StatusBadge status={purchase.status} />
                      <span className="text-sm text-[var(--text-tertiary)]">{purchase.date}</span>
                    </div>

                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm mb-3">
                      <div>
                        <p className="text-[var(--text-tertiary)]">Nhà cung cấp</p>
                        <p className="font-medium text-[var(--text-primary)]">{purchase.supplier}</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Dự kiến nhận</p>
                        <p className="text-[var(--text-primary)]">{purchase.expectedDate}</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Người tạo</p>
                        <p className="text-[var(--text-primary)]">{purchase.createdBy}</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Số sản phẩm</p>
                        <p className="text-[var(--text-primary)]">{purchase.items.length} mặt hàng</p>
                      </div>
                    </div>

                    <div className="text-sm text-[var(--text-secondary)]">
                      {purchase.items.map((item, idx) => (
                        <span key={idx}>
                          {item.quantity}x {item.name}
                          {idx < purchase.items.length - 1 && ', '}
                        </span>
                      ))}
                    </div>
                  </div>

                  <div className="text-right ml-6">
                    <p className="text-xl font-semibold text-[var(--text-primary)]">{formatCurrency(purchase.totalAmount)}</p>

                    <div className="flex gap-2 mt-3 justify-end">
                      {purchase.status === 'DRAFT' && <Button size="sm" variant="outline">Chỉnh sửa</Button>}
                      {purchase.status === 'PENDING' && (
                        <>
                          <Button size="sm" variant="outline">Từ chối</Button>
                          <Button size="sm" className="bg-green-600 hover:bg-green-700">Duyệt</Button>
                        </>
                      )}
                      {purchase.status === 'ORDERED' && (
                        <Button size="sm" className="bg-green-600 hover:bg-green-700">Xác nhận đã nhận</Button>
                      )}
                      {purchase.status === 'RECEIVED' && (
                        <Badge className="bg-green-100 text-green-700">Đã hoàn tất</Badge>
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
