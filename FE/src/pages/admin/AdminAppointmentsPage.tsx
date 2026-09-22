import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Badge } from '../../components/ui/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../components/ui/select';
import { Search, User, Phone, Eye } from 'lucide-react';
import { cn } from '../../lib/utils';
import { ConfirmModal, DetailModal, FormModal, InfoRow } from '../../components/ui/modal-templates';

type AppointmentStatus = 'PENDING' | 'CONFIRMED' | 'CHECKED_IN' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

interface Appointment {
  id: string; date: string; time: string; customerName: string;
  customerPhone: string; petName: string; petType: string;
  service: string; veterinarian: string; status: AppointmentStatus;
}

const MOCK_APPOINTMENTS: Appointment[] = [
  { id: '1', date: '2026-09-18', time: '09:00', customerName: 'Nguyễn Văn A', customerPhone: '0901234567', petName: 'Mèo Whiskas', petType: 'Mèo', service: 'Khám tổng quát', veterinarian: 'Dr. Minh', status: 'COMPLETED' },
  { id: '2', date: '2026-09-18', time: '10:30', customerName: 'Trần Thị B', customerPhone: '0912345678', petName: 'Chó Alaska', petType: 'Chó', service: 'Tiêm vaccine', veterinarian: 'Dr. Lan', status: 'IN_PROGRESS' },
  { id: '3', date: '2026-09-18', time: '11:00', customerName: 'Lê Văn C', customerPhone: '0923456789', petName: 'Chó Poodle', petType: 'Chó', service: 'Grooming', veterinarian: '-', status: 'CONFIRMED' },
  { id: '4', date: '2026-09-18', time: '14:00', customerName: 'Phạm Thị D', customerPhone: '0934567890', petName: 'Mèo Persian', petType: 'Mèo', service: 'Tái khám', veterinarian: 'Dr. Minh', status: 'PENDING' },
];

const STATUS_CONFIG: Record<AppointmentStatus, { label: string; className: string }> = {
  PENDING: { label: 'Chờ xác nhận', className: 'bg-gray-100 text-gray-700' },
  CONFIRMED: { label: 'Đã xác nhận', className: 'bg-blue-100 text-blue-700' },
  CHECKED_IN: { label: 'Đã check-in', className: 'bg-amber-100 text-amber-700' },
  IN_PROGRESS: { label: 'Đang khám', className: 'bg-green-100 text-green-700' },
  COMPLETED: { label: 'Hoàn tất', className: 'bg-emerald-100 text-emerald-700' },
  CANCELLED: { label: 'Đã hủy', className: 'bg-red-100 text-red-700' },
};

export function AdminAppointmentsPage() {
  const [appointments, setAppointments] = useState<Appointment[]>(MOCK_APPOINTMENTS);
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>('all');
  const [viewApt, setViewApt] = useState<Appointment | null>(null);
  const [checkInApt, setCheckInApt] = useState<Appointment | null>(null);
  const [cancelApt, setCancelApt] = useState<Appointment | null>(null);
  const [addAptOpen, setAddAptOpen] = useState(false);

  const filtered = appointments.filter(apt => {
    const matchesSearch = apt.customerName.toLowerCase().includes(searchQuery.toLowerCase()) || apt.petName.toLowerCase().includes(searchQuery.toLowerCase());
    const matchesStatus = statusFilter === 'all' || apt.status === statusFilter;
    return matchesSearch && matchesStatus;
  });

  const handleCheckIn = () => {
    if (!checkInApt) return;
    setAppointments(prev => prev.map(a => a.id === checkInApt.id ? { ...a, status: 'CHECKED_IN' as AppointmentStatus } : a));
    setCheckInApt(null);
  };

  const handleCancel = () => {
    if (!cancelApt) return;
    setAppointments(prev => prev.map(a => a.id === cancelApt.id ? { ...a, status: 'CANCELLED' as AppointmentStatus } : a));
    setCancelApt(null);
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Quản lý lịch hẹn</h1>
        <p className="text-(--text-secondary)">Xem và quản lý các lịch hẹn khám bệnh và dịch vụ</p>
      </div>

      <Card className="card"><CardContent className="p-4">
        <div className="flex flex-wrap gap-4 items-end">
          <div className="flex-1 min-w-[200px]">
            <div className="relative"><Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" /><Input placeholder="Tên khách hàng, thú cưng..." className="pl-10 input" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} /></div>
          </div>
          <div className="w-[180px]">
            <Select value={statusFilter} onValueChange={setStatusFilter}>
              <SelectTrigger className="select-trigger"><SelectValue placeholder="Tất cả" /></SelectTrigger>
              <SelectContent><SelectItem value="all">Tất cả</SelectItem><SelectItem value="PENDING">Chờ xác nhận</SelectItem><SelectItem value="CONFIRMED">Đã xác nhận</SelectItem><SelectItem value="CHECKED_IN">Đã check-in</SelectItem></SelectContent>
            </Select>
          </div>
          <Button onClick={() => setAddAptOpen(true)}>Tạo lịch hẹn</Button>
        </div>
      </CardContent></Card>

      <Card className="card">
        <CardContent className="p-0">
          <div className="divide-y divide-(--border-subtle)">
            {filtered.map(apt => (
              <div key={apt.id} className={cn('flex items-center justify-between p-4', apt.status === 'IN_PROGRESS' && 'bg-green-50/50 border-l-4 border-l-green-500', apt.status === 'CHECKED_IN' && 'bg-amber-50/50 border-l-4 border-l-amber-500')}>
                <div className="flex items-center gap-5">
                  <div className="w-16 text-center"><p className="text-lg font-semibold text-(--text-primary)">{apt.time}</p></div>
                  <div className="border-l border-(--border-color) pl-5">
                    <div className="flex items-center gap-3 mb-1.5"><div className="flex items-center gap-2"><User className="h-4 w-4 text-(--text-tertiary)" /><span className="font-medium text-(--text-primary)">{apt.customerName}</span></div><Badge className={STATUS_CONFIG[apt.status].className}>{STATUS_CONFIG[apt.status].label}</Badge></div>
                    <div className="flex items-center gap-4 text-sm text-(--text-secondary)"><span className="flex items-center gap-1.5"><Phone className="h-3 w-3" />{apt.customerPhone}</span><span>{apt.petName} ({apt.petType})</span></div>
                    <p className="text-sm mt-1.5"><span className="text-(--color-primary) font-medium">{apt.service}</span>{apt.veterinarian !== '-' && <span className="text-(--text-tertiary)"> • {apt.veterinarian}</span>}</p>
                  </div>
                </div>
                <div className="flex gap-2 ml-4">
                  <Button size="sm" variant="outline" onClick={() => setViewApt(apt)}><Eye className="h-4 w-4" /></Button>
                  {apt.status === 'CONFIRMED' && <Button size="sm" className="bg-(--color-primary)" onClick={() => setCheckInApt(apt)}>Check-in</Button>}
                  {apt.status === 'CHECKED_IN' && <Button size="sm" className="bg-green-600 hover:bg-green-700">Bắt đầu khám</Button>}
                  {!['COMPLETED', 'CANCELLED'].includes(apt.status) && <Button size="sm" variant="outline" className="text-red-500 border-red-200" onClick={() => setCancelApt(apt)}>Hủy</Button>}
                </div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* MODALS */}
      <DetailModal open={!!viewApt} onOpenChange={(o) => !o && setViewApt(null)} title="Chi tiết lịch hẹn" size="md">
        {viewApt && (
          <div className="space-y-4">
            <div className="flex items-center justify-between pb-4 border-b border-(--color-border-light)"><div><h3 className="text-lg font-semibold">{viewApt.petName}</h3><p className="text-sm text-(--text-secondary)">{viewApt.customerName}</p></div><Badge className={STATUS_CONFIG[viewApt.status].className}>{STATUS_CONFIG[viewApt.status].label}</Badge></div>
            <div className="space-y-1"><InfoRow label="Ngày hẹn" value={viewApt.date} /><InfoRow label="Giờ hẹn" value={viewApt.time} /><InfoRow label="Dịch vụ" value={viewApt.service} /><InfoRow label="Bác sĩ" value={viewApt.veterinarian} /><InfoRow label="Loài" value={viewApt.petType} /><InfoRow label="SĐT" value={viewApt.customerPhone} /></div>
          </div>
        )}
      </DetailModal>

      <ConfirmModal open={!!checkInApt} onOpenChange={(o) => !o && setCheckInApt(null)} type="success" title="Xác nhận Check-in?" description={`Check-in lịch hẹn của "${checkInApt?.customerName}" cho thú cưng "${checkInApt?.petName}"?`} confirmText="Xác nhận Check-in" onConfirm={handleCheckIn} />

      <ConfirmModal open={!!cancelApt} onOpenChange={(o) => !o && setCancelApt(null)} type="danger" title="Hủy lịch hẹn?" description={`Hủy lịch hẹn của "${cancelApt?.customerName}" vào lúc ${cancelApt?.time}? Khách hàng sẽ được thông báo.`} confirmText="Hủy lịch hẹn" onConfirm={handleCancel} />

      <FormModal open={addAptOpen} onOpenChange={setAddAptOpen} title="Tạo lịch hẹn mới" description="Điều thông tin để đặt lịch hẹn mới" onSubmit={() => setAddAptOpen(false)} submitText="Tạo lịch hẹn" size="lg">
        <div className="grid grid-cols-2 gap-4">
          <div><label className="block text-sm font-medium mb-1.5">Tên chủ nuôi *</label><Input placeholder="Nguyễn Văn A" /></div>
          <div><label className="block text-sm font-medium mb-1.5">SĐT *</label><Input placeholder="0901234567" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Tên thú cưng *</label><Input placeholder="Mèo Whiskas" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Loài</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm"><option>Chó</option><option>Mèo</option></select></div>
          <div><label className="block text-sm font-medium mb-1.5">Dịch vụ *</label><Input placeholder="Khám tổng quát" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Bác sĩ</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm"><option>Dr. Minh</option><option>Dr. Lan</option></select></div>
          <div><label className="block text-sm font-medium mb-1.5">Ngày *</label><Input type="date" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Giờ *</label><Input type="time" /></div>
        </div>
      </FormModal>
    </div>
  );
}
