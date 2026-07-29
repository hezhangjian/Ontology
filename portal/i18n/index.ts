import i18next from 'i18next';
import {initReactI18next} from 'react-i18next';
import {defaultLocale, normalizeLocale} from './locales';
import {resources} from './resources';

void i18next.use(initReactI18next).init({
  fallbackLng: defaultLocale,
  interpolation: {
    escapeValue: false,
  },
  lng: normalizeLocale(navigator.language),
  resources,
});

export {i18next};
