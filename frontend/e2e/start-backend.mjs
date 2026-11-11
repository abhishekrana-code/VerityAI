#!/usr/bin/env node
/**
 * Starts an isolated backend for the Playwright E2E suite.
 *
 * Guarantees:
 *  - its own database on port 5435 (started through docker-compose.e2e.yml),
 *  - its own upload directory under e2e/.tmp,
 *  - mail delivered to the local SMTP sink instead of a real provider,
 *  - rate limits disabled (tests exercise many logins/registrations),
 *  - embeddings and summaries disabled so the suite stays fast and offline.
 *
 * Never point this at a developer backend: E2E must not send real email.
 */
import { spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const frontendDir = path.resolve(here, '..');
const repoRoot = path.resolve(frontendDir, '..');
const backendDir = path.join(repoRoot, 'backend');
const tmpDir = path.join(here, '.tmp');
const isWindows = process.platform === 'win32';

mkdirSync(tmpDir, { recursive: true });

function log(message) {
  console.log(`[e2e-backend] ${message}`);
}

function portOpen(port, host = '127.0.0.1', timeoutMs = 1000) {
  return new Promise((resolve) => {
    const socket = net.connect({ port, host });
    const finish = (open) => {
      socket.destroy();
      resolve(open);
    };
    socket.setTimeout(timeoutMs);
    socket.once('connect', () => finish(true));
    socket.once('timeout', () => finish(false));
    socket.once('error', () => finish(false));
  });
}

function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'inherit', shell: isWindows, ...options });
    child.once('error', reject);
    child.once('exit', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`${command} exited with code ${code}`));
    });
  });
}

async function ensureDatabase() {
  if (await portOpen(5435)) {
    log('PostgreSQL already listening on 5435');
    return;
  }

  log('Starting isolated PostgreSQL (docker-compose.e2e.yml)...');
  try {
    await run(
      'docker',
      ['compose', '-f', path.join(repoRoot, 'docker-compose.e2e.yml'), 'up', '-d', '--wait'],
      { cwd: repoRoot },
    );
  } catch (error) {
    throw new Error(
      `Could not start the E2E database. Is Docker Desktop running? (${error.message})`,
    );
  }

  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    if (await portOpen(5435)) {
      log('PostgreSQL is ready on 5435');
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error('PostgreSQL did not become ready on port 5435 within 60s');
}

const env = {
  ...process.env,
  SPRING_PROFILES_ACTIVE: 'e2e',
  SERVER_PORT: '8081',
  SPRING_DATASOURCE_URL: 'jdbc:postgresql://127.0.0.1:5435/verityai_e2e',
  SPRING_DATASOURCE_USERNAME: 'verityai',
  SPRING_DATASOURCE_PASSWORD: 'verityai_e2e',
  UPLOAD_DIR: path.join(tmpDir, 'uploads'),
  // Keep the suite fast and offline: keyword retrieval only, no summaries.
  EMBEDDINGS_PROVIDER: 'off',
  SUMMARY_ENABLED: 'false',
  GROQ_API_KEY: '',
  // Mail goes to e2e/smtp-sink.mjs, never to a real provider.
  MAIL_HOST: '127.0.0.1',
  MAIL_PORT: '1025',
  MAIL_STARTTLS: 'false',
  MAIL_SSL: 'false',
  MAIL_USERNAME: '',
  MAIL_PASSWORD: '',
  MAIL_FROM: 'no-reply@verityai.test',
  APP_BASE_URL: 'http://127.0.0.1:5174',
  CORS_ALLOWED_ORIGINS: 'http://127.0.0.1:5174,http://localhost:5174',
  SERVER_SERVLET_SESSION_COOKIE_SECURE: 'false',
  // 0 disables a limiter; the suite deliberately exercises many attempts.
  RATE_LIMIT_LOGIN_PER_MINUTE_IP: '0',
  RATE_LIMIT_REGISTER_PER_HOUR_IP: '0',
  RATE_LIMIT_PASSWORD_RESET_PER_HOUR_IP: '0',
  RATE_LIMIT_PASSWORD_RESET_PER_HOUR_ACCOUNT: '0',
  RATE_LIMIT_UPLOADS_PER_HOUR: '0',
  RATE_LIMIT_AI_REQUESTS_PER_MINUTE: '0',
  LOGGING_LEVEL_COM_VERITYAI: 'INFO',
  LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY: 'INFO',
  LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB: 'INFO',
  LOGGING_LEVEL_ORG_HIBERNATE_SQL: 'WARN',
  LOGGING_LEVEL_ORG_HIBERNATE_ORM_JDBC_BIND: 'WARN',
};

await ensureDatabase();

log('Starting Spring Boot on port 8081...');
const maven = isWindows ? 'mvnw.cmd' : './mvnw';
const child = spawn(
  maven,
  ['-B', '--no-transfer-progress', '-Dspring-boot.run.fork=false', 'spring-boot:run'],
  {
    cwd: backendDir,
    env,
    stdio: 'inherit',
    shell: isWindows,
    detached: !isWindows,
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
      try {
        process.kill(-child.pid, 'SIGTERM');
      } catch {
        child.kill('SIGTERM');
      }
    }
  }
  setTimeout(() => process.exit(code), 500);
}

process.on('SIGINT', () => stop(0));
process.on('SIGTERM', () => stop(0));

child.once('error', (error) => {
  console.error(`[e2e-backend] failed to start: ${error.message}`);
  process.exit(1);
});
child.once('exit', (code) => {
  log(`backend process exited with code ${code ?? 0}`);
  process.exit(code ?? 0);
});
