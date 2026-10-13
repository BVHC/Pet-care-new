import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { useTheme } from './useTheme';

beforeEach(() => document.documentElement.classList.remove('dark'));

describe('useTheme', () => {
  it('takes the dark theme off <html> when the staff area unmounts, keeping the choice', () => {
    localStorage.setItem('theme', 'dark');
    const { unmount } = renderHook(() => useTheme());
    expect(document.documentElement).toHaveClass('dark');

    unmount();

    expect(document.documentElement).not.toHaveClass('dark');
    expect(localStorage.getItem('theme')).toBe('dark');
  });
});
