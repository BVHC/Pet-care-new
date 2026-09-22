import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Plus, Boxes, AlertTriangle, Eye } from 'lucide-react';
import { DetailModal, FormModal, InfoRow } from '../../components/ui/modal-templates';

type VaccineStatus = 'IN_STOCK' | 'LOW_STOCK' | 'EXPIRING' | 'EXPIRED' | 'OUT_OF_STOCK';

interface Vaccine {
  id: string; name: string; batch: string; manufacturer: string; quantity: number;
  unit: string; costPrice: number; minStock: number; importDate: string;
  expiryDate: string; status: VaccineStatus; storageTemp: string;
}

const MOCK_VACCINES: Vaccine[] = [
  { id: '1', name: 'Vaccine dại (Rabies)', batch: 'RB-2024-089', manufacturer: 'Nobivac', quantity: 50, unit: 'liều', costPrice: 150000, minStock: 20, importDate: '2024-06-15', expiryDate: '2026-06-15', status: 'IN_STOCK', storageTemp: '2-8°C' },
  { id: '2', name: 'Vaccine 5 bệnh (DHPP)', batch: 'DHPP-2024-156', manufacturer: 'Nobivac', quantity: 8, unit: 'liều', costPrice: 250000, minStock: 15, importDate: '2024-07-01', expiryDate: '2026-07-01', status: 'LOW_STOCK', storageTemp: '2-8°C' },
  { id: '3', name: 'Vaccine 3 bệnh (FVRCP)', batch: 'FVRCP-2024-078', manufacturer: 'Forte', quantity: 25, unit: 'liều', costPrice: 180000, minStock: 10, importDate: '2024-05-20', expiryDate: '2026-05-20', status: 'EXPIRING', storageTemp: '2-8°C' },
];

function StatusBadgeLocal({ status }: { status: VaccineStatus }) {
  const config: Record<VaccineStatus, { label: string; className: string }> = { IN_STOCK: { label: 'Còn hàng', className: 'bg-green-100 text-green-700' }, LOW_STOCK: { label: 'Sắp hết', className: 'bg-amber-100 text-amber-700' }, EXPIRING: { label: 'Sắp hết hạn', className: 'bg-orange-100 text-orange-700' }, EXPIRED: { label: 'Đã hết hạn', className: 'bg-red-100 text-red-700' }, OUT_OF_STOCK: { label: 'Hết hàng', className: 'bg-red-100 text-red-700' } };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminVaccinesPage() {
  const [vaccines] = useState<Vaccine[]>(MOCK_VACCINES);
  const [searchQuery, setSearchQuery] = useState('');
  const [viewVaccine, setViewVaccine] = useState<Vaccine | null>(null);
  const [addBatchOpen, setAddBatchOpen] = useState(false);

  const filteredVaccines = vaccines.filter(v => v.name.toLowerCase().includes(searchQuery.toLowerCase()) || v.batch.toLowerCase().includes(searchQuery.toLowerCase()));
  const lowStockCount = vaccines.filter(v => v.status === 'LOW_STOCK' || v.status === 'OUT_OF_STOCK').length;
  const expiringCount = vaccines.filter(v => v.status === 'EXPIRING' || v.status === 'EXPIRED').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Quản lý Vaccine</h1>
        <p className="text-(--text-secondary)">Theo dõi tồn kho và hạn dùng vaccine</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-green-100"><Boxes className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-semibold text-green-600">{vaccines.filter(v => v.status === 'IN_STOCK').length}</p><p className="text-sm text-(--text-secondary)">Còn hàng</p></div></CardContent></Card>
        <Card className="card-kpi border-amber-200"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-amber-100"><AlertTriangle className="h-6 w-6 text-amber-600" /></div><div><p className="text-2xl font-semibold text-amber-600">{lowStockCount}</p><p className="text-sm text-(--text-secondary)">Sắp hết / Hết hàng</p></div></CardContent></Card>
        <Card className="card-kpi border-orange-200"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-orange-100"><AlertTriangle className="h-6 w-6 text-orange-600" /></div><div><p className="text-2xl font-semibold text-orange-600">{expiringCount}</p><p className="text-sm text-(--text-secondary)">Sắp hết hạn</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-blue-100"><Boxes className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-semibold text-blue-600">{vaccines.length}</p><p className="text-sm text-(--text-secondary)">Tổng loại vaccine</p></div></CardContent></Card>
      </div>

      <Card className="card"><CardContent className="p-4 flex items-center justify-between"><div className="relative max-w-md"><Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" /><Input placeholder="Tìm vaccine..." className="pl-10 input" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} /></div><Button onClick={() => setAddBatchOpen(true)}><Plus className="mr-2 h-4 w-4" />Nhập vaccine</Button></CardContent></Card>

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead><tr><th>Vaccine</th><th>Lô</th><th>Tồn kho</th><th>Giá nhập</th><th>Hạn dùng</th><th>Nhiệt độ</th><th>Trạng thái</th><th></th></tr></thead>
            <tbody>
              {filteredVaccines.map(vaccine => (
                <tr key={vaccine.id} className={vaccine.status === 'LOW_STOCK' ? 'bg-amber-50/50' : vaccine.status === 'OUT_OF_STOCK' ? 'bg-red-50/50' : vaccine.status === 'EXPIRING' ? 'bg-orange-50/50' : ''}>
                  <td><div><p className="font-medium text-(--text-primary)">{vaccine.name}</p><p className="text-xs text-(--text-tertiary)">{vaccine.manufacturer}</p></div></td>
                  <td className="font-mono text-sm text-(--text-secondary)">{vaccine.batch}</td>
                  <td><p className="font-medium text-(--text-primary)">{vaccine.quantity} {vaccine.unit}</p><p className="text-xs text-(--text-tertiary)">Tối thiểu: {vaccine.minStock}</p></td>
                  <td className="text-(--text-secondary)">{formatCurrency(vaccine.costPrice)}</td>
                  <td><p className="text-(--text-primary)">{vaccine.expiryDate}</p><p className="text-xs text-(--text-tertiary)">Nhập: {vaccine.importDate}</p></td>
                  <td className="text-(--text-secondary)">{vaccine.storageTemp}</td>
                  <td><StatusBadgeLocal status={vaccine.status} /></td>
                  <td><Button size="sm" variant="ghost" onClick={() => setViewVaccine(vaccine)} title="Xem chi tiết"><Eye className="h-4 w-4" /></Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {/* MODALS */}
      <DetailModal open={!!viewVaccine} onOpenChange={(o) => !o && setViewVaccine(null)} title="Chi tiết lô vaccine" size="md">
        {viewVaccine && (
          <div className="space-y-4">
            <div className="flex items-center justify-between pb-4 border-b border-(--color-border-light)"><div><h3 className="text-lg font-semibold">{viewVaccine.name}</h3><p className="text-sm text-(--text-secondary)">{viewVaccine.batch}</p></div><StatusBadgeLocal status={viewVaccine.status} /></div>
            <div className="space-y-1"><InfoRow label="Nhà sản xuất" value={viewVaccine.manufacturer} /><InfoRow label="Số lượng" value={`${viewVaccine.quantity} ${viewVaccine.unit}`} /><InfoRow label="Tồn kho tối thiểu" value={`${viewVaccine.minStock} ${viewVaccine.unit}`} /><InfoRow label="Giá nhập" value={formatCurrency(viewVaccine.costPrice)} /><InfoRow label="Ngày nhập" value={viewVaccine.importDate} /><InfoRow label="Hạn dùng" value={viewVaccine.expiryDate} /><InfoRow label="Nhiệt độ bảo quản" value={viewVaccine.storageTemp} /></div>
          </div>
        )}
      </DetailModal>

      <FormModal open={addBatchOpen} onOpenChange={setAddBatchOpen} title="Nhập lô vaccine mới" description="Thêm thông tin lô vaccine nhập kho" onSubmit={() => setAddBatchOpen(false)} submitText="Nhập kho" size="lg">
        <div className="grid grid-cols-2 gap-4">
          <div><label className="block text-sm font-medium mb-1.5">Tên vaccine *</label><Input placeholder="VD: Vaccine dại (Rabies)" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Số lô *</label><Input placeholder="VD: RB-2024-089" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Nhà sản xuất</label><Input placeholder="VD: Nobivac" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Số lượng *</label><Input type="number" placeholder="VD: 100" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Giá nhập/liều *</label><Input type="number" placeholder="VD: 150000" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Hạn dùng *</label><Input type="date" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Ngày nhập kho</label><Input type="date" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Nhiệt độ bảo quản</label><Input placeholder="VD: 2-8°C" /></div>
        </div>
      </FormModal>
    </div>
  );
}
