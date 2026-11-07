import { isAxiosError } from 'axios';
import { ApiError, plainMessage, toApiError } from '../api/api-error';

/** Message hiển thị; mã rule ở cuối message (00-method §3.5) tách ra, xem `notifyError`. Lỗi axios thô (từ React Query) được chuẩn hóa trước. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError || isAxiosError(error)) return plainMessage(toApiError(error));
  return error instanceof Error && error.message ? error.message : 'Không thực hiện được. Thử lại sau ít phút.';
}
