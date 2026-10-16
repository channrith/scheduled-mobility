-- Results:
--  1 = stored and published
--  0 = ignored: equal or newer measurement exists
-- -1 = conflict: reload the previous location and retry

if #KEYS ~= 1 or #ARGV ~= 12 then
    return redis.error_reply('Expected 1 key and 12 arguments')
end

local key = KEYS[1]

local incomingSeconds = tonumber(ARGV[1])
local incomingNanos = tonumber(ARGV[2])
local retentionSeconds = tonumber(ARGV[8])
local expectedRecordedAt = ARGV[9]
local eventsChannel = ARGV[10]

-- Validate script arguments before changing stored data.
if not incomingSeconds or not incomingNanos then
    return redis.error_reply('Invalid measurement timestamp')
end

if not retentionSeconds
        or retentionSeconds < 1
        or retentionSeconds ~= math.floor(retentionSeconds) then
    return redis.error_reply('Invalid location retention')
end

if eventsChannel == '' then
    return redis.error_reply('Location events channel is required')
end

-- Check that the position Java validated is still current.
local currentRecordedAt = redis.call('HGET', key, 'recordedAt')

if expectedRecordedAt == '' then
    if redis.call('EXISTS', key) == 1 then
        return -1
    end
else
    if not currentRecordedAt
            or currentRecordedAt ~= expectedRecordedAt then
        return -1
    end
end

-- Prevent older or equal measurements from overwriting data.
local currentSeconds = redis.call('HGET', key, 'recordedAtSeconds')
local currentNanos = redis.call('HGET', key, 'recordedAtNanos')

if currentSeconds then
    local seconds = tonumber(currentSeconds)
    local nanos = tonumber(currentNanos)

    if not seconds or not nanos then
        return redis.error_reply('Invalid stored location timestamp')
    end

    if incomingSeconds < seconds then
        return 0
    end

    if incomingSeconds == seconds and incomingNanos <= nanos then
        return 0
    end
end

-- Build the event before modifying storage.
local event = cjson.encode({
    schemaVersion = 1,
    type = 'driver.location.updated',
    eventId = ARGV[12],
    driverId = ARGV[11],
    latitude = tonumber(ARGV[3]),
    longitude = tonumber(ARGV[4]),
    accuracyMeters = tonumber(ARGV[5]),
    recordedAt = ARGV[6],
    receivedAt = ARGV[7]
})

-- Save the accepted measurement.
redis.call('HSET', key,
        'recordedAtSeconds', ARGV[1],
        'recordedAtNanos', ARGV[2],
        'latitude', ARGV[3],
        'longitude', ARGV[4],
        'accuracyMeters', ARGV[5],
        'recordedAt', ARGV[6],
        'receivedAt', ARGV[7]
)

redis.call('EXPIRE', key, retentionSeconds)

-- Broadcast only newly stored measurements.
redis.call('PUBLISH', eventsChannel, event)

return 1