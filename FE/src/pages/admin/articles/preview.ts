// Khung `srcDoc` cho iframe xem trước bài. iframe có `sandbox=""` (không script, không same-origin); CSP là lớp thứ hai: không tải gì
// ngoài ảnh https và CSS nội tuyến. Bản xem trước là HTML người soạn gõ, CHƯA qua bộ lọc của BE (lọc khi lưu).
const CSS = [
  'body{margin:16px;font:16px/1.7 system-ui,-apple-system,"Segoe UI",sans-serif;color:#1f2937;overflow-wrap:anywhere}',
  'h1,h2,h3,h4,h5,h6{line-height:1.3;margin:1.2em 0 .5em}',
  'p,ul,ol,dl,blockquote,pre,table{margin:0 0 1em}',
  'img{max-width:100%;height:auto}',
  'a{color:#a43324}',
  'blockquote{border-left:3px solid #d1d5db;margin-left:0;padding-left:12px;color:#4b5563}',
  'pre{background:#f3f4f6;padding:12px;overflow:auto}',
  'table{border-collapse:collapse}',
  'td,th{border:1px solid #d1d5db;padding:6px 10px}',
].join('');

const POLICY = "default-src 'none'; img-src https:; style-src 'unsafe-inline'";

export const previewDocument = (html: string) =>
  `<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${POLICY}">` +
  `<meta name="viewport" content="width=device-width,initial-scale=1"><style>${CSS}</style></head><body>${html}</body></html>`;
