import {GitBranch, List, Plus, Search} from 'lucide-react';
import {useTranslation} from 'react-i18next';
import type {ObjectTypeView} from './ObjectTypePage';

type ObjectTypeToolbarProps = {
  onViewChange: (view: ObjectTypeView) => void;
  view: ObjectTypeView;
};

export function ObjectTypeToolbar({onViewChange, view}: ObjectTypeToolbarProps) {
  const {t} = useTranslation();

  return (
    <div className="object-type-toolbar">
      <nav aria-label={t('objectTypes.breadcrumbLabel')} className="object-type-breadcrumb">
        <span>{t('navigation.ontologyModeling')}</span>
        <span aria-hidden="true">/</span>
        <strong>{t('objectTypes.title')}</strong>
      </nav>
      <div className="object-type-actions">
        <label className="object-type-search">
          <Search aria-hidden="true" size={18} />
          <span className="sr-only">{t('objectTypes.searchLabel')}</span>
          <input placeholder={t('objectTypes.searchPlaceholder')} type="search" />
        </label>
        <div aria-label={t('objectTypes.viewLabel')} className="object-type-view-switcher" role="group">
          <button
            aria-label={t('objectTypes.listView')}
            aria-pressed={view === 'list'}
            className={view === 'list' ? 'is-active' : undefined}
            onClick={() => onViewChange('list')}
            title={t('objectTypes.listView')}
            type="button"
          >
            <List aria-hidden="true" size={18} />
          </button>
          <button
            aria-label={t('objectTypes.graphView')}
            aria-pressed={view === 'graph'}
            className={view === 'graph' ? 'is-active' : undefined}
            onClick={() => onViewChange('graph')}
            title={t('objectTypes.graphView')}
            type="button"
          >
            <GitBranch aria-hidden="true" size={18} />
          </button>
        </div>
        <button className="object-type-create" type="button">
          <Plus aria-hidden="true" size={18} />
          <span>{t('objectTypes.create')}</span>
        </button>
      </div>
    </div>
  );
}
