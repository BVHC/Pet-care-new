import type { Role } from '@/shared/types/auth';
import type { AdmissionOrderType } from '@/shared/types/inpatient';

/** inpatient-v1 §B: VET làm mọi việc; CARETAKER chỉ làm y lệnh không phải thuốc và ghi theo dõi; lễ tân chỉ xem. */
export function inpatientPermissions(role: Role | undefined) {
  const vet = role === 'VET';
  return {
    admit: vet,
    discharge: vet,
    createOrder: vet,
    cancelOrder: vet,
    completeOrder: (type: AdmissionOrderType) => vet || (role === 'CARETAKER' && type !== 'MEDICATION'),
    observe: vet || role === 'CARETAKER',
  };
}

export type InpatientPermissions = ReturnType<typeof inpatientPermissions>;
