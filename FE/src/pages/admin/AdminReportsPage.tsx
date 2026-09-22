import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../components/ui/select';
import { Download, DollarSign, TrendingUp, BarChart3, FileText, Eye } from 'lucide-react';
import { useState } from 'react';
import { DetailModal, FormModal } from '../../components/ui/modal-templates';

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminReportsPage() {
  const [selectedReport, setSelectedReport] = useState<string>('REVENUE');
  const [dateRange, setDateRange] = useState<string>('today');
  const [exportOpen, setExportOpen] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Báo cáo theo phạm vi</h1>
        <p className="text-(--text-secondary)">Xem và xuất các báo cáo quản lý</p>
      </div>

      <Card className="card"><CardContent className="p-4">
        <div className="flex flex-wrap gap-4 items-end">
          <div className="w-[200px]">
            <label className="text-sm font-medium text-(--text-primary) mb-2 block">Loại báo cáo</label>
            <Select value={selectedReport} onValueChange={setSelectedReport}>
              <SelectTrigger className="select-trigger"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="REVENUE">Báo cáo doanh thu</SelectItem>
                <SelectItem value="APPOINTMENT">Báo cáo lịch hẹn</SelectItem>
                <SelectItem value="INVENTORY">Báo cáo tồn kho</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="w-[150px]">
            <label className="text-sm font-medium text-(--text-primary) mb-2 block">Khoảng thời gian</label>
            <Select value={dateRange} onValueChange={setDateRange}>
              <SelectTrigger className="select-trigger"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="today">Hôm nay</SelectItem>
                <SelectItem value="week">Tuần này</SelectItem>
                <SelectItem value="month">Tháng này</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <Button variant="outline" onClick={() => setPreviewOpen(true)}><Eye className="mr-2 h-4 w-4" />Xem trước</Button>
          <Button onClick={() => setExportOpen(true)}><Download className="mr-2 h-4 w-4" />Xuất báo cáo</Button>
        </div>
      </CardContent></Card>

      {selectedReport === 'REVENUE' && (
        <>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <Card className="card-kpi border-green-200"><CardContent className="p-6">
              <div className="flex items-center justify-between"><div><p className="text-sm text-(--text-secondary)">Doanh thu hôm nay</p><p className="text-3xl font-semibold text-green-600 mt-1">{formatCurrency(12500000)}</p><p className="text-sm text-green-600 flex items-center gap-1 mt-2"><TrendingUp className="h-4 w-4" />+12% so với hôm qua</p></div><div className="p-4 rounded-xl bg-green-100"><DollarSign className="h-8 w-8 text-green-600" /></div></div>
            </CardContent></Card>
            <Card className="card-kpi"><CardContent className="p-6"><p className="text-sm text-(--text-secondary)">Tuần này</p><p className="text-2xl font-semibold text-(--text-primary) mt-1">{formatCurrency(87500000)}</p></CardContent></Card>
            <Card className="card-kpi"><CardContent className="p-6"><p className="text-sm text-(--text-secondary)">Tháng này</p><p className="text-2xl font-semibold text-(--text-primary) mt-1">{formatCurrency(350000000)}</p></CardContent></Card>
          </div>
          <Card className="card"><CardHeader><CardTitle className="text-base font-semibold">Biểu đồ doanh thu</CardTitle></CardHeader><CardContent><div className="h-[300px] flex items-center justify-center text-(--text-tertiary)"><div className="text-center"><BarChart3 className="h-16 w-16 mx-auto mb-4 opacity-50" /><p>Biểu đồ doanh thu sẽ hiển thị ở đây</p><p className="text-sm">(Tích hợp Recharts khi có backend)</p></div></div></CardContent></Card>
        </>
      )}

      {selectedReport === 'APPOINTMENT' && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <Card className="card-kpi"><CardContent className="p-6 text-center"><p className="text-4xl font-semibold text-(--text-primary)">24</p><p className="text-sm text-(--text-secondary) mt-1">Lịch hẹn hôm nay</p></CardContent></Card>
          <Card className="card-kpi border-green-200"><CardContent className="p-6 text-center"><p className="text-4xl font-semibold text-green-600">18</p><p className="text-sm text-(--text-secondary) mt-1">Đã hoàn tất</p></CardContent></Card>
          <Card className="card-kpi border-red-200"><CardContent className="p-6 text-center"><p className="text-4xl font-semibold text-red-600">2</p><p className="text-sm text-(--text-secondary) mt-1">Đã hủy</p></CardContent></Card>
        </div>
      )}

      {selectedReport === 'INVENTORY' && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <Card className="card-kpi"><CardContent className="p-6 text-center"><p className="text-4xl font-semibold text-(--text-primary)">156</p><p className="text-sm text-(--text-secondary) mt-1">Tổng sản phẩm</p></CardContent></Card>
          <Card className="card-kpi border-amber-200"><CardContent className="p-6 text-center"><p className="text-4xl font-semibold text-amber-600">5</p><p className="text-sm text-(--text-secondary) mt-1">Sắp hết</p></CardContent></Card>
          <Card className="card-kpi border-orange-200"><CardContent className="p-6 text-center"><p className="text-4xl font-semibold text-orange-600">3</p><p className="text-sm text-(--text-secondary) mt-1">Sắp hết hạn</p></CardContent></Card>
        </div>
      )}

      {/* MODALS */}
      <DetailModal open={previewOpen} onOpenChange={setPreviewOpen} title="Xem trước báo cáo" size="lg">
        <div className="space-y-4">
          <div className="p-4 bg-(--color-surface-2) rounded-xl">
            <p className="text-sm text-(--text-secondary)">Báo cáo: <span className="font-medium text-(--text-primary)">{selectedReport === 'REVENUE' ? 'Doanh thu' : selectedReport === 'APPOINTMENT' ? 'Lịch hẹn' : 'Tồn kho'}</span></p>
            <p className="text-sm text-(--text-secondary)">Khoảng thời gian: <span className="font-medium text-(--text-primary)">{dateRange === 'today' ? 'Hôm nay' : dateRange === 'week' ? 'Tuần này' : 'Tháng này'}</span></p>
          </div>
          <div className="h-64 flex items-center justify-center text-(--text-tertiary)"><FileText className="h-16 w-16 opacity-30 mr-4" /><p>Nội dung báo cáo sẽ hiển thị ở đây</p></div>
        </div>
      </DetailModal>

      <FormModal open={exportOpen} onOpenChange={setExportOpen} title="Xuất báo cáo" description="Chọn định dạng và tùy chọn xuất" onSubmit={() => setExportOpen(false)} submitText="Xuất báo cáo" size="sm">
        <div className="space-y-4">
          <div><label className="block text-sm font-medium mb-1.5">Định dạng</label><select className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm"><option>PDF</option><option>Excel (.xlsx)</option><option>CSV</option></select></div>
          <div><label className="block text-sm font-medium mb-1.5">Ngày bắt đầu</label><input type="date" className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm" /></div>
          <div><label className="block text-sm font-medium mb-1.5">Ngày kết thúc</label><input type="date" className="w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm" /></div>
        </div>
      </FormModal>
    </div>
  );
}
