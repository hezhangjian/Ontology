# Portal

Frontend application for the Ontology platform.

## Tech Stack

- **Framework**: React 19 + TypeScript
- **Build Tool**: Vite 7
- **UI Library**: HeroUI
- **HTTP Client**: Axios
- **Routing**: TanStack Router (file-based)

## Architecture

```
portal/
  main.tsx              # Application entry point
  App.tsx               # Root component with router and theme providers
  features/             # Feature modules (domain-scoped)
    aip/                # AI assistant features
    applications/       # Dashboard and application features
    ontology/           # Ontology modeling and exploration
  pages/                # Top-level pages
    data-connections/   # Data connection management
    datasets/           # Dataset management
    pipelines/          # Pipeline editor and management
  shared/               # Shared components and utilities
    components/         # Reusable UI components
    styles/             # Global styles
    utils/              # Shared utilities
```

## Development

```bash
pnpm install
pnpm dev
```

## Feature Modules

### Ontology Modeling
- Resource CRUD (list, create, edit, view)
- Property catalog
- Ontology health monitoring

### Ontology Explorer
- Global object search
- Object detail and exploration views
- Saved resources

### Dashboards
- Dashboard editor with widget palette
- Runtime rendering with filter support
- Data source configuration

### Pipelines
- Visual DAG editor
- Node configuration and field mapping
- Pipeline execution and monitoring

### Data Connections
- Connection CRUD
- Type-specific configuration forms

### AI Conversations
- Conversation center
- Message history and context
