/**
 * Lịch làm việc của tôi (UC35, UC36) — workforce-v1 #8–11. Lễ tân, bác sĩ, nhân viên chăm sóc: xem phân ca theo khoảng ngày, gửi yêu cầu
 * nghỉ / đổi ca, theo dõi và rút yêu cầu chờ duyệt. Vai khác (kể cả quản lý chi nhánh) không có chức năng này: không gọi API nào.
 */
import { useState } from 'react';
import { Lock } from 'lucide-react';
import { NoticeBar, type Notice } from '@/components/admin/form-ui';
import { StatePanel } from '@/components/staff/ui';
import { useAccount } from '@/shared/stores/session.store';
import { MyAssignments } from './workforce/MyAssignments';
import { MyRequests } from './workforce/MyRequests';

const STAFF_ROLES = ['RECEPTIONIST', 'VET', 'CARETAKER'];

export function MySchedulePage() {
  const role = useAccount()?.role;
  const [notice, setNotice] = useState<Notice | null>(null);
  if (!role || !STAFF_ROLES.includes(role)) {
    return <StatePanel icon={Lock} title="Chức năng dành cho lễ tân, bác sĩ và nhân viên chăm sóc" body="Quản lý chi nhánh phân ca và duyệt yêu cầu ở trang Nhân sự." />;
  }
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Lịch làm việc</h1>
        <p className="text-(--text-secondary)">Ca được phân cho bạn và các yêu cầu nghỉ / đổi ca. Yêu cầu gửi quá sát giờ vào ca sẽ bị từ chối.</p>
      </div>
      <NoticeBar notice={notice} />
      <MyAssignments onNotice={setNotice} />
      <MyRequests onNotice={setNotice} />
    </div>
  );
}
