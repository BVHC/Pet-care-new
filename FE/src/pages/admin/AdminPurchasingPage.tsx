import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Plus, Truck, Package, Clock, Check, Eye, X } from 'lucide-react';
import { ConfirmModal, DetailModal, InfoRow } from '../../components/ui/modal-templates';

type PurchaseStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'ORDERED' | 'RECEIVED' | 'CANCELLED';

interface PurchaseOrder {
  id: string; poNumber: string; date: string; supplier: string;
  supplierPhone: string; items: { name: string; quantity: number; unitPrice: number }[];
  totalAmount: number; status: PurchaseStatus; expectedDate: string;
  receivedDate?: string; createdBy: string;
}

const MOCK_PURCHASES: PurchaseOrder[] = [
  { id: '1', poNumber: 'PO-2026-001', date: '2026-09-18', supplier: 'Công ty TNHH Thức ăn Chó Mèo', supplierPhone: '0281234567', items: [{ name: 'Thức ăn Royal Canin', quantity: 100, unitPrice: 350000 }], totalAmount: 35000000, status: 'ORDERED', expectedDate: '2026-09-20', createdBy: 'Store Manager' },
  { id: '2', poNumber: 'PO-2026-002', date: '2026-09-17', supplier: 'Dược phẩm Thú Y ABC', supplierPhone: '0282345678', items: [{ name: 'Vaccine dại', quantity: 50, unitPrice: 150000 }], totalAmount: 7500000, status: 'RECEIVED', expectedDate: '2026-09-19', receivedDate: '2026-09-19', createdBy: 'Inventory Staff' },
  { id: '3', poNumber: 'PO-2026-003', date: '2026-09-16', supplier: 'Vật tư Thú Y XYZ', supplierPhone: '0283456789', items: [{ name: 'Bơm tiêm 5ml', quantity: 200, unitPrice: 15000 }], totalAmount: 3000000, status: 'PENDING', expectedDate: '2026-09-22', createdBy: 'Inventory Staff' },
];

function StatusBadgeLocal({ status }: { status: PurchaseStatus }) {
  const config: Record<PurchaseStatus, { label: string; className: string }> = { DRAFT: { label: 'Bản nháp', className: 'bg-gray-100 text-gray-700' }, PENDING: { label: 'Chờ duyệt', className: 'bg-amber-100 text-amber-700' }, APPROVED: { label: 'Đã duyệt', className: 'bg-blue-100 text-blue-700' }, ORDERED: { label: 'Đã đặt hàng', className: 'bg-purple-100 text-purple-700' }, RECEIVED: { label: 'Đã nhận hàng', className: 'bg-green-100 text-green-700' }, CANCELLED: { label: 'Đã hủy', className: 'bg-red-100 text-red-700' } };
  return <Badge className={config[status].className}>{config[status].label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminPurchasingPage() {
  const [purchases, setPurchases] = useState<PurchaseOrder[]>(MOCK_PURCHASES);
  const [searchQuery, setSearchQuery] = useState('');
  const [viewPO, setViewPO] = useState<PurchaseOrder | null>(null);
  const [approvePO, setApprovePO] = useState<PurchaseOrder | null>(null);
  const [rejectPO, setRejectPO] = useState<PurchaseOrder | null>(null);
  const [receivePO, setReceivePO] = useState<PurchaseOrder | null>(null);

  const filteredPurchases = purchases.filter(p => p.poNumber.toLowerCase().includes(searchQuery.toLowerCase()) || p.supplier.toLowerCase().includes(searchQuery.toLowerCase()));
  const pendingCount = purchases.filter(p => p.status === 'PENDING').length;
  const orderedCount = purchases.filter(p => p.status === 'ORDERED').length;

  const handleApprove = () => {
    if (!approvePO) return;
    setPurchases(prev => prev.map(p => p.id === approvePO.id ? { ...p, status: 'ORDERED' as PurchaseStatus } : p));
    setApprovePO(null);
  };

  const handleReject = () => {
    if (!rejectPO) return;
    setPurchases(prev => prev.map(p => p.id === rejectPO.id ? { ...p, status: 'CANCELLED' as PurchaseStatus } : p));
    setRejectPO(null);
  };

  const handleReceive = () => {
    if (!receivePO) return;
    setPurchases(prev => prev.map(p => p.id === receivePO.id ? { ...p, status: 'RECEIVED' as PurchaseStatus, receivedDate: new Date().toISOString().split('T')[0] } : p));
    setReceivePO(null);
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Mua hàng NCC</h1>
        <p className="text-(--text-secondary)">Quản lý đơn mua hàng từ nhà cung cấp</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-amber-100"><Clock className="h-6 w-6 text-amber-600" /></div><div><p className="text-2xl font-semibold text-amber-600">{pendingCount}</p><p className="text-sm text-(--text-secondary)">Chờ duyệt</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-purple-100"><Truck className="h-6 w-6 text-purple-600" /></div><div><p className="text-2xl font-semibold text-purple-600">{orderedCount}</p><p className="text-sm text-(--text-secondary)">Đang vận chuyển</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-green-100"><Check className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-semibold text-green-600">{purchases.filter(p => p.status === 'RECEIVED').length}</p><p className="text-sm text-(--text-secondary)">Đã nhận hàng</p></div></CardContent></Card>
        <Card className="card-kpi"><CardContent className="p-5 flex items-center gap-4"><div className="p-3 rounded-xl bg-blue-100"><Package className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-semibold text-blue-600">{purchases.length}</p><p className="text-sm text-(--text-secondary)">Tổng đơn</p></div></CardContent></Card>
      </div>

      <Card className="card"><CardContent className="p-4 flex items-center justify-between"><div className="relative max-w-md"><Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" /><Input placeholder="Tìm đơn mua hàng..." className="pl-10 input" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} /></div><Button><Plus className="mr-2 h-4 w-4" />Tạo đơn mua hàng</Button></CardContent></Card>

      <Card className="card">
        <CardContent className="p-0">
          <div className="divide-y divide-(--border-subtle)">
            {filteredPurchases.map(purchase => (
              <div key={purchase.id} className="p-5 hover:bg-(--bg-secondary) transition-colors">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-3 mb-2">
                      <span className="font-semibold text-(--text-primary)">{purchase.poNumber}</span>
                      <StatusBadgeLocal status={purchase.status} />
                      <span className="text-sm text-(--text-tertiary)">{purchase.date}</span>
                    </div>
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm mb-3">
                      <div><p className="text-(--text-tertiary)">Nhà cung cấp</p><p className="font-medium text-(--text-primary)">{purchase.supplier}</p></div>
                      <div><p className="text-(--text-tertiary)">Dự kiến nhận</p><p className="text-(--text-primary)">{purchase.expectedDate}</p></div>
                      <div><p className="text-(--text-tertiary)">Người tạo</p><p className="text-(--text-primary)">{purchase.createdBy}</p></div>
                      <div><p className="text-(--text-tertiary)">Số sản phẩm</p><p className="text-(--text-primary)">{purchase.items.length} mặt hàng</p></div>
                    </div>
                    <div className="text-sm text-(--text-secondary)">{purchase.items.map((item, idx) => <span key={idx}>{item.quantity}x {item.name}{idx < purchase.items.length - 1 && ', '}</span>)}</div>
                  </div>
                  <div className="text-right ml-6">
                    <p className="text-xl font-semibold text-(--text-primary)">{formatCurrency(purchase.totalAmount)}</p>
                    <div className="flex gap-2 mt-3 justify-end">
                      <Button size="sm" variant="outline" onClick={() => setViewPO(purchase)}><Eye className="mr-1 h-4 w-4" /> Chi tiết</Button>
                      {purchase.status === 'PENDING' && (<><Button size="sm" variant="outline" className="text-red-500 border-red-200 hover:bg-red-50" onClick={() => setRejectPO(purchase)}><X className="mr-1 h-4 w-4" /> Từ chối</Button><Button size="sm" className="bg-green-600 hover:bg-green-700" onClick={() => setApprovePO(purchase)}><Check className="mr-1 h-4 w-4" /> Duyệt</Button></>)}
                      {purchase.status === 'ORDERED' && <Button size="sm" className="bg-green-600 hover:bg-green-700" onClick={() => setReceivePO(purchase)}><Check className="mr-1 h-4 w-4" /> Xác nhận đã nhận</Button>}
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* MODALS */}
      <DetailModal open={!!viewPO} onOpenChange={(o) => !o && setViewPO(null)} title="Chi tiết đơn mua hàng" size="lg">
        {viewPO && (
          <div className="space-y-4">
            <div className="flex items-center justify-between pb-4 border-b border-(--color-border-light)"><div><h3 className="text-lg font-semibold">{viewPO.poNumber}</h3><p className="text-sm text-(--text-secondary)">{viewPO.date}</p></div><StatusBadgeLocal status={viewPO.status} /></div>
            <div className="space-y-1"><InfoRow label="Nhà cung cấp" value={viewPO.supplier} /><InfoRow label="Điện thoại" value={viewPO.supplierPhone} /><InfoRow label="Người tạo" value={viewPO.createdBy} /><InfoRow label="Dự kiến nhận" value={viewPO.expectedDate} /><InfoRow label="Ngày nhận" value={viewPO.receivedDate || '-'} /></div>
            <div className="border-t border-(--color-border-light) pt-4"><h4 className="font-medium mb-2">Danh sách sản phẩm</h4>{viewPO.items.map((item, idx) => <div key={idx} className="flex justify-between py-2 border-b border-(--color-border-light) last:border-0"><span>{item.quantity}x {item.name}</span><span className="font-medium">{formatCurrency(item.quantity * item.unitPrice)}</span></div>)}</div>
            <div className="border-t border-(--color-border-light) pt-4"><InfoRow label="Tổng cộng" value={<span className="font-bold text-accent">{formatCurrency(viewPO.totalAmount)}</span>} /></div>
          </div>
        )}
      </DetailModal>

      <ConfirmModal open={!!approvePO} onOpenChange={(o) => !o && setApprovePO(null)} type="success" title="Duyệt đơn mua hàng?" description={`Bạn có chắc duyệt đơn "${approvePO?.poNumber}" từ "${approvePO?.supplier}" trị giá ${approvePO ? formatCurrency(approvePO.totalAmount) : ''}?`} confirmText="Duyệt đơn" onConfirm={handleApprove} />

      <ConfirmModal open={!!rejectPO} onOpenChange={(o) => !o && setRejectPO(null)} type="danger" title="Từ chối đơn mua hàng?" description={`Bạn có chắc từ chối đơn "${rejectPO?.poNumber}"?`} confirmText="Từ chối" onConfirm={handleReject} />

      <ConfirmModal open={!!receivePO} onOpenChange={(o) => !o && setReceivePO(null)} type="success" title="Xác nhận đã nhận hàng?" description={`Xác nhận đã nhận đủ hàng từ "${receivePO?.supplier}" cho đơn "${receivePO?.poNumber}"? Hàng sẽ được cập nhật vào kho.`} confirmText="Xác nhận đã nhận" onConfirm={handleReceive} />
    </div>
  );
}
