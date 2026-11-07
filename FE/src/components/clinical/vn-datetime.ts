// Ô `datetime-local` không mang múi giờ: hiểu là giờ Việt Nam (+07:00), không theo máy (docs/time.md).
const VN_OFFSET_MS = 7 * 60 * 60 * 1000;

export const vnLocalToIso = (value: string) => new Date(`${value.length === 16 ? `${value}:00` : value}+07:00`).toISOString();

/** Giờ hiện tại theo Việt Nam, dạng `yyyy-MM-ddTHH:mm` cho ô `datetime-local`. */
export const nowVnLocal = (atMs: number = Date.now()) => new Date(atMs + VN_OFFSET_MS).toISOString().slice(0, 16);
