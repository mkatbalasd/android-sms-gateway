#!/usr/bin/env node
'use strict';
// Mnaseb SIM e2e stub: a dependency-free stand-in for the private platform (account API under
// /v1 and the gateway API under /api/mobile/v1). Wire shapes are copied from the app's JVM
// tests (AccountApiTest.kt, FakeAppClient.kt) and models (AccountModels.kt, AppModels.kt,
// GatewayApi.kt). It exists because the platform's private images cannot be pulled by the
// public fork's CI runners. Test data only; nothing here is a secret.
//
// Usage: node server.js [port]   (default 8787, binds 0.0.0.0 so 10.0.2.2 reaches it)
// Control API (/__control/*) is for the test harness, never called by the app.

const http = require('http');
const crypto = require('crypto');

const PORT = Number(process.argv[2] || process.env.STUB_PORT || 8787);
const OTP = '123456';
const LOGIN_CODE = 'e2eCode1';
const NONCE = 'E2EN0NCE';
const SIGNING_KEY = 'E2E-SIGNING-KEY-0123456789abcdef0123';
const PLATFORM_NUMBER = '+10000000001';
const TEST_RECIPIENT = { id: 'r1', label: 'Owner', msisdn_masked: '+1******000', verified_at: '2026-11-10T10:00:00.000Z' };

function fresh() {
  return {
    signups: [], logins: [], verifies: [],
    enrolment: null, enrolmentComplete: null,
    device: null, devicePatches: 0,
    lines: [],
    paused: false, pauseCalls: 0, resumeCalls: 0,
    testMessagePosts: [],
    queue: [],            // gateway messages waiting for the phone
    messageReports: [],   // PATCH /message bodies, in order
    inbox: [],            // POST /inbox items, in order
    heartbeats: [],       // ISO time of every authenticated GET /message
    sse: { connections: 0, open: 0, lastConnectAt: null },
    settingsServed: 0,    // GET /settings answers (the app imports the E2E passphrase from them)
    smsRequests: [],      // for the host watcher: inbound SMS to fire at the emulator
    requests: [],         // "METHOD path" log, no bodies
  };
}
let S = fresh();
const sseClients = new Set();

const now = () => new Date().toISOString();
const id = (p) => `${p}-${crypto.randomBytes(4).toString('hex')}`;

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8');
      try { resolve(text ? JSON.parse(text) : null); } catch (e) { resolve({ __invalid: true }); }
    });
  });
}

function send(res, status, body) {
  const text = body === undefined ? '' : (typeof body === 'string' ? body : JSON.stringify(body));
  res.writeHead(status, {
    'Content-Type': typeof body === 'string' ? 'text/plain; charset=utf-8' : 'application/json',
    'Content-Length': Buffer.byteLength(text),
  });
  res.end(text);
}
const fail = (res, status, code, message, details) =>
  send(res, status, { success: false, error: { code, message, ...(details ? { details } : {}) } });

const hasBearer = (req) => /^Bearer .+/.test(req.headers.authorization || '');

function otpInfo() {
  return {
    channel: 'SMS', sender: PLATFORM_NUMBER, sent_to_masked: '+1******000',
    expires_at: new Date(Date.now() + 600000).toISOString(),
    resend_after: new Date(Date.now() + 1000).toISOString(),
  };
}
const session = () => ({ access: 'e2e-access', refresh: 'e2e-refresh', expires_in: 900, session_id: 's1' });
function enrolment() {
  S.enrolment = {
    login_code: LOGIN_CODE, code_expires_at: new Date(Date.now() + 600000).toISOString(),
    label: 'Mnaseb SIM/e2e/' + NONCE, signing_key: SIGNING_KEY, nonce: NONCE, device_id: 'gw-dev-1',
    // server_url deliberately omitted: the app checks https+host of an offered URL.
  };
  return S.enrolment;
}
const lineView = (l) => ({
  id: l.id, slot: l.slot, msisdn_masked: '+1******000', status: l.status, method: 'MO',
  strength: 'STRONG', verified_at: l.verified_at,
});

function sseWrite(event, data) {
  for (const c of sseClients) c.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
}
function enqueue(to, text) {
  const m = {
    id: id('msg'), textMessage: { text }, phoneNumbers: [to], simNumber: null,
    withDeliveryReport: true, isEncrypted: false, state: 'Pending', createdAt: now(),
  };
  S.queue.push(m);
  sseWrite('MessageEnqueued', {});
  return m;
}

async function handle(req, res) {
  const url = new URL(req.url, 'http://stub');
  const path = url.pathname;
  const method = req.method;
  S.requests.push(`${method} ${path}`);

  // ---- control API
  if (path.startsWith('/__control/')) return control(req, res, path, method, url);

  // ---- account API
  if (path.startsWith('/v1/')) {
    const p = path.slice(3);
    const body = ['POST', 'PATCH', 'PUT'].includes(method) ? await readBody(req) : null;
    return account(req, res, method, p, body);
  }

  // ---- gateway API
  if (path.startsWith('/api/mobile/v1/')) {
    const p = path.slice('/api/mobile/v1'.length);
    const body = ['POST', 'PATCH', 'PUT'].includes(method) ? await readBody(req) : null;
    return gateway(req, res, method, p, body);
  }
  return fail(res, 404, 'NOT_FOUND', 'no such route');
}

function account(req, res, method, p, body) {
  if (body && body.__invalid) return fail(res, 400, 'VALIDATION_ERROR', 'bad json');
  const needsBearer = p.startsWith('/app/') && !['/app/login', '/app/login/verify', '/app/login/resend', '/app/refresh'].includes(p);
  if (needsBearer && !hasBearer(req)) return fail(res, 401, 'APP_SESSION_INVALID', 'no bearer');

  switch (`${method} ${p}`) {
    case 'POST /signup':
      // The password is never stored by the stub.
      S.signups.push({ business_name: body.business_name, contact: body.contact, locale: body.locale, terms_version: body.terms_version });
      return send(res, 202, { signup_id: '11111111-1111-1111-1111-111111111111', otp: otpInfo() });
    case 'POST /signup/resend':
    case 'POST /app/login/resend':
      return send(res, 202, { signup_id: body && body.signup_id, login_id: body && body.login_id, otp: otpInfo() });
    case 'POST /signup/verify':
    case 'POST /app/login/verify': {
      S.verifies.push({ path: p, at: now() });
      if (!body || body.code !== OTP) return fail(res, 400, 'OTP_INVALID', 'wrong code', { attempts_left: 3 });
      return send(res, 201, {
        tenant: { id: 't1', name: 'E2E Business', mode: 'SANDBOX' }, user: { id: 'u1' },
        app_session: session(), enrolment: enrolment(), next: 'ENROL',
      });
    }
    case 'POST /app/login':
      S.logins.push({ at: now() });
      return send(res, 202, { login_id: 'L1', otp: otpInfo() });
    case 'POST /app/refresh':
      return send(res, 201, { app_session: session() });
    case 'POST /app/enrolment':
      return send(res, 200, enrolment());
    case 'POST /app/enrolment/complete': {
      const ok = S.device && body && body.gateway_device_id === S.device.id && body.nonce === NONCE;
      if (!ok) return fail(res, 404, 'ENROLMENT_NOT_LISTED', 'device not listed');
      S.enrolmentComplete = { at: now(), gateway_device_id: body.gateway_device_id };
      return send(res, 200, { ok: true });
    }
    case 'POST /app/lines/verify': {
      const slot = (body && body.slot) || 1;
      let l = S.lines.find((x) => x.slot === slot);
      if (!l) { l = { id: 'l' + slot, slot, status: 'ACTIVE', verified_at: now() }; S.lines.push(l); }
      return send(res, 201, { method: 'MO', to: PLATFORM_NUMBER, text: 'MNASEB E2E', expires_in: 600 });
    }
    case 'GET /app/lines':
      return send(res, 200, { items: S.lines.map(lineView) });
    case 'GET /app/home':
      return send(res, 200, {
        tenant: { id: 't1', name: 'E2E Business', mode: 'SANDBOX', approval_status: 'NONE' },
        balance_minor: 0, sent_today: S.testMessagePosts.length, sandbox_daily_max: 30,
        devices: [{ id: 'd1', name: 'e2e', status: 'ACTIVE', last_seen_at: now(), lines: S.lines.map((l) => ({ slot: l.slot, msisdn_masked: '+1******000', status: l.status })) }],
        test_recipients: { verified: 1, max: 10 }, keep_alive_pending: false,
        paused: S.paused, paused_at: S.paused ? now() : null, pause_hold_max_min: 60,
      });
    case 'GET /app/notices': // not called by the app at this SHA; kept for the platform's shape
      return send(res, 200, { items: [] });
    case 'POST /app/device/pause': S.paused = true; S.pauseCalls++; return send(res, 200, { paused: true });
    case 'POST /app/device/resume': S.paused = false; S.resumeCalls++; return send(res, 200, { paused: false });
    case 'PATCH /app/device': S.devicePatches++; return send(res, 200, { updated: true });
    case 'GET /app/test-recipients':
      return send(res, 200, { items: [TEST_RECIPIENT] });
    case 'POST /app/test-message': {
      const key = req.headers['x-idempotency-key'];
      const dup = S.testMessagePosts.find((t) => t.key === key);
      if (dup) return send(res, 201, { id: dup.messageId, status: 'QUEUED', to_masked: TEST_RECIPIENT.msisdn_masked });
      const m = enqueue('+15550000000', (body && body.text) || 'test');
      S.testMessagePosts.push({ key, messageId: m.id, text: (body && body.text) || '', at: now() });
      return send(res, 201, { id: m.id, status: 'QUEUED', to_masked: TEST_RECIPIENT.msisdn_masked });
    }
    case 'POST /app/handoff': return send(res, 200, { url: 'https://app.mnaseb.com/handoff#t=stub', expires_in: 60 });
    case 'POST /app/unlink': return send(res, 200, { unlinked: true });
    default: {
      const m = /^GET \/app\/messages\/([0-9A-Za-z-]+)$/.exec(`${method} ${p}`);
      if (m) {
        const last = S.messageReports.filter((r) => r.id === m[1]).pop();
        const status = last ? (last.state === 'Failed' ? 'FAILED' : 'SENT') : 'QUEUED';
        return send(res, 200, { id: m[1], to_masked: TEST_RECIPIENT.msisdn_masked, status, not_billed: true, created_at: now() });
      }
      return fail(res, 404, 'NOT_FOUND', 'no such account route');
    }
  }
}

function gateway(req, res, method, p, body) {
  if (body && body.__invalid) return fail(res, 400, 'BAD_REQUEST', 'bad json');
  const auth = req.headers.authorization || '';
  switch (`${method} ${p}`) {
    case 'GET /device': return send(res, 200, { externalIp: '127.0.0.1' });
    case 'POST /device': {
      if (auth !== `Code ${LOGIN_CODE}`) return fail(res, 401, 'UNAUTHORIZED', 'code refused');
      S.device = {
        id: 'gw-dev-1', token: 'e2e-device-token', login: 'e2e-login', name: body.name,
        nameEndsWithNonce: typeof body.name === 'string' && body.name.endsWith('/' + NONCE),
        simCards: (body.simCards || []).length, hasPublicKey: !!body.publicKey, registeredAt: now(),
      };
      return send(res, 201, { id: S.device.id, token: S.device.token, login: S.device.login, password: 'e2e-pass' });
    }
    case 'PATCH /device': S.devicePatches++; return send(res, 204);
    case 'GET /message': {
      if (auth !== 'Bearer e2e-device-token') return fail(res, 401, 'UNAUTHORIZED', 'bad token');
      S.heartbeats.push(now());
      const pending = S.queue.filter((m) => !m.handedOut);
      pending.forEach((m) => { m.handedOut = true; });
      return send(res, 200, pending.map(({ handedOut, ...m }) => m));
    }
    case 'PATCH /message':
      for (const r of Array.isArray(body) ? body : []) S.messageReports.push({ ...r, receivedAt: now() });
      return send(res, 204);
    case 'POST /inbox':
      for (const m of Array.isArray(body) ? body : []) S.inbox.push({ ...m, receivedAt: now() });
      return send(res, 201);
    case 'GET /events': return sse(req, res);
    // The real server hands the phone its E2E passphrase here; without it the app cannot encrypt an
    // inbox upload ("Passphrase is not set") and never posts an inbound SMS.
    case 'GET /settings': S.settingsServed++; return send(res, 200, { encryption: { passphrase: 'e2e-test-passphrase-0001' } });
    case 'GET /webhooks': return send(res, 200, []);
    case 'GET /user/code': return send(res, 200, { code: '123456', validUntil: new Date(Date.now() + 300000).toISOString() });
    case 'PATCH /user/password': return send(res, 204);
    default: return fail(res, 404, 'NOT_FOUND', 'no such gateway route');
  }
}

function sse(req, res) {
  S.sse.connections++; S.sse.open++; S.sse.lastConnectAt = now();
  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
  res.write(': connected\n\n');
  sseClients.add(res);
  // Tell the phone to re-read /settings (as the server does when settings change).
  res.write('event: SettingsUpdated\ndata: {}\n\n');
  const ping = setInterval(() => res.write(': ping\n\n'), 15000);
  req.on('close', () => { clearInterval(ping); sseClients.delete(res); S.sse.open--; });
}

async function control(req, res, path, method, url) {
  const body = method === 'POST' ? await readBody(req) : null;
  switch (`${method} ${path.slice('/__control'.length)}`) {
    case 'GET /health': return send(res, 200, 'ok');
    case 'GET /state': return send(res, 200, { ...S, queue: S.queue.map((m) => ({ id: m.id, handedOut: !!m.handedOut })), requests: undefined, requestsTail: S.requests.slice(-80), requestCount: S.requests.length, sseOpen: S.sse.open });
    case 'GET /requests': return send(res, 200, S.requests);
    case 'GET /inbox': return send(res, 200, S.inbox);
    case 'GET /reports': return send(res, 200, S.messageReports);
    case 'GET /heartbeat': return send(res, 200, { count: S.heartbeats.length, last: S.heartbeats[S.heartbeats.length - 1] || null, sse: S.sse });
    case 'POST /reset': S = fresh(); return send(res, 200, { reset: true });
    case 'POST /inject/outbound': { const m = enqueue(body.to, body.text); return send(res, 201, { id: m.id }); }
    // Inbound SMS cannot be pushed from the stub; the host watcher fires it via `adb emu sms send`.
    case 'POST /inject/inbound-sms': S.smsRequests.push({ from: String(body.from).replace(/[^0-9+]/g, ''), text: String(body.text).replace(/[^0-9A-Za-z ._-]/g, '') }); return send(res, 202, { queued: S.smsRequests.length });
    case 'GET /sms-requests.txt': {
      const r = S.smsRequests.shift();
      return send(res, 200, r ? `${r.from}\t${r.text}\n` : '');
    }
    default: return fail(res, 404, 'NOT_FOUND', 'no such control route');
  }
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((e) => { try { fail(res, 500, 'STUB_ERROR', String(e && e.message)); } catch (_) { /* closed */ } });
});
if (require.main === module) {
  server.listen(PORT, '0.0.0.0', () => console.log(`e2e stub listening on ${PORT}`));
}
module.exports = { server, constants: { OTP, LOGIN_CODE, NONCE, PLATFORM_NUMBER } };
