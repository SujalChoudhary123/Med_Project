// MedTrack service worker.
// Keeps the app itself available when the internet drops. It never stores or answers
// /api/ requests: bills, stock and customer data always come live from the server.

const CACHE = "medtrack-shell-v1";
const SHELL = ["/", "/manifest.webmanifest", "/favicon.svg", "/icon-192.png", "/offline.html"];

self.addEventListener("install", (event) => {
  event.waitUntil(
    (async () => {
      const cache = await caches.open(CACHE);
      await cache.addAll(SHELL);

      // The built files are named in the page itself. Saving them now means the app
      // opens offline even the first time, before any of them has gone through here.
      const page = await (await fetch("/", { cache: "no-store" })).text();
      const files = [...page.matchAll(/\/assets\/[^"']+/g)].map((match) => match[0]);
      await cache.addAll([...new Set(files)]);

      await self.skipWaiting();
    })()
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((names) => Promise.all(names.filter((name) => name !== CACHE).map((name) => caches.delete(name))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  const url = new URL(request.url);

  if (request.method !== "GET" || url.origin !== self.location.origin || url.pathname.startsWith("/api/")) {
    return;
  }

  // Pages: newest version when online, the saved copy when not.
  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(CACHE).then((cache) => cache.put("/", copy));
          }
          return response;
        })
        .catch(async () => (await caches.match("/")) || caches.match("/offline.html"))
    );
    return;
  }

  // Built files have the version in their name, so a saved copy is always right.
  if (url.pathname.startsWith("/assets/")) {
    event.respondWith(
      caches.match(request).then(
        (saved) =>
          saved ||
          fetch(request).then((response) => {
            if (response.ok) {
              const copy = response.clone();
              caches.open(CACHE).then((cache) => cache.put(request, copy));
            }
            return response;
          })
      )
    );
    return;
  }

  event.respondWith(fetch(request).catch(() => caches.match(request)));
});
