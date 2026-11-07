import {useState, type MouseEvent} from 'react';
import {PanelLeftClose, PanelLeftOpen} from 'lucide-react';
import {useTranslation} from 'react-i18next';
import {AppNavigation} from './components/app-navigation/AppNavigation';
import {LanguageToggle} from './components/language-toggle/LanguageToggle';
import {ObjectTypePage} from './components/object-type-page/ObjectTypePage';
import {OntologySwitcher} from './components/ontology-switcher/OntologySwitcher';

const SIDEBAR_COLLAPSED_KEY = 'sidebar.collapsed';

function App() {
  const [collapsed, setCollapsed] = useState(() => window.localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === 'true');
  const {t} = useTranslation();
  const objectTypePage = window.location.pathname === '/ontology/object-types';

  const updateCollapsed = (value: boolean) => {
    window.localStorage.setItem(SIDEBAR_COLLAPSED_KEY, String(value));
    setCollapsed(value);
  };

  const expandFromEmptyArea = (event: MouseEvent<HTMLElement>) => {
    if (collapsed && !(event.target as Element).closest('a, button')) {
      updateCollapsed(false);
    }
  };

  return (
    <div className="app-shell">
      <aside className={collapsed ? 'sidebar is-collapsed' : 'sidebar'} onClick={expandFromEmptyArea}>
        <button
          aria-label={collapsed ? t('navigation.expandSidebar') : t('navigation.collapseSidebar')}
          className="sidebar-toggle"
          onClick={() => updateCollapsed(!collapsed)}
          type="button"
        >
          {collapsed ? (
            <>
              <img aria-hidden="true" className="collapsed-sidebar-logo" src="/ontology-icon.svg" alt="" />
              <PanelLeftOpen aria-hidden="true" className="collapsed-sidebar-open-icon" size={22} />
            </>
          ) : <PanelLeftClose aria-hidden="true" size={22} />}
        </button>
        <div className="sidebar-top">
          {!collapsed && (
            <div className="sidebar-brand">
              <img className="brand-mark" src="/ontology-icon.svg" alt="" aria-hidden="true" />
              <h1 className="sidebar-title">Ontology</h1>
            </div>
          )}
          {!collapsed && <OntologySwitcher collapsed={collapsed} onExpand={() => updateCollapsed(false)} />}
          <div className="sidebar-divider" />
        </div>
        <AppNavigation collapsed={collapsed} />
      </aside>
      <main className="app-content">
        {objectTypePage ? (
          <ObjectTypePage />
        ) : (
          <header className="app-header">
            <LanguageToggle />
          </header>
        )}
      </main>
    </div>
  );
}

export default App;
