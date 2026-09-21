import { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { User, Clock, Check, Plus } from 'lucide-react';
import { cn } from '../../lib/utils';

type QueueStatus = 'WAITING' | 'CALLED' | 'IN_SERVICE' | 'COMPLETED' | 'NO_SHOW';

interface QueueItem {
  id: string; number: number; customerName: string; petName: string;
  service: string; serviceType: string; checkInTime: string; status: QueueStatus; calledAt?: string;
}

const MOCK_QUEUE: QueueItem[] = [
  { id: '1', number: 1, customerName: 'Nguyễn Văn A', petName: 'Mèo Whiskas', service: 'Khám tổng quát', serviceType: 'CLINIC', checkInTime: '08:30', status: 'IN_SERVICE' },
  { id: '2', number: 2, customerName: 'Trần Thị B', petName: 'Chó Alaska', service: 'Tiêm vaccine', serviceType: 'VACCINE', checkInTime: '08:45', status: 'CALLED', calledAt: '09:15' },
  { id: '3', number: 3, customerName: 'Lê Văn C', petName: 'Chó Poodle', service: 'Grooming cơ bản', serviceType: 'GROOMING', checkInTime: '09:00', status: 'WAITING' },
  { id: '4', number: 4, customerName: 'Phạm Thị D', petName: 'Mèo Persian', service: 'Tái khám', serviceType: 'CLINIC', checkInTime: '09:15', status: 'WAITING' },
];

export function AdminQueuePage() {
  const [queue, setQueue] = useState(MOCK_QUEUE);

  const callNext = (id: string) => {
    setQueue(prev => prev.map(item => item.id === id ? { ...item, status: 'CALLED' as QueueStatus, calledAt: new Date().toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) } : item));
  };

  const startService = (id: string) => {
    setQueue(prev => prev.map(item => item.id === id ? { ...item, status: 'IN_SERVICE' as QueueStatus } : item));
  };

  const complete = (id: string) => {
    setQueue(prev => prev.map(item => item.id === id ? { ...item, status: 'COMPLETED' as QueueStatus } : item));
  };

  const waitingCount = queue.filter(q => q.status === 'WAITING').length;
  const inServiceCount = queue.filter(q => q.status === 'IN_SERVICE').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Hàng chờ walk-in</h1>
        <p className="text-[var(--text-secondary)]">Quản lý khách hàng đến trực tiếp không có lịch hẹn</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-3 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 text-center">
            <p className="text-4xl font-semibold text-amber-600">{waitingCount}</p>
            <p className="text-sm text-[var(--text-secondary)] mt-1">Đang chờ</p>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 text-center">
            <p className="text-4xl font-semibold text-green-600">{inServiceCount}</p>
            <p className="text-sm text-[var(--text-secondary)] mt-1">Đang phục vụ</p>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 text-center">
            <p className="text-4xl font-semibold text-[var(--text-primary)]">{queue.length}</p>
            <p className="text-sm text-[var(--text-secondary)] mt-1">Tổng số</p>
          </CardContent>
        </Card>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Queue List */}
        <div className="lg:col-span-2">
          <Card className="card">
            <CardHeader className="flex flex-row items-center justify-between border-b border-[var(--border-color)]">
              <CardTitle className="text-base font-semibold">Danh sách hàng đợi</CardTitle>
              <Button size="sm"><Plus className="h-4 w-4 mr-1" />Thêm vào hàng đợi</Button>
            </CardHeader>
            <CardContent className="p-0">
              <div className="divide-y divide-[var(--border-subtle)]">
                {queue.filter(q => q.status !== 'COMPLETED').map((item) => (
                  <div key={item.id} className={cn(
                    'flex items-center justify-between p-4 transition-colors',
                    item.status === 'IN_SERVICE' && 'bg-green-50 border-l-4 border-l-green-500',
                    item.status === 'CALLED' && 'bg-amber-50 border-l-4 border-l-amber-500'
                  )}>
                    <div className="flex items-center gap-4">
                      <div className={cn(
                        'w-12 h-12 rounded-full flex items-center justify-center font-semibold text-white',
                        item.status === 'IN_SERVICE' ? 'bg-green-500' : item.status === 'CALLED' ? 'bg-amber-500' : 'bg-gray-400'
                      )}>
                        {item.number}
                      </div>
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="font-medium text-[var(--text-primary)]">{item.customerName}</span>
                          <Badge variant="secondary" className="text-xs bg-[var(--bg-tertiary)]">{item.serviceType}</Badge>
                        </div>
                        <p className="text-sm text-[var(--text-secondary)]">{item.petName} • {item.service}</p>
                        <p className="text-xs text-[var(--text-tertiary)]">Check-in: {item.checkInTime}{item.calledAt && ` • Gọi: ${item.calledAt}`}</p>
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      {item.status === 'WAITING' && <Button size="sm" onClick={() => callNext(item.id)}>Gọi</Button>}
                      {item.status === 'CALLED' && <Button size="sm" className="bg-green-600 hover:bg-green-700" onClick={() => startService(item.id)}>Phục vụ</Button>}
                      {item.status === 'IN_SERVICE' && <Button size="sm" className="bg-blue-600 hover:bg-blue-700" onClick={() => complete(item.id)}><Check className="mr-1 h-3 w-3" /> Xong</Button>}
                    </div>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </div>

        {/* Now Serving */}
        <div>
          <Card className="card border-green-200 bg-gradient-to-b from-green-50 to-white">
            <CardHeader>
              <CardTitle className="text-base font-semibold text-green-700">Đang phục vụ</CardTitle>
            </CardHeader>
            <CardContent className="text-center">
              {queue.filter(q => q.status === 'IN_SERVICE').map(item => (
                <div key={item.id} className="py-4">
                  <div className="w-20 h-20 rounded-full bg-green-500 flex items-center justify-center mx-auto mb-4 shadow-lg">
                    <span className="text-3xl font-bold text-white">{item.number}</span>
                  </div>
                  <p className="font-medium text-lg text-[var(--text-primary)]">{item.customerName}</p>
                  <p className="text-[var(--text-secondary)]">{item.service}</p>
                </div>
              ))}
              {queue.filter(q => q.status === 'IN_SERVICE').length === 0 && (
                <div className="py-12 text-[var(--text-tertiary)]">
                  <User className="h-16 w-16 mx-auto mb-4 opacity-30" />
                  <p>Không có ai đang được phục vụ</p>
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}
