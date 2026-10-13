import { useCallback, useLayoutEffect, useState } from 'react';

const THEME_KEY = 'theme';

function initialDark(): boolean {
  try {
    const stored = localStorage.getItem(THEME_KEY);
    if (stored) return stored === 'dark';
  } catch {
    // storage blocked: fall back to the OS preference
  }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
}

export function useTheme() {
  const [dark, setDark] = useState(initialDark);

  // Before paint, so a dark page never flashes light; off again on unmount, so customer pages never inherit it.
  useLayoutEffect(() => {
    document.documentElement.classList.toggle('dark', dark);
    try {
      localStorage.setItem(THEME_KEY, dark ? 'dark' : 'light');
    } catch {
      // storage blocked: the choice lasts for this page only
    }
    return () => {
      document.documentElement.classList.remove('dark');
    };
  }, [dark]);

  const toggle = useCallback(() => setDark((d) => !d), []);
  return { dark, toggle };
}
