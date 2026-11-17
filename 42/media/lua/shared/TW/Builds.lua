-- Theoretical builds: drafts and named builds per player and weapon, stored in
-- the player's modData, and a text code to share them. Nothing here touches an
-- item; a build becomes real only through the planned assembly actions.
local C=require('TW/Core')
local D=C.data
local B={MAX=40,PREFIX='TWB1',PREFIX2='TWB2',NAME_LENGTH=40}

local function store(player)
    local md=player:getModData()
    if type(md.TWBuilds)~='table' then md.TWBuilds={} end
    local s=md.TWBuilds
    if type(s.saved)~='table' then s.saved={} end
    if type(s.drafts)~='table' then s.drafts={} end
    return s
end

-- Multiplayer (B42): the player's modData is kept by the server; a change made on the client reaches
-- it only through transmitModData, as vanilla sends its own (ISHotbar.savePosition, the crafting
-- favourites). Without it the saved builds and drafts were gone after the next connection.
local function persist(player)
    if isClient() and player and player.transmitModData then player:transmitModData() end
end

local function sortedPaths(tree)
    local paths={}
    for path in pairs(tree) do paths[#paths+1]=path end
    table.sort(paths,function(a,b)
        local da,db=C.depth(a),C.depth(b)
        if da~=db then return da<db end
        return a<b
    end)
    return paths
end

-- Rebuild a tree part by part with the current rules; unknown parts and parts
-- the rules refuse (for example after a mod update) are dropped.
function B.sanitize(key,tree)
    local result,dropped={},0
    if not D.weapons[key] or type(tree)~='table' then return result,0 end
    for _,path in ipairs(sortedPaths(tree)) do
        local tpl=tree[path]
        local nextTree=type(path)=='string' and type(tpl)=='string' and D.items[tpl] and C.install(key,result,path,tpl) or nil
        if nextTree then result=nextTree else dropped=dropped+1 end
    end
    return result,dropped
end

function B.cleanName(name)
    name=tostring(name or ''):gsub('[%c|]',' '):gsub('^%s+',''):gsub('%s+$','')
    if #name>B.NAME_LENGTH then name=name:sub(1,B.NAME_LENGTH) end
    return name
end

function B.list(player,key)
    local result={}
    for _,b in ipairs(store(player).saved[key] or {}) do
        if type(b)=='table' and type(b.name)=='string' and type(b.tree)=='table' then
            result[#result+1]={name=b.name,tree=(B.sanitize(key,b.tree))}
        end
    end
    return result
end

function B.find(player,key,name)
    for _,b in ipairs(B.list(player,key)) do if b.name==name then return b end end
end

-- Weapons with saved builds (the OPEN window lists them all, as Tarkov's).
function B.weapons(player)
    local keys={}
    for key,list in pairs(store(player).saved) do
        if D.weapons[key] and type(list)=='table' and #list>0 then keys[#keys+1]=key end
    end
    table.sort(keys)
    return keys
end

function B.save(player,key,name,tree)
    name=B.cleanName(name)
    if name=='' then return false,'buildName' end
    if not D.weapons[key] or not C.validate(key,tree) then return false,'invalidBuild' end
    local s=store(player)
    s.saved[key]=s.saved[key] or {}
    local list=s.saved[key]
    for _,b in ipairs(list) do
        if b.name==name then b.tree=C.copy(tree); persist(player); return true,'buildUpdated' end
    end
    if #list>=B.MAX then return false,'tooManyBuilds' end
    list[#list+1]={name=name,tree=C.copy(tree)}
    table.sort(list,function(a,b) return a.name:lower()<b.name:lower() end)
    persist(player)
    return true,'buildSaved'
end

function B.delete(player,key,name)
    local list=store(player).saved[key] or {}
    for i,b in ipairs(list) do
        if b.name==name then table.remove(list,i); persist(player); return true end
    end
    return false,'buildNotFound'
end

-- The last edited draft per weapon survives closing the window.
function B.draft(player,key)
    local tree=store(player).drafts[key]
    if type(tree)~='table' then return nil end
    return (B.sanitize(key,tree))
end

function B.setDraft(player,key,tree)
    store(player).drafts[key]=C.copy(tree)
    persist(player)
end

-- Build codes (PUBLISH ... and OPEN ...). A code holds only the weapon's key, Tarkov's template ids
-- and slot names, which are the same on every computer with this mod, so it opens the identical
-- build in another game; parts a later version of the mod no longer has are dropped and counted.
--   TWB2|<weapon>|<name>|<parts>|<check>
--   name   '-' or 4 hex digits per character (a UTF-16 unit in the game)
--   parts  '-' or <parent>.<slot>=<template> joined by ',', parents first: <parent> is the 1-based
--          number of the part that carries the slot, 0 the weapon; the slot drops its 'mod_' prefix
--          ('!' keeps a name without one) and '_' becomes '-' (chat apps format underscores)
--   check  Adler-32 of everything before it (8 hex digits): a cut or edited code is refused
-- The TWB1 codes of earlier versions (TWB1|<weapon>|<path>=<template>|...) still open.
local HEX='0123456789abcdef'
local function hex(n,digits)
    local s=''
    for _=1,digits do
        local d=n%16
        s=HEX:sub(d+1,d+1)..s
        n=(n-d)/16
    end
    return s
end
local function adler(text)
    local a,b=1,0
    for i=1,#text do
        a=(a+text:byte(i))%65521
        b=(b+a)%65521
    end
    return hex(b*65536+a,8)
end
local function encodeName(name)
    if name=='' then return '-' end
    local out={}
    for i=1,#name do out[i]=hex(name:byte(i),4) end
    return table.concat(out)
end
local function decodeName(field)
    if field=='-' then return '' end
    if #field%4~=0 or field:find('[^0-9a-f]') then return nil end
    local chars={}
    for i=1,#field,4 do chars[#chars+1]=string.char(tonumber(field:sub(i,i+3),16)) end
    return table.concat(chars)
end
local function slotCode(slot)
    local short=slot:sub(1,4)=='mod_' and slot:sub(5) or '!'..slot
    return (short:gsub('_','-'))
end
local function slotName(code)
    local name=code:gsub('%-','_')
    if name:sub(1,1)=='!' then return name:sub(2) end
    return 'mod_'..name
end

function B.encode(key,tree,name)
    local index,parts={},{}
    for i,path in ipairs(sortedPaths(tree)) do
        index[path]=i
        local parent=C.parent(path)
        parts[i]=(parent=='' and 0 or index[parent])..'.'..slotCode(C.slot(path))..'='..tree[path]
    end
    local body=table.concat({B.PREFIX2,key,encodeName(B.cleanName(name)),#parts>0 and table.concat(parts,',') or '-'},'|')
    return body..'|'..adler(body)
end

local function decodeV1(text)
    local fields={}
    for field in text:gmatch('[^|]+') do fields[#fields+1]=field end
    if fields[1]~=B.PREFIX or not D.weapons[fields[2]] then return nil,'badCode' end
    local key,raw=fields[2],{}
    for i=3,#fields do
        local path,tpl=fields[i]:match('^([%w_/]+)=(%w+)$')
        if not path then return nil,'badCode' end
        raw[path]=tpl
    end
    local tree,dropped=B.sanitize(key,raw)
    return key,tree,dropped,''
end

-- Returns key, tree, dropped count, name; or nil, reason.
function B.decode(text)
    text=tostring(text or ''):gsub('%s','')
    if text:sub(1,#B.PREFIX+1)==B.PREFIX..'|' then return decodeV1(text) end
    local fields={}
    for field in text:gmatch('[^|]+') do fields[#fields+1]=field end
    if fields[1]~=B.PREFIX2 then return nil,'badCode' end
    if #fields~=5 or adler(fields[1]..'|'..fields[2]..'|'..fields[3]..'|'..fields[4])~=fields[5]:lower() then return nil,'damagedCode' end
    local key=fields[2]
    if not D.weapons[key] then return nil,'unknownWeaponCode' end
    local name=decodeName(fields[3]:lower())
    if not name then return nil,'badCode' end
    local raw,paths={},{}
    if fields[4]~='-' then
        for part in fields[4]:gmatch('[^,]+') do
            local parent,slot,tpl=part:match('^(%d+)%.([%w%-!]+)=(%w+)$')
            parent=tonumber(parent)
            if not parent or parent>#paths or slot:find('!',2,true) then return nil,'badCode' end
            local path=(parent==0 and '' or paths[parent]..'/')..slotName(slot)
            paths[#paths+1]=path
            raw[path]=tpl
        end
    end
    local tree,dropped=B.sanitize(key,raw)
    return key,tree,dropped,B.cleanName(name)
end

return B
