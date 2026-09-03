export function isHeaderCollapsed(scrollY: number, threshold = 40): boolean {
  return scrollY > threshold;
}

export type MegaMenuItem = { label: string; url: string; query?: Record<string, string> };
export type MegaMenuColumn = { title: string; items: MegaMenuItem[] };

export function buildShopMegaMenu(rawItems: { label: string; url: string; category: string; query?: Record<string, string> }[]): MegaMenuColumn[] {
  const grouped = rawItems.reduce((acc, item) => {
    if (!acc[item.category]) acc[item.category] = [];
    acc[item.category].push({ label: item.label, url: item.url, query: item.query });
    return acc;
  }, {} as Record<string, MegaMenuItem[]>);
  
  return Object.keys(grouped).map(k => ({ title: k, items: grouped[k] }));
}
