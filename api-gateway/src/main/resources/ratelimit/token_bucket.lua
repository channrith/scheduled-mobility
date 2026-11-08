-- One token bucket, atomically: refill by elapsed time, take a token if there is one.
-- KEYS[1]  the bucket (a hash: t = tokens left, ts = last update in ms)
-- ARGV[1]  capacity (burst)
-- ARGV[2]  tokens added back per millisecond
-- Returns {allowed (1/0), milliseconds until the next token when refused}.
-- Time comes from Redis, so several gateway instances share one clock.
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local bucket = redis.call('HMGET', KEYS[1], 't', 'ts')
local tokens = tonumber(bucket[1])
local updated = tonumber(bucket[2])
if tokens == nil or updated == nil then
  tokens = capacity
  updated = now
end
tokens = math.min(capacity, tokens + math.max(0, now - updated) * rate)

local allowed = 0
local wait = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  wait = math.ceil((1 - tokens) / rate)
end

redis.call('HSET', KEYS[1], 't', tostring(tokens), 'ts', tostring(now))
-- A full bucket is the same as no bucket: let Redis forget it once it would have refilled.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate) + 1000)
return {allowed, wait}
