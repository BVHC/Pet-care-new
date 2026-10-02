import { useState } from 'react';
import { useOnNewItems } from './useOnNewItems';

/** Ids that just appeared in a polled list, kept for `ms` so rows can flash once. */
export function useFreshIds<T>(items: T[] | undefined, getId: (item: T) => string, ms = 1800): Set<string> {
  const [fresh, setFresh] = useState<Set<string>>(() => new Set());
  useOnNewItems(items, getId, (arrived) => {
    const ids = arrived.map(getId);
    setFresh((prev) => new Set([...prev, ...ids]));
    window.setTimeout(() => {
      setFresh((prev) => {
        const next = new Set(prev);
        ids.forEach((id) => next.delete(id));
        return next;
      });
    }, ms);
  });
  return fresh;
}
