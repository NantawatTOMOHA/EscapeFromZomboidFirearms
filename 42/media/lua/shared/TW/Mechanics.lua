-- Benelli M3: the normal Gunworks fire-mode control selects the native rack
-- mechanic. Gunworks restores weapon fields from shadows after ammunition and
-- part changes, so the selected action belongs to the saved instance modData.
local D=require('TW/Data')
local M={seen=setmetatable({},{__mode='k'})}
TWMechanics=M

function M.isDual(weapon)
    local key=weapon and D.byType[weapon:getFullType()]
    return key and D.weapons[key].kind=='dual-shotgun' or false
end

function M.apply(weapon,mode,player)
    if not M.isDual(weapon) then return false end
    if mode~='Pump' and mode~='Semi' then return false end
    assert(weapon.setRackAfterShoot,'[TW] M3 requires native setRackAfterShoot')
    local previous=weapon:getModData().TWActionMode or 'Pump'
    weapon:getModData().TWActionMode=mode
    weapon:setFireMode(mode)
    weapon:setRackAfterShoot(mode=='Pump')
    M.seen[weapon]=mode
    if previous~=mode and player and instanceof(player,'IsoPlayer') and not isServer() and player:isLocalPlayer() then
        local key=D.byType[weapon:getFullType()]
        local sounds=D.weapons[key].modeSounds
        if sounds and sounds[mode] then player:getEmitter():playSound(sounds[mode]) end
    end
    return true
end

function M.restore(weapon)
    if not M.isDual(weapon) then return end
    local mode=weapon:getModData().TWActionMode
    if mode~='Pump' and mode~='Semi' then mode=weapon:getFireMode() end
    if mode~='Pump' and mode~='Semi' then mode='Pump' end
    M.apply(weapon,mode)
end

function M.update(player)
    local weapon=player and player:getPrimaryHandItem()
    if not M.isDual(weapon) then return end
    if not M.seen[weapon] then M.restore(weapon)
    elseif weapon:getFireMode()~=M.seen[weapon] then M.apply(weapon,weapon:getFireMode(),player) end
end

local active=false
for _,weapon in pairs(D.weapons) do if weapon.kind=='dual-shotgun' then active=true end end
M.active=active
if active then
    local Stats=require('WeaponSystems/Utils/StatsFactory')
    local Rate=require('WeaponSystems/Utils/RateOfFire')
    Stats.RegisterRestoreHandler('TW:M3ActionMode',M.restore)
    -- Both the client UI and SWMG's authoritative server firemode command set
    -- FireMode and call this API. Capture that choice before its stat restore,
    -- so a freshly selected Semi cannot be overwritten by saved Pump.
    local adjust=Rate.RecoilDelayAdjuster
    Rate.RecoilDelayAdjuster=function(player,weapon,...)
        if M.isDual(weapon) then M.apply(weapon,weapon:getFireMode(),player) end
        return adjust(player,weapon,...)
    end
    Events.OnEquipPrimary.Add(M.update)
    Events.OnPlayerUpdate.Add(M.update)
end
return M
