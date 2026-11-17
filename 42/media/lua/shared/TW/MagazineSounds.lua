-- Substitute only the sound name. Vanilla and Gunworks keep ownership of the
-- loop latches, transfer limits, AmmoList and server-authoritative ammunition.
require('WeaponSystems/Hooks/CustomSystemHooks')
local D=require('TW/Data')
local M={familyByType={},familyEvents={}}
TWMagazineSounds=M

-- Weapon magazine lists already select the calibre-specific item type for
-- magazines shared between calibres (for example 5.56 and .300 BLK STANAGs).
-- D.byType contains weapons, while D.types contains these magazine instances.
for _,weapon in pairs(D.weapons) do
    local family=weapon.ammoKey
    for _,fullType in pairs(weapon.magazines or {}) do
        local meta=D.types[fullType]
        local item=meta and D.items[meta.tpl]
        if item and item.clip then
            local calibre=meta.calibre or family
            M.familyEvents[calibre]={load='TW_MagLoad_'..calibre,unload='TW_MagUnload_'..calibre}
            local previous=M.familyByType[fullType]
            if previous==nil then M.familyByType[fullType]=calibre
            elseif previous~=calibre then M.familyByType[fullType]=false end
        end
    end
end

function M.familyFor(magazine)
    return magazine and M.familyByType[magazine:getFullType()] or nil
end

function M.soundFor(magazine,operation)
    local family=M.familyFor(magazine)
    local events=family and M.familyEvents[family]
    local sound=events and events[operation]
    -- Missing/unbuilt content follows the previous sound path; never play an
    -- undefined TW event and never silence another mod's magazine.
    if sound and getScriptManager():getGameSound(sound) then return sound end
end

function M.install()
    if M.installed then return end
    M.installed=true
    local load=ISLoadBulletsInMagazine.animEvent
    ISLoadBulletsInMagazine.animEvent=function(self,event,parameter)
        if event=='InsertBulletSound' then
            parameter=M.soundFor(self.magazine,'load') or parameter
        end
        return load(self,event,parameter)
    end
    local unload=ISUnloadBulletsFromMagazine.animEvent
    ISUnloadBulletsFromMagazine.animEvent=function(self,event,parameter)
        if event=='RemoveBulletSound' then
            parameter=M.soundFor(self.magazine,'unload') or parameter
        end
        return unload(self,event,parameter)
    end
end

-- Capture the completed load-order chain, including Brita's fuel hook when
-- enabled. Repeated lifecycle events do not add another wrapper.
Events.OnGameBoot.Add(M.install)
Events.OnInitGlobalModData.Add(M.install)
Events.OnGameStart.Add(M.install)
return M
