export const DEFAULT_ONTOLOGY_ID = '00000000-0000-0000-0000-00000000a001';
const STORAGE_KEY = 'ontology.active-id';
const CHANGE_EVENT = 'ontology:active-changed';

export function activeOntologyId() {
  return window.localStorage.getItem(STORAGE_KEY) ?? DEFAULT_ONTOLOGY_ID;
}

export function setActiveOntologyId(id: string) {
  if (id === activeOntologyId()) return;
  window.localStorage.setItem(STORAGE_KEY, id);
  window.dispatchEvent(new CustomEvent(CHANGE_EVENT, { detail: id }));
}

export function onActiveOntologyChanged(listener: (id: string) => void) {
  const handleChange = (event: Event) => listener((event as CustomEvent<string>).detail);
  window.addEventListener(CHANGE_EVENT, handleChange);
  return () => window.removeEventListener(CHANGE_EVENT, handleChange);
}
