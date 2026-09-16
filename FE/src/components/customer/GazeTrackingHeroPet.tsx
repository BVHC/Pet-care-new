import React, { useEffect, useRef, useState } from "react";
import gsap from "gsap";
import { Heart, Sparkles } from "lucide-react";

interface GazeTrackingHeroPetProps {
	className?: string;
}

export const GazeTrackingHeroPet: React.FC<GazeTrackingHeroPetProps> = ({
	className = "",
}) => {
	const containerRef = useRef<HTMLDivElement>(null);
	const petWrapperRef = useRef<HTMLDivElement>(null);

	const [dialogue, setDialogue] = useState<string | null>(null);
	const [hearts, setHearts] = useState<{ id: number; x: number; y: number }[]>(
		[],
	);
	const dialogueTimeoutRef = useRef<NodeJS.Timeout | null>(null);

	// Clean dialogues matching cat mascot without raw text emojis
	const DIALOGUES = [
		"Meo meo! Em chào sen ghé thăm PetCare!",
		"Sen ơi, em ngắm sen nãy giờ đó!",
		"Phòng ở PetCare mát rượi 25°C thích lắm sen!",
		"Camera Live Cam 24/7 nét căng ngắm em ngủ cả ngày nha!",
		"Sen vuốt ve lông em mượt mà thích quá!",
	];

	// Handle click on pet: hearts + joyful dialogue + 3D physical bounce
	const handlePetClick = (e: React.MouseEvent) => {
		const rect = containerRef.current?.getBoundingClientRect();
		if (rect) {
			const clickX = e.clientX - rect.left;
			const clickY = e.clientY - rect.top;

			const newHeart = { id: Date.now() + Math.random(), x: clickX, y: clickY };
			setHearts((prev) => [...prev.slice(-5), newHeart]);

			setTimeout(() => {
				setHearts((prev) => prev.filter((h) => h.id !== newHeart.id));
			}, 1200);
		}

		const randomDialogue =
			DIALOGUES[Math.floor(Math.random() * DIALOGUES.length)];
		setDialogue(randomDialogue);

		if (dialogueTimeoutRef.current) {
			clearTimeout(dialogueTimeoutRef.current);
		}
		dialogueTimeoutRef.current = setTimeout(() => {
			setDialogue(null);
		}, 4000);

		// Joyful physical bounce on click
		if (petWrapperRef.current) {
			gsap.fromTo(
				petWrapperRef.current,
				{ y: -18, scale: 1.02, rotateZ: (Math.random() - 0.5) * 3 },
				{
					y: 0,
					scale: 1,
					rotateZ: 0,
					duration: 0.7,
					ease: "elastic.out(1.2, 0.4)",
				},
			);
		}
	};

	// Idle gentle breathing animation
	useEffect(() => {
		if (!petWrapperRef.current) return;

		const breatheTween = gsap.to(petWrapperRef.current, {
			y: "-=4",
			scale: 1.01,
			duration: 3.2,
			repeat: -1,
			yoyo: true,
			ease: "sine.inOut",
		});

		return () => {
			breatheTween.kill();
		};
	}, []);

	// Mouse move: 2.5D Head/Body Parallax tilt
	useEffect(() => {
		const handleMouseMove = (e: MouseEvent) => {
			if (!containerRef.current || !petWrapperRef.current) return;
			const rect = containerRef.current.getBoundingClientRect();
			const centerX = rect.left + rect.width / 2;
			const centerY = rect.top + rect.height / 2;

			// Relative mouse offset normalized to [-1, 1]
			const normX = Math.min(
				Math.max((e.clientX - centerX) / (window.innerWidth / 2), -1),
				1,
			);
			const normY = Math.min(
				Math.max((e.clientY - centerY) / (window.innerHeight / 2), -1),
				1,
			);

			// 2.5D Head & Body Tilt with smooth GSAP interpolation
			gsap.to(petWrapperRef.current, {
				rotateY: normX * 6.0,
				rotateX: -normY * 3.5,
				x: normX * 6,
				duration: 0.45,
				ease: "power1.out",
				overwrite: "auto",
			});
		};

		window.addEventListener("mousemove", handleMouseMove, { passive: true });

		return () => {
			window.removeEventListener("mousemove", handleMouseMove);
			if (dialogueTimeoutRef.current) clearTimeout(dialogueTimeoutRef.current);
		};
	}, []);

	return (
		<div
			ref={containerRef}
			onClick={handlePetClick}
			className={`relative select-none cursor-pointer group flex flex-col items-center justify-end ${className}`}
			title="Nhấn để vuốt ve mèo cưng PetCare!"
			role="button"
			tabIndex={0}
			aria-label="Mèo cưng Ragdoll PetCare lông xù mắt xanh"
			style={{ perspective: 1200 }}
		>
			{/* Speech Bubble on Click (No raw emojis) */}
			{dialogue && (
				<div className="absolute top-2 sm:top-4 -left-4 sm:-left-10 z-50 bg-white/95 backdrop-blur-md text-[#1a1a1a] text-xs sm:text-[13px] font-bold px-4 py-2.5 rounded-2xl shadow-2xl border border-orange-200 pointer-events-none text-left max-w-[210px] sm:max-w-[240px] leading-snug animate-bounce flex items-center gap-2">
					<Sparkles size={14} className="text-[#ff5722] shrink-0" />
					<span>{dialogue}</span>
					<div className="absolute -bottom-2 left-8 w-0 h-0 border-x-6 border-x-transparent border-t-6 border-t-white" />
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
						size={28}
						className="text-[#ff5722] fill-[#ff5722] drop-shadow-md"
					/>
				</div>
			))}

			{/* Realistic Soft Contact Shadow on Floor Under Paws */}
			<div className="absolute -bottom-3 left-1/2 -translate-x-1/2 w-[75%] h-6 bg-gradient-to-t from-black/25 via-black/10 to-transparent rounded-[100%] blur-md pointer-events-none z-0" />

			{/* 2.5D Tilting Mascot Container */}
			<div
				ref={petWrapperRef}
				className="relative w-full will-change-transform flex flex-col items-center z-10"
				style={{ transformStyle: "preserve-3d" }}
			>
				<img
					src="/imgs/maw-care-cat.png"
					alt="Mascot Mèo cưng Ragdoll PetCare"
					className="w-full h-auto object-contain select-none drop-shadow-[0_20px_40px_rgba(0,0,0,0.12)] pointer-events-auto"
				/>
			</div>
		</div>
	);
};

export default GazeTrackingHeroPet;
