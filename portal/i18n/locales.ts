export const supportedLocales = ['en-US', 'zh-CN'] as const;

export type SupportedLocale = (typeof supportedLocales)[number];

export const defaultLocale: SupportedLocale = 'en-US';

export function normalizeLocale(locale?: string | null): SupportedLocale {
  if (!locale) {
    return defaultLocale;
  }

  const normalizedLocale = locale.toLowerCase();

  if (normalizedLocale.startsWith('zh')) {
    return 'zh-CN';
  }

  if (normalizedLocale.startsWith('en')) {
    return 'en-US';
  }

  return defaultLocale;
}
