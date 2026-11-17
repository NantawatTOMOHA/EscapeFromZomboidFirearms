-- Native side of loot tickets (plan 0.9.x): creating the drawn items and replacing a
-- representative in a container. Used by the container fill (server), foraging (shared) and
-- the world replacer (server). Rules stay in TW/Loot.
local Loot=require('TW/Loot')
local D=require('TW/Data')
local LI={}

-- Weapons from the loot are marked (kits once, never drawn again); other items only when their
-- type is itself a representative, so a later fill of the same container leaves them alone.
function LI.mark(item)
    if item and (instanceof(item,'HandWeapon') or Loot.isTicket(item:getFullType())) then
        item:getModData().TWLoot=1
    end
    return item
end

function LI.isMarked(item)
    return item:getModData().TWLoot~=nil
end

function LI.condition(from,to)
    local maxFrom,maxTo=from:getConditionMax(),to:getConditionMax()
    if maxFrom and maxFrom>0 and maxTo and maxTo>0 then
        to:setCondition(math.max(1,math.floor(from:getCondition()/maxFrom*maxTo+0.5)))
    end
end

-- Items of a draw (Loot.choose): count items of the drawn type.
function LI.create(choice)
    local out={}
    if not choice then return out end
    for _=1,choice.count or 1 do
        local item=instanceItem(choice.type)
        if item then out[#out+1]=LI.mark(item) end
    end
    return out
end

-- The magazine and ammo pack of a loot weapon (its script MagazineType and the ordinary
-- round's pack of its calibre: the mod's own one with VanillaAmmo 3, D15).
function LI.weaponMagazine(weapon)
    local mag=weapon:getMagazineType()
    if mag and mag~='' then return mag end
    -- Built-in box by default but detachable magazines too (SKS, Mosin): the first of them.
    local key=D.byType[weapon:getFullType()]
    local list=key and D.weapons[key].magazines
    return list and list[1] or nil
end

function LI.weaponBox(weapon,o)
    local key=D.byType[weapon:getFullType()]
    if key=='RSHG2' then return nil end
    if key then return Loot.stdBox(D.weapons[key].ammoKey,o) or weapon:getAmmoBox() end
    local box=weapon:getAmmoBox()
    if box and box~='' then return box end
    return nil
end

-- Rounds in the Gunworks format (plan R7): the item's AmmoList holds one item type per round
-- (the last one is the next to fire), its AmmoType names the round. The ordinary round of the
-- calibre follows VanillaAmmo (D15).
local function fillList(item,round,count)
    local md=item:getModData()
    if count>0 then
        local list={}
        for i=1,count do list[i]=round end
        md.AmmoList=list
        local enum=TWAmmoEnumFor and TWAmmoEnumFor(round)
        if enum and item.setAmmoType then item:setAmmoType(enum) end
    else
        md.AmmoList=nil
    end
end

-- A loot weapon with a fraction of its capacity loaded (inserted magazine or built-in one),
-- chamber empty.
function LI.loadWeapon(weapon,fraction,o)
    local key=D.byType[weapon:getFullType()]
    if not key then return 0 end
    if key=='RSHG2' then return weapon:getCurrentAmmoCount() end
    local max=weapon:getMaxAmmo() or 0
    local count=math.max(0,math.min(max,math.floor(fraction*max+0.5)))
    if fraction>0 and count==0 and max>0 then count=1 end
    weapon:setCurrentAmmoCount(count)
    weapon:setRoundChambered(false)
    fillList(weapon,Loot.stdRound(D.weapons[key].ammoKey,o),count)
    return count
end

function LI.loadMagazine(mag,cal,fraction,o)
    local max=mag:getMaxAmmo() or 0
    local count=math.max(0,math.min(max,math.floor(fraction*max+0.5)))
    if fraction>0 and count==0 and max>0 then count=1 end
    mag:setCurrentAmmoCount(count)
    fillList(mag,Loot.stdRound(cal,o),count)
    return count
end

return LI
