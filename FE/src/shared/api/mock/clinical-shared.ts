// Phần dùng chung của ba mock tầng 3 nhóm CLINICAL (T57 bước A): homevisit, surgery, inpatient. File mới, không có route.
//
// 1) Dòng Order của Visit. BE: `VisitOrderApi.addServiceLine(visitId, serviceId, quantity)` và `addHomeFeeLine(visitId, amount)` (BE-2 sales, 09 §A–C) —
//    dịch vụ phẫu thuật vào Order khi hoàn tất, dịch vụ nội trú (số ngày × giá đã chốt) khi xuất viện, dòng HOME_FEE khi bắt đầu khám tại nhà.
//    sales.mock.ts chưa có hai hàm này và file đó ngoài danh sách được mở rộng của bước A: ở đây là *nguồn đăng ký* (`registerVisitOrderSource`),
//    mặc định trả mã dòng giả tăng dần mà KHÔNG sửa Order thật; luồng chính thêm hai hàm vào sales.mock rồi đăng ký (xem báo cáo).
// 2) Dịch vụ loại SURGERY / INPATIENT. catalog.mock chưa có hai loại này (09 Q1: BE-2 chưa xác nhận), nên bộ dịch vụ mẫu đặt ở đây (id 7001–7104);
//    tra cứu đi theo thứ tự: nguồn đăng ký (`registerClinicalServiceSource`) → dịch vụ mẫu → danh mục thật (dịch vụ khám / tiêm / thẩm mỹ / lưu trú, để
//    trả lời đúng "không phải loại SURGERY" thay vì "không tìm thấy").
// T57 (luồng chính, 6/11): hai ghi chú trên đã cũ. sales.mock.ts có `addServiceLine` / `addHomeFeeLine` (dòng thật vào Order của lượt) và catalog.mock.ts có dịch vụ 7001–7104 (cùng dữ liệu
//    với CLINICAL_SEED_SERVICES, test catalog.mock.test.ts so khớp); tier3-wiring.ts đăng ký `registerVisitOrderSource` và `registerClinicalServiceSource` nên chạy qua mock-api thì giá và trạng thái
//    ngừng kinh doanh lấy từ danh mục. Nguồn mặc định và dịch vụ mẫu ở đây chỉ còn cho test từng mock (import thẳng, không qua tier3-wiring).
import { branchBooking } from './branch.mock';
import { catalogServices } from './catalog.mock';

// ─── Dòng Order ───

export interface VisitOrderSource {
  /** Trả mã dòng Order vừa thêm, null khi lượt không có Order đang mở. Giá = `unitPrice` nếu truyền (nội trú: giá đã chốt lúc nhập viện), không thì giá dịch vụ lúc này (BR-BH-03). */
  addServiceLine(visitId: number, serviceId: number, quantity: number, actorId: number, unitPrice?: number): number | null;
  /** Dòng HOME_FEE (không có service / product), số tiền chốt lúc đặt lịch. */
  addHomeFeeLine(visitId: number, amount: number, actorId: number): number | null;
}

let nextLineId = 880_000;
let orderSource: VisitOrderSource = { addServiceLine: () => ++nextLineId, addHomeFeeLine: () => ++nextLineId };

export function registerVisitOrderSource(source: VisitOrderSource) {
  orderSource = source;
}
export const addServiceLineToVisitOrder = (visitId: number, serviceId: number, quantity: number, actorId: number, unitPrice?: number) =>
  orderSource.addServiceLine(visitId, serviceId, quantity, actorId, unitPrice);
export const addHomeFeeLineToVisitOrder = (visitId: number, amount: number, actorId: number) => orderSource.addHomeFeeLine(visitId, amount, actorId);

// ─── Dịch vụ ───

export type ClinicalMedicalType = 'EXAM' | 'VACCINE' | 'SURGERY' | 'INPATIENT';

export interface ClinicalService {
  serviceId: number;
  name: string;
  /** Giá hiện tại (surgery: một lần; inpatient: theo ngày). */
  price: number;
  isActive: boolean;
  /** null với dịch vụ không thuộc nhóm Khám/Tiêm. */
  medicalType: ClinicalMedicalType | null;
  /** Dịch vụ đang bật ở chi nhánh (UC33). */
  enabledAt(branchId: number): boolean;
}

interface SeedService {
  serviceId: number;
  name: string;
  price: number;
  isActive: boolean;
  medicalType: 'SURGERY' | 'INPATIENT';
  branches: number[];
}

/** 7003, 7103 chỉ bật ở chi nhánh 2 và 7004, 7104 ngừng kinh doanh: đủ để thử "không bật ở chi nhánh" và "ngừng" từ chi nhánh 1. */
export const CLINICAL_SEED_SERVICES: readonly SeedService[] = [
  { serviceId: 7001, name: 'Triệt sản chó mèo', price: 1_800_000, isActive: true, medicalType: 'SURGERY', branches: [1] },
  { serviceId: 7002, name: 'Phẫu thuật mô mềm', price: 3_500_000, isActive: true, medicalType: 'SURGERY', branches: [1] },
  { serviceId: 7003, name: 'Phẫu thuật nha khoa', price: 2_200_000, isActive: true, medicalType: 'SURGERY', branches: [2] },
  { serviceId: 7004, name: 'Cắt u (ngừng kinh doanh)', price: 2_800_000, isActive: false, medicalType: 'SURGERY', branches: [1] },
  { serviceId: 7101, name: 'Nội trú điều trị (theo ngày)', price: 350_000, isActive: true, medicalType: 'INPATIENT', branches: [1] },
  { serviceId: 7102, name: 'Nội trú chăm sóc đặc biệt (theo ngày)', price: 600_000, isActive: true, medicalType: 'INPATIENT', branches: [1] },
  { serviceId: 7103, name: 'Nội trú theo dõi (chi nhánh 2)', price: 300_000, isActive: true, medicalType: 'INPATIENT', branches: [2] },
  { serviceId: 7104, name: 'Nội trú cũ (ngừng kinh doanh)', price: 300_000, isActive: false, medicalType: 'INPATIENT', branches: [1] },
];

let serviceSource: (serviceId: number) => ClinicalService | undefined = () => undefined;

/** Nối với catalog khi catalog.mock có loại SURGERY / INPATIENT (09 Q1); trả undefined để rơi về dịch vụ mẫu. */
export function registerClinicalServiceSource(source: (serviceId: number) => ClinicalService | undefined) {
  serviceSource = source;
}

export function findClinicalService(serviceId: number): ClinicalService | undefined {
  const registered = serviceSource(serviceId);
  if (registered) return registered;
  const seeded = CLINICAL_SEED_SERVICES.find((x) => x.serviceId === serviceId);
  if (seeded) return { ...seeded, enabledAt: (branchId) => seeded.branches.includes(branchId) };
  const catalog = catalogServices().find((x) => x.serviceId === serviceId);
  return (
    catalog && {
      serviceId: catalog.serviceId,
      name: catalog.name,
      price: catalog.price,
      isActive: catalog.isActive,
      medicalType: catalog.medicalType ?? null,
      enabledAt: (branchId) => branchBooking(branchId)?.isServiceEnabled(catalog.serviceId) ?? false,
    }
  );
}
