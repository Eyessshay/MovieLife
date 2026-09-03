local metadataJson = redis.call('GET', KEYS[1])
if not metadataJson then
    return 0
end

local decoded, metadata = pcall(cjson.decode, metadataJson)
if not decoded
        or type(metadata) ~= 'table'
        or type(metadata.showtimeId) ~= 'number'
        or type(metadata.seatIds) ~= 'table'
        or #metadata.seatIds == 0 then
    return 1
end

for i = 1, #metadata.seatIds do
    if type(metadata.seatIds[i]) ~= 'number' then
        return 1
    end
end

for i = 1, #metadata.seatIds do
    local seatKey = ARGV[2]
            .. tostring(metadata.showtimeId)
            .. ':'
            .. tostring(metadata.seatIds[i])
    if redis.call('GET', seatKey) == ARGV[1] then
        redis.call('DEL', seatKey)
    end
end

redis.call('DEL', KEYS[1])
return 0
