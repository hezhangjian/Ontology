# Portal

The portal workspace contains the frontend application shell and user-facing UI code.

## Directory Layout

- `portal/components/`: React components for the portal shell, pages, and local UI pieces. Give each component its own directory, and keep component-only CSS beside that component.
- `portal/i18n/`: locale setup and translation resources. User-facing strings should be added here and read through `react-i18next`.
- `portal/types/`: TypeScript types for portal data shapes, component contracts, and UI state.
