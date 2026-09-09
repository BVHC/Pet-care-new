import React from 'react'
import { Link } from 'react-router-dom'

interface RotatingBadgeProps {
  text?: string
  to?: string
  className?: string
  size?: number
}

export const RotatingBadge: React.FC<RotatingBadgeProps> = ({
  text = 'PETCARE 5★ • LIVE CAM 24/7 • BOOK NOW • ',
  to = '/booking',
  className = '',
  size = 130,
}) => {
  return (
    <Link
      to={to}
      className={`group relative flex items-center justify-center rounded-full bg-[#fff500] text-[#e33529] shadow-[0_10px_25px_rgba(0,0,0,0.12)] transition-transform duration-300 hover:scale-110 active:scale-95 ${className}`}
      style={{ width: size, height: size }}
      title="Đặt lịch ngay"
    >
      <svg
        className="absolute inset-0 h-full w-full animate-[spin_12s_linear_infinite] group-hover:animate-[spin_6s_linear_infinite]"
        viewBox="0 0 100 100"
      >
        <defs>
          <path
            id="badgeCirclePath"
            d="M 50, 50 m -37, 0 a 37,37 0 1,1 74,0 a 37,37 0 1,1 -74,0"
          />
        </defs>
        <text
          fontSize="10.2"
          fontWeight="800"
          fill="#e33529"
          letterSpacing="1.8"
        >
          <textPath href="#badgeCirclePath">{text}</textPath>
        </text>
      </svg>
      <span className="text-2xl transition-transform duration-300 group-hover:rotate-12 group-hover:scale-125">
        🐾
      </span>
    </Link>
  )
}
