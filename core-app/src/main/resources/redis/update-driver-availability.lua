-- Java validates the complete old representation (including the ISO instant) before calling us.
-- Check that exact snapshot before any mutation, so concurrent malformed data can never be overwritten.
-- -1: snapshot changed, re-read and validate; 0: stale expectedVersion; 1: changed; 2: unchanged.
-- Versions are decimal strings supplied by Java: Lua doubles cannot represent the full long range.
local fields = redis.call('HGETALL', KEYS[1])
local current = {}
for i = 1, #fields, 2 do
    current[fields[i]] = fields[i + 1]
end

if ARGV[1] == 'missing' then
    if #fields ~= 0 then return -1 end
else
    if current.status ~= ARGV[2] or current.version ~= ARGV[3] or current.updatedAt ~= ARGV[4] then
        return -1
    end
end

if ARGV[5] ~= ARGV[6] then return 0 end
if ARGV[2] == ARGV[7] then return 2 end
if ARGV[8] == '' then return redis.error_reply('Availability version exhausted') end

redis.call('HSET', KEYS[1], 'status', ARGV[7], 'version', ARGV[8], 'updatedAt', ARGV[9])
return 1
