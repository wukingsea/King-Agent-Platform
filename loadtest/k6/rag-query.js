import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import {
  adminHeaders,
  baseUrl,
  login,
  requireEnv,
  requirePositiveInt,
} from './lib/api.js';

const latency = new Trend('king_ai_load_rag_latency_ms', true);
const errorRate = new Rate('king_ai_load_error_rate');

const vus = requirePositiveInt('VUS', 2);
const ramp = __ENV.RAMP || '10s';
const hold = __ENV.DURATION || '30s';
const pause = Number(__ENV.SLEEP || 0.5);

export const options = {
  scenarios: {
    rag: {
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
    king_ai_load_rag_latency_ms: ['p(95)<2000'],
  },
};

export function setup() {
  const auth = login();
  return {
    token: auth.token,
    spaceId: auth.spaceId,
    knowledgeBaseId: Number(requireEnv('KING_AI_KB_ID')),
  };
}

export default function (data) {
  const startedAt = Date.now();
  const response = http.post(
    `${baseUrl()}/api/knowledge-bases/${data.knowledgeBaseId}/search`,
    JSON.stringify({
      query: __ENV.RAG_QUERY || '负载测试查询',
      topK: 5,
    }),
    {
      headers: adminHeaders(data.token, data.spaceId),
      tags: { name: 'knowledge.search' },
    },
  );
  latency.add(Date.now() - startedAt);

  let hasData = false;
  if (response.status === 200) {
    try {
      hasData = Array.isArray(response.json().data);
    } catch (error) {
      hasData = false;
    }
  }
  check(response, {
    'search 200': (r) => r.status === 200,
    'search data array': () => hasData,
  });
  errorRate.add(!hasData);

  if (pause > 0) {
    sleep(pause);
  }
}

export function handleSummary(data) {
  const out = __ENV.SUMMARY_OUT;
  if (!out) {
    return {};
  }
  return { [out]: JSON.stringify(data, null, 2) };
}
