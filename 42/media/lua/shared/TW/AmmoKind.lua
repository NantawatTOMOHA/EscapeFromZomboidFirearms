-- B42.21 saves an item's modData but not InventoryItem.ammoType (the field is not in
-- InventoryItem.save/load, SyncItemFieldsPacket or SyncHandWeaponFieldsPacket). After a
-- save/load, a magazine ejected by ISEjectMagazine (a new instanceItem) or an item received
-- in MP therefore falls back to the script's plain round of the calibre: the context-menu
-- ammo label, the contents weight and every read of getAmmoType() before the first shot are
-- wrong, and "Load" without choosing a kind adds plain rounds.
-- Gunworks keeps one item type per loaded round in the saved modData.AmmoList (the last is
-- the round that fires next, the chambered one last); an emptied magazine keeps its last
-- kind in modData.TWAmmoKind. This module turns that kind back into the item's AmmoType:
-- a magazine through Gunworks' own setter (it also tells the server in MP), a weapon through
-- Ammo.AmmoAdjustWeaponStats (AmmoProfileSetter returns at once when the enum already
-- matches, which is the state after a load, so the round's stats would never be applied).
local Ammo=require('WeaponSystems/Utils/Ammo')
-- The game runs every file under media/lua/{shared,client} at startup and the mod also
-- requires its modules, so this file may execute twice: the second run must not register the
-- hooks (and wrap the setters and actions) a second time.
local A=rawget(_G,'TWAmmoKind')
if A then return A end
A={}
TWAmmoKind=A
local MARK='TWAmmoKind'

-- The last round of a Gunworks list; nil for an empty or missing list.
local function roundKind(list)
    if type(list)~='table' then return nil end
    local kind
    for i=1,#list do
        local value=list[i]
        if type(value)=='string' and value~='' then kind=value end
    end
    return kind
end

-- The kind of the rounds in an item: the last round of the list, else the mark an emptied
-- magazine keeps. Read-only: modData is written by apply() only, and the game's getModData()
-- is not called on an item without mod data (it would create and then save an empty table).
local function hasModData(item)
    -- Items from the game always carry it; a Lua stand-in without the method is not asked twice.
    if item.hasModData then return item:hasModData() end
    return true
end

function A.kind(item)
    if not item or not hasModData(item) then return nil end
    local md=item:getModData()
    return roundKind(md.AmmoList) or md[MARK]
end

-- AmmoType of a round item type: the script's own enum, the Brita registry, the mod's
-- registry (TW_Init), else the enum a Gunworks family knows for it.
function A.enum(kind)
    if type(kind)~='string' or kind=='' then return nil end
    local enum=TWAmmoEnumFor and TWAmmoEnumFor(kind)
    return enum or (Ammo.GetEnumForBullet and Ammo.GetEnumForBullet(kind))
end

-- Only the mod's own weapons and magazines, including a weapon whose installed part chose
-- the family: another mod's firearm keeps its own ammo handling.
function A.ours(item)
    local map=Ammo.ItemAmmoFamily
    local family=item and map and map[item:getFullType()]
    return type(family)=='string' and family:sub(1,8)=='TW:ammo:' or false
end

function A.profile(kind)
    return Ammo.GetAmmoCharacteristics and Ammo.GetAmmoCharacteristics(kind) or nil
end

-- Legacy/default magazines can contain plain rounds without a Gunworks list.
-- Preserve those rounds before an explicit-kind action appends a different kind;
-- otherwise unloading would recover only the new suffix with its correct types.
function A.prepareMagazine(item)
    if not item or not A.ours(item) or instanceof(item,'HandWeapon') then return false end
    local count=item:getCurrentAmmoCount()
    if count<=0 then return false end
    local md=item:getModData()
    if type(md.AmmoList)=='table' and #md.AmmoList>0 then return false end
    local enum=item:getAmmoType()
    local kind=md[MARK] or (enum and enum:getItemKey())
    if type(kind)~='string' or kind=='' then return false end
    md.AmmoList={}
    for i=1,count do md.AmmoList[i]=kind end
    return true
end

-- Put the item's saved kind back into its AmmoType (and, for a weapon, the round's stats).
-- False when the item has no kind, is not the mod's, or already carries the right state.
function A.apply(item)
    if not item or not instanceof(item,'InventoryItem') then return false end
    -- An item without mod data cannot carry a saved kind, and asking for its mod data would
    -- create an empty table that the game then saves with it.
    if not hasModData(item) then return false end
    local md=item:getModData()
    local kind=roundKind(md.AmmoList)
    if kind then
        if md[MARK]~=kind then md[MARK]=kind end
    else
        kind=md[MARK]
    end
    if kind==nil then return false end
    local enum=A.enum(kind)
    if not enum then return false end
    if instanceof(item,'HandWeapon') then
        if not item:isRanged() or not A.ours(item) then return false end
        local profile=A.profile(kind)
        if item:getAmmoType()==enum and md.ActiveAmmoProfile==profile then return false end
        Ammo.AmmoAdjustWeaponStats(item,kind,enum)
        if isClient() then
            local player=getSpecificPlayer(0)
            if player then sendClientCommand(player,'SWMG','ammoProfile',{itemId=item:getID(),bulletType=kind}) end
        end
        return true
    end
    if not A.ours(item) or item:getAmmoType()==enum then return false end
    if Ammo.MagazineAmmoProfileSetter then
        Ammo.MagazineAmmoProfileSetter(item,kind)
    else
        item:setAmmoType(enum)
    end
    return true
end

local function sweepContainer(container,out)
    if not container or not container.getItems then return end
    local items=container:getItems()
    if not items then return end
    for i=0,items:size()-1 do
        local item=items:get(i)
        if item then
            if A.apply(item) then out.n=out.n+1 end
            local inner=item.getInventory and item:getInventory()
            if inner then sweepContainer(inner,out) end
        end
    end
end

-- Every item of a container and of the containers inside it (bags, worn bags).
function A.sweep(container)
    local out={n=0}
    sweepContainer(container,out)
    return out.n
end

function A.restorePlayer(player)
    if not player then return 0 end
    local out={n=0}
    sweepContainer(player:getInventory(),out)
    local worn=player.getWornItems and player:getWornItems()
    if worn then sweepContainer(worn,out) end
    -- A weapon that is held but not inside a container (the server's copy of another player's
    -- weapon, an action that took it out mid-way) is restored from the hand too.
    for _,slot in ipairs({player:getPrimaryHandItem(),player:getSecondaryHandItem()}) do
        if slot and A.apply(slot) then out.n=out.n+1 end
    end
    if out.n>0 then
        A.restored=(A.restored or 0)+out.n
        print('[TW] ammo kind restored: '..out.n..' items')
    end
    return out.n
end

-- A player whose inventory is swept on the next tick: SWMG's own Ammo.RestoreOnLoad runs in
-- its OnGameStart/OnCreatePlayer handler and clears the profile, so ours must come after it.
local pending={}
local function defer(player)
    if player then pending[player]=true end
end

-- Ejected magazine, inserted magazine and a topped-up magazine: after the action the item
-- carries its Gunworks list, so its kind can be read from it.
local function afterAction(self)
    local character=self and self.character
    if character then defer(character) end
end

function A.install()
    if A.installed then return end
    A.installed=true
    -- The mark survives an emptied magazine; Gunworks' setter keeps the enum and the server.
    local setter=Ammo.MagazineAmmoProfileSetter
    if setter then
        Ammo.MagazineAmmoProfileSetter=function(item,bulletType)
            local result=setter(item,bulletType)
            if item and type(bulletType)=='string' and bulletType~='' then item:getModData()[MARK]=bulletType end
            return result
        end
    end
    -- Gunworks wraps these in CustomSystemHooks: load it now so our wrapper runs after its
    -- list transfer, whatever the mod order of the two mods is.
    pcall(require,'WeaponSystems/Hooks/CustomSystemHooks')
    if ISLoadBulletsInMagazine and not ISLoadBulletsInMagazine.TWAmmoKind then
        ISLoadBulletsInMagazine.TWAmmoKind=true
        for _,method in ipairs({'start','serverStart'}) do
            local original=ISLoadBulletsInMagazine[method]
            local serverStart=method=='serverStart'
            if original then
                ISLoadBulletsInMagazine[method]=function(self,...)
                    -- Vanilla start records this baseline only on the client.
                    -- Gunworks uses it for ammoLimit on both sides: pre-existing
                    -- rounds must not count as rounds inserted by this action.
                    if serverStart and A.ours(self.magazine) then
                        self.ammoCountStart=self.magazine:getCurrentAmmoCount()
                    end
                    if self.ammoTypeOverride then A.prepareMagazine(self.magazine) end
                    return original(self,...)
                end
            end
        end
    end
    if ISEjectMagazine and ISEjectMagazine.unloadAmmo and not ISEjectMagazine.TWAmmoKind then
        ISEjectMagazine.TWAmmoKind=true
        local unload=ISEjectMagazine.unloadAmmo
        ISEjectMagazine.unloadAmmo=function(self,...)
            local result=unload(self,...)
            afterAction(self)
            return result
        end
    end
    if ISInsertMagazine and ISInsertMagazine.loadAmmo and not ISInsertMagazine.TWAmmoKind then
        ISInsertMagazine.TWAmmoKind=true
        local load=ISInsertMagazine.loadAmmo
        ISInsertMagazine.loadAmmo=function(self,...)
            local result=load(self,...)
            afterAction(self)
            return result
        end
    end
    Events.OnTick.Add(function()
        for player in pairs(pending) do
            pending[player]=nil
            A.restorePlayer(player)
        end
    end)
end

Events.OnGameBoot.Add(A.install)
Events.OnGameStart.Add(function() defer(getSpecificPlayer(0)) end)
Events.OnCreatePlayer.Add(function(_,player) defer(player) end)
-- Containers while they are opened (loot, vehicles, corpses, a bag taken off): the labels of
-- the page are built after this, so the restored kind is what the player sees.
Events.OnRefreshInventoryWindowContainers.Add(function(page,state)
    if state~='begin' or not page then return end
    A.sweep(page.inventory)
    for _,button in ipairs(page.backpacks or {}) do A.sweep(button.inventory) end
end)

return A
