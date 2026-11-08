// Starts a language server and copies its standard streams. It writes the raw bytes of both directions to a file.
// ReStudio uses it only at the debug log level, to see the real traffic of a server.
// Usage: node stdio-tee.mjs <log file> <command> [arguments...]
import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';

const [log, command, ...args] = process.argv.slice(2);
const out = createWriteStream(log, { flags: 'w' });
const child = spawn(command, args, { stdio: ['pipe', 'pipe', 'pipe'] });
const record = (direction) => (chunk) => out.write(`\n--- ${direction} ${new Date().toISOString()} ${chunk.length} bytes\n${chunk}`);
process.stdin.on('data', (chunk) => { record('to server')(chunk); child.stdin.write(chunk); });
child.stdout.on('data', (chunk) => { record('from server')(chunk); process.stdout.write(chunk); });
child.stderr.on('data', (chunk) => { record('stderr')(chunk); process.stderr.write(chunk); });
process.stdin.on('end', () => child.stdin.end());
child.on('exit', (code) => { out.write(`\n--- exit ${code}\n`); out.end(() => process.exit(code ?? 0)); });
