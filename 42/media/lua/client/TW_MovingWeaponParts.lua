-- A read-only visual description of the held assembly. Native state belongs to each item.
local R=require('TW/Runtime')
local C=require('TW/Core')
local Render=require('TW/Render')
local M={cache=setmetatable({},{__mode='k'}),players=setmetatable({},{__mode='k'}),failures=setmetatable({},{__mode='k'})}
TWMovingWeaponParts=M

local function binding(key,tree,entries)
    local rows={}
    for _,e in ipairs(entries) do
        local matrix=C.renderMatrix(key,C.placement(key,tree,e.path))
        if matrix then
            local flat={}
            for i=1,3 do for j=1,4 do flat[#flat+1]=matrix[i][j] end end
            rows[#rows+1]={anchor=Render.anchorName(e.tpl,e.copy),path=e.path,matrix=flat}
        end
    end
    return rows
end

local function update(p)
    if not p then return end
    local api=TWMovingWeaponPartsNative
    if not api then return end
    local gun=p:getPrimaryHandItem()
    local key=R.key(gun)
    if not key then
        if M.players[p] then api.off(p);M.players[p]=nil end
        return
    end
    -- The shared snapshot (R.view): its tree is the same table until a part changes, so the rows are re-derived only then.
    local tree,entries=(R.view or R.read)(gun)
    local cached=M.cache[gun]
    if not cached or cached.tree~=tree then
        local fingerprint=Render.fingerprint(key,entries)
        if not cached or cached.fingerprint~=fingerprint then cached={fingerprint=fingerprint,rows=binding(key,tree,entries)} end
        cached.tree=tree
        M.cache[gun]=cached
    end
    api.update(p,gun,key,cached.rows)
    M.players[p]=true
end

-- Kahlua has no xpcall and no debug.traceback (calling nil paused the game on the first tick).
local function safeUpdate(p)
    if not p then return end
    local ok,why=pcall(update,p)
    if not ok then
        why=tostring(why)
        local api=TWMovingWeaponPartsNative
        if api and api.off then pcall(api.off,p) end
        M.players[p]=nil
        if M.failures[p]~=why then print('[TW] moving weapon parts Lua request rejected; '..why);M.failures[p]=why end
    end
end

Events.OnTick.Add(function()
    if not TWMovingWeaponPartsNative then return end
    for i=0,getNumActivePlayers()-1 do
        local p=getSpecificPlayer(i)
        safeUpdate(p)
    end
    local online=getOnlinePlayers()
    if online then for i=0,online:size()-1 do safeUpdate(online:get(i)) end end
end)

return M
