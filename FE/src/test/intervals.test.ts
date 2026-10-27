import { describe, expect, it, vi } from 'vitest';
import { clearTrackedIntervals } from './intervals';

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

// Interval còn sống sau khi file test xong làm vitest thoát mã 1 dù mọi ca xanh (xem intervals.ts).
describe('dọn interval còn sót sau mỗi file test', () => {
  it('interval do file test tạo bị theo dõi, và dọn thì dừng hẳn', async () => {
    const tick = vi.fn();
    setInterval(tick, 5);
    await wait(40);
    expect(tick).toHaveBeenCalled();

    expect(clearTrackedIntervals()).toBeGreaterThanOrEqual(1);
    const seen = tick.mock.calls.length;
    await wait(40);
    expect(tick.mock.calls.length).toBe(seen);
  });

  it('interval của GSAP ScrollTrigger (bật ngay khi registerPlugin) cũng bị theo dõi và dừng', async () => {
    clearTrackedIntervals();
    const { default: gsap } = await import('gsap');
    const { ScrollTrigger } = await import('gsap/ScrollTrigger');
    gsap.registerPlugin(ScrollTrigger);

    expect(clearTrackedIntervals()).toBeGreaterThanOrEqual(1);
  });

  it('gọi lại khi không còn interval nào thì không làm gì', () => {
    clearTrackedIntervals();
    expect(clearTrackedIntervals()).toBe(0);
  });
});
