import {useState} from 'react';
import {PanelLeftClose, PanelLeftOpen} from 'lucide-react';
import {useTranslation} from 'react-i18next';
import {LanguageToggle} from './components/language-toggle/LanguageToggle';
import {AppNavigation} from './components/app-navigation/AppNavigation';
import {OntologySwitcher} from './components/ontology-switcher/OntologySwitcher';

function App() {
  const [collapsed, setCollapsed] = useState(false);
  const {t} = useTranslation();

  return (
    <div className="app-shell">
      <aside className={collapsed ? 'sidebar is-collapsed' : 'sidebar'}>
        <button
          aria-label={collapsed ? t('navigation.expandSidebar') : t('navigation.collapseSidebar')}
          className="sidebar-toggle"
          onClick={() => setCollapsed((value) => !value)}
          type="button"
        >
          {collapsed ? <PanelLeftOpen size={22} /> : <PanelLeftClose size={22} />}
        </button>
        <div className="sidebar-top">
          {!collapsed && (
            <div className="sidebar-brand">
              <img className="brand-mark" src="/ontology-icon.svg" alt="" aria-hidden="true" />
              <h1 className="sidebar-title">Ontology</h1>
            </div>
          )}
          {!collapsed && <OntologySwitcher collapsed={collapsed} onExpand={() => setCollapsed(false)} />}
          <div className="sidebar-divider" />
        </div>
        <AppNavigation collapsed={collapsed} />
      </aside>
      <main className="app-content">
        <header className="app-header">
          <LanguageToggle />
        </header>
      </main>
    </div>
  );
}

export default App;
