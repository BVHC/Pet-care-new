// Theo dõi mọi `setInterval` mà một file test tạo ra, để dọn khi file xong (setup.ts).
// Vì sao: GSAP ScrollTrigger bật `setInterval(_sync, 250)` ngay khi `gsap.registerPlugin(ScrollTrigger)` chạy, và việc đó nằm ở mức module của
// HeroBanner, HomePage, OurServicesStage, StackingProcessCards, nên mọi test chỉ cần nạp <App /> cũng dính. `ScrollTrigger.killAll()` KHÔNG dừng
// interval (chỉ `ScrollTrigger.disable()` làm việc đó). Interval sống qua lúc jsdom bị hủy rồi ném "requestAnimationFrame is not defined", và vitest
// thoát mã 1 dù mọi ca xanh (chập chờn, ~1 trong 4 lần chạy cả bộ). Dọn theo dõi chung nên không phụ thuộc thư viện nào tạo interval.
type Timer = ReturnType<typeof setInterval>;

const open = new Set<Timer>();
let installed = false;

export function trackIntervals(): void {
  if (installed) return;
  installed = true;
  const real = globalThis.setInterval as unknown as (...args: unknown[]) => Timer;
  globalThis.setInterval = ((...args: unknown[]) => {
    const id = real(...args);
    open.add(id);
    return id;
  }) as unknown as typeof setInterval;
}

/** Dừng mọi interval còn sống do file test (hoặc mã nó nạp) tạo ra; trả về số interval đã dừng. */
export function clearTrackedIntervals(): number {
  const count = open.size;
  open.forEach((id) => clearInterval(id));
  open.clear();
  return count;
}
