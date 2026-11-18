// Runs the actual embedded Android call script against a fake AudioCodes SDK.
// Execute with: node artemis_ui_sdk/src/test/js/contact-center-call-engine.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/kotlin/com/artemis/uisdk/chat/ArtemisContactCenterCallActivity.kt'), 'utf8');
const documentStart = source.indexOf('<!doctype html>');
const scriptStart = source.indexOf('<script>', documentStart) + '<script>'.length;
const scriptEnd = source.indexOf('</script>', scriptStart);
assert.ok(documentStart >= 0 && scriptEnd > scriptStart, 'embedded call document must exist');
const script = source.slice(scriptStart, scriptEnd).replace('$scriptLiteral', JSON.stringify('https://runtime.example.test/audiocodes.js'));
const nodes = Object.fromEntries(['remote', 'local', 'localCard'].map((name) => [name, {
  style: {}, classList: {add() {}, remove() {}, toggle() {}}, srcObject: null, play: () => Promise.resolve(),
}]));
const events = [], phones = [];
class FakePhone {
  constructor() { this.calls = []; this.VIDEO = 'video'; this.AUDIO = 'audio'; phones.push(this); }
  setServerConfig() {} setAccount() {} setListeners(value) { this.listeners = value; }
  init() {} logout() {} deinit() {}
  call(...args) {
    const result = {args, audio: [], video: [], muteAudio(value) { this.audio.push(value); }, muteVideo(value) { this.video.push(value); }, terminate() {}};
    this.calls.push(result); return result;
  }
}
const window = {};
const sandbox = {window, AudioCodesUA: FakePhone, JsSIP: {}, MediaStream: class {}, setTimeout, clearTimeout,
  AndroidContactCenter: {postMessage: (value) => events.push(JSON.parse(value))},
  document: {getElementById: (id) => nodes[id], createElement: () => ({}), head: {appendChild() {}}},
};
vm.runInNewContext(script, sandbox);
const input = {callType: 'video', addresses: [], domain: 'example.test', iceServers: [], customer: {sipUser: 'u', displayName: 'u', sipPassword: 'p', sipAuthUser: 'u'}, target: 'agent', headers: []};

(async () => {
  const first = window.startContactCenterCall(input);
  assert.equal(phones.length, 1);
  window.stopContactCenterCall();
  phones[0].listeners.loginStateChanged(true, 'connected');
  await first;
  assert.equal(phones[0].calls.length, 0, 'a stopped pending login must not place a call');
  assert.equal(events.filter((event) => event.type === 'error').length, 0, 'stopping must not surface a stale error');

  const second = window.startContactCenterCall(input);
  window.setContactCenterMuted(true);
  window.setContactCenterCameraEnabled(false);
  phones[1].listeners.loginStateChanged(true, 'connected');
  await second;
  assert.equal(phones[1].calls.length, 1);
  assert.deepEqual(phones[1].calls[0].audio, [true]);
  assert.deepEqual(phones[1].calls[0].video, [true]);
  phones[0].listeners.callConfirmed();
  assert.equal(events.filter((event) => event.type === 'connected').length, 0, 'stale call callbacks must be ignored');
  phones[1].listeners.callConfirmed();
  assert.equal(events.filter((event) => event.type === 'connected').length, 1);
  window.stopContactCenterCall();
  process.stdout.write('Android Contact Center call engine checks passed.\n');
})().catch((error) => { process.stderr.write(`${error.stack}\n`); process.exitCode = 1; });
