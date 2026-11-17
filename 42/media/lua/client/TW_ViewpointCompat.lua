-- Client policy for optional Viewpoint add-ons. Only our weapon fullTypes and
-- STT's public item flag are changed; no foreign profiles or hooks are installed.
local D=require('TW/Data')
local R=require('TW/Runtime')
local Launchers=require('TW/Data/Launchers')
local V={}
TWViewpoint=V
local options,active
local reported=false
local seen=setmetatable({},{__mode='k'})
local fullTypes={}
for fullType in pairs(D.byType) do fullTypes[fullType]=true end
for _,row in ipairs(Launchers.underbarrels or {}) do fullTypes[row.weapon]=true end
if Launchers.disposable then
    fullTypes[Launchers.disposable.weapon]=true
    fullTypes[Launchers.disposable.spent]=true
end

-- All capability queries share this cache. B42 can prefix active IDs with '\'.
local function detected()
    if active then return active end
    local found={}
    if getActivatedMods then
        local ok=pcall(function()
            local mods=getActivatedMods()
            if not mods then return end
            for i=0,mods:size()-1 do
                local id=tostring(mods:get(i))
                while string.sub(id,1,1)=='\\' do id=string.sub(id,2) end
                found[id]=true
            end
        end)
        if not ok then found={} end
    end
    active=found
    return active
end

local function option(id,default)
    local entry=options and options:getOption(id)
    if entry then return entry:getValue()==true end
    return default
end

function V.enabled() return option('ViewpointSupport',true) and detected().Viewpoint==true end
function V.stt() return detected().ShootToThrill==true or detected().ShootToThrillMultiplayer==true end
function V.pvads() return detected().ProjectViewpointADS==true end
function V.vtb() return detected().ViewpointTrueBallistics==true end
function V.giveToStt() return option('ViewpointGiveToStt',false) end
-- Viewpoint draws its 3D view while our bridge is off (an unsupported Viewpoint version): the mod's
-- isometric overlays (sensor cones, beams, light cones) belong to the isometric frame and are not
-- drawn over Viewpoint's (FB1, 17.10.2026: a night vision cone over the whole first-person frame).
function V.isoBlocked()
    local zb=rawget(_G,'TWZB')
    if type(zb)~='table' or not zb.vpViewActive or not zb.vpState then return false end
    local ok,active=pcall(zb.vpViewActive)
    if not ok or active~=true then return false end
    local ok2,state=pcall(zb.vpState)
    return ok2 and state==0
end

function V.apply(weapon)
    if not weapon or not fullTypes[weapon:getFullType()] or not instanceof(weapon,'HandWeapon') then return end
    seen[weapon]=true
    local md=weapon:getModData()
    if not V.enabled() or not V.stt() then
        -- Remove a saved exclusion we set when support or the add-on is absent.
        -- A pre-existing manual exclusion is not ours to remove here.
        if md.TWViewpointSttDisabled then
            md.ISeeThem_DisabledComponent=nil
            md.TWViewpointSttDisabled=nil
        end
    elseif V.giveToStt() then
        md.ISeeThem_DisabledComponent=nil
        md.TWViewpointSttDisabled=nil
    elseif md.ISeeThem_DisabledComponent~=true then
        md.ISeeThem_DisabledComponent=true
        md.TWViewpointSttDisabled=true
    end
end

local function inventory(container,visited)
    if not container or visited[container] then return end
    visited[container]=true
    local items=container:getItems()
    for i=0,items:size()-1 do
        local item=items:get(i)
        V.apply(item)
        if instanceof(item,'InventoryContainer') then inventory(item:getInventory(),visited) end
    end
end

local function playerInventory(player)
    if not player or not player:isLocalPlayer() then return end
    V.apply(player:getPrimaryHandItem())
    V.apply(player:getSecondaryHandItem())
    inventory(player:getInventory(),{})
end

function V.refresh()
    for weapon in pairs(seen) do V.apply(weapon) end
    for i=0,getNumActivePlayers()-1 do playerInventory(getSpecificPlayer(i)) end
end

function V.bindOptions(settings)
    options=settings
end

-- Item scripts call this member by name. Keep the assembly and launcher
-- initializers intact, including wrappers installed later by TW/Launchers.
local create=R.createDefault
R.createDefault=function(weapon)
    V.apply(weapon)
    return create(weapon)
end

local function start()
    active=nil
    local mods=detected()
    if not reported then
        reported=true
        local policy=V.enabled() and V.stt() and (V.giveToStt() and 'allow TW' or 'exclude TW') or 'inactive'
        print('[TW-VP] Viewpoint='..tostring(mods.Viewpoint==true)..
            ' ShootToThrill='..tostring(mods.ShootToThrill==true)..
            ' ShootToThrillMultiplayer='..tostring(mods.ShootToThrillMultiplayer==true)..
            ' ProjectViewpointADS='..tostring(V.pvads())..' ViewpointTrueBallistics='..tostring(V.vtb())..
            '; support='..tostring(V.enabled())..'; STT policy='..policy)
        if V.pvads() then
            print('[TW-VP] WARNING: PVADS applies generic ADS to unregistered TW weapons; no public per-weapon opt-out was found. TW registers no PVADS profiles; ADS/arms may conflict.')
        end
        if V.vtb() then
            print('[TW-VP] WARNING: Until TW ballistics is ready, VTB handles TW bullet flight. TW grenade launchers with VTB are untested; TW registers no VTB profiles.')
        end
    end
    V.refresh()
end

local lastSupport,lastGive
-- Container updates come in bursts (every transfer, every looted item): one refresh on the next tick for all of
-- them, not a walk of every seen weapon and the whole inventory per event (performance pass 23.10.2026).
local containersChanged=false
Events.OnTick.Add(function()
    local support,give=option('ViewpointSupport',true),V.giveToStt()
    if support~=lastSupport or give~=lastGive then
        lastSupport,lastGive=support,give
        containersChanged=false
        V.refresh()
    elseif containersChanged then
        containersChanged=false
        V.refresh()
    end
end)
Events.OnGameStart.Add(start)
Events.OnCreatePlayer.Add(function(playerNum,player) playerInventory(player) end)
-- Equip events also come for NPCs of other mods (Project A-Life shells are IsoZombies, game QA
-- 18.10.2026: isLocalPlayer was nil): players only.
local function localPlayer(player) return player~=nil and instanceof(player,'IsoPlayer') and player:isLocalPlayer() end
Events.OnEquipPrimary.Add(function(player,weapon)
    if localPlayer(player) then V.apply(weapon) end
end)
Events.OnEquipSecondary.Add(function(player,weapon)
    if localPlayer(player) then V.apply(weapon) end
end)
Events.OnPlayerUpdate.Add(function(player)
    if not player or not player:isLocalPlayer() then return end
    V.apply(player:getPrimaryHandItem())
    V.apply(player:getSecondaryHandItem())
end)
Events.OnContainerUpdate.Add(function() containersChanged=true end)
Events.LoadGridsquare.Add(function(square)
    local objects=square and square:getWorldObjects()
    if not objects then return end
    for i=0,objects:size()-1 do V.apply(objects:get(i):getItem()) end
end)
-- Its square unloads: the item is discarded (Kahlua tables keep their keys alive, seen held every one).
if Events.ReuseGridsquare then Events.ReuseGridsquare.Add(function(square)
    local objects=square and square:getWorldObjects()
    if not objects then return end
    for i=0,objects:size()-1 do
        local item=objects:get(i):getItem()
        if item then seen[item]=nil end
    end
end) end
return V
