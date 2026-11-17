-- Common Sense and ReloadAllMagazines inspect only a magazine's current AmmoType.
-- A new magazine still names the calibre's default round, even when Gunworks has
-- registered other compatible rounds. Choose from that family without changing
-- the magazine's enum or its existing round list just to make a menu appear.
require('ISUI/ISInventoryPaneContextMenu')
require('TimedActions/ISLoadBulletsInMagazine')
local Ammo=require('WeaponSystems/Utils/Ammo')
local Kind=require('TW/AmmoKind')
local D=require('TW/Data')
local B=rawget(_G,'TWBulkMagazineReload')
if B then return B end
B={}
TWBulkMagazineReload=B

function B.ours(item)
    local meta=item and D.types[item:getFullType()]
    return meta and D.items[meta.tpl] and D.items[meta.tpl].clip and Kind.ours(item) or false
end

local function kinds(player,magazine)
    local family=Ammo.GetFamilyForItem(magazine)
    return family and Ammo.GetOrderedBulletTypesForFamily(player,family) or {}
end

-- Reserve actual item instances once for the entire batch, including nested bags.
-- Each action gets its own limit: Gunworks otherwise keeps loading the first kind
-- until the magazine is full, consuming rounds reserved for the next magazine.
function B.plan(player,magazines)
    local plan,pools,seen={},{},{}
    local inventory=player:getInventory()
    for _,magazine in ipairs(magazines) do
        if B.ours(magazine) and not seen[magazine] then
            seen[magazine]=true
            local space=magazine:getMaxAmmo()-magazine:getCurrentAmmoCount()
            for _,kind in ipairs(kinds(player,magazine)) do
                if space<=0 then break end
                local pool=pools[kind]
                if not pool then
                    pool={items=inventory:getAllTypeRecurse(kind),next=0}
                    pools[kind]=pool
                end
                local count=math.min(space,pool.items:size()-pool.next)
                if count>0 then
                    local bullets=ArrayList.new()
                    for i=pool.next,pool.next+count-1 do bullets:add(pool.items:get(i)) end
                    plan[#plan+1]={magazine=magazine,kind=kind,count=count,bullets=bullets}
                    pool.next=pool.next+count
                    space=space-count
                end
            end
        end
    end
    return plan
end

function B.queue(player,magazines,returnToContainer)
    local plan=B.plan(player,magazines)
    for i,step in ipairs(plan) do
        local magazine=step.magazine
        local original=magazine:getContainer()
        if not plan[i-1] or plan[i-1].magazine~=magazine then
            ISInventoryPaneContextMenu.transferIfNeeded(player,magazine)
        end
        ISInventoryPaneContextMenu.transferIfNeeded(player,step.bullets)
        ISTimedActionQueue.add(ISLoadBulletsInMagazine:new(player,magazine,step.count,step.count,step.kind))
        if returnToContainer and original and original~=player:getInventory()
            and (not plan[i+1] or plan[i+1].magazine~=magazine) then
            ISTimedActionQueue.add(ISInventoryTransferUtil.newInventoryTransferAction(
                player,magazine,player:getInventory(),original))
        end
    end
    return #plan
end

local function active(id) return getActivatedMods():contains(id) end
local ramWrapper
function B.install()
    local menu=ISInventoryPaneContextMenu
    local previous=menu.onLoadBulletsInAllMagazines
    if type(previous)=='function' and previous~=ramWrapper then
        ramWrapper=function(player,magazine,...)
            if not B.ours(magazine) then return previous(player,magazine,...) end
            local items=player:getInventory():getAllTypeRecurse(magazine:getFullType())
            local magazines={}
            for i=0,items:size()-1 do magazines[#magazines+1]=items:get(i) end
            return B.queue(player,magazines,false)
        end
        menu.onLoadBulletsInAllMagazines=ramWrapper
    end
    -- Common Sense owns a private callback. Run after its menu handler, preserving
    -- the callback for any selected magazines belonging to another mod.
    Events.OnFillInventoryObjectContextMenu.Remove(B.commonSenseMenu)
    Events.OnFillInventoryObjectContextMenu.Add(B.commonSenseMenu)
end

local function selectedMagazines(objects)
    local magazines,seen,selected={},{},0
    local function add(item)
        if item and not seen[item] then
            seen[item]=true
            selected=selected+1
            if not item:IsWeapon() and item.getMaxAmmo and item:getAmmoType()
                and item:getCurrentAmmoCount()<item:getMaxAmmo() then
                magazines[#magazines+1]=item
            end
        end
    end
    for _,object in ipairs(objects) do
        if instanceof(object,'InventoryItem') then
            add(object)
        else
            -- Context stacks repeat their first actual item at index 1.
            for i=2,#(object.items or {}) do add(object.items[i]) end
        end
    end
    return magazines,selected
end

function B.commonSenseMenu(playerNum,context,objects,test)
    if test or not active('VB_CommonSense') or active('ReloadAllMagazines')
        or not (SandboxVars.CommonSense and SandboxVars.CommonSense.ReloadAllMags) then return end
    local player=getSpecificPlayer(playerNum)
    if not player then return end
    local magazines,selected=selectedMagazines(objects)
    if selected<2 or #magazines==0 then return end
    local ours,others={},{}
    for _,magazine in ipairs(magazines) do
        local list=B.ours(magazine) and ours or others
        list[#list+1]=magazine
    end
    if #ours==0 or #B.plan(player,ours)==0 then return end
    local name=getText('ContextMenu_CS_ReloadAllMags')
    local option=context:getOptionFromName(name)
    local previous=option and option.onSelect
    local counts=option and option.param2
    local function loadAll(p)
        B.queue(p,ours,true)
        if previous and #others>0 then previous(p,others,counts) end
    end
    if option then
        option.target=player
        option.onSelect=loadAll
    else
        context:insertOptionAfter(getText('ContextMenu_Drop'),name,player,loadAll)
    end
end

Events.OnGameBoot.Add(B.install)
Events.OnInitGlobalModData.Add(B.install)
Events.OnGameStart.Add(B.install)
return B
