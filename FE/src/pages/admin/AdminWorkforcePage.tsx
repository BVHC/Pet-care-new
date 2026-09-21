import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Badge } from '../../components/ui/badge';
import { Users, Clock, Calendar } from 'lucide-react';
import { ROLE_LABELS } from '../../shared/types/admin';

interface Staff {
  id: string; name: string; role: string; status: 'ACTIVE' | 'ON_LEAVE'; todayShift?: string;
}

const MOCK_STAFF: Staff[] = [
  { id: '1', name: 'Nguyễn Văn A', role: 'STORE_MANAGER', status: 'ACTIVE', todayShift: 'Ca sáng' },
  { id: '2', name: 'Trần Thị B', role: 'RECEPTIONIST', status: 'ACTIVE', todayShift: 'Ca sáng' },
  { id: '3', name: 'Lê Văn C', role: 'VETERINARIAN', status: 'ACTIVE', todayShift: 'Ca chiều' },
  { id: '4', name: 'Phạm Thị D', role: 'VETERINARIAN', status: 'ON_LEAVE' },
  { id: '5', name: 'Hoàng Văn E', role: 'GROOMER', status: 'ACTIVE', todayShift: 'Ca sáng' },
];

export function AdminWorkforcePage() {
  const activeCount = MOCK_STAFF.filter(s => s.status === 'ACTIVE').length;
  const onLeaveCount = MOCK_STAFF.filter(s => s.status === 'ON_LEAVE').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Nhân sự & Ca làm việc</h1>
        <p className="text-gray-500">Quản lý nhân viên và lịch ca</p>
      </div>

      <div className="grid grid-cols-3 gap-4">
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-blue-100"><Users className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-bold">{MOCK_STAFF.length}</p><p className="text-sm text-gray-500">Tổng nhân viên</p></div></CardContent></Card>
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-green-100"><Users className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-bold">{activeCount}</p><p className="text-sm text-gray-500">Đang làm việc</p></div></CardContent></Card>
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-yellow-100"><Clock className="h-6 w-6 text-yellow-600" /></div><div><p className="text-2xl font-bold">{onLeaveCount}</p><p className="text-sm text-gray-500">Nghỉ phép</p></div></CardContent></Card>
      </div>

      <Card>
        <CardHeader><CardTitle>Lịch ca hôm nay</CardTitle></CardHeader>
        <CardContent>
          <div className="grid grid-cols-3 gap-4">
            {['Ca sáng (07:00-13:00)', 'Ca chiều (13:00-19:00)', 'Ca tối (19:00-22:00)'].map((shift, idx) => (
              <div key={idx} className="border rounded-lg p-4">
                <h3 className="font-medium mb-3">{shift}</h3>
                <div className="space-y-2">
                  {MOCK_STAFF.filter(s => s.todayShift?.includes(idx === 0 ? 'sáng' : idx === 1 ? 'chiều' : '')).map(s => (
                    <div key={s.id} className="flex items-center gap-2 p-2 bg-gray-50 rounded">
                      <div className="w-8 h-8 rounded-full bg-blue-600 flex items-center justify-center text-white text-sm font-medium">{s.name.charAt(0)}</div>
                      <div>
                        <p className="text-sm font-medium">{s.name}</p>
                        <p className="text-xs text-gray-500">{ROLE_LABELS[s.role as keyof typeof ROLE_LABELS]}</p>
                      </div>
                    </div>
                  ))}
                  {MOCK_STAFF.filter(s => s.todayShift?.includes(idx === 0 ? 'sáng' : idx === 1 ? 'chiều' : '')).length === 0 && (
                    <p className="text-sm text-gray-500 text-center py-4">Chưa có nhân viên</p>
                  )}
                </div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>Danh sách nhân viên</CardTitle></CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {MOCK_STAFF.map(s => (
              <Card key={s.id} className="p-4">
                <div className="flex items-center gap-3">
                  <div className="w-12 h-12 rounded-full bg-blue-600 flex items-center justify-center text-white text-lg font-medium">{s.name.charAt(0)}</div>
                  <div className="flex-1">
                    <div className="flex items-center gap-2">
                      <h3 className="font-medium">{s.name}</h3>
                      <Badge className={s.status === 'ACTIVE' ? 'bg-green-100 text-green-700' : 'bg-yellow-100 text-yellow-700'}>
                        {s.status === 'ACTIVE' ? 'Đang làm' : 'Nghỉ phép'}
                      </Badge>
                    </div>
                    <p className="text-sm text-gray-500">{ROLE_LABELS[s.role as keyof typeof ROLE_LABELS]}</p>
                  </div>
                </div>
                {s.todayShift && <p className="mt-2 text-xs text-gray-500">• {s.todayShift}</p>}
              </Card>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
