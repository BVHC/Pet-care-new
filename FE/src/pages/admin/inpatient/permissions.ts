import type { Role } from '@/shared/types/auth';
import type { AdmissionOrderType } from '@/shared/types/inpatient';

/**
 * inpatient-v1 §B (BE đo 25/11/2026): xuất viện, ra và hủy y lệnh chỉ VET đang được gán Visit của ca (`isAssignee`, BR-LT-13, BR-LT-15; VET khác nhận 403);
 * nhập viện, thực hiện y lệnh, ghi theo dõi thì VET nào của chi nhánh cũng làm được; CARETAKER chỉ làm y lệnh không phải thuốc và ghi theo dõi; lễ tân chỉ xem;
 * quản lý chi nhánh, quản lý chuỗi, quản trị không có endpoint nào (ADR-0148).
 */
export function inpatientPermissions(role: Role | undefined, isAssignee = false) {
  const vet = role === 'VET';
  return {
    admit: vet,
    discharge: vet && isAssignee,
    createOrder: vet && isAssignee,
    cancelOrder: vet && isAssignee,
    completeOrder: (type: AdmissionOrderType) => vet || (role === 'CARETAKER' && type !== 'MEDICATION'),
    observe: vet || role === 'CARETAKER',
  };
}

export type InpatientPermissions = ReturnType<typeof inpatientPermissions>;
