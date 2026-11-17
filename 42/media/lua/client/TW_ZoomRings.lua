-- Source ScopeZoomHandler mechanics; actual channel FOV, no writes to sight state.
local C=require('TW/Core')
local R=require('TW/Runtime')
local Render=require('TW/Render')
local G=require('TW/GeometryState')
local S=require('TW/SightState')
local Data=require('TW/Data/ZoomRings')
local Z={data=Data,players=setmetatable({},{__mode='k'})}
TWZoomRings=Z

local function finite(n) return type(n)=='number' and n==n and math.abs(n)<math.huge end

-- C.sightChannels of a part at a path: a pure function of the two (player report 23.10.2026: built every tick).
local channelCache={}
local function sightChannels(tpl,path)
    local byPath=channelCache[tpl]
    if not byPath then byPath={};channelCache[tpl]=byPath end
    local channels=byPath[path]
    if not channels then channels=C.sightChannels(tpl,path);byPath[path]=channels end
    return channels
end

-- The same TWOptic.channels storage read by SightState. A nonselected optic
-- keeps its own persisted position, including when two copies share a mesh.
-- The entries may be the shared snapshot's (R.view): each ring's state is read with G.entryState, nothing is set.
function Z.rows(weapon,key,tree,entries,parts)
    local rows,folded={},nil
    for _,entry in ipairs(entries) do
        local row=Data[entry.tpl]
        if row then
            local state
            state,folded=G.entryState(key,tree,entry,folded)
            local variant=row.variants[state or 'base'] or row.variants.base
            local part=parts[entry.path]
            local md=part and part:getModData()
            local optic=md and md.TWOptic
            local channels=type(optic)=='table' and optic.channels
            local fov=row.wide
            -- ADO's top collimator is another channel; only the adjustable
            -- physical camera drives this ring, even when that dot is selected.
            for _,channel in ipairs(sightChannels(entry.tpl,entry.path)) do
                if S.range(channel) then
                    local state=type(channels)=='table' and channels[channel.storage or tostring(channel.channel)]
                    if type(state)=='table' and finite(state.fov) then fov=state.fov end
                    break
                end
            end
            rows[#rows+1]={anchor=Render.anchorName(entry.tpl,entry.copy),tpl=entry.tpl,
                original=variant.original,body=variant.body.model,ring=variant.ring.model,fov=fov}
        end
    end
    return rows
end

function Z.update(player)
    local api=TWZoomRingsNative
    if not api or not player then return end
    local weapon=player:getPrimaryHandItem()
    local key=R.key(weapon)
    if not key then
        if Z.players[player] then api.off(player);Z.players[player]=nil end
        return
    end
    -- Without a zoom-ring part the rows are empty: checked on the shared snapshot (R.view), with no fresh read and no
    -- annotation every frame. A ring's rows read the live channel state and mode/range states of the same parts from
    -- the snapshot too (player report 23.10.2026: a fresh read and an annotation of every entry each tick).
    local tree,shown,parts=(R.view or R.read)(weapon)
    local ring=false
    for _,entry in ipairs(shown) do if Data[entry.tpl] then ring=true;break end end
    if not ring then
        api.update(player,weapon,Z.none)
    else
        if R.tickMemoOff then tree,shown,parts=R.read(weapon) end -- QA A/B: the former fresh read
        api.update(player,weapon,Z.rows(weapon,key,tree,shown,parts))
    end
    Z.players[player]=true
end
Z.none={}

-- Runs after SightInput's actual-FOV advance on the same per-frame event.
Events.OnTickEvenPaused.Add(function()
    if not TWZoomRingsNative then return end
    for i=0,getNumActivePlayers()-1 do Z.update(getSpecificPlayer(i)) end
    local online=getOnlinePlayers()
    if online then for i=0,online:size()-1 do Z.update(online:get(i)) end end
end)

return Z
