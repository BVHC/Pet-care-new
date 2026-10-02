import { useEffect, useState } from 'react';
import { onAnnouncement } from '../../shared/utils/announce';

/** Polite live region fed by announce(); queue changes and card moves are read out. */
export function LiveRegion() {
  const [message, setMessage] = useState('');

  useEffect(
    () =>
      onAnnouncement((text) => {
        // Clear first so the same sentence twice is still announced.
        setMessage('');
        window.requestAnimationFrame(() => setMessage(text));
      }),
    [],
  );

  return (
    <div role="status" aria-live="polite" aria-atomic="true" className="sr-only">
      {message}
    </div>
  );
}
