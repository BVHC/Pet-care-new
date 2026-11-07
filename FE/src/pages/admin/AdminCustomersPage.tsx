import { useState } from 'react';
import { CustomerDetailDialog } from '../../components/staff/reception/customers/CustomerDetailDialog';
import { CustomerSearchPanel } from '../../components/staff/reception/customers/CustomerSearchPanel';
import { useAccount } from '../../shared/stores/session.store';
import { CustomerMergePanel } from './customers/CustomerMergePanel';

/**
 * Tra cứu khách & thú cưng (UC23) cho bác sĩ và nhân viên chăm sóc: chỉ xem hồ sơ, thú cưng và hồ sơ sức khỏe.
 * Lễ tân tạo / sửa hồ sơ ở quầy (`/staff/reception`, nút Khách hàng). Quản lý chi nhánh chỉ gộp hồ sơ (UC27): UC23 không có A05 nên tra cứu bị 403.
 */
export function AdminCustomersPage() {
  const [openCustomerId, setOpenCustomerId] = useState<number | null>(null);
  const canMerge = useAccount()?.role === 'BRANCH_MANAGER';
  const lookup = (
    <div className="card">
      <CustomerSearchPanel onOpenCustomer={setOpenCustomerId} showTitle={false} />
    </div>
  );
  if (canMerge) {
    return (
      <div className="space-y-6">
        <div>
          <h1 className="text-2xl font-semibold text-(--text-primary)">Gộp hồ sơ khách trùng</h1>
          <p className="text-(--text-secondary)">Chọn hồ sơ đích và hồ sơ nguồn theo mã, xem trước dữ liệu sẽ chuyển rồi xác nhận. Không hoàn tác được.</p>
        </div>
        <CustomerMergePanel />
      </div>
    );
  }
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Tra cứu khách & thú cưng</h1>
        <p className="text-(--text-secondary)">Hồ sơ khách dùng chung toàn chuỗi. Chỉ xem; lễ tân tạo và sửa hồ sơ tại quầy.</p>
      </div>
      {lookup}
      <CustomerDetailDialog customerId={openCustomerId} onClose={() => setOpenCustomerId(null)} readOnly />
    </div>
  );
}
