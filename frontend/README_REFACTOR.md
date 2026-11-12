# MedTrack Frontend Refactor

This package contains the frontend from the supplied ZIP, reorganized into separate React components while preserving the existing UI, CSS, page behavior, and API calls.

## Main changes
- `src/App.jsx` now contains the application shell, shared state, navigation, and page selection.
- Authentication components live in `src/components/auth/`.
- Dashboard and its `StatCard` live in `src/components/dashboard/`.
- Inventory and medicine form/modal components live in `src/components/inventory/`.
- Sales, alerts, reports, settings, and placeholder components are separated into their own folders.
- Shared configuration and formatting helpers live in `src/utils/appUtils.js`.
- Existing `App.css`, `index.css`, assets, and project configuration are retained.

## Run locally
From the `frontend` directory:
```bash
npm install
npm run dev
```

## Validation notes
All 23 JavaScript/JSX files passed Babel parser syntax checks and relative import paths were checked.
The production build could not be confirmed in this environment because the ZIP's installed Vite/Rolldown dependencies do not include the native binding required by this operating system. Install dependencies on your machine with `npm install` before running `npm run build`.
The lint command still reports existing React hook/style issues in page components; those were not broadly rewritten as part of this structural refactor.
