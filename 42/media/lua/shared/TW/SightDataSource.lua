-- Client geometry is requested by template; server mechanics use shared metadata.
local S={}
local cache={optics={},channels={}}
local failed=false

function S.get(resource,key)
    if failed or (isServer and isServer()) then return end
    local zb=rawget(_G,'TWZB')
    if type(zb)~='table' or not zb.sightData then return end
    local pool=cache[resource]
    if not pool then return end
    if pool[key]~=nil then return pool[key] or nil end
    local ok,value=pcall(zb.sightData,resource,key)
    if not ok then failed=true;return end
    pool[key]=value or false
    return value
end

function S.attach(data,resource)
    local function pool(group)
        local rows=data[group]
        setmetatable(rows,{__index=function(t,key)
            local row=S.get(resource,group..'/'..tostring(key))
            if row then rawset(t,key,row) end
            return row
        end})
    end
    if resource=='optics' then pool('materials');pool('textures') else pool('alignment') end
    -- Populate only this item's dependencies as real entries: Java Kahlua
    -- consumers use rawget, which deliberately does not invoke __index.
    local function dependencies(value)
        if type(value)=='table' then
            for _,v in pairs(value) do dependencies(v) end
        elseif type(value)=='string' then
            if value:match('^m_%x+$') then local _=data.materials[value]
            elseif value:match('^r_%x+$') then local _=data.textures[value] end
        end
    end
    function data.item(tpl)
        if not data.items[tpl] then return end
        local full=S.get(resource,'items/'..tpl)
        if full then
            if data.items[tpl]~=full then
                if resource=='optics' then dependencies(full) end
                data.items[tpl]=full
            end
            return full
        end
        return data.items[tpl]
    end
    return setmetatable(data,{__index=function(t,key)
        if key=='provenance' and resource=='channels' then
            local row=S.get(resource,key)
            if row then rawset(t,key,row) end
            return row
        end
    end})
end

return S
