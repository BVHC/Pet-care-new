import React, { useEffect, useRef } from "react";
import { Link } from "react-router-dom";
import { PawPrint } from "lucide-react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { GazeTrackingHeroPet } from "./GazeTrackingHeroPet";

gsap.registerPlugin(ScrollTrigger);

const AVATARS = [
	"https://images.unsplash.com/photo-1544005313-94ddf0286df2?w=100&auto=format&fit=crop&q=80",
	"https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=100&auto=format&fit=crop&q=80",
];

export const HeroBanner: React.FC = () => {
	const bannerRef = useRef<HTMLDivElement>(null);

	useEffect(() => {
		if (typeof window === "undefined") return;

		const ctx = gsap.context(() => {
			const entryTl = gsap.timeline({ defaults: { ease: "power3.out" } });

			entryTl
				.fromTo(
					".hero-left-wing",
					{ opacity: 0, x: -30 },
					{ opacity: 1, x: 0, duration: 0.7 },
				)
				.fromTo(
					".hero-center-squircle",
					{ opacity: 0, scale: 0.92, y: 25 },
					{ opacity: 1, scale: 1, y: 0, duration: 0.8, ease: "back.out(1.15)" },
					"-=0.5",
				)
				.fromTo(
					".hero-right-wing",
					{ opacity: 0, x: 30 },
					{ opacity: 1, x: 0, duration: 0.7 },
					"-=0.5",
				)
				.fromTo(
					".hero-bottom-card",
					{ opacity: 0, y: 20 },
					{ opacity: 1, y: 0, duration: 0.5, stagger: 0.1, ease: "power2.out" },
					"-=0.3",
				);
		}, bannerRef);

		return () => ctx.revert();
	}, []);

	return (
		<div
			ref={bannerRef}
			className="relative w-full overflow-hidden bg-[#f6e8da] text-[#191919] pt-8 sm:pt-10 lg:pt-12 pb-8 sm:pb-10"
		>
			{/* Nối màu với header kem phía trên, tránh đường cắt cứng */}
			<div className="absolute inset-x-0 top-0 h-28 bg-gradient-to-b from-[#fdf5ec] to-transparent pointer-events-none z-0" />

			{/* Subtle Warm Backdrop Lighting */}
			<div className="absolute top-0 left-1/3 w-[600px] h-[500px] bg-white/40 rounded-full blur-3xl pointer-events-none -z-0" />

			{/* Main Content Container — rộng gần full-bleed như reference */}
			<div className="relative z-10 max-w-[1560px] mx-auto px-5 sm:px-8 lg:px-12">
				{/* ================= 3-ZONE HERO STAGE ================= */}
				<div className="grid grid-cols-1 lg:grid-cols-12 gap-8 lg:gap-8 items-center mb-10 sm:mb-16 lg:mb-[52px]">
					{/* 1. LEFT WING: Headline, Subtitle, White Pill Button, 5-Star Ratings */}
					<div className="hero-left-wing lg:col-span-4 flex flex-col justify-center order-2 lg:order-1 text-left">
						{/* Bold Giant Title */}
						<h1 className="font-sans text-[clamp(48px,6.6vw,124px)] font-black leading-[0.85] tracking-[-0.04em] text-[#141414] mb-5 lg:mb-6">
							Pet
							<br />
							Care
						</h1>

						{/* Subtitle — đen đậm như reference, không phải xám nhạt */}
						<p className="text-xl sm:text-2xl font-bold leading-[1.35] text-[#1a1a1a] mb-7 lg:mb-8 max-w-[340px]">
							Tận tâm chăm sóc
							<br />
							bé cưng của bạn
						</p>

						{/* White Pill Button with Orange Text */}
						<div className="mb-8 lg:mb-9">
							<Link
								to="/booking"
								className="inline-flex items-center justify-center rounded-full bg-white hover:bg-[#a43324] text-[#a43324] hover:text-white px-9 sm:px-10 py-4 sm:py-5 text-base sm:text-lg font-black uppercase tracking-wide shadow-sm hover:shadow-lg transition-all duration-200 hover:scale-[1.03] active:scale-95"
							>
								Đặt Lịch Ngay
							</Link>
						</div>

						{/* Đánh Giá 5 Sao Trên Mọi Nền Tảng */}
						<div className="flex items-center gap-4 mb-3">
							<div className="w-14 h-14 rounded-2xl bg-[#a43324] text-white flex items-center justify-center shrink-0 shadow-sm">
								<PawPrint size={26} className="fill-white text-white" />
							</div>
							<div className="text-[15px] sm:text-base font-black leading-[1.15] text-[#141414]">
								Đánh Giá
								<br />
								5 Sao Trên
								<br />
								Mọi Nền Tảng
							</div>
						</div>

						{/* Google Reviews Link */}
						<Link
							to="/review"
							className="text-[15px] text-[#5a5a5a] hover:text-[#a43324] underline underline-offset-4 font-semibold transition-colors w-fit mt-1"
						>
							Xem tất cả đánh giá Google
						</Link>
					</div>

					{/* 2. CENTER STAGE: White Squircle Card + Mascot (Căn chính giữa tuyệt đối 4-4-4) */}
					<div className="hero-center-squircle lg:col-span-4 flex justify-center items-center relative order-1 lg:order-2">
						{/* Khung vòm trắng — overflow-visible */}
						<div className="relative w-full max-w-[440px] sm:max-w-[480px] aspect-[49/54] bg-white rounded-[44px] sm:rounded-[56px] shadow-[0_24px_60px_rgba(0,0,0,0.07)] overflow-visible">
							{/* Vòng tròn xám/kem tạo chiều sâu — khóa tâm tuyệt đối 50% / 50% */}
							<div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[88%] aspect-square rounded-full bg-[#f1e4d3] pointer-events-none" />

							{/* Mascot tương tác phóng to và khóa tâm tuyệt đối 50% / 50% */}
							<div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[114%] sm:w-[122%] max-w-[580px] aspect-square z-35 flex items-center justify-center">
								<GazeTrackingHeroPet className="w-full h-full" />
							</div>

							{/* Orange Pill đè lên mép trái khung */}
							<div className="absolute top-1/2 -left-[7%] sm:-left-[10%] -translate-y-1/2 z-100 bg-[#a43324] text-white text-base sm:text-lg font-bold px-7 sm:px-8 py-4 sm:py-5 rounded-full shadow-[0_10px_30px_rgba(164,51,36,0.32)] leading-[1.25] whitespace-nowrap text-center">
								Bé cưng là
								<br />
								ưu tiên số 1
							</div>
						</div>
					</div>

					{/* 3. RIGHT WING: Join Circle, Vertical Orange Card, Stat (Đối xứng lg:col-span-4) */}
					<div className="hero-right-wing lg:col-span-4 flex flex-col text-left lg:text-right justify-center lg:justify-between order-3 lg:items-end lg:h-[clamp(320px,calc(100vh-320px),640px)]">
						{/* Join Us — hình tròn như reference, không phải pill ngang */}
						<div className="w-[128px] h-[128px] sm:w-[142px] sm:h-[142px] shrink-0 rounded-full bg-white shadow-[0_8px_28px_rgba(0,0,0,0.08)] flex flex-col items-center justify-center gap-1.5 mb-5 lg:mb-0 lg:mr-3">
							<div className="flex items-center -space-x-2">
								{AVATARS.map((src, i) => (
									<img
										key={i}
										src={src}
										alt="Khách hàng PetCare"
										className="w-8 h-8 rounded-full border-2 border-white object-cover"
									/>
								))}
								<span className="w-8 h-8 rounded-full border-2 border-white bg-[#efece8] text-[10px] font-black text-[#141414] flex items-center justify-center">
									2K+
								</span>
							</div>
							<span className="text-[14px] font-bold text-[#141414]">
								Tham Gia
							</span>
						</div>

						{/* Thẻ cam dọc — bleed sát mép phải container */}
						<div className="relative w-[176px] sm:w-[196px] lg:w-auto lg:flex-1 lg:min-h-0 aspect-[3/4] bg-[#a43324] rounded-[30px] p-2.5 shadow-sm mb-6 lg:mb-0 lg:my-4 overflow-hidden group">
							<img
								src="/imgs/maw-care-girl-orange.jpg"
								alt="Nhân viên PetCare ôm mèo cưng"
								className="w-full h-full object-cover rounded-[22px] group-hover:scale-105 transition-transform duration-500"
							/>
						</div>

						{/* Stat */}
						<div className="w-fit shrink-0 text-left lg:text-right">
							<div className="text-[15px] font-bold text-[#4d4d4d] leading-[1.25]">
								Chủ thú cưng
								<br />
								tin tưởng
							</div>
							<div className="font-sans text-5xl sm:text-6xl font-black text-[#141414] leading-none tracking-tight mt-1">
								2.500+
							</div>
						</div>
					</div>
				</div>

				{/* ================= BOTTOM ROW: 3 thẻ dịch vụ ================= */}
				<div className="grid grid-cols-1 md:grid-cols-3 gap-4 lg:gap-5">
					{/* Thẻ 1: Xám trung tính — ảnh full-bleed nửa phải */}
					<Link
						to="/news"
						className="hero-bottom-card group relative bg-[#d6d3cf] hover:bg-[#cdc9c5] rounded-[26px] overflow-hidden transition-colors duration-300 aspect-[2/1] flex"
					>
						<div className="flex flex-col justify-center gap-4 w-[54%] pl-7 lg:pl-9 pr-2 py-6">
							<div className="text-xl lg:text-[28px] font-black text-[#141414] leading-[1.12]">
								Cẩm Nang
								<br />
								Chăm Bé
							</div>
							<span className="text-sm lg:text-base text-[#3d3d3d] group-hover:text-[#141414] underline underline-offset-[5px] font-semibold transition-colors w-fit">
								đọc bài viết
							</span>
						</div>
						<div className="relative w-[46%]">
							<img
								src="/imgs/maw-care-girl-kitten.jpg"
								alt="Chủ nuôi bế mèo con"
								className="absolute inset-0 w-full h-full object-cover object-center group-hover:scale-[1.04] transition-transform duration-500"
							/>
						</div>
					</Link>

					{/* Thẻ 2: Than chì — squiggle lớn chạy tràn mép dưới */}
					<Link
						to="/recommend"
						className="hero-bottom-card group relative bg-[#1c1b1f] hover:bg-[#141316] text-white rounded-[26px] overflow-hidden transition-colors duration-300 aspect-[2/1] flex"
					>
						<div className="flex flex-col justify-center gap-4 w-[54%] pl-7 lg:pl-9 pr-2 py-6 relative z-10">
							<div className="text-xl lg:text-[28px] font-black text-white leading-[1.12]">
								Gợi Ý
								<br />
								Dịch Vụ
							</div>
							<span className="text-sm lg:text-base text-white/75 group-hover:text-white underline underline-offset-[5px] font-semibold transition-colors w-fit">
								tìm hiểu thêm
							</span>
						</div>
						{/* Hai dải uốn lượn lớn, tràn qua mép trên & dưới của thẻ */}
						<svg
							viewBox="0 0 200 300"
							fill="none"
							preserveAspectRatio="xMidYMid slice"
							className="absolute right-0 -top-[12%] h-[124%] w-[52%] pointer-events-none"
							aria-hidden="true"
						>
							<path
								d="M138,-20 C96,34 74,70 88,108 C102,146 122,168 106,206 C92,240 78,262 84,320"
								stroke="#cf5b47"
								strokeWidth="13"
								strokeLinecap="round"
							/>
							<path
								d="M196,16 C154,66 136,100 150,140 C164,180 182,202 164,238 C150,268 138,282 142,330"
								stroke="#efece2"
								strokeWidth="13"
								strokeLinecap="round"
							/>
						</svg>
					</Link>

					{/* Thẻ 3: Coral — mèo con cutout bước ra khỏi thẻ */}
					<Link
						to="/hotel"
						className="hero-bottom-card group relative bg-[#cf5b47] hover:bg-[#b94a38] text-white rounded-[26px] transition-colors duration-300 aspect-[2/1] flex overflow-visible"
					>
						<div className="flex flex-col justify-center gap-4 w-[54%] pl-7 lg:pl-9 pr-2 py-6 relative z-10">
							<div className="text-xl lg:text-[28px] font-black text-white leading-[1.12]">
								Khách Sạn
								<br />
								Cao Cấp
							</div>
							<span className="text-sm lg:text-base text-white/85 group-hover:text-white underline underline-offset-[5px] font-semibold transition-colors w-fit">
								đặt phòng ngay
							</span>
						</div>
						<img
							src="/imgs/maw-care-kitten-cutout.png"
							alt="Mèo con trắng tại khách sạn thú cưng PetCare"
							className="absolute -bottom-[14%] right-1 sm:right-2 w-[46%] max-w-[210px] object-contain drop-shadow-[0_14px_24px_rgba(0,0,0,0.18)] group-hover:-translate-y-1.5 transition-transform duration-500"
						/>
					</Link>
				</div>
			</div>
		</div>
	);
};

export default HeroBanner;
