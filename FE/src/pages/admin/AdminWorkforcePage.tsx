import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Plus, Users, Clock, UserCheck, AlertTriangle } from 'lucide-react';
import { ROLE_LABELS } from '../../shared/types/admin';
import { cn } from '../../lib/utils';

interface Staff {
  id: string;
  name: string;
  email: string;
  phone: string;
  role: string;
  department: string;
  status: 'WORKING' | 'ON_LEAVE' | 'OFF';
  shift: 'MORNING' | 'AFTERNOON' | 'EVENING';
  checkInTime?: string;
}

const MOCK_STAFF: Staff[] = [
  { id: '1', name: 'Nguyễn Văn A', email: 'nva@petcare.vn', phone: '0901234567', role: 'STORE_MANAGER', department: 'Quản lý', status: 'WORKING', shift: 'MORNING', checkInTime: '08:00' },
  { id: '2', name: 'Trần Thị B', email: 'ttb@petcare.vn', phone: '0912345678', role: 'RECEPTIONIST', department: 'Lễ tân', status: 'WORKING', shift: 'MORNING', checkInTime: '08:05' },
  { id: '3', name: 'Lê Văn C', email: 'lvc@petcare.vn', phone: '0923456789', role: 'VETERINARIAN', department: 'Y tế', status: 'WORKING', shift: 'MORNING', checkInTime: '08:15' },
  { id: '4', name: 'Phạm Thị D', email: 'ptd@petcare.vn', phone: '0934567890', role: 'GROOMER', department: 'Grooming', status: 'ON_LEAVE', shift: 'AFTERNOON' },
  { id: '5', name: 'Hoàng Văn E', email: 'hve@petcare.vn', phone: '0945678901', role: 'INVENTORY_STAFF', department: 'Kho', status: 'WORKING', shift: 'MORNING', checkInTime: '08:30' },
];

function StatusBadge({ status }: { status: Staff['status'] }) {
  const config: Record<Staff['status'], { label: string; className: string }> = {
    WORKING: { label: 'Đang làm', className: 'bg-green-100 text-green-700' },
    ON_LEAVE: { label: 'Nghỉ phép', className: 'bg-amber-100 text-amber-700' },
    OFF: { label: 'Nghỉ việc', className: 'bg-red-100 text-red-700' },
  };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function ShiftBadge({ shift }: { shift: Staff['shift'] }) {
  const config: Record<Staff['shift'], { label: string; className: string }> = {
    MORNING: { label: 'Sáng', className: 'bg-blue-100 text-blue-700' },
    AFTERNOON: { label: 'Chiều', className: 'bg-purple-100 text-purple-700' },
    EVENING: { label: 'Tối', className: 'bg-indigo-100 text-indigo-700' },
  };
  return <Badge variant="secondary" className={config[shift].className}>{config[shift].label}</Badge>;
}

export function AdminWorkforcePage() {
  const [staff] = useState(MOCK_STAFF);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredStaff = staff.filter(s =>
    s.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    s.email.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const workingCount = staff.filter(s => s.status === 'WORKING').length;
  const onLeaveCount = staff.filter(s => s.status === 'ON_LEAVE').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Nhân sự theo ca</h1>
        <p className="text-[var(--text-secondary)]">Quản lý nhân viên và lịch trực</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100">
              <Users className="h-6 w-6 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-[var(--text-primary)]">{staff.length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Tổng nhân viên</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi border-green-200">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <UserCheck className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{workingCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đang làm việc</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-amber-100">
              <Clock className="h-6 w-6 text-amber-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-amber-600">{onLeaveCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">Nghỉ phép</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-red-100">
              <AlertTriangle className="h-6 w-6 text-red-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-red-600">{staff.filter(s => s.status === 'OFF').length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Nghỉ việc</p>
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
              placeholder="Tìm nhân viên..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <Button><Plus className="mr-2 h-4 w-4" />Thêm nhân viên</Button>
        </CardContent>
      </Card>

      {/* Staff List */}
      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Nhân viên</th>
                <th>Vai trò</th>
                <th>Bộ phận</th>
                <th>Ca trực</th>
                <th>Check-in</th>
                <th>Trạng thái</th>
              </tr>
            </thead>
            <tbody>
              {filteredStaff.map(s => (
                <tr key={s.id} className={cn(
                  s.status === 'WORKING' && 'bg-green-50/30',
                  s.status === 'OFF' && 'opacity-50'
                )}>
                  <td>
                    <div>
                      <p className="font-medium text-[var(--text-primary)]">{s.name}</p>
                      <p className="text-xs text-[var(--text-tertiary)]">{s.email}</p>
                    </div>
                  </td>
                  <td>
                    <Badge variant="secondary" className="bg-[var(--bg-tertiary)]">
                      {ROLE_LABELS[s.role as keyof typeof ROLE_LABELS] || s.role}
                    </Badge>
                  </td>
                  <td className="text-[var(--text-secondary)]">{s.department}</td>
                  <td><ShiftBadge shift={s.shift} /></td>
                  <td>
                    {s.checkInTime ? (
                      <span className="text-[var(--text-primary)] font-medium">{s.checkInTime}</span>
                    ) : (
                      <span className="text-[var(--text-tertiary)]">-</span>
                    )}
                  </td>
                  <td><StatusBadge status={s.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  );
}
