// Đọc 13 sơ đồ tuần tự từ docs/bao-cao (nguồn duy nhất: khối Mermaid) cho tab "Tuần tự" của trang HTML.
// TT.1, TT.3, TT.4, TT.6–TT.10, TT.12, TT.13 nằm ở 10-bieu-do-tuan-tu.md; TT.2, TT.5, TT.11 là ba hình cũ của 03-thiet-ke.md.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const KIND = [[/PostgreSQL/, 'db'], [/Gemini|SMTP|Redis|Cổng thanh toán/, 'ext'], [/^(Khách|Lễ tân|Bác sĩ|Quản lý|Trình duyệt)/, 'actor']];
const kindOf = name => (KIND.find(([re]) => re.test(name)) || [, 'svc'])[1];
const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

export function parseMermaid(code, where) {
  const parts = [], ids = new Set(), root = [], stack = [];
  const items = () => (stack.length ? stack.at(-1).branches.at(-1).items : root);
  const need = id => { if (!ids.has(id)) throw new Error(`${where}: cột "${id}" chưa khai báo`); };
  for (const raw of code.split('\n')) {
    const ln = raw.trim();
    if (!ln || ln === 'sequenceDiagram' || ln === 'autonumber') continue;
    let m;
    if ((m = /^participant (\w+) as (.+)$/.exec(ln))) { ids.add(m[1]); parts.push({ id: m[1], name: m[2], kind: kindOf(m[2]) }); }
    else if ((m = /^(alt|opt|loop)\s*(.*)$/.exec(ln))) { const f = { t: 'frag', k: m[1], branches: [{ cond: m[2], items: [] }] }; items().push(f); stack.push(f); }
    else if ((m = /^else\s*(.*)$/.exec(ln))) { if (!stack.length) throw new Error(`${where}: else ngoài khung`); stack.at(-1).branches.push({ cond: m[1], items: [] }); }
    else if (ln === 'end') { if (!stack.length) throw new Error(`${where}: end thừa`); stack.pop(); }
    else if ((m = /^Note over (\w+)(?:,(\w+))?: (.+)$/.exec(ln))) { need(m[1]); need(m[2] || m[1]); items().push({ t: 'note', from: m[1], to: m[2] || m[1], text: m[3] }); }
    else if ((m = /^(\w+)(-->>|->>)(\w+): (.+)$/.exec(ln))) { need(m[1]); need(m[3]); items().push({ t: 'msg', f: m[1], to: m[3], ret: m[2] === '-->>', text: m[4] }); }
    else throw new Error(`${where}: dòng Mermaid không hiểu: ${ln}`);
  }
  if (stack.length) throw new Error(`${where}: thiếu end`);
  return { parts, items: root };
}
const countMsg = its => its.reduce((n, it) => n + (it.t === 'msg' ? 1 : it.t === 'frag' ? it.branches.reduce((s, b) => s + countMsg(b.items), 0) : 0), 0);

export function parseSequences(root) {
  const dir = join(root, 'docs/bao-cao/');
  const own = readFileSync(dir + '10-bieu-do-tuan-tu.md', 'utf8');
  const old = [...readFileSync(dir + '03-thiet-ke.md', 'utf8').matchAll(/```mermaid\n([\s\S]*?)```/g)].map(m => m[1]).filter(b => b.trimStart().startsWith('sequenceDiagram'));
  if (old.length < 3) throw new Error('03-thiet-ke.md: cần 3 khối sequenceDiagram cũ, có ' + old.length);
  const OLD = { 2: old[0], 5: old[1], 11: old[2] }; // Hình 3.5, 3.6, 3.7
  const caps = {};
  for (const m of own.matchAll(/^\*\*Hình TT\.(\d+)\. (.+?)\*\*\s+\*(.+)\*\s*$/gm)) caps[+m[1]] = { title: m[2].replace(/\.$/, ''), src: m[3].replace(/\*\*|`/g, '').replace(/^Nguồn: /, '') };
  const out = [];
  for (const sec of own.split(/^## /m).slice(1)) {
    const h = /^TT\.(\d+)\. (.+)/.exec(sec);
    if (!h) continue;
    const n = +h[1], code = OLD[n] ?? /```mermaid\n([\s\S]*?)```/.exec(sec)?.[1];
    if (!code) throw new Error('TT.' + n + ': không có khối Mermaid');
    if (!caps[n]) throw new Error('TT.' + n + ': không có chú thích');
    const d = parseMermaid(code, 'TT.' + n);
    out.push({
      id: 'TT.' + n, n, uc: /UC\d+/.exec(h[2])?.[0], title: caps[n].title, src: esc(caps[n].src),
      status: /đặc tả, chưa cài đặt/i.test(h[2]) ? 'Đặc tả, chưa cài đặt' : undefined,
      msgs: countMsg(d.items), ...d,
    });
  }
  out.sort((a, b) => a.n - b.n);
  if (out.length !== 13) throw new Error('cần đúng 13 sơ đồ tuần tự, đang có ' + out.length);
  return out;
}
