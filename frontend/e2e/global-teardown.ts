import { execFileSync } from 'node:child_process';
import net from 'node:net';

/**
 * Playwright stops its webServer processes with a plain process kill. On
 * Windows that terminates start-backend.mjs but not the forked Spring JVM,
 * which inherited the wrapper's stdout pipe — the runner then waits forever for
 * the stream to close even though every test finished.
 *
 * Stopping the JVM that owns the E2E API port here closes the pipe, so the run
 * actually ends. Only a java.exe listener is touched, so a developer server or
 * any other non-E2E process on that port is left alone.
 *
 * POSIX needs no equivalent: start-backend.mjs keeps the JVM in the wrapper's
 * process group, so Playwright's own group kill reaches it.
 */
function listeningPids(port: number): string[] {
  const pids = new Set<string>();
  const output = execFileSync('netstat', ['-ano'], { encoding: 'utf8' });
  for (const line of output.split(/\r?\n/)) {
    if (!line.includes(`:${port} `) || !line.includes('LISTENING')) continue;
    const pid = line.trim().split(/\s+/).pop();
    if (pid && pid !== '0') pids.add(pid);
  }
  return [...pids];
}

function isJava(pid: string): boolean {
  try {
    return execFileSync('tasklist', ['/FI', `PID eq ${pid}`, '/NH'], { encoding: 'utf8' })
      .toLowerCase()
      .includes('java.exe');
  } catch {
    return false;
  }
}

export default async function globalTeardown(): Promise<void> {
  if (process.platform !== 'win32') return;
  if (!(await portOpen(8081))) return;

  for (const pid of listeningPids(8081)) {
    if (!isJava(pid)) continue;
    try {
      execFileSync('taskkill', ['/pid', pid, '/T', '/F'], { stdio: 'ignore' });
    } catch {
      // Already gone; nothing to clean up.
    }
  }
}

function portOpen(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = net.connect({ port, host: '127.0.0.1' });
    const finish = (open: boolean) => {
      socket.destroy();
      resolve(open);
    };
    socket.setTimeout(1000);
    socket.once('connect', () => finish(true));
    socket.once('timeout', () => finish(false));
    socket.once('error', () => finish(false));
  });
}
