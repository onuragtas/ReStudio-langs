#!/usr/bin/env node
// Starts one debug adapter, launches a program, stops at a breakpoint, reads
// the stack and the variables, steps once, and continues to the end.
// Prints a JSON result. All traffic goes to a JSONL trace.
//
// Usage: node probe.mjs <cases.json> <case-name> <trace-dir>
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, appendFileSync, mkdirSync } from 'node:fs';
import { createConnection, createServer } from 'node:net';
import { resolve, dirname, join } from 'node:path';

const [casesPath, caseName, traceDir] = process.argv.slice(2);
const cases = JSON.parse(readFileSync(casesPath, 'utf8'));
const c = cases[caseName];
if (!c) throw new Error(`unknown case ${caseName}`);

const testdata = resolve(dirname(casesPath), '../../testdata');
const root = resolve(testdata, c.root);
const bpFile = resolve(root, c.breakpoint.file);
const bpText = readFileSync(bpFile, 'utf8').split('\n');
const bpLine = bpText.findIndex(l => l.includes(c.breakpoint.find)) + 1;
if (bpLine === 0) throw new Error(`"${c.breakpoint.find}" not found in ${c.breakpoint.file}`);

mkdirSync(traceDir, { recursive: true });
const tracePath = join(traceDir, `${caseName}.jsonl`);
writeFileSync(tracePath, '');
const trace = (session, dir, msg) => appendFileSync(tracePath, JSON.stringify({ t: Date.now(), session, dir, msg }) + '\n');
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function freePort() {
  return new Promise(res => { const s = createServer(); s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); }); });
}
const port = await freePort();
const subst = v => JSON.parse(JSON.stringify(v ?? null)
  .replaceAll('${root}', root).replaceAll('${home}', process.env.HOME).replaceAll('${port}', String(port)));
const command = subst(c.command);
const launchArgs = subst(c.launch);
// processIdEnv: the name of an environment variable with the PID for an attach request.
if (c.processIdEnv) launchArgs.processId = Number(process.env[c.processIdEnv]);

const started = Date.now();
// adapterCwd: the working directory of the adapter process. The IDE does not always start it in the project.
const adapterCwd = c.adapterCwd ? subst(c.adapterCwd) : root;
const proc = spawn(command[0], command.slice(1), { cwd: adapterCwd, env: { ...process.env, ...(c.env ?? {}) }, stdio: ['pipe', 'pipe', 'pipe'] });
let stderr = '', stdoutText = '';
proc.stderr.on('data', d => { stderr = (stderr + d).slice(-20000); });
let exited = null;
proc.on('exit', (code, signal) => { exited = { code, signal }; });

const events = [];     // every event from every session, in order
const output = [];     // program output
let waiters = [];
function emitEvent(session, msg) {
  const ev = { session, msg };
  events.push(ev);
  if (msg.event === 'output' && msg.body?.category !== 'telemetry') output.push(msg.body.output);
  // Give the event to the first matching waiter only, and mark it as used.
  const w = waiters.find(x => x.match(session, msg));
  if (w) { ev.used = true; waiters = waiters.filter(x => x !== w); w.resolve(ev); }
}
function waitEvent(match, timeoutMs) {
  const hit = events.find(e => match(e.session, e.msg) && !e.used);
  if (hit) { hit.used = true; return Promise.resolve(hit); }
  return new Promise(res => {
    const w = { match, resolve: v => { clearTimeout(timer); res(v); } };
    const timer = setTimeout(() => { waiters = waiters.filter(x => x !== w); res(null); }, timeoutMs);
    waiters.push(w);
  });
}

class Session {
  constructor(name, readable, writable) {
    this.name = name; this.w = writable; this.seq = 1; this.pending = new Map(); this.buf = Buffer.alloc(0);
    readable.on('data', chunk => this.onData(chunk));
  }
  onData(chunk) {
    this.buf = Buffer.concat([this.buf, chunk]);
    for (;;) {
      const sep = this.buf.indexOf('\r\n\r\n');
      if (sep < 0) return;
      const m = /Content-Length: (\d+)/i.exec(this.buf.slice(0, sep).toString());
      if (!m) { stdoutText += this.buf.slice(0, sep + 4).toString(); this.buf = this.buf.slice(sep + 4); continue; }
      const len = Number(m[1]);
      if (this.buf.length < sep + 4 + len) return;
      const msg = JSON.parse(this.buf.slice(sep + 4, sep + 4 + len).toString('utf8'));
      this.buf = this.buf.slice(sep + 4 + len);
      trace(this.name, 'in', msg);
      if (msg.type === 'response') { this.pending.get(msg.request_seq)?.(msg); this.pending.delete(msg.request_seq); }
      else if (msg.type === 'event') emitEvent(this, msg);
      else if (msg.type === 'request') onReverseRequest(this, msg);
    }
  }
  send(msg) {
    msg.seq = this.seq++;
    trace(this.name, 'out', msg);
    const body = Buffer.from(JSON.stringify(msg), 'utf8');
    this.w.write(`Content-Length: ${body.length}\r\n\r\n`); this.w.write(body);
    return msg.seq;
  }
  request(command, args, timeoutMs = 30000) {
    const seq = this.send({ type: 'request', command, arguments: args });
    return new Promise(res => {
      const timer = setTimeout(() => { this.pending.delete(seq); res({ success: false, message: 'timeout' }); }, timeoutMs);
      this.pending.set(seq, m => { clearTimeout(timer); res(m); });
    });
  }
}

const initArgs = {
  clientID: 'restudio-dap-probe', clientName: 'ReStudio DAP probe', adapterID: c.adapterID ?? 'probe',
  linesStartAt1: true, columnsStartAt1: true, pathFormat: 'path',
  supportsVariableType: true, supportsRunInTerminalRequest: false, supportsStartDebuggingRequest: true, supportsProgressReporting: false,
  locale: 'en',
};
const sessions = [];

async function configure(s, launch) {
  const init = await s.request('initialize', initArgs);
  if (!init.success) throw new Error(`initialize: ${init.message}`);
  const launchP = s.request(c.request ?? 'launch', launch, c.launchTimeoutMs ?? 60000);
  const ini = await waitEvent((ss, m) => ss === s && m.event === 'initialized', c.launchTimeoutMs ?? 60000);
  if (!ini) {
    const l = await launchP;
    throw new Error(`no initialized event; launch: ${l.success ? 'ok' : l.message}`);
  }
  const bp = await s.request('setBreakpoints', { source: { path: bpFile }, breakpoints: [{ line: bpLine }], lines: [bpLine] });
  s.breakpoints = bp.body?.breakpoints ?? [];
  if (init.body?.supportsConfigurationDoneRequest !== false) await s.request('configurationDone', {});
  s.launchResponse = launchP.then(l => ({ success: l.success, message: l.message }));
  return init.body ?? {};
}

// js-debug creates one child session per debuggee over a new connection.
async function onReverseRequest(s, msg) {
  if (msg.command === 'startDebugging') {
    s.send({ type: 'response', request_seq: msg.seq, command: msg.command, success: true });
    const sock = await connectTcp();
    const child = new Session(`child${sessions.length}`, sock, sock);
    sessions.push(child);
    configure(child, msg.arguments.configuration).catch(e => { out.childError = e.message; });
    return;
  }
  s.send({ type: 'response', request_seq: msg.seq, command: msg.command, success: false, message: 'not supported by the probe' });
}

async function connectTcp() {
  for (let i = 0; i < 100; i++) {
    const sock = await new Promise(res => {
      const k = createConnection({ port, host: '127.0.0.1' });
      k.once('connect', () => res(k)); k.once('error', () => res(null));
    });
    if (sock) return sock;
    await sleep(200);
  }
  throw new Error(`cannot connect to port ${port}`);
}

const out = { case: caseName, adapter: command.join(' ').replace(process.env.HOME, '~'), breakpoint: `${c.breakpoint.file}:${bpLine}` };
const step = async (name, fn) => { const t = Date.now(); try { out[name] = await fn(); } catch (e) { out[name] = { error: e.message }; } if (out[name] && typeof out[name] === 'object') out[name].ms = Date.now() - t; return out[name]; };

let main;
try {
  if (c.transport === 'tcp') { const sock = await connectTcp(); main = new Session('main', sock, sock); }
  else main = new Session('main', proc.stdout, proc.stdin);
  sessions.push(main);

  const caps = await step('initialize', async () => {
    const b = await configure(main, launchArgs);
    return { breakpointVerified: main.breakpoints.map(b => b.verified), supportsStepBack: !!b.supportsStepBack, supportsConditionalBreakpoints: !!b.supportsConditionalBreakpoints };
  });
  if (caps.error) throw new Error('setup failed');

  // Some adapters (Dart) pause at the entry and resume by themselves. Skip those stops.
  const stopDeadline = Date.now() + (c.stopTimeoutMs ?? 60000);
  let stop = null;
  while (Date.now() < stopDeadline) {
    stop = await waitEvent((s, m) => m.event === 'stopped', stopDeadline - Date.now());
    if (!stop || stop.msg.body.reason !== 'entry') break;
    out.entryStops = (out.entryStops ?? 0) + 1;
    // Give the adapter 5 s to resume by itself. Resume it only when nothing else happens.
    const next = await waitEvent((s, m) => m.event === 'stopped' || m.event === 'continued', 5000);
    if (next?.msg.event === 'stopped') { stop = next; if (next.msg.body.reason !== 'entry') break; }
    if (!next) await stop.session.request('continue', { threadId: stop.msg.body.threadId });
    stop = null;
  }
  await step('stop', async () => {
    if (!stop) throw new Error('no stopped event');
    return { reason: stop.msg.body.reason, session: stop.session.name };
  });
  if (!stop) throw new Error('did not stop');
  const s = stop.session;
  const threadId = stop.msg.body.threadId ?? (await s.request('threads', {})).body?.threads?.[0]?.id;

  const frameInfo = async (tid = threadId) => {
    const st = await s.request('stackTrace', { threadId: tid, startFrame: 0, levels: 5 });
    if (!st.success) throw new Error(`stackTrace: ${st.message}`);
    const f = st.body.stackFrames[0];
    return { frame: f, info: { function: f.name, file: f.source?.path?.replace(root + '/', '') ?? f.source?.name, line: f.line, depth: st.body.totalFrames ?? st.body.stackFrames.length } };
  };
  let top;
  await step('stack', async () => { top = await frameInfo(); return top.info; });
  await step('variables', async () => {
    const sc = await s.request('scopes', { frameId: top.frame.id });
    const scopes = sc.body?.scopes ?? [];
    const names = [];
    for (const scope of scopes.slice(0, 2)) {
      const v = await s.request('variables', { variablesReference: scope.variablesReference });
      for (const x of v.body?.variables ?? []) names.push(`${x.name}=${String(x.value).slice(0, 30)}`);
    }
    const found = names.find(n => n.startsWith(`${c.expectVariable}=`));
    return { scopes: scopes.map(x => x.name), found: found ?? null, sample: names.slice(0, 6) };
  });
  await step('evaluate', async () => {
    const r = await s.request('evaluate', { expression: c.expectVariable, frameId: top.frame.id, context: 'watch' });
    return r.success ? { result: String(r.body.result).slice(0, 40) } : { error: r.message };
  });
  await step('stepOver', async () => {
    const r = await s.request(c.stepCommand ?? 'next', { threadId });
    if (!r.success) throw new Error(`next: ${r.message}`);
    const st = await waitEvent((ss, m) => ss === s && m.event === 'stopped', 30000);
    if (!st) throw new Error('no stopped event after step');
    // After an await, the code can continue on a different thread.
    const now = await frameInfo(st.msg.body.threadId ?? threadId);
    return { thread: st.msg.body.threadId, reason: st.msg.body.reason, function: now.info.function, line: now.info.line, fromLine: top.info.line };
  });
  await step('continue', async () => {
    // Remove the breakpoint so that a second call of the same code does not stop again.
    for (const x of sessions) await x.request('setBreakpoints', { source: { path: bpFile }, breakpoints: [], lines: [] }, 5000);
    await s.request('continue', { threadId });
    const end = await waitEvent((ss, m) => m.event === 'terminated' || m.event === 'exited', c.endTimeoutMs ?? 30000);
    const stoppedAgain = events.filter(e => e.msg.event === 'stopped').length > 2;
    if (!end) throw new Error(stoppedAgain ? 'stopped again instead of ending' : 'no terminated event');
    const ex = events.find(e => e.msg.event === 'exited');
    return { ended: end.msg.event, exitCode: ex?.msg.body.exitCode ?? null };
  });
} catch (e) {
  out.fatal = e.message;
}
for (const s of sessions) s.request('disconnect', { terminateDebuggee: true }, 3000);
await sleep(1500);
out.launch = await main?.launchResponse?.catch?.(() => null) ?? null;
if (!exited) proc.kill();
out.output = output.join('').trim().split('\n').slice(-3).map(x => x.slice(0, 120));
out.totalMs = Date.now() - started;
out.stderrTail = stderr.trim().split('\n').slice(-4).map(x => x.slice(0, 200));
console.log(JSON.stringify(out));
process.exit(0);
