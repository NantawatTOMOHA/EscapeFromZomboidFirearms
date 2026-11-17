-- Hot Brass 29-30 October 2026 splits the rack into ejectCasing / chamberRound and makes rackBullet a no-op.
-- EFZ's own animation nodes and the B42 server emulator still use rackBullet. Keep the current HB casing methods,
-- but accept either event contract once per action. No dependency, animation, item data or save format is changed.
local M={active=true,installed=false}
TWHotBrassCompat=M
if not getActivatedMods():contains('HBVCEFb42') then return M end

-- Gunworks requires HB's hooks before defining its own removeBullet. HB installs its replacements later, at
-- OnInitGlobalModData. Capture Gunworks' inventory give-back now (also used directly by TW/Fixing), not HB's
-- permanent-casing bookkeeping method, which deliberately returns no inventory item.
require('WeaponSystems/Hooks/CustomSystemHooks')
local HB=require('SpentCasingPhysics/Init')
local Ammo=require('WeaponSystems/Utils/Ammo')
local D=require('TW/Data')
local launchers=require('TW/Data/Launchers')
local giveBack=ISRackFirearm.removeBullet

local function weapon(action)
    if not M.active or HB.AMMOMAKER then return nil end -- Ammomaker keeps HB's original delegation path.
    local gun=action and action.gun
    local full=gun and gun:getFullType()
    if not full or (launchers.disposable and full==launchers.disposable.weapon) then return nil end
    local key=D.byType[full]
    return key and D.weapons[key] or nil
end

local function profile(gun)
    local list=gun:getModData().AmmoList
    if list and #list>0 then Ammo.AmmoProfileSetter(gun,list[#list]) end
end

function M.install()
    local rack=ISRackFirearm
    if not rack or (M.installed and rack.rackBullet==M.rackMethod) or type(rack.removeRoundFromChamber)~='function'
        or type(rack.chamberRound)~='function' or type(rack.removeRoundFromTube)~='function' then return false end
    M.installed=true
    local rackBullet,ejectSpent,removeBullet=rack.rackBullet,rack.ejectSpentRounds,rack.removeBullet
    -- HB reapplies its hooks when another world is opened in the same Lua VM. Reattach then, but retain its raw
    -- split bodies: calling an older copy of our guard inside a newer one would suppress the transition itself.
    M.split=M.split or {rack.removeRoundFromChamber,rack.chamberRound,rack.removeRoundFromTube}
    local open,chamber,tube=M.split[1],M.split[2],M.split[3]

    function rack:removeBullet(...)
        -- Only HB's physical live-round ejection suppresses inventory give-back. Repair and other direct callers
        -- must still receive the exact Gunworks round. The flag belongs to this action, never to the weapon.
        if weapon(self) and not self.twHBPhysicalEject then return giveBack(self,...) end
        return removeBullet(self,...)
    end

    function rack:removeRoundFromChamber(...)
        if not weapon(self) then return open(self,...) end
        if isClient() or self.twHBOpened then return end
        self.twHBOpened=true
        self.twHBPhysicalEject=true
        local result=open(self,...)
        self.twHBPhysicalEject=nil
        return result
    end

    function rack:chamberRound(...)
        if not weapon(self) then return chamber(self,...) end
        if isClient() or self.twHBChambered then return end
        if not self.twHBOpened then self:removeRoundFromChamber() end
        -- A failed unjam must not load another round. HB's split opening already made that decision.
        if self.gun:isJammed() then return end
        self.twHBChambered=true
        profile(self.gun)
        return chamber(self,...)
    end

    function rack:removeRoundFromTube(...)
        local row=weapon(self)
        if not row then return tube(self,...) end
        if self.gun:haveChamber() then return self:removeRoundFromChamber(...) end
        if isClient() or self.twHBOpened then return end
        self.twHBOpened=true
        -- EFZ's open-bolt / belt-fed magazine weapons rack only to clear a jam (TW_Init's existing rule).
        -- HB's replacement would otherwise throw away a live round at each magazine insertion.
        if #row.magazines>0 then
            self.gun:setJammed(false)
            syncHandWeaponFields(self.character,self.gun)
            return
        end
        return tube(self,...)
    end

    function rack:ejectSpentRounds(...)
        if not weapon(self) then return ejectSpent(self,...) end
        -- HB's server hook opens AND chambers here, before any rack event. Defer both to the same event as SP:
        -- cancelling a rack before that event leaves the live round, magazine and spent casing untouched.
    end

    function rack:rackBullet(...)
        if not weapon(self) then return rackBullet(self,...) end
        if isClient() then return end
        if self.gun:haveChamber() then
            self:removeRoundFromChamber()
            self:chamberRound()
        else
            self:removeRoundFromTube()
        end
    end
    M.rackMethod=rack.rackBullet

    -- EFZ round-load nodes keep vanilla's ejectSpentRounds contract, not HB's openWeapon tags. This method only
    -- ejects spent cases (unlike the rack's method of the same name); call it once at the original reload boundary.
    -- TW/RoundReload's existing client and server timing remains unchanged.
    local reload=ISReloadWeaponAction
    local reloadEject=reload.ejectSpentRounds
    function reload:ejectSpentRounds(...)
        if not weapon(self) or type(self.removeRoundFromTube)~='function' then return reloadEject(self,...) end
        return self:removeRoundFromTube()
    end
    print('[TW] Hot Brass legacy/split rack compatibility installed')
    return true
end

-- HB registered its installer while CustomSystemHooks was required above, so its split methods exist here in both
-- mod orders. A second init/start does not stack wrappers. No activation or sandbox option is rewritten.
Events.OnInitGlobalModData.Add(M.install)
return M
