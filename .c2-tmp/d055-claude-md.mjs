// D055: xóa bullet "BẮT BUỘC cho FE-1: refresh token" và sửa câu "no refresh token yet" ở bullet HTTP trong CLAUDE.md. Không sửa chỗ nào khác.
import { readFileSync, writeFileSync } from 'node:fs';

const path = 'D:/Do-an/Pet-care-new/CLAUDE.md';
const raw = readFileSync(path, 'utf8');
const eol = raw.includes('\r\n') ? '\r\n' : '\n';
const lines = raw.split(eol);

const start = '- **BẮT BUỘC cho FE-1: refresh token (D055, ADR-0091).**';
const hits = lines.filter((l) => l.startsWith(start));
if (hits.length !== 1) throw new Error(`bullet D055: tìm thấy ${hits.length} dòng`);
const kept = lines.filter((l) => !l.startsWith(start));

const oldPhrase = 'no refresh token **yet** (D055 above) — 401 ends the session,';
const newPhrase =
  'access token 10 min renewed by `refreshSession()` (D055, ADR-0091: user requests and real clicks / keys refresh when < 5 min is left; polls pass `POLL` and never refresh, `useAccessValid` pauses them once the token has expired; single-flight + Web Locks) — a 401 that refresh cannot fix ends the session,';
const http = kept.findIndex((l) => l.startsWith('- **HTTP:**'));
if (http < 0 || !kept[http].includes(oldPhrase)) throw new Error('bullet HTTP: không thấy câu cần sửa');
kept[http] = kept[http].replace(oldPhrase, newPhrase);

writeFileSync(path, kept.join(eol));
console.log(`ok: bỏ 1 dòng, sửa bullet HTTP (dòng ${http + 1}), EOL=${eol === '\r\n' ? 'CRLF' : 'LF'}`);
