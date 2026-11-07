import { plainMessage, toApiError } from '@/shared/api/api-error';

/** Câu báo lỗi cho thao tác thương mại: 409 nghĩa là bản ghi đã đổi trạng thái ở nơi khác (hook đã tải lại), còn lại hiện message của máy chủ bỏ mã rule. */
export function commerceError(e: unknown, what: string): string {
  const error = toApiError(e);
  return error.errorCode === 'INVALID_STATE_TRANSITION'
    ? `${what} vừa được xử lý ở nơi khác nên không làm được thao tác này. Đã tải lại trạng thái mới.`
    : plainMessage(error);
}
