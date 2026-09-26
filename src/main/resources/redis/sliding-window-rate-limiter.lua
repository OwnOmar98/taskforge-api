-- Sliding window log: each allowed request is recorded as a member of a
-- sorted set, scored by its own timestamp. Trimming everything older than
-- the window on every call, then counting what's left, gives a true sliding
-- window (unlike a fixed-window counter, which lets a client burst up to
-- 2x the limit around a window boundary).
--
-- KEYS[1] = rate limit key
-- ARGV[1] = now (epoch millis)
-- ARGV[2] = window size (millis)
-- ARGV[3] = max requests allowed per window
-- ARGV[4] = unique member for this request
-- ARGV[5] = key TTL (seconds) - cleanup safety net, not the correctness mechanism
--
-- Returns {1, 0} if the request is allowed, or {0, retryAfterMillis} if
-- rejected - retryAfterMillis is how long until the oldest request in the
-- window ages out and a slot frees up.

local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local member = ARGV[4]
local ttlSeconds = tonumber(ARGV[5])

redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

if redis.call('ZCARD', key) >= limit then
  local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
  local oldestScore = tonumber(oldest[2])
  return {0, oldestScore + window - now}
end

redis.call('ZADD', key, now, member)
redis.call('EXPIRE', key, ttlSeconds)
return {1, 0}
