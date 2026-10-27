// Kiểm nháp bằng đúng schema trang công khai dùng (PAGE_SCHEMAS): một trường sai làm trang công khai lặng lẽ về mặc định cả khóa,
// nên phải chặn trước khi lưu. Thông điệp tiếng Việt dựng từ issue của zod (giới hạn lấy từ schema, nên luôn khớp PAGE_LIMITS).
import type { ZodIssue } from 'zod';
import { PAGE_SCHEMAS, type PageContents } from '../../../shared/constants/page-content';
import type { PageKey } from '../../../shared/types/page-content';
import { countOf, getAt, type FieldErrors } from './draft';

export type ValidationResult<K extends PageKey> = { ok: true; data: PageContents[K] } | { ok: false; errors: FieldErrors };

function messageOf(issue: ZodIssue, draft: unknown): string {
  if (issue.code === 'too_small' && issue.type === 'string') return 'Không được để trống.';
  if (issue.code === 'too_big' && issue.type === 'string') return `Tối đa ${issue.maximum} ký tự (đang ${countOf(getAt(draft, issue.path.join('.')))}).`;
  if (issue.code === 'too_small' && issue.type === 'array') return issue.exact ? `Cần đúng ${issue.minimum} mục.` : `Cần ít nhất ${issue.minimum} mục.`;
  if (issue.code === 'too_big' && issue.type === 'array') return issue.exact ? `Cần đúng ${issue.maximum} mục.` : `Tối đa ${issue.maximum} mục.`;
  if (issue.code === 'invalid_type' && issue.received === 'undefined') return 'Không được để trống.';
  return 'Giá trị không hợp lệ.';
}

/** `data` là bản đã bỏ khoảng trắng hai đầu (đúng thứ cần gửi); `errors` theo đường dẫn chấm, mỗi đường dẫn một thông điệp đầu tiên. */
export function validatePage<K extends PageKey>(key: K, draft: unknown): ValidationResult<K> {
  const parsed = PAGE_SCHEMAS[key].safeParse(draft);
  if (parsed.success) return { ok: true, data: parsed.data as PageContents[K] };
  const errors: FieldErrors = {};
  for (const issue of parsed.error.issues) {
    const path = issue.path.join('.');
    if (!(path in errors)) errors[path] = messageOf(issue, draft);
  }
  return { ok: false, errors };
}
