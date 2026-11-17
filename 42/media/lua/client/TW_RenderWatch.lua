-- Render-only refresh for the mod weapons this client sees but does not hold:
-- slung/holstered items, weapons of other players and weapons lying in the
-- world. Item placement lists are transient, so each client rebuilds them.
local R=require('TW/Runtime')
local Render=require('TW/Render')
local tick=0

local function attached(player)
    local list=player.getAttachedItems and player:getAttachedItems()
    if not list then return end
    for i=0,list:size()-1 do
        local entry=list:get(i)
        local item=entry and entry:getItem()
        if R.visualKey(item) then R.ensureVisible(item) end
    end
end

local function watchPlayer(player)
    if not player then return end
    local primary,secondary=player:getPrimaryHandItem(),player:getSecondaryHandItem()
    if not player:isLocalPlayer() then
        if R.visualKey(primary) then R.ensureVisible(primary) end
        if R.visualKey(secondary) then R.ensureVisible(secondary) end
    end
    attached(player)
end

-- Weapons on the ground (bug wave 324, 24.10.2026, the user's item 4: "models have no normal look on the ground - some
-- items are not shown at all, others crooked or unrealistic, others without half of their parts"). The engine draws a
-- ground weapon from its render copy and part list (TW/Render: rested on its side by the copy's `world`); they are
-- rebuilt when the weapon reaches the ground - a loaded square (LoadGridsquare) and every other way in (Java
-- GroundItems: InventoryItem.setWorldItem - drops from hands or bags, timed actions, loot, network, Gunworks) - and,
-- after a copy was taken for another assembly, by the ground pass below. A rebuilt look drops the engine's cached
-- render of the earlier one (TWGroundItemsNative.redraw: the item's atlas texture and the render chunk).
local ground={}
local GROUND_PASS=60     -- ticks between ground passes
local GROUND_NEAR=30     -- tiles: weapons this close to a local player keep their copies first
local function native() return rawget(_G,'TWGroundItemsNative') end
local function show(item)
    if R.ensureVisible(item,true) then
        local n=native()
        if n and n.redraw then n.redraw(item) end
    end
end
local function arrive(item)
    if not (item and R.visualKey(item)) then return end
    ground[item]=true
    show(item)
end
local function near(object)
    local x,y=object:getX(),object:getY()
    for i=0,getNumActivePlayers()-1 do
        local p=getSpecificPlayer(i)
        if p and math.abs(p:getX()-x)<=GROUND_NEAR and math.abs(p:getY()-y)<=GROUND_NEAR then return true end
    end
    return false
end
-- Every GROUND_PASS ticks: drop the weapons that left the ground, touch the near ones that still own their copy, then
-- rebuild the near ones that lost it into copies the pass did not touch (TW/Render groundOnly).
local function groundPass()
    Render.groundEpoch=Render.clock+1
    local waiting,gone={},{}
    for item in pairs(ground) do
        local object=item:getWorldItem()
        if not (object and object:getSquare()) then gone[#gone+1]=item
        elseif near(object) then
            if Render.current(item) then Render.touch(item) else waiting[#waiting+1]=item end
        end
    end
    -- Picked up keeps its copy (an inventory item); discarded (no container) lets it go. Not removed inside pairs.
    for _,item in ipairs(gone) do
        ground[item]=nil
        if not item:getContainer() then R.forget(item) end
    end
    for _,item in ipairs(waiting) do show(item) end
end
TWRenderWatchGround={items=ground,pass=groundPass}

Events.OnTick.Add(function()
    tick=tick+1
    local n=native()
    local list=n and n.poll and n.poll()
    if list then
        for i=0,list:size()-1 do
            local object=list:get(i)
            if object:getSquare() then arrive(object:getItem()) end
        end
    end
    if tick%GROUND_PASS==0 then groundPass() end
    if tick%30~=0 then return end
    for i=0,getNumActivePlayers()-1 do watchPlayer(getSpecificPlayer(i)) end
    if isClient() then
        local players=getOnlinePlayers()
        for i=0,players:size()-1 do watchPlayer(players:get(i)) end
    end
end)

Events.LoadGridsquare.Add(function(square)
    local objects=square and square:getWorldObjects()
    if not objects then return end
    for i=0,objects:size()-1 do arrive(objects:get(i):getItem()) end
end)

-- The square unloads (its items are discarded; a reload makes new ones): forget what the line above registered
-- (performance pass 23.10.2026: Kahlua tables have no weak keys, every weapon ever seen on the floor stayed in memory).
if Events.ReuseGridsquare then Events.ReuseGridsquare.Add(function(square)
    local objects=square and square:getWorldObjects()
    if not objects then return end
    for i=0,objects:size()-1 do
        local item=objects:get(i):getItem()
        if item and R.visualKey(item) then ground[item]=nil;R.forget(item) end
    end
end) end

-- A flying launcher round (bug wave 324, item 4): Gunworks draws it with the round's own item (worldModel,
-- Render3DItem), whose model now rests on the ground (tools/generate_scripts.py rest_world). The flight instance alone
-- takes the copy with the earlier zero `world` (MF_<tpl> of the ordnance round A_<tpl>; InventoryItem.getWorldStaticItem
-- reads modData worldStaticModel first), so the round in the air looks as before and the same round lying down rests.
local Launchers=require('TW/Data/Launchers')
local flight={}
for full in pairs(Launchers.ordnance or {}) do
    local tpl=string.match(full,'^EscapeFromZomboidFirearms%.A_(%w+)$')
    if tpl then flight[full]='EscapeFromZomboidFirearms.MF_'..tpl end
end
local function flightModels()
    pcall(require,'ExplosivesSystems/Client')
    local ok,E=pcall(require,'ExplosivesSystems/Physics')
    if not ok or type(E)~='table' or type(E.spawnVisualOrdnance)~='function' or E.twFlightModels then return end
    local spawn=E.spawnVisualOrdnance
    E.spawnVisualOrdnance=function(args)
        local before=E.visualOrdnance and #E.visualOrdnance or 0
        local result=spawn(args)
        local list=E.visualOrdnance
        local visual=list and #list>before and list[#list]
        local item=visual and visual.itemObj
        local model=item and flight[item:getFullType()]
        if model and getScriptManager():getModelScript(model) then item:getModData().worldStaticModel=model end
        return result
    end
    E.twFlightModels=true
end
-- Kahlua has no next(): a non-empty table is checked with pairs.
local flying=false
for _ in pairs(flight) do flying=true;break end
if not isServer() and flying then
    Events.OnGameBoot.Add(flightModels)
    Events.OnGameStart.Add(flightModels)
end
