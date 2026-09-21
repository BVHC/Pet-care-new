import { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { Stethoscope, FileText, Pill, Thermometer, Heart, Save } from 'lucide-react';
import { cn } from '../../lib/utils';

interface ExamSession {
  id: string; customerName: string; petName: string; petType: string;
  petAge: string; service: string; status: 'WAITING' | 'IN_PROGRESS' | 'COMPLETED';
  checkedInAt: string;
}

const MOCK_SESSIONS: ExamSession[] = [
  { id: '1', customerName: 'Trần Thị B', petName: 'Chó Alaska', petType: 'Chó', petAge: '2 năm', service: 'Khám bệnh', status: 'IN_PROGRESS', checkedInAt: '10:30' },
  { id: '2', customerName: 'Phạm Thị D', petName: 'Mèo Persian', petType: 'Mèo', petAge: '1 năm', service: 'Tái khám', status: 'WAITING', checkedInAt: '11:15' },
];

export function AdminExamPage() {
  const [selectedSession, setSelectedSession] = useState<ExamSession | null>(MOCK_SESSIONS[0]);
  const [symptoms, setSymptoms] = useState('');
  const [diagnosis, setDiagnosis] = useState('');
  const [treatment, setTreatment] = useState('');

  const activeSessions = MOCK_SESSIONS.filter(s => s.status !== 'COMPLETED');

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Khám & Hồ sơ EMR</h1>
        <p className="text-gray-500">Hồ sơ bệnh án điện tử cho bác sĩ thú y</p>
      </div>

      <div className="grid grid-cols-12 gap-6">
        {/* Session List */}
        <div className="col-span-4">
          <Card>
            <CardHeader><CardTitle className="text-lg">Phiên khám</CardTitle></CardHeader>
            <CardContent>
              <div className="space-y-3">
                {activeSessions.map(session => (
                  <div
                    key={session.id}
                    onClick={() => setSelectedSession(session)}
                    className={cn(
                      'p-3 border rounded-lg cursor-pointer transition-all',
                      selectedSession?.id === session.id ? 'border-blue-500 bg-blue-50' : 'hover:border-gray-300'
                    )}
                  >
                    <div className="flex items-center justify-between mb-2">
                      <Badge className={session.status === 'IN_PROGRESS' ? 'bg-green-100 text-green-700' : 'bg-yellow-100 text-yellow-700'}>
                        {session.status === 'IN_PROGRESS' ? 'Đang khám' : 'Đang chờ'}
                      </Badge>
                      <span className="text-xs text-gray-500">Check-in: {session.checkedInAt}</span>
                    </div>
                    <p className="font-medium">{session.petName}</p>
                    <p className="text-sm text-gray-500">{session.customerName}</p>
                    <p className="text-xs text-blue-600 mt-1">{session.service}</p>
                  </div>
                ))}
                {activeSessions.length === 0 && (
                  <p className="text-center text-gray-500 py-4">Không có phiên khám nào</p>
                )}
              </div>
            </CardContent>
          </Card>
        </div>

        {/* Exam Form */}
        <div className="col-span-8">
          {selectedSession ? (
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between">
                  <div>
                    <CardTitle>Hồ sơ bệnh án</CardTitle>
                    <p className="text-sm text-gray-500 mt-1">{selectedSession.petName} • {selectedSession.petType} • {selectedSession.petAge}</p>
                  </div>
                  <Button className="bg-green-600 hover:bg-green-700"><Save className="mr-2 h-4 w-4" />Lưu & Hoàn tất</Button>
                </div>
              </CardHeader>
              <CardContent>
                <Tabs defaultValue="exam">
                  <TabsList className="grid w-full grid-cols-4">
                    <TabsTrigger value="exam">Khám bệnh</TabsTrigger>
                    <TabsTrigger value="vitals">Sinh tồ</TabsTrigger>
                    <TabsTrigger value="history">Lịch sử</TabsTrigger>
                    <TabsTrigger value="prescription">Đơn thuốc</TabsTrigger>
                  </TabsList>

                  <TabsContent value="exam" className="space-y-4 mt-4">
                    <div className="grid grid-cols-2 gap-4">
                      <div>
                        <label className="text-sm font-medium mb-2 block">Triệu chứng</label>
                        <textarea
                          className="w-full h-32 p-3 border rounded-lg text-sm"
                          placeholder="Mô tả triệu chứng..."
                          value={symptoms}
                          onChange={(e) => setSymptoms(e.target.value)}
                        />
                      </div>
                      <div>
                        <label className="text-sm font-medium mb-2 block">Chẩn đoán</label>
                        <textarea
                          className="w-full h-32 p-3 border rounded-lg text-sm"
                          placeholder="Kết luận chẩn đoán..."
                          value={diagnosis}
                          onChange={(e) => setDiagnosis(e.target.value)}
                        />
                      </div>
                    </div>
                    <div>
                      <label className="text-sm font-medium mb-2 block">Phác đồ điều trị</label>
                      <textarea
                        className="w-full h-24 p-3 border rounded-lg text-sm"
                        placeholder="Mô tả phác đồ điều trị..."
                        value={treatment}
                        onChange={(e) => setTreatment(e.target.value)}
                      />
                    </div>
                  </TabsContent>

                  <TabsContent value="vitals" className="space-y-4 mt-4">
                    <div className="grid grid-cols-2 gap-4">
                      <Card className="bg-blue-50 border-blue-200">
                        <CardContent className="p-4 flex items-center gap-4">
                          <Thermometer className="h-8 w-8 text-blue-600" />
                          <div>
                            <p className="text-sm text-gray-500">Nhiệt độ</p>
                            <p className="text-2xl font-bold">38.5°C</p>
                            <p className="text-xs text-green-600">Bình thường</p>
                          </div>
                        </CardContent>
                      </Card>
                      <Card className="bg-red-50 border-red-200">
                        <CardContent className="p-4 flex items-center gap-4">
                          <Heart className="h-8 w-8 text-red-600" />
                          <div>
                            <p className="text-sm text-gray-500">Nhịp tim</p>
                            <p className="text-2xl font-bold">120 bpm</p>
                            <p className="text-xs text-green-600">Bình thường</p>
                          </div>
                        </CardContent>
                      </Card>
                    </div>
                  </TabsContent>

                  <TabsContent value="history" className="mt-4">
                    <div className="space-y-4">
                      <Card><CardContent className="p-4">
                        <div className="flex items-center justify-between mb-2">
                          <div className="flex items-center gap-2"><FileText className="h-4 w-4 text-gray-400" /><span className="font-medium">2026-06-15</span></div>
                          <span className="text-sm text-gray-500">BS. Minh</span>
                        </div>
                        <p className="font-medium text-blue-600">Viêm da dị ứng</p>
                        <p className="text-sm text-gray-500 mt-1">Thuốc kháng histamin + kem bôi</p>
                      </CardContent></Card>
                      <Card><CardContent className="p-4">
                        <div className="flex items-center justify-between mb-2">
                          <div className="flex items-center gap-2"><FileText className="h-4 w-4 text-gray-400" /><span className="font-medium">2026-03-20</span></div>
                          <span className="text-sm text-gray-500">BS. Lan</span>
                        </div>
                        <p className="font-medium text-blue-600">Tiêm vaccine dại</p>
                        <p className="text-sm text-gray-500 mt-1">Vaccine dại Nobivac</p>
                      </CardContent></Card>
                    </div>
                  </TabsContent>

                  <TabsContent value="prescription" className="mt-4">
                    <div className="text-center py-8 text-gray-500">
                      <Pill className="h-12 w-12 mx-auto mb-4 opacity-50" />
                      <p>Tính năng đơn thuốc đang được phát triển</p>
                    </div>
                  </TabsContent>
                </Tabs>
              </CardContent>
            </Card>
          ) : (
            <Card>
              <CardContent className="p-8 text-center text-gray-500">
                <Stethoscope className="h-12 w-12 mx-auto mb-4 opacity-50" />
                <p>Chọn một phiên khám để bắt đầu</p>
              </CardContent>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
