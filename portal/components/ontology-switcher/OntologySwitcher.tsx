import {useState} from 'react';
import {ChevronDown, Plus, X} from 'lucide-react';
import {useTranslation} from 'react-i18next';
import type {OntologyItem} from '../../types/ontology';
import './OntologySwitcher.css';

const ONTOLOGY_ITEMS_KEY = 'ontology.items';
const ONTOLOGY_SELECTED_KEY = 'ontology.active-id';

function loadOntologies(): OntologyItem[] {
  const stored = window.localStorage.getItem(ONTOLOGY_ITEMS_KEY);
  if (!stored) {
    return [];
  }

  try {
    const items = JSON.parse(stored) as OntologyItem[];
    return Array.isArray(items) ? items : [];
  } catch {
    return [];
  }
}

function loadSelectedOntologyId(items: OntologyItem[]) {
  const selectedId = window.localStorage.getItem(ONTOLOGY_SELECTED_KEY);
  return selectedId && items.some((item) => item.id === selectedId) ? selectedId : null;
}

function saveOntologies(items: OntologyItem[]) {
  window.localStorage.setItem(ONTOLOGY_ITEMS_KEY, JSON.stringify(items));
}

type OntologySwitcherProps = {
  collapsed: boolean;
  onExpand: () => void;
};

export function OntologySwitcher({collapsed, onExpand}: OntologySwitcherProps) {
  const [ontologies, setOntologies] = useState<OntologyItem[]>(() => loadOntologies());
  const [selectedOntologyId, setSelectedOntologyId] = useState<string | null>(() => loadSelectedOntologyId(loadOntologies()));
  const [ontologyDropdownOpen, setOntologyDropdownOpen] = useState(false);
  const [createOntologyOpen, setCreateOntologyOpen] = useState(false);
  const [createErrors, setCreateErrors] = useState({id: '', name: ''});
  const [createValues, setCreateValues] = useState({description: '', id: '', name: ''});
  const {t} = useTranslation();
  const selectedOntology = selectedOntologyId ? ontologies.find((item) => item.id === selectedOntologyId) : undefined;

  const chooseOntology = (item: OntologyItem) => {
    setSelectedOntologyId(item.id);
    window.localStorage.setItem(ONTOLOGY_SELECTED_KEY, item.id);
    setOntologyDropdownOpen(false);
  };

  const createOntology = () => {
    const id = createValues.id.trim();
    const name = createValues.name.trim();
    const description = createValues.description.trim();
    const nextErrors = {
      id: id ? '' : t('ontology.idRequired'),
      name: name ? '' : t('ontology.nameRequired'),
    };

    if (nextErrors.id || nextErrors.name) {
      setCreateErrors(nextErrors);
      return;
    }

    const nextOntology = {description, id, name};
    const nextOntologies = [...ontologies.filter((item) => item.id !== id), nextOntology];
    setOntologies(nextOntologies);
    saveOntologies(nextOntologies);
    chooseOntology(nextOntology);
    setCreateValues({description: '', id: '', name: ''});
    setCreateErrors({id: '', name: ''});
    setCreateOntologyOpen(false);
  };

  return (
    <>
      <div className="ontology-switcher">
        <button
          aria-expanded={ontologyDropdownOpen}
          aria-label={t('ontology.switcherLabel')}
          className="ontology-trigger"
          onClick={() => {
            if (collapsed) {
              onExpand();
              setOntologyDropdownOpen(true);
              return;
            }
            setOntologyDropdownOpen((value) => !value);
          }}
          title={collapsed ? selectedOntology?.name ?? t('ontology.unselected') : undefined}
          type="button"
        >
          {!collapsed && (
            <>
              <span className="ontology-trigger-name">{selectedOntology?.name ?? t('ontology.unselected')}</span>
              <ChevronDown aria-hidden="true" className="ontology-trigger-arrow" size={15} />
            </>
          )}
          {collapsed && <span className="ontology-collapsed-name" aria-hidden="true">{selectedOntology?.name.slice(0, 1) ?? '-'}</span>}
        </button>
        {ontologyDropdownOpen && !collapsed && (
          <div className="ontology-menu">
            {ontologies.length > 0 && (
              <div className="ontology-menu-list">
                {ontologies.map((item) => (
                  <button
                    className={item.id === selectedOntology?.id ? 'ontology-option is-active' : 'ontology-option'}
                    key={item.id}
                    onClick={() => chooseOntology(item)}
                    type="button"
                  >
                    <span>{item.name}</span>
                  </button>
                ))}
              </div>
            )}
            <button className="ontology-create-button" onClick={() => setCreateOntologyOpen(true)} type="button">
              <Plus aria-hidden="true" size={15} />
              <span>{t('ontology.new')}</span>
            </button>
          </div>
        )}
      </div>
      {createOntologyOpen && (
        <div className="modal-backdrop">
          <section aria-labelledby="create-ontology-title" className="ontology-modal" role="dialog">
            <div className="modal-header">
              <div>
                <h2 id="create-ontology-title">{t('ontology.new')}</h2>
                <p>{t('ontology.createHelp')}</p>
              </div>
              <button aria-label={t('ontology.closeCreate')} className="modal-close" onClick={() => setCreateOntologyOpen(false)} type="button">
                <X aria-hidden="true" size={18} />
              </button>
            </div>
            <form
              className="ontology-form"
              noValidate
              onSubmit={(event) => {
                event.preventDefault();
                createOntology();
              }}
            >
              <label className={createErrors.id ? 'has-error' : undefined}>
                <span>{t('ontology.id')}<span className="required-mark" aria-hidden="true">*</span></span>
                <input
                  aria-invalid={createErrors.id ? 'true' : 'false'}
                  autoFocus
                  onChange={(event) => {
                    setCreateValues((value) => ({...value, id: event.target.value}));
                    setCreateErrors((value) => ({...value, id: ''}));
                  }}
                  placeholder={t('ontology.idPlaceholder')}
                  value={createValues.id}
                />
                {createErrors.id && <small className="field-error">{createErrors.id}</small>}
              </label>
              <label className={createErrors.name ? 'has-error' : undefined}>
                <span>{t('ontology.name')}<span className="required-mark" aria-hidden="true">*</span></span>
                <input
                  aria-invalid={createErrors.name ? 'true' : 'false'}
                  onChange={(event) => {
                    setCreateValues((value) => ({...value, name: event.target.value}));
                    setCreateErrors((value) => ({...value, name: ''}));
                  }}
                  placeholder={t('ontology.namePlaceholder')}
                  value={createValues.name}
                />
                {createErrors.name && <small className="field-error">{createErrors.name}</small>}
              </label>
              <label>
                <span>{t('ontology.description')}</span>
                <textarea
                  onChange={(event) => setCreateValues((value) => ({...value, description: event.target.value}))}
                  placeholder={t('ontology.descriptionPlaceholder')}
                  rows={4}
                  value={createValues.description}
                />
              </label>
              <div className="modal-actions">
                <button className="secondary-action" onClick={() => setCreateOntologyOpen(false)} type="button">
                  {t('ontology.cancel')}
                </button>
                <button className="primary-action" type="submit">
                  {t('ontology.create')}
                </button>
              </div>
            </form>
          </section>
        </div>
      )}
    </>
  );
}
