import type { PaymentMethod, ProductCategory, QueuePriority, ServiceKind, Species, VisitStatus } from '../types/clinic';

export const SPECIES_LABEL: Record<Species, string> = { DOG: 'Chó', CAT: 'Mèo' };

export const KIND_LABEL: Record<ServiceKind, string> = { EXAM: 'Khám', VACCINATION: 'Tiêm', GROOMING: 'Grooming' };

export const VISIT_STATUS_LABEL: Record<VisitStatus, string> = {
  WAITING: 'Đang chờ',
  IN_PROGRESS: 'Đang làm',
  COMPLETED: 'Xong',
  CANCELLED: 'Đã hủy',
};

export const PRIORITY_LABEL: Record<QueuePriority, string> = {
  EMERGENCY: 'Cấp cứu',
  APPOINTMENT: 'Lịch hẹn',
  WALK_IN: 'Vãng lai',
};

export const CATEGORY_LABEL: Record<ProductCategory, string> = {
  FOOD: 'Thức ăn',
  HYGIENE: 'Vệ sinh',
  ACCESSORY: 'Phụ kiện',
  MEDICINE: 'Thuốc, bổ',
};

export const METHOD_LABEL: Record<PaymentMethod, string> = { CASH: 'Tiền mặt', BANK_TRANSFER: 'Chuyển khoản' };
