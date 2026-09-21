import { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Badge } from '../../components/ui/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../components/ui/select';
import { Search, Calendar, User, Phone, Eye } from 'lucide-react';
import { cn } from '../../lib/utils';

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
  { id: '5', date: '2026-09-18', time: '15:30', customerName: 'Hoàng Văn E', customerPhone: '0945678901', petName: 'Chó Golden', petType: 'Chó', service: 'Khám bệnh', veterinarian: 'Dr. Lan', status: 'CHECKED_IN' },
];

const STATUS_CONFIG: Record<AppointmentStatus, { label: string; className: string }> = {
  PENDING: { label: 'Chờ xác nhận', className: 'bg-[var(--bg-tertiary)] text-[var(--text-secondary)]' },
  CONFIRMED: { label: 'Đã xác nhận', className: 'bg-blue-100 text-blue-700' },
  CHECKED_IN: { label: 'Đã check-in', className: 'bg-amber-100 text-amber-700' },
  IN_PROGRESS: { label: 'Đang khám', className: 'bg-green-100 text-green-700' },
  COMPLETED: { label: 'Hoàn tất', className: 'bg-emerald-100 text-emerald-700' },
  CANCELLED: { label: 'Đã hủy', className: 'bg-red-100 text-red-700' },
};

export function AdminAppointmentsPage() {
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>('all');

  const filtered = MOCK_APPOINTMENTS.filter(apt => {
    const matchesSearch = apt.customerName.toLowerCase().includes(searchQuery.toLowerCase()) || apt.petName.toLowerCase().includes(searchQuery.toLowerCase());
    const matchesStatus = statusFilter === 'all' || apt.status === statusFilter;
    return matchesSearch && matchesStatus;
  });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Quản lý lịch hẹn</h1>
        <p className="text-[var(--text-secondary)]">Xem và quản lý các lịch hẹn khám bệnh và dịch vụ</p>
      </div>

      {/* Filters */}
      <Card className="card">
        <CardContent className="p-4">
          <div className="flex flex-wrap gap-4 items-end">
            <div className="flex-1 min-w-[200px]">
              <div className="relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-[var(--text-tertiary)]" />
                <Input
                  placeholder="Tên khách hàng, thú cưng..."
                  className="pl-10 input"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                />
              </div>
            </div>
            <div className="w-[180px]">
              <Select value={statusFilter} onValueChange={setStatusFilter}>
                <SelectTrigger className="select-trigger"><SelectValue placeholder="Tất cả" /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">Tất cả</SelectItem>
                  <SelectItem value="PENDING">Chờ xác nhận</SelectItem>
                  <SelectItem value="CONFIRMED">Đã xác nhận</SelectItem>
                  <SelectItem value="CHECKED_IN">Đã check-in</SelectItem>
                  <SelectItem value="IN_PROGRESS">Đang khám</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* List */}
      <Card className="card">
        <CardHeader className="border-b border-[var(--border-color)]">
          <CardTitle className="text-base font-semibold">Danh sách lịch hẹn ({filtered.length})</CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <div className="divide-y divide-[var(--border-subtle)]">
            {filtered.map(apt => (
              <div key={apt.id} className={cn(
                'flex items-center justify-between p-4 transition-colors',
                apt.status === 'IN_PROGRESS' && 'bg-green-50/50 border-l-4 border-l-green-500',
                apt.status === 'CHECKED_IN' && 'bg-amber-50/50 border-l-4 border-l-amber-500'
              )}>
                <div className="flex items-center gap-5">
                  <div className="w-16 text-center">
                    <p className="text-lg font-semibold text-[var(--text-primary)]">{apt.time}</p>
                  </div>
                  <div className="border-l border-[var(--border-color)] pl-5">
                    <div className="flex items-center gap-3 mb-1.5">
                      <div className="flex items-center gap-2">
                        <User className="h-4 w-4 text-[var(--text-tertiary)]" />
                        <span className="font-medium text-[var(--text-primary)]">{apt.customerName}</span>
                      </div>
                      <Badge className={STATUS_CONFIG[apt.status].className}>{STATUS_CONFIG[apt.status].label}</Badge>
                    </div>
                    <div className="flex items-center gap-4 text-sm text-[var(--text-secondary)]">
                      <span className="flex items-center gap-1.5"><Phone className="h-3 w-3" />{apt.customerPhone}</span>
                      <span>{apt.petName} ({apt.petType})</span>
                    </div>
                    <p className="text-sm mt-1.5">
                      <span className="text-[var(--color-primary)] font-medium">{apt.service}</span>
                      {apt.veterinarian !== '-' && <span className="text-[var(--text-tertiary)]"> • {apt.veterinarian}</span>}
                    </p>
                  </div>
                </div>
                <div className="flex gap-2 ml-4">
                  {apt.status === 'CONFIRMED' && <Button size="sm" className="bg-[var(--color-primary)]">Check-in</Button>}
                  {apt.status === 'CHECKED_IN' && <Button size="sm" className="bg-green-600 hover:bg-green-700">Bắt đầu khám</Button>}
                  <Button size="sm" variant="outline"><Eye className="h-4 w-4" /></Button>
                </div>
              </div>
            ))}
            {filtered.length === 0 && (
              <div className="text-center py-12 text-[var(--text-tertiary)]">
                <Calendar className="h-12 w-12 mx-auto mb-4 opacity-50" />
                <p>Không có lịch hẹn nào</p>
              </div>
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
