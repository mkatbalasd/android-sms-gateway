'use strict';
// Self-test of the stub (no dependencies): node e2e/stub-server/selftest.js
// Runs in CI before any emulator minute is spent.
const assert = require('assert');
const { server, constants } = require('./server');

const post = (base, path, body, headers = {}) => fetch(base + path, {
  method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: body === undefined ? undefined : JSON.stringify(body),
});

server.listen(0, '127.0.0.1', async () => {
  const base = `http://127.0.0.1:${server.address().port}`;
  const v1 = base + '/v1';
  const gw = base + '/api/mobile/v1';
  try {
    assert.strictEqual(await (await fetch(base + '/__control/health')).text(), 'ok');

    // sign-up -> wrong code -> right code -> enrolment
    let r = await post(v1, '/signup', { business_name: 'B', contact: { type: 'MOBILE', value: '+15550000000' }, password: 'pw', locale: 'ar', terms_version: 'd', device: {} });
    assert.strictEqual(r.status, 202);
    r = await post(v1, '/signup/verify', { signup_id: 'x', code: '000000' });
    assert.strictEqual(r.status, 400);
    assert.strictEqual((await r.json()).error.code, 'OTP_INVALID');
    r = await post(v1, '/signup/verify', { signup_id: 'x', code: constants.OTP });
    assert.strictEqual(r.status, 201);
    const verified = await r.json();
    assert.ok(verified.app_session.access && verified.enrolment.login_code);

    // gateway register with the code, then complete the enrolment
    const dev = { name: 'Mnaseb SIM/e2e/' + constants.NONCE, simCards: [], publicKey: 'k' };
    r = await post(gw, '/device', dev, { Authorization: 'Code wrong' });
    assert.strictEqual(r.status, 401);
    r = await post(gw, '/device', dev, { Authorization: 'Code ' + constants.LOGIN_CODE });
    assert.strictEqual(r.status, 201);
    const reg = await r.json();
    r = await post(v1, '/app/enrolment/complete', { gateway_device_id: reg.id, nonce: constants.NONCE }, { Authorization: 'Bearer a' });
    assert.strictEqual(r.status, 200);

    // one test-message post (idempotent) -> one queued message, handed out once
    const key = { Authorization: 'Bearer a', 'X-Idempotency-Key': 'k1' };
    await post(v1, '/app/test-message', { test_recipient_id: 'r1', text: 'hi' }, key);
    await post(v1, '/app/test-message', { test_recipient_id: 'r1', text: 'hi' }, key);
    let state = await (await fetch(base + '/__control/state')).json();
    assert.strictEqual(state.testMessagePosts.length, 1);
    const bearer = { Authorization: 'Bearer ' + reg.token };
    const pulled = await (await fetch(gw + '/message?order=lifo', { headers: bearer })).json();
    assert.strictEqual(pulled.length, 1);
    assert.strictEqual((await (await fetch(gw + '/message', { headers: bearer })).json()).length, 0);
    r = await fetch(gw + '/message', { method: 'PATCH', headers: { ...bearer, 'Content-Type': 'application/json' }, body: JSON.stringify([{ id: pulled[0].id, state: 'Sent', recipients: [], states: {} }]) });
    assert.strictEqual(r.status, 204);

    // inbox + heartbeat + pause
    await post(gw, '/inbox', [{ id: 'i1', type: 'SMS', sender: '5551', content: 'hello' }], bearer);
    await post(v1, '/app/device/pause', undefined, { Authorization: 'Bearer a' });
    state = await (await fetch(base + '/__control/state')).json();
    assert.strictEqual(state.inbox.length, 1);
    assert.strictEqual(state.paused, true);
    assert.ok(state.heartbeats.length >= 2);
    assert.strictEqual(state.messageReports.length, 1);

    // inbound SMS request queue drains once
    await post(base, '/__control/inject/inbound-sms', { from: '5551234', text: 'e2e hello' });
    assert.strictEqual(await (await fetch(base + '/__control/sms-requests.txt')).text(), '5551234\te2e hello\n');
    assert.strictEqual(await (await fetch(base + '/__control/sms-requests.txt')).text(), '');
    console.log('stub selftest OK');
    server.close(); process.exit(0);
  } catch (e) {
    console.error(e); server.close(); process.exit(1);
  }
});
