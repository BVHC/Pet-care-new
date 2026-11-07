import { useEffect, useReducer } from 'react';
import { useSession } from '../stores/session.store';

/**
 * Cổng của mọi poll (D055, ADR-0091): false khi access token đã hết hạn nên poll dừng, không bắn request 401 vô ích và không ai làm mới "hộ" người đang rảnh.
 * Tự chuyển false đúng lúc hết hạn (timer này chỉ đổi state hiển thị, không làm mới gì) và true lại khi token mới về, tức người dùng đã thao tác.
 * Phiên cũ không biết hạn access thì luôn true.
 */
export function useAccessValid(): boolean {
  const expiresAt = useSession((s) => s.accessTokenExpiresAt);
  const [, rerender] = useReducer((n: number) => n + 1, 0);
  useEffect(() => {
    const ms = expiresAt ? Date.parse(expiresAt) - Date.now() : 0;
    if (ms <= 0) return;
    const timer = setTimeout(rerender, ms);
    return () => clearTimeout(timer);
  }, [expiresAt]);
  return !expiresAt || Date.parse(expiresAt) > Date.now();
}
