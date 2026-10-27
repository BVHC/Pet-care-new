// Cấu trúc nội dung 4 trang tĩnh (UC21) và giá trị mặc định. Q1 của content-v1 là ĐỀ XUẤT của FE, chờ BE-2 / PO chốt.
// BE nhận mọi JSON object ≤ 64 KB và seed `{}`, nên FE tự `safeParse` khi đọc: `{}` hoặc sai dạng thì dùng PAGE_DEFAULTS
// (= đúng chữ các trang hiển thị trước UC21). Chỉ văn bản thuần, KHÔNG HTML (ADR-0071): trang render bằng JSX nên thẻ gõ vào
// chỉ hiện thành chữ. Xuống dòng trong chuỗi = xuống dòng khi hiển thị (tiêu đề, nhãn) hoặc sang đoạn mới (nội dung điều khoản).
// `PageContent.title` không dùng: mọi chữ hiển thị nằm trong `content`, mỗi khóa có nhiều tiêu đề nên một `title` không ánh xạ 1-1.
import { z } from 'zod';
import type { PageKey } from '../types/page-content';

/** Thứ tự BE trả ở `GET /page-contents`. */
export const PAGE_KEYS: readonly PageKey[] = ['HOME_BANNER', 'ABOUT', 'POLICY', 'FAQ'];

/** Nhãn tab quản trị (UC21: banner trang chủ, giới thiệu, chính sách, FAQ). */
export const PAGE_LABELS: Record<PageKey, string> = {
  HOME_BANNER: 'Banner trang chủ',
  ABOUT: 'Giới thiệu',
  POLICY: 'Chính sách',
  FAQ: 'Câu hỏi thường gặp',
};

/**
 * Giới hạn từng trường: theo bố cục (hero, dải số liệu, thẻ giá trị, accordion); BE chỉ giới hạn tổng 64 KB.
 * Đếm theo `.length` của chuỗi đã bỏ khoảng trắng hai đầu. `statCount` / `valueCount`: số ô cố định (mỗi ô có biểu tượng riêng theo vị trí).
 */
export const PAGE_LIMITS = {
  headline: 20,
  subheadline: 80,
  ctaLabel: 24,
  statValue: 10,
  statLabel: 40,
  statCount: 4,
  valueTitle: 30,
  valueDescription: 200,
  valueCount: 4,
  question: 150,
  answer: 500,
  faqItems: 20,
  docLead: 400,
  docUpdatedOn: 20,
  sectionHeading: 100,
  sectionBody: 3000,
  docSections: 20,
} as const;

/** Giới hạn của BE (PageContentService.MAX_CONTENT_BYTES). */
export const PAGE_CONTENT_MAX_BYTES = 64 * 1024;

/** Cách BE đếm: số byte UTF-8 của JSON gọn (tiếng Việt có dấu 2–3 byte/ký tự), không phải số ký tự; `title` không tính. */
export const pageContentBytes = (content: unknown): number => new TextEncoder().encode(JSON.stringify(content)).length;

const L = PAGE_LIMITS;
const text = (max: number) => z.string().trim().min(1).max(max);
const stat = z.object({ value: text(L.statValue), label: text(L.statLabel) });
const stats = z.array(stat).length(L.statCount);
const policyDoc = z.object({
  lead: text(L.docLead),
  updatedOn: text(L.docUpdatedOn),
  sections: z.array(z.object({ heading: text(L.sectionHeading), body: text(L.sectionBody) })).min(1).max(L.docSections),
});

export const PAGE_SCHEMAS = {
  /** Trang chủ: chữ của hero (HeroBanner) + dải số liệu "Sự tin tưởng của bạn…". */
  HOME_BANNER: z.object({
    headline: text(L.headline),
    subheadline: text(L.subheadline),
    ctaLabel: text(L.ctaLabel),
    heroStat: stat,
    stats,
  }),
  /** Trang giới thiệu: dải số liệu và 4 giá trị cốt lõi. */
  ABOUT: z.object({
    stats,
    values: z.array(z.object({ title: text(L.valueTitle), description: text(L.valueDescription) })).length(L.valueCount),
  }),
  /** Điều khoản sử dụng (/terms) và Chính sách bảo mật (/privacy). `sections[].body`: mỗi dòng một đoạn. */
  POLICY: z.object({ terms: policyDoc, privacy: policyDoc }),
  /** Câu hỏi thường gặp ở trang chủ. */
  FAQ: z.object({
    items: z.array(z.object({ question: text(L.question), answer: text(L.answer) })).min(1).max(L.faqItems),
  }),
} satisfies Record<PageKey, z.ZodTypeAny>;

export type PageContents = { [K in PageKey]: z.infer<(typeof PAGE_SCHEMAS)[K]> };
export type HomeBannerContent = PageContents['HOME_BANNER'];
export type AboutContent = PageContents['ABOUT'];
export type PolicyContent = PageContents['POLICY'];
export type FaqContent = PageContents['FAQ'];

export const PAGE_DEFAULTS: { [K in PageKey]: PageContents[K] } = {
  HOME_BANNER: {
    headline: 'Pet\nCare',
    subheadline: 'Tận tâm chăm sóc\nbé cưng của bạn',
    ctaLabel: 'Đặt Lịch Ngay',
    heroStat: { value: '2.500+', label: 'Chủ thú cưng\ntin tưởng' },
    stats: [
      { value: '10K+', label: 'Thú cưng được chăm sóc' },
      { value: '500+', label: 'Bác sĩ & chuyên gia' },
      { value: '24h', label: 'Giao hàng tận nơi' },
      { value: '4.9/5', label: 'Đánh giá hài lòng' },
    ],
  },
  ABOUT: {
    stats: [
      { value: '10K+', label: 'Khách hàng tin tưởng' },
      { value: '15+', label: 'Năm kinh nghiệm' },
      { value: '50+', label: 'Bác sĩ chuyên nghiệp' },
      { value: '4.9/5', label: 'Đánh giá trung bình' },
    ],
    values: [
      { title: 'Yêu thương', description: 'Mỗi thú cưng đều xứng đáng được yêu thương và chăm sóc tốt nhất.' },
      { title: 'An toàn', description: 'Tiêu chuẩn vệ sinh, an toàn và chất lượng dịch vụ hàng đầu trong ngành.' },
      { title: 'Chuyên nghiệp', description: 'Đội ngũ bác sĩ và nhân viên được đào tạo bài bản, giàu kinh nghiệm thực tiễn.' },
      { title: 'Chất lượng', description: 'Sản phẩm và dịch vụ luôn đạt tiêu chuẩn cao nhất, cập nhật theo quy chuẩn quốc tế.' },
    ],
  },
  POLICY: {
    terms: {
      lead: 'Những cam kết hai chiều giữa bạn và PetCare Vietnam khi đặt phòng nghỉ dưỡng, mua sắm hoặc sử dụng các dịch vụ chăm sóc thú cưng của chúng tôi.',
      updatedOn: '16/09/2026',
      sections: [
        {
          heading: 'Chấp nhận điều khoản',
          body: [
            'Khi tạo tài khoản hoặc sử dụng bất kỳ dịch vụ nào của PetCare Vietnam (website, ứng dụng, tổng đài đặt phòng), bạn xác nhận đã đọc và đồng ý với toàn bộ điều khoản dưới đây.',
            'Nếu bạn không đồng ý với một phần bất kỳ, vui lòng ngừng sử dụng dịch vụ. Chúng tôi có thể cập nhật điều khoản và sẽ thông báo qua email trước ít nhất 7 ngày với những thay đổi quan trọng.',
          ].join('\n'),
        },
        {
          heading: 'Tài khoản của bạn',
          body: [
            'Mỗi tài khoản gắn với một địa chỉ email duy nhất và phải dùng thông tin thật. Bạn chịu trách nhiệm bảo mật mật khẩu và mọi hoạt động phát sinh từ tài khoản của mình.',
            'Mật khẩu tối thiểu 8 ký tự. Sau 5 lần đăng nhập sai liên tiếp, tài khoản sẽ bị khoá tạm 15 phút; bạn có thể đặt lại mật khẩu để mở khoá ngay.',
            'Chúng tôi có quyền tạm khoá tài khoản có dấu hiệu gian lận, đặt phòng ảo hoặc gây ảnh hưởng tới vật nuôi khác.',
          ].join('\n'),
        },
        {
          heading: 'Đặt phòng và thanh toán',
          body: [
            'Đơn đặt phòng chỉ có hiệu lực sau khi bạn nhận được email xác nhận kèm mã đặt phòng. Giá hiển thị đã bao gồm thuế GTGT và chưa bao gồm dịch vụ cộng thêm (spa, huấn luyện, đưa đón).',
            'Bạn có thể thanh toán trực tuyến hoặc tại quầy khi nhận phòng. Với kỳ nghỉ dưỡng từ 7 đêm trở lên, chúng tôi thu trước 30% giá trị đơn để giữ phòng.',
          ].join('\n'),
        },
        {
          heading: 'Huỷ và đổi lịch',
          body: [
            'Huỷ trước 48 giờ so với giờ nhận phòng: hoàn 100%. Huỷ trong vòng 24–48 giờ: hoàn 50%. Huỷ trong 24 giờ cuối hoặc không đến: không hoàn tiền.',
            'Đổi lịch miễn phí một lần nếu báo trước 24 giờ và còn phòng trống trong hạng bạn đã đặt.',
          ].join('\n'),
        },
        {
          heading: 'Điều kiện nhận thú cưng',
          body: [
            'Thú cưng phải đủ 4 tháng tuổi, đã tiêm phòng dại và các mũi cơ bản trong vòng 12 tháng, và có sổ tiêm mang theo khi nhận phòng.',
            'Chúng tôi từ chối nhận thú cưng đang mắc bệnh truyền nhiễm, có tiền sử tấn công người hoặc vật nuôi khác. Nếu phát hiện sau khi nhận, bạn cần đón bé về trong vòng 4 giờ.',
            'Trường hợp khẩn cấp về sức khoẻ, chúng tôi sẽ liên hệ bạn ngay; nếu không liên lạc được trong 30 phút, đội ngũ bác sĩ thú y sẽ xử lý theo phác đồ cấp cứu và chi phí phát sinh do bạn chi trả.',
          ].join('\n'),
        },
        {
          heading: 'Giới hạn trách nhiệm',
          body: [
            'PetCare Vietnam chịu trách nhiệm với thiệt hại trực tiếp do lỗi của nhân viên trong quá trình chăm sóc, tối đa bằng giá trị đơn đặt phòng liên quan.',
            'Chúng tôi không chịu trách nhiệm với tình trạng bệnh lý có sẵn, tai nạn do đặc điểm hành vi mà bạn không khai báo, hoặc sự cố bất khả kháng (thiên tai, dịch bệnh, mất điện diện rộng).',
          ].join('\n'),
        },
        {
          heading: 'Luật áp dụng và liên hệ',
          body: 'Điều khoản này được điều chỉnh bởi pháp luật Việt Nam. Tranh chấp sẽ được ưu tiên giải quyết qua thương lượng trước khi đưa ra Toà án có thẩm quyền tại TP. Hồ Chí Minh.',
        },
      ],
    },
    privacy: {
      lead: 'Chúng tôi thu thập những gì, dùng để làm gì, giữ trong bao lâu và bạn có quyền gì với dữ liệu của mình — viết đủ rõ để bạn không phải đoán.',
      updatedOn: '16/09/2026',
      sections: [
        {
          heading: 'Dữ liệu chúng tôi thu thập',
          body: [
            'Thông tin bạn cung cấp: họ tên, email, số điện thoại, địa chỉ nhận hàng, hồ sơ thú cưng (giống, cân nặng, lịch tiêm, thói quen ăn, ghi chú sức khoẻ).',
            'Thông tin phát sinh khi sử dụng: lịch sử đặt phòng, đơn hàng, nội dung đánh giá, nhật ký ảnh trong kỳ nghỉ dưỡng của bé.',
            'Thông tin kỹ thuật: địa chỉ IP, loại trình duyệt, thời điểm truy cập — dùng để phát hiện đăng nhập bất thường.',
          ].join('\n'),
        },
        {
          heading: 'Mục đích sử dụng',
          body: [
            'Xử lý đặt phòng, đơn hàng và thanh toán; gửi email xác nhận, mã OTP xác thực và nhắc lịch nhận/trả phòng.',
            'Chăm sóc thú cưng đúng nhu cầu: khẩu phần, thuốc, lịch vận động dựa trên hồ sơ bạn khai báo.',
            'Cải thiện dịch vụ và gợi ý sản phẩm phù hợp. Bạn có thể tắt email tiếp thị bất cứ lúc nào trong phần Tài khoản.',
          ].join('\n'),
        },
        {
          heading: 'Chia sẻ với bên thứ ba',
          body: [
            'Chúng tôi không bán dữ liệu cá nhân. Dữ liệu chỉ được chia sẻ với: đối tác vận chuyển (tên, số điện thoại, địa chỉ giao hàng), cổng thanh toán (thông tin giao dịch), và phòng khám thú y đối tác khi cần cấp cứu.',
            'Trong mọi trường hợp, bên nhận chỉ được dùng dữ liệu cho đúng mục đích nêu trên và phải cam kết bảo mật bằng hợp đồng.',
          ].join('\n'),
        },
        {
          heading: 'Cách chúng tôi bảo vệ dữ liệu',
          body: [
            'Mật khẩu được băm bằng thuật toán BCrypt — kể cả quản trị viên cũng không đọc được mật khẩu gốc của bạn.',
            'Kết nối tới hệ thống dùng HTTPS. Phiên đăng nhập dựa trên token có thời hạn và bị thu hồi ngay khi bạn đăng xuất.',
            'Quyền truy cập dữ liệu khách hàng được phân theo vai trò: lễ tân, bác sĩ thú y và nhân viên spa chỉ thấy đúng phần thông tin cần cho công việc.',
          ].join('\n'),
        },
        {
          heading: 'Thời gian lưu trữ',
          body: [
            'Hồ sơ tài khoản và thú cưng được lưu trong suốt thời gian bạn sử dụng dịch vụ. Sau khi bạn yêu cầu xoá tài khoản, dữ liệu cá nhân sẽ được xoá trong 30 ngày.',
            'Riêng hoá đơn và chứng từ thanh toán được lưu 10 năm theo quy định kế toán, ở dạng tách rời khỏi hồ sơ định danh khi có thể.',
          ].join('\n'),
        },
        {
          heading: 'Quyền của bạn',
          body: [
            'Bạn có quyền xem, sửa, tải về hoặc yêu cầu xoá dữ liệu cá nhân của mình; rút lại sự đồng ý nhận email tiếp thị; và khiếu nại nếu cho rằng dữ liệu bị xử lý sai.',
            'Gửi yêu cầu tới hotro@petcare.vn — chúng tôi phản hồi trong vòng 7 ngày làm việc.',
          ].join('\n'),
        },
        {
          heading: 'Cookie',
          body: 'Chúng tôi dùng cookie kỹ thuật để giữ phiên đăng nhập và giỏ hàng. Cookie phân tích chỉ được bật khi bạn đồng ý và có thể tắt trong cài đặt trình duyệt mà không ảnh hưởng tới việc đặt phòng.',
        },
      ],
    },
  },
  FAQ: {
    items: [
      {
        question: 'PetCare hoạt động ở khu vực nào?',
        answer:
          'Hiện chúng tôi phục vụ tại các thành phố lớn và đang mở rộng liên tục. Nhập địa chỉ khi đặt lịch để kiểm tra khả năng phục vụ.',
      },
      {
        question: 'Tôi có thể đổi hoặc hủy lịch hẹn không?',
        answer: 'Có. Bạn có thể dời hoặc hủy lịch miễn phí trước 24 giờ, ngay trong mục Lịch hẹn của tài khoản.',
      },
      {
        question: 'Các bác sĩ thú y có được cấp phép không?',
        answer: 'Mọi phòng khám và bác sĩ đối tác đều được kiểm chứng giấy phép hành nghề trước khi tham gia hệ thống.',
      },
      {
        question: 'Chi phí giao hàng như thế nào?',
        answer: 'Miễn phí giao hàng cho đơn từ một mức tối thiểu; phần lớn đơn được giao trong vòng 24 giờ.',
      },
    ],
  },
};

/**
 * `{}` (BE seed), thiếu trường, sai kiểu, rỗng hoặc vượt giới hạn thì trả cả bộ mặc định của khóa (không trộn từng trường);
 * hợp lệ thì trả bản đã bỏ khoảng trắng hai đầu, trường lạ bị bỏ.
 */
export function parsePageContent<K extends PageKey>(key: K, raw: unknown): PageContents[K] {
  const parsed = PAGE_SCHEMAS[key].safeParse(raw);
  return (parsed.success ? parsed.data : PAGE_DEFAULTS[key]) as PageContents[K];
}
