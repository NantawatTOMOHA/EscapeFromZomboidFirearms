-- SWMG caches the ammo enum, but clears ActiveAmmoProfile on restore. Launcher
-- rounds all have absolute profiles, including the script's default HE round.
-- Repair only that incomplete cache; changed enums retain the framework path.
local Ammo=require('WeaponSystems/Utils/Ammo')
local D=require('TW/Data')
local config=require('TW/Data/Launchers')
local P={}

function P.entry(weapon,bulletType)
    if not config.enabled or not weapon or not instanceof(weapon,'HandWeapon') or type(bulletType)~='string' then return nil end
    local full=weapon:getFullType()
    local key=D.byType[full]
    local row=key and D.weapons[key]
    local ammoKey
    if row and (row.kind=='launcher-break' or row.kind=='launcher-revolver' or row.kind=='rocket') then ammoKey=row.ammoKey end
    if not ammoKey then
        for _,launcher in ipairs(config.underbarrels or {}) do
            if launcher.weapon==full then ammoKey=launcher.ammoKey;break end
        end
    end
    if not ammoKey then return nil end
    local family='TW:ammo:'..ammoKey
    if Ammo.GetFamilyForItem(weapon)~=family then return nil end
    for _,entry in ipairs(Ammo.AmmoFamilies[family] or {}) do
        if entry.type==bulletType and entry.enum and entry.profile and Ammo.AmmoStats[entry.profile] then return entry end
    end
end

function P.needsRepair(weapon,bulletType)
    local entry=P.entry(weapon,bulletType)
    if entry and weapon:getAmmoType()==entry.enum and weapon:getModData().ActiveAmmoProfile~=entry.profile then return entry end
end

function P.install()
    if P.installed or not config.enabled then return end
    local previous=Ammo.AmmoProfileSetter
    Ammo.AmmoProfileSetter=function(weapon,bulletType)
        local entry=P.needsRepair(weapon,bulletType)
        if not entry then return previous(weapon,bulletType) end
        if isClient() then
            local player=getSpecificPlayer(0)
            if player then sendClientCommand(player,'SWMG','ammoProfile',{itemId=weapon:getID(),bulletType=bulletType}) end
        end
        Ammo.AmmoAdjustWeaponStats(weapon,bulletType,entry.enum)
    end
    P.installed=true
end

-- The normal server handler also skips identical enums. This companion handler
-- resolves an owned item itself and accepts neither client profiles nor stats.
-- It is safe before or after SWMG's handler and uses its existing owner reply.
function P.onClientCommand(module,command,player,args)
    if not isServer() or module~='SWMG' or command~='ammoProfile' or not player or type(args)~='table'
        or type(args.itemId)~='number' or type(args.bulletType)~='string' then return end
    local inventory=player:getInventory()
    local weapon=inventory and inventory:getItemById(args.itemId)
    local entry=P.needsRepair(weapon,args.bulletType)
    if not entry then return end
    Ammo.AmmoAdjustWeaponStats(weapon,args.bulletType,entry.enum)
    sendServerCommand(player,'SWMG','applyAmmoProfile',{itemId=weapon:getID(),bulletType=args.bulletType})
end

return P
