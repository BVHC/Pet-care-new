// Đo lại chatbot ở T77 qua BE THẬT (docs/07 §14.1, §13 "Đo lại ở T77", docs/adr/0122 và 0131): gọi POST /api/public/ai/chat,
// giãn nhịp, chấm từng câu theo bộ 43 câu của spike (chỉ các câu endpoint "public"; nhóm /me là của T77c) và đo độ trễ.
//
// Khác spike (BE/scripts/spike/eval.mjs): spike tự dựng pipeline trong Node; ở đây pipeline là CODE JAVA của BE
// (ChatService, ChunkRetriever, GeminiClient), tri thức là KB demo đã nạp qua UC93 (BE/scripts/ai-kb/load-demo-kb.mjs).
// Người rà soát của KB demo là GIẢ ĐỊNH (Hiến quyết 9/11/2026); kết quả đo không chứng minh chất lượng nội dung y khoa.
//
// Chạy (BE 8082 + DB demo 5433 + GEMINI_API_KEY trong tiến trình BE; KB đã nạp):
//   node BE/scripts/ai-eval/eval.mjs --label t77 [--api http://localhost:8082] [--gap-ms 7000] [--runs 1] [--only Q01,Q02]
//        [--no-db] [--container petcare-seed-pg] [--db petcare_seed] [--summarize]
// - Giới hạn tần suất của chính BE (BR-AI-09): 10 câu / phút và 60 câu / giờ mỗi IP. Một lượt 32 câu cách nhau 7 giây là đủ
//   dưới cả hai; chạy hai lượt trong một giờ sẽ chạm 60 / giờ. Gặp 429 thì chờ Retry-After (tối đa 120 giây, quá thì dừng);
//   lượt 429 KHÔNG tính làm mẫu độ trễ.
// - Độ trễ: `clientMs` (đo ở máy khách, gồm mạng cục bộ) và `serverMs` (cột latency_ms của ai_chat_logs, đọc bằng psql
//   trong container demo, chỉ đọc). "Đường LLM" = dòng nhật ký có llm_model; so với spike dùng `llmPathOk*` (bỏ lượt UNAVAILABLE,
//   như `endToEndLlmPath` của spike); `llmPathAll*` gồm cả lượt hết giờ / lỗi nhà cung cấp.
// - Mỗi câu ghi ngay một dòng vào results/t77-<label>.jsonl; chạy lại cùng --label thì BỎ QUA câu đã xong.
// - Nhật ký hỏi đáp của lượt đo nằm trong DB demo (ai_chat_logs, giữ 30 ngày rồi ST23 xóa); câu hỏi là bộ cố định, không PII.
import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { doseViolation, leaksPrompt } from '../spike/guards.mjs';

const dir = fileURLToPath(new URL('.', import.meta.url));
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--')
  ? [...acc, [a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : 'true']] : acc), []));
const API = (args.api ?? process.env.AI_API_URL ?? 'http://localhost:8082').replace(/\/$/, '');
const label = args.label ?? 't77';
const RUNS = Number(args.runs ?? 1);
const GAP_MS = Number(args['gap-ms'] ?? 7000);
const USE_DB = args['no-db'] !== 'true';
const CONTAINER = args.container ?? 'petcare-seed-pg';
const DB_NAME = args.db ?? 'petcare_seed';
const only = args.only ? new Set(args.only.split(',')) : null;
const fx = JSON.parse(readFileSync(`${dir}../spike/questions.json`, 'utf8'));
const questions = fx.questions.filter((q) => q.endpoint === 'public' && (!only || only.has(q.id)));
const outDir = `${dir}results`;
const jsonl = `${outDir}/t77-${label}.jsonl`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- bản đồ slug → mã tài liệu do load-demo-kb.mjs ghi (ngoài git) --------------------------------------------------
const mapFile = args['slug-map'] ?? `${dir}../../../../.c2-tmp/ai-kb/slug-map.json`;
const slugById = new Map();
if (existsSync(mapFile)) {
  for (const [slug, v] of Object.entries(JSON.parse(readFileSync(mapFile, 'utf8')).slugs)) slugById.set(v.documentId, slug);
} else {
  console.error(`CẢNH BÁO: không thấy ${mapFile}; không chấm được nguồn theo slug (chạy load-demo-kb.mjs trước).`);
}

// ---- đọc nhật ký hỏi đáp của BE (chỉ đọc) ----------------------------------------------------------------------------
function psql(sql) {
  return execFileSync('docker', ['exec', CONTAINER, 'psql', '-U', 'postgres', '-d', DB_NAME, '-At', '-F', '|', '-c', sql],
    { encoding: 'utf8', timeout: 20_000 }).trim();
}
function lastLog(afterId) {
  if (!USE_DB) return null;
  const line = psql(`SELECT id, result, coalesce(error_kind,''), (llm_model IS NOT NULL), latency_ms, coalesce(top_similarity::text,''),
    coalesce(input_tokens::text,''), coalesce(output_tokens::text,'') FROM ai_chat_logs WHERE id > ${afterId} ORDER BY id DESC LIMIT 1`);
  if (!line) return null;
  const [id, result, errorKind, llm, latency, topSim, inTok, outTok] = line.split('|');
  return { id: Number(id), result, errorKind: errorKind || null, llmCalled: llm === 't', serverMs: Number(latency),
    topSimilarity: topSim === '' ? null : Number(topSim), inputTokens: inTok === '' ? null : Number(inTok), outputTokens: outTok === '' ? null : Number(outTok) };
}
const maxLogId = () => (USE_DB ? Number(psql('SELECT coalesce(max(id),0) FROM ai_chat_logs')) : 0);

// ---- một câu --------------------------------------------------------------------------------------------------------
async function ask(q, run, state) {
  const row = { run, id: q.id, group: q.group, question: q.question, status: null, result: null, answer: null, sources: [], bookingHint: null,
    emergencyBranches: 0, clientMs: null, serverMs: null, llmCalled: null, embedCalled: null, errorKind: null, topSimilarity: null,
    inputTokens: null, outputTokens: null, rateLimited: 0 };
  for (;;) {
    const t0 = performance.now();
    const res = await fetch(`${API}/api/public/ai/chat`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ question: q.question }) });
    const ms = performance.now() - t0;
    const body = await res.json().catch(() => null);
    row.status = res.status;
    if (res.status === 429) {
      const wait = Number(res.headers.get('retry-after') ?? 60);
      row.rateLimited++;
      if (wait > 120) throw new Error(`BE chặn tần suất (429), Retry-After ${wait} giây: dừng (đã chạm 60 câu / giờ?). Chạy lại cùng --label sau khi hết cửa sổ.`);
      console.log(`  429, chờ ${wait} giây rồi gửi lại ${q.id}`);
      await sleep((wait + 1) * 1000);
      continue;
    }
    row.clientMs = Math.round(ms);
    if (res.status === 200 && body?.data) {
      const d = body.data;
      row.result = d.result;
      row.answer = d.answer;
      row.sources = (d.sources ?? []).map((s) => slugById.get(s.documentId) ?? `#${s.documentId}`);
      row.bookingHint = d.bookingHint;
      row.emergencyBranches = (d.emergencyBranches ?? []).length;
    } else {
      row.error = `${body?.errorCode ?? ''} ${String(body?.message ?? '').slice(0, 160)}`.trim();
    }
    break;
  }
  const log = lastLog(state.lastLogId);
  if (log) {
    state.lastLogId = log.id;
    Object.assign(row, { serverMs: log.serverMs, llmCalled: log.llmCalled, errorKind: log.errorKind, topSimilarity: log.topSimilarity,
      inputTokens: log.inputTokens, outputTokens: log.outputTokens, logResult: log.result });
    row.embedCalled = log.topSimilarity !== null || (log.result === 'UNAVAILABLE' && !log.llmCalled);
  }
  return row;
}

function grade(q, row) {
  const e = q.expect;
  const c = {};
  c.http = row.status === 200;
  c.result = c.http && e.results.includes(row.result);
  if (row.logResult && c.http) c.logConsistent = row.logResult === row.result;
  if (e.embed !== undefined && row.embedCalled !== null) c.embed = row.embedCalled === e.embed;
  if (e.llm !== undefined && row.llmCalled !== null) c.llm = row.llmCalled === e.llm;
  if (e.mustMatch) c.content = row.result === 'ANSWERED' && new RegExp(e.mustMatch, 'iu').test(row.answer ?? '');
  if (e.noDose) c.noDose = !doseViolation(row.answer ?? '');
  if (e.noLeak) c.noLeak = !leaksPrompt(row.answer ?? '');
  if (e.docs && row.result === 'ANSWERED') c.sources = e.docs.some((d) => row.sources.includes(d)); // chỉ báo cáo, không làm câu trượt
  const required = Object.entries(c).filter(([k]) => k !== 'sources');
  return { checks: c, pass: required.every(([, v]) => v), failed: required.filter(([, v]) => !v).map(([k]) => k) };
}

// ---- tổng hợp -------------------------------------------------------------------------------------------------------
const pct = (arr, p) => {
  if (!arr.length) return null;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.max(0, Math.ceil(p * s.length) - 1)];
};
const lat = (rows, k) => ({ n: rows.filter((r) => r[k] != null).length, p50: pct(rows.map((r) => r[k]).filter((x) => x != null), 0.5), p95: pct(rows.map((r) => r[k]).filter((x) => x != null), 0.95),
  max: rows.length ? Math.max(...rows.map((r) => r[k] ?? 0)) : null });
const count = (arr) => arr.reduce((m, x) => ({ ...m, [x]: (m[x] ?? 0) + 1 }), {});

function summarize() {
  const all = readFileSync(jsonl, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
  const byGroup = {};
  for (const r of all) { (byGroup[r.group] ??= { n: 0, pass: 0 }).n++; if (r.pass) byGroup[r.group].pass++; }
  const ok = all.filter((r) => r.status === 200);
  const llmRows = ok.filter((r) => r.llmCalled === true);
  const llmOkRows = llmRows.filter((r) => !r.errorKind); // cách tính của spike T56: chỉ lượt gọi LLM thành công
  const unavailable = ok.filter((r) => r.result === 'UNAVAILABLE');
  const safety = (fx.safetyGroups ?? []).filter((g) => byGroup[g]);
  const sim = {};
  for (const g of Object.keys(byGroup)) {
    const v = ok.filter((r) => r.group === g && r.topSimilarity !== null).map((r) => r.topSimilarity);
    sim[g] = v.length ? { min: Math.min(...v), max: Math.max(...v), n: v.length } : null;
  }
  const tok = llmRows.filter((r) => r.inputTokens != null);
  return {
    label, finishedAt: new Date().toISOString(), api: API, model: 'đọc ở app.ai.llm-model của BE (mặc định gemini-3.1-flash-lite, ADR-0122)',
    gapMs: GAP_MS, rows: all.length, rowsPassed: all.filter((r) => r.pass).length, httpErrors: all.filter((r) => r.status !== 200).length,
    rateLimitedRetries: all.reduce((n, r) => n + r.rateLimited, 0),
    groups: byGroup,
    inKbPassed: `${byGroup['in-kb']?.pass ?? 0}/${byGroup['in-kb']?.n ?? 0}`,
    safetyGroups: safety, safetyGroupsAllPass: safety.every((g) => byGroup[g].pass === byGroup[g].n),
    results: count(ok.map((r) => r.result)),
    unavailable: { count: unavailable.length, rate: ok.length ? +(unavailable.length / ok.length).toFixed(3) : null, byKind: count(unavailable.map((r) => r.errorKind ?? '?')) },
    failed: all.filter((r) => !r.pass).map((r) => ({ run: r.run, id: r.id, group: r.group, result: r.result, failed: r.failed, topSimilarity: r.topSimilarity, sources: r.sources })),
    latencyMs: {
      llmPathOkServer: lat(llmOkRows, 'serverMs'), llmPathOkClient: lat(llmOkRows, 'clientMs'),
      llmPathAllServer: lat(llmRows, 'serverMs'), llmPathAllClient: lat(llmRows, 'clientMs'),
      allServer: lat(ok, 'serverMs'), allClient: lat(ok, 'clientMs'),
      criterion: 'p95 end-to-end ≤ 5000 ms trên ≥ 20 lượt (07 §14.1); spike T56 đo 6435 ms (đường LLM, chỉ lượt thành công)',
      llmPathOkSamples: llmOkRows.length, llmPathAllSamples: llmRows.length,
      meetsFiveSecondGoal: llmOkRows.length >= 20 ? pct(llmOkRows.map((r) => r.serverMs).filter((x) => x != null), 0.95) <= 5000 : null,
    },
    tokens: tok.length ? { avgInput: Math.round(tok.reduce((n, r) => n + r.inputTokens, 0) / tok.length), avgOutput: Math.round(tok.reduce((n, r) => n + (r.outputTokens ?? 0), 0) / tok.length) } : null,
    similarityByGroup: sim,
  };
}

// ---- chạy -----------------------------------------------------------------------------------------------------------
mkdirSync(outDir, { recursive: true });
let aborted = null;
if (args.summarize !== 'true') {
  const state = { lastLogId: maxLogId() };
  const finished = new Set(existsSync(jsonl) ? readFileSync(jsonl, 'utf8').split('\n').filter(Boolean).map((l) => { const j = JSON.parse(l); return `${j.run}:${j.id}`; }) : []);
  try {
    let first = true;
    for (let r = 1; r <= RUNS; r++) {
      for (const q of questions) {
        if (finished.has(`${r}:${q.id}`)) continue;
        if (!first) await sleep(GAP_MS);
        first = false;
        const row = await ask(q, r, state);
        const g = grade(q, row);
        appendFileSync(jsonl, JSON.stringify({ ...row, ...g }) + '\n');
        console.log(`${r}/${RUNS} ${q.id} ${g.pass ? 'ĐẠT ' : 'TRƯỢT'} ${q.group.padEnd(14)} ${String(row.result).padEnd(16)} sim=${row.topSimilarity ?? '-'} client=${row.clientMs}ms server=${row.serverMs ?? '-'}ms${row.llmCalled ? ' LLM' : ''}${g.pass ? '' : ' ✗' + g.failed.join(',')}`);
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
  writeFileSync(`${outDir}/t77-${label}.summary.json`, JSON.stringify(summary, null, 1));
  console.log('\n' + JSON.stringify(summary, null, 1));
}
process.exitCode = aborted ? 2 : 0;
