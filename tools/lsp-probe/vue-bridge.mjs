#!/usr/bin/env node
// Starts vtsls with @vue/typescript-plugin and the Vue language server, and bridges the
// tsserver/request notifications of Vue LS to vtsls (workspace/executeCommand typescript.tsserverRequest).
// Then it asks both servers for hover, definition and completion in App.vue.
// Usage: node vue-bridge.mjs <vue project> <trace file>
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, appendFileSync, realpathSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { pathToFileURL } from 'node:url';
import { execSync } from 'node:child_process';

const [root, tracePath] = [resolve(process.argv[2]), resolve(process.argv[3])];
writeFileSync(tracePath, '');
const vueLs = dirname(dirname(realpathSync(execSync('which vue-language-server').toString().trim())));
const settings = {
  vtsls: { tsserver: { globalPlugins: [{ name: '@vue/typescript-plugin', location: vueLs, languages: ['vue'], configNamespace: 'typescript', enableForWorkspaceTypeScriptVersions: true }] } },
};
const sleep = ms => new Promise(r => setTimeout(r, ms));

function server(name, command) {
  const proc = spawn(command[0], command.slice(1), { cwd: root, stdio: ['pipe', 'pipe', 'pipe'] });
  let buffer = Buffer.alloc(0), seq = 0;
  const pending = new Map(), handlers = {};
  const trace = (dir, msg) => appendFileSync(tracePath, JSON.stringify({ t: Date.now(), server: name, dir, msg }) + '\n');
  const send = msg => { trace('out', msg); const s = JSON.stringify({ jsonrpc: '2.0', ...msg }); proc.stdin.write(`Content-Length: ${Buffer.byteLength(s)}\r\n\r\n${s}`); };
  proc.stdout.on('data', d => {
    buffer = Buffer.concat([buffer, d]);
    for (;;) {
      const end = buffer.indexOf('\r\n\r\n'); if (end < 0) return;
      const length = Number(/Content-Length: (\d+)/i.exec(buffer.slice(0, end).toString())[1]);
      if (buffer.length < end + 4 + length) return;
      const msg = JSON.parse(buffer.slice(end + 4, end + 4 + length).toString()); buffer = buffer.slice(end + 4 + length);
      trace('in', msg);
      if (msg.id !== undefined && msg.method) {
        const h = handlers[msg.method]; Promise.resolve(h ? h(msg.params) : null).then(result => send({ id: msg.id, result }));
      } else if (msg.id !== undefined) { pending.get(msg.id)?.(msg); pending.delete(msg.id); }
      else handlers[msg.method]?.(msg.params);
    }
  });
  return {
    handlers, proc,
    request: (method, params, ms = 30000) => new Promise(res => { const id = ++seq; pending.set(id, res); send({ id, method, params }); setTimeout(() => res({ timeout: true }), ms); }),
    notify: (method, params) => send({ method, params }),
  };
}

const configuration = params => params.items.map(item => item.section?.split('.').reduce((o, k) => o?.[k], settings) ?? null);
const capabilities = { workspace: { configuration: true, workspaceFolders: true }, textDocument: { hover: { contentFormat: ['markdown', 'plaintext'] }, completion: { completionItem: { snippetSupport: true } }, definition: {}, synchronization: {} } };
const rootUri = pathToFileURL(root).href;
const init = { processId: process.pid, rootUri, workspaceFolders: [{ uri: rootUri, name: 'vue' }], capabilities };

const ts = server('vtsls', ['vtsls', '--stdio']);
const vue = server('vue', ['vue-language-server', '--stdio']);
for (const s of [ts, vue]) {
  s.handlers['workspace/configuration'] = configuration;
  for (const m of ['client/registerCapability', 'window/workDoneProgress/create', 'client/unregisterCapability']) s.handlers[m] = () => null;
}
let bridged = 0;
vue.handlers['tsserver/request'] = async (params) => {
  const [id, command, args] = Array.isArray(params[0]) ? params[0] : params;
  const r = await ts.request('workspace/executeCommand', { command: 'typescript.tsserverRequest', arguments: [command, args, { isAsync: true, lowPriority: true }] });
  bridged++;
  vue.notify('tsserver/response', [[id, r.result?.body]]);
};

const out = {};
out.tsInit = (await ts.request('initialize', init)).result ? 'ok' : 'failed';
ts.notify('initialized', {});
ts.notify('workspace/didChangeConfiguration', { settings });
// VUE_INIT_FILE: the initialize parameters of another client, for example LSP4IJ (from its trace).
const vueInit = process.env.VUE_INIT_FILE ? JSON.parse(readFileSync(process.env.VUE_INIT_FILE, 'utf8')) : { ...init, initializationOptions: {} };
out.vueInit = (await vue.request('initialize', vueInit)).result ? 'ok' : 'failed';
vue.notify('initialized', {});

const app = resolve(root, 'src/App.vue');
const text = readFileSync(app, 'utf8');
const doc = { uri: pathToFileURL(app).href, languageId: 'vue', version: 1, text };
ts.notify('textDocument/didOpen', { textDocument: doc });
vue.notify('textDocument/didOpen', { textDocument: doc });
const pos = (find, delta = 0) => { const i = text.indexOf(find) + delta; const before = text.slice(0, i); return { line: before.split('\n').length - 1, character: i - before.lastIndexOf('\n') - 1 }; };
const td = { uri: doc.uri };
// LSP4IJ order: many requests right after didOpen, and $/cancelRequest for the first ones (see the IDE trace).
if (process.env.LSP4IJ_ORDER) {
  const early = ['textDocument/documentLink', 'textDocument/inlayHint', 'textDocument/documentColor', 'textDocument/foldingRange', 'textDocument/documentSymbol', 'textDocument/semanticTokens/full'];
  const range = { start: { line: 0, character: 0 }, end: { line: 7, character: 0 } };
  const pendingEarly = early.map(m => vue.request(m, m.endsWith('inlayHint') ? { textDocument: td, range } : { textDocument: td }, 20000).then(r => `${m}: ${r.timeout ? 'TIMEOUT' : r.error ? 'error ' + r.error.message : 'ok'}`));
  out.earlyCancelled = 'ids 1..5';
  for (let id = 1; id <= 5; id++) vue.notify('$/cancelRequest', { id: id + 1 });
  out.early = await Promise.all(pendingEarly);
}
await sleep(8000);

const hoverText = r => { const c = r.result?.contents; return typeof c === 'string' ? c : c?.value ?? (Array.isArray(c) ? c.map(x => x.value ?? x).join(' ') : null); };
out.vtslsHoverImport = hoverText(await ts.request('textDocument/hover', { textDocument: td, position: pos('HelloWorld from', 2) }))?.replace(/\s+/g, ' ').slice(0, 120);
out.vtslsDefinitionTag = JSON.stringify((await ts.request('textDocument/definition', { textDocument: td, position: pos('<HelloWorld', 3) })).result)?.slice(0, 160);
out.vueHoverTag = hoverText(await vue.request('textDocument/hover', { textDocument: td, position: pos('<HelloWorld', 3) }))?.replace(/\s+/g, ' ').slice(0, 120);
const completion = (await vue.request('textDocument/completion', { textDocument: td, position: pos('<HelloWorld', 1) })).result;
const items = Array.isArray(completion) ? completion : completion?.items ?? [];
out.vueCompletionInTemplate = { count: items.length, hasHelloWorld: items.some(i => i.label === 'HelloWorld'), sample: items.slice(0, 5).map(i => i.label) };
out.bridgedRequests = bridged;
console.log(JSON.stringify(out, null, 1));
for (const s of [ts, vue]) { await s.request('shutdown', null, 3000); s.notify('exit', null); setTimeout(() => s.proc.kill(), 500); }
setTimeout(() => process.exit(0), 1500);
