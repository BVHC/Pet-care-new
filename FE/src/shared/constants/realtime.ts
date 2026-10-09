import { ApiError } from '../api/api-error';

/**
 * Queues refresh by polling. Plan decision: polling first, WebSocket/SSE once a clinic
 * outgrows it — only this file and the query hooks change then.
 */
export const LIVE_REFRESH_MS = 5_000;

/** Rule violations and other 4xx answers will not change on retry; network and 5xx failures might. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.statusCode < 500) return false;
  return failureCount < 3;
}

export function retryDelay(attempt: number): number {
  return Math.min(1000 * 2 ** attempt, 15_000);
}

export const liveQuery = {
  refetchInterval: LIVE_REFRESH_MS,
  refetchIntervalInBackground: false,
  staleTime: 0,
  retry: shouldRetry,
  retryDelay,
} as const;
