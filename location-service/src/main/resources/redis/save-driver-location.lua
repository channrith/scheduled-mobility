-- Results:
--  1 = location stored, presence refreshed, event published
--  0 = duplicate or older measurement ignored
-- -1 = concurrent change; reload and retry
--
-- KEYS[1] = location:driver:{driverId}:latest
-- KEYS[2] = location:driver:{driverId}:presence
-- KEYS[3] = driver:location:<userId> (dispatch mirror)
-- KEYS[4] = driver:positions (dispatch GEO index)
-- Requires standalone Redis, as configured in local/staging; keys span hash slots.
--
-- ARGV[1]  = recordedAt epoch seconds
-- ARGV[2]  = recordedAt nanoseconds
-- ARGV[3]  = latitude
-- ARGV[4]  = longitude
-- ARGV[5]  = accuracyMeters
-- ARGV[6]  = recordedAt ISO instant
-- ARGV[7]  = server receivedAt ISO instant
-- ARGV[8]  = location retention seconds
-- ARGV[9]  = expected previous recordedAt, empty if absent
-- ARGV[10] = events channel
-- ARGV[11] = driverId
-- ARGV[12] = eventId
-- ARGV[13] = presence timeout seconds
-- ARGV[14] = userId, empty for repository-only compatibility callers (no dispatch mirror)
-- ARGV[15] = recordedAt epoch milliseconds

-- Validate the script contract.
if #KEYS ~= 4 or #ARGV ~= 15 then
    return redis.error_reply('Expected 4 keys and 15 arguments')
end

local locationKey = KEYS[1]
local presenceKey = KEYS[2]

if locationKey == ''
        or presenceKey == ''
        or locationKey == presenceKey then
    return redis.error_reply(
            'Distinct location and presence keys are required'
    )
end

-- Numeric validation helpers.
local function finiteNumber(value)
    if type(value) ~= 'string' and type(value) ~= 'number' then
        return nil
    end

    local number = tonumber(value)

    if not number
            or number ~= number
            or number == math.huge
            or number == -math.huge then
        return nil
    end

    return number
end

local function integerInRange(value, minimum, maximum)
    local number = finiteNumber(value)

    if not number
            or number ~= math.floor(number)
            or number < minimum
            or number > maximum then
        return nil
    end

    return number
end

local MAX_EXACT_INTEGER = 9007199254740991

-- Explicit TTL storage limit: approximately 68 years.
local MAX_TTL_SECONDS = 2147483647

-- Parse arguments once.
local incomingSeconds = integerInRange(
        ARGV[1],
        -MAX_EXACT_INTEGER,
        MAX_EXACT_INTEGER
)

local incomingNanos = integerInRange(
        ARGV[2],
        0,
        999999999
)

local latitude = finiteNumber(ARGV[3])
local longitude = finiteNumber(ARGV[4])
local accuracyMeters = finiteNumber(ARGV[5])

local recordedAt = ARGV[6]
local receivedAt = ARGV[7]

local retentionSeconds = integerInRange(
        ARGV[8],
        1,
        MAX_TTL_SECONDS
)

local expectedRecordedAt = ARGV[9]
local eventsChannel = ARGV[10]
local driverId = ARGV[11]
local eventId = ARGV[12]

local presenceTimeoutSeconds = integerInRange(
        ARGV[13],
        1,
        MAX_TTL_SECONDS
)

-- Validate values before any writes.
if not incomingSeconds or not incomingNanos then
    return redis.error_reply('Invalid measurement timestamp')
end

if not latitude or latitude < -90 or latitude > 90 then
    return redis.error_reply('Invalid latitude')
end

if not longitude or longitude < -180 or longitude > 180 then
    return redis.error_reply('Invalid longitude')
end

if not accuracyMeters or accuracyMeters < 0 then
    return redis.error_reply('Invalid accuracy')
end

if recordedAt == '' or receivedAt == '' then
    return redis.error_reply('Location timestamps are required')
end

if not retentionSeconds then
    return redis.error_reply('Invalid location retention')
end

if not presenceTimeoutSeconds then
    return redis.error_reply('Invalid presence timeout')
end

if eventsChannel == '' then
    return redis.error_reply('Location events channel is required')
end

if driverId == '' or eventId == '' then
    return redis.error_reply('Driver ID and event ID are required')
end

-- Check that Java's validation baseline is still current.
-- A conflict must not change location, presence, or publish events.
local currentRecordedAt = redis.call(
        'HGET',
        locationKey,
        'recordedAt'
)

if expectedRecordedAt == '' then
    if redis.call('EXISTS', locationKey) == 1 then
        return -1
    end
else
    if not currentRecordedAt
            or currentRecordedAt ~= expectedRecordedAt then
        return -1
    end
end

-- Reject duplicate or older measurements.
local currentSeconds = redis.call(
        'HGET',
        locationKey,
        'recordedAtSeconds'
)

local currentNanos = redis.call(
        'HGET',
        locationKey,
        'recordedAtNanos'
)

if currentRecordedAt or currentSeconds or currentNanos then
    local storedSeconds = integerInRange(
            currentSeconds,
            -MAX_EXACT_INTEGER,
            MAX_EXACT_INTEGER
    )

    local storedNanos = integerInRange(
            currentNanos,
            0,
            999999999
    )

    if not storedSeconds or not storedNanos then
        return redis.error_reply(
                'Invalid stored location timestamp'
        )
    end

    if incomingSeconds < storedSeconds then
        return 0
    end

    if incomingSeconds == storedSeconds
            and incomingNanos <= storedNanos then
        return 0
    end
end

-- Encode before writing so encoding failures cannot follow a save.
local event = cjson.encode({
    schemaVersion = 1,
    type = 'driver.location.updated',
    eventId = eventId,
    driverId = driverId,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    recordedAt = recordedAt,
    receivedAt = receivedAt
})

-- Preflight dispatch mirror key types before any writes (Lua errors do not roll back).
local mirrorDispatch = ARGV[14] ~= ''
if mirrorDispatch then
    local hashType = redis.call('TYPE', KEYS[3]).ok
    local geoType = redis.call('TYPE', KEYS[4]).ok
    if (hashType ~= 'none' and hashType ~= 'hash')
            or (geoType ~= 'none' and geoType ~= 'zset') then
        return redis.error_reply('Invalid dispatch location key type')
    end
end
if latitude < -85.05112878 or latitude > 85.05112878 then
    return redis.error_reply('Latitude is outside the Redis GEO range')
end

-- Store the accepted location.
redis.call(
        'HSET',
        locationKey,
        'recordedAtSeconds', ARGV[1],
        'recordedAtNanos', ARGV[2],
        'latitude', ARGV[3],
        'longitude', ARGV[4],
        'accuracyMeters', ARGV[5],
        'recordedAt', recordedAt,
        'receivedAt', receivedAt
)

redis.call(
        'EXPIRE',
        locationKey,
        retentionSeconds
)

-- Refresh presence using the server timestamp.
redis.call(
        'SET',
        presenceKey,
        receivedAt,
        'EX',
        presenceTimeoutSeconds
)

-- Maintain main's dispatch hash/GEO contract in this same atomic operation.
if mirrorDispatch then
    redis.call('HSET', KEYS[3], 'lat', ARGV[3], 'lng', ARGV[4],
            'accuracy', ARGV[5], 'recordedAt', ARGV[15])
    redis.call('EXPIRE', KEYS[3], retentionSeconds)
    redis.call('GEOADD', KEYS[4], longitude, latitude, ARGV[14])
end

-- Publish only accepted updates.
redis.call(
        'PUBLISH',
        eventsChannel,
        event
)

return 1