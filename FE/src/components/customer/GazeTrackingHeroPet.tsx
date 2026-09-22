import React, { useEffect, useRef, useState, useCallback } from "react";
import { Heart, Sparkles } from "lucide-react";

const DIRECTIONS = [
	"up-left",
	"up",
	"up-right",
	"left",
	"center",
	"right",
	"down-left",
	"down",
	"down-right",
] as const;

const REACTIONS = [
	"blink",
	"heart",
	"sparkle",
	"surprised",
	"wink",
	"bashful",
	"sleepy",
	"dizzy",
	"delighted",
] as const;

type Direction = (typeof DIRECTIONS)[number];
type Reaction = (typeof REACTIONS)[number];

const CLOCKWISE: Direction[] = [
	"right",
	"down-right",
	"down",
	"down-left",
	"left",
	"up-left",
	"up",
	"up-right",
];
const SECTOR = (Math.PI * 2) / CLOCKWISE.length;
const HYSTERESIS = 0.12;

const PAYOFFS: Reaction[] = ["heart", "sparkle", "delighted"];
const BOOP_PAYOFF = 120;
const BOOP_END = 580;
const SQUASH_MS = 420;
const DIZZY_AFTER = 4;
const DIZZY_WINDOW = 1600;
const DIZZY_END = 1400;
const AUTO_SWITCH_INTERVAL = 14000; // Tự đổi bé sau 14s nếu idle

const SQUASH: Keyframe[] = [
	{ transform: "scale(1, 1)", easing: "ease-in" },
	{ transform: "scale(1.10, 0.86)", offset: 0.18, easing: "ease-out" },
	{ transform: "scale(0.95, 1.08)", offset: 0.45, easing: "ease-in-out" },
	{ transform: "scale(1.03, 0.97)", offset: 0.72, easing: "ease-in-out" },
	{ transform: "scale(1, 1)" },
];

function cellStyle(index: number): React.CSSProperties {
	return {
		backgroundPosition: `${(index % 3) * 50}% ${Math.floor(index / 3) * 50}%`,
	};
}

function wrapAngle(angle: number) {
	return Math.atan2(Math.sin(angle), Math.cos(angle));
}

const layerStyle: React.CSSProperties = {
	position: "absolute",
	inset: 0,
	backgroundSize: "300% 300%",
	backgroundRepeat: "no-repeat",
};

interface PetPreset {
	id: "pug" | "cat";
	name: string;
	tag: string;
	directions: string;
	reactions: string;
	switchInDialogue: string;

}

const PET_PRESETS: Record<"pug" | "cat", PetPreset> = {
	pug: {
		id: "pug",
		name: "Chó Pug",
		tag: "Pug",
		directions: "/mascots/pug-directions.webp?v=3",
		reactions: "/mascots/pug-reactions.webp?v=3",
		switchInDialogue: "Gâu gâu! Đến ca của Pug ra đón sen rồi nè!",
	},
	cat: {
		id: "cat",
		name: "Mèo",
		tag: "Meo",
		directions: "/mascots/cat-directions.webp?v=3",
		reactions: "/mascots/cat-reactions.webp?v=3",
		switchInDialogue: "Meo meo! Đổi ca nào, Miu ra phục vụ sen đây!",
		
	},
};

interface GazeTrackingHeroPetProps {
	className?: string;
}

export const GazeTrackingHeroPet: React.FC<GazeTrackingHeroPetProps> = ({
	className = "",
}) => {
	const [activePet, setActivePet] = useState<"pug" | "cat">("pug");
	const [direction, setDirection] = useState<Direction>("center");
	const [reaction, setReaction] = useState<Reaction | null>(null);
	const [dialogue, setDialogue] = useState<string | null>(null);
	const [isTransitioning, setIsTransitioning] = useState(false);
	const [hearts, setHearts] = useState<{ id: number; x: number; y: number }[]>(
		[],
	);

	const mascotBtnRef = useRef<HTMLButtonElement>(null);
	const squashRef = useRef<HTMLSpanElement>(null);
	const timersRef = useRef<number[]>([]);
	const boopsRef = useRef({ count: 0, at: 0 });
	const dialogueTimeoutRef = useRef<NodeJS.Timeout | null>(null);
	const idleTimerRef = useRef<NodeJS.Timeout | null>(null);

	const currentPreset = PET_PRESETS[activePet];

	// Đổi mascot mượt mà kèm hiệu ứng xoay nảy + câu chào đổi ca
	const switchPetWithAnimation = useCallback(
		(nextPet?: "pug" | "cat", customDialogue?: string) => {
			setIsTransitioning(true);
			setTimeout(() => {
				setActivePet((prev) => {
					const target = nextPet || (prev === "pug" ? "cat" : "pug");
					setDialogue(customDialogue || PET_PRESETS[target].switchInDialogue);
					if (dialogueTimeoutRef.current) clearTimeout(dialogueTimeoutRef.current);
					dialogueTimeoutRef.current = setTimeout(() => {
						setDialogue(null);
					}, 3800);
					return target;
				});
				setReaction("sparkle");
				setTimeout(() => setReaction(null), 500);
				setIsTransitioning(false);
			}, 280);
		},
		[],
	);

	// Tự động đổi ca sau chu kỳ idle nếu sen không bấm
	const resetIdleTimer = useCallback(() => {
		if (idleTimerRef.current) clearTimeout(idleTimerRef.current);
		idleTimerRef.current = setTimeout(() => {
			switchPetWithAnimation();
			resetIdleTimer();
		}, AUTO_SWITCH_INTERVAL);
	}, [switchPetWithAnimation]);

	useEffect(() => {
		resetIdleTimer();
		return () => {
			if (idleTimerRef.current) clearTimeout(idleTimerRef.current);
		};
	}, [resetIdleTimer]);

	// Gaze tracking logic matching page-mascot với sector hysteresis & dead-zone chuẩn tâm
	useEffect(() => {
		if (
			typeof window === "undefined" ||
			!window.matchMedia("(hover: hover) and (pointer: fine)").matches
		) {
			return;
		}

		let sector = -1;
		let pointer: { x: number; y: number } | null = null;
		let idleGazeTimer: NodeJS.Timeout | null = null;

		const returnToCenter = () => {
			sector = -1;
			setDirection("center");
		};

		const aim = () => {
			const button = mascotBtnRef.current;
			if (!button || !pointer) return;

			const box = button.getBoundingClientRect();
			const centerX = box.left + box.width / 2;
			const centerY = box.top + box.height / 2;

			const dx = pointer.x - centerX;
			const dy = pointer.y - centerY;
			const dist = Math.hypot(dx, dy);

			// Dead-zone vùng mặt (90px) hoặc khi chuột ở quá xa ngoài màn hình (> 750px) -> nhìn thẳng
			const deadZone = Math.max(90, box.width * 0.26);

			if (dist < deadZone || dist > 750) {
				returnToCenter();
				return;
			}

			const angle = Math.atan2(dy, dx);
			if (
				sector !== -1 &&
				Math.abs(wrapAngle(angle - sector * SECTOR)) < SECTOR / 2 + HYSTERESIS
			) {
				return;
			}

			sector = (Math.round(angle / SECTOR) + CLOCKWISE.length) % CLOCKWISE.length;
			setDirection(CLOCKWISE[sector]);

			// Sau 2.2s chuột đứng yên một chỗ, tự động trả mắt về nhìn thẳng thân thiện
			if (idleGazeTimer) clearTimeout(idleGazeTimer);
			idleGazeTimer = setTimeout(() => {
				returnToCenter();
			}, 2200);
		};

		const onPointerMove = (e: PointerEvent) => {
			pointer = { x: e.clientX, y: e.clientY };
			aim();
		};

		const onMouseLeaveDoc = () => {
			if (idleGazeTimer) clearTimeout(idleGazeTimer);
			returnToCenter();
		};

		window.addEventListener("pointermove", onPointerMove, { passive: true });
		window.addEventListener("scroll", aim, { passive: true });
		document.addEventListener("mouseleave", onMouseLeaveDoc);

		return () => {
			if (idleGazeTimer) clearTimeout(idleGazeTimer);
			window.removeEventListener("pointermove", onPointerMove);
			window.removeEventListener("scroll", aim);
			document.removeEventListener("mouseleave", onMouseLeaveDoc);
		};
	}, []);

	// Dọn dẹp timer khi unmount
	useEffect(() => {
		return () => {
			timersRef.current.forEach((t) => window.clearTimeout(t));
			if (dialogueTimeoutRef.current) clearTimeout(dialogueTimeoutRef.current);
		};
	}, []);

	// Boop / Poke handler: Squash & stretch + bắn tim + tự đổi bạn khi bị bấm chóng mặt
	const handleBoop = useCallback(
		(e: React.MouseEvent) => {
			resetIdleTimer();

			timersRef.current.forEach((t) => window.clearTimeout(t));
			timersRef.current = [];

			const later = (ms: number, next: Reaction | null) => {
				timersRef.current.push(window.setTimeout(() => setReaction(next), ms));
			};

			const now = Date.now();
			const boops = boopsRef.current;
			boops.count = now - boops.at < DIZZY_WINDOW ? boops.count + 1 : 1;
			boops.at = now;

			// Bắn tim bay tại vị trí chạm chuột
			const rect = mascotBtnRef.current?.getBoundingClientRect();
			if (rect) {
				const clickX = e.clientX - rect.left;
				const clickY = e.clientY - rect.top;
				const newHeart = { id: Date.now() + Math.random(), x: clickX, y: clickY };
				setHearts((prev) => [...prev.slice(-4), newHeart]);
				setTimeout(() => {
					setHearts((prev) => prev.filter((h) => h.id !== newHeart.id));
				}, 1200);
			}

			// Khi bị chọc liên tục >= 4 lần: Chóng mặt, sau đó BẠN BÈ TỰ ĐỘNG NHẢY RA ĐỔI CA cứu bồ!
			if (boops.count >= DIZZY_AFTER) {
				boops.count = 0;
				setReaction("dizzy");
				// setDialogue("Ối ối... Sen bấm nhanh quá em hoa mắt chóng mặt rồi!");

				if (dialogueTimeoutRef.current) clearTimeout(dialogueTimeoutRef.current);
				dialogueTimeoutRef.current = setTimeout(() => {
					// Bạn kia nhảy ra thay ca cứu nguy
					const nextPet = activePet === "pug" ? "cat" : "pug";
					const rescueMsg =
						nextPet === "cat"
							? "Miu ra cứu Pug đây! Sen chuyển sang chơi với em nha meo~"
							: "Gâu gâu! Miu mệt rồi, Pug ra chơi tiếp với sen nè!";
					switchPetWithAnimation(nextPet, rescueMsg);
				}, DIZZY_END);
			} else {
				setReaction("blink");
				later(BOOP_PAYOFF, PAYOFFS[(boops.count - 1) % PAYOFFS.length]);
				later(BOOP_END, null);

				if (dialogueTimeoutRef.current) {
					clearTimeout(dialogueTimeoutRef.current);
				}
				dialogueTimeoutRef.current = setTimeout(() => {
					setDialogue(null);
				}, 3600);
			}

			// Hiệu ứng nảy squash & stretch bằng Web Animations API
			if (!window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
				squashRef.current?.animate(SQUASH, {
					duration: SQUASH_MS,
					easing: "linear",
				});
			}
		},
		[activePet, resetIdleTimer, switchPetWithAnimation],
	);

	return (
		<div
			className={`relative w-full h-full flex items-center justify-center select-none ${className}`}
		>
			{/* Speech Dialogue Bubble on Click / Auto-switch */}
			{dialogue && (
				<div className="absolute top-1.5 sm:top-3.5 left-1/2 -translate-x-1/2 z-50 bg-white/95 backdrop-blur-md text-[#1a1a1a] text-xs sm:text-[13.5px] font-bold px-4 py-2.5 rounded-2xl shadow-xl border border-orange-200 pointer-events-none text-center whitespace-nowrap max-w-[92%] leading-snug animate-bounce flex items-center justify-center gap-2">
					<Sparkles size={16} className="text-accent shrink-0" />
					<span>{dialogue}</span>
					<div className="absolute -bottom-2 left-1/2 -translate-x-1/2 w-0 h-0 border-x-6 border-x-transparent border-t-6 border-t-white" />
				</div>
			)}

			{/* Floating SVG Hearts on Petting */}
			{hearts.map((h) => (
				<div
					key={h.id}
					style={{ left: h.x, top: h.y }}
					className="absolute z-50 pointer-events-none -translate-x-1/2 -translate-y-1/2 animate-ping"
				>
					<Heart
						size={32}
						className="text-accent fill-accent drop-shadow-md"
					/>
				</div>
			))}

			{/* Bóng tiếp xúc nền mềm mại dưới chân */}
			<div className="absolute bottom-[8%] left-1/2 -translate-x-1/2 w-[72%] h-7 bg-linear-to-t from-black/22 via-black/8 to-transparent rounded-[100%] blur-md pointer-events-none z-0" />

			{/* Mascot Button to và căn chính xác giữa khung */}
			<button
				ref={mascotBtnRef}
				type="button"
				onClick={handleBoop}
				aria-label={`Tương tác với ${currentPreset.name}`}
				className={`relative block w-full h-full p-0 border-0 bg-transparent cursor-pointer select-none outline-none rounded-full z-10 transition-transform duration-300 ${
					isTransitioning ? "scale-90 opacity-40 rotate-6" : "scale-100 opacity-100 rotate-0"
				}`}
			>
				<span
					ref={squashRef}
					className="relative block w-full h-full"
					style={{ transformOrigin: "50% 86%" }}
				>
					{/* Directions Sprite Layer (Nhìn theo chuột 8 hướng + tâm) */}
					<span
						style={{
							...layerStyle,
							backgroundImage: `url(${currentPreset.directions})`,
							...cellStyle(DIRECTIONS.indexOf(direction)),
							opacity: reaction ? 0 : 1,
							transition: "opacity 120ms ease-out",
						}}
					/>

					{/* Reactions Sprite Layer (Chớp mắt, tim, sao, chóng mặt) */}
					<span
						style={{
							...layerStyle,
							backgroundImage: `url(${currentPreset.reactions})`,
							...cellStyle(REACTIONS.indexOf(reaction ?? "blink")),
							opacity: reaction ? 1 : 0,
							transition: "opacity 120ms ease-out",
						}}
					/>
				</span>
			</button>
		</div>
	);
};

export default GazeTrackingHeroPet;
