// Cắt đoạn theo docs/07 BR-AI-15: theo tiêu đề Markdown, mỗi đoạn 300–1 200 ký tự, chồng lấn 100 ký tự khi phải cắt tiếp.
// Văn bản đem nhúng = tiêu đề tài liệu + tiêu đề mục + nội dung đoạn.
const MIN = 300;
const MAX = 1200;
const OVERLAP = 100;

function sections(content) {
  const out = [];
  let cur = { heading: null, lines: [] };
  for (const line of content.split(/\r?\n/)) {
    const m = /^#{1,3}\s+(.*)$/.exec(line);
    if (m) {
      out.push(cur);
      cur = { heading: m[1].trim(), lines: [] };
    } else cur.lines.push(line);
  }
  out.push(cur);
  return out.map((s) => ({ heading: s.heading, text: s.lines.join('\n').trim() })).filter((s) => s.text);
}

// Tách một mục dài thành các mảnh <= MAX, ưu tiên ranh giới đoạn văn rồi câu; mảnh sau lặp OVERLAP ký tự cuối của mảnh trước.
function split(text) {
  if (text.length <= MAX) return [text];
  const units = text.split(/\n{2,}/).flatMap((p) => (p.length <= MAX ? [p] : p.split(/(?<=[.!?])\s+/)));
  const pieces = [];
  let cur = '';
  for (const u of units) {
    if (cur && cur.length + u.length + 1 > MAX) {
      pieces.push(cur);
      const tail = cur.slice(-OVERLAP);
      cur = tail.slice(tail.indexOf(' ') + 1) + ' ' + u;
    } else cur = cur ? `${cur}\n${u}` : u;
  }
  if (cur) pieces.push(cur);
  return pieces;
}

export function chunkDoc({ title, content }) {
  // Gộp mục ngắn (< MIN) vào mục kế tiếp nếu vẫn <= MAX.
  const merged = [];
  let cur = null;
  for (const s of sections(content)) {
    if (cur && cur.text.length < MIN && cur.text.length + s.text.length + 2 <= MAX) {
      cur.text += `\n\n${s.heading ? s.heading + '\n' : ''}${s.text}`;
    } else {
      if (cur) merged.push(cur);
      cur = { ...s };
    }
  }
  if (cur) merged.push(cur);
  const last = merged.at(-1);
  if (merged.length > 1 && last.text.length < MIN) {
    merged[merged.length - 2].text += `\n\n${last.heading ? last.heading + '\n' : ''}${last.text}`;
    merged.pop();
  }
  const chunks = [];
  for (const s of merged) for (const piece of split(s.text)) chunks.push({ heading: s.heading, text: piece });
  return chunks.map((c, i) => ({
    ordinal: i + 1,
    heading: c.heading,
    text: c.text,
    embedText: [title, c.heading, c.text].filter(Boolean).join('\n'),
  }));
}
