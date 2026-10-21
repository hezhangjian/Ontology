import {useEffect, useState} from 'react';
import {ChevronDown, Plus, X} from 'lucide-react';
import {useTranslation} from 'react-i18next';
import {createOntology as createOntologyRequest, fetchOntologies} from '../../api/ontology';
import type {OntologyItem} from '@/types/ontology';
import './OntologySwitcher.css';

type OntologySwitcherProps = {
  collapsed: boolean;
  onExpand: () => void;
};

export function OntologySwitcher({collapsed, onExpand}: OntologySwitcherProps) {
  const [ontologies, setOntologies] = useState<OntologyItem[]>([]);
  const [selectedOntologyId, setSelectedOntologyId] = useState<string | null>(null);
  const [ontologyDropdownOpen, setOntologyDropdownOpen] = useState(false);
  const [createOntologyOpen, setCreateOntologyOpen] = useState(false);
  const [createErrors, setCreateErrors] = useState({id: '', name: '', request: ''});
  const [createValues, setCreateValues] = useState({description: '', id: '', name: ''});
  const [creating, setCreating] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const {t} = useTranslation();
  const selectedOntology = selectedOntologyId ? ontologies.find((item) => item.id === selectedOntologyId) : undefined;

  useEffect(() => {
    let disposed = false;

    const load = async () => {
      try {
        const items = await fetchOntologies();
        if (disposed) {
          return;
        }

        setOntologies(items);
        setSelectedOntologyId((currentId) =>
          currentId && items.some((item) => item.id === currentId) ? currentId : (items[0]?.id ?? null)
        );
        setLoadError('');
      } catch {
        if (!disposed) {
          setLoadError(t('ontology.loadFailed'));
        }
      } finally {
        if (!disposed) {
          setLoading(false);
        }
      }
    };

    void load();
    return () => {
      disposed = true;
    };
  }, [t]);

  const chooseOntology = (item: OntologyItem) => {
    setSelectedOntologyId(item.id);
    setOntologyDropdownOpen(false);
  };

  const createOntology = async () => {
    const id = createValues.id.trim();
    const name = createValues.name.trim();
    const description = createValues.description.trim();
    const nextErrors = {
      id: id ? '' : t('ontology.idRequired'),
      name: name ? '' : t('ontology.nameRequired'),
      request: '',
    };

    if (nextErrors.id || nextErrors.name) {
      setCreateErrors(nextErrors);
      return;
    }

    setCreating(true);
    try {
      const nextOntology = await createOntologyRequest({description, id, name});
      setOntologies((currentItems) => [...currentItems.filter((item) => item.id !== nextOntology.id), nextOntology]);
      chooseOntology(nextOntology);
      setCreateValues({description: '', id: '', name: ''});
      setCreateErrors({id: '', name: '', request: ''});
      setCreateOntologyOpen(false);
    } catch {
      setCreateErrors((currentErrors) => ({...currentErrors, request: t('ontology.createFailed')}));
    } finally {
      setCreating(false);
    }
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
          title={collapsed ? (selectedOntology?.name ?? t('ontology.unselected')) : undefined}
          type="button"
        >
          {!collapsed && (
            <>
              <span className="ontology-trigger-name">{selectedOntology?.name ?? t('ontology.unselected')}</span>
              <ChevronDown aria-hidden="true" className="ontology-trigger-arrow" size={15} />
            </>
          )}
          {collapsed && (
            <span className="ontology-collapsed-name" aria-hidden="true">
              {selectedOntology?.name.slice(0, 1) ?? '-'}
            </span>
          )}
        </button>
        {ontologyDropdownOpen && !collapsed && (
          <div className="ontology-menu">
            {loading && <div className="ontology-menu-message">{t('ontology.loading')}</div>}
            {!loading && loadError && <div className="ontology-menu-message is-error">{loadError}</div>}
            {!loading && !loadError && ontologies.length > 0 && (
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
              <button
                aria-label={t('ontology.closeCreate')}
                className="modal-close"
                onClick={() => setCreateOntologyOpen(false)}
                type="button"
              >
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
                <span>
                  {t('ontology.id')}
                  <span className="required-mark" aria-hidden="true">
                    *
                  </span>
                </span>
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
                <span>
                  {t('ontology.name')}
                  <span className="required-mark" aria-hidden="true">
                    *
                  </span>
                </span>
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
              {createErrors.request && <small className="field-error">{createErrors.request}</small>}
              <div className="modal-actions">
                <button className="secondary-action" onClick={() => setCreateOntologyOpen(false)} type="button">
                  {t('ontology.cancel')}
                </button>
                <button className="primary-action" disabled={creating} type="submit">
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
