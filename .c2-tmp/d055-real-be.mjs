// D055: kiểm refresh token với BE THẬT (localhost:8081) bằng Chrome thật, FE chạy ở cổng 3000 (VITE_API_MOCK=false).
// Hạ session.access_ttl_minutes về 5 qua API admin (có audit) trong lúc thử, LUÔN trả về giá trị cũ ở finally.
// Mật khẩu seed đọc từ FE/scripts/seed-dev/README.md lúc chạy, không ghi vào đâu khác.
import { readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire('D:/Do-an/Pet-care-new/FE/package.json');
const { chromium } = require('@playwright/test');

const API = 'http://localhost:8081';
const WEB = 'http://localhost:3010'; // 3000 do tiến trình khác giữ; BE chỉ CORS 5173/3000 nên Chrome chạy --disable-web-security
const KEY = 'session.access_ttl_minutes';
const ORIGINAL_FILE = 'D:/Do-an/Pet-care-new/.c2-tmp/d055-ttl-original.txt';
const readme = readFileSync('D:/Do-an/Pet-care-new/FE/scripts/seed-dev/README.md', 'utf8');
const PASSWORD = /\(kể cả ADMIN\): `([^`]+)`/.exec(readme)?.[1];
if (!PASSWORD) throw new Error('không đọc được mật khẩu seed từ README');

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? 'ĐẠT ' : 'LỖI '} ${name}${detail ? ` — ${detail}` : ''}`);
};

async function call(method, path, token, body) {
  const res = await fetch(API + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

async function login(email) {
  const res = await call('POST', '/api/auth/login', null, { email, password: PASSWORD });
  if (res.status !== 200) throw new Error(`đăng nhập ${email}: ${res.status} ${res.body?.message}`);
  return res.body.data;
}

const persisted = (l) =>
  JSON.stringify({
    state: {
      token: l.accessToken,
      refreshToken: l.refreshToken,
      accessTokenExpiresAt: l.accessTokenExpiresAt,
      expiresAt: l.expiresAt,
      account: l.account,
      linkDecisionPending: l.linkDecisionPending,
      fullName: 'Kiểm D055',
    },
    version: 0,
  });

const stored = (page) => page.evaluate(() => JSON.parse(localStorage.getItem('petcare-session') ?? '{}').state ?? {});

/** Ghi đè hạn access trong storage (FE tin là còn `minutes` phút) rồi nạp lại trang. */
async function setAccessLeft(page, minutes) {
  await page.evaluate((m) => {
    const raw = JSON.parse(localStorage.getItem('petcare-session'));
    raw.state.accessTokenExpiresAt = new Date(Date.now() + m * 60_000).toISOString();
    localStorage.setItem('petcare-session', JSON.stringify(raw));
  }, minutes);
}

function watch(page, log) {
  page.on('response', (r) => {
    const u = new URL(r.url());
    if (u.pathname.startsWith('/api/')) log.push({ method: r.request().method(), path: u.pathname, status: r.status(), t: Date.now() });
  });
}
const refreshes = (log) => log.filter((e) => e.path === '/api/auth/refresh');

let original = null;
const admin = await login('admin@petcare.local');
try {
  const configs = await call('GET', '/api/system-configs', admin.accessToken);
  original = configs.body.data.find((c) => c.key === KEY)?.value;
  writeFileSync(ORIGINAL_FILE, String(original));
  console.log(`${KEY} hiện là ${original}; đặt 5 trong lúc thử (giá trị cũ lưu ở ${ORIGINAL_FILE})`);
  const set = await call('PUT', `/api/system-configs/${KEY}`, admin.accessToken, { value: '5' });
  if (set.status !== 200) throw new Error(`PUT TTL: ${set.status} ${set.body?.message}`);

  const staff = await login('mai.le@petcare.local');
  const ttl = Date.parse(staff.accessTokenExpiresAt) - Date.now();
  check('BE cấp access token sống ~5 phút (TTL=5)', ttl > 4 * 60_000 && ttl <= 5 * 60_000, `${Math.round(ttl / 1000)} giây`);

  const browser = await chromium.launch({ channel: 'chrome', args: ['--disable-web-security', '--disable-site-isolation-trials'] });
  try {
    // A. Thao tác khi access token còn dưới 5 phút: đúng MỘT refresh, request chạy với token mới, không 401.
    {
      const l = await login('mai.le@petcare.local');
      const ctx = await browser.newContext();
      await ctx.addInitScript((s) => localStorage.setItem('petcare-session', s), persisted(l));
      const page = await ctx.newPage();
      const log = [];
      watch(page, log);
      await page.goto(`${WEB}/admin`);
      await page.waitForLoadState('networkidle');
      const before = await stored(page);
      log.length = 0;
      await setAccessLeft(page, 3);
      await page.reload();
      await page.waitForLoadState('networkidle');
      const after = await stored(page);
      check('A1 sau khi nạp trang với token còn 3 phút: đúng 1 lượt /auth/refresh', refreshes(log).length === 1, `${refreshes(log).length} lượt`);
      check('A2 refresh token và access token đã đổi', after.refreshToken !== before.refreshToken && after.token !== before.token);
      check('A3 không có 401 nào trong cả lượt', log.every((e) => e.status !== 401), log.filter((e) => e.status === 401).map((e) => e.path).join(', '));
      check('A4 không bị đưa về trang đăng nhập', !page.url().includes('login'), page.url());

      // B. Bấm chuột thật (pointerdown) khi còn dưới 5 phút bắt đầu refresh, không cần request nào.
      log.length = 0;
      await setAccessLeft(page, 2);
      await page.evaluate(() => window.dispatchEvent(new Event('focus')));
      const pre = await stored(page);
      await page.mouse.click(5, 5);
      await page.waitForTimeout(1500);
      const post = await stored(page);
      check('B1 một cú bấm chuột khi còn 2 phút bắt đầu đúng 1 lượt refresh', refreshes(log).length === 1 && post.refreshToken !== pre.refreshToken, `${refreshes(log).length} lượt`);

      // C. Access token hỏng nhưng đồng hồ còn hạn: 401 rồi làm mới và thử lại một lần.
      log.length = 0;
      await page.evaluate(() => {
        const raw = JSON.parse(localStorage.getItem('petcare-session'));
        raw.state.token = 'hong.hong.hong';
        raw.state.accessTokenExpiresAt = new Date(Date.now() + 4.5 * 60_000).toISOString();
        localStorage.setItem('petcare-session', JSON.stringify(raw));
      });
      await page.reload();
      await page.waitForLoadState('networkidle');
      const first401 = log.find((e) => e.status === 401 && e.path !== '/api/auth/refresh');
      const retried = first401 && log.some((e) => e.path === first401.path && e.status === 200 && e.t >= first401.t);
      check('C1 token hỏng: có 401, đúng 1 refresh, request đó được thử lại và thành công', !!first401 && refreshes(log).length === 1 && !!retried, JSON.stringify(log.map((e) => `${e.path}:${e.status}`)));
      await ctx.close();
    }

    // D. Hai tab cùng origin (Web Locks thật): cùng thấy token sắp hết, bấm đồng thời, chỉ MỘT refresh, phiên còn sống.
    {
      const l = await login('mai.le@petcare.local');
      const ctx = await browser.newContext();
      await ctx.addInitScript((s) => { if (!localStorage.getItem('petcare-session')) localStorage.setItem('petcare-session', s); }, persisted(l));
      const p1 = await ctx.newPage();
      const p2 = await ctx.newPage();
      const log = [];
      watch(p1, log);
      watch(p2, log);
      await p1.goto(`${WEB}/admin`);
      await p2.goto(`${WEB}/admin`);
      await p1.waitForLoadState('networkidle');
      await p2.waitForLoadState('networkidle');
      await setAccessLeft(p1, 2);
      await p1.reload();
      await p2.reload(); // p2 đọc cùng storage: cũng thấy "còn 2 phút" khi nạp
      await setAccessLeft(p1, 2);
      log.length = 0;
      await Promise.all([p1.mouse.click(5, 5), p2.mouse.click(5, 5)]);
      await Promise.all([p1.waitForTimeout(2500), p2.waitForTimeout(2500)]);
      check('D1 hai tab bấm đồng thời: đúng 1 lượt /auth/refresh', refreshes(log).length === 1, `${refreshes(log).length} lượt, trạng thái ${refreshes(log).map((e) => e.status)}`);
      const s1 = await stored(p1);
      const s2 = await stored(p2);
      check('D2 cả hai tab còn đăng nhập', !!s1.token && !!s2.token && !!s1.account && !!s2.account);
      const me = await call('GET', '/api/me', s1.token);
      check('D3 token trong storage dùng được với BE', me.status === 200, String(me.status));
      await ctx.close();
    }

    // E. Dùng lại refresh token đã dùng: 401 một thông điệp chung và cả phiên bị hủy.
    {
      const l = await login('mai.le@petcare.local');
      const next = await call('POST', '/api/auth/refresh', null, { refreshToken: l.refreshToken });
      const reuse = await call('POST', '/api/auth/refresh', null, { refreshToken: l.refreshToken });
      const dead = await call('POST', '/api/auth/refresh', null, { refreshToken: next.body.data.refreshToken });
      check('E1 refresh hợp lệ trả đủ 4 trường', next.status === 200 && ['accessToken', 'accessTokenExpiresAt', 'refreshToken', 'expiresAt'].every((k) => next.body.data[k]));
      check('E2 dùng lại token cũ: 401 "Refresh token không hợp lệ hoặc đã hết hạn"', reuse.status === 401 && reuse.body.message === 'Refresh token không hợp lệ hoặc đã hết hạn', `${reuse.status} ${reuse.body?.message}`);
      check('E3 sau đó token mới của cùng phiên cũng chết', dead.status === 401, String(dead.status));
    }
  } finally {
    await browser.close();
  }
} finally {
  if (original != null) {
    const back = await call('PUT', `/api/system-configs/${KEY}`, admin.accessToken, { value: String(original) });
    const now = (await call('GET', '/api/system-configs', admin.accessToken)).body.data.find((c) => c.key === KEY)?.value;
    console.log(`TRẢ TTL: PUT ${back.status}, ${KEY} hiện là ${now} (cũ ${original})`);
    if (String(now) !== String(original)) console.log('CẢNH BÁO: CHƯA TRẢ ĐƯỢC TTL, SỬA TAY NGAY');
  }
}
const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} đạt`);
process.exit(bad.length ? 1 : 0);
