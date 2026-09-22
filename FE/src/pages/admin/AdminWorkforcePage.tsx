import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Plus, Users, Clock, UserCheck, AlertTriangle, Eye } from 'lucide-react';
import { ROLE_LABELS } from '../../shared/types/admin';
import { DetailModal, FormModal, InfoRow } from '../../components/ui/modal-templates';

interface Staff {
  id: string; name: string; email: string; phone: string;
  role: string; department: string; status: 'WORKING' | 'ON_LEAVE' | 'OFF';
  shift: 'MORNING' | 'AFTERNOON' | 'EVENING'; checkInTime?: string;
}

const MOCK_STAFF: Staff[] = [
  { id: '1', name: 'Nguyễn Văn A', email: 'nva@petcare.vn', phone: '0901234567', role: 'STORE_MANAGER', department: 'Quản lý', status: 'WORKING', shift: 'MORNING', checkInTime: '08:00' },
  { id: '2', name: 'Trần Thị B', email: 'ttb@petcare.vn', phone: '0912345678', role: 'RECEPTIONIST', department: 'Lễ tân', status: 'WORKING', shift: 'MORNING', checkInTime: '08:05' },
  { id: '3', name: 'Lê Văn C', email: 'lvc@petcare.vn', phone: '0923456789', role: 'VETERINARIAN', department: 'Y tế', status: 'WORKING', shift: 'MORNING', checkInTime: '08:15' },
  { id: '4', name: 'Phạm Thị D', email: 'ptd@petcare.vn', phone: '0934567890', role: 'GROOMER', department: 'Grooming', status: 'ON_LEAVE', shift: 'AFTERNOON' },
];

function StatusBadgeLocal({ status }: { status: Staff['status'] }) {
  const config: Record<Staff['status'], { label: string; className: string }> = { WORKING: { label: 'Đang làm', className: 'bg-green-100 text-green-700' }, ON_LEAVE: { label: 'Nghỉ phép', className: 'bg-amber-100 text-amber-700' }, OFF: { label: 'Nghỉ việc', className: 'bg-red-100 text-red-700' } };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function ShiftBadgeLocal({ shift }: { shift: Staff['shift'] }) {
  const config: Record<Staff['shift'], { label: string; className: string }> = { MORNING: { label: 'Sáng', className: 'bg-blue-100 text-blue-700' }, AFTERNOON: { label: 'Chiều', className: 'bg-purple-100 text-purple-700' }, EVENING: { label: 'Tối', className: 'bg-indigo-100 text-indigo-700' } };
  return <Badge variant="secondary" className={config[shift].className}>{config[shift].label}</Badge>;
}

export function AdminWorkforcePage() {
  const [staff] = useState<Staff[]>(MOCK_STAFF);
  const [searchQuery, setSearchQuery] = useState('');
  const [viewStaff, setViewStaff] = useState<Staff | null>(null);
  const [addStaffOpen, setAddStaffOpen] = useState(false);

  const filteredStaff = staff.filter(s => s.name.toLowerCase().includes(searchQuery.toLowerCase()) || s.email.toLowerCase().includes(searchQuery.toLowerCase()));
  const workingCount = staff.filter(s => s.status === 'WORKING').length;
  const onLeaveCount = staff.filter(s => s.status === 'ON_LEAVE').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Nhân sự theo ca</h1>
        <p className="text-(--text-secondary)">Quản lý nhân viên và lịch trực</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-blue-100"><Users className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-semibold">{staff.length}</p><p className="text-sm text-(--text-secondary)">Tổng nhân viên</p></div></CardContent></Card>
        <Card className="card-kpi border-green-200"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-green-100"><UserCheck className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-semibold text-green-600">{workingCount}</p><p className="text-sm text-(--text-secondary)">Đang làm việc</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-amber-100"><Clock className="h-6 w-6 text-amber-600" /></div><div><p className="text-2xl font-semibold text-amber-600">{onLeaveCount}</p><p className="text-sm text-(--text-secondary)">Nghỉ phép</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-red-100"><AlertTriangle className="h-6 w-6 text-red-600" /></div><div><p className="text-2xl font-semibold text-red-600">{staff.filter(s => s.status === 'OFF').length}</p><p className="text-sm text-(--text-secondary)">Nghỉ việc</p></div></CardContent></Card>
      </div>

      <Card className="card"><CardContent className="p-4 flex items-center justify-between"><div className="relative max-w-md"><Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" /><Input placeholder="Tìm nhân viên..." className="pl-10 input" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} /></div><Button onClick={() => setAddStaffOpen(true)}><Plus className="mr-2 h-4 w-4" />Thêm nhân viên</Button></CardContent></Card>

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead><tr><th>Nhân viên</th><th>Vai trò</th><th>Bộ phận</th><th>Ca trực</th><th>Check-in</th><th>Trạng thái</th><th></th></tr></thead>
            <tbody>
              {filteredStaff.map(s => (
                <tr key={s.id} className={s.status === 'WORKING' ? 'bg-green-50/30' : s.status === 'OFF' ? 'opacity-50' : ''}>
                  <td><div><p className="font-medium text-(--text-primary)">{s.name}</p><p className="text-xs text-(--text-tertiary)">{s.email}</p></div></td>
                  <td><Badge variant="secondary" className="bg-(--bg-tertiary)">{ROLE_LABELS[s.role as keyof typeof ROLE_LABELS] || s.role}</Badge></td>
                  <td className="text-(--text-secondary)">{s.department}</td>
                  <td><ShiftBadgeLocal shift={s.shift} /></td>
                  <td>{s.checkInTime ? <span className="font-medium">{s.checkInTime}</span> : <span className="text-(--text-tertiary)">-</span>}</td>
                  <td><StatusBadgeLocal status={s.status} /></td>
                  <td><Button size="sm" variant="ghost" onClick={() => setViewStaff(s)} title="Xem chi tiết"><Eye className="h-4 w-4" /></Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {/* MODALS */}
      <DetailModal open={!!viewStaff} onOpenChange={(o) => !o && setViewStaff(null)} title="Chi tiết nhân viên" size="md">
        {viewStaff && (
          <div className="space-y-4">
            <div className="flex items-center gap-4 pb-4 border-b border-(--color-border-light)">
              <div className="h-16 w-16 rounded-full bg-linear-to-br from-accent to-accent-hover flex items-center justify-center text-white text-2xl font-bold">{viewStaff.name.charAt(0)}</div>
              <div><h3 className="text-lg font-semibold">{viewStaff.name}</h3><p className="text-sm text-(--text-secondary)">{viewStaff.email}</p><StatusBadgeLocal status={viewStaff.status} /></div>
            </div>
            <div className="space-y-1"><InfoRow label="Số điện thoại" value={viewStaff.phone} /><InfoRow label="Vai trò" value={ROLE_LABELS[viewStaff.role as keyof typeof ROLE_LABELS] || viewStaff.role} /><InfoRow label="Bộ phận" value={viewStaff.department} /><InfoRow label="Ca trực" value={<ShiftBadgeLocal shift={viewStaff.shift} />} /><InfoRow label="Check-in" value={viewStaff.checkInTime || '-'} /></div>
          </div>
        )}
      </DetailModal>

      <FormModal open={addStaffOpen} onOpenChange={setAddStaffOpen} title="Thêm nhân viên mới" description="Điều thông tin để tạo tài khoản nhân viên" onSubmit={() => setAddStaffOpen(false)} submitText="Thêm nhân viên" size="lg">
        <div className="grid grid-cols-2 gap-4">
          <div><label className="block text-sm font-medium mb-1.5">Họ tên *</label><Input placeholder="Nguyễn Văn A" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Email *</label><Input type="email" placeholder="email@petcare.vn" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Số điện thoại</label><Input placeholder="0901234567" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Vai trò *</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm focus:outline-none focus:ring-2 focus:ring-accent"><option>STORE_MANAGER</option><option>RECEPTIONIST</option><option>VETERINARIAN</option><option>GROOMER</option></select></div>
          <div><label className="block text-sm font-medium mb-1.5">Bộ phận</label><Input placeholder="Quản lý" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Ca làm việc</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm focus:outline-none focus:ring-2 focus:ring-accent"><option value="MORNING">Ca Sáng</option><option value="AFTERNOON">Ca Chiều</option><option value="EVENING">Ca Tối</option></select></div>
        </div>
      </FormModal>
    </div>
  );
}
