import {useState} from 'react';
import {PanelLeftClose, PanelLeftOpen} from 'lucide-react';

function App() {
  const [collapsed, setCollapsed] = useState(false);

  return (
    <div className="app-shell">
      <aside className={collapsed ? 'sidebar is-collapsed' : 'sidebar'}>
        {!collapsed && (
          <div className="sidebar-brand">
            <div className="brand-mark">O</div>
            <h1 className="sidebar-title">Ontology</h1>
          </div>
        )}
        <button
          aria-label={collapsed ? "展开侧边栏" : "收起侧边栏"}
          className="sidebar-toggle"
          onClick={() => setCollapsed((value) => !value)}
          type="button"
        >
          {collapsed ? <PanelLeftOpen size={22} /> : <PanelLeftClose size={22} />}
        </button>
        {!collapsed && <div className="sidebar-divider" />}
      </aside>
      <main className="app-content" />
    </div>
  );
}

export default App;
