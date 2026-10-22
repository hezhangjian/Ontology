import {useEffect, useState} from 'react';
import {LanguageToggle} from '../language-toggle/LanguageToggle';
import {ObjectTypeToolbar} from './ObjectTypeToolbar';
import './ObjectTypePage.css';

export type ObjectTypeView = 'graph' | 'list';

function getViewFromUrl(): ObjectTypeView {
  return new URLSearchParams(window.location.search).get('view') === 'graph' ? 'graph' : 'list';
}

export function ObjectTypePage() {
  const [view, setView] = useState<ObjectTypeView>(getViewFromUrl);

  useEffect(() => {
    const syncView = () => {
      const nextView = getViewFromUrl();
      const url = new URL(window.location.href);
      if (url.searchParams.get('view') !== nextView) {
        url.searchParams.set('view', nextView);
        window.history.replaceState({}, '', url);
      }
      setView(nextView);
    };
    syncView();
    window.addEventListener('popstate', syncView);
    return () => window.removeEventListener('popstate', syncView);
  }, []);

  const changeView = (nextView: ObjectTypeView) => {
    const url = new URL(window.location.href);
    url.searchParams.set('view', nextView);
    window.history.pushState({}, '', url);
    setView(nextView);
  };

  return (
    <section className="object-type-page">
      <header className="object-type-header">
        <ObjectTypeToolbar onViewChange={changeView} view={view} />
        <div className="object-type-header-divider" />
        <LanguageToggle />
      </header>
      <div className={`object-type-content is-${view}`} data-view={view} />
    </section>
  );
}
