import { useMemo, useState } from 'react';

// Quản lý state phân trang client-side cho danh sách nhỏ đã tải sẵn.
export function usePagination<T>(items: T[], pageSize = 12) {
  const [page, setPage] = useState(1);
  const totalPages = Math.max(1, Math.ceil(items.length / pageSize));
  const pageItems = useMemo(
    () => items.slice((page - 1) * pageSize, page * pageSize),
    [items, page, pageSize],
  );
  return { page, setPage, totalPages, pageItems };
}
