-- Bandits V2 bandits (IsoZombie bodies with a brain in their ModData) show the weapon they were given
-- on their body (Bandit.applyAttachments) and in their hands (Bandit.SetHands), but TW_RenderWatch
-- watches only players and world items. Their pack weapons and the shells' hand items get the
-- render-only refresh here, so the installed parts stay visible. Without Bandits the scan finds
-- nothing; without the adapter (option off) there is no pack weapon to draw.
local R=require('TW/Runtime')
local W={stats={scans=0,bandits=0,weapons=0},reported=false,PERIOD=30}
TW_BanditsClient=W
local tick=0

local function show(item,seen)
    if item and not seen[item] and R.visualKey(item) then
        seen[item]=true
        R.ensureVisible(item)
        W.stats.weapons=W.stats.weapons+1
    end
end

local function attached(zombie,seen)
    local list=zombie.getAttachedItems and zombie:getAttachedItems()
    if not list then return end
    for i=0,list:size()-1 do
        local entry=list:get(i)
        show(entry and entry:getItem(),seen)
    end
end

function W.scan()
    local Z=rawget(_G,'BanditZombie')
    local cache=type(Z)=='table' and Z.CacheLightB or nil
    if type(cache)~='table' then return 0 end
    W.stats.scans=W.stats.scans+1
    local seen,count={},0
    for id in pairs(cache) do
        local zombie=type(Z.GetInstanceById)=='function' and Z.GetInstanceById(id) or nil
        if zombie then
            count=count+1
            if zombie.getPrimaryHandItem then
                show(zombie:getPrimaryHandItem(),seen)
                show(zombie:getSecondaryHandItem(),seen)
            end
            attached(zombie,seen)
        end
    end
    W.stats.bandits=count
    return count
end

Events.OnTick.Add(function()
    tick=tick+1
    if tick%W.PERIOD~=0 or isServer() then return end
    local ok,err=pcall(W.scan)
    if not ok and not W.reported then
        W.reported=true
        print('[TW] Bandits render watch skipped: '..tostring(err))
    end
end)

return W
