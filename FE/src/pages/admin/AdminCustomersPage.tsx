import { useState } from 'react';
import { CustomerDetailDialog } from '../../components/staff/reception/customers/CustomerDetailDialog';
import { CustomerSearchPanel } from '../../components/staff/reception/customers/CustomerSearchPanel';

/**
 * Tra cứu khách & thú cưng (UC23) cho bác sĩ và nhân viên chăm sóc: chỉ xem hồ sơ, thú cưng và hồ sơ sức khỏe.
 * Lễ tân tạo / sửa hồ sơ ở quầy (`/staff/reception`, nút Khách hàng).
 */
export function AdminCustomersPage() {
  const [openCustomerId, setOpenCustomerId] = useState<number | null>(null);
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Tra cứu khách & thú cưng</h1>
        <p className="text-(--text-secondary)">Hồ sơ khách dùng chung toàn chuỗi. Chỉ xem; lễ tân tạo và sửa hồ sơ tại quầy.</p>
      </div>
      <div className="card">
        <CustomerSearchPanel onOpenCustomer={setOpenCustomerId} showTitle={false} />
      </div>
      <CustomerDetailDialog customerId={openCustomerId} onClose={() => setOpenCustomerId(null)} readOnly />
    </div>
  );
}
