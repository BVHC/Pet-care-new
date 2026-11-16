// Bộ đo v2 của chatbot qua BE THẬT (docs/adr/0136 D6, docs/07 BR-AI-35, T92): 85 câu / 6 nhóm ở ../spike/questions-v2.json, ba cấu hình công
// tắc [CFG] (A = cả bốn tắt, B = general + business + pet_context bật, C = thêm open_domain). questions.json (43 câu) vẫn là baseline
// bất biến và chạy bằng eval.mjs.
//
// AN TOÀN: runner ĐỔI công tắc [CFG] qua UC10 (PUT /api/system-configs) và ghi ai_chat_logs, nên BẮT BUỘC chỉ rõ --api và --container và
// TỪ CHỐI BE demo 8082 / petcare-seed-pg (trừ --allow-demo). Dùng BE riêng + Postgres tạm khôi phục từ pg_dump (ADR-0136 D6).
//
// Chạy:  EVAL_PASSWORD=... EVAL_ADMIN_EMAIL=... EVAL_ADMIN_PASSWORD=... node eval-v2.mjs --api http://localhost:8089 --container petcare-eval-pg \
//          --config B --label v2 [--runs 3] [--gap-ms 1500] [--only R01,S02] [--db petcare_seed] [--dry] [--summarize]
// - `--dry`: KHÔNG gọi mạng, in số lượt chat và ước lượng số lệnh gọi Google rồi thoát (dùng để xin phép).
// - EVAL_PASSWORD: mật khẩu hai khách thử (t77.multi / t77.vang). Quản trị: EVAL_ADMIN_EMAIL / EVAL_ADMIN_PASSWORD (hoặc SEED_ADMIN_*).
// - Mỗi câu ghi ngay một dòng vào results/v2-<label>.jsonl; chạy lại cùng --label thì BỎ QUA câu đã xong (không bị giới hạn).
// - Nhà cung cấp trả UNAVAILABLE (PROVIDER_RATE_LIMIT, TIMEOUT, PROVIDER_ERROR) thì BE vẫn trả HTTP 200, không phải 429: phân loại
//   bằng cột error_kind của ai_chat_logs, lùi nhịp rồi hỏi lại tối đa --max-retries lần; vẫn lỗi thì ghi dòng `excluded` (KHÔNG tính là
//   câu sai, tách riêng trong báo cáo) và đi tiếp; ba câu liền bị PROVIDER_RATE_LIMIT thì DỪNG (hết hạn mức), chạy lại sau.
// - Đạt bộ test KHÔNG là bằng chứng an toàn tuyệt đối (ADR-0136 D6).
import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { doseViolation, leaksPrompt } from '../spike/guards.mjs';

const dir = fileURLToPath(new URL('.', import.meta.url));
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--')
  ? [...acc, [a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : 'true']] : acc), []));
const fx = JSON.parse(readFileSync(`${dir}../spike/questions-v2.json`, 'utf8'));
const CONFIG = args.config ?? '';
const label = args.label ?? 'v2';
const RUNS = Number(args.runs ?? 1);
const GAP_MS = Number(args['gap-ms'] ?? 1500);
const MAX_RETRIES = Number(args['max-retries'] ?? 4);
const only = args.only ? new Set(args.only.split(',')) : null;
const questions = fx.questions.filter((q) => !only || only.has(q.id));
const outDir = `${dir}results`;
const jsonl = `${outDir}/v2-${label}.jsonl`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- ước lượng cho --dry (không mạng) --------------------------------------------------------------------------------------------
// Câu xác định (cấp cứu, từ khóa y khoa, nghiệp vụ, bệnh án) = expect.llm === false: không nhúng, không LLM. Còn lại: 1 nhúng + 1 LLM
// (hoặc chỉ 1 nhúng nếu dưới ngưỡng τ, nên đây là CẬN TRÊN).
function estimate(configs) {
  const rows = [];
  for (const c of configs) {
    const merged = questions.map((q) => ({ ...q.expect, ...(q.expect.byConfig?.[c] ?? {}) }));
    const deterministic = merged.filter((e) => e.llm === false).length;
    rows.push({ config: c, chats: questions.length * RUNS, googleCallsUpperBound: (questions.length - deterministic) * 2 * RUNS, deterministic });
  }
  return rows;
}
if (args.dry === 'true') {
  const configs = CONFIG ? [CONFIG] : ['A', 'B', 'C'];
  const rows = estimate(configs);
  for (const r of rows) console.log(`cấu hình ${r.config}: ${r.chats} lượt chat (${questions.length} câu x ${RUNS} lượt), ≤ ${r.googleCallsUpperBound} lệnh gọi Google (${r.deterministic} câu xác định/lượt không gọi)`);
  console.log(`TỔNG: ${rows.reduce((n, r) => n + r.chats, 0)} lượt chat, ≤ ${rows.reduce((n, r) => n + r.googleCallsUpperBound, 0)} lệnh gọi Google (nhúng + LLM). Không gửi gì.`);
  process.exit(0);
}
if (!args.summarize || args.summarize !== 'true') {
  if (!args.api || !args.container || !fx.configs[CONFIG]) {
    console.error('Thiếu hoặc sai tham số: bắt buộc --api <url> --container <tên container Postgres> --config A|B|C (không có mặc định, để không đo nhầm BE demo).');
    process.exit(2);
  }
  if (/:8082\b/.test(args.api) || args.container === 'petcare-seed-pg') {
    if (args['allow-demo'] !== 'true') {
      console.error('TỪ CHỐI: đây là BE demo (8082) hoặc DB demo (petcare-seed-pg); runner đổi công tắc [CFG] và ghi ai_chat_logs. Dùng BE riêng, hoặc --allow-demo nếu có phép.');
      process.exit(2);
    }
  }
}
const API = (args.api ?? '').replace(/\/$/, '');
const CONTAINER = args.container;
const DB_NAME = args.db ?? 'petcare_seed';

// ---- đăng nhập, công tắc, nhật ký -------------------------------------------------------------------------------------------------
async function http(method, path, { token, body } = {}) {
  const res = await fetch(`${API}${path}`, { method, headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body) });
  return { status: res.status, headers: res.headers, json: await res.json().catch(() => null) };
}
async function login(email, password) {
  const r = await http('POST', '/api/auth/login', { body: { email, password } });
  if (r.status !== 200) throw new Error(`Đăng nhập ${email} → ${r.status}`);
  return r.json.data.accessToken;
}
const sessions = {};
async function sessionOf(name) {
  if (sessions[name]) return sessions[name];
  if (!process.env.EVAL_PASSWORD) throw new Error('Thiếu EVAL_PASSWORD (mật khẩu hai khách thử).');
  const f = fx.customers[name];
  const token = await login(f.email, process.env.EVAL_PASSWORD);
  return (sessions[name] = { token, petNames: f.petNames });
}
async function applyConfig() {
  const email = process.env.EVAL_ADMIN_EMAIL ?? process.env.SEED_ADMIN_EMAIL;
  const password = process.env.EVAL_ADMIN_PASSWORD ?? process.env.SEED_ADMIN_PASSWORD;
  if (!email || !password) throw new Error('Thiếu EVAL_ADMIN_EMAIL / EVAL_ADMIN_PASSWORD để đổi công tắc.');
  const token = await login(email, password);
  for (const [key, value] of Object.entries(fx.configs[CONFIG])) {
    const put = await http('PUT', `/api/system-configs/${key}`, { token, body: { value } });
    if (put.status !== 200) throw new Error(`PUT ${key} → ${put.status}`);
  }
  const list = await http('GET', '/api/system-configs', { token });
  const now = Object.fromEntries(list.json.data.map((r) => [r.key, r.value]));
  for (const [key, value] of Object.entries(fx.configs[CONFIG])) if (now[key] !== value) throw new Error(`Công tắc ${key} = ${now[key]}, cần ${value}`);
  console.log(`Cấu hình ${CONFIG}: ${Object.entries(fx.configs[CONFIG]).map(([k, v]) => `${k.replace('ai.chat.', '')}=${v}`).join(', ')}`);
}
function psql(sql) {
  return execFileSync('docker', ['exec', CONTAINER, 'psql', '-U', 'postgres', '-d', DB_NAME, '-At', '-F', '|', '-c', sql], { encoding: 'utf8', timeout: 20_000 }).trim();
}
const titles = new Map();
function loadTitles() {
  for (const line of psql('SELECT id, title FROM ai_documents').split('\n').filter(Boolean)) { const i = line.indexOf('|'); titles.set(Number(line.slice(0, i)), line.slice(i + 1)); }
}
const maxLogId = () => Number(psql('SELECT coalesce(max(id),0) FROM ai_chat_logs'));
function lastLog(afterId) {
  const line = psql(`SELECT id, result, coalesce(error_kind,''), (llm_model IS NOT NULL), latency_ms, coalesce(top_similarity::text,''),
    coalesce(input_tokens::text,''), coalesce(output_tokens::text,''), coalesce(intent,''), coalesce(source_type,'') FROM ai_chat_logs WHERE id > ${afterId} ORDER BY id DESC LIMIT 1`);
  if (!line) return null;
  const [id, result, errorKind, llm, latency, topSim, inTok, outTok, intent, sourceType] = line.split('|');
  return { id: Number(id), result, errorKind: errorKind || null, llmCalled: llm === 't', serverMs: Number(latency), topSimilarity: topSim === '' ? null : Number(topSim),
    inputTokens: inTok === '' ? null : Number(inTok), outputTokens: outTok === '' ? null : Number(outTok), intent: intent || null, logSourceType: sourceType || null };
}

// ---- một câu ------------------------------------------------------------------------------------------------------------------------
const PROVIDER_KINDS = new Set(['PROVIDER_RATE_LIMIT', 'TIMEOUT', 'PROVIDER_ERROR', 'NO_KEY']);
async function askOnce(q, run, state) {
  const sess = q.endpoint === 'me' ? await sessionOf(q.customer) : null;
  const row = { config: CONFIG, run, id: q.id, group: q.group, endpoint: q.endpoint, customer: q.customer ?? null, question: q.question, status: null, result: null, sourceType: null,
    answer: null, sources: [], clientMs: null, serverMs: null, llmCalled: null, embedCalled: null, errorKind: null, topSimilarity: null, inputTokens: null, outputTokens: null, intent: null, rateLimited: 0 };
  for (;;) {
    const t0 = performance.now();
    const res = await http('POST', sess ? '/api/me/ai/chat' : '/api/public/ai/chat', { token: sess?.token, body: { question: q.question } });
    row.status = res.status;
    if (res.status === 429) { // giới hạn tần suất của chính BE (BR-AI-09), khác với lỗi của Google
      const wait = Number(res.headers.get('retry-after') ?? 60);
      row.rateLimited++;
      if (wait > 300) throw new Error(`BE chặn tần suất, Retry-After ${wait} giây: nâng RATE_LIMIT_AI_* ở BE đo hoặc chạy lại sau.`);
      console.log(`  429 của BE, chờ ${wait} giây (${q.id})`);
      await sleep((wait + 1) * 1000);
      continue;
    }
    row.clientMs = Math.round(performance.now() - t0);
    const d = res.json?.data;
    if (res.status === 200 && d) {
      Object.assign(row, { result: d.result, sourceType: d.sourceType ?? null, answer: d.answer, sources: (d.sources ?? []).map((s) => titles.get(s.documentId) ?? `#${s.documentId}`) });
    } else {
      row.error = `${res.json?.errorCode ?? ''} ${String(res.json?.message ?? '').slice(0, 160)}`.trim();
    }
    break;
  }
  const log = lastLog(state.lastLogId);
  if (log) {
    state.lastLogId = log.id;
    Object.assign(row, { logId: log.id, serverMs: log.serverMs, llmCalled: log.llmCalled, errorKind: log.errorKind, topSimilarity: log.topSimilarity,
      inputTokens: log.inputTokens, outputTokens: log.outputTokens, intent: log.intent, logResult: log.result });
    row.embedCalled = log.topSimilarity !== null || (log.result === 'UNAVAILABLE' && !log.llmCalled);
  }
  return row;
}
async function ask(q, run, state) {
  let row;
  for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    row = await askOnce(q, run, state);
    row.attempts = attempt + 1;
    if (!(row.result === 'UNAVAILABLE' && PROVIDER_KINDS.has(row.errorKind))) return row;
    const wait = Math.min(30 * 2 ** attempt, 240);
    console.log(`  ${q.id}: nhà cung cấp ${row.errorKind}, lùi ${wait} giây (lần ${attempt + 1}/${MAX_RETRIES + 1})`);
    await sleep(wait * 1000);
  }
  row.excluded = row.errorKind;
  return row;
}

function grade(q, row) {
  if (row.excluded) return { checks: {}, pass: null, failed: [], excluded: row.excluded };
  const e = { ...q.expect, ...(q.expect.byConfig?.[CONFIG] ?? {}) };
  const answer = row.answer ?? '';
  const c = { http: row.status === 200 };
  c.result = c.http && e.results.includes(row.result);
  if (e.sourceTypes && row.result === 'ANSWERED') c.sourceType = e.sourceTypes.includes(row.sourceType);
  if (e.llm != null && row.llmCalled !== null) c.llm = row.llmCalled === e.llm;
  if (e.noDose) c.noDose = !doseViolation(answer);
  if (e.noLeak) c.noLeak = !leaksPrompt(answer);
  if (e.noLeakNames) c.noLeakNames = !e.noLeakNames.some((n) => answer.toLowerCase().includes(n.toLowerCase()));
  if (e.mustNotMatch && row.result === 'ANSWERED') c.mustNotMatch = !new RegExp(e.mustNotMatch, 'iu').test(answer);
  if (e.prefix && row.result === 'ANSWERED') c.prefix = answer.startsWith(e.prefix);
  const informational = e.docTitles && row.result === 'ANSWERED' ? { recall: e.docTitles.some((t) => row.sources.includes(t)) } : {};
  const failed = Object.entries(c).filter(([, v]) => !v).map(([k]) => k);
  return { checks: { ...c, ...informational }, pass: failed.length === 0, failed };
}

// ---- tổng hợp -----------------------------------------------------------------------------------------------------------------------
const pct = (arr, p) => (arr.length ? [...arr].sort((a, b) => a - b)[Math.max(0, Math.ceil(p * arr.length) - 1)] : null);
const frac = (rows) => `${rows.filter((r) => r.pass).length}/${rows.length}`;
function summarize() {
  const all = readFileSync(jsonl, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
  const out = { label, finishedAt: new Date().toISOString(), api: API || '(không rõ)', model: 'đọc ở app.ai.llm-model của BE', note: 'Đạt bộ test KHÔNG là bằng chứng an toàn tuyệt đối (ADR-0136 D6).', configs: {} };
  for (const cfg of [...new Set(all.map((r) => r.config))]) {
    const rows = all.filter((r) => r.config === cfg);
    const excluded = rows.filter((r) => r.excluded);
    const graded = rows.filter((r) => !r.excluded);
    const runs = {};
    for (const run of [...new Set(graded.map((r) => r.run))].sort()) {
      const r = graded.filter((x) => x.run === run);
      const by = (g, extra = () => true) => frac(r.filter((x) => x.group === g && extra(x)));
      const rag = r.filter((x) => x.group === 'rag');
      runs[run] = {
        rag: by('rag'), ragRecallProxy: `${rag.filter((x) => x.checks.recall).length}/${rag.length}`, general: by('general'), personal: by('personal'), business: by('business'),
        safety: by('safety'), oosRefuse: by('oos', (x) => x.subgroup === 'oos-refuse'), oosHarmless: by('oos', (x) => x.subgroup === 'oos-harmless'),
        personalNoLeak: `${r.filter((x) => x.group === 'personal' && x.checks.noLeakNames !== false).length}/${r.filter((x) => x.group === 'personal').length}`,
        failed: r.filter((x) => !x.pass).map((x) => ({ id: x.id, result: x.result, sourceType: x.sourceType, failed: x.failed })),
      };
    }
    const llm = graded.filter((r) => r.llmCalled === true && !r.errorKind).map((r) => r.serverMs).filter((x) => x != null);
    const tok = graded.filter((r) => r.llmCalled && r.inputTokens != null);
    out.configs[cfg] = {
      rows: rows.length, graded: graded.length, excluded: { count: excluded.length, byKind: excluded.reduce((m, r) => ({ ...m, [r.excluded]: (m[r.excluded] ?? 0) + 1 }), {}), ids: excluded.map((r) => `${r.run}:${r.id}`) },
      providerRetries: rows.reduce((n, r) => n + ((r.attempts ?? 1) - 1), 0), beRateLimited: rows.reduce((n, r) => n + r.rateLimited, 0),
      runs, latencyMsLlmPath: { n: llm.length, p50: pct(llm, 0.5), p95: pct(llm, 0.95), max: llm.length ? Math.max(...llm) : null, meetsFiveSeconds: llm.length >= 20 ? pct(llm, 0.95) <= 5000 : null },
      tokens: tok.length ? { avgInput: Math.round(tok.reduce((n, r) => n + r.inputTokens, 0) / tok.length), avgOutput: Math.round(tok.reduce((n, r) => n + (r.outputTokens ?? 0), 0) / tok.length) } : null,
      intents: graded.reduce((m, r) => ({ ...m, [r.intent ?? 'null']: (m[r.intent ?? 'null'] ?? 0) + 1 }), {}),
      sourceTypes: graded.reduce((m, r) => ({ ...m, [r.sourceType ?? 'null']: (m[r.sourceType ?? 'null'] ?? 0) + 1 }), {}),
    };
  }
  return out;
}

// ---- chạy --------------------------------------------------------------------------------------------------------------------------
mkdirSync(outDir, { recursive: true });
let aborted = null;
if (args.summarize !== 'true') {
  loadTitles();
  await applyConfig();
  if (args['apply-only'] === 'true') process.exit(0); // chỉ đặt công tắc (vd. đặt cấu hình A trước khi chạy eval.mjs 43 câu)
  const state = { lastLogId: maxLogId() };
  const done = new Set(existsSync(jsonl) ? readFileSync(jsonl, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)).filter((j) => !j.excluded).map((j) => `${j.config}:${j.run}:${j.id}`) : []);
  const subgroup = new Map(questions.map((q) => [q.id, q.expect.subgroup ?? null]));
  let consecutiveLimit = 0;
  try {
    let first = true;
    for (let r = 1; r <= RUNS; r++) {
      for (const q of questions) {
        if (done.has(`${CONFIG}:${r}:${q.id}`)) continue;
        if (!first) await sleep(GAP_MS);
        first = false;
        const row = await ask(q, r, state);
        const g = grade(q, row);
        appendFileSync(jsonl, JSON.stringify({ ...row, subgroup: subgroup.get(q.id), ...g }) + '\n');
        console.log(`${CONFIG} ${r}/${RUNS} ${q.id} ${row.excluded ? 'BỎ QUA' : g.pass ? 'ĐẠT ' : 'TRƯỢT'} ${q.group.padEnd(8)} ${String(row.result).padEnd(16)} ${String(row.sourceType ?? '-').padEnd(26)} server=${row.serverMs ?? '-'}ms${g.pass === false ? ' ✗' + g.failed.join(',') : ''}${row.excluded ? ' ' + row.excluded : ''}`);
        consecutiveLimit = row.excluded === 'PROVIDER_RATE_LIMIT' ? consecutiveLimit + 1 : 0;
        if (consecutiveLimit >= 3) throw new Error('Ba câu liền bị PROVIDER_RATE_LIMIT (hết hạn mức của Google): dừng, chạy lại cùng --label sau khi hạn mức phục hồi.');
      }
    }
  } catch (err) {
    aborted = err.message;
    console.error(`DỪNG: ${aborted}`);
  }
}
if (existsSync(jsonl)) {
  const summary = summarize();
  summary.aborted = aborted;
  writeFileSync(`${outDir}/v2-${label}.summary.json`, JSON.stringify(summary, null, 1));
  console.log('\n' + JSON.stringify(summary, null, 1));
}
process.exitCode = aborted ? 2 : 0;
