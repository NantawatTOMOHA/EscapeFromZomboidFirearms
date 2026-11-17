-- W10 rack (the user's item 7, 27.10.2026): "Затвор иногда автоматически не передёргивается при полном опустошении
-- магазина и патронника" - the automatic rack after a reload, for every mechanism of the mod, and the state of the new
-- option "rack automatically after a reload" (on by default: today's behaviour).
--
-- The automatic rack is queued by the reload action itself, and each place has its own condition:
--   ISInsertMagazine:loadAmmo (single player) / perform (multiplayer client), and Gunworks's replacements of them:
--       "not isRoundChambered() and currentAmmo >= ammoPerShoot" - a magazine went in;
--   ISReloadWeaponAction:loadAmmo (vanilla and Gunworks): "haveChamber() and not isRoundChambered()" - round by round.
-- Both add the rack with ISTimedActionQueue.addAfter(self, ...), which returns nothing when the action is no longer in
-- the queue, and both skip it when the condition does not hold at the instant the event fires (a trailing animation
-- event, a cancelled or re-entered action, an empty magazine model at TWMagSeat). The weapon then keeps a loaded
-- magazine, an empty chamber and no rack: the bug the player reports.
--
-- This module is the one place the mod reconciles that, at the end of its own reload actions (TW_AutoRack.lua, client):
--   on    a completed reload with an empty chamber and rounds to chamber and no rack already queued => queue the rack;
--   off   a rack the reload has just queued behind itself (not started yet) is taken out again, so the chamber stays
--         empty and the player racks by hand (the rack key), like Tarkov; racks from other sources (the pump / bolt
--         cycle after a shot, the player's own key) are never touched.
-- Gunworks and vanilla keep owning ammo, the queue and the action; nothing is stored on items and no state is synced:
-- the option is a client setting, the chamber state travels as it does today.
--
-- The rack after a shot (the player's report of 30.10.2026 on the W11 build: "pump shotguns and bolt-action rifles
-- stopped racking the bolt automatically after each shot", also without Unlimited Ammo). The game queues it in one
-- place only, ISReloadWeaponAction.OnPlayerAttackFinished (a listener of Events.OnPlayerAttackFinished: a ranged weapon
-- with RackAfterShoot racks after every attack), and another mod can take that listener out: Hardship: Inertia
-- (Workshop 3816083168, 1.18.19 and 1.19.0, HI_Guns.lua) removes it at OnGameStart and adds its own, which returns
-- without a rack for every such weapon while its sandbox option ManualCycling ("Manual cycling (recommended)", default
-- on) is on - the player's world has it on (map_sand.bin). The weapon then fires once and keeps the spent case in the
-- chamber. With the option on, the mod's own weapons rack after a shot as in Tarkov whatever another mod did:
--   noteShot     the attack-finished event of the local player's weapon is noted (the listener of TW_AutoRack.lua);
--   reconcileShots  on the next tick, after every listener of that event has run, a noted weapon that racks after a
--                shot, has no live round in the chamber and has no rack queued gets one (exactly one: the game's own
--                rack, when it is there, is the one already queued). The game's own rule for a debug player with
--                Unlimited Ammo (no round spent, no rack) is kept.
-- With the option off nothing is added: a reload leaves the chamber empty, and the rack after a shot is left to the
-- game and the other mods (Hardship: Inertia's manual cycling then applies to the mod's weapons as well).
local M = {enabled = true, racks = 0, dropped = 0, errors = 0, lastError = '', shotRacks = 0, shotsNoted = 0, pending = {}}
TWAutoRack = M

local function weaponKey(gun)
    local D = require('TW/Data')
    return gun and gun.getFullType and D.byType[gun:getFullType()] or nil
end

-- The mod's weapon key of an item, else nil.
M.key = weaponKey

-- The option state (TW_AutoRack.lua pushes the option here; QA sets it directly).
function M.set(enabled)
    enabled = enabled == true
    if M.enabled == enabled then return false end
    M.enabled = enabled
    return true
end

-- Why a completed reload still needs a rack, or nil:
--   insert  a magazine went in: vanilla racks without a chamber check (a chamberless weapon cycles one round);
--   reload  round by round: vanilla racks only for a weapon with a chamber.
function M.reason(player, gun, insert)
    if not M.enabled then return nil end
    if not (player and gun) then return nil end
    if not weaponKey(gun) then return nil end
    if player.isDead and player:isDead() then return nil end
    if player.getPrimaryHandItem and player:getPrimaryHandItem() ~= gun then return nil end
    if gun:isRoundChambered() then return nil end
    local chamber = gun.haveChamber and gun:haveChamber() and true or false
    if not chamber and not insert then return nil end
    if gun:getCurrentAmmoCount() < gun:getAmmoPerShoot() then return nil end
    return chamber and 'empty-chamber' or 'chamberless'
end

-- True while a rack already runs or waits in the player's queue.
function M.queued(player)
    local Q = rawget(_G, 'ISTimedActionQueue')
    if type(Q) ~= 'table' or type(Q.hasActionType) ~= 'function' then return false end
    local ok, found = pcall(Q.hasActionType, player, 'ISRackFirearm')
    return ok and found == true
end

-- Queues the rack after the reload action, or at the end of the queue when that action has already left it. Returns
-- true when one was queued.
function M.rack(player, gun, previous)
    local Q = rawget(_G, 'ISTimedActionQueue')
    local class = rawget(_G, 'ISRackFirearm')
    if type(Q) ~= 'table' or type(class) ~= 'table' or type(class.new) ~= 'function' then return false end
    if M.queued(player) then return false end
    local action = class:new(player, gun)
    if previous then
        local _, added = Q.addAfter(previous, action)
        if added then
            M.racks = M.racks + 1
            return true
        end
    end
    Q.add(action)
    M.racks = M.racks + 1
    return true
end

-- The rack a reload has just put behind itself, if it is still there and has not started (perform runs while the
-- reload is the current action, so the rack is the next one and its start() has not run yet).
function M.queuedBehind(action)
    local Q = rawget(_G, 'ISTimedActionQueue')
    if type(Q) ~= 'table' or type(Q.getTimedActionQueue) ~= 'function' then return nil end
    local ok, queue = pcall(Q.getTimedActionQueue, action.character)
    if not ok or type(queue) ~= 'table' or type(queue.queue) ~= 'table' then return nil end
    local index
    for i, item in ipairs(queue.queue) do if item == action then index = i end end
    local nextAction = index and queue.queue[index + 1]
    if nextAction and nextAction.Type == 'ISRackFirearm' and not (nextAction.isStarted and nextAction:isStarted()) then
        return nextAction, queue
    end
end

-- Takes that rack out again (the option is off): it never started, so nothing of it is left on the character.
function M.dropQueuedRack(action)
    local rack, queue = M.queuedBehind(action)
    if not rack then return false end
    queue:removeFromQueue(rack)
    M.dropped = M.dropped + 1
    return true
end

-- The reconcile a completed reload (in its perform()) calls: with the option on and a reason to rack, queue it.
function M.afterReload(action, insert)
    if not (action and action.character and action.gun) then return false end
    if not M.reason(action.character, action.gun, insert) then return false end
    return M.rack(action.character, action.gun, action)
end

-- Why a weapon still needs its rack after an attack has finished, or nil (see the header): the option on, the mod's
-- ranged weapon that racks after a shot, still in the hands of a living player, no live round in the chamber, and not
-- the game's debug Unlimited Ammo (the game neither spends a round nor racks then).
function M.shotReason(player, gun)
    if not M.enabled then return nil end
    if not (player and gun) then return nil end
    if not weaponKey(gun) then return nil end
    if not (gun.isRanged and gun:isRanged()) then return nil end
    if not (gun.isRackAfterShoot and gun:isRackAfterShoot()) then return nil end
    if player.isDead and player:isDead() then return nil end
    if player.getPrimaryHandItem and player:getPrimaryHandItem() ~= gun then return nil end
    local debug = rawget(_G, 'getDebug')
    if type(debug) == 'function' and debug() and player.isUnlimitedAmmo and player:isUnlimitedAmmo() then return nil end
    if not (gun.haveChamber and gun:haveChamber()) then return nil end
    if gun:isRoundChambered() then return nil end
    return 'after-shot'
end

-- The listener of Events.OnPlayerAttackFinished (TW_AutoRack.lua adds it): only notes the weapon; the decision waits
-- for the next tick, when every other listener of the event (the game's, Hardship: Inertia's) has run.
function M.noteShot(player, gun)
    if not (M.enabled and player and gun) then return false end
    if player.isLocalPlayer and not player:isLocalPlayer() then return false end
    if not weaponKey(gun) then return false end
    M.pending[#M.pending + 1] = {player = player, gun = gun}
    M.shotsNoted = M.shotsNoted + 1
    return true
end

-- The rack of one finished attack: queued when the weapon needs it and no rack is queued yet. Returns true when one
-- was queued.
function M.afterShot(player, gun)
    if not M.shotReason(player, gun) then return false end
    if M.queued(player) then return false end
    local Q = rawget(_G, 'ISTimedActionQueue')
    local class = rawget(_G, 'ISRackFirearm')
    if type(Q) ~= 'table' or type(class) ~= 'table' or type(class.new) ~= 'function' then return false end
    Q.add(class:new(player, gun))
    M.shotRacks = M.shotRacks + 1
    return true
end

-- Every noted attack, once (TW_AutoRack.lua calls it on each tick). Returns the number of racks queued.
function M.reconcileShots()
    if #M.pending == 0 then return 0 end
    local list = M.pending
    M.pending = {}
    local queued = 0
    for _, shot in ipairs(list) do
        local ok, added = pcall(M.afterShot, shot.player, shot.gun)
        if not ok then
            M.errors = M.errors + 1
            M.lastError = tostring(added)
        elseif added then
            queued = queued + 1
        end
    end
    return queued
end

-- QA: "enabled=true racks=0 dropped=0 reason=empty-chamber queued=false ..."
function M.stats()
    return string.format('enabled=%s racks=%d dropped=%d shotRacks=%d shotsNoted=%d errors=%d', tostring(M.enabled),
        M.racks, M.dropped, M.shotRacks, M.shotsNoted, M.errors)
end

return M
