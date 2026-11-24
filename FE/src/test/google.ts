import { act } from '@testing-library/react';
import { onTestFinished, vi } from 'vitest';

// Helper test: ID token Google giả (header.payload.chữ-ký), chỉ để mock `POST /api/auth/google` giải mã payload (không kiểm chữ ký).
const base64url = (value: unknown) =>
  btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(value))))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

/** Claim mặc định hợp lệ với `VITE_GOOGLE_CLIENT_ID = 'test-client'`; `claims` ghi đè từng claim (vd `sub`, `email`, `name`, `hd`, `exp`). */
export function fakeGoogleCredential(claims: Record<string, unknown>): string {
  const payload = {
    aud: 'test-client',
    iss: 'https://accounts.google.com',
    email_verified: true,
    exp: Math.floor(Date.now() / 1000) + 3600,
    ...claims,
  };
  return `${base64url({ alg: 'RS256', typ: 'JWT' })}.${base64url(payload)}.sig`;
}

const GSI_SCRIPT = 'script[src="https://accounts.google.com/gsi/client"]';

/**
 * `window.google` giả cho Google Identity Services. jsdom không tải <script src>, nên stub quan sát <head>: khi hook chèn thẻ GIS
 * thì bắn `load` (hoặc `error` nếu `scriptFails`) bất đồng bộ như trình duyệt. `renderButton` vẽ `<button>Google</button>`; bấm nó gọi
 * callback đã `initialize` với credential của lần `click` gần nhất. Tự dọn khi test xong (jsdom giữ <head> và `window` giữa các test trong một file).
 */
export function stubGoogle({ scriptFails = false }: { scriptFails?: boolean } = {}) {
  document.querySelectorAll(GSI_SCRIPT).forEach((script) => script.remove());
  let callback: ((response: { credential: string }) => void) | undefined;
  let nextCredential = '';
  const renderOptions: Record<string, unknown>[] = [];
  const fake = {
    accounts: {
      id: {
        initialize: vi.fn((config: { callback: (response: { credential: string }) => void }) => {
          callback = config.callback;
        }),
        renderButton: vi.fn((container: HTMLElement, options: Record<string, unknown>) => {
          renderOptions.push(options);
          const button = document.createElement('button');
          button.type = 'button';
          button.textContent = 'Google';
          button.addEventListener('click', () => callback?.({ credential: nextCredential }));
          container.appendChild(button);
        }),
      },
    },
  };
  const observer = new MutationObserver((records) => {
    for (const node of records.flatMap((r) => [...r.addedNodes])) {
      if (!(node instanceof HTMLScriptElement) || !node.matches(GSI_SCRIPT)) continue;
      if (scriptFails) {
        node.dispatchEvent(new Event('error'));
      } else {
        window.google = fake;
        node.dispatchEvent(new Event('load'));
      }
    }
  });
  observer.observe(document.head, { childList: true });
  onTestFinished(() => {
    observer.disconnect();
    delete window.google;
    document.querySelectorAll(GSI_SCRIPT).forEach((script) => script.remove());
  });

  return {
    /** Khách bấm nút Google và Google trả `credential`. */
    click(credential: string) {
      const button = [...document.querySelectorAll('button')].find((b) => b.textContent === 'Google');
      if (!button) throw new Error('Không có nút Google trên trang');
      nextCredential = credential;
      act(() => button.click());
    },
    /** Tùy chọn của từng lần `renderButton`, theo thứ tự gọi. */
    options: () => renderOptions,
    scriptCount: () => document.querySelectorAll(GSI_SCRIPT).length,
  };
}
