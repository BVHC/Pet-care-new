import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useSession } from '../stores/session.store';
import { useAccessValid } from './useAccessValid';

const inMs = (ms: number) => new Date(Date.now() + ms).toISOString();

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-11-07T08:00:00Z'));
});

afterEach(() => {
  useSession.getState().clear();
  vi.useRealTimers();
});

describe('useAccessValid (cổng của mọi poll)', () => {
  it('true khi chưa có hạn access (chưa đăng nhập hoặc phiên cũ)', () => {
    useSession.setState({ accessTokenExpiresAt: null });
    expect(renderHook(() => useAccessValid()).result.current).toBe(true);
  });

  it('false khi access token đã hết hạn, kể cả đúng mốc hạn', () => {
    useSession.setState({ accessTokenExpiresAt: inMs(0) });
    expect(renderHook(() => useAccessValid()).result.current).toBe(false);
  });

  it('chuyển sang false đúng lúc hết hạn mà không cần ai làm gì, rồi true lại khi token được làm mới', () => {
    useSession.setState({ accessTokenExpiresAt: inMs(60_000) });
    const { result } = renderHook(() => useAccessValid());
    expect(result.current).toBe(true);

    act(() => void vi.advanceTimersByTime(59_999));
    expect(result.current).toBe(true);
    act(() => void vi.advanceTimersByTime(1));
    expect(result.current).toBe(false);

    act(() => useSession.setState({ accessTokenExpiresAt: inMs(600_000) }));
    expect(result.current).toBe(true);
  });
});
