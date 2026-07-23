export const DEFAULT_ONTOLOGY_ID = '00000000-0000-0000-0000-00000000a001';
const STORAGE_KEY = 'ontology.active-id';

export function activeOntologyId() {
  return window.localStorage.getItem(STORAGE_KEY) ?? DEFAULT_ONTOLOGY_ID;
}

export function setActiveOntologyId(id: string) {
  window.localStorage.setItem(STORAGE_KEY, id);
}
