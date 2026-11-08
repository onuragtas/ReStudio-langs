#!/usr/bin/env node
// Starts one language server over stdio, runs a fixed set of LSP requests
// against one file, and prints a JSON result. All traffic goes to a JSONL trace.
//
// Usage: node probe.mjs <cases.json> <case-name> <trace-dir>
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, appendFileSync, mkdirSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

const [casesPath, caseName, traceDir] = process.argv.slice(2);
const cases = JSON.parse(readFileSync(casesPath, 'utf8'));
const c = cases[caseName];
if (!c) throw new Error(`unknown case ${caseName}`);

const testdata = resolve(dirname(casesPath), c.testdataRoot ?? '../../testdata');
const root = resolve(testdata, c.root);
const rootUri = pathToFileURL(root).href;
const filePath = resolve(root, c.file);
const fileUri = pathToFileURL(filePath).href;
const text = readFileSync(filePath, 'utf8');
mkdirSync(traceDir, { recursive: true });
const tracePath = join(traceDir, `${caseName}.jsonl`);
writeFileSync(tracePath, '');
const trace = (dir, msg) => appendFileSync(tracePath, JSON.stringify({ t: Date.now(), dir, msg }) + '\n');

function positionOf(find, occurrence = 1, offset = 0) {
  let idx = -1;
  for (let i = 0; i < occurrence; i++) {
    idx = text.indexOf(find, idx + 1);
    if (idx < 0) throw new Error(`"${find}" not found in ${c.file}`);
  }
  idx += offset;
  const before = text.slice(0, idx).split('\n');
  return { line: before.length - 1, character: before[before.length - 1].length };
}

// Replace ${root}, ${rootUri} and ${home} in every string of the case.
const subst = v => JSON.parse(JSON.stringify(v ?? null)
  .replaceAll('${rootUri}', rootUri).replaceAll('${root}', root).replaceAll('${home}', process.env.HOME));
for (const k of ['command', 'initializationOptions', 'afterInit', 'settings', 'env']) if (c[k] !== undefined) c[k] = subst(c[k]);
const env = { ...process.env, ...(c.env ?? {}) };
const cmd = c.command;
const started = Date.now();
const proc = spawn(cmd[0], cmd.slice(1), { cwd: root, env, stdio: ['pipe', 'pipe', 'pipe'] });
let stderr = '';
proc.stderr.on('data', d => { stderr += d; if (stderr.length > 20000) stderr = stderr.slice(-20000); });
let exited = null;
proc.on('exit', (code, signal) => { exited = { code, signal }; });
proc.on('error', e => { exited = { error: e.message }; });

let nextId = 1;
const pending = new Map();
const diagnostics = new Map();
const progress = new Set();
const logs = [];
let buf = Buffer.alloc(0);

function send(msg) {
  msg.jsonrpc = '2.0';
  trace('out', msg);
  const body = Buffer.from(JSON.stringify(msg), 'utf8');
  proc.stdin.write(`Content-Length: ${body.length}\r\n\r\n`);
  proc.stdin.write(body);
}
function request(method, params, timeoutMs = c.timeoutMs ?? 30000) {
  const id = nextId++;
  send({ id, method, params });
  return new Promise(res => {
    const timer = setTimeout(() => { pending.delete(id); res({ timeout: true }); }, timeoutMs);
    pending.set(id, msg => { clearTimeout(timer); res(msg); });
  });
}
const notify = (method, params) => send({ method, params });

function configFor(item) {
  const s = c.settings ?? {};
  if (!item.section) return s[''] ?? s;
  return item.section.split('.').reduce((o, k) => (o == null ? null : o[k]), s) ?? null;
}

function onMessage(msg) {
  trace('in', msg);
  if (msg.id !== undefined && msg.method === undefined) {
    pending.get(msg.id)?.(msg);
    pending.delete(msg.id);
    return;
  }
  if (msg.id !== undefined) {
    let result = null;
    if (msg.method === 'workspace/configuration') result = msg.params.items.map(configFor);
    else if (msg.method === 'workspace/workspaceFolders') result = [{ uri: rootUri, name: 'root' }];
    send({ id: msg.id, result });
    return;
  }
  if (msg.method === 'textDocument/publishDiagnostics') diagnostics.set(msg.params.uri, msg.params.diagnostics);
  if (msg.method === '$/progress') {
    const v = msg.params.value;
    if (v?.kind === 'begin') progress.add(msg.params.token);
    if (v?.kind === 'end') progress.delete(msg.params.token);
  }
  if (msg.method === 'window/logMessage' || msg.method === 'window/showMessage') logs.push(msg.params.message.slice(0, 300));
}

proc.stdout.on('data', chunk => {
  buf = Buffer.concat([buf, chunk]);
  for (;;) {
    const sep = buf.indexOf('\r\n\r\n');
    if (sep < 0) return;
    const m = /Content-Length: (\d+)/i.exec(buf.slice(0, sep).toString());
    if (!m) { buf = buf.slice(sep + 4); continue; }
    const len = Number(m[1]);
    if (buf.length < sep + 4 + len) return;
    const body = buf.slice(sep + 4, sep + 4 + len).toString('utf8');
    buf = buf.slice(sep + 4 + len);
    try { onMessage(JSON.parse(body)); } catch (e) { logs.push(`bad message: ${e.message}`); }
  }
});

const sleep = ms => new Promise(r => setTimeout(r, ms));
const rel = uri => {
  if (!uri) return null;
  try { const p = fileURLToPath(uri); return p.startsWith(root) ? p.slice(root.length + 1) : p.replace(process.env.HOME, '~'); }
  catch { return uri; }
};
const locs = r => (r == null ? [] : Array.isArray(r) ? r : [r]).map(l => ({ file: rel(l.uri ?? l.targetUri), line: (l.range ?? l.targetSelectionRange)?.start?.line }));
const countEdits = we => {
  if (!we) return 0;
  let n = 0;
  for (const e of Object.values(we.changes ?? {})) n += e.length;
  for (const d of we.documentChanges ?? []) n += d.edits?.length ?? 0;
  return n;
};
const filesOf = we => [...new Set([...Object.keys(we?.changes ?? {}), ...(we?.documentChanges ?? []).map(d => d.textDocument?.uri)].filter(Boolean).map(rel))];

const out = { case: caseName, server: cmd.join(' '), file: c.file };
async function run(name, fn) {
  const t = Date.now();
  try { out[name] = await fn(); } catch (e) { out[name] = { error: e.message }; }
  if (out[name] && typeof out[name] === 'object') out[name].ms = Date.now() - t;
}
const ok = r => {
  if (r.timeout) throw new Error('timeout');
  if (r.error) throw new Error(`${r.error.code}: ${r.error.message}`.slice(0, 300));
  return r.result;
};

try {
  await run('initialize', async () => {
    const r = ok(await request('initialize', {
      processId: process.pid, rootUri, rootPath: root,
      workspaceFolders: [{ uri: rootUri, name: 'root' }],
      initializationOptions: c.initializationOptions ?? {},
      capabilities: {
        workspace: { configuration: true, workspaceFolders: true, applyEdit: false, didChangeConfiguration: { dynamicRegistration: true } },
        textDocument: {
          synchronization: { didSave: true },
          hover: { contentFormat: ['markdown', 'plaintext'] },
          completion: { completionItem: { snippetSupport: true, resolveSupport: { properties: ['documentation', 'detail'] } } },
          definition: { linkSupport: true }, references: {}, rename: { prepareSupport: true },
          formatting: {}, publishDiagnostics: { relatedInformation: true }, diagnostic: {},
        },
        window: { workDoneProgress: true, showMessage: {} },
      },
    }, c.initTimeoutMs ?? 60000));
    const caps = r.capabilities ?? {};
    return {
      serverInfo: r.serverInfo ? { name: r.serverInfo.name, version: String(r.serverInfo.version ?? '').slice(0, 60) } : null,
      capabilities: ['hoverProvider', 'completionProvider', 'definitionProvider', 'referencesProvider', 'renameProvider', 'documentFormattingProvider', 'diagnosticProvider']
        .filter(k => caps[k]),
    };
  });
  if (out.initialize.error) throw new Error('initialize failed');
  notify('initialized', {});
  if (c.settings) notify('workspace/didChangeConfiguration', { settings: c.settings });
  for (const n of c.afterInit ?? []) notify(n.method, n.params);
  notify('textDocument/didOpen', { textDocument: { uri: fileUri, languageId: c.languageId, version: 1, text } });

  // Let the server load the project. Wait until progress ends, but never longer than settleMs.
  const settleUntil = Date.now() + (c.settleMs ?? 5000);
  await sleep(Math.min(1500, c.settleMs ?? 5000));
  while (Date.now() < settleUntil && progress.size > 0) await sleep(250);

  const td = { uri: fileUri };
  const sym = positionOf(c.symbol.find, c.symbol.occurrence, c.symbol.offset);

  // A cold server can answer definition with nothing. Retry until it answers or the warm-up ends.
  await run('definition', async () => {
    const until = Date.now() + (c.warmupMs ?? 30000);
    let r;
    do {
      r = ok(await request('textDocument/definition', { textDocument: td, position: sym }));
      if (locs(r).length) break;
      await sleep(1000);
    } while (Date.now() < until);
    return { targets: locs(r).slice(0, 3) };
  });
  await run('hover', async () => {
    const r = ok(await request('textDocument/hover', { textDocument: td, position: sym }));
    const v = r?.contents;
    const s = typeof v === 'string' ? v : Array.isArray(v) ? v.map(x => x.value ?? x).join('\n') : v?.value ?? '';
    return { text: s.replace(/\s+/g, ' ').slice(0, 160) };
  });
  if (c.completion) await run('completion', async () => {
    const p = positionOf(c.completion.find, c.completion.occurrence, c.completion.offset);
    const r = ok(await request('textDocument/completion', { textDocument: td, position: p }));
    const items = Array.isArray(r) ? r : r?.items ?? [];
    return { count: items.length, sample: items.slice(0, 5).map(i => i.label?.label ?? i.label) };
  });
  await run('references', async () => {
    const r = ok(await request('textDocument/references', { textDocument: td, position: sym, context: { includeDeclaration: true } }));
    const l = locs(r);
    return { count: l.length, files: [...new Set(l.map(x => x.file))].slice(0, 5) };
  });
  await run('rename', async () => {
    const r = ok(await request('textDocument/rename', { textDocument: td, position: sym, newName: c.renameTo ?? 'RenamedSymbol' }));
    return { edits: countEdits(r), files: filesOf(r).slice(0, 5) };
  });
  await run('formatting', async () => {
    const r = ok(await request('textDocument/formatting', { textDocument: td, options: { tabSize: 4, insertSpaces: true } }));
    return { edits: Array.isArray(r) ? r.length : 0 };
  });
  await run('diagnostics', async () => {
    let pull = null;
    if (out.initialize.capabilities.includes('diagnosticProvider')) {
      const r = await request('textDocument/diagnostic', { textDocument: td });
      if (!r.error && !r.timeout) pull = r.result?.items ?? [];
    }
    const push = diagnostics.get(fileUri) ?? null;
    const all = pull ?? push ?? [];
    return { mode: pull ? 'pull' : push ? 'push' : 'none', count: all.length, messages: all.slice(0, 3).map(d => d.message.slice(0, 120)) };
  });
  await request('shutdown', null, 5000);
  notify('exit', null);
} catch (e) {
  out.fatal = e.message;
}
await sleep(300);
if (!exited) proc.kill();
out.totalMs = Date.now() - started;
out.exited = exited;
out.logs = logs.slice(-5);
out.stderrTail = stderr.trim().split('\n').slice(-5).map(s => s.slice(0, 200));
console.log(JSON.stringify(out));
process.exit(0);
