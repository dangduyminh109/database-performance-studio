import { spawn } from 'node:child_process';

const options = [];
const input = process.argv.slice(2);
for (let i = 0; i < input.length; i++) {
  if (input[i] === '--strictPort') continue;
  if (input[i] === '--host' || input[i] === '--hostname') {
    if (input[++i] !== '127.0.0.1') throw new Error('The local workspace must bind to loopback.');
    continue;
  }
  options.push(input[i]);
}
const child = spawn(process.execPath, ['node_modules/next/dist/bin/next', 'dev', '--hostname', '127.0.0.1', ...options], { stdio: 'inherit' });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
child.on('exit', code => process.exit(code ?? 1));
