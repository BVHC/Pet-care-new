import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Scissors, Plus, Clock, Check, Sparkles, Star, ClipboardList, Eye } from 'lucide-react';
import { DetailModal, FormModal, InfoRow } from '../../components/ui/modal-templates';

type GroomingStatus = 'WAITING' | 'IN_PROGRESS' | 'COMPLETED';

interface GroomingRecord {
  id: string; checkInTime: string; customerName: string; customerPhone: string;
  petName: string; petType: string; petBreed: string; service: string;
  serviceType: 'BASIC' | 'PREMIUM' | 'SPA'; duration: number; groomer: string;
  status: GroomingStatus; notes?: string;
}

const MOCK_GROOMING: GroomingRecord[] = [
  { id: '1', checkInTime: '08:30', customerName: 'Nguyễn Văn A', customerPhone: '0901234567', petName: 'Mèo Whiskas', petType: 'Mèo', petBreed: 'Scottish Fold', service: 'Cắt tỉa lông toàn thân', serviceType: 'SPA', duration: 120, groomer: 'Hương', status: 'IN_PROGRESS', notes: 'Lông bị rối, cần chải kỹ trước' },
  { id: '2', checkInTime: '09:00', customerName: 'Trần Thị B', customerPhone: '0912345678', petName: 'Chó Alaska', petType: 'Chó', petBreed: 'Alaska', service: 'Tắm + Sấy', serviceType: 'BASIC', duration: 60, groomer: 'Minh', status: 'IN_PROGRESS' },
  { id: '3', checkInTime: '10:00', customerName: 'Lê Văn C', customerPhone: '0923456789', petName: 'Chó Poodle', petType: 'Chó', petBreed: 'Poodle', service: 'Cắt móng + Vệ sinh tai', serviceType: 'BASIC', duration: 30, groomer: 'Hương', status: 'WAITING' },
  { id: '4', checkInTime: '08:00', customerName: 'Phạm Thị D', customerPhone: '0934567890', petName: 'Mèo Persian', petType: 'Mèo', petBreed: 'Persian', service: 'Tắm Spa thảo dược', serviceType: 'SPA', duration: 90, groomer: 'Minh', status: 'COMPLETED', notes: 'Khách hàng rất hài lòng' },
];

function StatusBadgeLocal({ status }: { status: GroomingStatus }) {
  const config: Record<GroomingStatus, { label: string; className: string }> = { WAITING: { label: 'Chờ', className: 'bg-amber-100 text-amber-700' }, IN_PROGRESS: { label: 'Đang làm', className: 'bg-blue-100 text-blue-700' }, COMPLETED: { label: 'Hoàn tất', className: 'bg-green-100 text-green-700' } };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function ServiceTypeBadge({ type }: { type: string }) {
  if (type === 'SPA') return <Badge variant="secondary" className="bg-purple-100 text-purple-700 inline-flex items-center gap-1"><Sparkles className="h-3 w-3" /> Spa</Badge>;
  if (type === 'PREMIUM') return <Badge variant="secondary" className="bg-amber-100 text-amber-700 inline-flex items-center gap-1"><Star className="h-3 w-3 fill-amber-500" /> Premium</Badge>;
  return <Badge variant="secondary" className="bg-blue-100 text-blue-700 inline-flex items-center gap-1"><ClipboardList className="h-3 w-3" /> Basic</Badge>;
}

export function AdminGroomingPage() {
  const [grooming, setGrooming] = useState<GroomingRecord[]>(MOCK_GROOMING);
  const [searchQuery, setSearchQuery] = useState('');
  const [viewRecord, setViewRecord] = useState<GroomingRecord | null>(null);
  const [addRecordOpen, setAddRecordOpen] = useState(false);

  const filteredGrooming = grooming.filter(g => g.customerName.toLowerCase().includes(searchQuery.toLowerCase()) || g.petName.toLowerCase().includes(searchQuery.toLowerCase()));

  const updateStatus = (id: string, status: GroomingStatus) => setGrooming(prev => prev.map(g => g.id === id ? { ...g, status } : g));

  const waitingCount = grooming.filter(g => g.status === 'WAITING').length;
  const inProgressCount = grooming.filter(g => g.status === 'IN_PROGRESS').length;
  const completedToday = grooming.filter(g => g.status === 'COMPLETED').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Grooming Spa</h1>
        <p className="text-(--text-secondary)">Quản lý dịch vụ spa và chăm sóc thú cưng</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-amber-100"><Clock className="h-6 w-6 text-amber-600" /></div><div><p className="text-2xl font-semibold text-amber-600">{waitingCount}</p><p className="text-sm text-(--text-secondary)">Đang chờ</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-blue-100"><Scissors className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-semibold text-blue-600">{inProgressCount}</p><p className="text-sm text-(--text-secondary)">Đang làm</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-green-100"><Check className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-semibold text-green-600">{completedToday}</p><p className="text-sm text-(--text-secondary)">Hoàn tất hôm nay</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-purple-100"><Scissors className="h-6 w-6 text-purple-600" /></div><div><p className="text-2xl font-semibold text-purple-600">{grooming.length}</p><p className="text-sm text-(--text-secondary)">Tổng ca hôm nay</p></div></CardContent></Card>
      </div>

      <Card className="card"><CardContent className="p-4 flex items-center justify-between"><div className="relative max-w-md"><Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" /><Input placeholder="Tìm thú cưng, khách hàng..." className="pl-10 input" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} /></div><Button onClick={() => setAddRecordOpen(true)}><Plus className="mr-2 h-4 w-4" />Thêm lịch grooming</Button></CardContent></Card>

      <Card className="card">
        <CardContent className="p-0">
          <div className="divide-y divide-(--border-subtle)">
            {filteredGrooming.map(g => (
              <div key={g.id} className={g.status === 'IN_PROGRESS' ? 'bg-blue-50/50 border-l-4 border-l-blue-500 p-5' : g.status === 'COMPLETED' ? 'bg-green-50/30 opacity-75 p-5' : 'p-5 hover:bg-(--bg-secondary)'}>
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-3 mb-2">
                      <div className={`w-12 h-12 rounded-xl flex items-center justify-center ${g.serviceType === 'SPA' ? 'bg-purple-100' : g.serviceType === 'PREMIUM' ? 'bg-amber-100' : 'bg-blue-100'}`}>
                        <Scissors className={`h-6 w-6 ${g.serviceType === 'SPA' ? 'text-purple-600' : g.serviceType === 'PREMIUM' ? 'text-amber-600' : 'text-blue-600'}`} />
                      </div>
                      <div>
                        <span className="font-semibold text-(--text-primary)">{g.petName}</span>
                        <p className="text-sm text-(--text-secondary)">{g.customerName} - {g.customerPhone}</p>
                      </div>
                      <StatusBadgeLocal status={g.status} />
                      <ServiceTypeBadge type={g.serviceType} />
                    </div>
                    <div className="grid grid-cols-2 md:grid-cols-5 gap-4 text-sm">
                      <div><p className="text-(--text-tertiary)">Dịch vụ</p><p className="font-medium text-(--text-primary)">{g.service}</p></div>
                      <div><p className="text-(--text-tertiary)">Giống / Loại</p><p className="text-(--text-primary)">{g.petBreed} ({g.petType})</p></div>
                      <div><p className="text-(--text-tertiary)">Thời gian</p><p className="text-(--text-primary)">{g.duration} phút</p></div>
                      <div><p className="text-(--text-tertiary)">Groomer</p><p className="text-(--text-primary)">{g.groomer}</p></div>
                      <div><p className="text-(--text-tertiary)">Check-in</p><p className="text-(--text-primary)">{g.checkInTime}</p></div>
                    </div>
                    {g.notes && <p className="mt-2 text-sm text-(--text-tertiary) italic">Ghi chú: {g.notes}</p>}
                  </div>
                  <div className="flex gap-2 ml-4">
                    {g.status === 'WAITING' && <Button size="sm" className="bg-blue-600 hover:bg-blue-700" onClick={() => updateStatus(g.id, 'IN_PROGRESS')}>Bắt đầu</Button>}
                    {g.status === 'IN_PROGRESS' && <Button size="sm" className="bg-green-600 hover:bg-green-700" onClick={() => updateStatus(g.id, 'COMPLETED')}>Hoàn tất</Button>}
                    <Button size="sm" variant="outline" onClick={() => setViewRecord(g)}><Eye className="mr-1 h-4 w-4" /> Chi tiết</Button>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* MODALS */}
      <DetailModal open={!!viewRecord} onOpenChange={(o) => !o && setViewRecord(null)} title="Chi tiết dịch vụ grooming" size="md">
        {viewRecord && (
          <div className="space-y-4">
            <div className="flex items-center justify-between pb-4 border-b border-(--color-border-light)">
              <div className="flex items-center gap-3">
                <div className={`w-12 h-12 rounded-xl flex items-center justify-center ${viewRecord.serviceType === 'SPA' ? 'bg-purple-100' : viewRecord.serviceType === 'PREMIUM' ? 'bg-amber-100' : 'bg-blue-100'}`}>
                  <Scissors className={`h-6 w-6 ${viewRecord.serviceType === 'SPA' ? 'text-purple-600' : viewRecord.serviceType === 'PREMIUM' ? 'text-amber-600' : 'text-blue-600'}`} />
                </div>
                <div><h3 className="text-lg font-semibold">{viewRecord.petName}</h3><p className="text-sm text-(--text-secondary)">{viewRecord.service}</p></div>
              </div>
              <StatusBadgeLocal status={viewRecord.status} />
            </div>
            <div className="space-y-1"><InfoRow label="Khách hàng" value={viewRecord.customerName} /><InfoRow label="SĐT" value={viewRecord.customerPhone} /><InfoRow label="Giống" value={viewRecord.petBreed} /><InfoRow label="Loài" value={viewRecord.petType} /><InfoRow label="Loại dịch vụ" value={<ServiceTypeBadge type={viewRecord.serviceType} />} /><InfoRow label="Thời gian" value={`${viewRecord.duration} phút`} /><InfoRow label="Groomer" value={viewRecord.groomer} /><InfoRow label="Check-in" value={viewRecord.checkInTime} /></div>
            {viewRecord.notes && <div className="border-t border-(--color-border-light) pt-4"><p className="text-sm font-medium text-(--text-secondary) mb-1">Ghi chú</p><p className="text-sm text-(--text-primary) italic">{viewRecord.notes}</p></div>}
          </div>
        )}
      </DetailModal>

      <FormModal open={addRecordOpen} onOpenChange={setAddRecordOpen} title="Thêm lịch grooming" description="Tạo lịch grooming mới cho thú cưng" onSubmit={() => setAddRecordOpen(false)} submitText="Tạo lịch" size="lg">
        <div className="grid grid-cols-2 gap-4">
          <div><label className="block text-sm font-medium mb-1.5">Tên thú cưng *</label><Input placeholder="VD: Mèo Whiskas" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Loại (Chó/Mèo)</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm focus:outline-none focus:ring-2 focus:ring-accent"><option>Chó</option><option>Mèo</option></select></div>
          <div><label className="block text-sm font-medium mb-1.5">Tên chủ nuôi *</label><Input placeholder="Nguyễn Văn A" /></div>
          <div><label className="block text-sm font-medium mb-1.5">SĐT chủ nuôi</label><Input placeholder="0901234567" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Giống</label><Input placeholder="VD: Scottish Fold" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Loại dịch vụ</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm focus:outline-none focus:ring-2 focus:ring-accent"><option value="BASIC">Basic</option><option value="PREMIUM">Premium</option><option value="SPA">Spa</option></select></div>
          <div><label className="block text-sm font-medium mb-1.5">Dịch vụ</label><Input placeholder="VD: Cắt tỉa lông toàn thân" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Thời gian (phút)</label><Input type="number" placeholder="60" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Groomer phụ trách</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm focus:outline-none focus:ring-2 focus:ring-accent"><option>Hương</option><option>Minh</option></select></div>
          <div><label className="block text-sm font-medium mb-1.5">Giờ check-in</label><Input type="time" /></div>
          <div className="col-span-2"><label className="block text-sm font-medium mb-1.5">Ghi chú</label><textarea className="w-full h-20 px-3 py-2 rounded-lg border border-(--color-border-default) bg-white text-sm focus:outline-none focus:ring-2 focus:ring-accent resize-none" placeholder="Ghi chú thể trạng, yêu cầu đặc biệt..." /></div>
        </div>
      </FormModal>
    </div>
  );
}
