import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { ShieldAlert, Search, AlertTriangle, Clock } from 'lucide-react';

type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
type Status = 'OPEN' | 'INVESTIGATING' | 'RESOLVED';

interface Incident {
  id: string; title: string; severity: Severity; status: Status; reporter: string; createdAt: string;
}

const MOCK_INCIDENTS: Incident[] = [
  { id: '1', title: 'Mất điện tại khu vực tiêm chủng', severity: 'HIGH', status: 'RESOLVED', reporter: 'Dr. Minh', createdAt: '2026-09-18 09:30' },
  { id: '2', title: 'Thú cưng bị thương nhẹ trong grooming', severity: 'MEDIUM', status: 'INVESTIGATING', reporter: 'NV Grooming', createdAt: '2026-09-18 14:00' },
  { id: '3', title: 'Khách hàng phàn nàn thái độ nhân viên', severity: 'LOW', status: 'OPEN', reporter: 'Lễ tân', createdAt: '2026-09-17 16:00' },
];

export function AdminIncidentsPage() {
  const openCount = MOCK_INCIDENTS.filter(i => i.status === 'OPEN' || i.status === 'INVESTIGATING').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Sự cố</h1>
        <p className="text-gray-500">Báo cáo và theo dõi các sự cố</p>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-red-100"><ShieldAlert className="h-6 w-6 text-red-600" /></div><div><p className="text-2xl font-bold">{MOCK_INCIDENTS.length}</p><p className="text-sm text-gray-500">Tổng sự cố</p></div></CardContent></Card>
        <Card className="border-red-500"><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-red-100"><AlertTriangle className="h-6 w-6 text-red-600" /></div><div><p className="text-2xl font-bold text-red-600">{openCount}</p><p className="text-sm text-gray-500">Đang mở</p></div></CardContent></Card>
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-yellow-100"><Clock className="h-6 w-6 text-yellow-600" /></div><div><p className="text-2xl font-bold">{MOCK_INCIDENTS.filter(i => i.status === 'INVESTIGATING').length}</p><p className="text-sm text-gray-500">Đang điều tra</p></div></CardContent></Card>
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-green-100"><ShieldAlert className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-bold">{MOCK_INCIDENTS.filter(i => i.status === 'RESOLVED').length}</p><p className="text-sm text-gray-500">Đã xử lý</p></div></CardContent></Card>
      </div>

      <div className="space-y-4">
        {MOCK_INCIDENTS.map(incident => (
          <Card key={incident.id} className={incident.status === 'OPEN' ? 'border-l-4 border-l-red-500' : ''}>
            <CardContent className="p-4">
              <div className="flex items-start justify-between">
                <div>
                  <div className="flex items-center gap-3 mb-2">
                    <h3 className="font-medium">{incident.title}</h3>
                    <Badge className={incident.severity === 'HIGH' || incident.severity === 'CRITICAL' ? 'bg-red-100 text-red-700' : incident.severity === 'MEDIUM' ? 'bg-yellow-100 text-yellow-700' : 'bg-blue-100 text-blue-700'}>
                      {incident.severity === 'HIGH' ? 'Cao' : incident.severity === 'MEDIUM' ? 'Trung bình' : 'Thấp'}
                    </Badge>
                    <Badge className={incident.status === 'OPEN' ? 'bg-red-100 text-red-700' : incident.status === 'INVESTIGATING' ? 'bg-yellow-100 text-yellow-700' : 'bg-green-100 text-green-700'}>
                      {incident.status === 'OPEN' ? 'Mới' : incident.status === 'INVESTIGATING' ? 'Đang điều tra' : 'Đã xử lý'}
                    </Badge>
                  </div>
                  <div className="flex items-center gap-4 text-sm text-gray-500">
                    <span>Người báo cáo: {incident.reporter}</span>
                    <span className="flex items-center gap-1"><Clock className="h-3 w-3" />{incident.createdAt}</span>
                  </div>
                </div>
                {incident.status === 'OPEN' && <Button size="sm">Xử lý</Button>}
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}
