// Ảnh đại diện thú cưng dùng chung cho lễ tân và khách: ảnh nếu có, không thì biểu tượng theo loài.
import { Cat, Dog, PawPrint } from 'lucide-react';
import type { Species } from '@/domains/pets/labels';

const SPECIES_BG: Record<Species, string> = {
  DOG: 'bg-amber-100',
  CAT: 'bg-orange-100',
  OTHER: 'bg-emerald-100',
};

const SPECIES_ICON = { DOG: Dog, CAT: Cat, OTHER: PawPrint } as const;

/** Biểu tượng theo loài (không dùng emoji). */
export function SpeciesIcon({ species, className }: { species: Species; className?: string }) {
  const Icon = SPECIES_ICON[species];
  return <Icon aria-hidden className={className} />;
}

export function PetAvatar({ pet, size = 'md' }: { pet: { name: string; species: Species; photoUrl?: string | null }; size?: 'md' | 'lg' }) {
  const box = size === 'lg' ? 'h-16 w-16' : 'h-12 w-12';
  if (pet.photoUrl) {
    return <img src={pet.photoUrl} alt={pet.name} className={`${box} shrink-0 rounded-xl object-cover`} loading="lazy" />;
  }
  return (
    <span aria-hidden className={`${box} ${SPECIES_BG[pet.species]} flex shrink-0 items-center justify-center rounded-xl`}>
      <SpeciesIcon species={pet.species} className={size === 'lg' ? 'h-8 w-8 text-stone-700' : 'h-6 w-6 text-stone-700'} />
    </span>
  );
}
