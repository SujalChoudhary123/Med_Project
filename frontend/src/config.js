// Where the Java API lives.
//
//  - Local development: nothing to set, defaults to http://localhost:8080
//  - Production behind one domain (Caddy/Nginx proxying /api): set
//    VITE_API_URL= (empty) so the browser calls the same site.
//  - API on a different domain: VITE_API_URL=https://api.example.com
const configured = import.meta.env.VITE_API_URL;

export const API_ORIGIN = (
  configured === undefined ? "http://localhost:8080" : configured
).replace(/\/+$/, "");

/** api("/api/medicines") -> full URL for the current environment. */
export const api = (path) => `${API_ORIGIN}${path}`;
