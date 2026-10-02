type Listener = (message: string) => void;

const listeners = new Set<Listener>();

/** Speak a message through the workspace live region (screen readers). */
export function announce(message: string): void {
  listeners.forEach((listener) => listener(message));
}

export function onAnnouncement(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
