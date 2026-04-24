// Service Worker ReconDoc MADAEF.
//
// Strategie de cache (sans rien casser pour les ecritures) :
//   - assets immutable (/assets/*, *.js, *.css, *.svg, *.woff2, .png, .jpg) :
//     CacheFirst, cache 1 an. Ces fichiers sont hashes par Vite, tout
//     changement de contenu change le nom -> 0 risque de servir du JS
//     obsolete apres deploiement.
//   - GET /api/* : StaleWhileRevalidate. On rend le cache immediatement,
//     on revalide en arriere-plan, on ecrit la nouvelle reponse en cache
//     pour la prochaine fois. Resultat: navigation entre pages quasi
//     instantanee meme avec une connexion lente.
//   - autres methodes (POST/PATCH/PUT/DELETE) : NetworkOnly (jamais en
//     cache). On invalide les entrees /api/dossiers/<id>/* apres une
//     mutation pour eviter de servir une donnee obsolete.
//   - tout le reste (index.html, navigations) : NetworkFirst avec fallback
//     cache pour offrir un mode dégradé hors ligne.

const VERSION = 'v1'
const ASSET_CACHE = `assets-${VERSION}`
const API_CACHE = `api-${VERSION}`

self.addEventListener('install', (event) => {
  // Activation immediate sans attendre fermeture des onglets
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    // Purge des anciens caches versionnes
    const keys = await caches.keys()
    await Promise.all(keys.filter(k => k !== ASSET_CACHE && k !== API_CACHE).map(k => caches.delete(k)))
    await self.clients.claim()
  })())
})

function isAsset(url) {
  return url.pathname.startsWith('/assets/') ||
    /\.(js|mjs|css|svg|png|jpg|jpeg|webp|woff2?|ico)$/.test(url.pathname)
}

function isApiGet(request, url) {
  return request.method === 'GET' && url.pathname.startsWith('/api/')
}

function isMutation(request) {
  return request.method !== 'GET' && request.method !== 'HEAD'
}

async function cacheFirst(request, cacheName) {
  const cache = await caches.open(cacheName)
  const hit = await cache.match(request)
  if (hit) return hit
  const res = await fetch(request)
  if (res.ok) cache.put(request, res.clone())
  return res
}

async function staleWhileRevalidate(request, cacheName) {
  const cache = await caches.open(cacheName)
  const cached = await cache.match(request)
  const networkPromise = fetch(request).then(res => {
    if (res.ok) cache.put(request, res.clone())
    return res
  }).catch(() => cached)
  // Si on a une copie en cache, on la sert tout de suite (UI instantanee).
  // La revalidation se fait en arriere-plan.
  return cached || networkPromise
}

async function networkFirst(request, cacheName) {
  try {
    const res = await fetch(request)
    if (res.ok) {
      const cache = await caches.open(cacheName)
      cache.put(request, res.clone())
    }
    return res
  } catch (err) {
    const cache = await caches.open(cacheName)
    const cached = await cache.match(request)
    if (cached) return cached
    throw err
  }
}

/**
 * Apres une mutation sur /api/dossiers/:id/*, on purge les entrees cache
 * du meme dossier. Sans ca, le SW continuerait a servir une vieille copie
 * du snapshot ou des documents pendant 30s (TTL stale). Comportement
 * voulu : la prochaine GET retape le reseau pour avoir la verite.
 */
async function invalidateDossierCache(url) {
  const match = url.pathname.match(/^\/api\/dossiers\/([0-9a-f-]{36})/i)
  if (!match) {
    // Mutation hors dossier (ex: settings, bulk) -> on purge tout le cache API
    await caches.delete(API_CACHE)
    return
  }
  const id = match[1]
  const cache = await caches.open(API_CACHE)
  const requests = await cache.keys()
  await Promise.all(
    requests
      .filter(req => new URL(req.url).pathname.includes(`/api/dossiers/${id}`) ||
                     new URL(req.url).pathname === `/api/dossiers` ||
                     new URL(req.url).pathname === `/api/dossiers/stats`)
      .map(req => cache.delete(req))
  )
}

self.addEventListener('fetch', (event) => {
  const request = event.request
  const url = new URL(request.url)

  // Ne s'occupe que des requetes same-origin. Les CDN externes (Mistral,
  // Anthropic) ne passent pas par le navigateur cote SPA, mais on est
  // explicite par securite.
  if (url.origin !== self.location.origin) return

  // Mutations : passe-plat reseau + invalidation cache derriere.
  if (isMutation(request)) {
    event.respondWith((async () => {
      const res = await fetch(request)
      // On invalide meme si !res.ok : un 4xx peut quand meme avoir touche la donnee
      try { await invalidateDossierCache(url) } catch (_) {}
      return res
    })())
    return
  }

  // SSE / streaming : ne JAMAIS cacher (sinon stream casse).
  if (request.headers.get('Accept')?.includes('text/event-stream')) {
    return // laisser le navigateur faire son fetch normal
  }

  if (isAsset(url)) {
    event.respondWith(cacheFirst(request, ASSET_CACHE))
    return
  }

  if (isApiGet(request, url)) {
    event.respondWith(staleWhileRevalidate(request, API_CACHE))
    return
  }

  // index.html / navigations : network-first pour eviter une vieille shell HTML
  if (request.mode === 'navigate') {
    event.respondWith(networkFirst(request, ASSET_CACHE))
    return
  }
})

// Bouton "vider le cache" depuis le frontend (Settings)
self.addEventListener('message', (event) => {
  if (event.data === 'clear-cache') {
    event.waitUntil((async () => {
      const keys = await caches.keys()
      await Promise.all(keys.map(k => caches.delete(k)))
    })())
  }
})
