import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Stethoscope, FileText, Plus, Eye, Pill } from 'lucide-react';
import { DetailModal, FormModal, InfoRow } from '../../components/ui/modal-templates';

type ExamStatus = 'WAITING' | 'IN_PROGRESS' | 'COMPLETED';

interface ExamRecord {
  id: string; examDate: string; customerName: string; customerPhone: string;
  petName: string; petType: string; petBreed: string; petAge: string;
  symptom: string; diagnosis?: string; prescription?: string;
  veterinarian: string; status: ExamStatus; weight?: string; temperature?: string;
}

const MOCK_EXAMS: ExamRecord[] = [
  { id: '1', examDate: '2026-09-18 09:30', customerName: 'Nguyễn Văn A', customerPhone: '0901234567', petName: 'Mèo Whiskas', petType: 'Mèo', petBreed: 'Scottish Fold', petAge: '2 năm', symptom: 'Ho liên tục, thở khó, bỏ ăn', diagnosis: 'Viêm đường hô hấp trên', prescription: 'Amoxicillin 50mg x 2 lần/ngày x 7 ngày\nThuốc ho tự nhiên\nNghỉ ngơi, giữ ấm', veterinarian: 'Dr. Minh', status: 'COMPLETED', weight: '3.5 kg', temperature: '39.2°C' },
  { id: '2', examDate: '2026-09-18 10:00', customerName: 'Trần Thị B', customerPhone: '0912345678', petName: 'Chó Alaska', petType: 'Chó', petBreed: 'Alaska Malamute', petAge: '1 năm', symptom: 'Nôn mật vàng, tiêu chảy', veterinarian: 'Dr. Lan', status: 'IN_PROGRESS', weight: '25 kg', temperature: '38.5°C' },
  { id: '3', examDate: '2026-09-18 11:00', customerName: 'Lê Văn C', customerPhone: '0923456789', petName: 'Chó Poodle', petType: 'Chó', petBreed: 'Poodle Toy', petAge: '3 năm', symptom: 'Gãi tai liên tục, tai có mùi', veterinarian: 'Dr. Minh', status: 'WAITING' },
];

function StatusBadgeLocal({ status }: { status: ExamStatus }) {
  const config: Record<ExamStatus, { label: string; className: string }> = { WAITING: { label: 'Chờ khám', className: 'bg-amber-100 text-amber-700' }, IN_PROGRESS: { label: 'Đang khám', className: 'bg-blue-100 text-blue-700' }, COMPLETED: { label: 'Hoàn tất', className: 'bg-green-100 text-green-700' } };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

export function AdminExamPage() {
  const [exams, setExams] = useState<ExamRecord[]>(MOCK_EXAMS);
  const [searchQuery, setSearchQuery] = useState('');
  const [viewExam, setViewExam] = useState<ExamRecord | null>(null);
  const [addExamOpen, setAddExamOpen] = useState(false);

  const filteredExams = exams.filter(e => e.customerName.toLowerCase().includes(searchQuery.toLowerCase()) || e.petName.toLowerCase().includes(searchQuery.toLowerCase()));

  const startExam = (id: string) => setExams(prev => prev.map(e => e.id === id ? { ...e, status: 'IN_PROGRESS' as ExamStatus } : e));
  const completeExam = (id: string) => setExams(prev => prev.map(e => e.id === id ? { ...e, status: 'COMPLETED' as ExamStatus } : e));

  const waitingCount = exams.filter(e => e.status === 'WAITING').length;
  const inProgressCount = exams.filter(e => e.status === 'IN_PROGRESS').length;
  const completedToday = exams.filter(e => e.status === 'COMPLETED').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Khám bệnh & EMR</h1>
        <p className="text-(--text-secondary)">Hồ sơ bệnh án điện tử và kê đơn</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-amber-100"><Stethoscope className="h-6 w-6 text-amber-600" /></div><div><p className="text-2xl font-semibold text-amber-600">{waitingCount}</p><p className="text-sm text-(--text-secondary)">Đang chờ khám</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-blue-100"><Stethoscope className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-semibold text-blue-600">{inProgressCount}</p><p className="text-sm text-(--text-secondary)">Đang khám</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-green-100"><FileText className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-semibold text-green-600">{completedToday}</p><p className="text-sm text-(--text-secondary)">Đã khám hôm nay</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-purple-100"><Pill className="h-6 w-6 text-purple-600" /></div><div><p className="text-2xl font-semibold text-purple-600">{exams.length}</p><p className="text-sm text-(--text-secondary)">Tổng ca hôm nay</p></div></CardContent></Card>
      </div>

      <Card className="card"><CardContent className="p-4 flex items-center justify-between"><div className="relative max-w-md"><Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" /><Input placeholder="Tìm bệnh nhân..." className="pl-10 input" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} /></div><Button onClick={() => setAddExamOpen(true)}><Plus className="mr-2 h-4 w-4" />Thêm lịch khám</Button></CardContent></Card>

      <Card className="card">
        <CardContent className="p-0">
          <div className="divide-y divide-(--border-subtle)">
            {filteredExams.map(exam => (
              <div key={exam.id} className={exam.status === 'IN_PROGRESS' ? 'bg-blue-50/50 border-l-4 border-l-blue-500 p-5' : 'p-5 hover:bg-(--bg-secondary)'}>
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-3 mb-2">
                      <span className="font-semibold text-(--text-primary)">{exam.petName}</span>
                      <Badge variant="secondary" className="bg-(--bg-tertiary)">{exam.petType}</Badge>
                      <StatusBadgeLocal status={exam.status} />
                    </div>
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm mb-3">
                      <div><p className="text-(--text-tertiary)">Chủ nuôi</p><p className="font-medium text-(--text-primary)">{exam.customerName}</p></div>
                      <div><p className="text-(--text-tertiary)">Giống / Tuổi</p><p className="text-(--text-primary)">{exam.petBreed} / {exam.petAge}</p></div>
                      <div><p className="text-(--text-tertiary)">Cân nặng / Nhiệt độ</p><p className="text-(--text-primary)">{exam.weight || '-'} / {exam.temperature || '-'}</p></div>
                      <div><p className="text-(--text-tertiary)">Bác sĩ</p><p className="text-(--text-primary)">{exam.veterinarian}</p></div>
                    </div>
                    <div className="mt-3"><p className="text-sm text-(--text-tertiary)">Triệu chứng:</p><p className="text-(--text-primary)">{exam.symptom}</p></div>
                    {exam.diagnosis && <div className="mt-2"><p className="text-sm text-(--text-tertiary)">Chẩn đoán:</p><p className="text-(--text-primary) font-medium">{exam.diagnosis}</p></div>}
                  </div>
                  <div className="flex gap-2 ml-4">
                    {exam.status === 'WAITING' && (<><Button size="sm" variant="outline" onClick={() => setViewExam(exam)}><Eye className="mr-1 h-4 w-4" /> Chi tiết</Button><Button size="sm" className="bg-blue-600 hover:bg-blue-700" onClick={() => startExam(exam.id)}>Bắt đầu khám</Button></>)}
                    {exam.status === 'IN_PROGRESS' && (<><Button size="sm" variant="outline" onClick={() => setViewExam(exam)}><Eye className="mr-1 h-4 w-4" /> Chi tiết</Button><Button size="sm" className="bg-green-600 hover:bg-green-700" onClick={() => completeExam(exam.id)}>Hoàn tất</Button></>)}
                    {exam.status === 'COMPLETED' && (<Button size="sm" variant="outline" onClick={() => setViewExam(exam)}><FileText className="mr-1 h-4 w-4" /> Xem hồ sơ</Button>)}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* MODALS */}
      <DetailModal open={!!viewExam} onOpenChange={(o) => !o && setViewExam(null)} title="Hồ sơ bệnh án" size="lg">
        {viewExam && (
          <div className="space-y-4">
            <div className="flex items-center justify-between pb-4 border-b border-(--color-border-light)">
              <div><h3 className="text-lg font-semibold">{viewExam.petName}</h3><p className="text-sm text-(--text-secondary)">{viewExam.customerName} - {viewExam.customerPhone}</p></div>
              <StatusBadgeLocal status={viewExam.status} />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-1"><InfoRow label="Giống" value={viewExam.petBreed} /><InfoRow label="Loài" value={viewExam.petType} /><InfoRow label="Tuổi" value={viewExam.petAge} /></div>
              <div className="space-y-1"><InfoRow label="Cân nặng" value={viewExam.weight || '-'} /><InfoRow label="Nhiệt độ" value={viewExam.temperature || '-'} /><InfoRow label="Bác sĩ" value={viewExam.veterinarian} /></div>
            </div>
            <div className="border-t border-(--color-border-light) pt-4">
              <p className="text-sm font-medium text-(--text-secondary) mb-2">Triệu chứng</p>
              <p className="text-sm text-(--text-primary) bg-(--color-surface-2) p-3 rounded-lg">{viewExam.symptom}</p>
            </div>
            {viewExam.diagnosis && (<div className="border-t border-(--color-border-light) pt-4"><p className="text-sm font-medium text-(--text-secondary) mb-2">Chẩn đoán</p><p className="text-sm text-(--text-primary) font-medium bg-green-50 p-3 rounded-lg">{viewExam.diagnosis}</p></div>)}
            {viewExam.prescription && (<div className="border-t border-(--color-border-light) pt-4"><p className="text-sm font-medium text-(--text-secondary) mb-2">Đơn thuốc</p><p className="text-sm text-(--text-primary) bg-blue-50 p-3 rounded-lg whitespace-pre-line">{viewExam.prescription}</p></div>)}
          </div>
        )}
      </DetailModal>

      <FormModal open={addExamOpen} onOpenChange={setAddExamOpen} title="Thêm lịch khám" description="Tạo lịch khám mới cho bệnh nhân" onSubmit={() => setAddExamOpen(false)} submitText="Tạo lịch khám" size="lg">
        <div className="grid grid-cols-2 gap-4">
          <div><label className="block text-sm font-medium mb-1.5">Tên thú cưng *</label><Input placeholder="VD: Mèo Whiskas" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Loại (Chó/Mèo)</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm focus:outline-none focus:ring-2 focus:ring-accent"><option>Mèo</option><option>Chó</option></select></div>
          <div><label className="block text-sm font-medium mb-1.5">Tên chủ nuôi *</label><Input placeholder="Nguyễn Văn A" /></div>
          <div><label className="block text-sm font-medium mb-1.5">SĐT chủ nuôi</label><Input placeholder="0901234567" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Giống</label><Input placeholder="VD: Scottish Fold" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Tuổi</label><Input placeholder="VD: 2 năm" /></div>
          <div className="col-span-2"><label className="block text-sm font-medium mb-1.5">Triệu chứng *</label><textarea className="w-full h-20 px-3 py-2 rounded-lg border border-(--color-border-default) bg-white text-sm focus:outline-none focus:ring-2 focus:ring-accent resize-none" placeholder="Mô tả triệu chứng..." /></div>
        </div>
      </FormModal>
    </div>
  );
}
