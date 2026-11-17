-- Native item side of the assembly: installed WeaponPart instances, state copy,
-- stats layer and rendering. Rules stay in TW/Core.
local C=require('TW/Core')
local Render=require('TW/Render')
local GeometryState=require('TW/GeometryState')
local Tactical=require('TW/Tactical')
local Stats=require('WeaponSystems/Utils/StatsFactory')
local Magazine=require('WeaponSystems/Utils/Magazine')
local D=C.data
local R={cache=setmetatable({},{__mode='k'}),reported={}}
TWRuntime=R
Render.onEvicted=function(weapon) R.cache[weapon]=nil end

-- QA A/B (player report 23.10.2026): true skips the per-tick memos of that pass (R.key, R.sights, TW_ZoomRings,
-- TW_LensGlass, TW_FirstPersonPose's assembly string), as before.
R.tickMemoOff=false

-- The last item asked about (player report 23.10.2026: some 40 calls per tick, nearly all for the held item, each two
-- reflective calls). An item's class and fullType never change, and D.byType is fixed once the data has loaded.
local lastKeyItem,lastKey
function R.key(item)
    if not item then return nil end
    if item==lastKeyItem and not R.tickMemoOff then return lastKey end
    local key=instanceof(item,'HandWeapon') and D.byType[item:getFullType()] or nil
    lastKeyItem,lastKey=item,key
    return key
end

function R.isWeapon(item)
    return R.key(item)~=nil
end

-- A deployed underbarrel renders/toggles devices as its host; it must never use
-- the host's assembly stat layer or editor, which would overwrite launcher ammo.
function R.visualKey(item)
    return R.key(item) or (TWLaunchers and TWLaunchers.hostKey(item))
end

-- Vanilla attach/detach UI never handles Tarkov parts; the assembly window does.
function R.blockNativeAttach() return false end
function R.blockNativeDetach() return false end

function R.meta(item)
    return item and D.types[item:getFullType()] or nil
end

function R.isMagazine(tpl)
    return C.isClip(tpl)
end

function R.installedPath(part)
    local meta=R.meta(part)
    if not meta then return nil end
    if R.isMagazine(meta.tpl) then return 'mod_magazine' end
    local md=part:getModData()
    return type(md.TWPath)=='string' and md.TWPath or nil
end

-- Installed parts of a weapon: tree path -> template, plus native entries.
function R.read(weapon)
    local tree,entries,parts,problem={},{},{},nil
    local list=weapon:getAllWeaponParts()
    for i=0,list:size()-1 do
        local part=list:get(i)
        local meta=R.meta(part)
        local path=R.installedPath(part)
        if meta and path then
            if tree[path] then problem='duplicatePath' end
            tree[path]=meta.tpl
            parts[path]=part
            entries[#entries+1]={path=path,tpl=meta.tpl,copy=meta.copy,fullType=part:getFullType(),part=part}
        elseif meta then
            problem=problem or 'missingPath'
        end
    end
    table.sort(entries,function(a,b) return a.path<b.path end)
    return tree,entries,parts,problem
end

-- Snapshot cache (performance pass 23.10.2026): R.read rebuilt the assembly from the Java part list, about five reflective
-- calls per part, a dozen times every frame (lights, lasers, sights, poses, cartridges...). R.view returns the same read,
-- SHARED AND READ-ONLY, while TWZB.partsVersion is unchanged: Java PartsVersion compares what R.read reads (the part
-- objects in order, their IDs, mod data tables and TWPath), so an equal version is an equal read. R.read keeps building
-- fresh tables for callers that change them. R.snapshotOff (QA A/B) or no Java part: every view is a fresh read.
R.snapshots=setmetatable({},{__mode='k'})
R.snapshotOff=false
local function snapshot(weapon)
    if R.snapshotOff or not weapon then return nil end
    local zb=rawget(_G,'TWZB')
    local version=zb and zb.partsVersion and zb.partsVersion(weapon)
    if not version or version<=0 then return nil end
    local s=R.snapshots[weapon]
    if s and s.version==version then return s end
    local tree,entries,parts,problem=R.read(weapon)
    -- R.fingerprint's parts: each part's key, and the part itself where a sight mode or range adds a state signature.
    local list=weapon:getAllWeaponParts()
    local base,states={},{}
    for i=0,list:size()-1 do
        local part=list:get(i)
        local full=part:getFullType()
        base[i+1]=tostring(part:getID())..':'..full..':'..tostring(part:getModData().TWPath)
        states[i+1]=GeometryState.hasStates(full) and part or false
    end
    s={version=version,tree=tree,entries=entries,parts=parts,problem=problem,base=base,states=states,fingerprints={}}
    R.snapshots[weapon]=s
    return s
end

-- The fifth value: true for the shared snapshot (a cache keyed on its tables holds), nil for a fresh read.
function R.view(weapon)
    local s=snapshot(weapon)
    if not s then return R.read(weapon) end
    return s.tree,s.entries,s.parts,s.problem,true
end

-- An item that left the world (TW_RenderWatch, its square unloaded): every cache keyed by it is dropped.
function R.forget(weapon)
    R.cache[weapon]=nil;R.snapshots[weapon]=nil
    if R.stateless then R.stateless[weapon]=nil end
    if R.swapWaits then R.swapWaits[weapon]=nil end
    if R.handResetAt then R.handResetAt[weapon]=nil end
    if R.handRepaired then R.handRepaired[weapon]=nil end
    if R.handWatch then
        for player,watch in pairs(R.handWatch) do
            if watch.weapon==weapon then
                R.handWatch[player]=nil
                if R.handSettling then R.handSettling[player]=nil end -- W9-anchorsqa
            end
        end
    end
    if R.handSettling then -- W9-anchorsqa
        for player in pairs(R.handSettling) do
            if player:getPrimaryHandItem()==weapon or player:getSecondaryHandItem()==weapon then
                R.handSettling[player]=nil
            end
        end
    end
    Render.forget(weapon)
end

-- Read-only tree of R.view.
function R.viewTree(weapon)
    return (R.view(weapon))
end

function R.tree(weapon)
    return (R.read(weapon))
end

function R.parts(weapon)
    local _,_,parts=R.read(weapon)
    return parts
end

function R.copyItem(source,target)
    target:copyModData(source:getModData())
    target:setID(source:getID())
    target:setCondition(source:getCondition())
    target:setFavorite(source:isFavorite())
    if source:isCustomName() then target:setName(source:getName()); target:setCustomName(true) end
    target:copyTimesRepairedFrom(source)
    target:copyConditionStatesFrom(source)
    if source.getCurrentUsesFloat and target.setCurrentUsesFloat then
        target:setCurrentUsesFloat(source:getCurrentUsesFloat())
    end
    return target
end

-- Lowest native copy type of a template that is not installed yet.
function R.freeCopy(tree,entries,tpl)
    local used={}
    for _,e in ipairs(entries) do if e.tpl==tpl then used[e.copy]=true end end
    for copy=0,(D.items[tpl].copies or 1)-1 do
        if not used[copy] then return copy end
    end
end

function R.typeFor(tpl,copy)
    return copy==0 and D.items[tpl].pz or D.items[tpl].pz..'_'..copy
end

function R.modifiers(weapon)
    local key=R.key(weapon)
    if not key then return nil end
    local p=C.pzStats(key,R.tree(weapon))
    -- In Gunworks RealAuto/RealBurst the cadence comes from the Tarkov RPM
    -- (RateOfFire.canFire) alone. Gunworks' layer sets RecoilDelay 1 so that
    -- holding the trigger keeps firing; this layer runs after it and sets 0: the
    -- engine truncates 1 x (1-Aiming/40) x (1-(2*Strength-10)/40) to 0 for most
    -- characters anyway, and a 1 left the rest a whole extra update per shot
    -- (Glock 18C, MP9N and Vector short of their RPM at 60 updates/s).
    local mode=weapon:getFireMode()
    -- The fired round's Tarkov extras (TW/Data/Ammo): applied to this layer's own
    -- values from the assembly, so they never accumulate.
    local extra=D.ammo.extras[weapon:getModData().ActiveAmmoProfile or ''] or {}
    local recoil=p.recoilDelay
    if mode=='RealAuto' or mode=='RealBurst' then recoil=0
    elseif extra.recoil then recoil=math.max(1,math.floor(recoil*extra.recoil+0.5)) end
    local sound,volume=p.soundRadius,p.soundVolume
    if extra.sound then
        sound=math.max(1,math.floor(sound*extra.sound+0.5))
        volume=math.max(1,math.floor(volume*extra.sound+0.5))
    end
    local hit=extra.hit and math.max(1,math.floor(p.hitChance*extra.hit+0.5)) or p.hitChance
    local result={Stats.Set('AimingTime',p.aimingTime),Stats.Set('RecoilDelay',recoil),Stats.Set('SoundRadius',sound),
        Stats.Set('SoundVolume',volume),Stats.Set('MaxRange',p.maxRange),Stats.Set('MaxAmmo',p.maxAmmo),Stats.Set('HitChance',hit)}
    -- The suppressed shot is a separate Tarkov sound event (TW_Sounds).
    if p.swingSound then result[#result+1]=Stats.Set('SwingSound',p.swingSound) end
    return result
end

-- Where an item is, for the screens and the actions that read or change it (user report 25.10.2026: the
-- inspect window closed at once for a weapon in a bag or in a container). PZ's reach is wider than the
-- main inventory: the player's inventory and every bag in it (ItemContainer:containsRecursive), and, on a
-- client, the containers the loot window shows - the floor and the world, bag and vehicle containers of
-- the reachable squares (ISInventoryPaneContextMenu.getContainers is the game's own list of them).
function R.inInventory(player,item)
    return player~=nil and item~=nil and player:getInventory():containsRecursive(item)==true
end

-- Depth of the bag scan (main inventory -> bag -> bag -> bag), as R.looseParts.
local REACH_DEPTH=3

-- Every item the player carries, main inventory first and then each bag inside it; the callback
-- returning true stops the walk.
function R.eachItem(player,fn)
    local function scan(container,depth)
        local items=container:getItems()
        for i=0,items:size()-1 do
            local item=items:get(i)
            if fn(item) then return true end
            if depth<REACH_DEPTH and instanceof(item,'InventoryContainer') and scan(item:getInventory(),depth+1) then return true end
        end
        return false
    end
    return player~=nil and scan(player:getInventory(),0) or false
end

-- The item with an ID in the player's inventory or a bag in it (the multiplayer action's liveness check:
-- the item's own copy reaches the server, the ID does not change with the container).
function R.inInventoryID(player,id)
    return R.eachItem(player,function(item) return item:getID()==id end)==true
end

-- Containers the loot window shows: the floor, world containers of the reachable squares and the bags and
-- vehicle part containers in them (the game's own list: ISInventoryPage:refreshBackpacks fills these buttons).
-- Client only (the server has no loot window): empty there.
function R.lootContainers(player)
    local set={}
    if player==nil or type(getPlayerLoot)~='function' then return set end
    local page=getPlayerLoot(player:getPlayerNum())
    if not page then return set end
    local ok=pcall(function()
        local pane=page.inventoryPane
        local shown=pane and pane.inventoryPage
        for _,button in ipairs(shown and shown.backpacks or {}) do
            if button.inventory then set[button.inventory]=true end
        end
    end)
    if not ok then set={} end
    return set
end

-- Whether the player can currently see and handle an item: carried, or in a container the loot window
-- shows (a crate on a reachable square, the floor, a car trunk, a bag in them). Walking away or closing
-- the container takes the item out of reach; nothing else does.
local LOOT_DEPTH=4
function R.reachable(player,item)
    if player==nil or item==nil then return false end
    if R.inInventory(player,item) then return true end
    local loot=R.lootContainers(player)
    local container=item:getContainer()
    for _=1,LOOT_DEPTH do
        if not container then return false end
        if loot[container] then return true end
        local holder=container:getContainingItem()
        container=holder and holder:getContainer() or nil
    end
    return false
end

-- Loose (not installed) Tarkov parts the player carries, by template: items
-- in the main inventory and items in bags inside it, each sorted by ID.
-- A copy-lane item (`P_<tpl>_1` …) is the same part and counts as its template (wave W8, user's item 5).
function R.looseParts(player)
    local main,nested={},{}
    local function collect(container,target,depth)
        local items=container:getItems()
        for i=0,items:size()-1 do
            local item=items:get(i)
            local meta=R.meta(item)
            if meta and not item:getModData().TWPath then
                target[meta.tpl]=target[meta.tpl] or {}
                table.insert(target[meta.tpl],item)
            elseif depth<3 and instanceof(item,'InventoryContainer') then
                collect(item:getInventory(),nested,depth+1)
            end
        end
    end
    collect(player:getInventory(),main,0)
    for _,list in pairs(main) do table.sort(list,function(a,b) return a:getID()<b:getID() end) end
    for _,list in pairs(nested) do table.sort(list,function(a,b) return a:getID()<b:getID() end) end
    return main,nested
end

-- First loose part of a template the player carries (the main inventory first, then its bags): actions
-- resolve their part lazily, because earlier steps of a build may return it.
function R.findLoose(player,tpl)
    local best
    R.eachItem(player,function(item)
        local meta=R.meta(item)
        if meta and meta.tpl==tpl and not item:getModData().TWPath and (not best or item:getID()<best:getID()) then best=item end
    end)
    return best
end

local Sight=require('TW/SightState')

-- C.sights of the weapon's shared snapshot is kept with the snapshot (player report 23.10.2026: S.current walked every
-- slot of every part each call, several times per tick). Only for the snapshot's own tree, which nobody changes; any
-- other tree (a fresh read a caller may edit) is enumerated anew. No caller changes the channel tables.
function R.sights(weapon,tree,key)
    local s=weapon and not R.tickMemoOff and R.snapshots[weapon]
    if not (s and s.tree==tree) then return C.sights(tree,key) end
    if s.sights==nil or s.sightsKey~=key then s.sights,s.sightsKey=C.sights(tree,key),key end
    return s.sights
end

-- Keep the old highest-magnification default until the user selects a channel.
-- Enumeration itself follows the original assembly/scope order.
function R.updateSight(weapon,tree,parts)
    local sights=R.sights(weapon,tree,R.key(weapon))
    local md=weapon:getModData()
    local wanted,first
    if md.TW and md.TW.sight then
        for _,s in ipairs(sights) do
            if s.path==md.TW.sight then
                first=first or s
                if s.channel==(md.TW.sightChannel or 0) then wanted=s;break end
            end
        end
    end
    wanted=wanted or first
    if not wanted then
        for _,s in ipairs(sights) do
            if s.kind~='iron' and (not wanted or (s.sight and s.sight.zoomMax or 1)>(wanted.sight and wanted.sight.zoomMax or 1)) then wanted=s end
        end
    end
    wanted=wanted or sights[1]
    local part=wanted and wanted.kind~='iron' and parts[wanted.path] or nil
    local view
    if wanted then
        view=Sight.bind(weapon,wanted,parts)
        if part and part.setMinSightRange and part.setMaxSightRange then
            local minimum,maximum=C.sightBand(view.magnification)
            part:setMinSightRange(minimum);part:setMaxSightRange(maximum)
        end
    end
    if weapon:getActiveSight()~=part then weapon:setActiveSight(part) end
    md.TW=md.TW or {}
    md.TW.activeSight=wanted and wanted.path or nil
    md.TW.activeSightChannel=wanted and wanted.channel or nil
    -- The bound view too (TW/SightState S.current returned a second bind of the same channel and parts: the same view).
    return wanted,view
end

-- Make an installed sight the active one: the choice (sight), the sight in use (activeSight, read
-- by TW/OpticView) and the native active optics part.
function R.selectSight(weapon,path,index)
    local key=R.key(weapon)
    if not key then return end
    local tree,_,parts=R.read(weapon)
    for _,s in ipairs(C.sights(tree,key)) do
        if s.path==path and (index==nil or s.channel==index) then
            local md=weapon:getModData()
            md.TW=md.TW or {}
            md.TW.sight=path
            md.TW.sightChannel=s.channel
            local active=R.updateSight(weapon,tree,parts)
            md.TW.activeSight=active and active.path or nil
            return s
        end
    end
end

function R.cycleSight(weapon)
    local key=R.key(weapon)
    if not key then return end
    local tree,_,parts=R.read(weapon)
    local sights=C.sights(tree,key)
    if #sights<2 then return end
    local current=R.updateSight(weapon,tree,parts)
    local index=1
    for i,s in ipairs(sights) do if current and s.path==current.path and s.channel==current.channel then index=i end end
    local selected=sights[index%#sights+1]
    return R.selectSight(weapon,selected.path,selected.channel)
end

local function report(weapon,reason)
    local id=tostring(weapon:getID())..':'..tostring(reason)
    if R.reported[id] then return end
    R.reported[id]=true
    print('[TW] Assembly needs attention: '..tostring(weapon:getFullType())..' '..tostring(reason))
end

-- ground: the weapon lies on the ground (TW_RenderWatch); its copy is not taken from a held one (TW/Render). False when
-- no copy was free for it.
function R.applyRender(weapon,ground)
    local key=R.key(weapon)
    if not key then return false end
    local tree,entries,parts=R.read(weapon)
    -- Through Render.groundRequest as well: a wrapper of Render.apply (TW/ReloadVisual) passes four arguments.
    Render.groundRequest=ground and true or nil
    local applied=Render.apply(weapon,key,tree,entries,ground)
    Render.groundRequest=nil
    Tactical.prepare(weapon,key,tree,parts)
    return applied~=false
end

-- The reload follows the installed magazine where Tarkov offers both kinds in one
-- slot (SKS, Mosin): the built-in box is loaded round by round (Brita B42 port's
-- boltactionnomag scheme), a detachable magazine through Gunworks.
function R.applyMagazineMode(weapon,key,tree)
    local w=D.weapons[key]
    if not w.mixedMagazine then return end
    local installed=tree.mod_magazine
    local internal=installed~=nil and not C.isClip(installed)
    local wanted=WeaponReloadType[internal and w.reloadInternal or w.reloadClip]
    if wanted and weapon:getWeaponReloadType()~=wanted then weapon:setWeaponReloadType(wanted) end
    if internal then
        if weapon:getMagazineType() then weapon:setMagazineType(nil) end
    elseif not weapon:getMagazineType() and w.magazines[1] then
        weapon:setMagazineType(w.magazines[1])
    end
end

-- A built-in magazine in the weapon: Gunworks has no magazine profile for it, so a
-- reload loads loose rounds (vanilla reload or the Gunworks AmmoList path).
function R.hasBuiltInMagazine(weapon)
    local key=R.key(weapon)
    if not key or not D.weapons[key].mixedMagazine then return false end
    local tpl=R.tree(weapon).mod_magazine
    return tpl~=nil and not C.isClip(tpl)
end
local getProfileForGun=Magazine.GetProfileForGun
function Magazine.GetProfileForGun(gun)
    if gun and R.hasBuiltInMagazine(gun) then return nil end
    return getProfileForGun(gun)
end

function R.refresh(weapon,player)
    R.refreshed=(R.refreshed or 0)+1
    local key=R.key(weapon)
    if not key then return end
    Magazine.manageMagazineAttachment(weapon)
    local tree,entries,parts,problem=R.read(weapon)
    R.applyMagazineMode(weapon,key,tree)
    local valid,reason=C.validate(key,tree)
    local s=C.stats(key,tree)
    if problem or not valid then
        s.ready=false
        report(weapon,problem or reason)
    end
    for _,modifier in ipairs(R.modifiers(weapon)) do modifier(weapon,nil) end
    Render.apply(weapon,key,tree,entries)
    local sight=R.updateSight(weapon,tree,parts)
    Tactical.prepare(weapon,key,tree,parts)
    local md=weapon:getModData()
    local previous=md.TW or {}
    md.TW={schema=2,key=key,ready=s.ready,ergonomics=s.ergonomics,recoilUp=s.recoilUp,recoilBack=s.recoilBack,weight=s.weight,
        sight=previous.sight,activeSight=sight and sight.path or nil,
        sightChannel=previous.sightChannel,activeSightChannel=sight and sight.channel or nil}
    if player and not s.ready then
        if player:getPrimaryHandItem()==weapon then player:setPrimaryHandItem(nil) end
        if player:getSecondaryHandItem()==weapon then player:setSecondaryHandItem(nil) end
    end
    if player then
        if R.handSettling then R.handSettling[player]=math.max(R.handSettling[player] or 0,2) end -- W9-anchorsqa
        player:resetEquippedHandsModels()
    end
    return s
end

function R.createDefault(weapon)
    -- No Gunworks reapply here: its shadow copies also call OnCreate.
    local key=R.key(weapon)
    if not key then return end
    local preset=C.presetTree(key)
    assert(C.validate(key,preset))
    local paths={}
    for path in pairs(preset) do paths[#paths+1]=path end
    table.sort(paths,function(a,b) if C.depth(a)==C.depth(b) then return a<b end return C.depth(a)<C.depth(b) end)
    local used={}
    for _,path in ipairs(paths) do
        local tpl=preset[path]
        local copy=used[tpl] or 0
        used[tpl]=copy+1
        local full=R.isMagazine(tpl) and C.clipType(key,tpl) or R.typeFor(tpl,copy)
        local part=assert(instanceItem(full),'[TW] Missing default item '..full)
        if not R.isMagazine(tpl) then part:getModData().TWPath=path end
        weapon:attachWeaponPart(part,false)
    end
    weapon:setContainsClip(C.isClip(preset.mod_magazine))
    weapon:setCurrentAmmoCount(0)
    R.refresh(weapon,nil)
end

local function loose(player,weapon,part)
    if not part or not R.inInventory(player,part) then return false,'notInInventory' end
    local meta=R.meta(part)
    if not meta then return false,'unknownPart' end
    if R.isMagazine(meta.tpl) then return false,'useMagazineReload' end
    -- A loose engine alias of a part (`P_<tpl>_1`, `_2` … the copy lanes of one template) is that same part and
    -- installs as its template; only a part that sits on a weapon carries TWPath (wave W8, user's item 5: the alias
    -- types looked like duplicates of the part, and only the first one worked).
    if part:getModData().TWPath then return false,'installedAlias' end
    return true,nil,meta
end

-- transfer: the weapon is in a container the player can reach but does not carry (a crate, the floor, a car
-- trunk), and the caller brings it into the inventory with a transfer action before the change (the client's
-- context menu does). The server of a multiplayer game has no loot window, so it never takes this path and
-- still refuses a weapon the player does not carry.
local function awaited(player,weapon,transfer)
    return R.inInventory(player,weapon) or (transfer and R.reachable(player,weapon)) or false
end

function R.canInstall(player,weapon,part,path,transfer)
    local key=R.key(weapon)
    if not key or not part then return false,'invalidItem' end
    if not awaited(player,weapon,transfer) then return false,'notInInventory' end
    local ok,reason,meta=loose(player,weapon,part)
    if not ok then return false,reason end
    local tree,entries=R.read(weapon)
    local nextTree
    nextTree,reason=C.install(key,tree,path,meta.tpl)
    if not nextTree then return false,reason end
    if not R.freeCopy(tree,entries,meta.tpl) then return false,'maxCopies' end
    return true,nil,nextTree
end

local function attach(player,weapon,installed,path)
    if path then installed:getModData().TWPath=path end
    weapon:attachWeaponPart(player,installed,false)
end

local function inHands(player,weapon)
    return player:getPrimaryHandItem()==weapon or player:getSecondaryHandItem()==weapon
end

-- held: the weapon was in the hands before the change (refresh unequips a weapon that lost a vital part).
local function sync(player,weapon,held)
    if syncHandWeaponFields then syncHandWeaponFields(player,weapon) end
    -- SyncHandWeaponFields reaches the owner only; other clients keep their own copy of a weapon in
    -- the hands from the last Equip packet (B42), so a part put on or taken off (or the weapon taken
    -- out of the hands) showed on the other players only after the next equip. The server sends the
    -- hands again.
    if isServer() and sendEquip and held then sendEquip(player) end
end

function R.install(player,weapon,part,path)
    local valid,reason=R.canInstall(player,weapon,part,path)
    if not valid then return false,reason end
    local meta=R.meta(part)
    local held=inHands(player,weapon)
    local tree,entries=R.read(weapon)
    local installed=instanceItem(R.typeFor(meta.tpl,R.freeCopy(tree,entries,meta.tpl)))
    if not installed then return false,'missingNativeAlias' end
    R.copyItem(part,installed)
    local applied,err=pcall(function()
        attach(player,weapon,installed,path)
        R.refresh(weapon,player)
    end)
    if not applied then
        weapon:detachWeaponPart(player,installed,false)
        pcall(R.refresh,weapon,player)
        print('[TW] Install rolled back: '..tostring(err))
        return false,'nativeApplyFailed'
    end
    -- The part leaves the container it is in: the main inventory, or the bag it was carried in.
    local holder=part:getContainer() or player:getInventory()
    holder:Remove(part)
    if sendRemoveItemFromContainer then sendRemoveItemFromContainer(holder,part) end
    sync(player,weapon,held)
    return true
end

function R.canRemove(player,weapon,path,branch,transfer)
    local key=R.key(weapon)
    if not key or not awaited(player,weapon,transfer) then return false,'notInInventory' end
    local tree=R.tree(weapon)
    if not tree[path] then return false,'empty' end
    if R.isMagazine(tree[path]) then return false,'useMagazineReload' end
    -- A built-in magazine (cylinder, tube cap, internal box) leaves only when empty.
    if path=='mod_magazine' and D.items[tree[path]].internalMagazine and (weapon:getCurrentAmmoCount()>0 or (weapon:haveChamber() and weapon:isRoundChambered())) then
        return false,'unloadFirst'
    end
    local nextTree,reason
    if branch then
        local order
        nextTree,order=C.removeBranch(key,tree,path)
        if not nextTree then return false,order end
        for _,p in ipairs(order) do if R.isMagazine(tree[p]) then return false,'useMagazineReload' end end
    else
        nextTree,reason=C.remove(key,tree,path)
        if not nextTree then return false,reason end
    end
    return true,nil,nextTree
end

local function looseCopy(part)
    local meta=R.meta(part)
    local result=instanceItem(D.items[meta.tpl].pz)
    if not result then return nil end
    R.copyItem(part,result)
    local md=result:getModData()
    md.TWPath=nil
    if type(md.TWDevice)=='table' then md.TWDevice.on=false end
    return result
end

function R.remove(player,weapon,path,branch)
    local valid,reason=R.canRemove(player,weapon,path,branch)
    if not valid then return false,reason end
    local held=inHands(player,weapon)
    local tree,_,parts=R.read(weapon)
    local order={path}
    if branch then order=select(2,C.removeBranch(R.key(weapon),tree,path)) end
    local removed,returned={},{}
    for _,p in ipairs(order) do
        local copy=looseCopy(parts[p])
        if not copy then return false,'missingLooseItem' end
        returned[#returned+1]=copy
        removed[#removed+1]=parts[p]
    end
    local applied,err=pcall(function()
        for _,part in ipairs(removed) do weapon:detachWeaponPart(player,part,false) end
        R.refresh(weapon,player)
    end)
    if not applied then
        for _,part in ipairs(removed) do
            if not weapon:getAllWeaponParts():contains(part) then weapon:attachWeaponPart(player,part,false) end
        end
        pcall(R.refresh,weapon,player)
        print('[TW] Removal rolled back: '..tostring(err))
        return false,'nativeApplyFailed'
    end
    local inv=player:getInventory()
    for _,item in ipairs(returned) do
        inv:AddItem(item)
        if sendAddItemToContainer then sendAddItemToContainer(inv,item) end
    end
    sync(player,weapon,held)
    return true
end

-- A weapon used up as the spare of a repair (TW/Fixing): every installed part leaves it as the loose part R.remove
-- returns, whatever the assembly rules say, since the receiver itself is consumed. The inserted magazine has left it
-- through the eject before; what is still shown of it (Gunworks' visual Clip) is only detached. Returns the parts given.
function R.takeApart(player,weapon)
    local list=weapon:getAllWeaponParts()
    local removed,returned={},{}
    for i=0,list:size()-1 do
        local part=list:get(i)
        local meta=R.meta(part)
        if meta and R.isMagazine(meta.tpl) then
            removed[#removed+1]=part
        elseif meta then
            local copy=looseCopy(part)
            if copy then
                removed[#removed+1]=part
                returned[#returned+1]=copy
            end
        end
    end
    for _,part in ipairs(removed) do weapon:detachWeaponPart(player,part,false) end
    local inv=player:getInventory()
    for _,item in ipairs(returned) do
        inv:AddItem(item)
        if sendAddItemToContainer then sendAddItemToContainer(inv,item) end
    end
    if syncHandWeaponFields then syncHandWeaponFields(player,weapon) end
    R.forget(weapon)
    return #returned
end

function R.canReplace(player,weapon,part,path,transfer)
    local key=R.key(weapon)
    if not key or not part then return false,'invalidItem' end
    if not awaited(player,weapon,transfer) then return false,'notInInventory' end
    local ok,reason,meta=loose(player,weapon,part)
    if not ok then return false,reason end
    local tree,entries=R.read(weapon)
    local nextTree
    nextTree,reason=C.replace(key,tree,path,meta.tpl)
    if not nextTree then return false,reason end
    local remaining={}
    for _,e in ipairs(entries) do if e.path~=path then remaining[#remaining+1]=e end end
    if not R.freeCopy(nextTree,remaining,meta.tpl) then return false,'maxCopies' end
    return true,nil,nextTree
end

function R.replace(player,weapon,part,path)
    local valid,reason=R.canReplace(player,weapon,part,path)
    if not valid then return false,reason end
    local held=inHands(player,weapon)
    local _,entries,parts=R.read(weapon)
    local previous=parts[path]
    local remaining={}
    for _,e in ipairs(entries) do if e.path~=path then remaining[#remaining+1]=e end end
    local meta=R.meta(part)
    local returned=looseCopy(previous)
    local installed=instanceItem(R.typeFor(meta.tpl,R.freeCopy(nil,remaining,meta.tpl)))
    if not returned or not installed then return false,'missingNativeAlias' end
    R.copyItem(part,installed)
    local applied,err=pcall(function()
        weapon:detachWeaponPart(player,previous,false)
        attach(player,weapon,installed,path)
        R.refresh(weapon,player)
    end)
    if not applied then
        weapon:detachWeaponPart(player,installed,false)
        weapon:attachWeaponPart(player,previous,false)
        pcall(R.refresh,weapon,player)
        print('[TW] Replacement rolled back: '..tostring(err))
        return false,'nativeApplyFailed'
    end
    -- The replaced part leaves its own container (the main inventory or the bag it was carried in); the
    -- previous one goes to the main inventory, where the player looks for it.
    local holder=part:getContainer() or player:getInventory()
    holder:Remove(part)
    local inv=player:getInventory()
    inv:AddItem(returned)
    if sendRemoveItemFromContainer then sendRemoveItemFromContainer(holder,part) end
    if sendAddItemToContainer then sendAddItemToContainer(inv,returned) end
    sync(player,weapon,held)
    return true
end

-- Debug/QA only: change the inserted magazine type the way a reload would.
-- Gunworks keeps an inserted magazine as type + rounds; rounds above the new
-- capacity are dropped.
function R.debugSetMagazine(weapon,tpl)
    local item=D.items[tpl]
    if not item or not item.clip then return false,'invalidItem' end
    local full=C.clipType(R.key(weapon),tpl)
    weapon:setMagazineType(full)
    Magazine.SaveMagazineType(weapon,full)
    weapon:setMaxAmmo(item.capacity)
    weapon:setCurrentAmmoCount(math.min(weapon:getCurrentAmmoCount(),item.capacity))
    weapon:setContainsClip(true)
    Magazine.manageMagazineAttachment(weapon,full)
    return true
end

-- Debug/QA only: replace the whole assembly by a vanilla preset with new parts.
-- Removed parts return to the inventory; the preset magazine type is inserted
-- (vanilla M700 chassis conflict with some magazines).
function R.debugApplyPreset(player,weapon,name)
    local key=R.key(weapon)
    if not key or not D.weapons[key].presets[name] then return false,'unknownPreset' end
    local tree=R.tree(weapon)
    local roots={}
    for path in pairs(tree) do
        local root=path:match('^[^/]+')
        if not (root=='mod_magazine' and C.isClip(tree[root])) then roots[root]=true end
    end
    for root in pairs(roots) do
        if tree[root] then
            local ok,reason=R.remove(player,weapon,root,true)
            if not ok then return false,reason end
        end
    end
    local preset=C.presetTree(key,name)
    local current=R.tree(weapon).mod_magazine
    if C.isClip(preset.mod_magazine) and preset.mod_magazine~=current then R.debugSetMagazine(weapon,preset.mod_magazine)
    elseif not C.isClip(preset.mod_magazine) and C.isClip(current) then
        -- A built-in magazine (SKS/Mosin box) replaces the inserted one: unload it first.
        weapon:setContainsClip(false)
        weapon:setCurrentAmmoCount(0)
        Magazine.manageMagazineAttachment(weapon)
    end
    local paths={}
    for path,tpl in pairs(preset) do if not C.isClip(tpl) then paths[#paths+1]=path end end
    table.sort(paths,function(a,b) if C.depth(a)==C.depth(b) then return a<b end return C.depth(a)<C.depth(b) end)
    local inv=player:getInventory()
    for _,path in ipairs(paths) do
        local part=inv:AddItem(instanceItem(D.items[preset[path]].pz))
        local ok,reason=R.install(player,weapon,part,path)
        if not ok then return false,reason end
    end
    return true
end

-- Server side, no player (plan 0.9.x: loot, zombie drops, world replacer): the weapon's
-- assembly becomes `tree` with new parts. Removed parts are destroyed (a found weapon never
-- had them in anyone's hands); a detachable magazine of the tree is inserted empty.
function R.applyTree(weapon,tree)
    local key=R.key(weapon)
    if not key then return false,'invalidItem' end
    local valid,reason=C.validate(key,tree)
    if not valid then return false,reason end
    local list=weapon:getAllWeaponParts()
    local installed={}
    for i=0,list:size()-1 do installed[#installed+1]=list:get(i) end
    for _,part in ipairs(installed) do
        local meta=R.meta(part)
        if meta then weapon:detachWeaponPart(part) end
    end
    local paths={}
    for path,tpl in pairs(tree) do if not R.isMagazine(tpl) then paths[#paths+1]=path end end
    table.sort(paths,function(a,b) if C.depth(a)==C.depth(b) then return a<b end return C.depth(a)<C.depth(b) end)
    local used={}
    for _,path in ipairs(paths) do
        local tpl=tree[path]
        local copy=used[tpl] or 0
        used[tpl]=copy+1
        local part=instanceItem(R.typeFor(tpl,copy))
        if not part then return false,'missingNativeAlias' end
        part:getModData().TWPath=path
        weapon:attachWeaponPart(part,false)
    end
    weapon:setCurrentAmmoCount(0)
    weapon:setRoundChambered(false)
    weapon:getModData().AmmoList=nil
    if C.isClip(tree.mod_magazine) then
        local full=C.clipType(key,tree.mod_magazine)
        weapon:setMagazineType(full)
        Magazine.SaveMagazineType(weapon,full)
        weapon:setMaxAmmo(D.items[tree.mod_magazine].capacity)
        weapon:setContainsClip(true)
        Magazine.manageMagazineAttachment(weapon,full)
    else
        weapon:setContainsClip(false)
        Magazine.manageMagazineAttachment(weapon)
    end
    R.refresh(weapon,nil)
    return true
end

function R.fingerprint(weapon,withoutModes)
    -- From the snapshot: the same string as below, rebuilt only when the parts or a sight's state signature change.
    local s=snapshot(weapon)
    if s then
        local signature
        for i,part in ipairs(s.states) do
            if part then signature=(signature or '')..i..'='..GeometryState.partSignature(part,withoutModes)..';' end
        end
        local slot=withoutModes and 2 or 1
        local cached=s.fingerprints[slot]
        if cached and cached.signature==signature then return cached.value end
        local keys={}
        for i,key in ipairs(s.base) do
            local part=s.states[i]
            keys[i]=key..(part and GeometryState.partSignature(part,withoutModes) or '')
        end
        table.sort(keys)
        local value=table.concat(keys,'|')
        s.fingerprints[slot]={signature=signature,value=value}
        return value
    end
    local list=weapon:getAllWeaponParts()
    local keys={}
    for i=0,list:size()-1 do
        local part=list:get(i)
        keys[#keys+1]=tostring(part:getID())..':'..part:getFullType()..':'..tostring(part:getModData().TWPath)
            ..GeometryState.partSignature(part,withoutModes)
    end
    table.sort(keys)
    return table.concat(keys,'|')
end

-- Render-only path for weapons the local client just sees (other players,
-- slung or world items). Stats and hands are left to the owner.
-- Bug wave 324 (24.10.2026, item 4): ground is set for a weapon on the ground (TW_RenderWatch); true is returned when
-- the drawn assembly was rebuilt (the caller drops the engine's cached ground render of the earlier look). A ground
-- weapon without a free copy stays uncached and is asked again by the next ground pass.
function R.ensureVisible(weapon,ground)
    if TWLaunchers and TWLaunchers.hostKey(weapon) then
        local stale=not Render.current(weapon)
        TWLaunchers.refreshAppearance(weapon,nil,false)
        return stale
    end
    if isServer() or not R.isWeapon(weapon) then return false end
    local fingerprint=R.fingerprint(weapon)
    if R.cache[weapon]~=fingerprint or not Render.current(weapon) then
        if not R.applyRender(weapon,ground) then return false end
        R.cache[weapon]=fingerprint
        return true
    end
    Render.touch(weapon)
    return false
end

-- FB4 (19.10.2026, user's item 9: "switching the magnification of thermal sights jerks the hands, the picture loses
-- the thermal effect for a second"): a sight mode with its own mesh state (FLIR RS-32, REAP-IR...) changed the
-- fingerprint, and the full refresh re-created the hand models (resetEquippedHandsModels): the hands' pose restarted,
-- for a frame the sight's housing was gone and the scope picture lost its pairing. A change of mode states only
-- (parts, routes and range states unchanged) of the weapon in the hands replaces just the changed part models in the
-- drawn weapon (TW/Render.swapStates, TWZB.swapPartModel); the rest of the refresh that depends on the mode is the
-- sight's range band. A mesh still loading is waited for (a few frames; the rows are preloaded on refresh).
R.stateless=setmetatable({},{__mode='k'})
R.swapWaits=setmetatable({},{__mode='k'})
function R.swapStates(player,weapon)
    if not player or isServer() or player:getPrimaryHandItem()~=weapon or not R.stateless[weapon]
        or not (rawget(_G,'TWZB') and TWZB.swapPartModel) or R.stateless[weapon]~=R.fingerprint(weapon,true) then return nil end
    local key=R.key(weapon)
    local tree,entries,parts,problem=R.read(weapon)
    if problem then return nil end
    local root=weapon:getWeaponSprite()
    local result=Render.swapStates(weapon,key,tree,entries,function(from,to) return TWZB.swapPartModel(player,root,from,to) end)
    if result=='wait' then
        local waits=(R.swapWaits[weapon] or 0)+1
        R.swapWaits[weapon]=waits<=120 and waits or nil
        return waits<=120 and 'wait' or nil
    end
    R.swapWaits[weapon]=nil
    if result=='done' then R.updateSight(weapon,tree,parts) end
    return result
end

function R.preloadStates(weapon)
    if isServer() or not (rawget(_G,'TWZB') and TWZB.preloadPartModel) then return end
    local _,entries=R.read(weapon)
    Render.preloadStates(entries,TWZB.preloadPartModel)
end

-- W9 item 16: cheap hand-model anchor / stale-copy check for the weapon held in hands.
-- Runs TWZB.handModelState only when the held item, sprite, fingerprint or pool slot changed,
-- or once per HAND_CHECK_TICKS (~1 s at 60 FPS); never rebuilds hands on a timer or on 'foreign'.
local HAND_CHECK_TICKS=60
local HAND_RESET_COOLDOWN_MS=3000
R.handResetAt=setmetatable({},{__mode='k'})
R.handRepaired=setmetatable({},{__mode='k'})
R.handWatch=setmetatable({},{__mode='k'})
R.handSettling=setmetatable({},{__mode='k'}) -- W9-anchorsqa

-- W9-anchorsqa: IsoGameCharacter.setPrimaryHandItem fires OnEquipPrimary before resetEquippedHandsModels(),
-- and resetEquippedHandsModels() defers rebuilding primaryHandModel via ModelManager.ResetEquippedNextFrame
-- until ModelManager.update() after OnPlayerUpdate. Skip checkHandModel on the equip event and while settling.
local function canCheckHandModel(player,equipEvent)
    if equipEvent then return false end
    local s=R.handSettling[player]
    if s and s>0 then
        R.handSettling[player]=s>1 and (s-1) or nil
        return false
    end
    return true
end

local function logHandRepair(weapon,reason,sprite,detail)
    local seen=R.handRepaired[weapon]
    if seen and seen[reason] then return end
    if not seen then seen={};R.handRepaired[weapon]=seen end
    seen[reason]=true
    local wname=tostring(weapon:getFullType() or weapon)
    local text=detail~=nil and tostring(detail) or ''
    if text:find('root=',1,true) and text:find('sprite=',1,true) and text:find('missing=',1,true) then
        if not text:find('weapon=',1,true) then text=text..' weapon='..wname end
        print('[TW] hand model repaired: reason='..reason..' '..text)
    else
        print('[TW] hand model repaired: reason='..reason..' root='..(text~='' and text or '?')
            ..' sprite='..tostring(sprite)..' missing=? weapon='..wname)
    end
end

local function checkHandModel(player,weapon,fingerprint)
    local zb=rawget(_G,'TWZB')
    if not (zb and zb.handModelState) then return end
    local owner=Render.owner[weapon]
    if not owner or not Render.current(weapon) then return end
    local sprite=weapon:getWeaponSprite()
    local index=owner.index
    local watch=R.handWatch[player]
    if not watch then watch={ticks=0};R.handWatch[player]=watch end
    watch.ticks=watch.ticks+1
    if not R.tickMemoOff and not watch.staleWeapon and watch.weapon==weapon and watch.sprite==sprite
        and watch.fp==fingerprint and watch.index==index and watch.ticks<HAND_CHECK_TICKS then
        return
    end
    local elapsed=watch.ticks
    watch.weapon,watch.sprite,watch.fp,watch.index,watch.ticks=weapon,sprite,fingerprint,index,0
    local state=zb.handModelState(player)
    if state=='own-missing' then
        watch.staleWeapon,watch.staleRoot=nil,nil
        local detail=zb.handModelDetail and zb.handModelDetail(player) or nil
        if Render.reconfigure(weapon) then
            watch.sprite=weapon:getWeaponSprite()
            logHandRepair(weapon,'own-missing',sprite,detail)
        end
    elseif state=='stale' then
        -- W9-anchorsqa: confirm 'stale' for the same weapon and root on two checks >= 1 tick apart;
        -- watch.staleWeapon schedules the confirmation check on the next tick instead of waiting 60 ticks.
        local detail=zb.handModelDetail and zb.handModelDetail(player) or nil
        local text=detail~=nil and tostring(detail) or ''
        local root=text:match('root=([^%s]+)') or (text~='' and text or '?')
        if watch.staleWeapon~=weapon or watch.staleRoot~=root or elapsed<1 then
            watch.staleWeapon,watch.staleRoot=weapon,root
            return
        end
        watch.staleWeapon,watch.staleRoot=nil,nil
        local now=getTimestampMs and getTimestampMs() or 0
        local last=R.handResetAt[weapon]
        if not last or now<last or now-last>=HAND_RESET_COOLDOWN_MS then
            local expected=Render.modelName(owner.key,owner.index)
            if sprite~=expected then
                if Render.snowPrepare then Render.snowPrepare(weapon,owner.key,owner.index) end
                weapon:setWeaponSprite(expected)
                if TWLaunchers and TWLaunchers.hostKey(weapon) then
                    weapon:getModData().GW_UBHostSprite=expected
                end
            end
            R.handResetAt[weapon]=now
            R.handSettling[player]=math.max(R.handSettling[player] or 0,1)
            player:resetEquippedHandsModels()
            watch.sprite=weapon:getWeaponSprite()
            logHandRepair(weapon,'stale',sprite,detail)
        end
    else
        watch.staleWeapon,watch.staleRoot=nil,nil
    end
end

function R.update(player,weapon,equipEvent) -- W9-anchorsqa
    local primary=player and player:getPrimaryHandItem()
    local primaryOurs=R.isWeapon(primary) or (primary~=nil and TWLaunchers~=nil and TWLaunchers.hostKey(primary)~=nil)
    if not weapon and player then
        if primaryOurs then weapon=primary
        else weapon=player:getSecondaryHandItem() end
    end
    if not R.isWeapon(weapon) then
        if weapon and TWLaunchers and TWLaunchers.hostKey(weapon) then
            if player and not isServer() then
                -- The host's appearance is kept by TW/Launchers (OnPlayerUpdate); only the anchor check runs here.
                local held=(weapon==primary) or (not primaryOurs and player:getSecondaryHandItem()==weapon)
                if held and canCheckHandModel(player,equipEvent) then checkHandModel(player,weapon,R.fingerprint(weapon)) end
            end
            return
        end
        if player and (R.handWatch[player] or R.handSettling[player]) and not primaryOurs then
            local sec=player:getSecondaryHandItem()
            if not (R.isWeapon(sec) or (sec~=nil and TWLaunchers~=nil and TWLaunchers.hostKey(sec)~=nil)) then
                R.handWatch[player]=nil
                R.handSettling[player]=nil
            end
        end
        return
    end
    local fingerprint=R.fingerprint(weapon)
    if R.cache[weapon]~=fingerprint or not Render.current(weapon) then
        local swapped=Render.current(weapon) and R.swapStates(player,weapon)
        if swapped=='wait' then Render.touch(weapon);return end
        if swapped~='done' then
            R.refresh(weapon,player)
            R.stateless[weapon]=R.fingerprint(weapon,true)
            R.preloadStates(weapon)
        end
        R.cache[weapon]=fingerprint
    else
        Render.touch(weapon)
    end
    if weapon:getModData().TW and not weapon:getModData().TW.ready then
        if player and player:getPrimaryHandItem()==weapon then player:setPrimaryHandItem(nil);primary=nil;primaryOurs=false end
        if player and player:getSecondaryHandItem()==weapon then player:setSecondaryHandItem(nil) end
    end
    if player and not isServer() then
        local held=(weapon==primary) or (not primaryOurs and player:getSecondaryHandItem()==weapon)
        if held and canCheckHandModel(player,equipEvent) then checkHandModel(player,weapon,fingerprint) end
    end
end

Stats.RegisterModifierLayer('TarkovVanillaAssembly',R.modifiers,{AimingTime=true,RecoilDelay=true,SoundRadius=true,SoundVolume=true,SwingSound=true,
    MaxRange=true,MaxAmmo=true,HitChance=true},R.isWeapon)
-- W9-anchorsqa: arm equip settling on OnEquipPrimary/Secondary and skip synchronous checkHandModel.
-- Player report of 03.11.2026 (MP: frame drops while someone shoots): on a client another player's equip (EquipPacket:
-- a new copy of the weapon with all its parts) ran the owner's full refresh - stats, validation, the hands rebuilt once
-- more. It gets the render-only path its other appearances get (TW_RenderWatch): stats and hands are the owner's.
local function onEquip(player,weapon)
    if isClient() and instanceof(player,'IsoPlayer') and not player:isLocalPlayer() then
        if weapon and R.visualKey(weapon) then R.ensureVisible(weapon) end
        return
    end
    if player then R.handSettling[player]=2 end
    R.update(player,weapon,true)
end
Events.OnEquipPrimary.Add(onEquip)
Events.OnEquipSecondary.Add(onEquip)
Events.OnPlayerUpdate.Add(R.update)
return R
