import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Warehouse, AlertTriangle, TrendingUp, Plus, Eye, Edit, ArrowRightLeft } from 'lucide-react';
import { ConfirmModal, DetailModal, FormModal, InfoRow } from '../../components/ui/modal-templates';

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

function StatusBadgeLocal({ status }: { status: StockStatus }) {
  const config: Record<StockStatus, { label: string; className: string }> = { IN_STOCK: { label: 'Còn hàng', className: 'bg-green-100 text-green-700' }, LOW_STOCK: { label: 'Sắp hết', className: 'bg-amber-100 text-amber-700' }, OUT_OF_STOCK: { label: 'Hết hàng', className: 'bg-red-100 text-red-700' }, EXPIRING: { label: 'Sắp hết hạn', className: 'bg-orange-100 text-orange-700' } };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminWarehousePage() {
  const [inventory, setInventory] = useState<InventoryItem[]>(MOCK_INVENTORY);
  const [searchQuery, setSearchQuery] = useState('');
  const [viewItem, setViewItem] = useState<InventoryItem | null>(null);
  const [adjustItem, setAdjustItem] = useState<InventoryItem | null>(null);
  const [transferItem, setTransferItem] = useState<InventoryItem | null>(null);
  const [adjustQty, setAdjustQty] = useState(0);
  const [adjustReason, setAdjustReason] = useState('');

  const filteredInventory = inventory.filter(item => item.name.toLowerCase().includes(searchQuery.toLowerCase()) || item.sku.toLowerCase().includes(searchQuery.toLowerCase()));
  const lowStockCount = inventory.filter(i => i.status === 'LOW_STOCK' || i.status === 'OUT_OF_STOCK').length;
  const totalValue = inventory.reduce((sum, i) => sum + (i.quantity * i.costPrice), 0);

  const handleAdjustStock = () => {
    if (!adjustItem) return;
    setInventory(prev => prev.map(i => i.id === adjustItem.id ? { ...i, quantity: Math.max(0, adjustItem.quantity + adjustQty) } : i));
    setAdjustItem(null);
    setAdjustQty(0);
    setAdjustReason('');
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Kho hàng</h1>
        <p className="text-(--text-secondary)">Quản lý tồn kho và theo dõi sản phẩm</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-blue-100"><Warehouse className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-semibold">{inventory.length}</p><p className="text-sm text-(--text-secondary)">Tổng sản phẩm</p></div></CardContent></Card>
        <Card className="card-kpi border-amber-200"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-amber-100"><AlertTriangle className="h-6 w-6 text-amber-600" /></div><div><p className="text-2xl font-semibold text-amber-600">{lowStockCount}</p><p className="text-sm text-(--text-secondary)">Sắp hết / Hết hàng</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-orange-100"><AlertTriangle className="h-6 w-6 text-orange-600" /></div><div><p className="text-2xl font-semibold text-orange-600">0</p><p className="text-sm text-(--text-secondary)">Sắp hết hạn</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-green-100"><TrendingUp className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-semibold text-green-600">{formatCurrency(totalValue)}</p><p className="text-sm text-(--text-secondary)">Giá trị tồn kho</p></div></CardContent></Card>
      </div>

      <Card className="card"><CardContent className="p-4 flex items-center justify-between"><div className="relative max-w-md"><Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" /><Input placeholder="Tìm sản phẩm..." className="pl-10 input" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} /></div><Button><Plus className="mr-2 h-4 w-4" />Nhập kho</Button></CardContent></Card>

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead><tr><th>SKU</th><th>Sản phẩm</th><th>Tồn kho</th><th>Giá nhập</th><th>Giá bán</th><th>Trạng thái</th><th></th></tr></thead>
            <tbody>
              {filteredInventory.map(item => (
                <tr key={item.id} className={item.status === 'LOW_STOCK' ? 'bg-amber-50/50' : item.status === 'OUT_OF_STOCK' ? 'bg-red-50/50' : ''}>
                  <td className="font-mono text-sm text-(--text-secondary)">{item.sku}</td>
                  <td><p className="font-medium text-(--text-primary)">{item.name}</p><p className="text-xs text-(--text-tertiary)">{item.category}</p></td>
                  <td><p className="font-medium text-(--text-primary)">{item.quantity} {item.unit}</p><p className="text-xs text-(--text-tertiary)">Tối thiểu: {item.minStock}</p></td>
                  <td className="text-(--text-secondary)">{formatCurrency(item.costPrice)}</td>
                  <td className="font-medium text-(--text-primary)">{formatCurrency(item.sellPrice)}</td>
                  <td><StatusBadgeLocal status={item.status} /></td>
                  <td><div className="flex gap-1"><Button size="sm" variant="ghost" onClick={() => setViewItem(item)}><Eye className="h-4 w-4" /></Button><Button size="sm" variant="ghost" onClick={() => { setAdjustItem(item); setAdjustQty(0); }}><Edit className="h-4 w-4" /></Button><Button size="sm" variant="ghost" onClick={() => setTransferItem(item)}><ArrowRightLeft className="h-4 w-4" /></Button></div></td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {/* MODALS */}

      <DetailModal open={!!viewItem} onOpenChange={(o) => !o && setViewItem(null)} title="Chi tiết sản phẩm" size="md">
        {viewItem && (
          <div className="space-y-4">
            <div className="flex items-center justify-between pb-4 border-b border-(--color-border-light)"><div><h3 className="text-lg font-semibold">{viewItem.name}</h3><p className="text-sm text-(--text-secondary)">{viewItem.sku}</p></div><StatusBadgeLocal status={viewItem.status} /></div>
            <div className="space-y-1"><InfoRow label="Danh mục" value={viewItem.category} /><InfoRow label="Tồn kho" value={`${viewItem.quantity} ${viewItem.unit}`} /><InfoRow label="Tồn kho tối thiểu" value={`${viewItem.minStock} ${viewItem.unit}`} /><InfoRow label="Giá nhập" value={formatCurrency(viewItem.costPrice)} /><InfoRow label="Giá bán" value={formatCurrency(viewItem.sellPrice)} /><InfoRow label="Lợi nhuận/đơn vị" value={formatCurrency(viewItem.sellPrice - viewItem.costPrice)} /></div>
          </div>
        )}
      </DetailModal>

      <FormModal open={!!adjustItem} onOpenChange={(o) => !o && setAdjustItem(null)} title="Điều chỉnh tồn kho" description={`Điều chỉnh số lượng cho "${adjustItem?.name}"`} onSubmit={handleAdjustStock} submitText="Lưu thay đổi" size="sm">
        <div className="space-y-4">
          <div className="p-4 bg-(--color-surface-2) rounded-xl"><p className="text-sm text-(--text-secondary)">Tồn kho hiện tại</p><p className="text-2xl font-bold text-(--text-primary)">{adjustItem?.quantity} {adjustItem?.unit}</p></div>
          <div><label className="block text-sm font-medium mb-1.5">Số lượng thay đổi (âm: giảm, dương: tăng)</label><Input type="number" value={adjustQty} onChange={(e) => setAdjustQty(parseInt(e.target.value) || 0)} placeholder="VD: -5 hoặc +10" /></div>
          <div className="p-4 bg-(--color-accent-soft) rounded-xl"><p className="text-sm text-(--text-secondary)">Tồn kho sau điều chỉnh</p><p className="text-2xl font-bold text-accent">{Math.max(0, (adjustItem?.quantity || 0) + adjustQty)} {adjustItem?.unit}</p></div>
          <div><label className="block text-sm font-medium mb-1.5">Lý do điều chỉnh</label><Input value={adjustReason} onChange={(e) => setAdjustReason(e.target.value)} placeholder="VD: Kiểm kê hàng lỗi" /></div>
        </div>
      </FormModal>

      <ConfirmModal open={!!transferItem} onOpenChange={(o) => !o && setTransferItem(null)} type="info" title="Chuyển kho?" description={`Chuyển "${transferItem?.name}" giữa các chi nhánh?`} confirmText="Chuyển kho" onConfirm={() => setTransferItem(null)} />
    </div>
  );
}
