import { useCallback, useEffect, useState } from 'react';

const THEME_KEY = 'theme'; // same key as AdminHeader

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

  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark);
    try {
      localStorage.setItem(THEME_KEY, dark ? 'dark' : 'light');
    } catch {
      // storage blocked: the choice lasts for this page only
    }
  }, [dark]);

  const toggle = useCallback(() => setDark((d) => !d), []);
  return { dark, toggle };
}
