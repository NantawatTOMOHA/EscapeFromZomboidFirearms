-- Project A-Life NPC shells (IsoZombie bodies) show their weapon in the hands or on the body like a
-- player, but TW_RenderWatch watches only players and world items. A-Life keeps the weapon it presents
-- on each shell in its own cache (Animations.weaponVisuals); those weapons and the shells' hand items get
-- the render-only refresh here, so their installed parts stay visible after the render pool reuses a copy.
-- Without A-Life the scan finds nothing. An MP spectator's copy comes from A-Life's own message
-- (instanceItem of the type): it shows the default build, see TW/ALifeCompat.
local R=require('TW/Runtime')
local W={stats={scans=0,shells=0,weapons=0},reported=false,PERIOD=30}
TWALifeClient=W
local tick=0

local function show(item,seen)
    if item and not seen[item] and R.visualKey(item) then
        seen[item]=true
        R.ensureVisible(item)
        W.stats.weapons=W.stats.weapons+1
    end
end

function W.scan()
    local PA=rawget(_G,'ProjectALife')
    local anim=type(PA)=='table' and PA.Animations or nil
    local visuals=type(anim)=='table' and anim.weaponVisuals or nil
    if type(visuals)~='table' then return 0 end
    W.stats.scans=W.stats.scans+1
    local seen,shells={},0
    for shell,entry in pairs(visuals) do
        shells=shells+1
        if type(entry)=='table' then show(entry.item,seen) end
        if shell and shell.getPrimaryHandItem then
            show(shell:getPrimaryHandItem(),seen)
            show(shell:getSecondaryHandItem(),seen)
        end
    end
    W.stats.shells=shells
    return shells
end

Events.OnTick.Add(function()
    tick=tick+1
    if tick%W.PERIOD~=0 or isServer() then return end
    local ok,err=pcall(W.scan)
    if not ok and not W.reported then
        W.reported=true
        print('[TW] A-Life render watch skipped: '..tostring(err))
    end
end)

return W
