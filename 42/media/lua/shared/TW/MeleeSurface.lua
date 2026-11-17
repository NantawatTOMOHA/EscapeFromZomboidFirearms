-- The melee surface of a hit belongs to that hit (W11 doorsound, the player's report of 29.10.2026: "when I
-- break doors or roller shutters with a gun, the door-breaking attack sound shows up when I push zombies").
--
-- PZ keeps one melee-hit surface per player (IsoPlayer.setMeleeHitSurface, the FMOD parameter
-- MeleeHitSurface) and the shove sound is played with it: SwipeStatePlayer plays AttackShove (AttackStomp for
-- a stomp) on the player, and the event's material layer comes from that parameter. The engine sets it on a
-- hit - IsoDoor/IsoThumpable/IsoWindow with the weapon's DoorHitSound (every HandWeapon defaults it to
-- BaseballBatHit, so a firearm hit sets it too), IsoBarricade and IsoTree with any weapon - and never puts it
-- back: CombatManager.processHit returns before the Body value for a shove, and a door that breaks only plays
-- its own <DoorSound>Break on the player's emitter. The next shove is therefore heard as a hit on the last
-- door, window, barricade or tree.
--
-- Here the surface goes back to the body a shove hits:
--   * before a shove or a stomp starts (Events.OnWeaponSwing runs before SwipeStatePlayer plays the sound);
--   * at the end of every attack, so a door hit's material belongs to that hit only.
-- The value is an FMOD parameter of the local emitter, so this is client-side work only (a dedicated server
-- has no listener; there the module does nothing). Any player the client renders is covered: on a server the
-- door packet carries the material to every client, so a remote player's copy of the surface would otherwise
-- stay on the door after their shove. A shot, a reload or another interaction never reaches these paths.
local M = {BODY = 'Body'}

-- The game side that has a listener: a client, or the single-player game (both isServer and isClient false).
local function listening()
    if isServer and isServer() and not (isClient and isClient()) then return false end
    return true
end

-- Put a player's melee surface back to a body. Returns whether the value was written.
function M.restore(player)
    if not player or not player.setMeleeHitSurface then return false end
    local ok = pcall(function() player:setMeleeHitSurface(M.BODY) end)
    return ok
end

-- A shove or a stomp is aimed at a body, not at the surface of the last hit.
function M.onSwing(player)
    if not player then return end
    local shove = false
    pcall(function() shove = player:isDoShove() == true or player:isAimAtFloor() == true end)
    if shove then M.restore(player) end
end

-- The surface a hit set is the sound of that hit; nothing that follows it may wear it.
function M.onFinished(player)
    M.restore(player)
end

function M.install()
    if M.installed or rawget(_G, 'TWMeleeSurfaceInstalled') or not listening() or not Events then return M end
    if Events.OnWeaponSwing and Events.OnWeaponSwing.Add then
        Events.OnWeaponSwing.Add(M.onSwing)
    end
    if Events.OnPlayerAttackFinished and Events.OnPlayerAttackFinished.Add then
        Events.OnPlayerAttackFinished.Add(M.onFinished)
    end
    M.installed = true
    TWMeleeSurfaceInstalled = M
    return M
end

return M
