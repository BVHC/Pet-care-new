import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Plus, Boxes, AlertTriangle, Check } from 'lucide-react';
import { cn } from '../../lib/utils';

type VaccineStatus = 'IN_STOCK' | 'LOW_STOCK' | 'EXPIRING' | 'EXPIRED' | 'OUT_OF_STOCK';

interface Vaccine {
  id: string;
  name: string;
  batch: string;
  manufacturer: string;
  quantity: number;
  unit: string;
  costPrice: number;
  minStock: number;
  importDate: string;
  expiryDate: string;
  status: VaccineStatus;
  storageTemp: string;
}

const MOCK_VACCINES: Vaccine[] = [
  { id: '1', name: 'Vaccine dại (Rabies)', batch: 'RB-2024-089', manufacturer: 'Nobivac', quantity: 50, unit: 'liều', costPrice: 150000, minStock: 20, importDate: '2024-06-15', expiryDate: '2026-06-15', status: 'IN_STOCK', storageTemp: '2-8°C' },
  { id: '2', name: 'Vaccine 5 bệnh (DHPP)', batch: 'DHPP-2024-156', manufacturer: 'Nobivac', quantity: 8, unit: 'liều', costPrice: 250000, minStock: 15, importDate: '2024-07-01', expiryDate: '2026-07-01', status: 'LOW_STOCK', storageTemp: '2-8°C' },
  { id: '3', name: 'Vaccine 3 bệnh (FVRCP)', batch: 'FVRCP-2024-078', manufacturer: 'Forte', quantity: 25, unit: 'liều', costPrice: 180000, minStock: 10, importDate: '2024-05-20', expiryDate: '2026-05-20', status: 'EXPIRING', storageTemp: '2-8°C' },
  { id: '4', name: 'Vaccine 7 bệnh (7-in-1)', batch: '7IN1-2024-034', manufacturer: 'Vanguard', quantity: 0, unit: 'liều', costPrice: 350000, minStock: 10, importDate: '2024-03-10', expiryDate: '2026-03-10', status: 'OUT_OF_STOCK', storageTemp: '2-8°C' },
];

function StatusBadge({ status }: { status: VaccineStatus }) {
  const config: Record<VaccineStatus, { label: string; className: string }> = {
    IN_STOCK: { label: 'Còn hàng', className: 'bg-green-100 text-green-700' },
    LOW_STOCK: { label: 'Sắp hết', className: 'bg-amber-100 text-amber-700' },
    EXPIRING: { label: 'Sắp hết hạn', className: 'bg-orange-100 text-orange-700' },
    EXPIRED: { label: 'Đã hết hạn', className: 'bg-red-100 text-red-700' },
    OUT_OF_STOCK: { label: 'Hết hàng', className: 'bg-red-100 text-red-700' },
  };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminVaccinesPage() {
  const [vaccines] = useState(MOCK_VACCINES);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredVaccines = vaccines.filter(v =>
    v.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    v.batch.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const lowStockCount = vaccines.filter(v => v.status === 'LOW_STOCK' || v.status === 'OUT_OF_STOCK').length;
  const expiringCount = vaccines.filter(v => v.status === 'EXPIRING' || v.status === 'EXPIRED').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Quản lý Vaccine</h1>
        <p className="text-[var(--text-secondary)]">Theo dõi tồn kho và hạn dùng vaccine</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <Boxes className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{vaccines.filter(v => v.status === 'IN_STOCK').length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Còn hàng</p>
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
        <Card className="card-kpi border-orange-200">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-orange-100">
              <AlertTriangle className="h-6 w-6 text-orange-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-orange-600">{expiringCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">Sắp hết hạn</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100">
              <Boxes className="h-6 w-6 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-blue-600">{vaccines.length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Tổng loại vaccine</p>
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
              placeholder="Tìm vaccine..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <Button><Plus className="mr-2 h-4 w-4" />Nhập vaccine</Button>
        </CardContent>
      </Card>

      {/* Vaccines Table */}
      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Vaccine</th>
                <th>Lô</th>
                <th>Tồn kho</th>
                <th>Giá nhập</th>
                <th>Hạn dùng</th>
                <th>Nhiệt độ</th>
                <th>Trạng thái</th>
              </tr>
            </thead>
            <tbody>
              {filteredVaccines.map(vaccine => (
                <tr key={vaccine.id} className={cn(
                  vaccine.status === 'LOW_STOCK' && 'bg-amber-50/50',
                  vaccine.status === 'OUT_OF_STOCK' && 'bg-red-50/50',
                  vaccine.status === 'EXPIRING' && 'bg-orange-50/50'
                )}>
                  <td>
                    <div>
                      <p className="font-medium text-[var(--text-primary)]">{vaccine.name}</p>
                      <p className="text-xs text-[var(--text-tertiary)]">{vaccine.manufacturer}</p>
                    </div>
                  </td>
                  <td className="font-mono text-sm text-[var(--text-secondary)]">{vaccine.batch}</td>
                  <td>
                    <p className="font-medium text-[var(--text-primary)]">{vaccine.quantity} {vaccine.unit}</p>
                    <p className="text-xs text-[var(--text-tertiary)]">Tối thiểu: {vaccine.minStock}</p>
                  </td>
                  <td className="text-[var(--text-secondary)]">{formatCurrency(vaccine.costPrice)}</td>
                  <td>
                    <p className="text-[var(--text-primary)]">{vaccine.expiryDate}</p>
                    <p className="text-xs text-[var(--text-tertiary)]">Nhập: {vaccine.importDate}</p>
                  </td>
                  <td className="text-[var(--text-secondary)]">{vaccine.storageTemp}</td>
                  <td><StatusBadge status={vaccine.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  );
}
