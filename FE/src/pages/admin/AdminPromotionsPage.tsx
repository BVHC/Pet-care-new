import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Plus, Tag, Calendar, Percent, Users } from 'lucide-react';
import { cn } from '../../lib/utils';

type PromotionStatus = 'ACTIVE' | 'SCHEDULED' | 'EXPIRED' | 'DRAFT';

interface Promotion {
  id: string;
  name: string;
  code: string;
  type: 'PERCENT' | 'FIXED' | 'BUY_X_GET_Y';
  value: number;
  minOrderAmount: number;
  maxDiscount?: number;
  startDate: string;
  endDate: string;
  usageLimit?: number;
  usedCount: number;
  status: PromotionStatus;
  applicableTo: string[];
}

const MOCK_PROMOTIONS: Promotion[] = [
  {
    id: '1',
    name: 'Giảm 10% dịch vụ khám',
    code: 'KHAM10',
    type: 'PERCENT',
    value: 10,
    minOrderAmount: 0,
    maxDiscount: 50000,
    startDate: '2026-09-01',
    endDate: '2026-09-30',
    usageLimit: 100,
    usedCount: 45,
    status: 'ACTIVE',
    applicableTo: ['Khám bệnh', 'Tiêm chủng']
  },
  {
    id: '2',
    name: 'Freeship đơn từ 500K',
    code: 'FREESHIP500',
    type: 'FIXED',
    value: 30000,
    minOrderAmount: 500000,
    startDate: '2026-09-15',
    endDate: '2026-10-15',
    usageLimit: 200,
    usedCount: 78,
    status: 'ACTIVE',
    applicableTo: ['Đơn online']
  },
  {
    id: '3',
    name: 'Mua 2 tặng 1 vaccine',
    code: 'VAXCOMBO',
    type: 'BUY_X_GET_Y',
    value: 1,
    minOrderAmount: 0,
    startDate: '2026-08-01',
    endDate: '2026-08-31',
    usageLimit: 50,
    usedCount: 50,
    status: 'EXPIRED',
    applicableTo: ['Vaccine']
  },
];

function StatusBadge({ status }: { status: PromotionStatus }) {
  const config: Record<PromotionStatus, { label: string; className: string }> = {
    ACTIVE: { label: 'Đang hoạt động', className: 'bg-green-100 text-green-700' },
    SCHEDULED: { label: 'Sắp diễn ra', className: 'bg-blue-100 text-blue-700' },
    EXPIRED: { label: 'Đã hết hạn', className: 'bg-gray-100 text-gray-700' },
    DRAFT: { label: 'Bản nháp', className: 'bg-amber-100 text-amber-700' },
  };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminPromotionsPage() {
  const [promotions] = useState(MOCK_PROMOTIONS);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredPromotions = promotions.filter(p =>
    p.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    p.code.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const activeCount = promotions.filter(p => p.status === 'ACTIVE').length;
  const totalUsed = promotions.reduce((sum, p) => sum + p.usedCount, 0);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Khuyến mãi & Voucher</h1>
        <p className="text-[var(--text-secondary)]">Quản lý chương trình khuyến mãi</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi border-green-200">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <Tag className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{activeCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đang hoạt động</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100">
              <Percent className="h-6 w-6 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-blue-600">{totalUsed}</p>
              <p className="text-sm text-[var(--text-secondary)]">Lượt sử dụng</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-amber-100">
              <Calendar className="h-6 w-6 text-amber-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-amber-600">{promotions.filter(p => p.status === 'SCHEDULED').length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Sắp diễn ra</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-purple-100">
              <Users className="h-6 w-6 text-purple-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-purple-600">{promotions.length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Tổng khuyến mãi</p>
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
              placeholder="Tìm khuyến mãi..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <Button><Plus className="mr-2 h-4 w-4" />Tạo khuyến mãi</Button>
        </CardContent>
      </Card>

      {/* Promotions List */}
      <Card className="card">
        <CardContent className="p-0">
          <div className="divide-y divide-[var(--border-subtle)]">
            {filteredPromotions.map(promo => (
              <div key={promo.id} className="p-5 hover:bg-[var(--bg-secondary)] transition-colors">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-3 mb-2">
                      <h3 className="font-semibold text-[var(--text-primary)]">{promo.name}</h3>
                      <code className="px-2 py-0.5 bg-[var(--bg-tertiary)] rounded text-sm font-mono text-[var(--color-primary)]">{promo.code}</code>
                      <StatusBadge status={promo.status} />
                    </div>

                    <div className="grid grid-cols-2 md:grid-cols-5 gap-4 text-sm">
                      <div>
                        <p className="text-[var(--text-tertiary)]">Loại giảm</p>
                        <p className="font-medium text-[var(--text-primary)]">
                          {promo.type === 'PERCENT' ? `${promo.value}%` :
                           promo.type === 'FIXED' ? formatCurrency(promo.value) :
                           `Mua ${promo.value}+ tặng 1`}
                        </p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Đơn tối thiểu</p>
                        <p className="text-[var(--text-primary)]">{promo.minOrderAmount > 0 ? formatCurrency(promo.minOrderAmount) : 'Không'}</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Thời gian</p>
                        <p className="text-[var(--text-primary)]">{promo.startDate} - {promo.endDate}</p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Lượt dùng</p>
                        <p className="text-[var(--text-primary)]">
                          {promo.usedCount}{promo.usageLimit ? `/${promo.usageLimit}` : ''}
                        </p>
                      </div>
                      <div>
                        <p className="text-[var(--text-tertiary)]">Áp dụng cho</p>
                        <p className="text-[var(--text-primary)]">{promo.applicableTo.join(', ')}</p>
                      </div>
                    </div>
                  </div>

                  <div className="flex gap-2 ml-4">
                    <Button size="sm" variant="outline">Sửa</Button>
                    {promo.status === 'ACTIVE' && (
                      <Button size="sm" variant="outline">Tạm dừng</Button>
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
