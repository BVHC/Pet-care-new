import { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Search, Warehouse, AlertTriangle, TrendingUp, Plus } from 'lucide-react';
import { cn } from '../../lib/utils';

type StockStatus = 'IN_STOCK' | 'LOW_STOCK' | 'OUT_OF_STOCK' | 'EXPIRING';

interface InventoryItem {
  id: string; sku: string; name: string; category: string; quantity: number;
  minStock: number; unit: string; costPrice: number; sellPrice: number; status: StockStatus;
}

const MOCK_INVENTORY: InventoryItem[] = [
  { id: '1', sku: 'THUCAN-001', name: 'Thức ăn Royal Canin (mèo)', category: 'Thức ăn', quantity: 50, minStock: 20, unit: 'bao', costPrice: 350000, sellPrice: 450000, status: 'IN_STOCK' },
  { id: '2', sku: 'THUCAN-002', name: 'Thức ăn Pedigree (chó)', category: 'Thức ăn', quantity: 8, minStock: 15, unit: 'bao', costPrice: 280000, sellPrice: 380000, status: 'LOW_STOCK' },
  { id: '3', sku: 'CHAMSOC-001', name: 'Sữa tắm diệt ve rận', category: 'Chăm sóc', quantity: 0, minStock: 10, unit: 'chai', costPrice: 80000, sellPrice: 120000, status: 'OUT_OF_STOCK' },
];

function StatusBadge({ status }: { status: StockStatus }) {
  const config: Record<StockStatus, { label: string; className: string }> = {
    IN_STOCK: { label: 'Còn hàng', className: 'bg-green-100 text-green-700' },
    LOW_STOCK: { label: 'Sắp hết', className: 'bg-amber-100 text-amber-700' },
    OUT_OF_STOCK: { label: 'Hết hàng', className: 'bg-red-100 text-red-700' },
    EXPIRING: { label: 'Sắp hết hạn', className: 'bg-orange-100 text-orange-700' },
  };
  const { label, className } = config[status];
  return <Badge className={className}>{label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminWarehousePage() {
  const [inventory] = useState(MOCK_INVENTORY);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredInventory = inventory.filter(item =>
    item.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    item.sku.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const lowStockCount = inventory.filter(i => i.status === 'LOW_STOCK' || i.status === 'OUT_OF_STOCK').length;
  const totalValue = inventory.reduce((sum, i) => sum + (i.quantity * i.costPrice), 0);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Kho hàng</h1>
        <p className="text-[var(--text-secondary)]">Quản lý tồn kho và theo dõi sản phẩm</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100">
              <Warehouse className="h-6 w-6 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-[var(--text-primary)]">{inventory.length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Tổng sản phẩm</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi border-amber-200">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-amber-100">
              <AlertTriangle className="h-6 w-6 text-amber-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-amber-600">{lowStockCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">Sắp hết / Hết hàng</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-orange-100">
              <AlertTriangle className="h-6 w-6 text-orange-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-orange-600">0</p>
              <p className="text-sm text-[var(--text-secondary)]">Sắp hết hạn</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <TrendingUp className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{formatCurrency(totalValue)}</p>
              <p className="text-sm text-[var(--text-secondary)]">Giá trị tồn kho</p>
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
              placeholder="Tìm sản phẩm..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <Button><Plus className="mr-2 h-4 w-4" />Nhập kho</Button>
        </CardContent>
      </Card>

      {/* Table */}
      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>SKU</th>
                <th>Sản phẩm</th>
                <th>Tồn kho</th>
                <th>Giá nhập</th>
                <th>Giá bán</th>
                <th>Trạng thái</th>
              </tr>
            </thead>
            <tbody>
              {filteredInventory.map(item => (
                <tr key={item.id} className={cn(
                  item.status === 'LOW_STOCK' && 'bg-amber-50/50',
                  item.status === 'OUT_OF_STOCK' && 'bg-red-50/50'
                )}>
                  <td className="font-mono text-sm text-[var(--text-secondary)]">{item.sku}</td>
                  <td>
                    <p className="font-medium text-[var(--text-primary)]">{item.name}</p>
                    <p className="text-xs text-[var(--text-tertiary)]">{item.category}</p>
                  </td>
                  <td>
                    <p className="font-medium text-[var(--text-primary)]">{item.quantity} {item.unit}</p>
                    <p className="text-xs text-[var(--text-tertiary)]">Tối thiểu: {item.minStock}</p>
                  </td>
                  <td className="text-[var(--text-secondary)]">{formatCurrency(item.costPrice)}</td>
                  <td className="font-medium text-[var(--color-primary)]">{formatCurrency(item.sellPrice)}</td>
                  <td><StatusBadge status={item.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  );
}
