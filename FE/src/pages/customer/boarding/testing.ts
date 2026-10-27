// Đồ dùng chung cho test màn hình lưu trú của khách (không nạp ở bản chạy thật).
import { AxiosError, AxiosHeaders } from 'axios';
import type { ErrorCode } from '@/shared/api/api-error';
import type { Booking } from '@/shared/types/boarding';

/** Đặt chỗ giả để dựng các trạng thái mà dữ liệu mẫu của khach@petcare.vn không có (đang ở, quá hạn, không đến…). */
export const fakeBooking = (over: Partial<Booking> = {}): Booking => ({
  bookingId: 100,
  code: 'LT-2610-09100',
  customerId: 1,
  customerName: 'Nguyễn Thu Trang',
  petId: 2,
  petName: 'Bim',
  branchId: 1,
  kennelTypeId: 5,
  kennelTypeName: 'Chuồng chó nhỏ',
  kennelId: null,
  kennelCode: null,
  checkInDate: '2026-10-26',
  checkOutDate: '2026-10-28',
  actualCheckInAt: null,
  actualCheckOutAt: null,
  nightlyPrice: 200_000,
  status: 'BOOKED',
  endReason: null,
  channel: 'ONLINE',
  lateCancel: false,
  cancelSource: null,
  cancelReason: null,
  overdueSince: null,
  activeOrderId: null,
  missingCareLogToday: false,
  ...over,
});

export const asPage = (rows: Booking[]) => ({ content: rows, page: 0, size: 100, totalElements: rows.length, totalPages: 1 });

/** Lỗi axios mang envelope lỗi của BE, để `toApiError` / `plainMessage` đọc như lỗi thật. */
export function apiFailure(status: number, errorCode: ErrorCode, message: string): AxiosError {
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, {
    status,
    statusText: '',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data: { success: false, errorCode, message, statusCode: status, timestamp: '2026-10-23T02:00:00.000Z', traceId: 'test' },
  });
}
