// Service Worker — enables offline access + PWA installability
const CACHE = 'photoswipe-v1';
const ASSETS = [
  '/',
  '/index.html',
  '/manifest.json'
];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE).then(c => c.addAll(ASSETS).catch(() => {}))
  );
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', e => {
  // Only cache same-origin navigation and static assets; skip blob: URLs
  if (e.request.url.startsWith('blob:')) return;

  e.respondWith(
    caches.match(e.request).then(cached => {
      const fetched = fetch(e.request).then(res => {
        if (res.ok && e.request.method === 'GET') {
          const clone = res.clone();
          caches.open(CACHE).then(c => c.put(e.request, clone));
        }
        return res;
      }).catch(() => cached);
      return cached || fetched;
    })
  );
});

// Handle share target — store shared files for the app to read
self.addEventListener('fetch', e => {
  if (e.request.method === 'POST' && e.request.url.includes('/share-target')) {
    e.respondWith(
      (async () => {
        const formData = await e.request.formData();
        const files = formData.getAll('photos');
        const client = await self.clients.get(e.resultingClientId || (await self.clients.matchAll())[0]?.id);
        if (client) {
          client.postMessage({ type: 'shared-files', files });
        }
        return Response.redirect('/?shared=true', 303);
      })()
    );
  }
});
