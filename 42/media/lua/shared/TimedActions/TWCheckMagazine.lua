-- Magazine check, as Tarkov's "check ammo" (user 2026-10-16): the character pulls the magazine,
-- looks at it and seats it again (anim variable TWMagCheck: the class's check node, Tarkov
-- <weapon>_check_N / glock_mag_check_N), then says how full it is. As in Tarkov the estimate is
-- coarse until the skill is high: below EXACT_LEVEL of Reloading - empty, almost empty, about
-- half, almost full, full; from EXACT_LEVEL - the exact count. Nothing in the weapon changes.
-- The check ends on the node's TWMagCheckDone event; the server emulates it after CHECK_MS, and a
-- client without the node (an old cache) finishes after the same time.
require 'TimedActions/ISBaseTimedAction'
local Anim=require('TW/AnimOptOut') -- W8 animopt: the two "vanilla animations" options

TWCheckMagazine = ISBaseTimedAction:derive('TWCheckMagazine')

local CHECK_MS = 2600
local EXACT_LEVEL = 6

function TWCheckMagazine:isValid()
    return self.character:getPrimaryHandItem() == self.gun and self.gun:isContainsClip()
end

function TWCheckMagazine:start()
    if not self.gun:isContainsClip() then
        self:forceStop()
        return
    end
    self:setAnimVariable('WeaponReloadType', tostring(self.gun:getWeaponReloadType()))
    -- W8 animopt (the user's item 9): with this view's option on the vanilla reload clip plays: no check node and no
    -- hand-model change of ours (the TWGripOwned gate already hides the node; this is the direct switch).
    if Anim.poses(self.character) then
        self:setAnimVariable('TWMagCheck', true)
        self:setOverrideHandModels(self.gun, nil)
    end
    self:setActionAnim(CharacterActionAnims.Reload)
    ISReloadWeaponAction.setReloadSpeed(self.character, false)
    self.startedAt = getTimestampMs()
end

function TWCheckMagazine:update()
    -- no check node for this class (or an old cache): finish after the emulated time
    local speed = math.max(0.1, self.character:getVariableFloat('ReloadSpeed', 1))
    if self.startedAt and getTimestampMs() - self.startedAt > CHECK_MS / speed + 500 and not isClient() then
        self:forceComplete()
    end
end

function TWCheckMagazine:serverStart()
    ISReloadWeaponAction.setReloadSpeed(self.character, false)
    emulateAnimEventOnce(self.netAction, ISReloadWeaponAction.getReloadTime(self.character, CHECK_MS), 'TWMagCheckDone', nil)
end

function TWCheckMagazine:animEvent(event, parameter)
    if event == 'TWMagCheckDone' then
        if isServer() then
            self.netAction:forceComplete()
        elseif not isClient() then
            self:forceComplete()
        end
    end
end

function TWCheckMagazine:getDuration()
    return -1
end

function TWCheckMagazine:complete()
    return true
end

-- The estimate key and its argument for a magazine with rounds of capacity.
function TWCheckMagazine.estimate(rounds, capacity, level)
    if level >= EXACT_LEVEL then return 'IGUI_TW_MagCheck_Exact', rounds .. '/' .. capacity end
    if rounds <= 0 then return 'IGUI_TW_MagCheck_Empty' end
    if rounds >= capacity then return 'IGUI_TW_MagCheck_Full' end
    local f = rounds / math.max(1, capacity)
    if f >= 0.75 then return 'IGUI_TW_MagCheck_AlmostFull' end
    if f >= 0.4 then return 'IGUI_TW_MagCheck_Half' end
    return 'IGUI_TW_MagCheck_AlmostEmpty'
end

local function finish(self)
    self.character:clearVariable('TWMagCheck')
    self.character:clearVariable('WeaponReloadType')
end

function TWCheckMagazine:stop()
    finish(self)
    ISBaseTimedAction.stop(self)
end

function TWCheckMagazine:perform()
    finish(self)
    if self.character:isLocalPlayer() and HaloTextHelper then
        local key, arg = TWCheckMagazine.estimate(self.gun:getCurrentAmmoCount(), self.gun:getMaxAmmo(), self.character:getPerkLevel(Perks.Reloading))
        local text = arg and getText(key, arg) or getText(key)
        -- Viewpoint first person: a screen line (TW_SightNotice), not a note at the camera.
        local notice = rawget(_G, 'TWSightNotice')
        if not (notice and notice(self.character, text, 'magazine')) then HaloTextHelper.addText(self.character, text) end
    end
    ISBaseTimedAction.perform(self)
end

function TWCheckMagazine:new(character, gun)
    local o = ISBaseTimedAction.new(self, character)
    o.gun = gun
    o.stopOnWalk = false
    o.stopOnRun = true
    o.stopOnAim = true
    o.maxTime = o:getDuration()
    o.useProgressBar = false
    return o
end
