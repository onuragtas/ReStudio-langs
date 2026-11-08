// Reads one probe result (JSON) from stdin and prints a one-line summary.
let s = '';
process.stdin.on('data', d => { s += d; }).on('end', () => {
  let j;
  try { j = JSON.parse(s); } catch { console.log(`BAD ${s.slice(0, 200)}`); return; }
  const e = k => (j[k]?.error ? `ERR(${j[k].error.slice(0, 60)})` : '');
  const st = j.stepOver;
  const parts = [
    `bp=${j.initialize?.breakpointVerified ?? e('initialize')}`,
    `stop=${j.stop?.reason ?? e('stop')}`,
    `at=${j.stack ? `${j.stack.function}:${j.stack.line}` : e('stack')}`,
    `var=${j.variables?.found ?? (e('variables') || '-')}`,
    `eval=${j.evaluate?.result ?? e('evaluate')}`,
    `step=${st && !st.error ? `${st.fromLine}->${st.line} ${st.function ?? ''}` : e('stepOver')}`,
    `end=${j.continue?.ended ?? e('continue')}`,
    `total=${j.totalMs}ms`,
  ];
  if (j.fatal) parts.push(`FATAL=${j.fatal}`);
  console.log(parts.join(' '));
});
