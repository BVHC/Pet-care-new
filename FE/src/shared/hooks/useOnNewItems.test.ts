import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useOnNewItems } from './useOnNewItems';

type Item = { id: string };
const getId = (i: Item) => i.id;

function setup(initial: Item[] | undefined) {
  const onNew = vi.fn();
  const hook = renderHook(({ items }) => useOnNewItems(items, getId, onNew), {
    initialProps: { items: initial },
  });
  return { onNew, rerender: (items: Item[] | undefined) => hook.rerender({ items }) };
}

describe('useOnNewItems', () => {
  it('stays quiet on the first load', () => {
    const { onNew } = setup([{ id: 'a' }]);
    expect(onNew).not.toHaveBeenCalled();
  });

  it('reports items that appear later, exactly once', () => {
    const { onNew, rerender } = setup([{ id: 'a' }]);
    rerender([{ id: 'a' }, { id: 'b' }]);
    rerender([{ id: 'a' }, { id: 'b' }]);
    expect(onNew).toHaveBeenCalledTimes(1);
    expect(onNew).toHaveBeenCalledWith([{ id: 'b' }]);
  });

  it('takes a fresh baseline after the list goes away (leaving and re-entering a workspace)', () => {
    const { onNew, rerender } = setup([{ id: 'a' }]);
    rerender(undefined);
    rerender([{ id: 'a' }, { id: 'b' }]);
    expect(onNew).not.toHaveBeenCalled();
  });

  it('takes the baseline only once data has loaded', () => {
    const { onNew, rerender } = setup(undefined);
    rerender([{ id: 'a' }]);
    expect(onNew).not.toHaveBeenCalled();
  });
});
