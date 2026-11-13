// Phần HTTP dùng chung của các script nạp tri thức (load-demo-kb.mjs, load-docs-rag.mjs): gọi API JSON, đăng nhập
// SUPER_MANAGER demo (mật khẩu đọc lúc chạy từ FE/scripts/seed-dev/README.md, không ghi vào script, không in ra),
// liệt kê tài liệu UC93 theo trang. Không có logic nghiệp vụ.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

export function parseArgs(argv) {
  return Object.fromEntries(argv.reduce((acc, a, i, all) => (a.startsWith('--')
    ? [...acc, [a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : 'true']] : acc), []));
}

export function makeClient(api) {
  const API = api.replace(/\/$/, '');
  async function call(method, path, body, token) {
    const res = await fetch(`${API}${path}`, {
      method,
      headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    let json = null;
    try { json = JSON.parse(text); } catch { /* thân không phải JSON */ }
    return { status: res.status, json };
  }
  const fail = (what, r) => { throw new Error(`${what}: HTTP ${r.status} ${r.json?.errorCode ?? ''} ${String(r.json?.message ?? '').slice(0, 300)}`); };

  async function login(repoRoot) {
    const readme = readFileSync(join(repoRoot, 'FE', 'scripts', 'seed-dev', 'README.md'), 'utf8');
    const email = /`([^`]+@petcare\.local)`\s*\|\s*SUPER_MANAGER/.exec(readme)?.[1];
    const password = /Mật khẩu chung[^`]*`([^`]+)`/.exec(readme)?.[1];
    if (!email || !password) throw new Error('Không đọc được tài khoản SUPER_MANAGER demo từ FE/scripts/seed-dev/README.md');
    const r = await call('POST', '/api/auth/login', { email, password });
    if (r.status !== 200 || !r.json?.data?.accessToken) fail(`Đăng nhập ${email}`, r);
    console.log(`Đã đăng nhập SUPER_MANAGER ${email}`);
    return r.json.data.accessToken;
  }

  async function listAll(token) {
    const rows = [];
    for (let page = 0; ; page++) {
      const r = await call('GET', `/api/ai/documents?size=100&page=${page}`, undefined, token);
      if (r.status !== 200) fail('Liệt kê tài liệu', r);
      rows.push(...r.json.data.content);
      if (rows.length >= r.json.data.totalElements) return rows;
    }
  }

  return { API, call, fail, login, listAll };
}
