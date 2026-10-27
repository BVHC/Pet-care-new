/** Mẫu slug của BE (`ContentText.isSlug`): chữ thường, số, gạch ngang đơn giữa các nhóm. */
export const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

/**
 * Gợi ý đường dẫn từ tiêu đề. `normalize('NFD')` tách dấu thanh và dấu mũ nhưng KHÔNG tách `đ` / `Đ` (không có dạng tổ hợp), nên
 * hai chữ này phải đổi tường minh trước, nếu không "Đường đi" thành "uong-i". Không còn ký tự hợp lệ thì trả chuỗi rỗng.
 */
export function slugify(title: string, maxLength = 270): string {
  return title
    .replace(/đ/g, 'd')
    .replace(/Đ/g, 'D')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, maxLength)
    .replace(/-+$/, '');
}
