import { Counter } from 'k6/metrics'
import { check, sleep } from 'k6'
import http from 'k6/http'

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '')
const RUN_ID = __ENV.RUN_ID || ''

function readNumber(name, fallback) {
  const value = __ENV[name] === undefined ? fallback : Number(__ENV[name])
  if (!Number.isFinite(value)) {
    throw new Error(`${name} must be a finite number`)
  }
  return value
}

const MAX_VUS = readNumber('MAX_VUS', 1)
const STAGE_SECONDS = readNumber('STAGE_SECONDS', 10)
const THINK_TIME_SECONDS = readNumber('THINK_TIME_SECONDS', 1)
const P95_MAX_MS = readNumber('P95_MAX_MS', 1500)
const CHECK_FAILURE_RATE_MAX = readNumber('CHECK_FAILURE_RATE_MAX', 0)

if (!/^[A-Za-z0-9_-]{1,50}$/.test(RUN_ID)) {
  throw new Error('RUN_ID is required and must contain 1-50 letters, numbers, "_" or "-"')
}
if (!Number.isInteger(MAX_VUS) || MAX_VUS < 1) {
  throw new Error('MAX_VUS must be a positive integer')
}
if (STAGE_SECONDS <= 0 || THINK_TIME_SECONDS < 0 || P95_MAX_MS <= 0) {
  throw new Error('STAGE_SECONDS and P95_MAX_MS must be positive; THINK_TIME_SECONDS cannot be negative')
}
if (CHECK_FAILURE_RATE_MAX < 0 || CHECK_FAILURE_RATE_MAX > 1) {
  throw new Error('CHECK_FAILURE_RATE_MAX must be between 0 and 1')
}

const stageTargets = [5, 10, 20, 50, 100, 200, 500, 1000]
  .filter((target) => target < MAX_VUS)
if (MAX_VUS > 1) {
  stageTargets.push(MAX_VUS)
}
stageTargets.push(MAX_VUS)

const acceptedPosts = new Counter('telemetry_posts_accepted')
const httpErrors = new Counter('telemetry_http_errors')

export const options = {
  scenarios: {
    telemetry_ramp: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: stageTargets.map((target) => ({
        target,
        duration: `${STAGE_SECONDS}s`,
      })),
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_duration: [`p(95)<${P95_MAX_MS}`],
    checks: [`rate>=${1 - CHECK_FAILURE_RATE_MAX}`],
    telemetry_posts_accepted: ['count>0'],
  },
}

function uuidV4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const randomNibble = Math.floor(Math.random() * 16)
    const value = character === 'x' ? randomNibble : (randomNibble & 0x3) | 0x8
    return value.toString(16)
  })
}

export default function () {
  const payload = {
    eventId: uuidV4(),
    vehicleId: `k6-${RUN_ID}-${__VU}`,
    latitude: 40.7128,
    longitude: -74.006,
    timestamp: new Date().toISOString(),
    speed: 45,
  }

  const response = http.post(`${BASE_URL}/api/telemetry`, JSON.stringify(payload), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /api/telemetry' },
  })
  const accepted = check(response, {
    'POST /api/telemetry returns 202': (result) => result.status === 202,
  })

  if (accepted) {
    acceptedPosts.add(1)
  } else {
    httpErrors.add(1, { status: String(response.status) })
  }

  sleep(THINK_TIME_SECONDS)
}
