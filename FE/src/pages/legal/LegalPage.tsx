import { Link } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import type { PolicyContent } from '@/shared/constants/page-content';
import { usePublicPage } from '@/shared/hooks/usePageContent';

type LegalDoc = PolicyContent['terms'];

const PULSE = 'animate-pulse rounded bg-[#e6d9c8]/70 motion-reduce:animate-none';

/** Mỗi dòng của `body` là một đoạn; dòng trống bị bỏ. */
const paragraphsOf = (body: string) =>
  body
    .split('\n')
    .map((p) => p.trim())
    .filter(Boolean);

// Nội dung điều khoản / bảo mật do quản trị sửa (UC21, T47, khóa POLICY). Chưa có câu trả lời thì giữ skeleton để không nháy từ chữ mặc định
// sang chữ đã sửa; `{}`, sai dạng hoặc request lỗi thì `ready` vẫn true với chữ mặc định, trang không bao giờ trống.
function LegalShell({
  eyebrow,
  title,
  doc,
  ready,
  sibling,
}: {
  eyebrow: string;
  title: string;
  doc: LegalDoc;
  ready: boolean;
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
          {ready ? (
            <>
              <p className="mt-4 max-w-[62ch] text-[15.5px] leading-[1.7] text-[#7a6a5d] whitespace-pre-line wrap-anywhere">{doc.lead}</p>
              <p className="mt-5 text-[13px] font-semibold text-[#a3968a] wrap-anywhere">Cập nhật lần cuối: {doc.updatedOn}</p>
            </>
          ) : (
            <div role="status" aria-label="Đang tải nội dung" className="mt-4 space-y-2.5">
              <div aria-hidden className={`${PULSE} h-4 w-full max-w-[62ch]`} />
              <div aria-hidden className={`${PULSE} h-4 w-2/3 max-w-[40ch]`} />
              <div aria-hidden className={`${PULSE} mt-5 h-3.5 w-48`} />
            </div>
          )}
        </div>
      </header>

      <div className="mx-auto grid max-w-[1100px] gap-12 px-6 pt-14 lg:grid-cols-[220px_1fr]">
        {/* Muc luc — dinh theo scroll tren man hinh rong */}
        <nav className="hidden lg:block lg:sticky lg:top-[170px] lg:self-start">
          <p className="mb-3 text-[12px] font-bold uppercase tracking-[0.09em] text-[#a3968a]">Nội dung</p>
          {ready && (
            <ul className="space-y-2.5">
              {doc.sections.map((s, i) => (
                <li key={i}>
                  <a
                    href={`#muc-${i + 1}`}
                    className="text-[13.5px] leading-[1.45] text-[#7a6a5d] transition-colors hover:text-accent"
                  >
                    {i + 1}. {s.heading}
                  </a>
                </li>
              ))}
            </ul>
          )}
        </nav>

        <article className="max-w-[68ch] space-y-10">
          {ready ? (
            doc.sections.map((s, i) => (
              <section key={i} id={`muc-${i + 1}`} className="scroll-mt-[170px]">
                <h2 className="font-friendly text-[22px] font-extrabold leading-[1.25] text-[#191919] wrap-anywhere">
                  {i + 1}. {s.heading}
                </h2>
                {paragraphsOf(s.body).map((p, j) => (
                  <p key={j} className="mt-3 text-[15.5px] leading-[1.75] text-[#5c4f45] wrap-anywhere">
                    {p}
                  </p>
                ))}
              </section>
            ))
          ) : (
            <div aria-hidden className="space-y-10">
              {[0, 1, 2, 3].map((i) => (
                <div key={i} className="space-y-3">
                  <div className={`${PULSE} h-6 w-2/3`} />
                  <div className={`${PULSE} h-4 w-full`} />
                  <div className={`${PULSE} h-4 w-full`} />
                  <div className={`${PULSE} h-4 w-5/6`} />
                </div>
              ))}
            </div>
          )}

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

export function TermsPage() {
  const { content, ready } = usePublicPage('POLICY');
  return (
    <LegalShell
      eyebrow="Pháp lý"
      title="Điều khoản sử dụng"
      doc={content.terms}
      ready={ready}
      sibling={{ to: '/privacy', label: 'Đọc Chính sách bảo mật' }}
    />
  );
}

export function PrivacyPage() {
  const { content, ready } = usePublicPage('POLICY');
  return (
    <LegalShell
      eyebrow="Pháp lý"
      title="Chính sách bảo mật"
      doc={content.privacy}
      ready={ready}
      sibling={{ to: '/terms', label: 'Đọc Điều khoản sử dụng' }}
    />
  );
}
