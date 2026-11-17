-- Player reports of 03.11.2026: "FPS drop when shooting: when shooting guns in multiplayer it causes peoples games to
-- drop to 1 fps and then back to normal", and the frequent "the mod freezes while shooting".
--
-- Gunworks (SWMG, a dependency) re-equips the weapon in the hands around every shot of a chambered firearm. Its hooks of
-- ISReloadWeaponAction.canShoot (each attack) and onShoot (OnWeaponSwingHitPoint, also on a dedicated server) call
-- Animations.lockActionOpen: CallAnimate now and, a cycle later (10 ticks), releaseActionLock's CallAnimate again. Each
-- CallAnimate is CallSyncHandWeaponFields: syncHandWeaponFields, setPrimaryHandItem(nil) and back to the weapon (both
-- hands for a two-handed one), resetEquippedHandsModels. What it exists for is the open/locked look of a weapon listed
-- in Animations.WeaponsWithAnimatedParts (CallAnimationFunction); the mod lists none of its weapons there (their bolts,
-- carriers and covers move in Java, MovingWeaponParts), so for them every shot paid for nothing:
--   * the shooter: up to three rebuilds of the hands' models (every part model of the assembly) and every
--     OnEquipPrimary/OnEquipSecondary listener of every mod run four times per rebuild;
--   * multiplayer: the server's re-equip sends the weapon to every client again (EquipPacket: each observer loads a new
--     item with all its parts and rebuilds the shooter's hands), and the server's syncHandWeaponFields makes the
--     shooter's client re-create every part of its own weapon (SyncHandWeaponFieldsPacket.processClient) - twice a
--     shot, ten shots a second in automatic fire.
-- The shot's own sync is the game's: the vanilla onShoot sends syncHandWeaponFields after every shot (the server only
-- takes the ammo fields from it, SyncHandWeaponFieldsPacket.processServer).
--
-- lockActionOpen is left out here for the mod's weapons without a Gunworks animated-parts entry (another mod may add
-- one: Gunworks then animates that weapon as before). Every other firearm, and the single re-equip Gunworks makes at
-- the end of a reload, rack or magazine action, are unchanged. Shared: the server's hook is the one that reaches
-- every client.
local M={enabled=true,skipped=0,passed=0}
TWGunworksCycle=M

local PREFIX='EscapeFromZomboidFirearms.'

-- True when lockActionOpen would only re-equip a mod weapon (parts: Gunworks' WeaponsWithAnimatedParts).
function M.skip(weapon,parts)
    if not M.enabled or not weapon or not weapon.getFullType then return false end
    local full=weapon:getFullType()
    return full~=nil and string.sub(full,1,#PREFIX)==PREFIX and not (parts and parts[full])
end

function M.install()
    if M.installed then return true end
    local ok,A=pcall(require,'WeaponSystems/Utils/Animations')
    if not ok or type(A)~='table' or type(A.lockActionOpen)~='function' then return false end
    -- Gunworks' hooks look the function up in its module table at every call, so the wrapper reaches all of them.
    local lock=A.lockActionOpen
    A.lockActionOpen=function(player,weapon,...)
        if M.skip(weapon,A.WeaponsWithAnimatedParts) then
            M.skipped=M.skipped+1
            return
        end
        M.passed=M.passed+1
        return lock(player,weapon,...)
    end
    M.installed=true
    return true
end

M.install()
return M
