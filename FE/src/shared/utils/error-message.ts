import { ApiError, plainMessage } from '../api/api-error';

/** Message hiển thị; mã rule ở cuối message (00-method §3.5) tách ra, xem `notifyError`. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return plainMessage(error);
  return error instanceof Error && error.message ? error.message : 'Không thực hiện được. Thử lại sau ít phút.';
}
