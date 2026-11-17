-- Holding the reload key opens the vanilla firearm radial (ISFirearmRadialMenu, extended by
-- Gunworks). For the mod's magazine weapons it also offers, first:
--   * top up the inserted magazine: it comes out, takes rounds of the chosen kind (the kind
--     already in it first; a sub-menu when there are several) and goes back in - the same
--     magazine, Gunworks' per-round ammo list kept;
--   * another magazine: every compatible magazine the character carries (bags included; the
--     same type, rounds and top round shown once with a count), the inserted one comes out.
--   * quick reload: the inserted magazine is dropped at the feet (TWDropMagazine) and the fullest
--     compatible magazine goes in - Tarkov's double-tap reload;
--   * check the magazine (TWCheckMagazine): how full it is, exact with a high Reloading skill.
-- Compatible = the weapon's Gunworks magazine profile (narrowed by installed parts, TW_Init.lua).
-- Gunworks' "select magazine" slice is replaced by the second; the other vanilla and Gunworks
-- slices stay after them. Actions: vanilla ISEjectMagazine, ISInsertMagazine, ISLoadBulletsInMagazine
-- (Gunworks' ammo kind override) and ISTimedActionQueue.queueActions for the ejected magazine.
require 'ISUI/ISFirearmRadialMenu'
require 'TimedActions/ISQueueActionsAction'
require 'TimedActions/TWDropMagazine'
require 'TimedActions/TWCheckMagazine'
local R=require('TW/Runtime')
local U=require('TW/UIHelpers')
local H=require('TW/HoldMenu')
local Magazine=require('WeaponSystems/Utils/Magazine')
local Ammo=require('WeaponSystems/Utils/Ammo')

local M={}
TWReloadRadial=M
-- Gunworks' own "select magazine" slice (its translation), replaced by the magazine choice here.
local GUNWORKS_SELECT='IGUI_SelectMagazine'

local function fullType(item)
    return item and item:getFullType()
end

-- The round on top of a magazine (Gunworks AmmoList: the last loaded is fed first), else its kind.
function M.topRound(item)
    local list=item and item:getModData().AmmoList
    if type(list)=='table' and #list>0 then return list[#list] end
    local at=item and item:getAmmoType()
    return at and at:getItemKey() or nil
end

local function ammoName(bulletType)
    return bulletType and getItemNameFromFullType(bulletType) or nil
end

local function magazineName(item)
    local meta=R.meta(item)
    return meta and U.shortName(meta.tpl) or item:getDisplayName()
end

-- Bullet kinds of the weapon's family the character carries: {type, count (fits), name, tex}.
function M.bulletChoices(player,weapon,free)
    local family=Ammo.GetFamilyForItem(weapon)
    local types=family and (Ammo.GetOrderedBulletTypesForFamily and Ammo.GetOrderedBulletTypesForFamily(player,family) or Ammo.GetBulletTypesForFamily(family))
    if not types or #types==0 then
        local at=weapon:getAmmoType()
        types=at and {at:getItemKey()} or {}
    end
    local inv=player:getInventory()
    local loaded=M.topRound(weapon)
    local list={}
    for i,bulletType in ipairs(types) do
        local total=inv:getItemCountRecurse(bulletType)
        if total>0 then
            local item=inv:getFirstTypeRecurse(bulletType)
            list[#list+1]={type=bulletType,count=math.min(total,free),name=ammoName(bulletType),tex=item and item:getTex(),order=i,loaded=bulletType==loaded}
        end
    end
    table.sort(list,function(a,b)
        if a.loaded~=b.loaded then return a.loaded end
        return a.order<b.order
    end)
    return list
end

-- Compatible magazines the character carries, grouped by type, rounds and top round.
function M.magazines(player,weapon)
    local types=Magazine.GetMagazineTypesForGun(weapon) or {weapon:getMagazineType()}
    local inv=player:getInventory()
    local groups,list={},{}
    for _,magType in ipairs(types) do
        local items=inv:getAllTypeRecurse(magType)
        for i=0,items:size()-1 do
            local mag=items:get(i)
            local top=M.topRound(mag)
            local id=magType..'|'..mag:getCurrentAmmoCount()..'|'..tostring(top)
            local g=groups[id]
            if not g then
                g={item=mag,type=magType,rounds=mag:getCurrentAmmoCount(),max=mag:getMaxAmmo(),top=top,count=0}
                groups[id]=g
                list[#list+1]=g
            end
            g.count=g.count+1
            -- One already in the main inventory needs no transfer.
            if mag:getContainer()==inv and g.item:getContainer()~=inv then g.item=mag end
        end
    end
    table.sort(list,function(a,b)
        if a.rounds~=b.rounds then return a.rounds>b.rounds end
        if a.max~=b.max then return a.max>b.max end
        return a.type<b.type
    end)
    return list
end

-- Every public action below goes through this: a key press can arrive with empty hands (the
-- magazine-check key did: "attempted index: isContainsClip of non-table: null"), and no item
-- in the hand must never count as the weapon.
local function wielded(player,weapon)
    if not (player and weapon) then return false end
    -- A firearm, not any item that ends up in the hand (a magazine or a bag has no isContainsClip).
    if not weapon.isContainsClip then return false end
    return player:getPrimaryHandItem()==weapon and not player:isDead()
end

-- Queued after the eject: the magazine that came out (not among the known ones) is filled and
-- goes back. In multiplayer the server creates it; this runs when the eject has completed.
local function refill(character,weapon,magType,known,bulletType)
    local items=character:getInventory():getAllTypeRecurse(magType)
    local mag
    for i=0,items:size()-1 do
        local item=items:get(i)
        if not known[item:getID()] then mag=item end
    end
    if not mag or not wielded(character,weapon) then return end
    local count=math.min(mag:getMaxAmmo()-mag:getCurrentAmmoCount(),character:getInventory():getItemCountRecurse(bulletType))
    if count>0 then
        ISInventoryPaneContextMenu.transferIfNeeded(character,character:getInventory():getSomeTypeRecurse(bulletType,count))
        -- The chosen kind becomes the magazine's own (its label, and the mark kept when the
        -- magazine is emptied); Gunworks' setter sets the enum and tells the server in MP.
        if Ammo.MagazineAmmoProfileSetter then Ammo.MagazineAmmoProfileSetter(mag,bulletType) end
        ISTimedActionQueue.add(ISLoadBulletsInMagazine:new(character,mag,count,nil,bulletType))
    end
    ISTimedActionQueue.add(ISInsertMagazine:new(character,weapon,mag))
end

function M.topUp(player,weapon,bulletType)
    if type(bulletType)~='string' or bulletType=='' then return false end
    if not wielded(player,weapon) or not weapon:isContainsClip() then return false end
    local magType=Magazine.GetMagazineType(weapon) or weapon:getMagazineType()
    local known={}
    local items=player:getInventory():getAllTypeRecurse(magType)
    for i=0,items:size()-1 do known[items:get(i):getID()]=true end
    ISTimedActionQueue.add(ISEjectMagazine:new(player,weapon))
    ISTimedActionQueue.queueActions(player,refill,weapon,magType,known,bulletType)
    return true
end

function M.swap(player,weapon,mag)
    if not mag or not wielded(player,weapon) or not player:getInventory():containsRecursive(mag) then return false end
    if not Magazine.IsMagazineInProfile(mag:getFullType(),Magazine.GetProfileForGun(weapon)) and mag:getFullType()~=weapon:getMagazineType() then return false end
    ISInventoryPaneContextMenu.transferIfNeeded(player,mag)
    if weapon:isContainsClip() then ISTimedActionQueue.add(ISEjectMagazine:new(player,weapon)) end
    ISTimedActionQueue.add(ISInsertMagazine:new(player,weapon,mag))
    return true
end

-- Drop the inserted magazine and seat the fullest one carried.
function M.quickReload(player,weapon)
    if not wielded(player,weapon) or not weapon:isContainsClip() then return false end
    local list=M.magazines(player,weapon)
    local best=list[1]
    if not best or best.rounds<=0 then return false end
    ISInventoryPaneContextMenu.transferIfNeeded(player,best.item)
    ISTimedActionQueue.add(TWDropMagazine:new(player,weapon))
    ISTimedActionQueue.add(ISInsertMagazine:new(player,weapon,best.item))
    return true
end

function M.checkMagazine(player,weapon)
    if not wielded(player,weapon) or not weapon:isContainsClip() then return false end
    ISTimedActionQueue.add(TWCheckMagazine:new(player,weapon))
    return true
end

local function fillAmmo(menu,player,weapon,choices)
    for _,c in ipairs(choices) do
        menu:addSlice(H.text(c.name,'+'..c.count..(c.loaded and ' '..getText('IGUI_TW_Radial_Loaded') or '')),c.tex,M.topUp,player,weapon,c.type)
    end
end

local function magazineText(g)
    return H.text(magazineName(g.item),g.rounds..'/'..g.max..(g.count>1 and '  x'..g.count or ''),g.rounds>0 and ammoName(g.top) or nil)
end

local function fillMagazines(menu,player,weapon,list)
    for _,g in ipairs(list) do menu:addSlice(magazineText(g),g.item:getTex(),M.swap,player,weapon,g.item) end
end

local function topUpSlice(menu,player,weapon)
    if not weapon:isContainsClip() then return end
    local free=weapon:getMaxAmmo()-weapon:getCurrentAmmoCount()
    if free<=0 then return end
    local choices=M.bulletChoices(player,weapon,free)
    if #choices==0 then return end
    local text=H.text(getText('IGUI_TW_Radial_TopUp'),weapon:getCurrentAmmoCount()..'/'..weapon:getMaxAmmo())
    local icon=getTexture('media/ui/FirearmRadial_BulletsIntoMagazine.png')
    if #choices==1 then
        menu:addSlice(H.text(text,ammoName(choices[1].type)..' +'..choices[1].count),icon,M.topUp,player,weapon,choices[1].type)
    else
        menu:addSlice(text,icon,H.sub,'reload',fillAmmo,player,weapon,choices)
    end
end

local function swapSlice(menu,player,weapon)
    local list=M.magazines(player,weapon)
    if #list==0 then return end
    if #list==1 then
        menu:addSlice(H.text(getText('IGUI_TW_Radial_OtherMagazine'),magazineText(list[1])),list[1].item:getTex(),M.swap,player,weapon,list[1].item)
    else
        menu:addSlice(H.text(getText('IGUI_TW_Radial_OtherMagazine'),getText('IGUI_TW_Radial_Count',#list)),
            getTexture('media/ui/FirearmRadial_InsertMagazine.png'),H.sub,'reload',fillMagazines,player,weapon,list)
    end
end

local function quickSlice(menu,player,weapon)
    if not weapon:isContainsClip() then return end
    local list=M.magazines(player,weapon)
    if not list[1] or list[1].rounds<=0 then return end
    menu:addSlice(H.text(getText('IGUI_TW_Radial_QuickReload'),magazineText(list[1])),getTexture('media/ui/FirearmRadial_EjectMagazine.png'),M.quickReload,player,weapon)
end

local function checkSlice(menu,player,weapon)
    if not weapon:isContainsClip() then return end
    menu:addSlice(getText('IGUI_TW_Radial_CheckMagazine'),getTexture('media/ui/FirearmRadial_BulletsIntoMagazine.png'),M.checkMagazine,player,weapon)
end

-- The mod's own slices first, then the vanilla and Gunworks ones that do something.
function M.extend(frm)
    local weapon=frm:getWeapon()
    if not weapon or not R.key(weapon) or not weapon:getMagazineType() then return end
    local menu=getPlayerRadialMenu(frm.playerNum)
    local replaced=getText(GUNWORKS_SELECT)
    local kept={}
    for _,slice in ipairs(menu.slices) do
        if slice.text and slice.command and slice.command[1] and slice.text~=replaced then kept[#kept+1]=slice end
    end
    menu:clear()
    topUpSlice(menu,frm.character,weapon)
    swapSlice(menu,frm.character,weapon)
    quickSlice(menu,frm.character,weapon)
    checkSlice(menu,frm.character,weapon)
    for _,slice in ipairs(kept) do
        local c=slice.command
        menu:addSlice(slice.text,slice.texture,c[1],c[2],c[3],c[4],c[5],c[6],c[7])
    end
end

-- Wrapped after every file loaded: Gunworks patches fillMenu at load, this extends its result.
local function install()
    if M.installed then return end
    M.installed=true
    local fill=ISFirearmRadialMenu.fillMenu
    function ISFirearmRadialMenu:fillMenu()
        fill(self)
        local ok,err=pcall(M.extend,self)
        if not ok then print('[TW] reload radial: '..tostring(err)) end
    end
end
Events.OnGameStart.Add(install)

return M
