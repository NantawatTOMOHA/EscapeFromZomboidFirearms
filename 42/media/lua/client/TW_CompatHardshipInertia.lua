-- Hardship: Inertia (Workshop 3816083168, mod id HardshipInertia) compatibility.
--
-- Hardship Inertia owns a player's body while it is off its feet: lying face down, on all fours, on its
-- back, scrambling, the knock-down and get-up moves, sitting against a wall and hanging on a tall fence.
-- Its animation nodes are additional nodes with m_ConditionPriority 100 (see its README and its AnimSets);
-- this mod's grip-class and stance nodes carry 100..200, so for a Tarkov weapon in hand ours won the state
-- and Hardship's posture never played at all (QA 26.10.2026: bare hands + Hardship prone = body prone,
-- camera 0.31 m; the same with an M4A1 or a GLOCK17 = the body still standing at 1.33 m).
--
-- The fix is the mod's own W8 "vanilla animations" mechanism, for one character at a time: TW/AnimOptOut
-- (shared) now takes per-character yield predicates, and TW_TacHoldOverride clears TWGripOwned and the
-- carry variables for a yielded character, so every stance/contact/carry node of this mod stops matching
-- and Hardship's own clips play. The Java layers ask AnimOptOut.blocks(character) and yield the same way
-- (TWZB.animOptOutYield, pushed from the sync hooks below). Without Hardship this file changes nothing: the
-- predicate is always false and the walk speed variable is the value the handgun walk node had before.
--
-- W10 prone / proneback (player item 5, 27.10.2026):
--   1. GripIK.seatYielded keeps Bip01_Prop1 firearm-oriented in Bip01_R_Hand (and seated by dR) while
--      Hardship's unarmed prone/fours/crawl clips own the body (touching zero body or arm bones there),
--      and when lying on the back (supine > 0 while TWHIFloor is true) holds the weapon on the upper chest
--      with the right arm and right fingers on the grip, while leaving W8's "vanilla animations" options
--      as a full yield.
--   2. TWHIFloor gates standalone zero-weight turning substate nodes (TW_HI_FloorTurn*) and non-aim
--      Viewpoint strafe floor/transition nodes (strafe/TW_HI_*) so neither unmasked turning sublayers nor
--      standing non-aim strafe clips bounce the pelvis while crawling, turning, strafing or transitioning.
--   3. Event tail re-registration at OnGameStart syncs TWGripOwned, TWHIFloor, TWGripIK and Java yield in
--      the same frame Hardship changes HIPose/HIDown, in either mod load order.
--
-- What is NOT yielded: aiming from the ground in Viewpoint's first person keeps this mod's Tarkov ADS -
-- the eye on the sight, the sight picture, the recoil camera and the point of impact from the drawn
-- weapon - over Hardship's face-down body (M.keepFpPose; coordinator decision 26.10.2026). Its own
-- face-down aim clips are the pose of the body under it.
--
-- Gun handling: Hardship forbids reloading, racking, loading/unloading a magazine and unloading bullets
-- from its floor postures except lying face down (HardshipInertia.gunsBlocked). It wraps the game's own
-- actions; this mod's own gun actions are wrapped here so they obey the same rule.
require('TW/AnimOptOut')

local M = {
    errors = 0,
    lastError = '',
    pushed = 0,
    blocked = 0,
    synced = 0,
    detected = false,
    qaBypass = false,
    FLOOR_VAR = 'TWHIFloor',
}
TWCompatHI = M

local State = require('TW/AnimOptOut')
local YIELD_ID = 'HardshipInertia'

local function pcallOk(f, ...)
    if type(f) ~= 'function' then return nil end
    local ok, value = pcall(f, ...)
    if ok then return value end
    return nil
end

local R = pcallOk(require, 'TW/Runtime')
local Launchers = pcallOk(require, 'TW/Data/Launchers')

-- ---------------------------------------------------------------- detection

-- The mod's module table, when it is really loaded (its own offFeet is the test we need).
local function hardship()
    local h = rawget(_G, 'HardshipInertia')
    if type(h) ~= 'table' or type(h.offFeet) ~= 'function' then return nil end
    return h
end

local function activated()
    local mods = getActivatedMods and pcallOk(getActivatedMods)
    if not mods or type(mods.contains) ~= 'function' then return false end
    local ok, value = pcall(function() return mods:contains('HardshipInertia') == true end)
    if ok and value then return true end
    local ok2, value2 = pcall(function() return mods:contains('\\HardshipInertia') == true end)
    return ok2 and value2 == true
end

-- ---------------------------------------------------------------- the yield

-- The body belongs to Hardship now: any of its floor postures, a fall, a wall sit or a fence (the kneel: M.kneelAim).
function M.offFeet(player)
    local h = hardship()
    if not (h and player) then return false end
    return pcallOk(h.offFeet, player) == true
end

-- Does this character hold one of this mod's firearms (including deployed underbarrels and spent RShG-2)?
function M.ownsWeapon(player)
    if not (player and type(player.getPrimaryHandItem) == 'function') then return false end
    local gun = pcallOk(player.getPrimaryHandItem, player)
    if not gun then return false end
    if R and type(R.visualKey) == 'function' and pcallOk(R.visualKey, gun) ~= nil then return true end
    if Launchers and Launchers.disposable and type(gun.getFullType) == 'function' then
        if pcallOk(gun.getFullType, gun) == Launchers.disposable.spent then return true end
    end
    if type(gun.getFullType) == 'function' then
        local ft = tostring(pcallOk(gun.getFullType, gun) or '')
        if ft:sub(1, 27) == 'EscapeFromZomboidFirearms.' then return true end
    end
    return false
end

-- Hardship's low crouch (player report 04.11.2026): crouched and still with a gun up it kneels on one knee, its own
-- HI_KneelAim*, HI_KneelAimTurn* and HI_KneelFirearm*/Handgun* nodes (HIPose=none, sneaking=true). They carry priority
-- 100 with 3..6 conditions, this mod's aim nodes 100 with 4 (handguns 101 with 8), and AnimState.getAnimNodes takes the
-- higher priority, then the most conditions: with a Tarkov weapon the body stood up to the standing aim as soon as the
-- right button went down (pelvis 85 cm against Hardship's 33 cm). The nodes of this mod yield as for its floor postures
-- (TWGripOwned; the Java layers stay, see pushToJava), for exactly the moments its kneel plays: moving with the gun up is the game's aimed walk there too (its aimBonusWant), and a gun
-- action has no kneeling clip, so both stay this mod's - except the rack of a pump or bolt gun right after the shot, the
-- game's masked aim rack on top of the kneel. Only the firearm and handgun kinds have kneeling nodes.
local KNEEL_WEAPON = {firearm = true, handgun = true}
function M.kneelAim(player)
    if M.qaBypass or not player or not hardship() or not M.ownsWeapon(player) then return false end
    if M.offFeet(player) then return false end
    if not (player.isSneaking and pcallOk(player.isSneaking, player) == true) then return false end
    if not (player.isAiming and pcallOk(player.isAiming, player) == true) then return false end
    if player.isPlayerMoving and pcallOk(player.isPlayerMoving, player) == true then return false end
    if pcallOk(player.getVariableBoolean, player, 'IsPerformingAnAction') == true
        and pcallOk(player.getVariableBoolean, player, 'isRacking') ~= true then return false end
    if pcallOk(player.getCurrentActionContextStateName, player) == 'strafe' then return false end
    return KNEEL_WEAPON[pcallOk(player.getVariableString, player, 'Weapon') or ''] == true
end

-- Hardship's clips own the body now: a floor posture, or the kneel.
function M.ownsBody(player)
    return M.offFeet(player) or M.kneelAim(player)
end

-- Should our standalone floor turning/strafe AnimSet nodes (TWHIFloor) govern this character right now?
-- True only while Hardship is active, QA bypass is off, the character is alive and out of a vehicle,
-- holds a firearm of this mod, W8's vanilla-animations option for its view is off, and Hardship has the
-- body in a floor pose or floor transition (HIPose ~= "none" or HIDown ~= "").
function M.floorActive(player)
    if M.qaBypass or not M.detected then return false end
    local h = hardship()
    if not (h and player) then return false end
    if player.isDead and pcallOk(player.isDead, player) == true then return false end
    if player.getVehicle and pcallOk(player.getVehicle, player) ~= nil then return false end
    if State.optionsBlock and State.optionsBlock(player) then return false end
    if not M.ownsWeapon(player) then return false end
    local pose = player.getVariableString and (pcallOk(player.getVariableString, player, 'HIPose') or '') or ''
    if pose ~= '' and pose ~= 'none' then return true end
    local down = player.getVariableString and (pcallOk(player.getVariableString, player, 'HIDown') or '') or ''
    return down ~= ''
end

-- Keep this mod's first-person Tarkov ADS over Hardship's face-down body: only the local player, only in
-- Viewpoint's first person, only while aiming, and only while the "First person: vanilla animations"
-- option is off (that option always wins).
function M.keepFpPose(player)
    if not (player and M.offFeet(player)) then return false end
    local local_ = player.isLocalPlayer and pcallOk(player.isLocalPlayer, player)
    if local_ ~= true then return false end
    if State.blocksFor(1) then return false end          -- the user asked for vanilla first-person animations
    local z = rawget(_G, 'TWZB')
    if not (type(z) == 'table' and type(z.vpState) == 'function') then return false end
    if pcallOk(z.vpState) ~= 1 then return false end
    local aiming = player.isAiming and pcallOk(player.isAiming, player) == true
    if not aiming and type(z.aimIntent) == 'function' then aiming = pcallOk(z.aimIntent, player) == true end
    return aiming
end

-- ---------------------------------------------------------------- Java mirror & floor variable

local pushed = setmetatable({}, {__mode = 'k'})
local floored = setmetatable({}, {__mode = 'k'})
local wasOff = setmetatable({}, {__mode = 'k'})
local wasGun = setmetatable({}, {__mode = 'k'})

local function pushFloorVar(player, on)
    if not player then return end
    if on then
        if floored[player] ~= true then
            local ok = pcall(function() player:setVariable(M.FLOOR_VAR, true) end)
            if ok then floored[player] = true end
        end
    elseif floored[player] then
        pcall(function() player:setVariable(M.FLOOR_VAR, false) end)
        floored[player] = nil
    end
end

-- W11 inertia (player item 15): the face-down copies of this mod's gun-action nodes (AnimSets/player/*/TW_HI_F_*,
-- tools/hi_floor_nodes.py) play Hardship's own prone clips; where the node's speed is a variable, the copy reads
-- that variable times (Hardship clip / standing clip) so the move lasts as long as standing (TW/Data/HIFloorSpeeds).
local FloorSpeeds = pcallOk(require, 'TW/Data/HIFloorSpeeds') or {}
local function pushFloorSpeeds(player)
    if not (player and (floored[player] or M.kneelAim(player))) then return end
    for i = 1, #FloorSpeeds do
        local s = FloorSpeeds[i]
        local base = pcallOk(player.getVariableFloat, player, s.var, 1.0) or 1.0
        pcall(player.setVariable, player, s.name, base * s.ratio)
    end
end
M.pushFloorSpeeds = pushFloorSpeeds

local function pushToJava(player)
    local z = rawget(_G, 'TWZB')
    if not (type(z) == 'table' and type(z.animOptOutYield) == 'function') then return end
    -- all Java layers yield while Hardship's floor postures own the body, except the first-person pose that keeps our
    -- ADS. Not for the kneel: its clips hold the weapon as ours do, so the hand layer keeps both hands on this weapon's
    -- own grips (GripIK.apply: without it the left hand hangs past the muzzle of a short gun).
    local yield = M.offFeet(player) and not M.keepFpPose(player)
    if pushed[player] == yield then return end
    local ok, err = pcall(z.animOptOutYield, player, yield)
    if ok then
        pushed[player] = yield
        M.pushed = M.pushed + 1
    else
        M.errors = M.errors + 1
        M.lastError = tostring(err)
    end
end

-- Sync TWHIFloor, TWGripOwned (via TWTacHoldOverride.clear), Java yield and TWGripIK in the same frame
-- Hardship changes HIPose/HIDown, so entering/exiting a floor posture never flashes 1 standing frame.
local function syncPlayer(player)
    if not player then return end
    if not M.detected and hardship() then M.detected = true end
    local off = M.ownsBody(player)
    local gun = player.getPrimaryHandItem and pcallOk(player.getPrimaryHandItem, player) or false
    local changed = (wasOff[player] ~= off) or (wasGun[player] ~= gun)
    wasOff[player] = off
    wasGun[player] = gun
    pushFloorVar(player, M.floorActive(player))
    pushToJava(player)
    if not M.qaBypass and changed then
        local tac = rawget(_G, 'TWTacHoldOverride')
        if type(tac) == 'table' and type(tac.clear) == 'function' then
            pcall(tac.clear, player)
        end
        local gik = rawget(_G, 'TWGripIK')
        if type(gik) == 'table' and type(gik.update) == 'function' then
            pcall(gik.update, player)
        end
        M.synced = M.synced + 1
    end
end
M.syncPlayer = syncPlayer

-- QA (W10 prone before/after in one run): true disables GripIK.floorSeat, TWHIFloor and same-frame sync,
-- reproducing the pre-W10 behavior (commit 6da9e7224e) without changing any mod option.
function M.setQaBypass(on)
    M.qaBypass = on == true
    local z = rawget(_G, 'TWZB')
    if type(z) == 'table' and type(z.gripIKFloorSeat) == 'function' then
        pcall(z.gripIKFloorSeat, not M.qaBypass)
    end
    if getNumActivePlayers and getSpecificPlayer then
        for i = 0, (pcallOk(getNumActivePlayers) or 0) - 1 do
            local p = pcallOk(getSpecificPlayer, i)
            if p then
                wasOff[p] = nil
                syncPlayer(p)
            end
        end
    end
end

-- ---------------------------------------------------------------- the handgun walk pace

-- Hardship ships its own copy of the game's walk nodes with their speed read from HIWalkScale
-- (1.04 x its WalkPace option, 0.88 by default) - including player/movement/walkhandgun.xml, the one
-- animation node both mods own. This mod's copy of that node wins the name when this mod loads after
-- Hardship (the supported order), so it reads TWWalkScale instead and the pace is kept here: the game's
-- 1.04, times Hardship's pace when it is on. Without Hardship the value is exactly the old 1.04.
M.WALK_SCALE = 'TWWalkScale'
local walked = setmetatable({}, {__mode = 'k'})

local function walkPace()
    local h = hardship()
    if not (h and type(h.opt) == 'function') then return 1.0 end
    local k = tonumber(pcallOk(h.opt, 'WalkPace', 0.88)) or 0.88
    if k < 0.5 then k = 0.5 elseif k > 1.0 then k = 1.0 end
    return k
end

local function pushWalk(player)
    if not player then return end
    local value = 1.04 * walkPace()
    if math.abs((walked[player] or -1) - value) < 1e-6 then return end
    local ok = pcall(function() player:setVariable(M.WALK_SCALE, value) end)
    if ok then walked[player] = value end
end

-- ---------------------------------------------------------------- gun handling

-- Our own gun actions must obey Hardship's rule, as the game's own do (its HI_Guns wraps
-- ISReloadWeaponAction, ISRackFirearm, ISInsertMagazine, ISEjectMagazine and ISUnloadBulletsFromFirearm).
local BLOCKED = {'TWCheckMagazine', 'TWWeaponUtility', 'TWDropMagazine'}

function M.gunsBlocked(player)
    local h = hardship()
    if not (h and player) then return false end
    return pcallOk(h.gunsBlocked, player) == true
end

local function wrapActions()
    for _, name in ipairs(BLOCKED) do
        local cls = rawget(_G, name)
        if type(cls) == 'table' and type(cls.isValid) == 'function' and not cls.twHardshipGuard then
            local original = cls.isValid
            cls.isValid = function(self, ...)
                if self and M.gunsBlocked(self.character or self.player) then
                    M.blocked = M.blocked + 1
                    return false
                end
                return original(self, ...)
            end
            cls.twHardshipGuard = true
        end
    end
end

-- ---------------------------------------------------------------- lifecycle

local announced = false
local function install()
    local h = hardship()
    M.detected = h ~= nil or activated()
    State.yieldFor(YIELD_ID, M.ownsBody)
    wrapActions()
    if M.detected and not announced then
        announced = true
        print('[TW-HI] Hardship: Inertia present: its floor postures own the body; ' ..
            'aiming from the ground in first person keeps the Tarkov ADS')
    end
end

local lastTickError
local function tick()
    local ok, err = pcall(function()
        -- Hardship may have been loaded after this mod: pick it up when it appears.
        if not M.detected and hardship() then install() end
        for i = 0, getNumActivePlayers() - 1 do
            local p = getSpecificPlayer(i)
            if p then syncPlayer(p); pushWalk(p); pushFloorSpeeds(p) end
        end
        local online = getOnlinePlayers and pcallOk(getOnlinePlayers)
        if online then
            for i = 0, online:size() - 1 do
                local p = online:get(i)
                if p then syncPlayer(p); pushWalk(p); pushFloorSpeeds(p) end
            end
        end
    end)
    if not ok then
        M.errors = M.errors + 1
        if lastTickError ~= tostring(err) then lastTickError = tostring(err); print('[TW-HI] off ' .. tostring(err)) end
    end
end

-- Hardship forbids aiming on all fours, on the back and between postures (its aimControl, HI_Client): it sets the
-- game's aim-input flag once, when the posture changes, and IsoGameCharacter.isAiming answers false while the flag is
-- up. Player report 04.11.2026 (video 1104): on the back the right button still stood the body up, in a mod list this
-- project does not have (with Hardship and this mod alone isAiming stays false). The aim state has no floor node, so
-- anything that clears the flag stands the body up for as long as the button is held. Put Hardship's rule back after
-- every update and say once what happened: the player's log then names the path if the body still stands up.
M.floorAimGuards = 0
local guardNoted, aimNoted = false, false
local function guardFloorAim(player)
    if M.qaBypass or not M.detected or not (player.isIgnoringAimingInput and player.setIgnoreAimingInput) then return end
    if not (player.isLocalPlayer and player:isLocalPlayer()) or not M.ownsWeapon(player) then return end
    local pose = player:getVariableString('HIPose') or ''
    if pose == '' or pose == 'none' or pose == 'prone' then return end
    if not player:isIgnoringAimingInput() then
        player:setIgnoreAimingInput(true)
        M.floorAimGuards = M.floorAimGuards + 1
        if not guardNoted then
            guardNoted = true
            print('[TW-HI] another mod cleared the aim-input flag Hardship: Inertia sets on the floor (pose=' .. pose .. '): put back')
        end
    end
    if not aimNoted and player:isAiming() then
        aimNoted = true
        print(string.format('[TW-HI] aiming on the floor with the flag up: pose=%s state=%s hostile=%s', pose,
            tostring(player:getCurrentActionContextStateName()), tostring(player:isPerformingHostileAnimation())))
    end
end

local function onPlayerUpdate(player)
    if player then
        pcall(syncPlayer, player)
        pcall(guardFloorAim, player)
    end
end

local function syncActivePlayers()
    if not (getNumActivePlayers and getSpecificPlayer) then return end
    for i = 0, (pcallOk(getNumActivePlayers) or 0) - 1 do
        local p = pcallOk(getSpecificPlayer, i)
        if p then pcall(syncPlayer, p) end
    end
end

local function onEquip(player)
    if player and (not instanceof or pcallOk(instanceof, player, 'IsoPlayer') == true) then
        wasGun[player] = nil
        pcall(syncPlayer, player)
    end
end

local function moveToTail(ev, fn)
    if ev and type(ev.Add) == 'function' then
        if type(ev.Remove) == 'function' then pcall(ev.Remove, fn) end
        ev.Add(fn)
    end
end

local function attachTailHooks()
    -- Re-add after all mods' Lua files have registered their handlers so syncPlayer runs after Hardship's
    -- own OnKeyPressed / OnKeyStartPressed / OnPlayerUpdate / OnTick regardless of mod load order.
    moveToTail(Events.OnPlayerUpdate, onPlayerUpdate)
    moveToTail(Events.OnKeyPressed, syncActivePlayers)
    moveToTail(Events.OnKeyStartPressed, syncActivePlayers)
    moveToTail(Events.OnTick, tick)
end

Events.OnGameStart.Add(function() install(); attachTailHooks(); tick() end)
Events.OnCreatePlayer.Add(function(_, player) install(); attachTailHooks(); if player then syncPlayer(player); pushWalk(player) end end)
Events.OnPlayerDeath.Add(function(player)
    local z = rawget(_G, 'TWZB')
    if player and type(z) == 'table' and type(z.animOptOutYield) == 'function' then pcall(z.animOptOutYield, player, false) end
    if player then
        pushFloorVar(player, false)
        pushed[player] = false
        wasOff[player] = nil
        wasGun[player] = nil
    end
end)
if Events.OnEquipPrimary then Events.OnEquipPrimary.Add(onEquip) end
if Events.OnPlayerUpdate then Events.OnPlayerUpdate.Add(onPlayerUpdate) end
if Events.OnKeyPressed then Events.OnKeyPressed.Add(syncActivePlayers) end
if Events.OnKeyStartPressed then Events.OnKeyStartPressed.Add(syncActivePlayers) end
Events.OnTick.Add(tick)
if Events.OnMainMenuEnter then
    Events.OnMainMenuEnter.Add(function()
        pushed = setmetatable({}, {__mode = 'k'})
        floored = setmetatable({}, {__mode = 'k'})
        wasOff = setmetatable({}, {__mode = 'k'})
        wasGun = setmetatable({}, {__mode = 'k'})
        walked = setmetatable({}, {__mode = 'k'})
    end)
end

-- QA: "detected=... blocked=... pushed=... errors=... offFeet=... floor=... keepFp=... walk=..."
function M.stats(player)
    return string.format('detected=%s bypass=%s blocked=%d pushed=%d synced=%d errors=%d offFeet=%s kneel=%s floor=%s keepFp=%s walk=%.3f %s',
        tostring(M.detected), tostring(M.qaBypass), M.blocked, M.pushed, M.synced, M.errors,
        player ~= nil and tostring(M.offFeet(player)) or '-',
        player ~= nil and tostring(M.kneelAim(player)) or '-',
        player ~= nil and tostring(M.floorActive(player)) or '-',
        player ~= nil and tostring(M.keepFpPose(player)) or '-', 1.04 * walkPace(),
        player ~= nil and tostring(State.stats(player)) or '')
end

install()
return M
