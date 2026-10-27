import http from 'k6/http';
import { check, fail } from 'k6';

export function baseUrl() {
  return (__ENV.KING_AI_BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
}

export function requireEnv(name) {
  const value = __ENV[name];
  if (!value) {
    fail(`${name} is required`);
  }
  return value;
}

export function requirePositiveInt(name, fallback) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isFinite(value) || value < 1) {
    fail(`${name} must be a positive integer`);
  }
  return Math.floor(value);
}

export function login() {
  const response = http.post(
    `${baseUrl()}/api/auth/login`,
    JSON.stringify({
      username: requireEnv('KING_AI_USERNAME'),
      password: requireEnv('KING_AI_PASSWORD'),
    }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'auth.login' },
    },
  );
  check(response, { 'login 200': (r) => r.status === 200 });
  if (response.status !== 200) {
    fail(`login failed: HTTP ${response.status}`);
  }
  const body = response.json();
  if (!body || !body.data || !body.data.token) {
    fail('login response missing data.token');
  }
  return {
    token: body.data.token,
    spaceId: __ENV.KING_AI_SPACE_ID
      ? Number(__ENV.KING_AI_SPACE_ID)
      : body.data.currentSpaceId,
  };
}

export function adminHeaders(token, spaceId, accept) {
  if (spaceId === null || spaceId === undefined || Number.isNaN(Number(spaceId))) {
    fail('space id is required; set KING_AI_SPACE_ID');
  }
  return {
    'Content-Type': 'application/json',
    Accept: accept || 'application/json',
    Authorization: `Bearer ${token}`,
    'X-Space-Id': String(spaceId),
  };
}

export function agentId() {
  const value = Number(requireEnv('KING_AI_AGENT_ID'));
  if (!Number.isFinite(value) || value < 1) {
    fail('KING_AI_AGENT_ID must be a numeric agent id');
  }
  return value;
}
