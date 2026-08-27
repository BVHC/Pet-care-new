import { useEffect, useState } from 'react';

// Trả về giá trị trễ `delay`ms — dùng cho ô tìm kiếm/filter tránh gọi liên tục.
export function useDebounce<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(t);
  }, [value, delay]);
  return debounced;
}
