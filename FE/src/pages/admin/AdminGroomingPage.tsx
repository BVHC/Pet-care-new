import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Scissors, Plus, Clock, Check } from 'lucide-react';
import { cn } from '../../lib/utils';

type GroomingStatus = 'WAITING' | 'IN_PROGRESS' | 'COMPLETED';

interface GroomingRecord {
  id: string;
  checkInTime: string;
  customerName: string;
  customerPhone: string;
  petName: string;
  petType: string;
  petBreed: string;
  service: string;
  serviceType: 'BASIC' | 'PREMIUM' | 'SPA';
  duration: number; // minutes
  groomer: string;
  status: GroomingStatus;
  notes?: string;
}

const MOCK_GROOMING: GroomingRecord[] = [
  {
    id: '1',
    checkInTime: '08:30',
    customerName: 'Nguyễn Văn A',
    customerPhone: '0901234567',
    petName: 'Mèo Whiskas',
    petType: 'Mèo',
    petBreed: 'Scottish Fold',
    service: 'Cắt tỉa lông toàn thân',
    serviceType: 'SPA',
    duration: 120,
    groomer: 'Hương',
    status: 'IN_PROGRESS',
    notes: 'Lông bị rối, cần chải kỹ trước'
  },
  {
    id: '2',
    checkInTime: '09:00',
    customerName: 'Trần Thị B',
    customerPhone: '0912345678',
    petName: 'Chó Alaska',
    petType: 'Chó',
    petBreed: 'Alaska',
    service: 'Tắm + Sấy',
    serviceType: 'BASIC',
    duration: 60,
    groomer: 'Minh',
    status: 'IN_PROGRESS'
  },
  {
    id: '3',
    checkInTime: '10:00',
    customerName: 'Lê Văn C',
    customerPhone: '0923456789',
    petName: 'Chó Poodle',
    petType: 'Chó',
    petBreed: 'Poodle',
    service: 'Cắt móng + Vệ sinh tai',
    serviceType: 'BASIC',
    duration: 30,
    groomer: 'Hương',
    status: 'WAITING'
  },
  {
    id: '4',
    checkInTime: '08:00',
    customerName: 'Phạm Thị D',
    customerPhone: '0934567890',
    petName: 'Mèo Persian',
    petType: 'Mèo',
    petBreed: 'Persian',
    service: 'Tắm Spa thảo dược',
    serviceType: 'SPA',
    duration: 90,
    groomer: 'Minh',
    status: 'COMPLETED',
    notes: 'Khách hàng rất hài lòng'
  },
];

export function AdminGroomingPage() {
  const [grooming, setGrooming] = useState(MOCK_GROOMING);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredGrooming = grooming.filter(g =>
    g.customerName.toLowerCase().includes(searchQuery.toLowerCase()) ||
    g.petName.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const updateStatus = (id: string, status: GroomingStatus) => {
    setGrooming(prev => prev.map(g => g.id === id ? { ...g, status } : g));
  };

  const waitingCount = grooming.filter(g => g.status === 'WAITING').length;
  const inProgressCount = grooming.filter(g => g.status === 'IN_PROGRESS').length;
  const completedToday = grooming.filter(g => g.status === 'COMPLETED').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Grooming Spa</h1>
        <p className="text-[var(--text-secondary)]">Quản lý dịch vụ spa và chăm sóc thú cưng</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-amber-100">
              <Clock className="h-6 w-6 text-amber-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-amber-600">{waitingCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đang chờ</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100">
              <Scissors className="h-6 w-6 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-blue-600">{inProgressCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đang làm</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <Check className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{completedToday}</p>
              <p className="text-sm text-[var(--text-secondary)]">Hoàn tất hôm nay</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-purple-100">
              <Scissors className="h-6 w-6 text-purple-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-purple-600">{grooming.length}</p>
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
              placeholder="Tìm thú cưng, khách hàng..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <Button><Plus className="mr-2 h-4 w-4" />Thêm lịch grooming</Button>
        </CardContent>
      </Card>

      {/* Grooming List */}
      <Card className="card">
        <CardContent className="p-0">
          <div className="divide-y divide-[var(--border-subtle)]">
            {filteredGrooming.map(g => (
              <div key={g.id} className={cn(
                'p-5 transition-colors',
                g.status === 'IN_PROGRESS' && 'bg-blue-50/50 border-l-4 border-l-blue-500',
                g.status === 'COMPLETED' && 'bg-green-50/30 opacity-75'
              )}>
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-3 mb-2">
                      <div className={cn(
                        'w-12 h-12 rounded-xl flex items-center justify-center',
                        g.serviceType === 'SPA' ? 'bg-purple-100' :
                        g.serviceType === 'PREMIUM' ? 'bg-amber-100' : 'bg-blue-100'
                      )}>
                        <Scissors className={cn(
                          'h-6 w-6',
                          g.serviceType === 'SPA' ? 'text-purple-600' :
                          g.serviceType === 'PREMIUM' ? 'text-amber-600' : 'text-blue-600'
                        )} />
                      </div>
                      <div>
                        <h3 className="font-semibold text-[var(--text-primary)]">{g.petName}</h3>
                        <p className="text-sm text-[var(--text-secondary)]">{g.customerName} • {g.customerPhone}</p>
                      </div>
                      <Badge className={
                        g.status === 'WAITING' ? 'bg-amber-100 text-amber-700' :
                        g.status === 'IN_PROGRESS' ? 'bg-blue-100 text-blue-700' :
                        'bg-green-100 text-green-700'
                      }>
                        {g.status === 'WAITING' ? 'Chờ' :
                         g.status === 'IN_PROGRESS' ? 'Đang làm' : 'Hoàn tất'}
                      </Badge>
                      <Badge variant="secondary" className="bg-[var(--bg-tertiary)]">
                        {g.serviceType === 'SPA' ? '🌸 Spa' : g.serviceType === 'PREMIUM' ? '⭐ Premium' : '📋 Basic'}
                      </Badge>
                    </div>

                    <div className="grid grid-cols-2 md:grid-cols-5 gap-4 text-sm">
                      <div>
                        <p className="text-[var(--text-tertiary)]">Dịch vụ</p>
                        <p className="font-medium text-[var(--text-primary)]">{g.service}</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Giống / Loại</p>
                        <p className="text-[var(--text-primary)]">{g.petBreed} ({g.petType})</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Thời gian</p>
                        <p className="text-[var(--text-primary)]">{g.duration} phút</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Groomer</p>
                        <p className="text-[var(--text-primary)]">{g.groomer}</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Check-in</p>
                        <p className="text-[var(--text-primary)]">{g.checkInTime}</p>
                      </div>
                    </div>

                    {g.notes && (
                      <p className="mt-2 text-sm text-[var(--text-tertiary)] italic">
                        Ghi chú: {g.notes}
                      </p>
                    )}
                  </div>

                  <div className="flex gap-2 ml-4">
                    {g.status === 'WAITING' && (
                      <Button size="sm" className="bg-blue-600 hover:bg-blue-700" onClick={() => updateStatus(g.id, 'IN_PROGRESS')}>
                        Bắt đầu
                      </Button>
                    )}
                    {g.status === 'IN_PROGRESS' && (
                      <Button size="sm" className="bg-green-600 hover:bg-green-700" onClick={() => updateStatus(g.id, 'COMPLETED')}>
                        Hoàn tất
                      </Button>
                    )}
                    {g.status === 'COMPLETED' && (
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
