// T49 — UC89: xem báo cáo. SUPER_MANAGER xem toàn chuỗi hoặc lọc một chi nhánh, BRANCH_MANAGER chỉ chi nhánh mình (BR-BC-01).
// Danh mục cố định 6 báo cáo (BR-BC-03, report-v1 #1–7), chỉ đọc: không xuất file, không báo cáo tùy biến, không lưu báo cáo.
// Giữ nguyên <h1> (route test dựa vào đó). Mỗi lần chỉ mở một tab và chỉ tab đó truy vấn.
//
// ASSUMPTION (docs im lặng):
//  - Kỳ mặc định "Tháng này" = mùng 1 đến hôm nay; "7 / 30 ngày qua" gồm cả hôm nay (hôm nay trừ 6 / 29 ngày); "Tháng trước" = cả tháng trước.
//  - Kỳ ≤ 12 tháng = ngày kết thúc không quá ngày bắt đầu cộng 12 tháng theo lịch. Kiểm ở FE chỉ để báo sớm; lệch với BE thì BE trả 400 và FE hiện nguyên câu đó.
//  - BRANCH_MANAGER không gửi `branchId` (BE ngầm hiểu chi nhánh mình), không có ô chọn chi nhánh.
//  - Mẫu số bằng 0 thì tỷ lệ hiện "—" (chưa có lượt đến hạn nên chưa có tỷ lệ), không hiện 0,0%.
//  - Dòng Feedback không gắn chi nhánh (góp ý về website) hiện "Không gắn chi nhánh".
//  - `withinDays` của tồn kho: số nguyên dương, mặc định 30; cận trên để BE phán (400 hiện nguyên câu).
import { useMemo, useState } from 'react';
import { todayVn } from '../../components/appointments/format';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { useBranches } from '../../shared/hooks/useBranch';
import { useAccount } from '../../shared/stores/session.store';
import { FeedbackTab } from './reports/FeedbackTab';
import { NoShowTab } from './reports/NoShowTab';
import { RevaccinationTab } from './reports/RevaccinationTab';
import { ReportFilters } from './reports/ReportFilters';
import { periodError, presetPeriod, type Period } from './reports/report-format';
import { RevenueTab } from './reports/RevenueTab';
import { ServicesTab } from './reports/ServicesTab';
import { StockTab } from './reports/StockTab';

const TABS = {
  revenue: 'Doanh thu',
  services: 'Lượt dịch vụ',
  noShow: 'NO_SHOW và hủy muộn',
  revaccination: 'Tái chủng',
  stock: 'Tồn kho',
  feedback: 'Feedback',
} as const;
type ReportTab = keyof typeof TABS;
const TAB_KEYS = Object.keys(TABS) as ReportTab[];

export function AdminReportsPage() {
  const chainWide = useAccount()?.role === 'SUPER_MANAGER';
  const [tab, setTab] = useState<ReportTab>('revenue');
  const [branchId, setBranchId] = useState('');
  const [period, setPeriod] = useState<Period>(() => presetPeriod('thisMonth', todayVn()));
  const branches = useBranches();

  // Tên chi nhánh cho các báo cáo chỉ trả `branchId`. Danh sách chi nhánh tự thu hẹp theo phạm vi: BRANCH_MANAGER chỉ có chi nhánh mình.
  const names = useMemo(() => new Map(branches.data?.map((b): [number, string] => [b.branchId, b.name])), [branches.data]);
  const branchName = (id: number) => names.get(id) ?? `Chi nhánh #${id}`;

  const error = periodError(period.from, period.to);
  const query = { branchId: chainWide && branchId ? Number(branchId) : undefined, ...period };
  const common = { query, enabled: error === null, branchName };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Báo cáo theo phạm vi</h1>
        <p className="text-(--text-secondary)">
          {chainWide
            ? 'Sáu báo cáo cố định của toàn chuỗi hoặc một chi nhánh. Số liệu tính tại thời điểm xem.'
            : 'Sáu báo cáo cố định của chi nhánh bạn. Số liệu tính tại thời điểm xem.'}
        </p>
      </div>

      <ReportFilters
        chainWide={chainWide}
        branches={branches.data}
        branchName={branchName}
        branchId={branchId}
        onBranch={setBranchId}
        withPeriod={tab !== 'stock'}
        period={period}
        today={todayVn()}
        error={error}
        onPeriod={setPeriod}
      />

      <Tabs value={tab} onValueChange={(value) => setTab(value as ReportTab)}>
        <TabsList className="h-auto flex-wrap">
          {TAB_KEYS.map((key) => (
            <TabsTrigger key={key} value={key} className="min-h-10">
              {TABS[key]}
            </TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value={tab} className="mt-4">
          {tab === 'revenue' && <RevenueTab {...common} />}
          {tab === 'services' && <ServicesTab {...common} />}
          {tab === 'noShow' && <NoShowTab {...common} />}
          {tab === 'revaccination' && <RevaccinationTab {...common} />}
          {tab === 'stock' && <StockTab branchId={query.branchId} branchName={branchName} />}
          {tab === 'feedback' && <FeedbackTab {...common} />}
        </TabsContent>
      </Tabs>
    </div>
  );
}
