# VerityAI premium UI refresh

## Design direction
- Premium dark-first palette with navy surfaces, indigo/violet accents, restrained gradients, and clear contrast.
- Shared typography, border, focus, surface, and spacing tokens in `frontend/src/styles/globals.css`.
- Responsive application shell with collapsible desktop navigation and a mobile navigation drawer.
- Updated brand mark and consistent page hierarchy.

## Updated areas
- Landing page: refreshed hero, example research workspace preview, feature cards, process and security sections, and CTA.
- Auth: refined login and registration surfaces while preserving form validation and mutation hooks.
- Dashboard: research-focused welcome panel, primary action, and refined recent-document/conversation cards.
- Workspace and documents: shared visual tokens and component treatments applied without changing API calls or upload behavior.
- Conversation: refined message surfaces and sticky composer while retaining the existing send, citation, and query behavior.
- Settings: theme preference controls now update the existing theme provider; profile and password flows remain unchanged.

## Compatibility notes
- Existing routes, API endpoints, authentication/session behavior, React Query keys, upload limits, and backend code are preserved.
- No mock API data or new backend behavior was introduced.
- Default theme is dark; light and system themes remain selectable.
