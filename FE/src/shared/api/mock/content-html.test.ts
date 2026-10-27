import { describe, expect, it } from 'vitest';
import { cleanArticleHtml, hasArticleText } from './content-html';

// Bộ lọc HTML của mock mô phỏng BE `ArticleHtml` (jsoup 1.18.3 `Safelist.relaxed()`; a[href], img[src] chỉ https). Mọi cặp
// [vào, ra] ở dưới lấy từ chính jsoup 1.18.3 với đúng cấu hình của BE (`Jsoup.clean`, tắt prettyPrint cho dễ so; BE thật in
// đẹp có xuống dòng / thụt lề nên chuỗi lưu ở BE khác khoảng trắng giữa các khối, phần còn lại giống nhau). Khác biệt đã biết,
// không kiểm: <![CDATA[...]]> (jsoup giữ chữ, trình duyệt coi là comment).
type Case = [name: string, input: string, output: string];

const DANGER: Case[] = [
  ['script và thuộc tính onclick', '<p onclick="x()">a</p><script>alert(1)</script>', '<p>a</p>'],
  ['script giữa đoạn văn bị bỏ cả nội dung', '<p>a<script>b</script>c</p>', '<p>ac</p>'],
  ['style đầu bài bị bỏ cả nội dung', '<style>p{color:red}</style><p>x</p>', '<p>x</p>'],
  ['style giữa bài bị bỏ cả nội dung', '<p>a</p><style>p{color:red}</style><p>b</p>', '<p>a</p><p>b</p>'],
  ['script trong svg bị bỏ cả nội dung', '<svg><script>alert(1)</script></svg><p>x</p>', '<p>x</p>'],
  ['svg onload không còn gì', '<svg onload=alert(1)><circle/></svg>', ''],
  ['onmouseover không có ngoặc kép', '<p onclick="alert(1)" onmouseover=alert(2)>ev</p>', '<p>ev</p>'],
  ['onerror trên ảnh https: giữ ảnh, bỏ onerror', '<img src="https://x/a.png" onerror=alert(1)//>', '<img src="https://x/a.png">'],
  ['onclick trên link https', '<a href="https://e.com" onclick="x()">c</a>', '<a href="https://e.com">c</a>'],
  ['class, style, id bị bỏ', '<p class="a" style="x" id="y">z</p>', '<p>z</p>'],
  ['style và data-* bị bỏ', '<span style="color:red" data-x="1">s</span>', '<span>s</span>'],
  ['id và class trên tiêu đề', '<h2 id="x" class="y">h</h2>', '<h2>h</h2>'],
  ['thẻ lồng tên script', '<scr<script>ipt>alert(1)</script>', 'ipt&gt;alert(1)'],
  ['comment bị bỏ', '<!-- comment --><p>x</p>', '<p>x</p>'],
  ['comment có điều kiện IE bị bỏ', '<p>text</p><!--[if IE]>x<![endif]-->', '<p>text</p>'],
];

const UNWRAP: Case[] = [
  ['font', '<font color="red">hi <b>there</b></font>', 'hi <b>there</b>'],
  ['section', '<section><h2>t</h2></section>', '<h2>t</h2>'],
  ['lồng nhiều tầng', '<div><span><font><b>x</b></font></span></div>', '<div><span><b>x</b></span></div>'],
  ['form, input, button', '<form action="/x"><input value="v"><button>go</button></form>', 'go'],
  ['select, option', '<select><option>one</option></select>', 'one'],
  ['video, audio', '<video src="https://e.com/v.mp4">v</video><audio src="https://e.com/a.mp3"></audio>', 'v'],
  ['object', '<object data="https://e.com">o</object>', 'o'],
  ['object, param, embed không còn gì', '<object><param name="x" value="y"><embed src="https://e.com"></object>', ''],
  ['iframe giữ chữ dự phòng', '<iframe src="https://e.com">fallback</iframe><p>x</p>', 'fallback<p>x</p>'],
  ['title giữ chữ', '<title>t</title><p>x</p>', 't<p>x</p>'],
  ['textarea: nội dung là chữ, thẻ bên trong bị thoát', '<p>x<textarea>abc <b>d</b></textarea></p>', '<p>xabc &lt;b&gt;d&lt;/b&gt;</p>'],
  ['noscript giữ phần tử con', '<noscript><p>ns</p></noscript>', '<p>ns</p>'],
  ['hr bị bỏ', '<hr><p>after hr</p>', '<p>after hr</p>'],
  ['math giữ chữ', '<math><mi>x</mi></math><p>m</p>', 'x<p>m</p>'],
  ['meta và link bị bỏ', '<meta http-equiv="refresh" content="0;url=https://e.com"><link rel="stylesheet" href="https://e.com/x.css"><p>x</p>', '<p>x</p>'],
  ['base bị bỏ, link tương đối mất href', '<base href="https://e.com/"><a href="rel">r</a>', '<a>r</a>'],
  ['cả tài liệu html', '<html><head><title>t</title></head><body onload="x()"><p>x</p></body></html>', 't<p>x</p>'],
  ['doctype bị bỏ', '<!DOCTYPE html><p>x</p>', '<p>x</p>'],
];

const LINKS: Case[] = [
  ['javascript: và http: mất href, ảnh http mất src nhưng còn thẻ img', '<a href="javascript:alert(1)">x</a><a href="http://e.com">y</a><img src="http://e.com/a.png">', '<a>x</a><a>y</a><img>'],
  ['https được giữ', '<p><a href="https://e.com/x">x</a><img src="https://e.com/a.png"></p>', '<p><a href="https://e.com/x">x</a><img src="https://e.com/a.png"></p>'],
  ['khoảng trắng đầu href bị cắt', '<a href=" https://x">sp</a>', '<a href="https://x">sp</a>'],
  ['khoảng trắng hai đầu href bị cắt', '<a href="  https://x  ">t</a>', '<a href="https://x">t</a>'],
  ['scheme viết hoa được chữ thường hóa, phần còn lại giữ nguyên', '<a href="HTTPS://X.com/p">up</a>', '<a href="https://X.com/p">up</a>'],
  ['scheme viết hoa kèm query và fragment', '<a href="HTTPS://E.com/P?q=1#f">u</a>', '<a href="https://E.com/P?q=1#f">u</a>'],
  ['scheme-relative //', '<a href="//e.com">scheme-relative</a>', '<a>scheme-relative</a>'],
  ['đường dẫn tuyệt đối /rel', '<a href="/rel">rel</a>', '<a>rel</a>'],
  ['đường dẫn tương đối', '<a href="rel/path">rel2</a>', '<a>rel2</a>'],
  ['neo #', '<a href="#anchor">anchor</a>', '<a>anchor</a>'],
  ['mailto:', '<a href="mailto:x@y.z">mail</a>', '<a>mail</a>'],
  ['ftp:', '<a href="ftp://e.com/f">ftp</a>', '<a>ftp</a>'],
  ['data: trên link', '<a href="data:text/html;base64,PHNjcmlwdD4=">d</a>', '<a>d</a>'],
  ['data: trên ảnh', '<img src="data:image/png;base64,AAAA">', '<img>'],
  ['javascript: chèn xuống dòng bằng thực thể', '<a href="java&#x0A;script:alert(1)">e1</a>', '<a>e1</a>'],
  ['javascript: viết bằng thực thể', '<a href="&#106;avascript:alert(1)">e2</a>', '<a>e2</a>'],
  ['IMG SRC=JaVaScRiPt:', '<IMG SRC=JaVaScRiPt:alert(1)>', '<img>'],
  ['& trong URL được thoát', '<a href="https://e.com?a=1&b=2">amp</a>', '<a href="https://e.com?a=1&amp;b=2">amp</a>'],
  ['URL có khoảng trắng giữ nguyên', '<img src="https://e.com/a b.png">', '<img src="https://e.com/a b.png">'],
  ['URL có ký tự Unicode giữ nguyên', '<a href="https://e.com/ü">uni</a>', '<a href="https://e.com/ü">uni</a>'],
  ['https: trơ trọi vẫn qua (như jsoup)', '<a href="https:">h</a>', '<a href="https:">h</a>'],
  ['thuộc tính trùng: lấy giá trị đầu (không để javascript: lọt)', '<a href="https://e.com" href="javascript:alert(1)">dup</a>', '<a href="https://e.com">dup</a>'],
  ['src trùng: lấy giá trị đầu', '<img src="https://a.png" src="javascript:x">', '<img src="https://a.png">'],
  ['giữ title, bỏ target và rel', '<a href="HtTpS://e.com" title="t" target="_blank" rel="x">mix</a>', '<a href="https://e.com" title="t">mix</a>'],
  ['ngoặc kép trong href không thoát được khỏi thuộc tính', '<a href="https://e.com/"onmouseover=alert(1)">q</a>', '<a href="https://e.com/">q</a>'],
  ['ảnh trong link', '<a href="https://e.com"><img src="https://e.com/a.png" alt="x"></a>', '<a href="https://e.com"><img src="https://e.com/a.png" alt="x"></a>'],
  ['cite của blockquote và q nhận http/https, cite của <cite> bị bỏ', '<blockquote cite="https://e.com/q">q</blockquote><blockquote cite="javascript:x">q2</blockquote><q cite="http://e.com">qq</q><cite cite="https://e.com">cc</cite>', '<blockquote cite="https://e.com/q">q</blockquote><blockquote>q2</blockquote><q cite="http://e.com">qq</q><cite>cc</cite>'],
];

const RELAXED: Case[] = [
  ['định dạng cơ bản', '<h2>T</h2><p><strong>b</strong> <em>i</em></p><ul><li>1</li></ul>', '<h2>T</h2><p><strong>b</strong> <em>i</em></p><ul><li>1</li></ul>'],
  ['ol start và type', '<ol start="6" type="a"><li>x</li></ol>', '<ol start="6" type="a"><li>x</li></ol>'],
  ['ul type, dl, dt, dd', '<ul type="disc"><li>1</li></ul><dl><dt>t</dt><dd>d</dd></dl>', '<ul type="disc"><li>1</li></ul><dl><dt>t</dt><dd>d</dd></dl>'],
  ['pre, code, small, sub, sup, strike, u, span, i, b', '<pre>  a\n  b </pre><code>c</code><small>s</small><sub>1</sub><sup>2</sup><strike>x</strike><u>u</u><span>sp</span><i>i</i><b>b</b>', '<pre>  a\n  b </pre><code>c</code><small>s</small><sub>1</sub><sup>2</sup><strike>x</strike><u>u</u><span>sp</span><i>i</i><b>b</b>'],
  ['h1, h3, h4, h5, h6', '<h1>1</h1><h3>3</h3><h4>4</h4><h5>5</h5><h6>6</h6>', '<h1>1</h1><h3>3</h3><h4>4</h4><h5>5</h5><h6>6</h6>'],
  ['thuộc tính ảnh: alt được thoát, bỏ onerror và class', '<img src="https://x/a.png" alt=\'a"b\' width="10" height="5" onerror="alert(1)" title="t" align="left" class="c">', '<img src="https://x/a.png" alt="a&quot;b" width="10" height="5" title="t" align="left">'],
  ['ảnh có alt rỗng', '<img src="https://e.com/b.png" alt="">', '<img src="https://e.com/b.png" alt="">'],
  ['bảng đầy đủ: caption, colgroup, col, thead, tbody, tfoot, th, td', '<table><caption>c</caption><colgroup><col span="2" width="5" class="k"></colgroup><thead><tr><th scope="col" abbr="a" axis="b" colspan="1" rowspan="1" width="9" class="z">h</th></tr></thead><tbody><tr><td abbr="a" axis="b" colspan="2" rowspan="1" width="3">d</td></tr></tbody><tfoot><tr><td>f</td></tr></tfoot></table>', '<table><caption>c</caption><colgroup><col span="2" width="5"></colgroup><thead><tr><th scope="col" abbr="a" axis="b" colspan="1" rowspan="1" width="9">h</th></tr></thead><tbody><tr><td abbr="a" axis="b" colspan="2" rowspan="1" width="3">d</td></tr></tbody><tfoot><tr><td>f</td></tr></tfoot></table>'],
  ['bảng thiếu tbody: trình phân tích thêm tbody, bỏ border', '<table summary="s" width="100" border="1"><tr><td>1</td></tr></table>', '<table summary="s" width="100"><tbody><tr><td>1</td></tr></tbody></table>'],
  ['td đứng ngoài bảng chỉ còn chữ', '<td colspan="2">cell</td>', 'cell'],
  ['tr, td đứng ngoài bảng chỉ còn chữ', '<tr><td>x</td></tr>', 'x'],
];

const TEXT: Case[] = [
  ['thực thể giữ nguyên nghĩa', '<p>&amp; &lt;script&gt; &copy; &#x27; "q" \'s\'</p>', '<p>&amp; &lt;script&gt; © \' "q" \'s\'</p>'],
  ['< và > rời trong văn bản được thoát', 'a < b > c', 'a &lt; b &gt; c'],
  ['< và & trong câu tiếng Việt', '<p>Giá 5 < 6 & ok</p>', '<p>Giá 5 &lt; 6 &amp; ok</p>'],
  ['nbsp được ghi lại là &nbsp;', '<p>&nbsp;</p>', '<p>&nbsp;</p>'],
  ['tên thẻ viết hoa được chữ thường hóa', '<P>Up<STRONG>per</STRONG></P>', '<p>Up<strong>per</strong></p>'],
  ['thẻ chưa đóng: p', '<p>a<p>b', '<p>a</p><p>b</p>'],
  ['thẻ chưa đóng: li', '<ul><li>a<li>b</ul>', '<ul><li>a</li><li>b</li></ul>'],
  ['thẻ lồng sai thứ tự', '<b><i>x</b></i>', '<b><i>x</i></b>'],
  ['br viết kiểu XML', '<br/><br />', '<br><br>'],
  ['xuống dòng giữa các khối được giữ', '<p>a</p>\n\n<p>b</p>', '<p>a</p>\n\n<p>b</p>'],
  ['xuống dòng trong đoạn văn', '<p>line1\nline2</p>', '<p>line1\nline2</p>'],
  ['chữ trần không có thẻ', 'plain text only', 'plain text only'],
  ['tiếng Việt có dấu', '<p>Đường đi</p>', '<p>Đường đi</p>'],
  ['emoji', '<p>emoji 😀 text</p>', '<p>emoji 😀 text</p>'],
];

const HAS_TEXT: [name: string, input: string, expected: boolean][] = [
  ['chuỗi rỗng', '', false],
  ['chỉ khoảng trắng', '   ', false],
  ['đoạn văn trống', '<p>  </p>', false],
  ['chỉ có br', '<br>', false],
  ['chỉ có nbsp', '&nbsp;', false],
  ['nbsp trong đoạn văn', '<p>&nbsp;</p>', false],
  ['chỉ có script và đoạn trống', '<script>x</script><p>  </p>', false],
  ['chỉ có svg onload', '<svg onload=alert(1)><circle/></svg>', false],
  ['chỉ có object, param, embed', '<object><param name="x" value="y"><embed src="https://e.com"></object>', false],
  ['link rỗng', '<a href="https://e.com"></a>', false],
  ['có chữ', '<p>Xin chào</p>', true],
  ['chữ trần', 'plain text only', true],
  ['chỉ có ảnh https', '<img src="https://e.com/a.png">', true],
  ['ảnh http mất src nhưng thẻ img vẫn tính là có nội dung (như jsoup)', '<img src="http://e.com/a.png">', true],
  ['img không src vẫn tính là có nội dung', '<img>', true],
];

const run = (cases: Case[]) => it.each(cases)('%s', (_name, input, output) => expect(cleanArticleHtml(input)).toBe(output));

describe('cleanArticleHtml: bỏ thẻ và thuộc tính nguy hiểm', () => run(DANGER));
describe('cleanArticleHtml: thẻ ngoài danh sách bị bỏ thẻ, giữ chữ bên trong', () => run(UNWRAP));
describe('cleanArticleHtml: a[href], img[src], cite chỉ nhận https (BE bỏ http, ftp, mailto)', () => run(LINKS));
describe('cleanArticleHtml: thẻ và thuộc tính của Safelist.relaxed()', () => {
  run(RELAXED);

  it.each([
    'a', 'b', 'blockquote', 'cite', 'code', 'dd', 'div', 'dl', 'dt', 'em', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'i', 'li', 'ol',
    'p', 'pre', 'q', 'small', 'span', 'strike', 'strong', 'sub', 'sup', 'u', 'ul',
  ])('thẻ <%s> được giữ nguyên', (tag) => {
    expect(cleanArticleHtml(`<${tag}>x</${tag}>`)).toBe(`<${tag}>x</${tag}>`);
  });

  it.each(['font', 'section', 'article', 'center', 'marquee', 'blink', 'button', 'details', 'figure', 'nav'])(
    'thẻ <%s> bị bỏ thẻ, giữ chữ',
    (tag) => expect(cleanArticleHtml(`<${tag}>x</${tag}>`)).toBe('x'),
  );
});
describe('cleanArticleHtml: ký tự, thực thể, cấu trúc', () => run(TEXT));

describe('cleanArticleHtml: tính chất chung', () => {
  const ALL = [DANGER, UNWRAP, LINKS, RELAXED, TEXT].flat();

  it('lọc lần hai không đổi gì (kết quả đã sạch)', () => {
    for (const [name, , output] of ALL) expect(cleanArticleHtml(output), name).toBe(output);
  });

  it('không để lọt script, iframe, style, thuộc tính sự kiện hay javascript: trong mọi kết quả', () => {
    for (const [name, , output] of ALL) expect(output, name).not.toMatch(/<script|<iframe|<style|\son\w+\s*=|javascript:/i);
  });

  it('các payload XSS kinh điển không còn thẻ hay thuộc tính chạy được', () => {
    const payloads = [
      '<img src=x onerror=alert(1)>',
      '<svg/onload=alert(1)>',
      '<body onload=alert(1)>',
      '<a href="jAvAsCrIpT:alert(1)">x</a>',
      '<a href="  javascript:alert(1)">x</a>',
      '<a href="\tjava\tscript:alert(1)">x</a>',
      '<iframe srcdoc="<script>alert(1)</script>"></iframe>',
      '<img src="https://e.com/a.png" onload="alert(1)" onerror="alert(2)">',
      '<math><mtext><table><mglyph><style><img src=x onerror=alert(1)>',
      '<form action="javascript:alert(1)"><button>x</button></form>',
      '<p style="background:url(javascript:alert(1))">x</p>',
      '<object data="javascript:alert(1)"></object>',
      '<embed src="javascript:alert(1)">',
      '<link rel="stylesheet" href="javascript:alert(1)">',
      '<meta http-equiv="refresh" content="0;url=javascript:alert(1)">',
    ];
    for (const payload of payloads) {
      const out = cleanArticleHtml(payload);
      expect(out, payload).not.toMatch(/<script|<iframe|<style|<svg|<object|<embed|<form|<link|<meta|\son\w+\s*=|javascript:/i);
    }
  });
});

describe('hasArticleText (BE ArticleHtml.hasText: còn chữ hoặc ảnh sau khi lọc)', () => {
  it.each(HAS_TEXT)('%s', (_name, input, expected) => expect(hasArticleText(cleanArticleHtml(input))).toBe(expected));
});
