import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { User, Check, Plus, Eye } from 'lucide-react';
import { cn } from '../../lib/utils';
import { ConfirmModal, DetailModal, FormModal, InfoRow } from '../../components/ui/modal-templates';

type QueueStatus = 'WAITING' | 'CALLED' | 'IN_SERVICE' | 'COMPLETED' | 'NO_SHOW';

interface QueueItem {
  id: string; number: number; customerName: string; petName: string;
  service: string; serviceType: string; checkInTime: string; status: QueueStatus; calledAt?: string;
}

const MOCK_QUEUE: QueueItem[] = [
  { id: '1', number: 1, customerName: 'Nguyễn Văn A', petName: 'Mèo Whiskas', service: 'Khám tổng quát', serviceType: 'CLINIC', checkInTime: '08:30', status: 'IN_SERVICE' },
  { id: '2', number: 2, customerName: 'Trần Thị B', petName: 'Chó Alaska', service: 'Tiêm vaccine', serviceType: 'VACCINE', checkInTime: '08:45', status: 'CALLED', calledAt: '09:15' },
  { id: '3', number: 3, customerName: 'Lê Văn C', petName: 'Chó Poodle', service: 'Grooming cơ bản', serviceType: 'GROOMING', checkInTime: '09:00', status: 'WAITING' },
];

const STATUS_CONFIG: Record<QueueStatus, { label: string; className: string }> = {
  WAITING: { label: 'Đang chờ', className: 'bg-gray-100 text-gray-700' },
  CALLED: { label: 'Đã gọi', className: 'bg-amber-100 text-amber-700' },
  IN_SERVICE: { label: 'Đang phục vụ', className: 'bg-green-100 text-green-700' },
  COMPLETED: { label: 'Hoàn tất', className: 'bg-blue-100 text-blue-700' },
  NO_SHOW: { label: 'Không đến', className: 'bg-red-100 text-red-700' },
};

export function AdminQueuePage() {
  const [queue, setQueue] = useState<QueueItem[]>(MOCK_QUEUE);
  const [viewItem, setViewItem] = useState<QueueItem | null>(null);
  const [noShowItem, setNoShowItem] = useState<QueueItem | null>(null);
  const [addQueueOpen, setAddQueueOpen] = useState(false);

  const callNext = (id: string) => setQueue(prev => prev.map(item => item.id === id ? { ...item, status: 'CALLED' as QueueStatus, calledAt: new Date().toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) } : item));
  const startService = (id: string) => setQueue(prev => prev.map(item => item.id === id ? { ...item, status: 'IN_SERVICE' as QueueStatus } : item));
  const complete = (id: string) => setQueue(prev => prev.map(item => item.id === id ? { ...item, status: 'COMPLETED' as QueueStatus } : item));
  const handleNoShow = () => { if (!noShowItem) return; setQueue(prev => prev.map(item => item.id === noShowItem.id ? { ...item, status: 'NO_SHOW' as QueueStatus } : item)); setNoShowItem(null); };

  const waitingCount = queue.filter(q => q.status === 'WAITING').length;
  const inServiceCount = queue.filter(q => q.status === 'IN_SERVICE').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Hàng chờ walk-in</h1>
        <p className="text-(--text-secondary)">Quản lý khách hàng đến trực tiếp không có lịch hẹn</p>
      </div>

      <div className="grid grid-cols-3 gap-4">
        <Card className="card-kpi"><CardContent className="p-5 text-center"><p className="text-4xl font-semibold text-amber-600">{waitingCount}</p><p className="text-sm text-(--text-secondary) mt-1">Đang chờ</p></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 text-center"><p className="text-4xl font-semibold text-green-600">{inServiceCount}</p><p className="text-sm text-(--text-secondary) mt-1">Đang phục vụ</p></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 text-center"><p className="text-4xl font-semibold text-(--text-primary)">{queue.length}</p><p className="text-sm text-(--text-secondary) mt-1">Tổng số</p></CardContent></Card>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2">
          <Card className="card">
            <CardContent className="p-0">
              <div className="divide-y divide-(--border-subtle)">
                {queue.filter(q => q.status !== 'COMPLETED').map((item) => (
                  <div key={item.id} className={cn('flex items-center justify-between p-4', item.status === 'IN_SERVICE' && 'bg-green-50 border-l-4 border-l-green-500', item.status === 'CALLED' && 'bg-amber-50 border-l-4 border-l-amber-500')}>
                    <div className="flex items-center gap-4">
                      <div className={cn('w-12 h-12 rounded-full flex items-center justify-center font-semibold text-white', item.status === 'IN_SERVICE' ? 'bg-green-500' : item.status === 'CALLED' ? 'bg-amber-500' : 'bg-gray-400')}>{item.number}</div>
                      <div>
                        <div className="flex items-center gap-2"><span className="font-medium text-(--text-primary)">{item.customerName}</span><Badge variant="secondary" className="text-xs bg-(--bg-tertiary)">{item.serviceType}</Badge></div>
                        <p className="text-sm text-(--text-secondary)">{item.petName} • {item.service}</p>
                        <p className="text-xs text-(--text-tertiary)">Check-in: {item.checkInTime}{item.calledAt && ` • Gọi: ${item.calledAt}`}</p>
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      <Button size="sm" variant="outline" onClick={() => setViewItem(item)}><Eye className="h-4 w-4" /></Button>
                      {item.status === 'WAITING' && <><Button size="sm" onClick={() => callNext(item.id)}>Gọi</Button><Button size="sm" variant="outline" className="text-red-500" onClick={() => setNoShowItem(item)}>Vắng</Button></>}
                      {item.status === 'CALLED' && <Button size="sm" className="bg-green-600 hover:bg-green-700" onClick={() => startService(item.id)}>Phục vụ</Button>}
                      {item.status === 'IN_SERVICE' && <Button size="sm" className="bg-blue-600 hover:bg-blue-700" onClick={() => complete(item.id)}><Check className="mr-1 h-3 w-3" /> Xong</Button>}
                    </div>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </div>

        <div>
          <Card className="card border-green-200 bg-linear-to-b from-green-50 to-white">
            <CardContent className="text-center">
              {queue.filter(q => q.status === 'IN_SERVICE').map(item => (
                <div key={item.id} className="py-4">
                  <div className="w-20 h-20 rounded-full bg-green-500 flex items-center justify-center mx-auto mb-4 shadow-lg"><span className="text-3xl font-bold text-white">{item.number}</span></div>
                  <p className="font-medium text-lg text-(--text-primary)">{item.customerName}</p>
                  <p className="text-(--text-secondary)">{item.service}</p>
                </div>
              ))}
              {queue.filter(q => q.status === 'IN_SERVICE').length === 0 && (
                <div className="py-12 text-(--text-tertiary)"><User className="h-16 w-16 mx-auto mb-4 opacity-30" /><p>Không có ai đang được phục vụ</p></div>
              )}
            </CardContent>
          </Card>
          <Button className="w-full mt-4" onClick={() => setAddQueueOpen(true)}><Plus className="mr-2 h-4 w-4" />Thêm vào hàng đợi</Button>
        </div>
      </div>

      {/* MODALS */}
      <DetailModal open={!!viewItem} onOpenChange={(o) => !o && setViewItem(null)} title="Chi tiết hàng đợi" size="sm">
        {viewItem && (
          <div className="space-y-4">
            <div className="flex items-center gap-4 pb-4 border-b border-(--color-border-light)">
              <div className={cn('w-16 h-16 rounded-full flex items-center justify-center text-2xl font-bold text-white', viewItem.status === 'IN_SERVICE' ? 'bg-green-500' : 'bg-gray-400')}>{viewItem.number}</div>
              <div><h3 className="text-lg font-semibold">{viewItem.customerName}</h3><Badge className={STATUS_CONFIG[viewItem.status].className}>{STATUS_CONFIG[viewItem.status].label}</Badge></div>
            </div>
            <div className="space-y-1"><InfoRow label="Thú cưng" value={viewItem.petName} /><InfoRow label="Dịch vụ" value={viewItem.service} /><InfoRow label="Loại" value={viewItem.serviceType} /><InfoRow label="Check-in" value={viewItem.checkInTime} /><InfoRow label="Được gọi lúc" value={viewItem.calledAt || '-'} /></div>
          </div>
        )}
      </DetailModal>

      <ConfirmModal open={!!noShowItem} onOpenChange={(o) => !o && setNoShowItem(null)} type="warning" title="Đánh dấu vắng mặt?" description={`Đánh dấu số ${noShowItem?.number} của "${noShowItem?.customerName}" là không đến?`} confirmText="Xác nhận vắng" onConfirm={handleNoShow} />

      <FormModal open={addQueueOpen} onOpenChange={setAddQueueOpen} title="Thêm vào hàng đợi" description="Nhận khách hàng walk-in vào hàng đợi" onSubmit={() => setAddQueueOpen(false)} submitText="Thêm vào đợi" size="sm">
        <div className="space-y-4">
          <div><label className="block text-sm font-medium mb-1.5">Tên khách hàng *</label><Input placeholder="Nguyễn Văn A" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Tên thú cưng *</label><Input placeholder="Mèo Whiskas" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Dịch vụ *</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm"><option value="CLINIC">Khám bệnh</option><option value="VACCINE">Tiêm vaccine</option><option value="GROOMING">Grooming</option></select></div>
        </div>
      </FormModal>
    </div>
  );
}
