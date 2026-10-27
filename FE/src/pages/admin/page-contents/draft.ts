// Nháp của một trang (UC21): chuỗi thô người dùng đang gõ, cùng hình dạng với nội dung đã lưu. Đường dẫn chấm ("stats.2.label") dùng
// chung cho truy cập, cập nhật và khóa lỗi (path.join('.') của zod). Mọi cập nhật đều sao chép; không bao giờ sửa tại chỗ,
// vì bản đã lưu có thể chính là PAGE_DEFAULTS (parsePageContent trả đúng đối tượng đó khi nội dung sai dạng).
import { createContext, useContext } from 'react';

export type Draft = Record<string, unknown>;
export type FieldErrors = Record<string, string>;

const child = (node: unknown, key: string) => (node as Record<string, unknown> | undefined)?.[key];

export const getAt = (root: unknown, path: string): unknown => path.split('.').reduce(child, root);

export function updateAt(root: unknown, path: string, update: (current: unknown) => unknown): unknown {
  const [head, ...rest] = path.split('.');
  const next = rest.length ? updateAt(child(root, head!), rest.join('.'), update) : update(child(root, head!));
  if (Array.isArray(root)) return root.map((item, i) => (i === Number(head) ? next : item));
  return { ...(root as object), [head!]: next };
}

/** So sánh sâu, thô: dấu cách cuối vẫn là khác biệt (nháp chỉ bị xóa khi trùng đúng từng ký tự với bản đã lưu). */
export function sameContent(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length && keys.every((k) => sameContent(child(a, k), child(b, k)));
}

/** Số ký tự theo cách schema đếm: chuỗi đã bỏ khoảng trắng hai đầu, `.length`. */
export const countOf = (value: unknown) => (typeof value === 'string' ? value.trim().length : 0);

export interface EditorApi {
  draft: Draft;
  errors: FieldErrors;
  /** Đang lưu: ô nhập chỉ đọc, nút thêm / xóa / chuyển tắt. */
  busy: boolean;
  change: (path: string, update: (current: unknown) => unknown) => void;
}

export const EditorContext = createContext<EditorApi | null>(null);

export function useEditor(): EditorApi {
  const api = useContext(EditorContext);
  if (!api) throw new Error('Trường nhập phải nằm trong PageEditor');
  return api;
}
