import { Link } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';

type Section = { id: string; heading: string; body: string[] };

const UPDATED = '16/09/2026';

function LegalShell({
  eyebrow,
  title,
  lead,
  sections,
  sibling,
}: {
  eyebrow: string;
  title: string;
  lead: string;
  sections: Section[];
  sibling: { to: string; label: string };
}) {
  return (
    <div className="bg-surface-1 pb-24">
      <header className="border-b border-[#e6d9c8] bg-surface-2">
        <div className="mx-auto max-w-[1100px] px-6 py-14 sm:py-16">
          <Link
            to="/"
            className="inline-flex items-center gap-2 text-[13px] font-bold text-[#7a6a5d] transition-colors hover:text-accent"
          >
            <ArrowLeft size={15} strokeWidth={2.2} />
            Về trang chủ
          </Link>
          <span className="mt-6 flex w-fit items-center rounded-full bg-accent-soft px-3.5 py-[7px] text-[11px] font-extrabold uppercase tracking-[0.16em] text-accent">
            {eyebrow}
          </span>
          <h1 className="font-friendly font-extrabold mt-4 text-[40px] leading-[1.05] text-[#191919] sm:text-[54px]">{title}</h1>
          <p className="mt-4 max-w-[62ch] text-[15.5px] leading-[1.7] text-[#7a6a5d]">{lead}</p>
          <p className="mt-5 text-[13px] font-semibold text-[#a3968a]">Cập nhật lần cuối: {UPDATED}</p>
        </div>
      </header>

      <div className="mx-auto grid max-w-[1100px] gap-12 px-6 pt-14 lg:grid-cols-[220px_1fr]">
        {/* Muc luc — dinh theo scroll tren man hinh rong */}
        <nav className="hidden lg:block lg:sticky lg:top-[170px] lg:self-start">
          <p className="mb-3 text-[12px] font-bold uppercase tracking-[0.09em] text-[#a3968a]">Nội dung</p>
          <ul className="space-y-2.5">
            {sections.map((s, i) => (
              <li key={s.id}>
                <a
                  href={`#${s.id}`}
                  className="text-[13.5px] leading-[1.45] text-[#7a6a5d] transition-colors hover:text-accent"
                >
                  {i + 1}. {s.heading}
                </a>
              </li>
            ))}
          </ul>
        </nav>

        <article className="max-w-[68ch] space-y-10">
          {sections.map((s, i) => (
            <section key={s.id} id={s.id} className="scroll-mt-[170px]">
              <h2 className="font-friendly text-[22px] font-extrabold leading-[1.25] text-[#191919]">
                {i + 1}. {s.heading}
              </h2>
              {s.body.map((p) => (
                <p key={p} className="mt-3 text-[15.5px] leading-[1.75] text-[#5c4f45]">
                  {p}
                </p>
              ))}
            </section>
          ))}

          <div className="rounded-2xl border-[1.5px] border-[#e6d9c8] bg-white p-6">
            <p className="text-[15px] leading-[1.7] text-[#5c4f45]">
              Có câu hỏi về nội dung này? Gửi thư tới{' '}
              <a href="mailto:hotro@petcare.vn" className="font-bold text-accent hover:underline">
                hotro@petcare.vn
              </a>{' '}
              hoặc gọi 1900 6868 (8:00 – 20:00 mỗi ngày).
            </p>
            <Link
              to={sibling.to}
              className="mt-4 inline-flex rounded-full border-[1.5px] border-[#e6d9c8] px-5 py-2.5 text-[14px] font-bold text-[#191919] transition-colors hover:border-accent hover:text-accent"
            >
              {sibling.label}
            </Link>
          </div>
        </article>
      </div>
    </div>
  );
}

const TERMS: Section[] = [
  {
    id: 'chap-nhan',
    heading: 'Chấp nhận điều khoản',
    body: [
      'Khi tạo tài khoản hoặc sử dụng bất kỳ dịch vụ nào của PetCare Vietnam (website, ứng dụng, tổng đài đặt phòng), bạn xác nhận đã đọc và đồng ý với toàn bộ điều khoản dưới đây.',
      'Nếu bạn không đồng ý với một phần bất kỳ, vui lòng ngừng sử dụng dịch vụ. Chúng tôi có thể cập nhật điều khoản và sẽ thông báo qua email trước ít nhất 7 ngày với những thay đổi quan trọng.',
    ],
  },
  {
    id: 'tai-khoan',
    heading: 'Tài khoản của bạn',
    body: [
      'Mỗi tài khoản gắn với một địa chỉ email duy nhất và phải dùng thông tin thật. Bạn chịu trách nhiệm bảo mật mật khẩu và mọi hoạt động phát sinh từ tài khoản của mình.',
      'Mật khẩu tối thiểu 8 ký tự. Sau 5 lần đăng nhập sai liên tiếp, tài khoản sẽ bị khoá tạm 15 phút; bạn có thể đặt lại mật khẩu để mở khoá ngay.',
      'Chúng tôi có quyền tạm khoá tài khoản có dấu hiệu gian lận, đặt phòng ảo hoặc gây ảnh hưởng tới vật nuôi khác.',
    ],
  },
  {
    id: 'dat-phong',
    heading: 'Đặt phòng và thanh toán',
    body: [
      'Đơn đặt phòng chỉ có hiệu lực sau khi bạn nhận được email xác nhận kèm mã đặt phòng. Giá hiển thị đã bao gồm thuế GTGT và chưa bao gồm dịch vụ cộng thêm (spa, huấn luyện, đưa đón).',
      'Bạn có thể thanh toán trực tuyến hoặc tại quầy khi nhận phòng. Với kỳ nghỉ dưỡng từ 7 đêm trở lên, chúng tôi thu trước 30% giá trị đơn để giữ phòng.',
    ],
  },
  {
    id: 'huy-doi',
    heading: 'Huỷ và đổi lịch',
    body: [
      'Huỷ trước 48 giờ so với giờ nhận phòng: hoàn 100%. Huỷ trong vòng 24–48 giờ: hoàn 50%. Huỷ trong 24 giờ cuối hoặc không đến: không hoàn tiền.',
      'Đổi lịch miễn phí một lần nếu báo trước 24 giờ và còn phòng trống trong hạng bạn đã đặt.',
    ],
  },
  {
    id: 'trach-nhiem-thu-cung',
    heading: 'Điều kiện nhận thú cưng',
    body: [
      'Thú cưng phải đủ 4 tháng tuổi, đã tiêm phòng dại và các mũi cơ bản trong vòng 12 tháng, và có sổ tiêm mang theo khi nhận phòng.',
      'Chúng tôi từ chối nhận thú cưng đang mắc bệnh truyền nhiễm, có tiền sử tấn công người hoặc vật nuôi khác. Nếu phát hiện sau khi nhận, bạn cần đón bé về trong vòng 4 giờ.',
      'Trường hợp khẩn cấp về sức khoẻ, chúng tôi sẽ liên hệ bạn ngay; nếu không liên lạc được trong 30 phút, đội ngũ bác sĩ thú y sẽ xử lý theo phác đồ cấp cứu và chi phí phát sinh do bạn chi trả.',
    ],
  },
  {
    id: 'gioi-han',
    heading: 'Giới hạn trách nhiệm',
    body: [
      'PetCare Vietnam chịu trách nhiệm với thiệt hại trực tiếp do lỗi của nhân viên trong quá trình chăm sóc, tối đa bằng giá trị đơn đặt phòng liên quan.',
      'Chúng tôi không chịu trách nhiệm với tình trạng bệnh lý có sẵn, tai nạn do đặc điểm hành vi mà bạn không khai báo, hoặc sự cố bất khả kháng (thiên tai, dịch bệnh, mất điện diện rộng).',
    ],
  },
  {
    id: 'lien-he-terms',
    heading: 'Luật áp dụng và liên hệ',
    body: [
      'Điều khoản này được điều chỉnh bởi pháp luật Việt Nam. Tranh chấp sẽ được ưu tiên giải quyết qua thương lượng trước khi đưa ra Toà án có thẩm quyền tại TP. Hồ Chí Minh.',
    ],
  },
];

const PRIVACY: Section[] = [
  {
    id: 'du-lieu-thu-thap',
    heading: 'Dữ liệu chúng tôi thu thập',
    body: [
      'Thông tin bạn cung cấp: họ tên, email, số điện thoại, địa chỉ nhận hàng, hồ sơ thú cưng (giống, cân nặng, lịch tiêm, thói quen ăn, ghi chú sức khoẻ).',
      'Thông tin phát sinh khi sử dụng: lịch sử đặt phòng, đơn hàng, nội dung đánh giá, nhật ký ảnh trong kỳ nghỉ dưỡng của bé.',
      'Thông tin kỹ thuật: địa chỉ IP, loại trình duyệt, thời điểm truy cập — dùng để phát hiện đăng nhập bất thường.',
    ],
  },
  {
    id: 'muc-dich',
    heading: 'Mục đích sử dụng',
    body: [
      'Xử lý đặt phòng, đơn hàng và thanh toán; gửi email xác nhận, mã OTP xác thực và nhắc lịch nhận/trả phòng.',
      'Chăm sóc thú cưng đúng nhu cầu: khẩu phần, thuốc, lịch vận động dựa trên hồ sơ bạn khai báo.',
      'Cải thiện dịch vụ và gợi ý sản phẩm phù hợp. Bạn có thể tắt email tiếp thị bất cứ lúc nào trong phần Tài khoản.',
    ],
  },
  {
    id: 'chia-se',
    heading: 'Chia sẻ với bên thứ ba',
    body: [
      'Chúng tôi không bán dữ liệu cá nhân. Dữ liệu chỉ được chia sẻ với: đối tác vận chuyển (tên, số điện thoại, địa chỉ giao hàng), cổng thanh toán (thông tin giao dịch), và phòng khám thú y đối tác khi cần cấp cứu.',
      'Trong mọi trường hợp, bên nhận chỉ được dùng dữ liệu cho đúng mục đích nêu trên và phải cam kết bảo mật bằng hợp đồng.',
    ],
  },
  {
    id: 'bao-mat',
    heading: 'Cách chúng tôi bảo vệ dữ liệu',
    body: [
      'Mật khẩu được băm bằng thuật toán BCrypt — kể cả quản trị viên cũng không đọc được mật khẩu gốc của bạn.',
      'Kết nối tới hệ thống dùng HTTPS. Phiên đăng nhập dựa trên token có thời hạn và bị thu hồi ngay khi bạn đăng xuất.',
      'Quyền truy cập dữ liệu khách hàng được phân theo vai trò: lễ tân, bác sĩ thú y và nhân viên spa chỉ thấy đúng phần thông tin cần cho công việc.',
    ],
  },
  {
    id: 'luu-tru',
    heading: 'Thời gian lưu trữ',
    body: [
      'Hồ sơ tài khoản và thú cưng được lưu trong suốt thời gian bạn sử dụng dịch vụ. Sau khi bạn yêu cầu xoá tài khoản, dữ liệu cá nhân sẽ được xoá trong 30 ngày.',
      'Riêng hoá đơn và chứng từ thanh toán được lưu 10 năm theo quy định kế toán, ở dạng tách rời khỏi hồ sơ định danh khi có thể.',
    ],
  },
  {
    id: 'quyen-cua-ban',
    heading: 'Quyền của bạn',
    body: [
      'Bạn có quyền xem, sửa, tải về hoặc yêu cầu xoá dữ liệu cá nhân của mình; rút lại sự đồng ý nhận email tiếp thị; và khiếu nại nếu cho rằng dữ liệu bị xử lý sai.',
      'Gửi yêu cầu tới hotro@petcare.vn — chúng tôi phản hồi trong vòng 7 ngày làm việc.',
    ],
  },
  {
    id: 'cookie',
    heading: 'Cookie',
    body: [
      'Chúng tôi dùng cookie kỹ thuật để giữ phiên đăng nhập và giỏ hàng. Cookie phân tích chỉ được bật khi bạn đồng ý và có thể tắt trong cài đặt trình duyệt mà không ảnh hưởng tới việc đặt phòng.',
    ],
  },
];

export function TermsPage() {
  return (
    <LegalShell
      eyebrow="Pháp lý"
      title="Điều khoản sử dụng"
      lead="Những cam kết hai chiều giữa bạn và PetCare Vietnam khi đặt phòng nghỉ dưỡng, mua sắm hoặc sử dụng các dịch vụ chăm sóc thú cưng của chúng tôi."
      sections={TERMS}
      sibling={{ to: '/privacy', label: 'Đọc Chính sách bảo mật' }}
    />
  );
}

export function PrivacyPage() {
  return (
    <LegalShell
      eyebrow="Pháp lý"
      title="Chính sách bảo mật"
      lead="Chúng tôi thu thập những gì, dùng để làm gì, giữ trong bao lâu và bạn có quyền gì với dữ liệu của mình — viết đủ rõ để bạn không phải đoán."
      sections={PRIVACY}
      sibling={{ to: '/terms', label: 'Đọc Điều khoản sử dụng' }}
    />
  );
}
