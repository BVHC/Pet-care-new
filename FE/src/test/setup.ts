import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { clearTrackedIntervals, trackIntervals } from './intervals';

// Mọi interval một file test tạo ra (GSAP ScrollTrigger bật một cái khi nạp <App />) phải dừng khi file xong: xem intervals.ts.
trackIntervals();
afterAll(() => {
  clearTrackedIntervals();
});

// jsdom không có matchMedia; GSAP và nút đổi theme gọi nó khi import / mount.
if (!window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList;
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});
