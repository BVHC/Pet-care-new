// Hook riêng của hộp thoại Nhật ký chăm sóc (T48 UC57). Không sửa useBoarding.ts: thêm ở đây.
import { useCallback, useEffect, useState } from 'react';

/**
 * Đồng hồ của hộp thoại: đọc lại `Date.now()` mỗi `intervalMs` VÀ mỗi lần cửa sổ / tab lấy lại tiêu điểm (tab nền bị trình duyệt làm chậm interval,
 * mà "hôm nay" và hạn sửa 60 phút phải đúng khi người dùng quay lại). Dùng cho: ngày hôm nay giờ VN, còn bao nhiêu phút để sửa, ẩn nút Sửa khi hết hạn.
 * `sync` đọc lại ngay: gọi sau khi lưu, nếu không đồng hồ cũ (tới 10 giây) làm nhãn "còn N phút" của mục vừa ghi thành 61.
 */
export function useNow(intervalMs = 10_000): [now: number, sync: () => void] {
  const [now, setNow] = useState(() => Date.now());
  const sync = useCallback(() => setNow(Date.now()), []);
  useEffect(() => {
    const timer = setInterval(sync, intervalMs);
    window.addEventListener('focus', sync);
    document.addEventListener('visibilitychange', sync);
    return () => {
      clearInterval(timer);
      window.removeEventListener('focus', sync);
      document.removeEventListener('visibilitychange', sync);
    };
  }, [intervalMs, sync]);
  return [now, sync];
}
