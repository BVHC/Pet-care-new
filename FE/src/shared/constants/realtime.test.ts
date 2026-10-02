import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/clinic.api';
import { liveQuery, retryDelay, shouldRetry } from './realtime';

describe('live query policy', () => {
  it('does not retry business rule errors', () => {
    expect(shouldRetry(0, new ApiError('BR-TN-05', 'Lượt chưa được gán', 400))).toBe(false);
  });

  it('retries network failures up to three times', () => {
    expect(shouldRetry(2, new TypeError('Failed to fetch'))).toBe(true);
    expect(shouldRetry(3, new TypeError('Failed to fetch'))).toBe(false);
  });

  it('retries server errors', () => {
    expect(shouldRetry(0, new ApiError('INTERNAL_ERROR', 'Lỗi máy chủ', 503))).toBe(true);
  });

  it('backs off exponentially up to 15 seconds', () => {
    expect([0, 1, 2, 3, 4, 5].map(retryDelay)).toEqual([1000, 2000, 4000, 8000, 15000, 15000]);
  });

  it('polls every 5 seconds and pauses while the tab is hidden', () => {
    expect(liveQuery).toMatchObject({ refetchInterval: 5000, refetchIntervalInBackground: false });
  });
});
