# Decision 008: Rate limiting at the gateway

**Status:** accepted (step 2.7) · **Service:** gateway-service

## Problem
Nothing limited how fast one client could call us: unlimited password guessing on `/api/auth/login`, bots hammering
`POST /api/orders` in a flash sale, and floods that reach every service. Limits should live at the edge (one place,
before any service does work), be shared by all gateway instances, and fail in a way that doesn't take the shop down.

## 1. Algorithm: token bucket (chosen)
| Algorithm | How | Bursts | Cost | Weak spot |
|---|---|---|---|---|
| **Token bucket (chosen)** | A bucket of `burstCapacity` tokens refills at `replenishRate`/s; each request takes `requestedTokens` | Allows a short burst up to the bucket size, then a smooth sustained rate | 2 keys per client, one atomic Lua script per request | Two parameters to explain instead of one |
| Fixed window | Count requests per calendar window (e.g. per minute) | Up to **2× the limit** across a window boundary (end of one minute + start of the next) | 1 counter, `INCR` + `EXPIRE` | Boundary burst |
| Sliding window log | Store every request time, count those in the last N seconds | Exact | One entry per request (memory grows with traffic) | Memory/CPU at high rates |
| Sliding window counter | Weighted mix of the current and previous fixed window | Close to exact | 2 counters | An approximation |

Token bucket fits people (a few quick clicks are fine) and bots (a sustained flood is not). It's also what Spring
Cloud Gateway ships: `RedisRateLimiter` runs one Lua script, so the check-and-take is atomic in Redis, even with
several gateway instances.

## 2. Where the buckets live: Redis (chosen) vs in-memory per gateway instance
- Redis: one limit across all gateway instances (N instances don't mean N × the limit), and buckets survive a
  gateway restart.
- In-memory (Bucket4j, Resilience4j RateLimiter): no network hop and no Redis dependency, but each instance
  counts on its own.

## 3. Key: route + user id from the JWT, else route + client IP (chosen)
- Logged-in users get their own bucket, so many users behind one office NAT don't block each other. Proven by
  mutation: keyed by IP only, user B got 429 because of user A's orders.
- Anonymous routes (login, register, browsing) can only use the IP.
- The route id is part of the key, so the strict login bucket and the general auth bucket of the same IP stay
  separate (tested: after the login limit, register still works).
- **`X-Forwarded-For` is not trusted:** a client could send a new value per request and never be limited. On the
  VM the gateway is the edge, so the TCP peer is the client. If a load balancer is ever put in front, trust that
  header only when the request comes from the balancer's address.

## 4. Limits (in application.yml; policy, not measurements)
| Route | Key | replenishRate / burstCapacity / requestedTokens | Meaning |
|---|---|---|---|
| `POST /api/auth/login` | IP | 1 / 60 / 12 | 5 attempts at once, then 1 per 12 s (5 per minute) |
| `/api/auth/**` (other) | IP | 5 / 10 / 1 | register/refresh/logout: normal use passes, scripted sign-up spam doesn't |
| `/api/products/**` | user or IP | 20 / 40 / 1 | browsing never notices; a scraper does |
| `POST /api/orders` | user | 2 / 5 / 1 | a few quick buys, not a bot loop |
| `/api/orders/**` (other) | user | 20 / 40 / 1 | reading own orders |

"N per minute" with a bucket: make one request cost several tokens. 12 tokens per login with 1 token/s refill means
one login per 12 s sustained, and a 60-token bucket holds 5 logins.

## 5. Response: 429 ProblemDetail + Retry-After (chosen)
The stock filter answers 429 with an empty body. `RateLimitProblemFilter` wraps the response and writes RFC 7807
JSON, plus `Retry-After` = requested tokens ÷ refill rate, rounded up (login: 12 s, orders: 1 s). That way clients
know when to come back, like with any other error from our API.

## 6. Redis down: fail open (chosen)
- Spring Cloud Gateway's `RedisRateLimiter` already allows the request when Redis errors. We keep that and make it
  cheap: 500 ms connect/command timeouts, and the Redis health check is off, so the gateway stays UP. Measured
  without Redis: 10 logins all passed, the first in 0.9–1.1 s (the connection attempt; 2 runs), then 24–46 ms each.
- **Why open:** the limiter protects the services; it isn't part of the business flow. Failing closed turns a
  Redis outage into a full outage while every service is healthy.
- **What we lose meanwhile:** brute-force protection on login. BCrypt still makes each guess slow, and Day 4 adds an
  alert on limiter errors.
- **Alternative (not built):** fail closed only for login, with a small in-memory bucket per instance as a
  fallback. That's stricter for the one endpoint where abuse matters most, but it's more code and the limit is
  per instance.

## What this does not stop
- **Credential stuffing spread over many IPs** (a botnet): each IP stays under the limit. That needs a per-account
  limit (key = email from the login body) and/or lockout with backoff in auth-service (docs/todo.md).
- **Large distributed floods (DDoS):** belong in front of the gateway (cloud firewall, CDN).
- Redis memory is `allkeys-lru` (64 MB): under memory pressure a bucket can be evicted, which gives that client a
  fresh bucket (a soft fail-open). Fine for limits. Revisit before Redis holds data that must not be evicted
  (continuation plan C2).
