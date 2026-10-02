const COMBINING_FIRST = 0x300;
const COMBINING_LAST = 0x36f;

/** Lower-case text with Vietnamese tone marks and "đ" folded, so "nguyen" finds "Nguyễn". */
export function foldVi(text: string): string {
  let out = '';
  for (const ch of text.normalize('NFD')) {
    const code = ch.codePointAt(0) ?? 0;
    if (code >= COMBINING_FIRST && code <= COMBINING_LAST) continue;
    out += ch;
  }
  return out.replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase();
}
