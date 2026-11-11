// A service worker whose only job is to remove itself and whatever the previous application
// cached in this browser.
//
// Why this exists: a single-page application served from this domain earlier registered a service
// worker. A service worker answers requests before the network does, so it kept serving its own
// cached pages after the site was replaced - a reload, even a hard one, could not get past it.
//
// A browser always revalidates the service worker script itself against the server, so serving
// this file at the old worker's address replaces it. On activation it empties every cache for the
// origin, unregisters itself, and reloads any open tab, which then comes from the network.
//
// It is copied to the usual worker file names by scripts/build-site.py. This site registers no
// service worker of its own, so these files only ever do this one thing, and they are harmless
// once no browser is asking for them any more.

self.addEventListener('install', () => {
  // Take over from the old worker at once rather than waiting for its tabs to close.
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    // Everything the previous application stored for this origin.
    try {
      const names = await caches.keys();
      await Promise.all(names.map((n) => caches.delete(n)));
    } catch (e) {
      // A browser that refuses the cache API still gets the unregister below.
    }

    try {
      await self.registration.unregister();
    } catch (e) {
      // Nothing useful to do; the reload below still shows the real site.
    }

    // Reload open tabs so the page comes from the server instead of from this worker.
    try {
      const windows = await self.clients.matchAll({ type: 'window' });
      for (const w of windows) {
        w.navigate(w.url);
      }
    } catch (e) {
      // Navigating a client is not allowed everywhere; the next manual reload will be clean.
    }
  })());
});

// Until this worker goes away, never answer from a cache: always go to the network.
self.addEventListener('fetch', (event) => {
  event.respondWith(fetch(event.request));
});
