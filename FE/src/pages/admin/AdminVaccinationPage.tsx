import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Syringe, Plus, Calendar, AlertCircle } from 'lucide-react';
import { cn } from '../../lib/utils';

type VaccinationStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'REMINDER_SENT';

interface VaccinationRecord {
  id: string;
  date: string;
  time: string;
  customerName: string;
  customerPhone: string;
  petName: string;
  petType: string;
  petAge: string;
  vaccineName: string;
  vaccineBatch: string;
  doseNumber: number;
  nextDoseDate?: string;
  veterinarian: string;
  status: VaccinationStatus;
  notes?: string;
}

const MOCK_VACCINATIONS: VaccinationRecord[] = [
  {
    id: '1',
    date: '2026-09-18',
    time: '09:00',
    customerName: 'Nguyễn Văn A',
    customerPhone: '0901234567',
    petName: 'Mèo Whiskas',
    petType: 'Mèo',
    petAge: '2 năm',
    vaccineName: 'Vaccine dại (Rabies)',
    vaccineBatch: 'RB-2024-089',
    doseNumber: 1,
    nextDoseDate: '2027-09-18',
    veterinarian: 'Dr. Minh',
    status: 'COMPLETED',
    notes: 'Tiêm không có phản ứng phụ'
  },
  {
    id: '2',
    date: '2026-09-18',
    time: '10:30',
    customerName: 'Trần Thị B',
    customerPhone: '0912345678',
    petName: 'Chó Alaska',
    petType: 'Chó',
    petAge: '1 năm',
    vaccineName: 'Vaccine 5 bệnh (DHPP)',
    vaccineBatch: 'DHPP-2024-156',
    doseNumber: 2,
    nextDoseDate: '2026-10-02',
    veterinarian: 'Dr. Lan',
    status: 'IN_PROGRESS'
  },
  {
    id: '3',
    date: '2026-09-18',
    time: '14:00',
    customerName: 'Lê Văn C',
    customerPhone: '0923456789',
    petName: 'Chó Poodle',
    petType: 'Chó',
    petAge: '3 năm',
    vaccineName: 'Vaccine dại (Rabies)',
    vaccineBatch: 'RB-2024-089',
    doseNumber: 1,
    veterinarian: 'Dr. Minh',
    status: 'SCHEDULED'
  },
  {
    id: '4',
    date: '2026-09-17',
    time: '15:00',
    customerName: 'Phạm Thị D',
    customerPhone: '0934567890',
    petName: 'Mèo Persian',
    petType: 'Mèo',
    petAge: '4 năm',
    vaccineName: 'Vaccine 3 bệnh (FVRCP)',
    vaccineBatch: 'FVRCP-2024-078',
    doseNumber: 1,
    veterinarian: 'Dr. Lan',
    status: 'REMINDER_SENT',
    notes: 'Đã gửi nhắc hẹn qua SMS'
  },
];

export function AdminVaccinationPage() {
  const [vaccinations, setVaccinations] = useState(MOCK_VACCINATIONS);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredVaccinations = vaccinations.filter(v =>
    v.customerName.toLowerCase().includes(searchQuery.toLowerCase()) ||
    v.petName.toLowerCase().includes(searchQuery.toLowerCase()) ||
    v.vaccineName.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const completedToday = vaccinations.filter(v => v.status === 'COMPLETED').length;
  const scheduledToday = vaccinations.filter(v => v.status === 'SCHEDULED' || v.status === 'IN_PROGRESS').length;
  const reminderSent = vaccinations.filter(v => v.status === 'REMINDER_SENT').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Tiêm chủng</h1>
        <p className="text-[var(--text-secondary)]">Quản lý tiêm vaccine và lịch tiêm nhắc</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <Syringe className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{completedToday}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đã tiêm hôm nay</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100">
              <Calendar className="h-6 w-6 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-blue-600">{scheduledToday}</p>
              <p className="text-sm text-[var(--text-secondary)]">Lịch tiêm hôm nay</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-amber-100">
              <AlertCircle className="h-6 w-6 text-amber-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-amber-600">{reminderSent}</p>
              <p className="text-sm text-[var(--text-secondary)]">Chờ xác nhận</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-purple-100">
              <Syringe className="h-6 w-6 text-purple-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-purple-600">{vaccinations.length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Tổng ca tiêm</p>
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
              placeholder="Tìm theo tên, thú cưng, vaccine..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <Button><Plus className="mr-2 h-4 w-4" />Thêm lịch tiêm</Button>
        </CardContent>
      </Card>

      {/* Vaccination List */}
      <Card className="card">
        <CardContent className="p-0">
          <div className="divide-y divide-[var(--border-subtle)]">
            {filteredVaccinations.map(vaccination => (
              <div key={vaccination.id} className={cn(
                'p-5 transition-colors',
                vaccination.status === 'IN_PROGRESS' && 'bg-blue-50/50 border-l-4 border-l-blue-500',
                vaccination.status === 'COMPLETED' && 'bg-green-50/30'
              )}>
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-3 mb-2">
                      <Syringe className="h-5 w-5 text-[var(--color-primary)]" />
                      <h3 className="font-semibold text-[var(--text-primary)]">{vaccination.vaccineName}</h3>
                      <Badge variant="secondary" className="bg-[var(--bg-tertiary)]">Mũi {vaccination.doseNumber}</Badge>
                      <Badge className={
                        vaccination.status === 'COMPLETED' ? 'bg-green-100 text-green-700' :
                        vaccination.status === 'IN_PROGRESS' ? 'bg-blue-100 text-blue-700' :
                        vaccination.status === 'REMINDER_SENT' ? 'bg-amber-100 text-amber-700' :
                        'bg-gray-100 text-gray-700'
                      }>
                        {vaccination.status === 'COMPLETED' ? 'Hoàn tất' :
                         vaccination.status === 'IN_PROGRESS' ? 'Đang tiêm' :
                         vaccination.status === 'REMINDER_SENT' ? 'Chờ xác nhận' : 'Đã lên lịch'}
                      </Badge>
                    </div>

                    <div className="grid grid-cols-2 md:grid-cols-5 gap-4 text-sm">
                      <div>
                        <p className="text-[var(--text-tertiary)]">Thú cưng</p>
                        <p className="font-medium text-[var(--text-primary)]">{vaccination.petName}</p>
                        <p className="text-xs text-[var(--text-tertiary)]">{vaccination.petType} - {vaccination.petAge}</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Chủ nuôi</p>
                        <p className="text-[var(--text-primary)]">{vaccination.customerName}</p>
                        <p className="text-xs text-[var(--text-tertiary)]">{vaccination.customerPhone}</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Giờ tiêm</p>
                        <p className="text-[var(--text-primary)]">{vaccination.time}</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Bác sĩ</p>
                        <p className="text-[var(--text-primary)]">{vaccination.veterinarian}</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Lô vaccine</p>
                        <p className="text-[var(--text-primary)] font-mono text-xs">{vaccination.vaccineBatch}</p>
                      </div>
                    </div>

                    {vaccination.nextDoseDate && (
                      <p className="mt-2 text-sm text-[var(--text-secondary)]">
                        <span className="font-medium">Tiêm nhắc:</span> {vaccination.nextDoseDate}
                      </p>
                    )}

                    {vaccination.notes && (
                      <p className="mt-2 text-sm text-[var(--text-tertiary)] italic">
                        Ghi chú: {vaccination.notes}
                      </p>
                    )}
                  </div>

                  <div className="flex gap-2 ml-4">
                    {vaccination.status === 'SCHEDULED' && (
                      <Button size="sm" className="bg-blue-600 hover:bg-blue-700">Bắt đầu tiêm</Button>
                    )}
                    {vaccination.status === 'IN_PROGRESS' && (
                      <Button size="sm" className="bg-green-600 hover:bg-green-700">Hoàn tất tiêm</Button>
                    )}
                    {vaccination.status === 'REMINDER_SENT' && (
                      <Button size="sm" variant="outline">Gửi lại nhắc</Button>
                    )}
                    {vaccination.status === 'COMPLETED' && (
                      <Button size="sm" variant="outline">Xem chi tiết</Button>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
