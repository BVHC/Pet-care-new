import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';

type Props = {
  /** anh that o cot trai */
  image: string;
  /** nhan nho phia tren headline */
  tag: string;
  /** headline Anton, dung <br/> de ngat dong */
  headline: ReactNode;
  lead: string;
  /** dai so lieu / checklist duoi cung cot trai */
  stageFooter: ReactNode;
  title: string;
  subtitle: string;
  children: ReactNode;
};

export function AuthShell({ image, tag, headline, lead, stageFooter, title, subtitle, children }: Props) {
  return (
    <div className="min-h-screen bg-surface-1 grid grid-cols-1 lg:grid-cols-[1.08fr_1fr]">
      {/* ── Cot trai: anh that + headline tren slab toi ── */}
      <div className="relative overflow-hidden bg-surface-dark min-h-[300px] p-8 lg:sticky lg:top-0 lg:h-screen lg:p-11">
        <img src={image} alt="" className="absolute inset-0 h-full w-full object-cover opacity-[0.62]" />
        <div className="absolute inset-0 bg-[linear-gradient(180deg,rgba(56,36,23,0.35)_0%,rgba(56,36,23,0.55)_45%,rgba(56,36,23,0.94)_100%)]" />

        {/* Logo that — cung asset voi SiteHeader/SiteFooter */}
        <Link to="/" className="relative z-10 inline-flex items-center gap-2 text-surface-1">
          {/* Sticker mau #414545 nen phai co de sang moi doc duoc tren anh toi */}
          <span className="grid h-10 w-10 place-items-center rounded-2xl bg-surface-1">
            <img src="/imgs/DogSticker.svg" alt="PetCare" className="h-7 w-auto object-contain" />
          </span>
          <span className="font-friendly text-[26px] font-black tracking-tight">PetCare</span>
        </Link>

        <div className="relative z-10 mt-8 lg:absolute lg:inset-x-11 lg:bottom-11 lg:mt-0">
          <span className="inline-flex items-center rounded-full bg-[#fdf6ec]/[0.13] px-3.5 py-[7px] text-[11px] font-extrabold uppercase tracking-[0.16em] text-surface-1">
            {tag}
          </span>
          <h1 className="font-bayon mt-5 text-[38px] leading-[0.96] text-surface-1 sm:text-[52px] lg:text-[60px]">
            {headline}
          </h1>
          <p className="mt-4 max-w-[42ch] text-[15px] leading-[1.65] text-[#fdf6ec]/70">{lead}</p>
          <div className="mt-8 border-t border-[#fdf6ec]/[0.16] pt-6">{stageFooter}</div>
        </div>
      </div>

      {/* ── Cot phai: form ── */}
      <div className="flex flex-col justify-center px-6 py-12 sm:px-12 lg:px-[76px]">
        <div className="mx-auto w-full max-w-[400px]">
          <Link
            to="/"
            className="mb-9 inline-flex items-center gap-2 text-[13px] font-bold text-[#7a6a5d] transition-colors hover:text-accent"
          >
            <ArrowLeft size={15} strokeWidth={2.2} />
            Về trang chủ
          </Link>
          <h2 className="font-friendly text-[32px] font-extrabold leading-[1.1] text-[#191919] sm:text-[36px]">
            {title}
          </h2>
          <p className="mt-2 mb-8 text-[15px] text-[#7a6a5d]">{subtitle}</p>
          {children}
        </div>
      </div>
    </div>
  );
}
