import type {OntologyItem} from '@/types/ontology';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:4242';
const ONTOLOGY_LOAD_RETRY_DELAYS = [500, 1000, 2000, 4000, 8000, 10000];

type OntologyResponse = {
  description?: string | null;
  id: string;
  name: string;
};

type OntologyPageResponse = {
  items?: OntologyResponse[];
};

export type CreateOntologyInput = {
  description: string;
  id: string;
  name: string;
};

function toOntologyItem(ontology: OntologyResponse): OntologyItem {
  return {
    description: ontology.description ?? '',
    id: ontology.id,
    name: ontology.name,
  };
}

async function readError(response: Response) {
  try {
    const body = (await response.json()) as {message?: string};
    return body.message ?? `Request failed with status ${response.status}`;
  } catch {
    return `Request failed with status ${response.status}`;
  }
}

async function wait(delay: number) {
  await new Promise((resolve) => setTimeout(resolve, delay));
}

export async function fetchOntologies(): Promise<OntologyItem[]> {
  for (let attempt = 0; ; attempt += 1) {
    let response: Response;
    try {
      response = await fetch(`${API_BASE_URL}/v1/ontologies?limit=100&offset=0`);
    } catch (error) {
      if (attempt >= ONTOLOGY_LOAD_RETRY_DELAYS.length) {
        throw error;
      }

      await wait(ONTOLOGY_LOAD_RETRY_DELAYS[attempt]);
      continue;
    }

    if (response.ok) {
      const page = (await response.json()) as OntologyPageResponse;
      return (page.items ?? []).map(toOntologyItem);
    }

    if (response.status < 500 || attempt >= ONTOLOGY_LOAD_RETRY_DELAYS.length) {
      throw new Error(await readError(response));
    }

    await wait(ONTOLOGY_LOAD_RETRY_DELAYS[attempt]);
  }
}

export async function createOntology(input: CreateOntologyInput): Promise<OntologyItem> {
  const response = await fetch(`${API_BASE_URL}/v1/ontologies`, {
    body: JSON.stringify(input),
    headers: {'Content-Type': 'application/json'},
    method: 'POST',
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }

  return toOntologyItem((await response.json()) as OntologyResponse);
}
