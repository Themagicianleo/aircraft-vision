// Offline support: cache-first for everything the app loads from its own site, so after the first
// visit it opens instantly and works without internet. The version is stamped in at deploy time,
// so a new model or app version replaces the old cache.
const CACHE = "aircraft-vision-__VERSION__";
const SHELL = ["./", "index.html", "style.css", "app.js", "specs.js", "draw.js", "worker.js", "manifest.webmanifest", "icons/icon-192.png"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.origin !== location.origin) return;
  event.respondWith(
    caches.open(CACHE).then(async (cache) => {
      const hit = await cache.match(event.request, { ignoreSearch: true });
      if (hit) return hit;
      const response = await fetch(event.request);
      if (response.ok) cache.put(event.request, response.clone());
      return response;
    }),
  );
});
