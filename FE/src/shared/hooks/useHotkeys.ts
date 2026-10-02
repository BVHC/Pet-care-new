import { useEffect, useRef } from 'react';

/** Normalised combo for a keydown: "mod+k", "alt+1", "f1", "escape", "?". Ctrl and Cmd are both "mod". */
export function comboOf(e: KeyboardEvent): string {
  // Alt+digit types a symbol on macOS, so read the physical key.
  const digit = e.altKey && e.code?.startsWith('Digit') ? e.code.slice(5) : null;
  const key = digit ?? (e.key === ' ' ? 'space' : e.key.toLowerCase());
  const parts: string[] = [];
  if (e.ctrlKey || e.metaKey) parts.push('mod');
  if (e.altKey) parts.push('alt');
  if (e.shiftKey && e.key.length > 1) parts.push('shift');
  parts.push(key);
  return parts.join('+');
}

const FIELD_TAGS = new Set(['INPUT', 'TEXTAREA', 'SELECT']);

/** Plain keys belong to the field being typed in; modifier combos, F-keys and Escape stay global. */
export function shouldIgnore(combo: string, target: EventTarget | null): boolean {
  const typing = target instanceof HTMLElement && (FIELD_TAGS.has(target.tagName) || target.isContentEditable);
  if (!typing) return false;
  const global = combo.startsWith('mod+') || combo.startsWith('alt+') || /^f[0-9]{1,2}$/.test(combo) || combo === 'escape';
  return !global;
}

export type HotkeyBindings = Record<string, (e: KeyboardEvent) => void>;

/** Window-level shortcuts; bindings can change every render without re-subscribing. */
export function useHotkeys(bindings: HotkeyBindings, enabled = true): void {
  const latest = useRef(bindings);
  latest.current = bindings;

  useEffect(() => {
    if (!enabled) return;
    const onKeyDown = (e: KeyboardEvent) => {
      // isComposing: Vietnamese IMEs (Telex/VNI) compose through keydown.
      if (e.defaultPrevented || e.isComposing) return;
      const combo = comboOf(e);
      const handler = latest.current[combo];
      // A dialog owns the keyboard: Ctrl+Enter must not pay or complete behind it.
      if (!handler || shouldIgnore(combo, e.target) || document.querySelector('[role="dialog"]')) return;
      e.preventDefault();
      handler(e);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [enabled]);
}
