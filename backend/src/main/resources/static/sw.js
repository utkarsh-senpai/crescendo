/*
 * Crescendo service worker — offline-first app shell.
 *
 * Strategy: cache-first for the static shell (HTML/CSS/JS/icons) so the installed PWA opens
 * instantly and works offline; network-first (never cached) for /api calls so game state is always
 * live. On a new deploy, bump SHELL_VERSION to invalidate the old shell cache.
 */
const SHELL_VERSION = 'crescendo-shell-v1.7.1';
const SHELL = [
  '/',
  '/index.html',
  '/styles.css',
  '/app.js',
  '/manifest.webmanifest',
  '/icons/icon.svg',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL_VERSION).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== SHELL_VERSION).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);

  // API is always live — never serve a stale game from cache.
  if (url.pathname.startsWith('/api')) {
    return; // fall through to network
  }

  // App shell: cache-first, fall back to network, then to the cached index for navigations.
  event.respondWith(
    caches.match(request).then((cached) => {
      if (cached) return cached;
      return fetch(request)
        .then((resp) => {
          if (resp.ok && (url.origin === self.location.origin)) {
            const copy = resp.clone();
            caches.open(SHELL_VERSION).then((cache) => cache.put(request, copy));
          }
          return resp;
        })
        .catch(() => (request.mode === 'navigate' ? caches.match('/index.html') : undefined));
    })
  );
});
