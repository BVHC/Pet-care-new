import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Stethoscope, FileText, Plus, Eye, Pill, Thermometer } from 'lucide-react';
import { cn } from '../../lib/utils';

type ExamStatus = 'WAITING' | 'IN_PROGRESS' | 'COMPLETED';

interface ExamRecord {
  id: string;
  examDate: string;
  customerName: string;
  customerPhone: string;
  petName: string;
  petType: string;
  petBreed: string;
  petAge: string;
  symptom: string;
  diagnosis?: string;
  prescription?: string;
  veterinarian: string;
  status: ExamStatus;
  weight?: string;
  temperature?: string;
}

const MOCK_EXAMS: ExamRecord[] = [
  {
    id: '1',
    examDate: '2026-09-18 09:30',
    customerName: 'Nguyễn Văn A',
    customerPhone: '0901234567',
    petName: 'Mèo Whiskas',
    petType: 'Mèo',
    petBreed: 'Scottish Fold',
    petAge: '2 năm',
    symptom: 'Ho liên tục, thở khó, bỏ ăn',
    diagnosis: 'Viêm đường hô hấp trên',
    prescription: 'Amoxicillin 50mg x 2 lần/ngày x 7 ngày\nThuốc ho tự nhiên\nNghỉ ngơi, giữ ấm',
    veterinarian: 'Dr. Minh',
    status: 'COMPLETED',
    weight: '3.5 kg',
    temperature: '39.2°C'
  },
  {
    id: '2',
    examDate: '2026-09-18 10:00',
    customerName: 'Trần Thị B',
    customerPhone: '0912345678',
    petName: 'Chó Alaska',
    petType: 'Chó',
    petBreed: 'Alaska Malamute',
    petAge: '1 năm',
    symptom: 'Nôn mật vàng, tiêu chảy',
    veterinarian: 'Dr. Lan',
    status: 'IN_PROGRESS',
    weight: '25 kg',
    temperature: '38.5°C'
  },
  {
    id: '3',
    examDate: '2026-09-18 11:00',
    customerName: 'Lê Văn C',
    customerPhone: '0923456789',
    petName: 'Chó Poodle',
    petType: 'Chó',
    petBreed: 'Poodle Toy',
    petAge: '3 năm',
    symptom: 'Gãi tai liên tục, tai có mùi',
    veterinarian: 'Dr. Minh',
    status: 'WAITING'
  },
  {
    id: '4',
    examDate: '2026-09-18 14:00',
    customerName: 'Phạm Thị D',
    customerPhone: '0934567890',
    petName: 'Mèo Persian',
    petType: 'Mèo',
    petBreed: 'Persian',
    petAge: '4 năm',
    symptom: 'Mắt đổ ghèn, không mở được mắt',
    veterinarian: 'Dr. Lan',
    status: 'WAITING'
  },
];

export function AdminExamPage() {
  const [exams, setExams] = useState(MOCK_EXAMS);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedExam, setSelectedExam] = useState<ExamRecord | null>(null);

  const filteredExams = exams.filter(e =>
    e.customerName.toLowerCase().includes(searchQuery.toLowerCase()) ||
    e.petName.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const startExam = (id: string) => {
    setExams(prev => prev.map(e => e.id === id ? { ...e, status: 'IN_PROGRESS' as ExamStatus } : e));
  };

  const completeExam = (id: string) => {
    setExams(prev => prev.map(e => e.id === id ? { ...e, status: 'COMPLETED' as ExamStatus } : e));
  };

  const waitingCount = exams.filter(e => e.status === 'WAITING').length;
  const inProgressCount = exams.filter(e => e.status === 'IN_PROGRESS').length;
  const completedToday = exams.filter(e => e.status === 'COMPLETED').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Khám bệnh & EMR</h1>
        <p className="text-[var(--text-secondary)]">Hồ sơ bệnh án điện tử và kê đơn</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-amber-100">
              <Stethoscope className="h-6 w-6 text-amber-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-amber-600">{waitingCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đang chờ khám</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100">
              <Stethoscope className="h-6 w-6 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-blue-600">{inProgressCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đang khám</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <FileText className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{completedToday}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đã khám hôm nay</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-purple-100">
              <Pill className="h-6 w-6 text-purple-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-purple-600">{exams.length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Tổng ca hôm nay</p>
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
              placeholder="Tìm bệnh nhân..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <Button><Plus className="mr-2 h-4 w-4" />Thêm lịch khám</Button>
        </CardContent>
      </Card>

      {/* Exam List */}
      <Card className="card">
        <CardContent className="p-0">
          <div className="divide-y divide-[var(--border-subtle)]">
            {filteredExams.map(exam => (
              <div key={exam.id} className={cn(
                'p-5 transition-colors',
                exam.status === 'IN_PROGRESS' && 'bg-blue-50/50 border-l-4 border-l-blue-500',
                exam.status === 'WAITING' && 'hover:bg-[var(--bg-secondary)]'
              )}>
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-3 mb-2">
                      <h3 className="font-semibold text-[var(--text-primary)]">{exam.petName}</h3>
                      <Badge variant="secondary" className="bg-[var(--bg-tertiary)]">{exam.petType}</Badge>
                      <Badge className={
                        exam.status === 'WAITING' ? 'bg-amber-100 text-amber-700' :
                        exam.status === 'IN_PROGRESS' ? 'bg-blue-100 text-blue-700' :
                        'bg-green-100 text-green-700'
                      }>
                        {exam.status === 'WAITING' ? 'Chờ khám' :
                         exam.status === 'IN_PROGRESS' ? 'Đang khám' : 'Hoàn tất'}
                      </Badge>
                    </div>

                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
                      <div>
                        <p className="text-[var(--text-tertiary)]">Chủ nuôi</p>
                        <p className="font-medium text-[var(--text-primary)]">{exam.customerName}</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Giống / Tuổi</p>
                        <p className="text-[var(--text-primary)]">{exam.petBreed} / {exam.petAge}</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Cân nặng / Nhiệt độ</p>
                        <p className="text-[var(--text-primary)]">{exam.weight || '-'} / {exam.temperature || '-'}</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Bác sĩ</p>
                        <p className="text-[var(--text-primary)]">{exam.veterinarian}</p>
                      </div>
                    </div>

                    <div className="mt-3">
                      <p className="text-sm text-[var(--text-tertiary)]">Triệu chứng:</p>
                      <p className="text-[var(--text-primary)]">{exam.symptom}</p>
                    </div>

                    {exam.diagnosis && (
                      <div className="mt-2">
                        <p className="text-sm text-[var(--text-tertiary)]">Chẩn đoán:</p>
                        <p className="text-[var(--text-primary)] font-medium">{exam.diagnosis}</p>
                      </div>
                    )}
                  </div>

                  <div className="flex gap-2 ml-4">
                    {exam.status === 'WAITING' && (
                      <Button size="sm" className="bg-blue-600 hover:bg-blue-700" onClick={() => startExam(exam.id)}>
                        Bắt đầu khám
                      </Button>
                    )}
                    {exam.status === 'IN_PROGRESS' && (
                      <>
                        <Button size="sm" variant="outline" onClick={() => setSelectedExam(exam)}>
                          <Eye className="mr-1 h-4 w-4" /> Chi tiết
                        </Button>
                        <Button size="sm" className="bg-green-600 hover:bg-green-700" onClick={() => completeExam(exam.id)}>
                          Hoàn tất
                        </Button>
                      </>
                    )}
                    {exam.status === 'COMPLETED' && (
                      <Button size="sm" variant="outline" onClick={() => setSelectedExam(exam)}>
                        <FileText className="mr-1 h-4 w-4" /> Xem hồ sơ
                      </Button>
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
