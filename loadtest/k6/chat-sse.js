import sse from 'k6/x/sse';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import {
  adminHeaders,
  agentId,
  baseUrl,
  login,
  requirePositiveInt,
} from './lib/api.js';

const ttft = new Trend('king_ai_load_ttft_ms', true);
const chatDuration = new Trend('king_ai_load_chat_duration_ms', true);
const done = new Counter('king_ai_load_done');
const events = new Counter('king_ai_load_events');
const errorRate = new Rate('king_ai_load_error_rate');

const vus = requirePositiveInt('VUS', 2);
const ramp = __ENV.RAMP || '10s';
const hold = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    chat: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: ramp, target: vus },
        { duration: hold, target: vus },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    king_ai_load_error_rate: ['rate<0.01'],
    king_ai_load_ttft_ms: ['p(95)<5000'],
  },
};

export function setup() {
  const auth = login();
  return {
    token: auth.token,
    spaceId: auth.spaceId,
    agentId: agentId(),
  };
}

export default function (data) {
  const startedAt = Date.now();
  let settled = false;
  let firstToken = false;
  let sawDone = false;
  let failed = false;

  const finish = (ok) => {
    if (settled) {
      return;
    }
    settled = true;
    chatDuration.add(Date.now() - startedAt);
    errorRate.add(!ok);
    if (ok) {
      done.add(1);
    }
  };

  const response = sse.open(
    `${baseUrl()}/api/chat/send`,
    {
      method: 'POST',
      headers: adminHeaders(data.token, data.spaceId, 'text/event-stream'),
      body: JSON.stringify({
        agentId: data.agentId,
        sessionId: null,
        message: `load test vu=${__VU} iter=${__ITER}`,
      }),
      tags: { name: 'chat.send' },
    },
    (client) => {
      client.on('event', (event) => {
        events.add(1);
        if (event.name === 'content' && !firstToken) {
          firstToken = true;
          ttft.add(Date.now() - startedAt);
        }
        if (event.name === 'error') {
          failed = true;
          finish(false);
          client.close();
        }
        if (event.name === 'done') {
          sawDone = true;
          finish(true);
          client.close();
        }
      });
      client.on('error', () => {
        failed = true;
        finish(false);
        client.close();
      });
    },
  );

  check(response, {
    'chat status 200': (r) => r && r.status === 200,
    'chat reached done': () => sawDone && !failed,
  });
  if (!settled) {
    finish(sawDone && !failed);
  }
}

export function handleSummary(data) {
  const out = __ENV.SUMMARY_OUT;
  if (!out) {
    return {};
  }
  return { [out]: JSON.stringify(data, null, 2) };
}
