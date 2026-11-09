-- Store one driver ping atomically: noise filter, "never overwrite a newer position", then the write.
-- KEYS[1]  driver:location:<userId>  (hash read by core-app's dispatch: lat, lng, recordedAt, ...)
-- KEYS[2]  driver:positions          (GEO set, member = user id)
-- KEYS[3]  driver:state:<userId>     (hash: lastSeen here; status and tripId are written by core-app)
-- ARGV[1]  user id               ARGV[2]  latitude        ARGV[3]  longitude
-- ARGV[4]  recordedAt (epoch ms) ARGV[5]  accuracy m ('' if unknown)
-- ARGV[6]  speed m/s ('')        ARGV[7]  heading degrees ('')
-- ARGV[8]  now (epoch ms)        ARGV[9]  TTL seconds for both hashes
-- ARGV[10] max accuracy m        ARGV[11] max speed m/s
-- ARGV[12] speed check window ms (the implied-speed check only compares with a position this recent, so one bad
--          position that slipped through can't block a driver for longer than the window)
-- Returns 'stored', or why the ping was dropped: 'stale', 'accuracy', 'speed'.
local ttl = tonumber(ARGV[9])

-- Any authenticated ping shows the driver app is alive, even one we drop.
redis.call('HSET', KEYS[3], 'lastSeen', ARGV[8])
redis.call('EXPIRE', KEYS[3], ttl)

local accuracy = tonumber(ARGV[5])
if accuracy ~= nil and accuracy > tonumber(ARGV[10]) then
  return 'accuracy'
end

local lat = tonumber(ARGV[2])
local lng = tonumber(ARGV[3])
local at = tonumber(ARGV[4])
local prev = redis.call('HMGET', KEYS[1], 'lat', 'lng', 'recordedAt')
local prevAt = tonumber(prev[3])
if prevAt ~= nil then
  if prevAt >= at then
    return 'stale'
  end
  local prevLat = tonumber(prev[1])
  local prevLng = tonumber(prev[2])
  if prevLat ~= nil and prevLng ~= nil and at - prevAt <= tonumber(ARGV[12]) then
    local function rad(x) return x * math.pi / 180 end
    local dLat = rad(lat - prevLat)
    local dLng = rad(lng - prevLng)
    local a = math.sin(dLat / 2) ^ 2 + math.cos(rad(prevLat)) * math.cos(rad(lat)) * math.sin(dLng / 2) ^ 2
    local meters = 2 * 6371000 * math.asin(math.min(1, math.sqrt(a)))
    if meters / ((at - prevAt) / 1000) > tonumber(ARGV[11]) then
      return 'speed'
    end
  end
end

redis.call('HSET', KEYS[1], 'lat', ARGV[2], 'lng', ARGV[3], 'recordedAt', ARGV[4], 'accuracy', ARGV[5],
  'speed', ARGV[6], 'heading', ARGV[7])
redis.call('EXPIRE', KEYS[1], ttl)
redis.call('GEOADD', KEYS[2], lng, lat, ARGV[1])
return 'stored'
