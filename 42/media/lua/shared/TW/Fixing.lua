-- Repair of the mod's firearms (scripts/TW_Fixing.txt, tools/generate_scripts.py; the user's request of 30.10.2026):
-- as every vanilla firearm, a weapon of the same model is the spare. FixingManager.useFixer (B42.21) takes the spare
-- apart the vanilla way before it removes it: every native part goes to the inventory as it is, then a NEW magazine of
-- getMagazineType() with the round count, or loose rounds of getAmmoType(). On a mod weapon that would give the
-- installed copies of the parts (their mod data still marking them installed), a second magazine besides Gunworks'
-- visual Clip, and plain rounds instead of the loaded Tarkov kinds. So before the vanilla completion the spare is
-- emptied and taken apart the mod's way: the magazine through the eject (Gunworks moves its round list into it), the
-- chambered round and the rounds of a built-in magazine through the rack's give-back (Gunworks gives the listed kind),
-- the parts as R.remove gives them (R.takeApart). The vanilla code then finds only the receiver, and removes it.
-- ISFixAction.complete runs where the repair is decided: in single player, and on the server in multiplayer.
require 'TimedActions/ISFixAction'
require 'TimedActions/ISEjectMagazine'
require 'TimedActions/ISRackFirearm'
local R=require('TW/Runtime')

-- The game runs every file under media/lua/shared and the mod may require this one too: wrap only once.
local F=rawget(_G,'TWFixing')
if F then return F end
F={stripped=0,failed=0}
TWFixing=F

-- The spare FixingManager.useFixer takes: the first item of the main inventory with the fixer's full type that is not
-- the item being repaired.
function F.spare(character,fixer,broken)
    local name=fixer and fixer:getFixerName()
    local items=name and character:getInventory():getItems()
    if not items then return nil end
    for i=0,items:size()-1 do
        local item=items:get(i)
        if item and item~=broken and item:getFullType()==name then return item end
    end
    return nil
end

-- The inserted magazine and every round leave the weapon into the inventory, each with its kind.
function F.unload(character,weapon)
    if weapon:isContainsClip() then ISEjectMagazine:new(character,weapon):unloadAmmo() end
    local rack=ISRackFirearm:new(character,weapon)
    if weapon:haveChamber() and weapon:isRoundChambered() then
        rack:removeBullet()
        weapon:setRoundChambered(false)
    end
    -- A built-in magazine (tube, cylinder, internal box), and a weapon without a chamber: one round per give-back.
    for _=1,weapon:getCurrentAmmoCount() do
        rack:removeBullet()
        weapon:setCurrentAmmoCount(weapon:getCurrentAmmoCount()-1)
    end
    weapon:setJammed(false)
    if syncHandWeaponFields then syncHandWeaponFields(character,weapon) end
end

function F.takeApart(character,weapon)
    if character:getPrimaryHandItem()==weapon then character:setPrimaryHandItem(nil) end
    if character:getSecondaryHandItem()==weapon then character:setSecondaryHandItem(nil) end
    F.unload(character,weapon)
    return R.takeApart(character,weapon)
end

local complete=ISFixAction.complete
function ISFixAction:complete()
    local spare=F.spare(self.character,self.fixer,self.item)
    if spare and R.key(spare) then
        -- A failure leaves the spare to the vanilla code: the repair itself still happens.
        local ok,err=pcall(F.takeApart,self.character,spare)
        if ok then F.stripped=F.stripped+1 else
            F.failed=F.failed+1
            print('[TW] repair spare not taken apart: '..tostring(err))
        end
    end
    return complete(self)
end

return F
