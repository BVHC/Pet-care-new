import { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, AlertTriangle, Syringe, Shield } from 'lucide-react';

interface VaccinationRecord {
  id: string; petName: string; petType: string; customerName: string;
  vaccineName: string; batchNumber: string; injectionDate: string;
  nextDueDate: string; veterinarian: string; status: 'DONE' | 'DUE' | 'OVERDUE';
}

const MOCK_RECORDS: VaccinationRecord[] = [
  { id: '1', petName: 'Chó Alaska', petType: 'Chó', customerName: 'Trần Thị B', vaccineName: 'Vaccine dại', batchNumber: 'LOT-2024-001', injectionDate: '2026-09-18', nextDueDate: '2027-09-18', veterinarian: 'Dr. Lan', status: 'DONE' },
  { id: '2', petName: 'Mèo Persian', petType: 'Mèo', customerName: 'Phạm Thị D', vaccineName: 'Vaccine 5 bệnh', batchNumber: 'LOT-2024-002', injectionDate: '2026-06-20', nextDueDate: '2026-09-20', veterinarian: 'Dr. Minh', status: 'DUE' },
  { id: '3', petName: 'Chó Golden', petType: 'Chó', customerName: 'Hoàng Văn E', vaccineName: 'Vaccine dại', batchNumber: 'LOT-2024-001', injectionDate: '2026-03-15', nextDueDate: '2026-09-15', veterinarian: 'Dr. Lan', status: 'OVERDUE' },
];

export function AdminVaccinationPage() {
  const [records] = useState(MOCK_RECORDS);
  const [searchQuery, setSearchQuery] = useState('');

  const dueCount = records.filter(r => r.status === 'DUE').length;
  const overdueCount = records.filter(r => r.status === 'OVERDUE').length;

  const filteredRecords = records.filter(r =>
    r.petName.toLowerCase().includes(searchQuery.toLowerCase()) ||
    r.customerName.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Tiêm chủng</h1>
        <p className="text-gray-500">Quản lý lịch tiêm vaccine và theo dõi</p>
      </div>

      {/* Alert */}
      {overdueCount > 0 && (
        <Card className="border-red-500 bg-red-50">
          <CardContent className="p-4 flex items-center gap-4">
            <AlertTriangle className="h-6 w-6 text-red-600" />
            <div>
              <p className="font-medium text-red-700">Vaccine quá hạn</p>
              <p className="text-sm text-red-600">{overdueCount} thú cưng cần tiêm lại</p>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Stats */}
      <div className="grid grid-cols-4 gap-4">
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-blue-100"><Syringe className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-bold">{records.length}</p><p className="text-sm text-gray-500">Tổng lịch tiêm</p></div></CardContent></Card>
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-green-100"><Syringe className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-bold">{records.filter(r => r.status === 'DONE').length}</p><p className="text-sm text-gray-500">Đã tiêm</p></div></CardContent></Card>
        <Card className="border-yellow-500"><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-yellow-100"><Syringe className="h-6 w-6 text-yellow-600" /></div><div><p className="text-2xl font-bold text-yellow-600">{dueCount}</p><p className="text-sm text-gray-500">Đến hạn</p></div></CardContent></Card>
        <Card className="border-red-500"><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-red-100"><AlertTriangle className="h-6 w-6 text-red-600" /></div><div><p className="text-2xl font-bold text-red-600">{overdueCount}</p><p className="text-sm text-gray-500">Quá hạn</p></div></CardContent></Card>
      </div>

      {/* Search */}
      <Card>
        <CardContent className="p-4">
          <div className="relative max-w-md">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" />
            <Input placeholder="Tìm kiếm..." className="pl-10" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} />
          </div>
        </CardContent>
      </Card>

      {/* Table */}
      <Card>
        <CardContent className="p-0">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-4 py-3 text-left text-sm font-medium">Thú cưng</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Khách hàng</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Vaccine</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Ngày tiêm</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Mũi tiếp theo</th>
                  <th className="px-4 py-3 text-left text-sm font-medium">Trạng thái</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {filteredRecords.map(record => (
                  <tr key={record.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3"><p className="font-medium">{record.petName}</p><p className="text-xs text-gray-500">{record.petType}</p></td>
                    <td className="px-4 py-3">{record.customerName}</td>
                    <td className="px-4 py-3"><p className="font-medium">{record.vaccineName}</p><p className="text-xs text-gray-500">Lô: {record.batchNumber}</p></td>
                    <td className="px-4 py-3">{record.injectionDate}</td>
                    <td className="px-4 py-3">{record.nextDueDate}</td>
                    <td className="px-4 py-3">
                      <Badge className={record.status === 'DONE' ? 'bg-green-100 text-green-700' : record.status === 'DUE' ? 'bg-yellow-100 text-yellow-700' : 'bg-red-100 text-red-700'}>
                        {record.status === 'DONE' ? 'Đã tiêm' : record.status === 'DUE' ? 'Đến hạn' : 'Quá hạn'}
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
