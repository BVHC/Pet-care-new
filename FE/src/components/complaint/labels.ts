import type { ComplaintClosedReason, ComplaintRelatedType, ComplaintStatus, ComplaintTopic } from '@/shared/types/complaint';

// Nhãn và tông màu của khiếu nại (UC85, UC86): dùng chung trang khách, trang xử lý và hộp lễ tân. Màu luôn đi kèm chữ.

export const COMPLAINT_TOPIC_LABELS: Record<ComplaintTopic, string> = {
  SERVICE_QUALITY: 'Chất lượng dịch vụ',
  GROOMING: 'Làm đẹp (grooming)',
  BOARDING: 'Lưu trú',
  PURCHASE: 'Mua hàng',
  STAFF: 'Thái độ nhân viên',
  OTHER: 'Vấn đề khác',
};

export const COMPLAINT_STATUS_LABELS: Record<ComplaintStatus, string> = {
  OPEN: 'Chờ nhận',
  IN_PROGRESS: 'Đang xử lý',
  RESOLVED: 'Đã giải quyết',
  CLOSED: 'Đã đóng',
};

export const COMPLAINT_STATUS_TONE: Record<ComplaintStatus, 'info' | 'warning' | 'success' | 'neutral'> = {
  OPEN: 'warning',
  IN_PROGRESS: 'info',
  RESOLVED: 'success',
  CLOSED: 'neutral',
};

export const COMPLAINT_RELATED_LABELS: Record<ComplaintRelatedType, string> = {
  ORDER: 'Đơn mua tại quầy',
  ONLINE_ORDER: 'Đơn hàng online',
  VISIT: 'Lượt khám',
  BOARDING: 'Đặt chỗ lưu trú',
};

export const COMPLAINT_CLOSED_LABELS: Record<ComplaintClosedReason, string> = {
  CUSTOMER: 'Khách đã đóng',
  WITHDRAWN: 'Khách đã rút',
  AUTO: 'Hệ thống tự đóng',
};
