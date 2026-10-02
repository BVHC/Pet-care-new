import { useEffect, useRef } from 'react';

/**
 * Calls `onNew` with items whose id has not been seen before. The first loaded list is the
 * baseline, so opening (or coming back to) a workspace does not announce what is already there.
 */
export function useOnNewItems<T>(
  items: T[] | undefined,
  getId: (item: T) => string,
  onNew: (fresh: T[]) => void,
): void {
  const seen = useRef<Set<string> | null>(null);
  const latest = useRef({ getId, onNew });
  latest.current = { getId, onNew };

  useEffect(() => {
    if (!items) {
      seen.current = null;
      return;
    }
    const { getId: idOf, onNew: report } = latest.current;
    if (!seen.current) {
      seen.current = new Set(items.map((item) => idOf(item)));
      return;
    }
    const known = seen.current;
    const fresh = items.filter((item) => !known.has(idOf(item)));
    if (fresh.length === 0) return;
    fresh.forEach((item) => known.add(idOf(item)));
    report(fresh);
  }, [items]);
}
