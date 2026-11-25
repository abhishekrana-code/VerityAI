#!/usr/bin/env node
/**
 * Starts the Vite dev server for the Playwright E2E suite on port 5174.
 *
 * The E2E port is intentionally different from the developer default (5173)
 * so a running `npm run dev` is never reused or interrupted. The API proxy
 * target is injected through VITE_API_PROXY_TARGET and points at the isolated
 * backend on 8081.
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const frontendDir = path.resolve(here, '..');
const isWindows = process.platform === 'win32';

const env = {
  ...process.env,
  VITE_API_PROXY_TARGET: 'http://127.0.0.1:8081',
};

const npm = isWindows ? 'npm.cmd' : 'npm';
// Stay in Playwright's process group on POSIX: it stops webServers with
// `kill(-pid)`, and a detached Vite child would survive that kill, hold the
// inherited stdout pipe open, and leave the runner waiting forever.
const child = spawn(
  npm,
  ['run', 'dev', '--', '--host', '127.0.0.1', '--port', '5174', '--strictPort'],
  {
    cwd: frontendDir,
    env,
    stdio: 'inherit',
    shell: isWindows,
  },
);

let stopping = false;
function stop(code = 0) {
  if (stopping) return;
  stopping = true;
  if (child.pid) {
    if (isWindows) {
      spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], {
        stdio: 'ignore',
        shell: true,
      });
    } else {
      child.kill('SIGTERM');
    }
  }
  setTimeout(() => process.exit(code), 500);
}

process.on('SIGINT', () => stop(0));
process.on('SIGTERM', () => stop(0));

child.once('error', (error) => {
  console.error(`[e2e-frontend] failed to start: ${error.message}`);
  process.exit(1);
});
child.once('exit', (code) => {
  console.log(`[e2e-frontend] process exited with code ${code ?? 0}`);
  process.exit(code ?? 0);
});
