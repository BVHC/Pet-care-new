/**
 * Nhân sự (UC34, UC37, UC38) — workforce-v1 #1–7, #12–14. Chỉ BRANCH_MANAGER (chi nhánh lấy từ người gọi): ca làm việc, phân ca + hủy +
 * ghi vắng đột xuất, duyệt / từ chối yêu cầu nghỉ hoặc đổi ca. Vai khác không có chức năng này (SUPER_MANAGER cũng không): không gọi API nào.
 */
import { Lock } from 'lucide-react';
import { StatePanel } from '@/components/staff/ui';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useAccount } from '@/shared/stores/session.store';
import { AssignmentsTab } from './workforce/AssignmentsTab';
import { RequestsTab } from './workforce/RequestsTab';
import { ShiftDefinitionsTab } from './workforce/ShiftDefinitionsTab';

export function AdminWorkforcePage() {
  const role = useAccount()?.role;
  if (role !== 'BRANCH_MANAGER') {
    return <StatePanel icon={Lock} title="Chức năng dành cho quản lý chi nhánh" body="Ca làm việc, phân ca và duyệt yêu cầu nghỉ / đổi ca do quản lý của từng chi nhánh phụ trách." />;
  }
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Nhân sự</h1>
        <p className="text-(--text-secondary)">Quản lý ca làm việc, lịch phân ca và yêu cầu nghỉ / đổi ca của nhân viên chi nhánh.</p>
      </div>
      <Tabs defaultValue="shifts">
        <TabsList aria-label="Nhân sự">
          <TabsTrigger value="shifts">Ca làm việc</TabsTrigger>
          <TabsTrigger value="assignments">Phân ca</TabsTrigger>
          <TabsTrigger value="requests">Yêu cầu nghỉ / đổi ca</TabsTrigger>
        </TabsList>
        <TabsContent value="shifts" className="mt-4">
          <ShiftDefinitionsTab />
        </TabsContent>
        <TabsContent value="assignments" className="mt-4">
          <AssignmentsTab />
        </TabsContent>
        <TabsContent value="requests" className="mt-4">
          <RequestsTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}
