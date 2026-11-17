-- Tarkov launchers use Gunworks' projectile and underbarrel APIs. Engine reloads,
-- host snapshots and authoritative equipped-item replacement follow SWMG 42.13;
-- resource/provenance records are in tools/write_launcher_provenance.py.
local R=require('TW/Runtime')
local D=require('TW/Data')
local ok,config=pcall(require,'TW/Data/Launchers')
if not ok then config={enabled=false} end
local L={config=config,registered=false,visual=setmetatable({},{__mode='k'}),massScan=setmetatable({},{__mode='k'})}
TWLaunchers=L

function L.isDisposable(weapon)
    return config.enabled and config.disposable and weapon and weapon:getFullType()==config.disposable.weapon or false
end

function L.isUnderbarrel(weapon)
    if not config.enabled or not weapon then return false end
    local full=weapon:getFullType()
    for _,row in ipairs(config.underbarrels or {}) do if full==row.weapon then return true end end
    return false
end

function L.hostKey(weapon)
    if not L.isUnderbarrel(weapon) then return nil end
    local md=weapon:getModData()
    return md.GW_UBMode==true and D.byType[md.GW_UBHostType] or nil
end

local function copy(value)
    if type(value)~='table' then return value end
    local out={}
    for k,v in pairs(value) do
        if type(v)=='table' or type(v)=='string' or type(v)=='number' or type(v)=='boolean' then out[k]=copy(v) end
    end
    return out
end

-- Only fresh OnCreate paths call this. Never refill an existing empty or spent tube.
function L.onCreate(weapon)
    if not L.isDisposable(weapon) then return end
    local md=weapon:getModData()
    if md.TWDisposable then return end
    md.TWDisposable={initialized=true,spent=false}
    md.AmmoList={config.disposable.ammo}
    weapon:setCurrentAmmoCount(1)
    weapon:setRoundChambered(false)
    weapon:setSpentRoundChambered(false)
    weapon:setSpentRoundCount(0)
end

-- SWMG clones attached part TYPES on entering underbarrel mode, but not modData.
-- Restore nested TWPath and tactical state from its authoritative host snapshot.
function L.restoreHostParts(weapon,force)
    if not L.hostKey(weapon) then return false end
    local md=weapon:getModData()
    local snapshot=md.GW_UBHostSnapshot
    if type(snapshot)~='table' then return false end
    local saved={}
    for _,row in ipairs(snapshot.parts or {}) do saved[row.partType]=row end
    local parts=weapon:getAllWeaponParts()
    for i=0,parts:size()-1 do
        local part=parts:get(i)
        local row=saved[part:getPartType()]
        if row and row.type==part:getFullType() and (force or part:getModData().TWPath==nil) then
            if row.modData then part:copyModData(copy(row.modData)) end
            if row.condition~=nil then part:setCondition(row.condition) end
        end
    end
    if snapshot.modData and snapshot.modData.TW and (force or not md.TW) then md.TW=copy(snapshot.modData.TW) end
    return true
end

-- The player may change device modes while using the launcher. Merge these into
-- the host snapshot before SWMG rebuilds it, without touching host AmmoList/ammo.
function L.captureHostParts(weapon)
    if not L.hostKey(weapon) then return end
    local snapshot=weapon:getModData().GW_UBHostSnapshot
    if type(snapshot)~='table' then return end
    local parts=weapon:getAllWeaponParts()
    local installed={}
    for i=0,parts:size()-1 do local p=parts:get(i);installed[p:getPartType()]=p end
    for _,row in ipairs(snapshot.parts or {}) do
        local part=installed[row.partType]
        if part and part:getFullType()==row.type then
            row.modData=copy(part:getModData());row.condition=part:getCondition()
        end
    end
    snapshot.modData=snapshot.modData or {}
    snapshot.modData.TW=copy(weapon:getModData().TW)
end

local function scriptWeight(full)
    local script=full and ScriptManager.instance:FindItem(full)
    return script and script:getActualWeight() or 0
end

-- HandWeapon.getActualWeight is script root weight + live part modifiers; its
-- setActualWeight field is ignored. Use the existing launcher part to retain
-- the hidden host/chamber mass in GL mode and the retained grenade in host mode.
function L.refreshMass(weapon)
    if not config.enabled or not weapon then return end
    local md=weapon:getModData()
    local key=L.hostKey(weapon)
    local correction
    if key then
        local snapshot=md.GW_UBHostSnapshot
        if type(snapshot)~='table' then return end -- MP observers use server part modifiers.
        local saved=snapshot.preservers and snapshot.preservers['EscapeFromZomboidFirearms']
        if not saved or type(saved.contentsWeight)~='number' then return end
        correction=scriptWeight(snapshot.type)-scriptWeight(weapon:getFullType())+saved.contentsWeight
    elseif R.key(weapon) then
        local snapshot=md.GW_UBSelfSnapshot
        if type(snapshot)~='table' then return end
        local ammo=snapshot and snapshot.ammo
        correction=ammo and scriptWeight(ammo.ammoType)*math.max(0,ammo.currentAmmoCount or 0) or 0
    else return end
    local parts=weapon:getAllWeaponParts()
    for i=0,parts:size()-1 do
        local part=parts:get(i)
        for _,row in ipairs(config.underbarrels or {}) do
            if part:getFullType()==row.attachment then
                -- Each P item uses its Tarkov Weight as native WeightModifier.
                part:setWeightModifier(scriptWeight(row.attachment)+correction)
                return
            end
        end
    end
end

-- Native WeaponPart does not serialize its mutable WeightModifier. Restore
-- carried snapshot-bearing weapons too, including bags, after a cold load.
local massKinds={}
-- Loaded on first use and kept (refreshAppearance runs every update in underbarrel mode).
local RenderModule,TacticalModule
function L.refreshInventoryMass(player,force)
    if not config.enabled or #(config.underbarrels or {})==0 or not player then return end
    -- Equipped items may have changed since the last inventory scan. Restore
    -- both hands immediately, including native equipment not yet in its bag.
    local primary=player:getPrimaryHandItem()
    local secondary=player:getSecondaryHandItem()
    L.refreshMass(primary)
    if secondary~=primary then L.refreshMass(secondary) end
    local now=getTimestampMs()
    if not force and (L.massScan[player] or 0)>now then return end
    L.massScan[player]=now+1000
    local pending,seen={player:getInventory()},{}
    while #pending>0 do
        local container=table.remove(pending)
        if container and not seen[container] then
            seen[container]=true
            local list=container:getItems()
            for i=0,list:size()-1 do
                local item=list:get(i)
                -- What an item is follows from its type (performance pass 23.10.2026: R.key, hostKey and instanceof
                -- for every carried item once a second was a hitch with a full inventory); a deployed host's
                -- mode is its mod data and is read each time, as before.
                local full=item:getFullType()
                local kind=massKinds[full]
                if kind==nil then
                    kind=R.key(item) and 'weapon' or L.isUnderbarrel(item) and 'underbarrel'
                        or instanceof(item,'InventoryContainer') and 'container' or false
                    massKinds[full]=kind
                end
                if kind=='weapon' or kind=='underbarrel' and L.hostKey(item) then L.refreshMass(item)
                elseif kind=='container' or kind=='underbarrel' and instanceof(item,'InventoryContainer') then
                    pending[#pending+1]=item:getInventory()
                end
            end
        end
    end
end

function L.refreshAppearance(weapon,player,force)
    local key=L.hostKey(weapon)
    if not key then return end
    L.restoreHostParts(weapon,false)
    L.refreshMass(weapon)
    local Render=RenderModule or require('TW/Render');RenderModule=Render
    local Tactical=TacticalModule or require('TW/Tactical');TacticalModule=Tactical
    local fingerprint=R.fingerprint(weapon)
    local tree,entries,parts=R.read(weapon)
    if not isServer() then
        if force or L.visual[weapon]~=fingerprint or not Render.current(weapon) then
            Render.apply(weapon,key,tree,entries)
            -- The framework's restore handler must retain this pool sprite.
            weapon:getModData().GW_UBHostSprite=weapon:getWeaponSprite()
            L.visual[weapon]=fingerprint
            if player then player:resetEquippedHandsModels() end
        else Render.touch(weapon) end
    end
    Tactical.prepare(weapon,key,tree,parts)
end

function L.afterSwap(old,new,player,entering)
    if not new then return end
    R.cache[old]=nil;R.cache[new]=nil;L.visual[old]=nil;L.visual[new]=nil
    if entering then
        L.restoreHostParts(new,true)
        -- SWMG has already sent the fresh item; sync its final flag to the owner.
        new:setFavorite(old:isFavorite(), true)
        L.refreshAppearance(new,player,true)
    else
        R.refresh(new,player)
        L.refreshMass(new)
    end
    if syncHandWeaponFields then syncHandWeaponFields(player,new) end
    if isServer() and sendItemStats then sendItemStats(new) end
    if isServer() and L.broadcastState then L.broadcastState(player,new) end
    local Tactical=require('TW/Tactical')
    if Tactical.send then Tactical.send(player,new,true) end
end

-- Native hand-weapon sync reaches the owner; SWMG's observer packet only sends
-- the model mask. Supply the exact part IDs and nested paths needed by lasers
-- and the host render tree. Ammo and inventory ownership stay with native sync.
function L.visualSnapshot(player,weapon)
    if not config.enabled or not weapon then return nil end
    local md=weapon:getModData()
    if not L.hostKey(weapon) and not (R.key(weapon) and md.GW_UBSelfSnapshot) then return nil end
    -- WeightModifier is not serialized by native WeaponPart. Never publish
    -- a stale cold-load modifier, even before the first server inventory tick.
    if isServer() then L.refreshMass(weapon) end
    local state={playerId=player:getOnlineID(),weaponId=weapon:getID(),fullType=weapon:getFullType(),
        ubMode=L.hostKey(weapon)~=nil,hostType=md.GW_UBHostType,tw=copy(md.TW),parts={}}
    local list=weapon:getAllWeaponParts()
    for i=0,list:size()-1 do
        local part=list:get(i)
        state.parts[#state.parts+1]={type=part:getFullType(),partType=part:getPartType(),id=part:getID(),
            condition=part:getCondition(),weightModifier=part:getWeightModifier(),modData=copy(part:getModData())}
    end
    return state
end

function L.applyVisualSnapshot(player,weapon,state)
    if not isClient() or not state or not weapon or weapon:getID()~=state.weaponId
        or weapon:getFullType()~=state.fullType or type(state.parts)~='table' then return false end
    if state.ubMode then
        if not L.isUnderbarrel(weapon) or not D.byType[state.hostType] then return false end
    elseif not R.key(weapon) then return false end
    local wanted={}
    for _,row in ipairs(state.parts) do
        local script=ScriptManager.instance:FindItem(row.type)
        if not script or not row.partType or type(row.id)~='number' or wanted[row.partType] then return false end
        wanted[row.partType]=row
    end
    local list=weapon:getAllWeaponParts()
    local remove={}
    for i=0,list:size()-1 do
        local part=list:get(i);local row=wanted[part:getPartType()]
        if not row or row.type~=part:getFullType() then remove[#remove+1]=part end
    end
    for _,part in ipairs(remove) do weapon:detachWeaponPart(part,true) end
    for _,row in ipairs(state.parts) do
        local part=weapon:getWeaponPart(row.partType)
        if not part then part=instanceItem(row.type);weapon:attachWeaponPart(part,true) end
        part:setID(row.id)
        if row.modData then part:copyModData(copy(row.modData)) end
        if row.condition~=nil then part:setCondition(row.condition) end
        if type(row.weightModifier)=='number' then part:setWeightModifier(row.weightModifier) end
    end
    local md=weapon:getModData()
    md.GW_UBMode=state.ubMode and true or nil
    md.GW_UBModel=state.ubMode and 'host' or nil
    md.GW_UBHostType=state.ubMode and state.hostType or nil
    if state.tw then md.TW=copy(state.tw) end
    R.cache[weapon]=nil;L.visual[weapon]=nil
    if state.ubMode then L.refreshAppearance(weapon,player,true)
    else
        local tree,entries,parts=R.read(weapon)
        require('TW/Render').apply(weapon,R.key(weapon),tree,entries)
        require('TW/Tactical').prepare(weapon,R.key(weapon),tree,parts)
        player:resetEquippedHandsModels()
    end
    return true
end

-- Replace only after SWMG has accepted/spawned the rocket (server/SP hook below).
-- This sequence is the same inventory/equip sequence as SWMG Underbarrel.
function L.spend(player,weapon)
    if isClient() or not player or not L.isDisposable(weapon) then return false end
    local md=weapon:getModData()
    if md.TWDisposable and md.TWDisposable.spent then return false end
    local inventory=player:getInventory()
    local container=weapon:getContainer() or inventory
    if not container:contains(weapon) then return false end
    local spent=instanceItem(config.disposable.spent)
    if not spent then error('[TW] Missing spent RSHG-2 item') end
    spent:setCondition(weapon:getCondition());spent:setFavorite(weapon:isFavorite())
    if weapon:isCustomName() then spent:setName(weapon:getName());spent:setCustomName(true) end
    md.TWDisposable=md.TWDisposable or {};md.TWDisposable.spent=true
    md.AmmoList=nil;md.GWG_FiringExplosiveAmmo=nil
    weapon:setCurrentAmmoCount(0);weapon:setRoundChambered(false)
    spent:getModData().TWDisposable={initialized=true,spent=true,sourceId=weapon:getID()}
    local primary=player:getPrimaryHandItem()==weapon
    local secondary=player:getSecondaryHandItem()==weapon
    player:removeFromHands(weapon)
    container:Remove(weapon);sendRemoveItemFromContainer(container,weapon)
    inventory:AddItem(spent);sendAddItemToContainer(inventory,spent)
    if primary then player:setPrimaryHandItem(spent) end
    if secondary then player:setSecondaryHandItem(spent) end
    player:resetEquippedHandsModels()
    if isServer() then sendItemStats(spent);sendEquip(player) end
    return true,spent
end

function L.installActionGuards()
    if L.guarded or not config.enabled or not config.disposable then return end
    require('WeaponSystems/Hooks/CustomSystemHooks')
    local classes={ISReloadWeaponAction,ISUnloadBulletsFromFirearm,ISRackFirearm}
    for _,class in ipairs(classes) do
        if class then
            for _,method in ipairs({'isValid','loadAmmo','animEvent','rackBullet','removeBullet','complete'}) do
                local previous=class[method]
                if previous then
                    class[method]=function(self,...)
                        if L.isDisposable(self.gun) then return false end
                        return previous(self,...)
                    end
                end
            end
        end
    end
    L.guarded=true
end

function L.register()
    if L.registered or not config.enabled then return end
    local Ordnance=require('ExplosivesSystems/OrdnanceFactory')
    local Underbarrel=require('WeaponSystems/Utils/Underbarrel')
    local Ammo=require('WeaponSystems/Utils/Ammo')
    local Magazine=require('WeaponSystems/Utils/Magazine')
    local Reload=require('WeaponSystems/ReloadAnim/HandlerFactory')
    for full,params in pairs(config.ordnance or {}) do
        assert(ScriptManager.instance:FindItem(full),'[TW] Missing ordnance '..full)
        assert(ScriptManager.instance:FindItem(params.parentItem),'[TW] Missing explosive parent '..params.parentItem)
        Ordnance.RegisterAmmo(full,params)
    end
    local families={}
    for _,row in ipairs(config.underbarrels or {}) do
        assert(ScriptManager.instance:FindItem(row.weapon),'[TW] Missing underbarrel weapon '..row.weapon)
        assert(ScriptManager.instance:FindItem(row.attachment),'[TW] Missing launcher part '..row.attachment)
        local family='TW:ammo:'..row.ammoKey
        if not families[family] then
            local bullets={}
            for _,round in ipairs(assert(D.ammo.calibres[row.ammoKey]).rounds) do
                local enum=TWAmmoEnumFor and TWAmmoEnumFor(round.type)
                assert(enum,'[TW] Missing underbarrel round enum '..round.type)
                bullets[#bullets+1]={type=round.type,enum=enum,profile=round.profile}
            end
            Ammo.RegisterAmmoFamily(family,bullets);families[family]=true
        end
        Ammo.RegisterItemWithFamily(family,row.weapon)
        Underbarrel.Register({attachment=row.attachment,weapon=row.weapon,model='host'})
        Reload.RegisterWeapon(row.weapon,{animId='breakaction',archetype='doublebarrel'})
    end
    for _,weapon in pairs(D.weapons) do
        if weapon.kind=='launcher-break' or weapon.kind=='rocket' then
            Reload.RegisterWeapon(weapon.pz,{animId='breakaction',archetype='doublebarrel'})
        end
    end
    if config.disposable then
        -- The RShG-2 holds with its own grip class, registered by TW/Grips.lua.
        local create=R.createDefault
        R.createDefault=function(weapon) create(weapon);L.onCreate(weapon) end
    end
    local swap=Underbarrel.PerformSwap
    local magazineProfile=Magazine.GetProfileForGun
    Magazine.GetProfileForGun=function(weapon)
        -- Host chassis/receivers may carry magazine profiles; these are visual
        -- clones on the launcher and must not change its single-round reload.
        if L.isUnderbarrel(weapon) or L.isDisposable(weapon) then return nil end
        return magazineProfile(weapon)
    end
    local ammoFamily=Ammo.GetFamilyForItem
    if ammoFamily then
        Ammo.GetFamilyForItem=function(item)
            if L.isUnderbarrel(item) then
                for _,row in ipairs(config.underbarrels or {}) do
                    if row.weapon==item:getFullType() then return 'TW:ammo:'..row.ammoKey end
                end
            end
            return ammoFamily(item)
        end
    end
    Underbarrel.PerformSwap=function(weapon,player,entering,silent)
        local entry=entering and weapon and Underbarrel.GetAttachmentEntry(weapon)
        local registered=false
        for _,row in ipairs(config.underbarrels or {}) do if entry and row.weapon==entry.type then registered=true end end
        local ours=entering and R.key(weapon)~=nil and registered or L.hostKey(weapon)~=nil
        if ours and not entering then L.captureHostParts(weapon) end
        local result=swap(weapon,player,entering,silent)
        if ours and result then L.afterSwap(weapon,result,player,entering) end
        return result
    end
    Underbarrel.RegisterStatePreserver('EscapeFromZomboidFirearms',{
        snapshot=function(weapon)
            if R.key(weapon) or L.hostKey(weapon) then
                return {favorite=weapon:isFavorite(),contentsWeight=weapon:getContentsWeight()}
            end
        end,
        restore=function(weapon,state) weapon:setFavorite(state.favorite==true) end,
    })
    require('TW/LauncherPhysics').install(config)
    require('TW/LauncherAmmo').install()
    L.registered=true
    L.installActionGuards()
end

Events.OnGameBoot.Add(L.register)
Events.OnGameStart.Add(L.register)
Events.OnGameStart.Add(function()
    for i=0,(getNumActivePlayers and getNumActivePlayers() or 1)-1 do
        local player=getSpecificPlayer and getSpecificPlayer(i) or (getPlayer and getPlayer())
        L.refreshInventoryMass(player,true)
    end
end)
Events.OnPlayerUpdate.Add(function(player)
    local weapon=player:getPrimaryHandItem()
    if L.hostKey(weapon) then L.refreshAppearance(weapon,player,false) end
    if R.key(weapon) then L.refreshMass(weapon) end
    L.refreshInventoryMass(player,false)
end)
return L
