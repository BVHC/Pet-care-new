// Lớp mock API theo hợp đồng docs/api/*-v1.md, cắm vào axios (adapter) nên code gọi API
// giống hệt khi nối BE thật: cùng path, cùng envelope (00-method §3.4–3.5).
// Thêm module: viết `<module>Routes` cạnh identity.mock.ts rồi gộp vào ROUTES.
// Khóa route là `METHOD /api/path`, đoạn `:ten` là tham số path.
import { AxiosError, getAdapter, type AxiosAdapter, type AxiosInstance, type AxiosResponse } from 'axios';
import type { AccountSummary } from '../../types/auth';
import { adminIdentityRoutes } from './identity-admin.mock';
import { appointmentRoutes } from './appointment.mock';
import { branchRoutes } from './branch.mock';
import { careRoutes } from './care.mock';
import { cashierRoutes } from './cashier.mock';
import { catalogRoutes } from './catalog.mock';
import { customerRoutes } from './customer.mock';
import { customerPetRoutes } from './customer-pet.mock';
import { inventoryRoutes } from './inventory.mock';
import { salesRoutes } from './sales.mock';
import { visitRoutes } from './visit.mock';
import { visitClinicalRoutes } from './visit-clinical.mock';
import { fail, ruleFail, type MockHandler, type MockReply } from './envelope';
import { accountForToken, identityRoutes } from './identity.mock';
import { isMockedRequest, type MockRule } from './mock-scope';

const ROUTES: Record<string, MockHandler> = {
  ...identityRoutes,
  ...customerRoutes,
  ...customerPetRoutes,
  ...catalogRoutes,
  ...inventoryRoutes,
  ...branchRoutes,
  ...adminIdentityRoutes,
  ...appointmentRoutes,
  ...careRoutes,
  ...salesRoutes,
  ...cashierRoutes,
  ...visitRoutes,
  ...visitClinicalRoutes,
};

const COMPILED = Object.entries(ROUTES).map(([key, handler]) => {
  const [method, path] = key.split(' ');
  const names: string[] = [];
  const pattern = new RegExp(
    '^' +
      path.replace(/:(\w+)/g, (_, name: string) => {
        names.push(name);
        return '([^/]+)';
      }) +
      '$',
  );
  return { method, pattern, names, handler };
});

function findRoute(method: string, path: string) {
  for (const r of COMPILED) {
    const m = r.method === method ? r.pattern.exec(path) : null;
    if (m) return { handler: r.handler, params: Object.fromEntries(r.names.map((n, i) => [n, decodeURIComponent(m[i + 1])])) };
  }
  return null;
}

/** Không cần đăng nhập (00-method §3.1). */
const PUBLIC_PATH = /^\/api\/(auth\/(register(\/.*)?|login|password\/.*)|public\/.*)$/;
/** Vẫn gọi được khi còn mustChangePassword (identity-v1 A4). */
const ALLOWED_BEFORE_PASSWORD_CHANGE = new Set(['GET /api/me', 'POST /api/me/password', 'POST /api/auth/logout']);

/** Nguồn tài khoản của phiên đăng nhập thật; chỉ dùng ở chế độ hỗn hợp, nơi token JWT của BE mock không biết. */
type SessionAccount = () => AccountSummary | null;

function route(
  method: string,
  path: string,
  body: unknown,
  token: string | null,
  query: Record<string, string>,
  sessionAccount?: SessionAccount,
): MockReply {
  const key = `${method} ${path}`;
  let account: AccountSummary | null = null;
  if (!PUBLIC_PATH.test(path)) {
    account = sessionAccount ? sessionAccount() : token ? accountForToken(token) : null;
    if (!account) return fail('UNAUTHENTICATED', 'Chưa đăng nhập hoặc phiên đăng nhập đã hết hạn', 401);
    if (account.mustChangePassword && !ALLOWED_BEFORE_PASSWORD_CHANGE.has(key)) {
      return ruleFail('BR-TK-17', 'Bạn cần đổi mật khẩu trước khi sử dụng chức năng khác');
    }
  }
  const found = findRoute(method, path);
  if (!found) return fail('RESOURCE_NOT_FOUND', `Chưa có mock cho ${key}.`, 404);
  return found.handler({ body, account, params: found.params, query });
}

/** Query của URL cộng `params` của axios (bỏ giá trị rỗng). */
function queryOf(url: URL, params: unknown): Record<string, string> {
  const query = Object.fromEntries(url.searchParams);
  for (const [k, v] of Object.entries((params ?? {}) as Record<string, unknown>)) {
    if (v !== undefined && v !== null && v !== '') query[k] = String(v);
  }
  return query;
}

const LATENCY_MS = import.meta.env.MODE === 'test' ? 0 : 200;

const makeMockAdapter = (sessionAccount?: SessionAccount): AxiosAdapter => async (config) => {
  await new Promise((resolve) => setTimeout(resolve, LATENCY_MS));
  const method = (config.method ?? 'get').toUpperCase();
  const url = new URL(config.url ?? '', 'http://mock');
  const token = String(config.headers?.Authorization ?? '').replace(/^Bearer /, '') || null;

  let reply: MockReply;
  try {
    const body = typeof config.data === 'string' && config.data ? JSON.parse(config.data) : config.data;
    reply = route(method, url.pathname, body, token, queryOf(url, config.params), sessionAccount);
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
  client.defaults.adapter = makeMockAdapter();
}

/**
 * Chế độ hỗn hợp (VITE_API_MOCK=false kèm VITE_API_MOCK_ONLY): đường dẫn khớp `rules` trả bằng mock, còn lại đi tiếp tới
 * adapter thật mà axios đang dùng. Mock không biết token JWT của BE nên lấy tài khoản từ phiên đăng nhập thật.
 * Giới hạn: dữ liệu mock không liên thông với dữ liệu BE (id khác nhau), nên chỉ mock những màn mà BE chưa có
 * và không tham chiếu chéo dữ liệu thật.
 */
export function installHybridMockApi(client: AxiosInstance, rules: MockRule[], sessionAccount: SessionAccount) {
  const real = getAdapter(client.defaults.adapter);
  const mock = makeMockAdapter(sessionAccount);
  client.defaults.adapter = (config) => {
    const { pathname } = new URL(config.url ?? '', 'http://mock');
    return isMockedRequest(config.method ?? 'get', pathname, rules) ? mock(config) : real(config);
  };
}
