import { describe, expect, it } from 'vitest';
import { ApiError } from '@/shared/api/api-error';
import { commerceError } from './commerce-error';

// Câu lỗi của shop-v1 #12 đến #18 trên BE thật (đo 17/11/2026, petcare_t67). Giao diện chặn lỗi nhập ở máy khách nên không tới được ba ca đầu qua màn hình;
// test này ghim phần ánh xạ lỗi máy chủ thành câu hiện cho lễ tân (lỗi bị nuốt hoặc hiện nguyên tên trường / mã rule là lỗi giao diện).
const err = (code: ConstructorParameters<typeof ApiError>[0], message: string, status: number) => new ApiError(code, message, status);

describe('commerceError', () => {
  it('400 BR-BH-11: câu của máy chủ, bỏ mã rule ở cuối', () => {
    expect(commerceError(err('BUSINESS_RULE_VIOLATION', 'Thiếu mã vận đơn (BR-BH-11)', 400), 'Đơn')).toBe('Thiếu mã vận đơn');
    expect(commerceError(err('BUSINESS_RULE_VIOLATION', 'Đơn đã giao lại tối đa 2 lần, không giao lại thêm được (BR-BH-11)', 400), 'Đơn')).toBe(
      'Đơn đã giao lại tối đa 2 lần, không giao lại thêm được',
    );
  });

  it('400 VALIDATION_FAILED (thiếu lý do giao thất bại): bỏ tên trường "reason: "', () => {
    expect(commerceError(err('VALIDATION_FAILED', 'reason: Lý do là bắt buộc', 400), 'Đơn')).toBe('Lý do là bắt buộc');
    expect(commerceError(err('VALIDATION_FAILED', 'carrier: Đơn vị vận chuyển tối đa 100 ký tự', 400), 'Đơn')).toBe('Đơn vị vận chuyển tối đa 100 ký tự');
  });

  it('409 INVALID_STATE_TRANSITION: đơn vừa đổi trạng thái ở nơi khác, không lộ tên trạng thái kỹ thuật', () => {
    const text = commerceError(err('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái đơn hàng online từ PAID sang SHIPPED', 409), 'Đơn');
    expect(text).toBe('Đơn vừa được xử lý ở nơi khác nên không làm được thao tác này. Đã tải lại trạng thái mới.');
    expect(text).not.toMatch(/PAID|SHIPPED/);
  });

  it('403: câu của máy chủ, kể cả khi sai chi nhánh', () => {
    expect(commerceError(err('ACCESS_DENIED', 'Không có quyền thực hiện thao tác này', 403), 'Đơn')).toBe('Không có quyền thực hiện thao tác này');
    expect(commerceError(err('ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này', 403), 'Đơn')).toBe('Không có quyền truy cập dữ liệu này');
  });
});
