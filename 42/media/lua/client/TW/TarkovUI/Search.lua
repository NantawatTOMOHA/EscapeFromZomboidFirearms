-- Search by name in the Tarkov screens (the part grid of a slot, OPEN ..., SELECT WEAPON): every
-- word of the query must occur in one of the names, case-insensitive and ignoring spaces and
-- punctuation, so "ekp818" finds EKP-8-18 and "гп 34" finds ГП-34. In the game lower() is Java's
-- (every alphabet); "ё" counts as "е".
local S={}

local function plain(text)
    text=tostring(text or ''):lower():gsub('ё','е')
    return (text:gsub('[%s%-%._/%(%),"\'+]',''))
end

-- The words of a query, or nil when it has none (everything matches).
function S.query(text)
    local words={}
    for word in tostring(text or ''):gmatch('%S+') do
        word=plain(word)
        if word~='' then words[#words+1]=word end
    end
    return #words>0 and words or nil
end

-- The names of an entry, prepared once.
function S.text(...)
    local parts={}
    for i=1,select('#',...) do
        local value=select(i,...)
        if value then parts[#parts+1]=plain(value) end
    end
    return table.concat(parts,'|')
end

function S.matches(query,text)
    if not query then return true end
    for _,word in ipairs(query) do
        if not text:find(word,1,true) then return false end
    end
    return true
end

return S
