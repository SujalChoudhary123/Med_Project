import { API_ORIGIN } from "../config.js";

const TOKEN_KEY = "medtrackToken";
const nativeFetch = window.fetch.bind(window);

// The browser origin our API lives on. With an empty API_ORIGIN (same-site
// production) this is simply the page's own origin.
const apiBase = new URL(API_ORIGIN || "/", window.location.href).origin;

// Adds the session token to every call to our API, stores the token returned
// by login/signup/Google, and returns the user to the login screen when the
// session has expired.
window.fetch = async (input, init = {}) => {
  const rawUrl = typeof input === "string" ? input : input.url;
  const target = new URL(rawUrl, window.location.href);

  // Never attach the token to requests for other sites.
  if (target.origin !== apiBase) return nativeFetch(input, init);

  const token = localStorage.getItem(TOKEN_KEY);
  const headers = new Headers(
    init.headers || (typeof input !== "string" ? input.headers : undefined)
  );
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await nativeFetch(input, { ...init, headers });
  const isAuthCall =
    target.pathname.startsWith("/api/auth/") ||
    target.pathname.startsWith("/api/signup");

  if (isAuthCall && response.ok) {
    try {
      const data = await response.clone().json();
      if (data && data.token) {
        localStorage.setItem(TOKEN_KEY, data.token);
        if (data.role) localStorage.setItem("medtrackRole", data.role);
        localStorage.setItem("medtrackAdmin", data.admin ? "true" : "false");
      }
    } catch {
      /* response was not JSON */
    }
  }

  if (response.status === 401 && !isAuthCall) {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem("medtrackRole");
    localStorage.removeItem("medtrackLoggedIn");
    localStorage.removeItem("medtrackUsername");
    localStorage.removeItem("medtrackAdmin");
    window.location.reload();
  }

  // Plan ended while the app was open: the banner re-checks and explains it.
  if (response.status === 402) {
    window.dispatchEvent(new Event("medtrack-plan-changed"));
  }

  return response;
};
