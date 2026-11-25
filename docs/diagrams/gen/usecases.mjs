// Danh mục use case rút gọn từ docs/01-business-operations.md (85 UC hiệu lực; UC18–20, 41, 43, 50, 51, 85, 86 đã bỏ).
// Cột `a` = actor theo docs/01; "A02-A08" là dải. S02/S03 là hệ thống ngoài.
export const ACTORS = [
  { id: 'A01', code: 'A01', name: 'Khách vãng lai', sub: 'GUEST' },
  { id: 'A02', code: 'A02', name: 'Khách hàng', sub: 'CUSTOMER' },
  { id: 'USER', code: '', name: 'Người dùng đăng nhập', sub: 'A02–A08 (trừu tượng)', abstract: true },
  { id: 'STAFF', code: '', name: 'Nhân viên chi nhánh', sub: 'A05–A08 (trừu tượng)', abstract: true },
  { id: 'A03', code: 'A03', name: 'Quản trị hệ thống', sub: 'ADMIN' },
  { id: 'A04', code: 'A04', name: 'Quản lý chuỗi', sub: 'SUPER_MANAGER' },
  { id: 'A05', code: 'A05', name: 'Quản lý chi nhánh', sub: 'BRANCH_MANAGER' },
  { id: 'A06', code: 'A06', name: 'Lễ tân kiêm thu ngân', sub: 'RECEPTIONIST' },
  { id: 'A07', code: 'A07', name: 'Bác sĩ thú y', sub: 'VET' },
  { id: 'A08', code: 'A08', name: 'Nhân viên chăm sóc', sub: 'CARETAKER' },
  { id: 'S02', code: 'S02', name: 'Cổng thanh toán', sub: 'Payment Gateway', ext: true },
  { id: 'S03', code: 'S03', name: 'Dịch vụ email / push', sub: 'Email · Push', ext: true },
];

export const MODULES_UC = [
  ['TK', 'Tài khoản & xác thực'], ['QT', 'Quản trị hệ thống'], ['CN', 'Chi nhánh'], ['CK', 'Thông tin công khai'],
  ['KH', 'Khách hàng & thú cưng'], ['SP', 'Sản phẩm & dịch vụ'], ['NS', 'Nhân sự'], ['LH', 'Lịch hẹn'],
  ['TN', 'Tiếp nhận & hàng đợi'], ['KB', 'Khám & điều trị'], ['LT', 'Lưu trú & nội trú'], ['BH', 'Bán hàng & đơn hàng'],
  ['TG', 'Thu ngân'], ['KO', 'Kho'], ['BV', 'Bài viết'], ['DG', 'Feedback'],
  ['TB', 'Chăm sóc khách & thông báo'], ['BC', 'Báo cáo'], ['AI', 'Trí tuệ nhân tạo'],
].map(([id, title]) => ({ id, title }));

const R = (m, rows) => rows.map(([c, n, a]) => ({ c, m, n, a }));
export const USE_CASES = [
  ...R('TK', [['UC01', 'Đăng ký tài khoản (email + OTP, hoặc Google)', 'A01'], ['UC02', 'Xác thực OTP', 'A01-A08,S03'], ['UC03', 'Đăng nhập / đăng xuất', 'A02-A08'], ['UC04', 'Quên mật khẩu', 'A02-A08'], ['UC05', 'Đổi mật khẩu', 'A02-A08'], ['UC06', 'Quản lý hồ sơ cá nhân', 'A02-A08'], ['UC07', 'Liên kết tài khoản với hồ sơ khách có sẵn', 'A02']]),
  ...R('QT', [['UC08', 'Quản lý tài khoản nhân viên', 'A03,A04,A05'], ['UC09', 'Khóa / mở khóa người dùng', 'A03'], ['UC10', 'Cấu hình tham số & mẫu thông báo', 'A03'], ['UC11', 'Xem nhật ký audit', 'A03']]),
  ...R('CN', [['UC12', 'Quản lý chi nhánh', 'A04'], ['UC13', 'Tạm ngừng / đóng cửa chi nhánh', 'A04'], ['UC14', 'Cấu hình giờ mở cửa & ngày nghỉ', 'A05']]),
  ...R('CK', [['UC15', 'Xem dịch vụ, chi nhánh & đội ngũ bác sĩ', 'A01,A02'], ['UC16', 'Xem & tìm kiếm sản phẩm', 'A01,A02'], ['UC17', 'Xem bài viết', 'A01,A02'], ['UC21', 'Quản lý nội dung trang', 'A04']]),
  ...R('KH', [['UC22', 'Quản lý hồ sơ khách tại quầy', 'A06'], ['UC23', 'Tra cứu khách & thú cưng', 'A06,A07,A08'], ['UC24', 'Quản lý thú cưng', 'A02,A06'], ['UC25', 'Xem hồ sơ sức khỏe thú cưng', 'A02'], ['UC26', 'Chuyển chủ thú cưng', 'A06'], ['UC27', 'Gộp hồ sơ khách trùng', 'A05']]),
  ...R('SP', [['UC28', 'Quản lý danh mục sản phẩm', 'A04'], ['UC29', 'Quản lý sản phẩm', 'A04'], ['UC30', 'Quản lý dịch vụ', 'A04'], ['UC31', 'Quản lý loại vaccine & phác đồ tiêm chủng', 'A04'], ['UC32', 'Cấu hình phí vận chuyển', 'A04'], ['UC33', 'Bật / tắt dịch vụ tại chi nhánh', 'A05']]),
  ...R('NS', [['UC34', 'Quản lý ca làm việc', 'A05'], ['UC35', 'Xem lịch làm việc & đăng ký ca theo tuần', 'A06-A08'], ['UC36', 'Gửi / rút yêu cầu nghỉ, đổi ca', 'A06-A08'], ['UC37', 'Duyệt yêu cầu nghỉ, đổi ca', 'A05'], ['UC38', 'Ghi nhận vắng đột xuất', 'A05']]),
  ...R('LH', [['UC39', 'Đặt lịch hẹn', 'A02,A06'], ['UC40', 'Quản lý lịch hẹn', 'A02,A06'], ['UC42', 'Thiết lập quota lịch hẹn', 'A05']]),
  ...R('TN', [['UC44', 'Tiếp nhận khách', 'A06'], ['UC45', 'Điều phối hàng đợi', 'A06,A05'], ['UC46', 'Xem hàng đợi của tôi & gọi lượt', 'A07,A08'], ['UC47', 'Xác định ca cấp cứu', 'A07,A06']]),
  ...R('KB', [['UC48', 'Khám bệnh', 'A07'], ['UC49', 'Tiêm chủng', 'A07'], ['UC52', 'Thực hiện dịch vụ thẩm mỹ', 'A08'], ['UC53', 'Xem & bổ sung bệnh án', 'A07,A08']]),
  ...R('LT', [['UC54', 'Quản lý chuồng', 'A05'], ['UC55', 'Nhập / xuất viện', 'A07'], ['UC56', 'Điều trị nội trú', 'A07,A08,A06'], ['UC57', 'Ghi nhật ký chăm sóc', 'A07,A08'], ['UC58', 'Đặt chỗ lưu trú theo loại chuồng', 'A02,A06'], ['UC59', 'Nhận / trả thú cưng lưu trú', 'A06,A08'], ['UC60', 'Xem lưu trú & nhật ký chăm sóc', 'A02']]),
  ...R('BH', [['UC61', 'Quản lý giỏ hàng', 'A02'], ['UC62', 'Đặt hàng online', 'A02'], ['UC63', 'Thanh toán online', 'A02,S02'], ['UC64', 'Quản lý đơn hàng của tôi', 'A02'], ['UC65', 'Xử lý & giao đơn online', 'A06'], ['UC66', 'Bán hàng & chốt Order tại quầy', 'A05,A06'], ['UC67', 'Thêm dịch vụ / hàng vào Order', 'A06,A07,A08'], ['UC68', 'Tạo yêu cầu trả hàng', 'A06'], ['UC69', 'Duyệt yêu cầu trả hàng', 'A05']]),
  ...R('TG', [['UC70', 'Thu tiền tại quầy', 'A06'], ['UC71', 'Mở / chốt ca thu ngân', 'A06'], ['UC72', 'Đối soát ca thu ngân', 'A05']]),
  ...R('KO', [['UC73', 'Quản lý nhà cung cấp', 'A05'], ['UC74', 'Nhập kho', 'A05'], ['UC75', 'Xem tồn kho & thiết lập tồn tối thiểu', 'A05,A06'], ['UC76', 'Điều chỉnh tồn kho', 'A05'], ['UC77', 'Chuyển kho', 'A05'], ['UC78', 'Kiểm kê kho', 'A05'], ['UC79', 'Truy vết lô hàng', 'A05,A07']]),
  ...R('BV', [['UC80', 'Soạn & gửi duyệt bài viết', 'A07'], ['UC81', 'Quản lý bài viết, chuyên mục & bình luận', 'A04'], ['UC82', 'Bình luận bài viết', 'A02']]),
  ...R('DG', [['UC83', 'Gửi feedback', 'A02'], ['UC84', 'Xem feedback', 'A04,A05']]),
  ...R('TB', [['UC87', 'Thực hiện Care Task', 'A06'], ['UC88', 'Xem thông báo & cài đặt nhận thông báo', 'A02-A08,S03']]),
  ...R('BC', [['UC89', 'Xem báo cáo', 'A04,A05']]),
  ...R('AI', [['UC90', 'Hỏi đáp chatbot tư vấn chăm sóc', 'A01,A02'], ['UC91', 'Hỏi chatbot về lịch của tôi', 'A02,A01'], ['UC92', 'Xem gợi ý cá nhân hóa cho thú', 'A02'], ['UC93', 'Quản lý tri thức chatbot', 'A04'], ['UC94', 'Khai báo hồ sơ gợi ý dịch vụ / sản phẩm', 'A04']]),
];

// Liên kết mức phân hệ: actor tham gia ít nhất một UC của phân hệ.
// Dải A02–A08 trong một UC → USER; A02 kế thừa A01 nên bỏ A02 khi UC đã có A01;
// cả bốn A05–A08 cùng tham gia một phân hệ → STAFF (docs/02-phan-tich: "nhân viên A05–A08 thuộc đúng một chi nhánh").
export function ucLinks() {
  const per = new Map();
  for (const u of USE_CASES) {
    const ids = new Set();
    for (const tok of u.a.split(',')) {
      const r = /^A(\d\d)-A(\d\d)$/.exec(tok);
      if (r) for (let i = +r[1]; i <= +r[2]; i++) ids.add('A' + String(i).padStart(2, '0')); else ids.add(tok);
    }
    const all = ['A02', 'A03', 'A04', 'A05', 'A06', 'A07', 'A08'];
    if (all.every(a => ids.has(a))) { all.forEach(a => ids.delete(a)); ids.add('USER'); }
    if (ids.has('A01')) ids.delete('A02');
    if (!per.has(u.m)) per.set(u.m, new Set());
    for (const a of ids) per.get(u.m).add(a);
  }
  const out = [];
  for (const [m, s] of per) {
    const staff = ['A05', 'A06', 'A07', 'A08'];
    if (staff.every(a => s.has(a))) { staff.forEach(a => s.delete(a)); s.add('STAFF'); }
    for (const a of s) out.push([m, a]);
  }
  return out;
}

// Bố trí: 19 phân hệ vào lưới (thử 4×5 và 5×4), tác nhân hai bên khung, USER và S03 phía trên.
// Simulated annealing trên (vị trí phân hệ, thứ tự tác nhân): ít đường cắt nhau, ít đường xuyên qua phân hệ khác, đường ngắn.
// Cố định seed nên kết quả lặp lại được.
const GRIDS = [{ cols: 4, rows: 5 }, { cols: 5, rows: 4 }];
const BASE = { x0: 500, y0: 260, dx: 236, dy: 126, rx: 104, ry: 44 };
export function layoutUC(links) {
  const mods = MODULES_UC.map(m => m.id);
  const RIGHT = ['A03', 'A04', 'A05', 'A06', 'A07', 'A08'], LEFT = ['A01', 'A02'];
  const ccw = (a, b, c) => (c[1] - a[1]) * (b[0] - a[0]) - (b[1] - a[1]) * (c[0] - a[0]);
  const cross = (p1, p2, p3, p4) => ccw(p1, p3, p4) * ccw(p2, p3, p4) < 0 && ccw(p1, p2, p3) * ccw(p1, p2, p4) < 0;
  let best = null;
  for (const gr of GRIDS) {
    const G = { ...BASE, ...gr };
    const bL = G.x0 - G.rx - 34, bR = G.x0 + (gr.cols - 1) * G.dx + G.rx + 34, bT = G.y0 - G.ry - 56, bB = G.y0 + (gr.rows - 1) * G.dy + G.ry + 36, mid = (bL + bR) / 2;
    const slots = []; for (let r = 0; r < gr.rows; r++) for (let c = 0; c < gr.cols; c++) slots.push([G.x0 + c * G.dx, G.y0 + r * G.dy]);
    const rx0 = bR + 170, lx0 = bL - 170, ys = i => bT + 40 + i * 120;
    const fixed = { S02: [lx0, bB - 10], S03: [mid - 210, 118], USER: [mid + 210, 72] };
    const E0 = links.map(([m, a]) => ({ mi: mods.indexOf(m), id: a }));
    const actPos = st => {
      const p = { ...fixed };
      st.right.forEach((a, i) => p[RIGHT[st.right.indexOf(a)] && a] = [rx0, ys(i)]);
      st.left.forEach((a, i) => p[a] = [lx0, bT + 150 + i * 280]);
      const ay = ['A05', 'A06', 'A07', 'A08'].map(a => p[a][1]); p.STAFF = [rx0 + 280, ay.reduce((s, v) => s + v, 0) / 4];
      return p;
    };
    const cost = st => {
      const P = st.slot.map(i => slots[i]), A = actPos(st); let c = 0;
      const E = E0.map(e => ({ mi: e.mi, a: A[e.id], id: e.id }));
      for (const e of E) c += Math.hypot(P[e.mi][0] - e.a[0], P[e.mi][1] - e.a[1]) * 0.02;
      for (let i = 0; i < E.length; i++) for (let j = i + 1; j < E.length; j++) { const a = E[i], b = E[j]; if (a.mi === b.mi || a.id === b.id) continue; if (cross(a.a, P[a.mi], b.a, P[b.mi])) c += 30; }
      for (const e of E) { const p = P[e.mi], hit = new Set();
        for (let m = 0; m < mods.length; m++) { if (m === e.mi) continue; const q = P[m];
          for (let k = 1; k < 16; k++) { const t = k / 16, x = e.a[0] + (p[0] - e.a[0]) * t, y = e.a[1] + (p[1] - e.a[1]) * t;
            if (((x - q[0]) / (G.rx + 10)) ** 2 + ((y - q[1]) / (G.ry + 10)) ** 2 < 1) { hit.add(m); break; } } }
        c += hit.size * 150; }
      return c;
    };
    let seed = 11; const rnd = () => { seed |= 0; seed = seed + 0x6D2B79F5 | 0; let t = Math.imul(seed ^ seed >>> 15, 1 | seed); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };
    const clone = s => ({ slot: s.slot.slice(), right: s.right.slice(), left: s.left.slice() });
    let gbest = null;
    for (let rs = 0; rs < 3; rs++) {
      let st = { slot: slots.map((_, i) => i), right: RIGHT.slice(), left: LEFT.slice() };
      for (let i = st.slot.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [st.slot[i], st.slot[j]] = [st.slot[j], st.slot[i]]; }
      let cur = cost(st), bst = clone(st), bc = cur; const N = 30000;
      for (let it = 0; it < N; it++) {
        const T = 80 * (1 - it / N) + 0.5, q = clone(st), r = rnd();
        if (r < 0.8) { const i = Math.floor(rnd() * mods.length), j = Math.floor(rnd() * slots.length); if (i === j) continue; [q.slot[i], q.slot[j]] = [q.slot[j], q.slot[i]]; }
        else if (r < 0.97) { const i = Math.floor(rnd() * 6), j = Math.floor(rnd() * 6); [q.right[i], q.right[j]] = [q.right[j], q.right[i]]; }
        else q.left.reverse();
        const c = cost(q); if (c < cur || rnd() < Math.exp((cur - c) / T)) { st = q; cur = c; if (c < bc) { bc = c; bst = clone(q); } }
      }
      if (!gbest || bc < gbest.c) gbest = { c: bc, st: bst };
    }
    if (!best || gbest.c < best.c) {
      const mod = {}; mods.forEach((m, i) => mod[m] = slots[gbest.st.slot[i]]);
      const act = actPos(gbest.st);
      best = { c: gbest.c, grid: G, mod, act, bound: { x: bL, y: bT, w: bR - bL, h: bB - bT },
        tr: { staff: rx0 + 140, user: rx0 + 280 + 120, l1: lx0 - 84, l2: lx0 - 116 } };
    }
  }
  return { mod: best.mod, act: best.act, grid: best.grid, bound: best.bound, tr: best.tr, cost: Math.round(best.c) };
}
