import { Counter, Rate } from 'k6/metrics'
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

function readPercentage(name, fallback) {
  const value = readNumber(name, fallback)
  if (!Number.isInteger(value) || value < 0 || value > 100) {
    throw new Error(`${name} must be an integer between 0 and 100`)
  }
  return value
}

const MAX_VUS = readNumber('MAX_VUS', 1)
const STAGE_SECONDS = readNumber('STAGE_SECONDS', 10)
const THINK_TIME_SECONDS = readNumber('THINK_TIME_SECONDS', 1)
const P95_MAX_MS = readNumber('P95_MAX_MS', 1500)
const CHECK_FAILURE_RATE_MAX = readNumber('CHECK_FAILURE_RATE_MAX', 0)
const DUPLICATE_PERCENT = readPercentage('DUPLICATE_PERCENT', 10)
const INTENTIONAL_ERROR_PERCENT = readPercentage('INTENTIONAL_ERROR_PERCENT', 5)
const TRAFFIC_RATE_TOLERANCE = readNumber('TRAFFIC_RATE_TOLERANCE', 0.02)
const UNEXPECTED_FAILURE_RATE_MAX = readNumber('UNEXPECTED_FAILURE_RATE_MAX', 0)
const ITERATIONS_PER_RATIO_CYCLE = 100
const ORIGINAL_PERCENT = 100 - DUPLICATE_PERCENT - INTENTIONAL_ERROR_PERCENT

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
if (DUPLICATE_PERCENT + INTENTIONAL_ERROR_PERCENT >= 100) {
  throw new Error('DUPLICATE_PERCENT and INTENTIONAL_ERROR_PERCENT must sum to less than 100')
}
if (TRAFFIC_RATE_TOLERANCE < 0 || TRAFFIC_RATE_TOLERANCE > 1) {
  throw new Error('TRAFFIC_RATE_TOLERANCE must be between 0 and 1')
}
if (UNEXPECTED_FAILURE_RATE_MAX < 0 || UNEXPECTED_FAILURE_RATE_MAX > 1) {
  throw new Error('UNEXPECTED_FAILURE_RATE_MAX must be between 0 and 1')
}

const stageTargets = [5, 10, 20, 50, 100, 200, 500, 1000]
  .filter((target) => target < MAX_VUS)
if (MAX_VUS > 1) {
  stageTargets.push(MAX_VUS)
}
stageTargets.push(MAX_VUS)

const acceptedPosts = new Counter('telemetry_posts_accepted')
const httpErrors = new Counter('telemetry_http_errors')
const originalRequests = new Counter('telemetry_original_requests')
const originalsAccepted = new Counter('telemetry_originals_accepted')
const duplicateRequests = new Counter('telemetry_duplicate_requests')
const duplicatesAccepted = new Counter('telemetry_duplicates_accepted')
const intentionalInvalidRequests = new Counter('telemetry_intentional_invalid_requests')
const expectedValidationErrors = new Counter('telemetry_expected_validation_errors')
const validationScenarioFailures = new Counter('telemetry_validation_scenario_failures')
const unexpectedFailures = new Counter('telemetry_unexpected_failures')
const duplicateRequestRate = new Rate('telemetry_duplicate_request_rate')
const intentionalErrorRate = new Rate('telemetry_intentional_error_rate')
const unexpectedFailureRate = new Rate('telemetry_unexpected_failure_rate')

const thresholds = {
  http_req_duration: [`p(95)<${P95_MAX_MS}`],
  checks: [`rate>=${1 - CHECK_FAILURE_RATE_MAX}`],
  telemetry_posts_accepted: ['count>0'],
  telemetry_original_requests: ['count>0'],
  telemetry_unexpected_failure_rate: [`rate<=${UNEXPECTED_FAILURE_RATE_MAX}`],
  telemetry_duplicate_request_rate: [
    `rate>=${Math.max(0, DUPLICATE_PERCENT / 100 - TRAFFIC_RATE_TOLERANCE)}`,
    `rate<=${Math.min(1, DUPLICATE_PERCENT / 100 + TRAFFIC_RATE_TOLERANCE)}`,
  ],
  telemetry_intentional_error_rate: [
    `rate>=${Math.max(0, INTENTIONAL_ERROR_PERCENT / 100 - TRAFFIC_RATE_TOLERANCE)}`,
    `rate<=${Math.min(1, INTENTIONAL_ERROR_PERCENT / 100 + TRAFFIC_RATE_TOLERANCE)}`,
  ],
}
if (DUPLICATE_PERCENT > 0) {
  thresholds.telemetry_duplicate_requests = ['count>0']
}
if (INTENTIONAL_ERROR_PERCENT > 0) {
  thresholds.telemetry_intentional_invalid_requests = ['count>0']
}

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
  thresholds,
}

function uuidV4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const randomNibble = Math.floor(Math.random() * 16)
    const value = character === 'x' ? randomNibble : (randomNibble & 0x3) | 0x8
    return value.toString(16)
  })
}

function createValidPayload() {
  return {
    eventId: uuidV4(),
    vehicleId: `k6-${RUN_ID}-${__VU}`,
    latitude: 40.7128,
    longitude: -74.006,
    timestamp: new Date().toISOString(),
    speed: 45,
  }
}

function isExpectedValidationProblem(response) {
  if (response.status !== 400) return false
  try {
    return response.json('code') === 'REQUEST_VALIDATION_FAILED'
  } catch (_error) {
    return false
  }
}

function postTelemetry(payload, requestType) {
  if (requestType === 'original') originalRequests.add(1)
  if (requestType === 'duplicate') duplicateRequests.add(1)
  if (requestType === 'intentional_invalid') intentionalInvalidRequests.add(1)

  duplicateRequestRate.add(requestType === 'duplicate')
  intentionalErrorRate.add(requestType === 'intentional_invalid')

  const response = http.post(`${BASE_URL}/api/telemetry`, JSON.stringify(payload), {
    headers: { 'Content-Type': 'application/json' },
    tags: {
      name: 'POST /api/telemetry',
      request_type: requestType,
    },
  })

  const expected = requestType === 'intentional_invalid'
    ? isExpectedValidationProblem(response)
    : response.status === 202

  if (response.status === 202) {
    acceptedPosts.add(1)
    if (requestType === 'original') originalsAccepted.add(1)
    if (requestType === 'duplicate') duplicatesAccepted.add(1)
  } else {
    httpErrors.add(1, { status: String(response.status) })
  }

  if (requestType === 'intentional_invalid' && !expected
    && (response.status === 202 || response.status === 400)) {
    validationScenarioFailures.add(1)
  }
  if (requestType === 'intentional_invalid' && expected) {
    expectedValidationErrors.add(1)
  }

  const unexpected = !expected
  unexpectedFailureRate.add(unexpected)
  if (unexpected) unexpectedFailures.add(1, { request_type: requestType })

  check(response, {
    [`${requestType} response matches the expected contract`]: () => expected,
  })
}

let cachedOriginalCycle = -1
let cachedOriginalPayload

export default function () {
  const cycleIndex = Math.floor(__ITER / ITERATIONS_PER_RATIO_CYCLE)
  const cycleSlot = __ITER % ITERATIONS_PER_RATIO_CYCLE

  if (cycleSlot < ORIGINAL_PERCENT) {
    const payload = createValidPayload()
    cachedOriginalCycle = cycleIndex
    cachedOriginalPayload = payload
    postTelemetry(payload, 'original')
  } else if (cycleSlot < ORIGINAL_PERCENT + DUPLICATE_PERCENT) {
    if (cachedOriginalCycle !== cycleIndex || !cachedOriginalPayload) {
      throw new Error('Duplicate slot has no valid original payload in its VU cycle')
    }
    postTelemetry(cachedOriginalPayload, 'duplicate')
  } else {
    const invalidPayload = {
      ...createValidPayload(),
      latitude: 91,
    }
    postTelemetry(invalidPayload, 'intentional_invalid')
  }

  sleep(THINK_TIME_SECONDS)
}
