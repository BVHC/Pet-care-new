/**
 * PetCard — card hiển thị thú cưng trong danh sách (/pets).
 * Avatar emoji/ảnh, tên, loài, badge đã mất, cân nặng hiện tại, tuổi.
 */
import { HeartCrack } from 'lucide-react';
import { cn } from '@/lib/utils';
import { SPECIES_EMOJI, SPECIES_LABEL } from '@/domains/pets/labels';

interface Props {
  pet: {
    petId: number;
    name: string;
    species: 'DOG' | 'CAT' | 'OTHER';
    currentWeightKg?: number | null;
    deceasedOn?: string | null;
    birthDate?: string | null;
    photoUrl?: string | null;
  };
  selected?: boolean;
  onClick?: () => void;
  className?: string;
}

function ageLabel(birthDate: string | null | undefined): string {
  if (!birthDate) return '';
  const b = new Date(birthDate);
  const now = new Date();
  const years = now.getFullYear() - b.getFullYear();
  const months = now.getMonth() - b.getMonth();
  const totalMonths = years * 12 + months;
  if (totalMonths < 1) return 'Dưới 1 tháng';
  if (totalMonths < 12) return `${totalMonths} tháng`;
  const y = Math.floor(totalMonths / 12);
  const m = totalMonths % 12;
  return m > 0 ? `${y} tuổi ${m} tháng` : `${y} tuổi`;
}

export function PetCard({ pet, selected, onClick, className }: Props) {
  const isDeceased = !!pet.deceasedOn;

  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'group relative w-full rounded-2xl border bg-white p-4 text-left transition-all hover:shadow-md',
        selected
          ? 'border-(--color-primary) shadow-[0_4px_16px_-4px_rgba(164,51,36,0.25)] ring-2 ring-(--color-primary)/20'
          : 'border-(--color-border-default) hover:border-(--color-primary)/50',
        className,
      )}
    >
      {/* Avatar */}
      <div className="mb-3 flex items-start justify-between">
        <div
          className={cn(
            'flex h-16 w-16 items-center justify-center rounded-2xl text-3xl',
            isDeceased
              ? 'bg-slate-100 grayscale'
              : pet.species === 'DOG'
                ? 'bg-amber-100'
                : pet.species === 'CAT'
                  ? 'bg-orange-100'
                  : 'bg-emerald-100',
          )}
        >
          {pet.photoUrl ? (
            <img src={pet.photoUrl} alt={pet.name} className="h-16 w-16 rounded-2xl object-cover" />
          ) : (
            SPECIES_EMOJI[pet.species]
          )}
        </div>
        {isDeceased && (
          <span className="flex items-center gap-1 rounded-full bg-slate-200 px-2 py-0.5 text-[10px] font-medium text-slate-600">
            <HeartCrack className="h-3 w-3" />
            Đã mất
          </span>
        )}
      </div>

      {/* Info */}
      <p className={cn('truncate font-bold', isDeceased ? 'text-slate-500' : 'text-(--color-text-primary)')}>
        {pet.name}
      </p>
      <p className="mt-0.5 text-xs text-(--color-text-secondary)">
        {SPECIES_LABEL[pet.species]}
      </p>

      <div className="mt-3 flex flex-wrap gap-2">
        {pet.currentWeightKg != null && (
          <span className="rounded-full bg-amber-50 px-2 py-0.5 text-[10px] font-medium text-amber-700">
            {pet.currentWeightKg} kg
          </span>
        )}
        {ageLabel(pet.birthDate) && (
          <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-[10px] font-medium text-emerald-700">
            {ageLabel(pet.birthDate)}
          </span>
        )}
      </div>
    </button>
  );
}
