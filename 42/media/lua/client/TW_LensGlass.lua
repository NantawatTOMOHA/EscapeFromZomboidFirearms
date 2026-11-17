-- Warm private meshes at hip; open only the selected part's completed ADS window.
local V=require('TW_ViewpointCompat')
local C=require('TW/Core')
local R=require('TW/Runtime')
local Sight=require('TW/SightState')
local Render=require('TW/Render')
local L={}
TWLensGlass=L
local previous,previousKey,previousWeapon
-- C.entries of the last shared snapshot tree (player report 23.10.2026: a fresh read and a sorted entry list every
-- aiming tick). Nobody changes the snapshot's tree: the same table, the same entries.
local entriesTree,entriesKey,entriesList

-- entries: C.entries(tree,key) when the caller already has them.
function L.selection(view,key,tree,entries)
    if not view or not view.modeProfile or (view.kind~='optic' and view.kind~='collimator'
        and view.kind~='thermal' and view.kind~='nv') then return end
    for _,entry in ipairs(entries or C.entries(tree,key)) do
        if entry.path==view.path and entry.tpl==view.tpl then
            return {key=view.weapon:getID()..':'..view.path..':'..view.tpl..':'..view.channel..':'..view.mode,
                tpl=view.tpl,profile=view.modeProfile.id,anchor=Render.anchorName(entry.tpl,entry.copy)}
        end
    end
end

function L.update()
    if not TWZB or not TWZB.lensGlassEnable then return end
    local enabled=V.enabled()==true
    if enabled~=previous then
        TWZB.lensGlassEnable(enabled)
        previous=enabled
    end
    if not TWZB.lensGlassSet then return end
    local player=getSpecificPlayer(0)
    local view=enabled and player and not player:isDead() and (TWZB and TWZB.aimIntent or player.isAiming)(player) and Sight.current(player)
    local key=view and R.key(view.weapon)
    local selection
    if key then
        local tree,_,_,_,shared
        if R.tickMemoOff then tree=R.tree(view.weapon) else tree,_,_,_,shared=(R.view or R.read)(view.weapon) end -- QA A/B: as before
        local entries
        if shared then
            if tree~=entriesTree or key~=entriesKey then entriesTree,entriesKey,entriesList=tree,key,C.entries(tree,key) end
            entries=entriesList
        end
        selection=L.selection(view,key,tree,entries)
    end
    local weapon=selection and view.weapon or nil
    local signature=selection and (selection.key..':'..selection.anchor..':'..selection.profile) or ''
    if signature~=previousKey or weapon~=previousWeapon then
        TWZB.lensGlassSet(player,weapon,selection)
        previousKey,previousWeapon=signature,weapon
    end
end
Events.OnTickEvenPaused.Add(L.update)
Events.OnGameStart.Add(function() previous,previousKey,previousWeapon=nil,nil,nil; L.update() end)
return L
