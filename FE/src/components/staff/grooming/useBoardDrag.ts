import { useRef, useState, type PointerEvent } from 'react';
import type { GroomColumn } from '../../../shared/hooks/useKanban';

export interface DragGhost {
  visitId: number;
  from: GroomColumn;
  x: number;
  y: number;
}

function columnAt(x: number, y: number): GroomColumn | null {
  const column = document.elementFromPoint(x, y)?.closest<HTMLElement>('[data-column]');
  return (column?.dataset.column as GroomColumn | undefined) ?? null;
}

/**
 * Pointer-event drag for board cards — mouse, pen and touch (groomers work on tablets).
 * Keyboard users move cards with the card buttons instead.
 */
export function useBoardDrag({
  onDrop,
  canDrop,
}: {
  onDrop: (visitId: number, to: GroomColumn) => void;
  canDrop: (visitId: number, to: GroomColumn) => boolean;
}) {
  const [ghost, setGhost] = useState<DragGhost | null>(null);
  const start = useRef<{ x: number; y: number; visitId: number; from: GroomColumn; active: boolean } | null>(null);

  const handleProps = (visitId: number, from: GroomColumn) => ({
    onPointerDown: (e: PointerEvent<HTMLElement>) => {
      if (e.button !== 0) return;
      e.preventDefault();
      e.currentTarget.setPointerCapture(e.pointerId);
      start.current = { x: e.clientX, y: e.clientY, visitId, from, active: false };
    },
    onPointerMove: (e: PointerEvent<HTMLElement>) => {
      const s = start.current;
      if (!s) return;
      if (!s.active && Math.hypot(e.clientX - s.x, e.clientY - s.y) < 6) return;
      s.active = true;
      setGhost({ visitId: s.visitId, from: s.from, x: e.clientX, y: e.clientY });
    },
    onPointerUp: (e: PointerEvent<HTMLElement>) => {
      const s = start.current;
      start.current = null;
      setGhost(null);
      if (!s?.active) return;
      const to = columnAt(e.clientX, e.clientY);
      if (to && to !== s.from) onDrop(s.visitId, to);
    },
    onPointerCancel: () => {
      start.current = null;
      setGhost(null);
    },
  });

  const dropState = (column: GroomColumn): 'ok' | 'no' | undefined => {
    if (!ghost || column === ghost.from) return undefined;
    return canDrop(ghost.visitId, column) ? 'ok' : 'no';
  };

  return { ghost, handleProps, dropState };
}
