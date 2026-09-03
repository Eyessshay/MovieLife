local seatCount = tonumber(ARGV[4])
local tokenKey = KEYS[seatCount + 1]

if redis.call('EXISTS', tokenKey) == 1 then
    return 2
end

for i = 1, seatCount do
    if redis.call('EXISTS', KEYS[i]) == 1 then
        return 1
    end
end

for i = 1, seatCount do
    redis.call('SET', KEYS[i], ARGV[1])
    redis.call('PEXPIREAT', KEYS[i], ARGV[3])
end

redis.call('SET', tokenKey, ARGV[2])
redis.call('PEXPIREAT', tokenKey, ARGV[3])

return 0
