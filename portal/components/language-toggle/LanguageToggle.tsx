import {useTranslation} from 'react-i18next';
import './LanguageToggle.css';

export function LanguageToggle() {
  const {i18n, t} = useTranslation();
  const activeLanguage = i18n.resolvedLanguage === 'zh-CN' ? 'zh-CN' : 'en-US';
  const nextLanguage = activeLanguage === 'zh-CN' ? 'en-US' : 'zh-CN';

  return (
    <button
      aria-label={t('language.switcherLabel')}
      className="language-toggle"
      onClick={() => void i18n.changeLanguage(nextLanguage)}
      type="button"
    >
      {activeLanguage === 'zh-CN' ? t('language.zhCNShort') : t('language.english')}
    </button>
  );
}
