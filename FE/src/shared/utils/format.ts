// Hàm format thuần, không phụ thuộc React/domain.

export const formatVnd = (v: number) => `${v.toLocaleString('vi-VN')}₫`;

export const formatDate = (d: Date | string) =>
  new Date(d).toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric' });

export const formatDateTime = (d: Date | string) =>
  new Date(d).toLocaleString('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });

// Gộp className có điều kiện (thay clsx nhẹ cho tới khi cần).
export const cn = (...parts: Array<string | false | null | undefined>) =>
  parts.filter(Boolean).join(' ');

// Truncate text
export const truncate = (str: string, length: number) =>
  str.length > length ? str.slice(0, length) + '...' : str;
