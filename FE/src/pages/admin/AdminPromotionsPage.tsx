import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Plus, Tag, Calendar, Percent, Users, Eye, Edit, Pause } from 'lucide-react';
import { ConfirmModal, DetailModal, FormModal, InfoRow } from '../../components/ui/modal-templates';

type PromotionStatus = 'ACTIVE' | 'SCHEDULED' | 'EXPIRED' | 'DRAFT';

interface Promotion {
  id: string; name: string; code: string; type: 'PERCENT' | 'FIXED' | 'BUY_X_GET_Y';
  value: number; minOrderAmount: number; maxDiscount?: number;
  startDate: string; endDate: string; usageLimit?: number;
  usedCount: number; status: PromotionStatus; applicableTo: string[];
}

const MOCK_PROMOTIONS: Promotion[] = [
  { id: '1', name: 'Giảm 10% dịch vụ khám', code: 'KHAM10', type: 'PERCENT', value: 10, minOrderAmount: 0, maxDiscount: 50000, startDate: '2026-09-01', endDate: '2026-09-30', usageLimit: 100, usedCount: 45, status: 'ACTIVE', applicableTo: ['Khám bệnh', 'Tiêm chủng'] },
  { id: '2', name: 'Freeship đơn từ 500K', code: 'FREESHIP500', type: 'FIXED', value: 30000, minOrderAmount: 500000, startDate: '2026-09-15', endDate: '2026-10-15', usageLimit: 200, usedCount: 78, status: 'ACTIVE', applicableTo: ['Đơn online'] },
  { id: '3', name: 'Mua 2 tặng 1 vaccine', code: 'VAXCOMBO', type: 'BUY_X_GET_Y', value: 1, minOrderAmount: 0, startDate: '2026-08-01', endDate: '2026-08-31', usageLimit: 50, usedCount: 50, status: 'EXPIRED', applicableTo: ['Vaccine'] },
];

function StatusBadge({ status }: { status: PromotionStatus }) {
  const config: Record<PromotionStatus, { label: string; className: string }> = { ACTIVE: { label: 'Đang hoạt động', className: 'bg-green-100 text-green-700' }, SCHEDULED: { label: 'Sắp diễn ra', className: 'bg-blue-100 text-blue-700' }, EXPIRED: { label: 'Đã hết hạn', className: 'bg-gray-100 text-gray-700' }, DRAFT: { label: 'Bản nháp', className: 'bg-amber-100 text-amber-700' } };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminPromotionsPage() {
  const [promotions, setPromotions] = useState(MOCK_PROMOTIONS);
  const [searchQuery, setSearchQuery] = useState('');
  const [viewPromo, setViewPromo] = useState<Promotion | null>(null);
  const [pausePromo, setPausePromo] = useState<Promotion | null>(null);
  const [deletePromo, setDeletePromo] = useState<Promotion | null>(null);
  const [addPromoOpen, setAddPromoOpen] = useState(false);

  const filteredPromotions = promotions.filter(p => p.name.toLowerCase().includes(searchQuery.toLowerCase()) || p.code.toLowerCase().includes(searchQuery.toLowerCase()));
  const activeCount = promotions.filter(p => p.status === 'ACTIVE').length;
  const totalUsed = promotions.reduce((sum, p) => sum + p.usedCount, 0);

  const handlePause = () => {
    if (!pausePromo) return;
    setPromotions(prev => prev.map(p => p.id === pausePromo.id ? { ...p, status: 'DRAFT' as PromotionStatus } : p));
    setPausePromo(null);
  };

  const handleDelete = () => {
    if (!deletePromo) return;
    setPromotions(prev => prev.filter(p => p.id !== deletePromo.id));
    setDeletePromo(null);
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Khuyến mãi & Voucher</h1>
        <p className="text-(--text-secondary)">Quản lý chương trình khuyến mãi</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi border-green-200"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-green-100"><Tag className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-semibold text-green-600">{activeCount}</p><p className="text-sm text-(--text-secondary)">Đang hoạt động</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-blue-100"><Percent className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-semibold text-blue-600">{totalUsed}</p><p className="text-sm text-(--text-secondary)">Lượt sử dụng</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-amber-100"><Calendar className="h-6 w-6 text-amber-600" /></div><div><p className="text-2xl font-semibold text-amber-600">{promotions.filter(p => p.status === 'SCHEDULED').length}</p><p className="text-sm text-(--text-secondary)">Sắp diễn ra</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-purple-100"><Users className="h-6 w-6 text-purple-600" /></div><div><p className="text-2xl font-semibold text-purple-600">{promotions.length}</p><p className="text-sm text-(--text-secondary)">Tổng khuyến mãi</p></div></CardContent></Card>
      </div>

      <Card className="card"><CardContent className="p-4 flex items-center justify-between">
        <div className="relative max-w-md"><Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" /><Input placeholder="Tìm khuyến mãi..." className="pl-10 input" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} /></div>
        <Button onClick={() => setAddPromoOpen(true)}><Plus className="mr-2 h-4 w-4" />Tạo khuyến mãi</Button>
      </CardContent></Card>

      <Card className="card"><CardContent className="p-0">
        <div className="divide-y divide-(--border-subtle)">
          {filteredPromotions.map(promo => (
            <div key={promo.id} className="p-5 hover:bg-(--bg-secondary) transition-colors">
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <div className="flex items-center gap-3 mb-2">
                    <h3 className="font-semibold text-(--text-primary)">{promo.name}</h3>
                    <code className="px-2 py-0.5 bg-(--bg-tertiary) rounded text-sm font-mono text-(--color-primary)">{promo.code}</code>
                    <StatusBadge status={promo.status} />
                  </div>
                  <div className="grid grid-cols-2 md:grid-cols-5 gap-4 text-sm">
                    <div><p className="text-(--text-tertiary)">Loại giảm</p><p className="font-medium text-(--text-primary)">{promo.type === 'PERCENT' ? `${promo.value}%` : promo.type === 'FIXED' ? formatCurrency(promo.value) : `Mua ${promo.value}+ tặng 1`}</p></div>
                    <div><p className="text-(--text-tertiary)">Đơn tối thiểu</p><p className="text-(--text-primary)">{promo.minOrderAmount > 0 ? formatCurrency(promo.minOrderAmount) : 'Không'}</p></div>
                    <div><p className="text-(--text-tertiary)">Thời gian</p><p className="text-(--text-primary)">{promo.startDate} - {promo.endDate}</p></div>
                    <div><p className="text-(--text-tertiary)">Lượt dùng</p><p className="text-(--text-primary)">{promo.usedCount}{promo.usageLimit ? `/${promo.usageLimit}` : ''}</p></div>
                    <div><p className="text-(--text-tertiary)">Áp dụng cho</p><p className="text-(--text-primary)">{promo.applicableTo.join(', ')}</p></div>
                  </div>
                </div>
                <div className="flex gap-2 ml-4">
                  <Button size="sm" variant="outline" onClick={() => setViewPromo(promo)}><Eye className="h-4 w-4" /></Button>
                  <Button size="sm" variant="outline" onClick={() => setAddPromoOpen(true)}><Edit className="h-4 w-4" /></Button>
                  {promo.status === 'ACTIVE' && <Button size="sm" variant="outline" className="text-amber-500" onClick={() => setPausePromo(promo)}><Pause className="h-4 w-4" /> Tạm dừng</Button>}
                  <Button size="sm" variant="outline" className="text-red-500" onClick={() => setDeletePromo(promo)}>Xóa</Button>
                </div>
              </div>
            </div>
          ))}
        </div>
      </CardContent></Card>

      {/* MODALS */}
      <DetailModal open={!!viewPromo} onOpenChange={(o) => !o && setViewPromo(null)} title="Chi tiết khuyến mãi" size="md">
        {viewPromo && (
          <div className="space-y-4">
            <div className="flex items-center justify-between pb-4 border-b border-(--color-border-light)">
              <div><h3 className="text-lg font-semibold">{viewPromo.name}</h3><code className="text-sm text-(--color-primary)">{viewPromo.code}</code></div>
              <StatusBadge status={viewPromo.status} />
            </div>
            <div className="space-y-1">
              <InfoRow label="Loại giảm" value={viewPromo.type === 'PERCENT' ? `${viewPromo.value}%` : viewPromo.type === 'FIXED' ? formatCurrency(viewPromo.value) : `Mua ${viewPromo.value}+ tặng 1`} />
              <InfoRow label="Đơn tối thiểu" value={viewPromo.minOrderAmount > 0 ? formatCurrency(viewPromo.minOrderAmount) : 'Không'} />
              {viewPromo.maxDiscount && <InfoRow label="Giảm tối đa" value={formatCurrency(viewPromo.maxDiscount)} />}
              <InfoRow label="Thời gian" value={`${viewPromo.startDate} - ${viewPromo.endDate}`} />
              <InfoRow label="Lượt dùng" value={`${viewPromo.usedCount}${viewPromo.usageLimit ? ` / ${viewPromo.usageLimit}` : ''}`} />
              <InfoRow label="Áp dụng cho" value={viewPromo.applicableTo.join(', ')} />
            </div>
          </div>
        )}
      </DetailModal>

      <ConfirmModal open={!!pausePromo} onOpenChange={(o) => !o && setPausePromo(null)} type="warning" title="Tạm dừng khuyến mãi?" description={`Tạm dừng khuyến mãi "${pausePromo?.name}"? Khách hàng sẽ không thể sử dụng voucher này.`} confirmText="Tạm dừng" onConfirm={handlePause} />

      <ConfirmModal open={!!deletePromo} onOpenChange={(o) => !o && setDeletePromo(null)} type="danger" title="Xóa khuyến mãi?" description={`Xóa khuyến mãi "${deletePromo?.name}"? Hành động này không thể hoàn tác.`} confirmText="Xóa" onConfirm={handleDelete} />

      <FormModal open={addPromoOpen} onOpenChange={setAddPromoOpen} title={viewPromo ? 'Sửa khuyến mãi' : 'Tạo khuyến mãi mới'} description="Điều thông tin để tạo voucher khuyến mãi" onSubmit={() => { setAddPromoOpen(false); setViewPromo(null); }} submitText={viewPromo ? 'Lưu thay đổi' : 'Tạo khuyến mãi'} size="lg">
        <div className="grid grid-cols-2 gap-4">
          <div><label className="block text-sm font-medium mb-1.5">Tên khuyến mãi *</label><Input placeholder="VD: Giảm 10% dịch vụ" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Mã voucher *</label><Input placeholder="VD: SUMMER10" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Loại giảm</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm"><option value="PERCENT">Phần trăm (%)</option><option value="FIXED">Số tiền cố định</option><option value="BUY_X_GET_Y">Mua X tặng Y</option></select></div>
          <div><label className="block text-sm font-medium mb-1.5">Giá trị giảm *</label><Input type="number" placeholder="VD: 10" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Đơn tối thiểu</label><Input type="number" placeholder="0" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Giảm tối đa</label><Input type="number" placeholder="Không giới hạn" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Ngày bắt đầu *</label><Input type="date" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Ngày kết thúc *</label><Input type="date" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Số lượng sử dụng</label><Input type="number" placeholder="Không giới hạn" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Trạng thái</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm"><option value="DRAFT">Bản nháp</option><option value="SCHEDULED">Sắp diễn ra</option><option value="ACTIVE">Hoạt động</option></select></div>
        </div>
      </FormModal>
    </div>
  );
}
