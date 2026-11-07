// Đột biến D055: phá từng chốt chặn, chạy test liên quan, mong ĐỎ; luôn trả file về nguyên trạng.
import { execSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';

const FE = 'D:/Do-an/Pet-care-new/FE';
const store = `${FE}/src/shared/stores/session.store.ts`;
const axiosTs = `${FE}/src/shared/api/axios.ts`;
const pending = `${FE}/src/shared/hooks/usePendingSalesOrders.ts`;
const STORE_TESTS = 'src/shared/stores/session.refresh.test.ts src/shared/stores/session.refresh.tabs.test.ts';

const mutations = [
  ['poll được phép bắt đầu làm mới', store, 'else if (!poll && needsRefresh(', 'else if (needsRefresh(', STORE_TESTS],
  ['bỏ khóa liên tab', store, "typeof navigator !== 'undefined' && navigator.locks ? navigator.locks.request('petcare-refresh', task) : task()", 'task()', STORE_TESTS],
  ['bỏ đọc lại storage trong khóa', store, '    await useSession.persist.rehydrate();\n    const s = useSession.getState();', '    const s = useSession.getState();', STORE_TESTS],
  ['poll 401 kết thúc phiên', axiosTs, 'if (!config.poll) auth.onUnauthorized()', 'auth.onUnauthorized()', STORE_TESTS],
  ['bỏ listener thao tác người dùng', store, "if (typeof window !== 'undefined') {", 'if (false) {', STORE_TESTS],
  ['hook bỏ cổng useAccessValid', pending, 'enabled: enabled && open && role', 'enabled: enabled && role', 'src/shared/hooks/pollHooks.test.tsx'],
];

let bad = 0;
for (const [name, file, from0, to0, tests] of mutations) {
  if (process.argv[2] && !name.includes(process.argv[2])) continue;
  const original = readFileSync(file, 'utf8');
  const eol = (t) => (original.includes('\r\n') ? t.replace(/\n/g, '\r\n') : t);
  const from = eol(from0);
  const to = eol(to0);
  if (!original.includes(from)) {
    console.log(`?? ${name}: không tìm thấy đoạn cần phá`);
    bad++;
    continue;
  }
  writeFileSync(file, original.replace(from, to));
  let red = false;
  try {
    execSync(`npx vitest run ${tests} --maxWorkers=2`, { cwd: FE, stdio: 'pipe', timeout: 240000 });
  } catch {
    red = true;
  } finally {
    writeFileSync(file, original);
  }
  console.log(`${red ? 'OK (đỏ)' : 'LỌT (vẫn xanh)'}: ${name}`);
  if (!red) bad++;
}
process.exit(bad ? 1 : 0);
