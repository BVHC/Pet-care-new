// Google Identity Services (GIS): nạp script một lần cho cả app, `initialize` một lần, credential đi qua một handler hiện hành.
// Kiểu tối thiểu cho `window.google` ở đây, không thêm package @types (spec 2026-11-24 §6.8).

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize(config: { client_id: string; callback: (response: { credential: string }) => void }): void;
          renderButton(parent: HTMLElement, options: Record<string, unknown>): void;
        };
      };
    };
  }
}

const SCRIPT_SRC = 'https://accounts.google.com/gsi/client';

/** Client ID đọc lúc gọi (không ở cấp module) để test đổi được bằng `vi.stubEnv`. Trống: ẩn nút Google. */
export const googleClientId = (): string => String(import.meta.env.VITE_GOOGLE_CLIENT_ID ?? '').trim();

// Khóa theo thẻ script / đối tượng `id` của GIS thay vì cờ cấp module: một thẻ chỉ có một promise, và thẻ bị gỡ thì lần sau nạp lại.
const loads = new WeakMap<HTMLScriptElement, Promise<void>>();
const initialized = new WeakSet<object>();
let handler: ((credential: string) => void) | null = null;

/** Chèn thẻ script GIS đúng một lần; reject khi không tải được (offline, bị chặn). */
export function loadGoogleIdentity(): Promise<void> {
  let script = document.querySelector<HTMLScriptElement>(`script[src="${SCRIPT_SRC}"]`);
  if (!script) {
    script = document.createElement('script');
    script.src = SCRIPT_SRC;
    script.async = true;
    document.head.appendChild(script);
  }
  let loaded = loads.get(script);
  if (!loaded) {
    const tag = script;
    loaded = new Promise<void>((resolve, reject) => {
      tag.addEventListener('load', () => resolve());
      tag.addEventListener('error', () => {
        tag.remove(); // lần mount sau thử tải lại
        reject(new Error('Không tải được Google Identity Services'));
      });
    });
    loads.set(script, loaded);
  }
  return loaded;
}

/** Nơi nhận `credential` (ID token) từ nút Google của trang đang mở; `null` khi trang đóng. */
export function setGoogleCredentialHandler(fn: ((credential: string) => void) | null): void {
  handler = fn;
}

/** `google.accounts.id.initialize` đúng một lần cho mỗi bản GIS, kể cả khi có nhiều nút hoặc StrictMode chạy effect hai lần. */
export function ensureGoogleInitialized(): void {
  const id = window.google?.accounts.id;
  if (!id || initialized.has(id)) return;
  initialized.add(id);
  id.initialize({ client_id: googleClientId(), callback: ({ credential }) => handler?.(credential) });
}
