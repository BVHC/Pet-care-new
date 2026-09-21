import { Card, CardContent } from '../../components/ui/card';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Boxes, Search, AlertTriangle } from 'lucide-react';
import { cn } from '../../lib/utils';

type Status = 'IN_STOCK' | 'LOW_STOCK' | 'EXPIRING';

interface VaccineBatch {
  id: string; name: string; batchNumber: string; quantity: number;
  minStock: number; expiryDate: string; status: Status;
}

const MOCK_VACCINES: VaccineBatch[] = [
  { id: '1', name: 'Vaccine dại Nobivac', batchNumber: 'LOT-2024-001', quantity: 50, minStock: 20, expiryDate: '2027-06-30', status: 'IN_STOCK' },
  { id: '2', name: 'Vaccine 5 bệnh cho chó', batchNumber: 'LOT-2024-002', quantity: 15, minStock: 20, expiryDate: '2026-12-31', status: 'LOW_STOCK' },
  { id: '3', name: 'Vaccine dại Rabisin', batchNumber: 'LOT-2024-003', quantity: 30, minStock: 15, expiryDate: '2026-09-30', status: 'EXPIRING' },
];

export function AdminVaccinesPage() {
  const lowStockCount = MOCK_VACCINES.filter(v => v.status === 'LOW_STOCK').length;
  const expiringCount = MOCK_VACCINES.filter(v => v.status === 'EXPIRING').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Vaccine lô & Hạn dùng</h1>
        <p className="text-gray-500">Quản lý vaccine và theo dõi hạn sử dụng</p>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-blue-100"><Boxes className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-bold">{MOCK_VACCINES.length}</p><p className="text-sm text-gray-500">Tổng lô vaccine</p></div></CardContent></Card>
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-green-100"><Boxes className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-bold">{MOCK_VACCINES.filter(v => v.status === 'IN_STOCK').length}</p><p className="text-sm text-gray-500">Còn hàng</p></div></CardContent></Card>
        <Card className="border-yellow-500"><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-yellow-100"><AlertTriangle className="h-6 w-6 text-yellow-600" /></div><div><p className="text-2xl font-bold text-yellow-600">{lowStockCount}</p><p className="text-sm text-gray-500">Sắp hết</p></div></CardContent></Card>
        <Card className="border-orange-500"><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-orange-100"><AlertTriangle className="h-6 w-6 text-orange-600" /></div><div><p className="text-2xl font-bold text-orange-600">{expiringCount}</p><p className="text-sm text-gray-500">Sắp hết hạn</p></div></CardContent></Card>
      </div>

      <Card>
        <CardContent className="p-4">
          <div className="relative max-w-md">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" />
            <Input placeholder="Tìm vaccine..." className="pl-10" />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="p-0">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-4 py-3 text-left text-sm font-medium">Tên vaccine</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Số lô</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Số lượng</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Hạn SD</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Trạng thái</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {MOCK_VACCINES.map(vaccine => (
                  <tr key={vaccine.id} className={cn(
                    'hover:bg-gray-50',
                    vaccine.status === 'LOW_STOCK' && 'bg-yellow-50/50',
                    vaccine.status === 'EXPIRING' && 'bg-orange-50/50'
                  )}>
                    <td className="px-4 py-3 font-medium">{vaccine.name}</td>
                    <td className="px-4 py-3 font-mono text-sm">{vaccine.batchNumber}</td>
                    <td className="px-4 py-3">
                      <p className="font-medium">{vaccine.quantity} liều</p>
                      <p className="text-xs text-gray-500">Tối thiểu: {vaccine.minStock}</p>
                    </td>
                    <td className="px-4 py-3">{vaccine.expiryDate}</td>
                    <td className="px-4 py-3">
                      <Badge className={vaccine.status === 'IN_STOCK' ? 'bg-green-100 text-green-700' : vaccine.status === 'LOW_STOCK' ? 'bg-yellow-100 text-yellow-700' : 'bg-orange-100 text-orange-700'}>
                        {vaccine.status === 'IN_STOCK' ? 'Còn hàng' : vaccine.status === 'LOW_STOCK' ? 'Sắp hết' : 'Sắp hết hạn'}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
