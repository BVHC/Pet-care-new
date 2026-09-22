import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Syringe, Plus, Calendar, AlertCircle, Eye, Check } from 'lucide-react';
import { cn } from '../../lib/utils';
import { ConfirmModal, DetailModal, FormModal, InfoRow } from '../../components/ui/modal-templates';

type VaccinationStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'REMINDER_SENT';

interface VaccinationRecord {
  id: string; date: string; time: string; customerName: string;
  customerPhone: string; petName: string; petType: string; petAge: string;
  vaccineName: string; vaccineBatch: string; doseNumber: number;
  nextDoseDate?: string; veterinarian: string; status: VaccinationStatus; notes?: string;
}

const MOCK_VACCINATIONS: VaccinationRecord[] = [
  { id: '1', date: '2026-09-18', time: '09:00', customerName: 'Nguyễn Văn A', customerPhone: '0901234567', petName: 'Mèo Whiskas', petType: 'Mèo', petAge: '2 năm', vaccineName: 'Vaccine dại (Rabies)', vaccineBatch: 'RB-2024-089', doseNumber: 1, nextDoseDate: '2027-09-18', veterinarian: 'Dr. Minh', status: 'COMPLETED', notes: 'Tiêm không có phản ứng phụ' },
  { id: '2', date: '2026-09-18', time: '10:30', customerName: 'Trần Thị B', customerPhone: '0912345678', petName: 'Chó Alaska', petType: 'Chó', petAge: '1 năm', vaccineName: 'Vaccine 5 bệnh (DHPP)', vaccineBatch: 'DHPP-2024-156', doseNumber: 2, nextDoseDate: '2026-10-02', veterinarian: 'Dr. Lan', status: 'IN_PROGRESS' },
  { id: '3', date: '2026-09-18', time: '14:00', customerName: 'Lê Văn C', customerPhone: '0923456789', petName: 'Chó Poodle', petType: 'Chó', petAge: '3 năm', vaccineName: 'Vaccine dại (Rabies)', vaccineBatch: 'RB-2024-089', doseNumber: 1, veterinarian: 'Dr. Minh', status: 'SCHEDULED' },
];

const STATUS_CONFIG: Record<VaccinationStatus, { label: string; className: string }> = {
  SCHEDULED: { label: 'Đã lên lịch', className: 'bg-gray-100 text-gray-700' },
  IN_PROGRESS: { label: 'Đang tiêm', className: 'bg-blue-100 text-blue-700' },
  COMPLETED: { label: 'Hoàn tất', className: 'bg-green-100 text-green-700' },
  REMINDER_SENT: { label: 'Chờ xác nhận', className: 'bg-amber-100 text-amber-700' },
};

export function AdminVaccinationPage() {
  const [vaccinations, setVaccinations] = useState<VaccinationRecord[]>(MOCK_VACCINATIONS);
  const [searchQuery, setSearchQuery] = useState('');
  const [viewVax, setViewVax] = useState<VaccinationRecord | null>(null);
  const [completeVax, setCompleteVax] = useState<VaccinationRecord | null>(null);
  const [addVaxOpen, setAddVaxOpen] = useState(false);

  const filteredVaccinations = vaccinations.filter(v => v.customerName.toLowerCase().includes(searchQuery.toLowerCase()) || v.petName.toLowerCase().includes(searchQuery.toLowerCase()) || v.vaccineName.toLowerCase().includes(searchQuery.toLowerCase()));

  const handleStartVax = (id: string) => setVaccinations(prev => prev.map(v => v.id === id ? { ...v, status: 'IN_PROGRESS' as VaccinationStatus } : v));
  const handleComplete = () => { if (!completeVax) return; setVaccinations(prev => prev.map(v => v.id === completeVax.id ? { ...v, status: 'COMPLETED' as VaccinationStatus } : v)); setCompleteVax(null); };

  const completedToday = vaccinations.filter(v => v.status === 'COMPLETED').length;
  const scheduledToday = vaccinations.filter(v => v.status === 'SCHEDULED' || v.status === 'IN_PROGRESS').length;
  const reminderSent = vaccinations.filter(v => v.status === 'REMINDER_SENT').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Tiêm chủng</h1>
        <p className="text-(--text-secondary)">Quản lý tiêm vaccine và lịch tiêm nhắc</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-green-100"><Syringe className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-semibold text-green-600">{completedToday}</p><p className="text-sm text-(--text-secondary)">Đã tiêm hôm nay</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-blue-100"><Calendar className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-semibold text-blue-600">{scheduledToday}</p><p className="text-sm text-(--text-secondary)">Lịch tiêm hôm nay</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-amber-100"><AlertCircle className="h-6 w-6 text-amber-600" /></div><div><p className="text-2xl font-semibold text-amber-600">{reminderSent}</p><p className="text-sm text-(--text-secondary)">Chờ xác nhận</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-purple-100"><Syringe className="h-6 w-6 text-purple-600" /></div><div><p className="text-2xl font-semibold text-purple-600">{vaccinations.length}</p><p className="text-sm text-(--text-secondary)">Tổng ca tiêm</p></div></CardContent></Card>
      </div>

      <Card className="card"><CardContent className="p-4 flex items-center justify-between">
        <div className="relative max-w-md"><Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" /><Input placeholder="Tìm theo tên, thú cưng, vaccine..." className="pl-10 input" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} /></div>
        <Button onClick={() => setAddVaxOpen(true)}><Plus className="mr-2 h-4 w-4" />Thêm lịch tiêm</Button>
      </CardContent></Card>

      <Card className="card"><CardContent className="p-0">
        <div className="divide-y divide-(--border-subtle)">
          {filteredVaccinations.map(vaccination => (
            <div key={vaccination.id} className={cn('p-5', vaccination.status === 'IN_PROGRESS' && 'bg-blue-50/50 border-l-4 border-l-blue-500', vaccination.status === 'COMPLETED' && 'bg-green-50/30')}>
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <div className="flex items-center gap-3 mb-2"><Syringe className="h-5 w-5 text-(--color-primary)" /><h3 className="font-semibold text-(--text-primary)">{vaccination.vaccineName}</h3><Badge variant="secondary" className="bg-(--bg-tertiary)">Mũi {vaccination.doseNumber}</Badge><Badge className={STATUS_CONFIG[vaccination.status].className}>{STATUS_CONFIG[vaccination.status].label}</Badge></div>
                  <div className="grid grid-cols-2 md:grid-cols-5 gap-4 text-sm">
                    <div><p className="text-(--text-tertiary)">Thú cưng</p><p className="font-medium text-(--text-primary)">{vaccination.petName}</p><p className="text-xs text-(--text-tertiary)">{vaccination.petType} - {vaccination.petAge}</p></div>
                    <div><p className="text-(--text-tertiary)">Chủ nuôi</p><p className="text-(--text-primary)">{vaccination.customerName}</p><p className="text-xs text-(--text-tertiary)">{vaccination.customerPhone}</p></div>
                    <div><p className="text-(--text-tertiary)">Giờ tiêm</p><p className="text-(--text-primary)">{vaccination.time}</p></div>
                    <div><p className="text-(--text-tertiary)">Bác sĩ</p><p className="text-(--text-primary)">{vaccination.veterinarian}</p></div>
                    <div><p className="text-(--text-tertiary)">Lô vaccine</p><p className="text-(--text-primary) font-mono text-xs">{vaccination.vaccineBatch}</p></div>
                  </div>
                  {vaccination.nextDoseDate && <p className="mt-2 text-sm text-(--text-secondary)"><span className="font-medium">Tiêm nhắc:</span> {vaccination.nextDoseDate}</p>}
                  {vaccination.notes && <p className="mt-2 text-sm text-(--text-tertiary) italic">Ghi chú: {vaccination.notes}</p>}
                </div>
                <div className="flex gap-2 ml-4">
                  <Button size="sm" variant="outline" onClick={() => setViewVax(vaccination)}><Eye className="h-4 w-4" /></Button>
                  {vaccination.status === 'SCHEDULED' && <Button size="sm" className="bg-blue-600 hover:bg-blue-700" onClick={() => handleStartVax(vaccination.id)}>Bắt đầu tiêm</Button>}
                  {vaccination.status === 'IN_PROGRESS' && <Button size="sm" className="bg-green-600 hover:bg-green-700" onClick={() => setCompleteVax(vaccination)}><Check className="mr-1 h-4 w-4" /> Hoàn tất</Button>}
                </div>
              </div>
            </div>
          ))}
        </div>
      </CardContent></Card>

      {/* MODALS */}
      <DetailModal open={!!viewVax} onOpenChange={(o) => !o && setViewVax(null)} title="Chi tiết tiêm chủng" size="md">
        {viewVax && (
          <div className="space-y-4">
            <div className="flex items-center justify-between pb-4 border-b border-(--color-border-light)"><div><h3 className="text-lg font-semibold">{viewVax.vaccineName}</h3><p className="text-sm text-(--text-secondary)">Mũi {viewVax.doseNumber}</p></div><Badge className={STATUS_CONFIG[viewVax.status].className}>{STATUS_CONFIG[viewVax.status].label}</Badge></div>
            <div className="space-y-1"><InfoRow label="Thú cưng" value={`${viewVax.petName} (${viewVax.petType})`} /><InfoRow label="Tuổi" value={viewVax.petAge} /><InfoRow label="Chủ nuôi" value={viewVax.customerName} /><InfoRow label="SĐT" value={viewVax.customerPhone} /><InfoRow label="Ngày tiêm" value={viewVax.date} /><InfoRow label="Giờ tiêm" value={viewVax.time} /><InfoRow label="Bác sĩ" value={viewVax.veterinarian} /><InfoRow label="Lô vaccine" value={viewVax.vaccineBatch} /><InfoRow label="Tiêm nhắc" value={viewVax.nextDoseDate || '-'} /></div>
            {viewVax.notes && <div className="border-t border-(--color-border-light) pt-4"><p className="text-sm font-medium text-(--text-secondary) mb-1">Ghi chú</p><p className="text-sm text-(--text-primary)">{viewVax.notes}</p></div>}
          </div>
        )}
      </DetailModal>

      <ConfirmModal open={!!completeVax} onOpenChange={(o) => !o && setCompleteVax(null)} type="success" title="Hoàn tất tiêm?" description={`Xác nhận hoàn tất tiêm "${completeVax?.vaccineName}" cho "${completeVax?.petName}"?`} confirmText="Hoàn tất tiêm" onConfirm={handleComplete} />

      <FormModal open={addVaxOpen} onOpenChange={setAddVaxOpen} title="Thêm lịch tiêm vaccine" description="Tạo lịch tiêm vaccine mới" onSubmit={() => setAddVaxOpen(false)} submitText="Tạo lịch tiêm" size="lg">
        <div className="grid grid-cols-2 gap-4">
          <div><label className="block text-sm font-medium mb-1.5">Tên thú cưng *</label><Input placeholder="Mèo Whiskas" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Loài</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm"><option>Chó</option><option>Mèo</option></select></div>
          <div><label className="block text-sm font-medium mb-1.5">Tên chủ nuôi *</label><Input placeholder="Nguyễn Văn A" /></div>
          <div><label className="block text-sm font-medium mb-1.5">SĐT</label><Input placeholder="0901234567" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Vaccine *</label><Input placeholder="Vaccine dại (Rabies)" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Lô vaccine</label><Input placeholder="RB-2024-089" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Số mũi</label><Input type="number" placeholder="1" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Bác sĩ</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm"><option>Dr. Minh</option><option>Dr. Lan</option></select></div>
          <div><label className="block text-sm font-medium mb-1.5">Ngày tiêm *</label><Input type="date" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Giờ tiêm *</label><Input type="time" /></div>
        </div>
      </FormModal>
    </div>
  );
}
