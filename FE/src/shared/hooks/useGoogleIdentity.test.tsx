import { StrictMode } from 'react';
import { render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GoogleButton } from '../../pages/auth/AuthField';
import { stubGoogle } from '../../test/google';
import { loadGoogleIdentity } from './useGoogleIdentity';

beforeEach(() => vi.stubEnv('VITE_GOOGLE_CLIENT_ID', 'test-client'));
afterEach(() => vi.unstubAllEnvs());

describe('useGoogleIdentity', () => {
  it('loadsScriptOnce', async () => {
    const google = stubGoogle();

    await Promise.all([loadGoogleIdentity(), loadGoogleIdentity()]);

    expect(google.scriptCount()).toBe(1);
  });

  it('initializeOnceAcrossTwoButtons', async () => {
    const google = stubGoogle();
    const { container } = render(
      <StrictMode>
        <GoogleButton text="continue_with" onCredential={vi.fn()} />
        <GoogleButton text="signup_with" onCredential={vi.fn()} />
      </StrictMode>,
    );

    await waitFor(() => expect(container.querySelectorAll('button')).toHaveLength(2));

    expect(window.google?.accounts.id.initialize).toHaveBeenCalledTimes(1);
    expect(window.google?.accounts.id.initialize).toHaveBeenCalledWith(expect.objectContaining({ client_id: 'test-client' }));
    expect([...container.children].map((box) => box.querySelectorAll('button').length)).toEqual([1, 1]); // StrictMode chạy effect hai lần: không được thành hai nút
    expect(google.options().map((o) => o.text)).toEqual(['continue_with', 'signup_with']);
    expect(google.scriptCount()).toBe(1);
  });

  it('scriptLoadFailureHidesButton', async () => {
    const google = stubGoogle({ scriptFails: true });
    const { container } = render(<GoogleButton text="continue_with" onCredential={vi.fn()} />);

    await waitFor(() => expect(container).toBeEmptyDOMElement());

    expect(google.options()).toHaveLength(0);
    expect(google.scriptCount()).toBe(0); // thẻ hỏng được gỡ để lần mount sau thử tải lại
  });

  it('rendersNothingWhenClientIdIsBlank', () => {
    vi.stubEnv('VITE_GOOGLE_CLIENT_ID', '');
    const google = stubGoogle();
    const { container } = render(<GoogleButton text="continue_with" onCredential={vi.fn()} />);

    expect(container).toBeEmptyDOMElement();
    expect(google.scriptCount()).toBe(0);
  });

  it('passesCredentialToTheLatestHandlerAndStopsAfterUnmount', async () => {
    const google = stubGoogle();
    const first = vi.fn();
    const second = vi.fn();
    const { container, rerender, unmount } = render(<GoogleButton text="continue_with" onCredential={first} />);
    await waitFor(() => expect(container.querySelector('button')).not.toBeNull());

    rerender(<GoogleButton text="continue_with" onCredential={second} />); // trang dựng lại với hàm mới: nút không vẽ lại
    google.click('cred-1');
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledWith('cred-1');
    expect(google.options()).toHaveLength(1);

    const stale = container.querySelector('button')!;
    unmount();
    stale.click(); // nút còn sót trong DOM của GIS sau khi trang đóng: không ai nhận credential
    expect(second).toHaveBeenCalledTimes(1);
  });
});
