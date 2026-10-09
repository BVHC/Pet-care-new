// Lớp mock API theo hợp đồng docs/api/*-v1.md, cắm vào axios (adapter) nên code gọi API
// giống hệt khi nối BE thật: cùng path, cùng envelope (00-method §3.4–3.5).
// Thêm module: viết `<module>Routes` cạnh identity.mock.ts rồi gộp vào ROUTES.
import { AxiosError, type AxiosAdapter, type AxiosInstance, type AxiosResponse } from 'axios';
import type { AccountSummary } from '../../types/auth';
import { fail, ruleFail, type MockHandler, type MockReply } from './envelope';
import { accountForToken, identityRoutes } from './identity.mock';

const ROUTES: Record<string, MockHandler> = { ...identityRoutes };

/** Không cần đăng nhập (00-method §3.1). */
const PUBLIC_PATH = /^\/api\/(auth\/(register(\/.*)?|login|password\/.*)|public\/.*)$/;
/** Vẫn gọi được khi còn mustChangePassword (identity-v1 A4). */
const ALLOWED_BEFORE_PASSWORD_CHANGE = new Set(['GET /api/me', 'POST /api/me/password', 'POST /api/auth/logout']);

function route(method: string, path: string, body: unknown, token: string | null): MockReply {
  const key = `${method} ${path}`;
  let account: AccountSummary | null = null;
  if (!PUBLIC_PATH.test(path)) {
    account = token ? accountForToken(token) : null;
    if (!account) return fail('UNAUTHENTICATED', 'Chưa đăng nhập hoặc phiên đăng nhập đã hết hạn.', 401);
    if (account.mustChangePassword && !ALLOWED_BEFORE_PASSWORD_CHANGE.has(key)) {
      return ruleFail('BR-TK-17', 'Bạn cần đổi mật khẩu trước khi tiếp tục');
    }
  }
  const handler = ROUTES[key];
  if (!handler) return fail('RESOURCE_NOT_FOUND', `Chưa có mock cho ${key}.`, 404);
  return handler({ body, account });
}

const LATENCY_MS = import.meta.env.MODE === 'test' ? 0 : 200;

const mockAdapter: AxiosAdapter = async (config) => {
  await new Promise((resolve) => setTimeout(resolve, LATENCY_MS));
  const method = (config.method ?? 'get').toUpperCase();
  const path = new URL(config.url ?? '', 'http://mock').pathname;
  const token = String(config.headers?.Authorization ?? '').replace(/^Bearer /, '') || null;

  let reply: MockReply;
  try {
    const body = typeof config.data === 'string' && config.data ? JSON.parse(config.data) : config.data;
    reply = route(method, path, body, token);
  } catch {
    reply = fail('MALFORMED_REQUEST', 'Dữ liệu gửi lên không đọc được.', 400);
  }

  const response: AxiosResponse = {
    data: reply.body ?? '',
    status: reply.status,
    statusText: String(reply.status),
    headers: {},
    config,
    request: {},
  };
  if (reply.status < 400) return response;
  throw new AxiosError(
    `Request failed with status code ${reply.status}`,
    reply.status >= 500 ? AxiosError.ERR_BAD_RESPONSE : AxiosError.ERR_BAD_REQUEST,
    config,
    {},
    response,
  );
};

export function installMockApi(client: AxiosInstance) {
  client.defaults.adapter = mockAdapter;
}
