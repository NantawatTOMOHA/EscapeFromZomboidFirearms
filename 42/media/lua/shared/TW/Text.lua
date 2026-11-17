-- Characters of a string in both Lua flavours the mod runs in. In the game
-- (Kahlua) a string holds UTF-16 code units, one per character (Cyrillic is
-- 1040..1103); plain Lua 5.1 (offline tests) holds UTF-8 bytes. A lead byte
-- groups only with real continuation bytes, so both read correctly, while a
-- UTF-8 byte pattern would drop every Cyrillic letter in Kahlua.
local Text={}

function Text.chars(text)
    local result={}
    local i,n=1,#text
    while i<=n do
        local c=text:byte(i)
        local size=c>=0xF0 and c<=0xF4 and 4 or c>=0xE0 and c<0xF0 and 3 or c>=0xC2 and c<0xE0 and 2 or 1
        for k=1,size-1 do
            local d=text:byte(i+k)
            if not d or d<0x80 or d>0xBF then size=1; break end
        end
        result[#result+1]=text:sub(i,i+size-1)
        i=i+size
    end
    return result
end

return Text
