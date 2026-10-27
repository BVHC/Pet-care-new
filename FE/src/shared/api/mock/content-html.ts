// Bộ lọc HTML bài viết của mock, mô phỏng BE `ArticleHtml` (ADR-0071): jsoup 1.18.3 `Safelist.relaxed()` với a[href] và img[src] chỉ
// nhận https. Bài hiển thị cho người chưa đăng nhập nên đây là chốt chặn stored XSS; mock lọc khi lưu và trả lại bản đã lọc.
// Dựng chuỗi ra từ danh sách cho phép (thẻ, thuộc tính, giao thức) thay vì xóa phần nguy hiểm khỏi DOM, nên thứ không có trong
// danh sách không thể lọt. Bảng cặp [vào, ra] lấy từ chính jsoup nằm ở content-html.test.ts.
//
// Khác BE ở chỗ vô hại: không in đẹp (BE xuống dòng / thụt lề giữa các khối), và <![CDATA[...]]> bị bỏ (trình duyệt coi là comment).

/** `Safelist.relaxed()` của jsoup 1.18.3. */
const TAGS = new Set([
  'a', 'b', 'blockquote', 'br', 'caption', 'cite', 'code', 'col', 'colgroup', 'dd', 'div', 'dl', 'dt', 'em',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'i', 'img', 'li', 'ol', 'p', 'pre', 'q', 'small', 'span', 'strike', 'strong',
  'sub', 'sup', 'table', 'tbody', 'td', 'tfoot', 'th', 'thead', 'tr', 'u', 'ul',
]);
const VOID = new Set(['br', 'col', 'img']);

const TD_ATTRS = ['abbr', 'axis', 'colspan', 'rowspan', 'width'];
const ATTRS: Record<string, readonly string[]> = {
  a: ['href', 'title'],
  blockquote: ['cite'],
  col: ['span', 'width'],
  colgroup: ['span', 'width'],
  img: ['align', 'alt', 'height', 'src', 'title', 'width'],
  ol: ['start', 'type'],
  q: ['cite'],
  table: ['summary', 'width'],
  td: TD_ATTRS,
  th: [...TD_ATTRS, 'scope'],
  ul: ['type'],
};

/** Thuộc tính chứa URL → giao thức cho phép. BE thu hẹp a[href] và img[src] còn https; cite của relaxed() nhận http và https. */
const PROTOCOLS: Record<string, readonly string[]> = {
  'a.href': ['https'],
  'img.src': ['https'],
  'blockquote.cite': ['http', 'https'],
  'q.cite': ['http', 'https'],
};

/**
 * Như jsoup: bỏ ký tự điều khiển và khoảng trắng hai đầu, giao thức phải nằm trong danh sách (không phân biệt hoa thường, ghi lại
 * bằng chữ thường). URL tương đối, `//host`, `#neo` đều không có giao thức nên bị bỏ.
 */
function allowedUrl(raw: string, protocols: readonly string[]): string | null {
  // eslint-disable-next-line no-control-regex -- jsoup bỏ mọi ký tự điều khiển trong URL trước khi kiểm giao thức
  const url = raw.replace(/[\u0000-\u001f]/g, '').replace(/^ +| +$/g, '');
  const scheme = /^([A-Za-z][A-Za-z0-9+.-]*):/.exec(url)?.[1]?.toLowerCase();
  return scheme && protocols.includes(scheme) ? scheme + url.slice(scheme.length) : null;
}

const escapeText = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\xa0/g, '&nbsp;');
const escapeAttr = (s: string) => s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/\xa0/g, '&nbsp;');

function attributesOf(el: Element, tag: string): string {
  const allowed = ATTRS[tag] ?? [];
  let out = '';
  // Theo thứ tự trong nguồn như jsoup; thuộc tính trùng tên: trình phân tích HTML đã giữ giá trị đầu.
  for (const { name, value } of Array.from(el.attributes)) {
    if (!allowed.includes(name)) continue;
    const protocols = PROTOCOLS[`${tag}.${name}`];
    const clean = protocols ? allowedUrl(value, protocols) : value;
    if (clean !== null) out += ` ${name}="${escapeAttr(clean)}"`;
  }
  return out;
}

function serialize(parent: Node): string {
  let out = '';
  for (const node of Array.from(parent.childNodes)) {
    if (node.nodeType === Node.TEXT_NODE) {
      out += escapeText(node.nodeValue ?? '');
    } else if (node.nodeType === Node.ELEMENT_NODE) {
      const el = node as Element;
      const tag = el.localName.toLowerCase();
      if (tag === 'script' || tag === 'style') continue; // bỏ cả nội dung
      if (!TAGS.has(tag)) out += serialize(el); // thẻ lạ: bỏ thẻ, giữ phần bên trong
      else out += VOID.has(tag) ? `<${tag}${attributesOf(el, tag)}>` : `<${tag}${attributesOf(el, tag)}>${serialize(el)}</${tag}>`;
    }
    // comment, doctype…: bỏ
  }
  return out;
}

/** Như jsoup `parseBodyFragment`: tiền tố `<body>` để mọi thứ (kể cả title, meta, script ở đầu) vào body chứ không về head. */
const parseBody = (html: string) => new DOMParser().parseFromString(`<body>${html}`, 'text/html').body;

/** `ArticleHtml.clean`: HTML đã lọc. Phía gọi tự chuẩn hóa NFC + strip trước (như `ContentText.normalize`). */
export const cleanArticleHtml = (html: string): string => serialize(parseBody(html));

/** `ArticleHtml.hasText`: bản đã lọc còn chữ (nbsp tính là trống) hoặc ảnh. */
export const hasArticleText = (cleanHtml: string): boolean => {
  const body = parseBody(cleanHtml);
  return (body.textContent ?? '').trim() !== '' || body.querySelector('img') !== null;
};
