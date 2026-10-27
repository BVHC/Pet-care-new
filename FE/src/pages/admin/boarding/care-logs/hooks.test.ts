import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useNow } from './hooks';

afterEach(() => vi.useRealTimers());

describe('useNow', () => {
  it('đọc lại đồng hồ theo chu kỳ', () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date('2026-10-23T09:00:00+07:00'));
    const { result } = renderHook(() => useNow(1000));
    const start = result.current[0];
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(result.current[0]).toBe(start + 1000);
  });

  it('đọc lại khi cửa sổ lấy lại tiêu điểm hoặc tab hiện lại (tab nền bị trình duyệt làm chậm interval)', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-23T09:00:00+07:00'));
    const { result } = renderHook(() => useNow());
    const start = result.current[0];
    vi.setSystemTime(new Date('2026-10-23T09:05:00+07:00'));
    act(() => void window.dispatchEvent(new Event('focus')));
    expect(result.current[0]).toBe(start + 5 * 60_000);
    vi.setSystemTime(new Date('2026-10-23T09:06:00+07:00'));
    act(() => void document.dispatchEvent(new Event('visibilitychange')));
    expect(result.current[0]).toBe(start + 6 * 60_000);
    vi.setSystemTime(new Date('2026-10-23T09:07:30+07:00'));
    act(() => result.current[1]()); // sync: đọc lại ngay, không chờ sự kiện
    expect(result.current[0]).toBe(start + 7.5 * 60_000);
  });

  it('gỡ interval và hai bộ lắng nghe khi hộp thoại đóng', () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    const { unmount } = renderHook(() => useNow(1000));
    expect(vi.getTimerCount()).toBe(1);
    const removed = vi.spyOn(window, 'removeEventListener');
    unmount();
    expect(vi.getTimerCount()).toBe(0);
    expect(removed).toHaveBeenCalledWith('focus', expect.any(Function));
  });
});
