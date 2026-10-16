/**
 * Risk Analysis (RA) service — TrustLens primitive p5
 *
 * Port of the ZT-Tax risk-analysis service. The PEP inside each microservice
 * (zt-common RiskAnalysisFilter) calls POST /analyze synchronously for every
 * protected request and blocks it (403) when the answer is {block: true}.
 *
 * Detectors inherited from ZT-Tax (in-memory sliding window per user):
 *   1. Privilege escalation – a user reading another user's profile (/users/{id})
 *   2. Rate limiting        – per-user requests-per-minute cap
 *   3. Lateral movement     – many distinct routes within the window
 *   4. IP change            – same user seen from a different IP mid-session
 * TrustLens-specific detectors (previously the in-process RiskEvaluator):
 *   5. Device fingerprint   – token presented from a device other than the
 *                             one first registered for the user (replay)
 *   6. Impossible travel    – consecutive positions implying a physically
 *                             impossible speed (forged coordinates)
 */
const express    = require('express');
const promClient = require('prom-client');

const app = express();
app.use(express.json());

promClient.collectDefaultMetrics();
const raDuration = new promClient.Histogram({
  name: 'zt_ra_duration_seconds',
  help: 'Risk Analysis /analyze processing time in seconds',
  buckets: [0.0001, 0.0005, 0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1],
});
const raDecisions = new promClient.Counter({
  name: 'zt_ra_decisions_total',
  help: 'Risk Analysis decisions',
  labelNames: ['decision', 'type'],
});

const PORT              = parseInt(process.env.PORT                   || '9000', 10);
const WINDOW_MS         = parseInt(process.env.ALERT_WINDOW_MS        || '60000', 10);
const LATERAL_THRESHOLD = parseInt(process.env.LATERAL_MOVE_THRESHOLD || '15', 10);
const RATE_LIMIT_RPM    = parseInt(process.env.RATE_LIMIT_RPM         || '100000', 10);
const MAX_SPEED_KMH     = parseFloat(process.env.MAX_SPEED_KMH        || '900');
const MIN_JUMP_KM       = parseFloat(process.env.MIN_JUMP_KM          || '50');
const DEVICE_TTL_MS     = parseInt(process.env.DEVICE_TTL_MS          || '3600000', 10);

// { [userId]: { requests: [{ts, path}], blockedUntil, lastIp, device, deviceTs, lastPos } }
const userState = new Map();

const getOrCreate = (userId) => {
  let s = userState.get(userId);
  if (!s) {
    s = { requests: [], blockedUntil: 0, lastIp: null, device: null, deviceTs: 0, lastPos: null };
    userState.set(userId, s);
  }
  return s;
};

const purgeOld = (requests, now) => requests.filter(r => (now - r.ts) < WINDOW_MS);

// Collapse identifiers so that /users/42 and /users/43 count as one route
const normalizePath = (p) => (p || '')
  .replace(/\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, '/:uuid')
  .replace(/\/users\/[^/]+/, '/users/:id')
  .replace(/\/\d+/g, '/:id');

const haversineKm = (lat1, lon1, lat2, lon2) => {
  const R = 6371.0, rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad, dLon = (lon2 - lon1) * rad;
  const a = Math.sin(dLat / 2) ** 2
          + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
};

const allow = () => ({ block: false });
const block = (type, reason) => ({ block: true, type, reason });

const analyze = ({ userId, device, method, path, ip, lat, lon }) => {
  const now = Date.now();
  const route = normalizePath(path);

  // ── Privilege escalation (horizontal): reading someone else's profile ─────
  const m = /^\/users\/([^/]+)/.exec(path || '');
  if (m && userId && userId !== 'anonymous' && m[1] !== userId) {
    return block('privilege-escalation', `${userId} accessing profile of ${m[1]}`);
  }

  if (!userId) return allow();
  const state = getOrCreate(userId);

  // ── Previously flagged user ───────────────────────────────────────────────
  const wasBlocked = state.blockedUntil !== 0;
  if (state.blockedUntil > now) return block('blocked-user', 'User previously flagged');
  if (wasBlocked) state.lastIp = null;   // block expired: accept a new network anchor
  state.blockedUntil = 0;

  // ── IP change ─────────────────────────────────────────────────────────────
  if (ip && state.lastIp && state.lastIp !== ip) {
    state.blockedUntil = now + 5 * WINDOW_MS;
    return block('ip-change', `Session IP changed from ${state.lastIp} to ${ip}`);
  }
  if (ip) state.lastIp = ip;

  // ── Device fingerprint ────────────────────────────────────────────────────
  if (device) {
    if (!state.device || now - state.deviceTs > DEVICE_TTL_MS) {
      state.device = device;
      state.deviceTs = now;
    } else if (state.device !== device) {
      return block('device-mismatch', `Known device ${state.device}, presented ${device}`);
    }
  }

  // ── Rate limit + lateral movement ─────────────────────────────────────────
  state.requests = purgeOld(state.requests, now);
  state.requests.push({ ts: now, path: route });
  if (state.requests.length > RATE_LIMIT_RPM) {
    state.blockedUntil = now + 5 * WINDOW_MS;
    return block('rate-limit', `User exceeded ${RATE_LIMIT_RPM} rpm`);
  }
  const distinctRoutes = new Set(state.requests.map(r => r.path)).size;
  if (distinctRoutes > LATERAL_THRESHOLD) {
    state.blockedUntil = now + 5 * WINDOW_MS;
    return block('lateral-movement', `Accessed ${distinctRoutes} distinct routes in ${WINDOW_MS}ms`);
  }

  // ── Impossible travel ─────────────────────────────────────────────────────
  if (typeof lat === 'number' && typeof lon === 'number') {
    if (state.lastPos) {
      const km = haversineKm(state.lastPos.lat, state.lastPos.lon, lat, lon);
      const hours = Math.max(now - state.lastPos.ts, 1) / 3_600_000;
      if (km > MIN_JUMP_KM && km / hours > MAX_SPEED_KMH) {
        // keep the previous anchor: a forged position must not become the reference
        return block('impossible-travel', `${km.toFixed(1)} km in ${now - state.lastPos.ts} ms`);
      }
    }
    state.lastPos = { lat, lon, ts: now };
  }

  return allow();
};

app.post('/analyze', (req, res) => {
  const end = raDuration.startTimer();
  let result;
  try {
    result = analyze(req.body || {});
  } catch (e) {
    result = allow();
  }
  end();
  raDecisions.inc({ decision: result.block ? 'block' : 'allow', type: result.type || 'none' });
  res.json(result);
});

app.get('/metrics', async (_req, res) => {
  res.set('Content-Type', promClient.register.contentType);
  res.end(await promClient.register.metrics());
});

app.get('/health', (_req, res) => res.json({ status: 'ok', users: userState.size }));

// Periodic cleanup of stale entries
setInterval(() => {
  const now = Date.now();
  for (const [uid, s] of userState.entries()) {
    s.requests = purgeOld(s.requests, now);
    if (s.requests.length === 0 && s.blockedUntil <= now && now - s.deviceTs > DEVICE_TTL_MS) {
      userState.delete(uid);
    }
  }
}, WINDOW_MS);

app.listen(PORT, () => {
  console.log(`[risk-analysis] :${PORT} window=${WINDOW_MS}ms rate=${RATE_LIMIT_RPM}rpm `
    + `lateral=${LATERAL_THRESHOLD} routes travel>${MAX_SPEED_KMH}km/h`);
});
