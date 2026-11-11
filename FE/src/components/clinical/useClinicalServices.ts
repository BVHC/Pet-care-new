// Dịch vụ nội trú đang kinh doanh, lấy từ danh mục (catalog-v1 GET /services, nhóm Khám/Tiêm). Danh mục chưa có dòng nào thì trả mảng rỗng
// và màn hình báo "chưa có dịch vụ" thay vì cho gõ mã tay. Danh sách này chưa lọc theo chi nhánh (staff không có endpoint dịch vụ bật theo chi nhánh): BE từ chối dịch vụ chưa bật (BR-LT-13).
import { useServices } from '@/shared/hooks/useCatalog';
import type { Service } from '@/shared/types/catalog';

export function useClinicalServices(medicalType: 'INPATIENT'): { services: Service[]; isPending: boolean } {
  const query = useServices('MEDICAL');
  return { services: (query.data ?? []).filter((s) => s.isActive && s.medicalType === medicalType), isPending: query.isPending };
}
