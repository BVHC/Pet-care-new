import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Search, Gift, Clock, TrendingUp, Plus } from 'lucide-react';
import { cn } from '../../lib/utils';

type Status = 'ACTIVE' | 'EXPIRED' | 'SCHEDULED';

interface Promotion {
  id: string; name: string; code: string; value: number; type: string;
  startDate: string; endDate: string; usedCount: number; usageLimit: number; status: Status;
}

const MOCK_PROMOTIONS: Promotion[] = [
  { id: '1', name: 'Giảm 10% dịch vụ khám', code: 'KHAM10', value: 10, type: 'PERCENTAGE', startDate: '2026-09-01', endDate: '2026-09-30', usageLimit: 100, usedCount: 45, status: 'ACTIVE' },
  { id: '2', name: 'Giảm 50K cho đơn từ 500K', code: 'SALE50K', value: 50000, type: 'FIXED', startDate: '2026-09-15', endDate: '2026-09-25', usageLimit: 50, usedCount: 32, status: 'ACTIVE' },
  { id: '3', name: 'Giảm 20% vaccine', code: 'VAX20', value: 20, type: 'PERCENTAGE', startDate: '2026-08-01', endDate: '2026-08-31', usageLimit: 100, usedCount: 100, status: 'EXPIRED' },
];

export function AdminPromotionsPage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Khuyến mãi & Voucher</h1>
        <p className="text-gray-500">Quản lý chương trình khuyến mãi và mã giảm giá</p>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-green-100"><TrendingUp className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-bold">{MOCK_PROMOTIONS.filter(p => p.status === 'ACTIVE').length}</p><p className="text-sm text-gray-500">Đang hoạt động</p></div></CardContent></Card>
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-blue-100"><Gift className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-bold">{MOCK_PROMOTIONS.length}</p><p className="text-sm text-gray-500">Tổng khuyến mãi</p></div></CardContent></Card>
        <Card><CardContent className="p-4"><p className="text-sm text-gray-500">Lượt sử dụng</p><p className="text-2xl font-bold">{MOCK_PROMOTIONS.reduce((sum, p) => sum + p.usedCount, 0)}</p></CardContent></Card>
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-orange-100"><Clock className="h-6 w-6 text-orange-600" /></div><div><p className="text-2xl font-bold">{MOCK_PROMOTIONS.filter(p => p.status === 'SCHEDULED').length}</p><p className="text-sm text-gray-500">Sắp diễn ra</p></div></CardContent></Card>
      </div>

      <Card>
        <CardContent className="p-4 flex items-center justify-between">
          <div className="relative w-[300px]">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" />
            <Input placeholder="Tìm khuyến mãi..." className="pl-10" />
          </div>
          <Button><Plus className="mr-2 h-4 w-4" />Tạo khuyến mãi</Button>
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {MOCK_PROMOTIONS.map(promo => (
          <Card key={promo.id} className={cn('transition-all', promo.status === 'ACTIVE' && 'border-green-500')}>
            <CardContent className="p-4">
              <div className="flex items-start justify-between mb-3">
                <div>
                  <div className="flex items-center gap-2 mb-1">
                    <h3 className="font-medium">{promo.name}</h3>
                    <Badge className={promo.status === 'ACTIVE' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-700'}>
                      {promo.status === 'ACTIVE' ? 'Đang hoạt động' : 'Đã hết hạn'}
                    </Badge>
                  </div>
                  <code className="bg-muted px-2 py-1 rounded text-sm font-mono">{promo.code}</code>
                </div>
                <p className="text-2xl font-bold text-green-600">
                  {promo.type === 'PERCENTAGE' ? `${promo.value}%` : `${promo.value.toLocaleString()}đ`}
                </p>
              </div>
              <div className="flex items-center gap-4 text-sm text-gray-500 mb-3">
                <span className="flex items-center gap-1"><Clock className="h-3 w-3" />{promo.startDate} - {promo.endDate}</span>
              </div>
              <div className="flex-1 mr-4">
                <div className="flex items-center justify-between text-sm mb-1">
                  <span>Đã sử dụng: {promo.usedCount}/{promo.usageLimit}</span>
                  <span>{Math.round((promo.usedCount / promo.usageLimit) * 100)}%</span>
                </div>
                <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
                  <div className="h-full bg-green-500 rounded-full" style={{ width: `${(promo.usedCount / promo.usageLimit) * 100}%` }} />
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}
