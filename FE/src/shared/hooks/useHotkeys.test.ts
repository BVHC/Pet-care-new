import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { comboOf, shouldIgnore, useHotkeys } from './useHotkeys';

const key = (init: KeyboardEventInit) => new KeyboardEvent('keydown', init);

describe('comboOf', () => {
  it('treats Ctrl and Cmd alike', () => {
    expect(comboOf(key({ key: 'k', ctrlKey: true }))).toBe('mod+k');
    expect(comboOf(key({ key: 'k', metaKey: true }))).toBe('mod+k');
  });

  it('reads Alt+digit from the physical key', () => {
    expect(comboOf(key({ key: '¡', code: 'Digit1', altKey: true }))).toBe('alt+1');
  });

  it('names function and control keys', () => {
    expect(comboOf(key({ key: 'F1' }))).toBe('f1');
    expect(comboOf(key({ key: 'Enter', ctrlKey: true }))).toBe('mod+enter');
    expect(comboOf(key({ key: 'Escape' }))).toBe('escape');
  });

  it('keeps shifted characters as typed', () => {
    expect(comboOf(key({ key: '?', shiftKey: true }))).toBe('?');
  });
});

describe('shouldIgnore', () => {
  it('ignores plain keys while typing in a field', () => {
    expect(shouldIgnore('?', document.createElement('input'))).toBe(true);
  });

  it('lets modifier, function and Escape keys through while typing', () => {
    const field = document.createElement('textarea');
    expect(shouldIgnore('mod+enter', field)).toBe(false);
    expect(shouldIgnore('f1', field)).toBe(false);
    expect(shouldIgnore('escape', field)).toBe(false);
  });

  it('handles plain keys outside fields', () => {
    expect(shouldIgnore('?', document.body)).toBe(false);
  });
});

describe('useHotkeys', () => {
  const ctrlEnter = () =>
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true, cancelable: true }));

  it('runs the bound handler', () => {
    const pay = vi.fn();
    renderHook(() => useHotkeys({ 'mod+enter': pay }));
    ctrlEnter();
    expect(pay).toHaveBeenCalledTimes(1);
  });

  it('stays out of the way while a dialog is open', () => {
    const pay = vi.fn();
    renderHook(() => useHotkeys({ 'mod+enter': pay }));
    const dialog = document.createElement('div');
    dialog.setAttribute('role', 'dialog');
    document.body.append(dialog);
    ctrlEnter();
    dialog.remove();
    expect(pay).not.toHaveBeenCalled();
  });
});
