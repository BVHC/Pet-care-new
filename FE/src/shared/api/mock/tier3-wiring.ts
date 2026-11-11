// Nối chéo các mock tầng 3 (T57): mỗi module chỉ biết nguồn mặc định của nó, chỗ này đăng ký nguồn thật của module khác.
// Chỉ mock-api.ts nhập file này, nên test của từng mock (import thẳng) vẫn chạy với nguồn mặc định.
import { registerAdmissionSource } from './boarding.mock';
import { branchBooking } from './branch.mock';
import { registerClosureSources } from './branchlife.mock';
import { catalogServices } from './catalog.mock';
import { CLINICAL_SEED_SERVICES, registerClinicalServiceSource, registerVisitOrderSource } from './clinical-shared';
import { shippingFeeConfig } from './fees.mock';
import { hasActiveAdmission } from './inpatient.mock';
import { recordMaterialUsage } from './materialuse.mock';
import { pendingRefundCount } from './returns.mock';
import { addServiceLine, serviceIdsOfVisit } from './sales.mock';
import { openOnlineOrderCount, registerShippingFeeSource } from './shop.mock';
import { inTransitTransfersOf, openStockCountsOf } from './stockops.mock';
import { registerCompletionListener } from './visit-clinical.mock';

registerShippingFeeSource(() => shippingFeeConfig());
registerClosureSources({
  unfinishedOnlineOrders: openOnlineOrderCount,
  pendingRefunds: pendingRefundCount,
  inTransitTransfers: inTransitTransfersOf,
  openStockCounts: openStockCountsOf,
});

// ─── Lâm sàng (T57 bước A → luồng chính) ───

// Xuất viện nội trú: dòng vào Order thật của lượt (09 §C).
registerVisitOrderSource({ addServiceLine });
// BR-LT-17: bắt đầu trả thú / giao thú khi thú còn ca nội trú ADMITTED → 409.
registerAdmissionSource(hasActiveAdmission);
// ST07 (BR-KB-12): hoàn tất lượt thành công thì trừ vật tư theo định mức của mọi dịch vụ đang có trong Order của lượt.
registerCompletionListener('materialuse', ({ visitId, branchId }) => {
  recordMaterialUsage({ visitId, branchId, serviceIds: serviceIdsOfVisit(visitId) });
});
// Dịch vụ INPATIENT lấy từ danh mục (SM đổi giá, ngừng kinh doanh có hiệu lực ngay). Bật theo chi nhánh: dòng mẫu của clinical-shared HOẶC bật ở UC33.
// shortcut: branch.mock chưa gieo sẵn 7101–7104 vào `services` nên chi nhánh mẫu bật bằng danh sách riêng; tắt dịch vụ mẫu ở UC33 chưa có hiệu lực. Gieo vào branch.mock rồi bỏ danh sách này.
registerClinicalServiceSource((serviceId) => {
  const service = catalogServices().find((x) => x.serviceId === serviceId);
  if (service?.medicalType !== 'INPATIENT') return undefined;
  const seeded = CLINICAL_SEED_SERVICES.find((x) => x.serviceId === serviceId)?.branches ?? [];
  return {
    serviceId,
    name: service.name,
    price: service.price,
    isActive: service.isActive,
    medicalType: service.medicalType,
    enabledAt: (branchId) => seeded.includes(branchId) || !!branchBooking(branchId)?.isServiceEnabled(serviceId),
  };
});
