BlackoutPredators = BlackoutPredators or {}

local BP = BlackoutPredators
local CLIENT_MODE = isClient and isClient() == true
local knownVolatiles = {}
local revisionHighWater = {}
local lastSnapshotSequences = {}
local snapshotMembership = {}
local lastConfigSequence = -1
local authoritativeConfigReady = not CLIENT_MODE
local appliedStates = {}
local ownerStates = {}
local nextLoreChecks = {}
local nextUVDrives = {}
local nextMoveDrives = {}
local reportCooldowns = {}
local uvDistances = {}
local uvPredictions = {}
local localUVGuards = {}
local uvTrackedVolatiles = setmetatable({}, { __mode = "k" })
local uvNegativeSearches = setmetatable({}, { __mode = "k" })
local uvPositiveSources = setmetatable({}, { __mode = "k" })
local directedAttackCancels = setmetatable({}, { __mode = "k" })
local untrackedVolatileChecks = setmetatable({}, { __mode = "k" })
local untrackedVolatileStates = setmetatable({}, { __mode = "k" })
local appearanceIdentityChecks = setmetatable({}, { __mode = "k" })
local objectRuntimeStates = setmetatable({}, { __mode = "k" })
local ordinaryIdentityChecks = setmetatable({}, { __mode = "k" })
local runtimeConfigEpoch = 0
local tickCounter = 0
local uvScanCursor = 1
local nextScopePruneAt = 0
local nextMissingStateSyncAt = 0
local appearanceClockMs = 0
local appearanceProbeToken = 0
local normalizeInteger

-- Keep expensive world-light searches and MP snapshot cleanup from being
-- multiplied by every loaded zombie on the same frame.
local UV_LIGHT_SEARCH_BUDGET = 8
local UV_NEGATIVE_CACHE_NEAR_MS = 250
local UV_NEGATIVE_CACHE_FAR_MS = 1000
local UV_NEGATIVE_CACHE_NEAR_DISTANCE = 30
local UV_NEGATIVE_CACHE_DANGER_PADDING = 3
local SNAPSHOT_PRUNE_INTERVAL_MS = 1000
local AWAKE_LORE_WATCHDOG_MS = 1000
local UNTRACKED_VOLATILE_RETRY_MS = 500
local MISSING_STATE_SYNC_INTERVAL_MS = 5000
local APPEARANCE_PROBE_RETRY_MS = 2000
local APPEARANCE_CHECK_INITIAL_RETRY_MS = 750
local APPEARANCE_CHECK_FINAL_RETRY_MS = 4000
local APPEARANCE_FINGERPRINT_RECHECK_MS = 15000
local APPEARANCE_CANDIDATE_HOLD_MS = 8000
local MAX_APPEARANCE_PROBE_TOKEN = 2147483647
local SOUND_POLL_INTERVAL_MS = 100
local SLEEPING_MUTE_WATCHDOG_MS = 500
local ANIMATION_SPEED_WATCHDOG_MS = 1000
local ORDINARY_IDENTITY_RECHECK_MS = 1000

-- Volatile voices are mixed locally on every client. Ambient voices use one
-- shared slot near the player; combat reactions may use at most two slots.
-- This keeps a nest group audible without seven creatures shouting together.
local volatileSoundProfiles = {
    attack = { name = "Attack", count = 11, duration = 1600, cooldown = 700 },
    bite = { name = "Bite", count = 3, duration = 1200, cooldown = 700 },
    chase = { name = "Chase", count = 3, duration = 2800, cooldown = 3500, ambient = true, groupCooldown = 2500 },
    death = { name = "Death", count = 4, duration = 2800, cooldown = 5000, always = true, world = true, distance = 30 },
    hit = { name = "Hit", count = 4, duration = 1100, cooldown = 650 },
    returnhome = { name = "ReturnHome", count = 1, duration = 5200, cooldown = 10000, ambient = true, groupCooldown = 4500 },
    scream = { name = "Scream", count = 2, duration = 6500, cooldown = 18000, ambient = true, groupCooldown = 5000 },
    search = { name = "Search", count = 4, duration = 4000, cooldown = 8000, ambient = true, idleAmbient = true, groupCooldown = 5000, world = true, distance = 32 },
    seeplayer = { name = "SeePlayer", count = 4, duration = 2200, cooldown = 5000, ambient = true, groupCooldown = 1800 },
    sleepbreath = { name = "SleepBreath", count = 2, duration = 6500, cooldown = 9000, ambient = true, groupCooldown = 6500, world = true, distance = 18 },
    uvpain = { name = "UVPain", count = 4, duration = 1800, cooldown = 2400, ambient = true, groupCooldown = 1200, world = true, distance = 32 },
    wake = { name = "Wake", count = 5, duration = 6500, cooldown = 10000, ambient = true, groupCooldown = 5000 },
}

local volatileSoundStates = setmetatable({}, { __mode = "k" })
local activeVoiceSlots = {}
local nextAmbientVoiceAt = 0
local nextIdleAmbientVoiceAt = 0
local nextCombatVoiceAt = 0

local function soundNow()
    if getTimestampMs then return getTimestampMs() end
    return math.floor(BP.GetWorldHour() * 60 * 60 * 1000)
end

local function getVolatileSoundVolume()
    if BP.GetSoundEffectsVolumeMultiplier then
        local ok, value = pcall(BP.GetSoundEffectsVolumeMultiplier)
        if ok and tonumber(value) then
            return math.max(0, math.min(2, tonumber(value)))
        end
    end
    return 1
end

local function getAmbientScreamDelayMilliseconds()
    if BP.GetAmbientScreamDelayMilliseconds then
        local ok, value = pcall(BP.GetAmbientScreamDelayMilliseconds)
        if ok and tonumber(value) then
            return math.max(5000, math.min(300000, math.floor(tonumber(value))))
        end
    end
    return 60000
end

local function setWorldVolatileSoundVolume(audio, volume)
    if not audio then return false end
    pcall(function() audio:setVolume(volume) end)
    return true
end

local function randomBetween(minimum, maximum)
    minimum = math.floor(tonumber(minimum) or 0)
    maximum = math.floor(tonumber(maximum) or minimum)
    if maximum <= minimum then return minimum end
    return ZombRand(minimum, maximum + 1)
end

local function getVolatileSoundState(zombie)
    local soundState = volatileSoundStates[zombie]
    if not soundState then
        soundState = { cooldowns = {} }
        volatileSoundStates[zombie] = soundState
    end
    return soundState
end

local function pruneVoiceSlots(now)
    for index = #activeVoiceSlots, 1, -1 do
        if (activeVoiceSlots[index].untilTime or 0) <= now then
            table.remove(activeVoiceSlots, index)
        end
    end
end

local function countNearbyVoiceSlots(zombie, now)
    pruneVoiceSlots(now)
    local count = 0
    for _, slot in ipairs(activeVoiceSlots) do
        if math.floor(tonumber(slot.z) or -99) == math.floor(zombie:getZ())
            and BP.Distance2D(slot.x, slot.y, zombie:getX(), zombie:getY()) <= 25 then
            count = count + 1
        end
    end
    return count
end

local function getLocalPlayers()
    local result = {}
    if getNumActivePlayers and getSpecificPlayer then
        local count = math.max(0, tonumber(getNumActivePlayers()) or 0)
        for index = 0, count - 1 do
            local player = getSpecificPlayer(index)
            if player and not player:isDead() then result[#result + 1] = player end
        end
    else
        local player = getPlayer and getPlayer() or nil
        if player and not player:isDead() then result[1] = player end
    end
    return result
end

local function sendAuthoritativeSyncRequest()
    if not isClient() then return end
    local players = getLocalPlayers()
    if #players == 0 then
        sendClientCommand(BP.Module, "RequestSync", {})
    else
        for _, player in ipairs(players) do
            sendClientCommand(player, BP.Module, "RequestSync", {})
        end
    end
end

local function nearestLocalPlayer(zombie, maximumDistance, players)
    if not zombie then return nil end
    local nearest
    local nearestDistance = maximumDistance or math.huge
    for _, player in ipairs(players or getLocalPlayers()) do
        if math.floor(player:getZ()) == math.floor(zombie:getZ()) then
            local distance = BP.Distance2D(
                player:getX(), player:getY(), zombie:getX(), zombie:getY()
            )
            if distance <= nearestDistance then
                nearest = player
                nearestDistance = distance
            end
        end
    end
    return nearest, nearestDistance
end

-- Appearance can arrive before (or after) the authoritative Volatile state
-- when a zombie enters relevance or changes MP owner. Probe only the visible
-- zombie; the server either sends its real identity or repairs a vanilla zombie
-- that accidentally received BP_Volatile from B42's global outfit pool.
local function holdAppearanceCandidate(zombie, resetState)
    if not zombie or BP.IsCorpseDragProxy(zombie)
        or not BP.CanControlZombie(zombie) then return false end

    local data = zombie:getModData()
    if data then data.BP_AppearanceCandidateHeld = true end

    pcall(function() zombie:clearAggroList() end)
    pcall(function() zombie:setTarget(nil) end)
    pcall(function() zombie:setPath2(nil) end)
    pcall(function() zombie:getPathFindBehavior2():reset() end)
    BP.CancelZombieAttack(zombie, resetState == true, true)
    -- setDefaultState inside CancelZombieAttack can rebuild a path.
    pcall(function() zombie:setPath2(nil) end)
    pcall(function() zombie:getPathFindBehavior2():reset() end)
    pcall(function() zombie:setUseless(true) end)
    return true
end

local function releaseAppearanceCandidate(zombie, force)
    if not zombie then return end
    if BP.IsCorpseDragProxy(zombie) then
        appearanceIdentityChecks[zombie] = nil
        return
    end
    local data = zombie:getModData()
    if not data then return end
    if force ~= true and (data.BP_AppearanceCandidateHeld ~= true
        or data.BP_IsVolatile == true) then return end
    data.BP_AppearanceCandidateHeld = nil
    BP.ClearZombieAttackOverrides(zombie)
    pcall(function() zombie:setVariable("BP_Sleeping", false) end)
    pcall(function() zombie:setUseless(false) end)
end

local function allocateAppearanceProbeToken()
    appearanceProbeToken = appearanceProbeToken + 1
    if appearanceProbeToken > MAX_APPEARANCE_PROBE_TOKEN then
        appearanceProbeToken = 1
    end
    return appearanceProbeToken
end

local function probeVolatileAppearance(zombie)
    if not isClient() or not zombie or zombie:isDead()
        or BP.IsCorpseDragProxy(zombie)
        or not BP.HasStrongVolatileAppearance then return false end

    local now = appearanceClockMs > 0 and appearanceClockMs or soundNow()
    local fingerprint = BP.GetZombieAppearanceFingerprint
        and BP.GetZombieAppearanceFingerprint(zombie) or tostring(zombie)
    local check = appearanceIdentityChecks[zombie]
    local currentOnlineId = normalizeInteger(BP.GetZombieID(zombie))
    if check and check.onlineId ~= nil
        and normalizeInteger(check.onlineId) ~= currentOnlineId then
        -- Pooled Java zombie objects and online IDs can both be reused. Never
        -- carry a completed negative/positive probe across that identity edge.
        releaseAppearanceCandidate(zombie)
        check = nil
        appearanceIdentityChecks[zombie] = nil
    end
    if check and check.fingerprint ~= fingerprint then
        releaseAppearanceCandidate(zombie)
        check = nil
        appearanceIdentityChecks[zombie] = nil
    end
    if check and check.serverStatus == "volatile"
        and now >= (tonumber(check.confirmedUntil) or 0) then
        -- A correlated confirmation only bridges the initial state-packet gap.
        -- Once it expires, discard it so strong appearance starts a fresh token
        -- probe instead of remaining complete forever.
        check = nil
        appearanceIdentityChecks[zombie] = nil
    end
    if check and check.timedOut == true
        and now >= (tonumber(check.nextAt) or 0) then
        -- A lost reply must not permanently classify a genuine Volatile as an
        -- ordinary zombie. Retry later with a fresh, bounded token/hold.
        check = nil
        appearanceIdentityChecks[zombie] = nil
    end

    -- Ownership may move to this client after the first probe. Reassert the
    -- already-confirmed candidate guard before the cooldown return, without
    -- repeating the comparatively expensive appearance scan every update.
    if check and check.appearanceConfirmed == true then
        if not BP.CanControlZombie(zombie) then
            releaseAppearanceCandidate(zombie)
            appearanceIdentityChecks[zombie] = nil
            return false
        end
        if now >= (tonumber(check.holdUntil) or 0) then
            releaseAppearanceCandidate(zombie)
            check.appearanceConfirmed = nil
            check.complete = true
            check.timedOut = true
            check.nextAt = now + APPEARANCE_FINGERPRINT_RECHECK_MS
            return false
        end
        holdAppearanceCandidate(zombie, false)
    end
    if not BP.CanControlZombie(zombie) then return false end
    if check and now < (tonumber(check.nextAt) or 0) then return false end

    if check and check.complete == true then
        check.nextAt = now + APPEARANCE_FINGERPRINT_RECHECK_MS
        return false
    end

    if not BP.HasStrongVolatileAppearance(zombie) then
        releaseAppearanceCandidate(zombie)
        local attempts = math.floor(tonumber(check and check.attempts) or 0) + 1
        if attempts >= 3 then
            appearanceIdentityChecks[zombie] = {
                complete = true,
                fingerprint = fingerprint,
                nextAt = now + APPEARANCE_FINGERPRINT_RECHECK_MS,
            }
        else
            appearanceIdentityChecks[zombie] = {
                attempts = attempts,
                fingerprint = fingerprint,
                nextAt = now + (attempts == 1
                    and APPEARANCE_CHECK_INITIAL_RETRY_MS
                    or APPEARANCE_CHECK_FINAL_RETRY_MS),
            }
        end
        return false
    end

    local onlineId = currentOnlineId
    local reporter = nearestLocalPlayer(
        zombie, math.max(1, tonumber(BP.GetConfig().SyncRadius) or 120)
    )
    if not onlineId or not reporter then
        -- Never disable vanilla AI unless the targeted authority request can
        -- actually be sent. A newborn ID or out-of-scope zombie is retried
        -- after replication/relevance catches up.
        releaseAppearanceCandidate(zombie)
        appearanceIdentityChecks[zombie] = {
            attempts = 0,
            fingerprint = fingerprint,
            nextAt = now + APPEARANCE_CHECK_INITIAL_RETRY_MS,
        }
        if now >= nextMissingStateSyncAt then
            nextMissingStateSyncAt = now + MISSING_STATE_SYNC_INTERVAL_MS
            sendAuthoritativeSyncRequest()
        end
        return false
    end

    local alreadyConfirmed = check and check.appearanceConfirmed == true
    local heldAt = alreadyConfirmed and tonumber(check.heldAt) or now
    local holdUntil = alreadyConfirmed and tonumber(check.holdUntil)
        or (heldAt + APPEARANCE_CANDIDATE_HOLD_MS)
    local probeToken = alreadyConfirmed and tonumber(check.probeToken)
        or allocateAppearanceProbeToken()

    -- Relevance can rematerialize a genuine nest Volatile before its server
    -- record reaches the new owner. Hold an exact appearance candidate only
    -- for a bounded authority window; retries never renew this deadline.
    if not alreadyConfirmed and not holdAppearanceCandidate(zombie, true) then
        return false
    end
    appearanceIdentityChecks[zombie] = {
        attempts = 0,
        appearanceConfirmed = true,
        fingerprint = fingerprint,
        onlineId = onlineId,
        heldAt = heldAt,
        holdUntil = holdUntil,
        probeToken = probeToken,
        nextAt = now + APPEARANCE_PROBE_RETRY_MS,
    }
    sendClientCommand(reporter, BP.Module, "ProbeVolatile", {
        id = onlineId,
        probeToken = probeToken,
    })
    return true
end

local function canLocalPlayerHear(zombie)
    if not zombie then return false end
    for _, player in ipairs(getLocalPlayers()) do
        if math.abs(math.floor(player:getZ()) - math.floor(zombie:getZ())) <= 1
            and BP.Distance2D(player:getX(), player:getY(), zombie:getX(), zombie:getY()) <= 50 then
            return true
        end
    end
    return false
end

local function playVolatileSound(zombie, soundKey)
    local profile = volatileSoundProfiles[soundKey]
    if not zombie or not profile or not canLocalPlayerHear(zombie) then return false end

    local now = soundNow()
    local soundState = getVolatileSoundState(zombie)
    if (soundState.cooldowns[soundKey] or 0) > now then return false end
    local volume = getVolatileSoundVolume()
    if volume <= 0 then
        -- Preserve per-voice cooldowns while muted so silent sound attempts do
        -- not run on every zombie update.
        soundState.cooldowns[soundKey] = now + (profile.cooldown or 0)
        return false
    end

    local nearbyVoices = countNearbyVoiceSlots(zombie, now)
    if profile.always ~= true then
        if nearbyVoices >= 2 then return false end
        if profile.ambient then
            if nearbyVoices >= 1 or nextAmbientVoiceAt > now then return false end
            if profile.idleAmbient == true and nextIdleAmbientVoiceAt > now then return false end
        elseif nextCombatVoiceAt > now then
            return false
        end
    end

    local variant = randomBetween(1, profile.count)
    local soundName = string.format("BPV_%s_%02d", profile.name, variant)
    local played = false
    pcall(function()
        if profile.world == true then
            local square = zombie:getCurrentSquare() or zombie:getSquare()
            if square then
                -- B42.20 treats maxGain as a cap, not as this mod's volume
                -- multiplier. Set the returned local Audio gain explicitly.
                local sound = getSoundManager():PlayWorldSoundImpl(
                    soundName, false,
                    square:getX(), square:getY(), square:getZ(),
                    1, profile.distance or 30, 1, false
                )
                played = setWorldVolatileSoundVolume(sound, volume)
            end
        else
            local emitter = zombie:getEmitter()
            if emitter then
                -- Each client mixes Volatile voices using its own option. The
                -- Impl path prevents an unscaled duplicate MP sound packet.
                local soundId = emitter:playSoundImpl(soundName, nil)
                if soundId and soundId ~= 0 then
                    emitter:setVolume(soundId, volume)
                    played = true
                end
            end
        end
    end)
    if not played then return false end

    soundState.cooldowns[soundKey] = now + (profile.cooldown or 0)
    activeVoiceSlots[#activeVoiceSlots + 1] = {
        x = zombie:getX(),
        y = zombie:getY(),
        z = zombie:getZ(),
        untilTime = now + (profile.duration or 1500),
    }
    if profile.ambient then
        nextAmbientVoiceAt = now + (profile.groupCooldown or 1500)
        if profile.idleAmbient == true then
            nextIdleAmbientVoiceAt = now + getAmbientScreamDelayMilliseconds()
        end
    else
        nextCombatVoiceAt = now + 120
    end
    return true
end

local function readZombieAttackState(zombie)
    local attacking = false
    if zombie.isZombieAttacking then
        pcall(function() attacking = zombie:isZombieAttacking() == true end)
    end
    local outcome = ""
    if zombie.getVariableString then
        pcall(function() outcome = tostring(zombie:getVariableString("AttackOutcome") or "") end)
    end
    return attacking, outcome
end

local function updateVolatileSounds(zombie, entry, nowOverride)
    if not zombie or zombie:isDead() or not entry then return end
    local now = tonumber(nowOverride) or soundNow()
    local soundState = getVolatileSoundState(zombie)
    local stateName = tostring(entry.state or "night")
    local stateTransition = soundState.initialized == true
        and soundState.stateName ~= stateName
    if soundState.initialized == true and not stateTransition
        and now < (tonumber(soundState.nextPollAt) or 0) then
        return
    end
    soundState.nextPollAt = now + SOUND_POLL_INTERVAL_MS

    local hasTarget = zombie:getTarget() ~= nil
    local attacking, attackOutcome = readZombieAttackState(zombie)

    -- B42's vanilla idle vocals are much denser than this mod's custom pack.
    -- Mute them while an awake Volatile has no target; Search is played as a
    -- separate world sound below and therefore is not cut off by stopAll().
    if not hasTarget and (stateName == "night" or stateName == "ambient-night") then
        pcall(function()
            local emitter = zombie:getEmitter()
            if emitter then emitter:stopAll() end
        end)
    end

    if soundState.initialized ~= true then
        soundState.initialized = true
        soundState.stateName = stateName
        soundState.hasTarget = hasTarget
        soundState.attacking = attacking
        soundState.attackOutcome = attackOutcome
        soundState.nextBreathAt = now + randomBetween(3000, 7000)
        soundState.nextSearchAt = now + randomBetween(6000, 10000)
        soundState.nextChaseAt = now + 4000
        soundState.nextScreamAt = now + randomBetween(18000, 30000)
        soundState.nextUVPainAt = now + randomBetween(2500, 4500)
        return
    end

    local wokeUp = soundState.stateName == "sleeping" and stateName == "alert"
    if wokeUp then
        playVolatileSound(zombie, "wake")
        soundState.suppressSeeUntil = now + 6500
    elseif soundState.stateName ~= stateName and stateName == "returning" then
        playVolatileSound(zombie, "returnhome")
    end

    if soundState.stateName ~= "uv" and stateName == "uv" then
        playVolatileSound(zombie, "uvpain")
        soundState.nextUVPainAt = now + randomBetween(3000, 5200)
    end

    if hasTarget and soundState.hasTarget ~= true and not wokeUp
        and (soundState.suppressSeeUntil or 0) <= now then
        playVolatileSound(zombie, "seeplayer")
        soundState.nextChaseAt = now + 4000
        soundState.nextScreamAt = now + randomBetween(18000, 30000)
    end

    if attacking and soundState.attacking ~= true then
        playVolatileSound(zombie, "attack")
    end
    if attackOutcome == "success" and soundState.attackOutcome ~= "success" then
        playVolatileSound(zombie, "bite")
    end

    if stateName == "sleeping" then
        if now >= (soundState.nextBreathAt or 0) then
            playVolatileSound(zombie, "sleepbreath")
            soundState.nextBreathAt = now + randomBetween(12000, 18000)
        end
    elseif stateName == "uv" then
        if now >= (soundState.nextUVPainAt or 0) then
            playVolatileSound(zombie, "uvpain")
            soundState.nextUVPainAt = now + randomBetween(3500, 6000)
        end
    elseif hasTarget then
        if now >= (soundState.nextChaseAt or 0) then
            playVolatileSound(zombie, "chase")
            soundState.nextChaseAt = now + 4000
        end
        if now >= (soundState.nextScreamAt or 0) then
            playVolatileSound(zombie, "scream")
            soundState.nextScreamAt = now + randomBetween(22000, 36000)
        end
    elseif stateName ~= "returning" and stateName ~= "ambient-escaping"
        and stateName ~= "uv-pending" then
        if now >= (soundState.nextSearchAt or 0) then
            playVolatileSound(zombie, "search")
            soundState.nextSearchAt = now + randomBetween(10000, 16000)
        end
    end

    soundState.stateName = stateName
    soundState.hasTarget = hasTarget
    soundState.attacking = attacking
    soundState.attackOutcome = attackOutcome
end

local function findNightStartWarningSquare(player, minimumRadius, maximumRadius)
    local cell = getCell and getCell() or nil
    if not cell or not player then return nil end

    local fallbackSquare = nil
    for _ = 1, 20 do
        local angle = math.rad(randomBetween(0, 359))
        local radius = randomBetween(minimumRadius, maximumRadius)
        local x = math.floor(player:getX() + math.cos(angle) * radius)
        local y = math.floor(player:getY() + math.sin(angle) * radius)
        local z = math.floor(player:getZ())
        local square = cell:getGridSquare(x, y, z)
        if square then
            fallbackSquare = fallbackSquare or square
            local outside = false
            pcall(function() outside = square:isOutside() == true end)
            if outside then return square end
        end
    end
    return fallbackSquare
end

local function playNightStartWarning()
    local config = BP.GetConfig()
    if config.MidnightWarningEnabled == false then return end

    local gameTime = getGameTime and getGameTime() or nil
    local player = getPlayer and getPlayer() or nil
    if not gameTime or not player then return end

    local nightStartHour = math.max(
        0,
        math.min(23, math.floor(tonumber(config.NightStartHour) or 0))
    )
    if gameTime:getHour() ~= nightStartHour then return end

    local worldAgeHours = tonumber(gameTime:getWorldAgeHours()) or 0
    local warningCycle = math.floor((worldAgeHours - nightStartHour) / 24)
    local warningKey = tostring(warningCycle) .. ":" .. tostring(nightStartHour)
    local playerData = player:getModData()
    if tostring(playerData.BP_LastNightStartWarningKey or "") == warningKey then return end
    local volume = getVolatileSoundVolume()
    if volume <= 0 then
        -- Mark this night as handled; otherwise a muted warning retries every
        -- tick for the whole start hour.
        playerData.BP_LastNightStartWarningKey = warningKey
        return
    end

    local minimumRadius = math.max(1, math.floor(tonumber(config.MidnightWarningMinRadius) or 22))
    local maximumRadius = math.max(minimumRadius, math.floor(tonumber(config.MidnightWarningMaxRadius) or 30))
    local square = findNightStartWarningSquare(player, minimumRadius, maximumRadius)
    if not square then return end

    local soundName = string.format("BPV_Scream_%02d", randomBetween(1, 2))
    local played = false
    pcall(function()
        local sound = getSoundManager():PlayWorldSoundImpl(
            soundName, false,
            square:getX(), square:getY(), square:getZ(),
            1, 45, 1, false
        )
        played = setWorldVolatileSoundVolume(sound, volume)
    end)
    if not played then return end

    playerData.BP_LastNightStartWarningKey = warningKey
    local now = soundNow()
    activeVoiceSlots[#activeVoiceSlots + 1] = {
        x = square:getX(),
        y = square:getY(),
        z = square:getZ(),
        untilTime = now + 6500,
    }
    nextAmbientVoiceAt = now + 6500
    nextIdleAmbientVoiceAt = now + getAmbientScreamDelayMilliseconds()
end

normalizeInteger = function(value)
    local number = tonumber(value)
    if not number or number ~= number or number == math.huge or number == -math.huge then
        return nil
    end
    return math.floor(number)
end

local function snapshotScopeKey(record)
    local scopeId = record and record.scopeId or nil
    if scopeId == nil then return "default" end
    return tostring(scopeId)
end

local function rememberScopeMembership(scopeKey, id, generation)
    if not id or generation == nil then return end
    snapshotMembership[scopeKey] = snapshotMembership[scopeKey] or {}
    snapshotMembership[scopeKey][id] = generation
end

local function removeMembershipEverywhere(id)
    for _, members in pairs(snapshotMembership) do members[id] = nil end
end

local function belongsToAnotherScope(id, excludedScope, generation)
    for scopeKey, members in pairs(snapshotMembership) do
        if scopeKey ~= excludedScope and members[id] == generation then return true end
    end
    return false
end

local function normalizeRevision(record, sequenceOverride)
    if not record then return nil end
    local id = normalizeInteger(record.id or record.zombieId)
    local generation = normalizeInteger(record.generation)
    local stateVersion = normalizeInteger(record.stateVersion)
    local snapshotSequence = normalizeInteger(sequenceOverride)
        or normalizeInteger(record.snapshotSequence)
    if not BP.IsValidNetworkZombieID(id) or not generation or generation < 0
        or not stateVersion or stateVersion < 0
        or not snapshotSequence or snapshotSequence < 0 then
        return nil
    end
    return {
        id = id,
        generation = generation,
        stateVersion = stateVersion,
        snapshotSequence = snapshotSequence,
    }
end

local function normalizeEntry(entry, sequenceOverride)
    local revision = normalizeRevision(entry, sequenceOverride)
    if not revision then return nil end
    return {
        id = revision.id,
        generation = revision.generation,
        stateVersion = revision.stateVersion,
        snapshotSequence = revision.snapshotSequence,
        loreVersion = tonumber(entry.loreVersion)
            or math.floor(tonumber(BP.GetConfig().VolatileLoreVersion) or 1),
        state = tostring(entry.state or "night"),
        nestId = entry.nestId,
        slot = tonumber(entry.slot),
        ambient = entry.ambient == true,
        uvUntil = tonumber(entry.uvUntil),
        targetX = tonumber(entry.targetX),
        targetY = tonumber(entry.targetY),
        targetZ = tonumber(entry.targetZ),
        moveX = tonumber(entry.moveX),
        moveY = tonumber(entry.moveY),
        moveZ = tonumber(entry.moveZ),
    }
end

local function cloneKnownEntries()
    local clone = {}
    for id, entry in pairs(knownVolatiles) do clone[id] = entry end
    return clone
end

local function cloneHighWater()
    local clone = {}
    for id, water in pairs(revisionHighWater) do
        clone[id] = {
            generation = water.generation,
            stateVersion = water.stateVersion,
            snapshotSequence = water.snapshotSequence,
            removed = water.removed == true,
            softPruned = water.softPruned == true,
        }
    end
    return clone
end

local function clearRevisionCaches(id)
    appliedStates[id] = nil
    nextLoreChecks[id] = nil
    nextUVDrives[id] = nil
    nextMoveDrives[id] = nil
    uvPredictions[id] = nil
    localUVGuards[id] = nil
end

local function clearIdentityCaches(id)
    clearRevisionCaches(id)
    ownerStates[id] = nil
    reportCooldowns[id] = nil
    uvDistances[id] = nil
end

local function pruneInactiveSnapshotScopes()
    if not isClient() then return end

    local activeScopes = {}
    local localPlayers = getLocalPlayers()
    if #localPlayers == 0 then return end
    for _, player in ipairs(localPlayers) do
        local ok, id = pcall(function() return player:getOnlineID() end)
        if ok and id ~= nil then activeScopes[tostring(id)] = true end
    end

    local candidateIdentities = {}
    local inactiveScopes = {}
    for scopeKey, members in pairs(snapshotMembership) do
        if scopeKey ~= "default" and not activeScopes[scopeKey] then
            for id, generation in pairs(members) do
                candidateIdentities[id] = candidateIdentities[id] or {}
                candidateIdentities[id][generation] = true
            end
            inactiveScopes[#inactiveScopes + 1] = scopeKey
        end
    end
    for _, scopeKey in ipairs(inactiveScopes) do
        snapshotMembership[scopeKey] = nil
        lastSnapshotSequences[scopeKey] = nil
    end

    for id, generations in pairs(candidateIdentities) do
        local current = knownVolatiles[id]
        local generation = current and current.generation or nil
        if generation ~= nil and generations[generation]
            and not belongsToAnotherScope(id, nil, generation) then
            knownVolatiles[id] = nil
            local water = revisionHighWater[id]
            if water and water.removed ~= true then water.softPruned = true end
            clearIdentityCaches(id)
        end
    end
end

local function pruneAuxiliaryCaches()
    local function prune(cache)
        for id in pairs(cache) do
            if not knownVolatiles[id] then cache[id] = nil end
        end
    end
    prune(appliedStates)
    prune(ownerStates)
    prune(nextLoreChecks)
    prune(nextUVDrives)
    prune(nextMoveDrives)
    prune(reportCooldowns)
    prune(uvDistances)
    prune(uvPredictions)
    prune(localUVGuards)
end

local function newReducerContext(atomic)
    return {
        known = atomic and cloneKnownEntries() or knownVolatiles,
        highWater = atomic and cloneHighWater() or revisionHighWater,
        resetRevision = {},
        resetIdentity = {},
        changedIds = {},
    }
end

local function queueReducerReset(context, id, identityChanged)
    context.resetRevision[id] = true
    if identityChanged then context.resetIdentity[id] = true end
end

-- All snapshot entries, deltas and removals pass through this reducer. The
-- server sequence orders packets globally; generation is only an opaque zombie
-- identity token. B42 reuses MP online IDs when zombies leave and re-enter
-- relevance, so comparing generation numerically can permanently reject an old
-- persistent Volatile that reloads into an ID most recently used by a newer
-- zombie. A removal wins equal revisions and remains a tombstone until a
-- different generation arrives in a strictly newer server sequence.
local function reduceAuthoritativeRecord(kind, rawRecord, context, sequenceOverride)
    local revision = normalizeRevision(rawRecord, sequenceOverride)
    if not revision then return nil, false end

    local id = revision.id
    local currentWater = context.highWater[id]
    if currentWater then
        if revision.snapshotSequence < currentWater.snapshotSequence then
            return nil, false
        end

        local sameGeneration = revision.generation == currentWater.generation
        if revision.snapshotSequence == currentWater.snapshotSequence
            and not sameGeneration then
            -- One server sequence cannot legitimately identify two different
            -- zombies with the same online ID. Wait for a newer authoritative
            -- snapshot instead of making arrival order decide the identity.
            return nil, false
        end
        if sameGeneration and revision.stateVersion < currentWater.stateVersion then
            return nil, false
        end
    end

    if kind == "state" then
        local entry = normalizeEntry(rawRecord, sequenceOverride)
        if not entry then return nil, false end

        if currentWater and currentWater.removed == true
            and entry.generation == currentWater.generation then
            return nil, false
        end

        local sameRevision = currentWater
            and entry.generation == currentWater.generation
            and entry.stateVersion == currentWater.stateVersion
        if sameRevision then
            if currentWater.softPruned == true
                and entry.snapshotSequence <= currentWater.snapshotSequence then
                return nil, false
            end
            if entry.snapshotSequence > currentWater.snapshotSequence then
                currentWater.snapshotSequence = entry.snapshotSequence
            end
            currentWater.softPruned = false
            if context.known[id] then return context.known[id], false end

            context.known[id] = entry
            context.changedIds[id] = true
            queueReducerReset(context, id, false)
            return entry, true
        end

        local identityChanged = not currentWater
            or entry.generation ~= currentWater.generation
        context.highWater[id] = {
            generation = entry.generation,
            stateVersion = entry.stateVersion,
            snapshotSequence = entry.snapshotSequence,
            removed = false,
            softPruned = false,
        }
        context.known[id] = entry
        context.changedIds[id] = true
        queueReducerReset(context, id, identityChanged)
        return entry, true
    end

    if kind ~= "remove" then return nil, false end

    local sameRevision = currentWater
        and revision.generation == currentWater.generation
        and revision.stateVersion == currentWater.stateVersion
    if sameRevision and currentWater.removed == true then
        if revision.snapshotSequence > currentWater.snapshotSequence then
            currentWater.snapshotSequence = revision.snapshotSequence
        end
        return nil, false
    end

    context.highWater[id] = {
        generation = revision.generation,
        stateVersion = revision.stateVersion,
        snapshotSequence = revision.snapshotSequence,
        removed = true,
        softPruned = false,
    }
    context.known[id] = nil
    context.changedIds[id] = true
    queueReducerReset(context, id, true)
    return nil, true
end

local function softPruneSnapshotRecord(id, current, context, snapshotSequence)
    local water = context.highWater[id]
    if not water or water.snapshotSequence > snapshotSequence then return false end

    context.highWater[id] = {
        generation = current.generation,
        stateVersion = current.stateVersion,
        snapshotSequence = snapshotSequence,
        removed = false,
        softPruned = true,
    }
    context.known[id] = nil
    context.changedIds[id] = true
    queueReducerReset(context, id, true)
    return true
end

local function flushReducerResets(context)
    for id in pairs(context.resetRevision) do
        if not context.resetIdentity[id] then clearRevisionCaches(id) end
    end
    for id in pairs(context.resetIdentity) do clearIdentityCaches(id) end
end

local function reduceDelta(kind, rawRecord)
    local context = newReducerContext(false)
    local entry, changed = reduceAuthoritativeRecord(kind, rawRecord, context)
    flushReducerResets(context)
    pruneAuxiliaryCaches()
    return entry, changed, context
end

local function reduceSnapshot(args)
    local snapshotSequence = normalizeInteger(args and args.snapshotSequence)
    local scopeKey = snapshotScopeKey(args)
    local lastSnapshotSequence = tonumber(lastSnapshotSequences[scopeKey]) or -1
    if not snapshotSequence or snapshotSequence < 0
        or snapshotSequence <= lastSnapshotSequence then
        return nil
    end

    -- Validate the complete batch before touching live state.
    local entries = {}
    local batchIdentities = {}
    for _, rawEntry in ipairs(args.entries or {}) do
        local entry = normalizeEntry(rawEntry, snapshotSequence)
        if not entry then return nil end
        local batchGeneration = batchIdentities[entry.id]
        if batchGeneration ~= nil and batchGeneration ~= entry.generation then
            -- A single authoritative snapshot cannot contain two identities
            -- for the same online ID. Reject the complete batch atomically.
            return nil
        end
        batchIdentities[entry.id] = entry.generation
        entries[#entries + 1] = entry
    end

    local context = newReducerContext(true)
    local seen = {}
    for _, entry in ipairs(entries) do
        local accepted = reduceAuthoritativeRecord(
            "state", entry, context, snapshotSequence
        )
        if accepted and accepted.generation == entry.generation then
            seen[entry.id] = entry.generation
        end
    end

    -- Absence is authoritative only for records that were not changed after
    -- this snapshot. Newer deltas survive an older snapshot arriving late.
    local previousMembership = snapshotMembership[scopeKey] or {}
    for id, previousGeneration in pairs(previousMembership) do
        local current = context.known[id]
        if current and current.generation == previousGeneration
            and seen[id] ~= previousGeneration
            and not belongsToAnotherScope(id, scopeKey, previousGeneration) then
            local water = context.highWater[id]
            if water and water.snapshotSequence <= snapshotSequence then
                softPruneSnapshotRecord(id, current, context, snapshotSequence)
            end
        end
    end

    knownVolatiles = context.known
    revisionHighWater = context.highWater
    snapshotMembership[scopeKey] = seen
    lastSnapshotSequences[scopeKey] = snapshotSequence
    flushReducerResets(context)
    pruneAuxiliaryCaches()
    return context
end

local function copyAuthoritativeEntryToZombie(zombie, entry)
    if BP.IsCorpseDragProxy(zombie) then return false end
    local data = zombie:getModData()
    -- Clear a provisional appearance guard before marking the object. Remote
    -- owners return before applying AI state, so merely deleting the marker
    -- here could otherwise strand setUseless(true) on this client.
    releaseAppearanceCandidate(zombie, true)
    data.BP_AppearanceCandidateHeld = nil
    data.BP_IsVolatile = true
    data.BP_Generation = entry.generation
    data.BP_StateVersion = entry.stateVersion
    data.BP_State = entry.state
    data.BP_NestId = entry.nestId
    data.BP_Slot = entry.slot
    data.BP_Ambient = entry.ambient
    data.BP_UVUntil = entry.uvUntil
    data.BP_UVTargetX = entry.targetX
    data.BP_UVTargetY = entry.targetY
    data.BP_UVTargetZ = entry.targetZ
    data.BP_MoveTargetX = entry.moveX
    data.BP_MoveTargetY = entry.moveY
    data.BP_MoveTargetZ = entry.moveZ
    -- The handshake is single-use. Replicated ModData now carries the durable
    -- generation proof and a pooled object cannot inherit this confirmation.
    appearanceIdentityChecks[zombie] = nil
end

local function effectiveEntryForOwner(entry, worldNow)
    if not BP.IsUVMechanicsEnabled() then
        uvPredictions[entry.id] = nil
        return entry
    end
    local prediction = entry and uvPredictions[entry.id] or nil
    if not prediction then return entry end
    if prediction.generation ~= entry.generation
        or prediction.baseVersion ~= entry.stateVersion
        or prediction.expiresAt <= (tonumber(worldNow) or BP.GetWorldHour()) then
        uvPredictions[entry.id] = nil
        return entry
    end
    return prediction.entry
end

local function entryWithoutDisabledUV(entry)
    if not entry or BP.IsUVMechanicsEnabled() then return entry end
    local stateName = tostring(entry.state or "night")
    local hasUVState = stateName == "uv" or stateName == "uv-pending"
        or entry.uvUntil ~= nil or entry.targetX ~= nil
        or entry.targetY ~= nil or entry.targetZ ~= nil
    if not hasUVState then return entry end

    local copy = {}
    for key, value in pairs(entry) do copy[key] = value end
    if hasUVState then
        if BP.IsNight() then
            copy.state = copy.ambient == true and "ambient-night" or "night"
        else
            copy.state = copy.ambient == true and "ambient-escaping" or "returning"
        end
    end
    copy.uvUntil = nil
    copy.targetX = nil
    copy.targetY = nil
    copy.targetZ = nil
    copy.localPrediction = nil
    return copy
end

local directedMovementStates = {
    ["returning"] = true,
    ["day-escaping"] = true,
    ["ambient-escaping"] = true,
}

local function classifyVolatileState(stateName)
    if stateName == "sleeping" then return "sleeping" end
    if stateName == "uv-pending" then return "uv-pending" end
    if stateName == "uv" then return "uv" end
    if directedMovementStates[stateName] then return "directed" end
    return "awake"
end

local function getObjectRuntimeState(zombie, entry)
    local runtime = objectRuntimeStates[zombie]
    local identityChanged = not runtime
        or runtime.id ~= entry.id
        or runtime.generation ~= entry.generation
    if identityChanged then
        runtime = {
            id = entry.id,
            generation = entry.generation,
        }
        objectRuntimeStates[zombie] = runtime
        -- IsoZombie objects may be pooled. Do not let voice cooldowns, directed
        -- attack state or UV source coordinates leak into the new identity.
        volatileSoundStates[zombie] = nil
        directedAttackCancels[zombie] = nil
        uvNegativeSearches[zombie] = nil
        uvPositiveSources[zombie] = nil
    end
    return runtime, identityChanged
end

local function refreshAnimationSpeedIfNeeded(zombie, runtime, now, force)
    local configChanged = runtime.animationConfigEpoch ~= runtimeConfigEpoch
    if force == true or configChanged
        or now >= (tonumber(runtime.nextAnimationSpeedAt) or 0) then
        BP.ApplyVolatileAnimationSpeed(zombie)
        runtime.animationConfigEpoch = runtimeConfigEpoch
        runtime.nextAnimationSpeedAt = now + ANIMATION_SPEED_WATCHDOG_MS
    end
end

local function muteSleepingEmitterIfNeeded(zombie, runtime, now, force)
    if force ~= true and now < (tonumber(runtime.nextSleepingMuteAt) or 0) then
        return
    end
    pcall(function()
        local emitter = zombie:getEmitter()
        if emitter then emitter:stopAll() end
    end)
    runtime.nextSleepingMuteAt = now + SLEEPING_MUTE_WATCHDOG_MS
end

local function commitObjectRuntimeState(
    runtime, authoritativeEntry, effectiveEntry, canControl, stateName, mode
)
    runtime.id = authoritativeEntry.id
    runtime.generation = authoritativeEntry.generation
    runtime.stateVersion = authoritativeEntry.stateVersion
    runtime.stateConfigEpoch = runtimeConfigEpoch
    runtime.canControl = canControl == true
    runtime.stateName = stateName
    runtime.mode = mode
    runtime.predictionEntry = effectiveEntry.localPrediction == true
        and effectiveEntry or nil
    runtime.moveX = effectiveEntry.moveX
    runtime.moveY = effectiveEntry.moveY
    runtime.moveZ = effectiveEntry.moveZ
    runtime.uvUntil = effectiveEntry.uvUntil
    runtime.targetX = effectiveEntry.targetX
    runtime.targetY = effectiveEntry.targetY
    runtime.targetZ = effectiveEntry.targetZ
end

local function enterSleepingState(zombie, resetState)
    directedAttackCancels[zombie] = nil
    pcall(function() zombie:clearAggroList() end)
    pcall(function() zombie:setTarget(nil) end)
    BP.CancelZombieAttack(zombie, resetState)
    pcall(function() zombie:setPath2(nil) end)
    pcall(function() zombie:getPathFindBehavior2():reset() end)
    pcall(function() zombie:setVariable("ReactUV", false) end)
    pcall(function() zombie:setVariable("BP_Sleeping", true) end)
    pcall(function() zombie:setUseless(true) end)
end

local function guardSleepingState(zombie)
    -- Keep the safety-critical attack suppression on every owner update. The
    -- expensive path, emitter and animation setup belongs to state entry.
    pcall(function() zombie:clearAggroList() end)
    pcall(function() zombie:setTarget(nil) end)
    BP.CancelZombieAttack(zombie, false)
    -- CancelZombieAttack may rebuild the default state when a stale attack was
    -- already active. Reassert only the two safety flags after that boundary.
    pcall(function() zombie:setVariable("BP_Sleeping", true) end)
    pcall(function() zombie:setUseless(true) end)
end

local function enterUVPendingState(zombie)
    pcall(function() zombie:setPath2(nil) end)
    pcall(function() zombie:getPathFindBehavior2():reset() end)
    pcall(function() zombie:setVariable("BP_Sleeping", false) end)
end

local function restoreAwakeFlags(zombie)
    directedAttackCancels[zombie] = nil
    BP.ClearZombieAttackOverrides(zombie)
    pcall(function() zombie:setVariable("BP_Sleeping", false) end)
    pcall(function() zombie:setVariable("ReactUV", false) end)
    pcall(function() zombie:setUseless(false) end)
end

local function applyAwakeState(zombie, forceLore)
    restoreAwakeFlags(zombie)
    BP.ApplyVolatileLore(zombie, true, forceLore)
end

local function awakeLoreHasDrifted(zombie)
    local speedType
    local walkType = ""
    local ok = pcall(function()
        speedType = tonumber(zombie:getSpeedType())
        walkType = tostring(zombie:getWalkType() or ""):lower()
    end)
    local expectedSpeed = math.floor(
        tonumber(BP.GetConfig().VolatileSpeedType) or 1
    )
    local walkMatches = expectedSpeed ~= 1
        or walkType:sub(1, 6) == "sprint"
    return not ok or speedType ~= expectedSpeed or not walkMatches
end

local function reassertAwakeLoreIfNeeded(zombie, entry, nowOverride)
    local id = entry and entry.id or nil
    if not zombie or id == nil then return end
    local now = tonumber(nowOverride) or soundNow()
    if now < (tonumber(nextLoreChecks[id]) or 0) then return end
    nextLoreChecks[id] = now + AWAKE_LORE_WATCHDOG_MS

    if awakeLoreHasDrifted(zombie) then
        -- B42 may reset a locally-owned zombie to a vanilla walker during a
        -- native state transition. Rebuild lore only after drift is observed;
        -- doing this every update would continuously destroy chase paths.
        BP.ApplyVolatileLore(zombie, true, true)
    end
end

local function enterDirectedState(zombie, entry, applyLore, forceLore)
    pcall(function() zombie:setVariable("BP_Sleeping", false) end)
    pcall(function() zombie:setVariable("ReactUV", false) end)
    -- Direct PathFindState remains mobile while useless, matching UV escape.
    pcall(function() zombie:setUseless(true) end)
    if entry.moveX and entry.moveY then
        pcall(function() zombie:faceLocation(entry.moveX + 0.5, entry.moveY + 0.5) end)
    elseif applyLore then
        BP.ApplyVolatileLore(zombie, true, forceLore)
    end
end

local function guardDirectedSafety(zombie, resetAttack)
    pcall(function() zombie:clearAggroList() end)
    pcall(function() zombie:setTarget(nil) end)

    -- Do not rebuild the zombie state machine every owner update. A stale
    -- attack flag at 06:00 otherwise replaces the directed path continuously.
    local attacking = BP.IsZombieAttacking(zombie)
    local shouldCancel = resetAttack == true
        or (attacking and directedAttackCancels[zombie] ~= true)
    if shouldCancel then
        BP.CancelZombieAttack(zombie, true, true)
        directedAttackCancels[zombie] = true
    elseif not attacking then
        directedAttackCancels[zombie] = nil
    end

    -- Useless is the barrier that prevents vanilla targeting from replacing
    -- the directed PathFindState, so it remains a per-update safety write.
    pcall(function() zombie:setUseless(true) end)
end

local function driveDirectedMovement(zombie, entry, worldNow)
    local now = tonumber(worldNow) or BP.GetWorldHour()
    if entry.moveX and entry.moveY and (nextMoveDrives[entry.id] or 0) <= now then
        BP.DriveVolatileMovement(
            zombie, entry.moveX, entry.moveY, entry.moveZ or zombie:getZ()
        )
        nextMoveDrives[entry.id] = now + 0.003
    end
end

local function guardDirectedMovement(zombie, entry, resetAttack, worldNow)
    guardDirectedSafety(zombie, resetAttack)
    driveDirectedMovement(zombie, entry, worldNow)
end

local function hasConfirmedVolatileIdentity(zombie, entry)
    if not zombie or BP.IsCorpseDragProxy(zombie) or not entry then return false end

    local data = zombie:getModData()
    local existingGeneration = data and normalizeInteger(data.BP_Generation) or nil
    local entryGeneration = normalizeInteger(entry.generation)
    if data and data.BP_IsVolatile == true
        and existingGeneration ~= nil
        and existingGeneration == entryGeneration then
        return true
    end

    -- Appearance alone is not identity: vanilla can rarely choose BP_Volatile
    -- from the global outfit pool while an unrelated Volatile record still owns
    -- the same reused online ID. Only the correlated server probe may bridge
    -- the initial ModData replication gap.
    local entryId = normalizeInteger(entry.id)
    local onlineId = normalizeInteger(BP.GetZombieID(zombie))
    local check = appearanceIdentityChecks[zombie]
    if not check or check.serverStatus ~= "volatile"
        or normalizeInteger(check.onlineId) ~= entryId
        or onlineId ~= entryId
        or normalizeInteger(check.confirmedGeneration) ~= entryGeneration
        or normalizeInteger(check.confirmedProbeToken)
            ~= normalizeInteger(check.probeToken)
        or (tonumber(check.confirmedUntil) or 0)
            < (appearanceClockMs > 0 and appearanceClockMs or soundNow()) then
        return false
    end

    local fingerprint = BP.GetZombieAppearanceFingerprint
        and BP.GetZombieAppearanceFingerprint(zombie) or tostring(zombie)
    return check.fingerprint == fingerprint
end

local function applyEntryToZombie(zombie, entry)
    if not zombie or zombie:isDead() or BP.IsCorpseDragProxy(zombie)
        or not entry then return end
    if CLIENT_MODE and not authoritativeConfigReady then return end
    -- Online IDs are reused as zombies cross MP relevance boundaries. Do not
    -- let an old ID-keyed record promote an unrelated ordinary zombie. Strong
    -- appearance starts a bounded probe; only replicated generation or the
    -- correlated authoritative reply may attach the record.
    if CLIENT_MODE and not hasConfirmedVolatileIdentity(zombie, entry) then
        return nil
    end

    local authoritativeEntry = entry
    entry = entryWithoutDisabledUV(entry)
    local runtime, identityChanged = getObjectRuntimeState(zombie, authoritativeEntry)
    if BP.IsUVMechanicsEnabled() and uvTrackedVolatiles[zombie] ~= true then
        uvTrackedVolatiles[zombie] = true
    elseif not BP.IsUVMechanicsEnabled() then
        uvTrackedVolatiles[zombie] = nil
    end

    local now = appearanceClockMs > 0 and appearanceClockMs or soundNow()
    local canControl = BP.CanControlZombie(zombie)
    local worldNow = nil
    if canControl then
        if uvPredictions[entry.id] then worldNow = BP.GetWorldHour() end
        entry = effectiveEntryForOwner(entry, worldNow)
    else
        uvPredictions[entry.id] = nil
    end

    local stateName = tostring(entry.state or "night")
    local mode = classifyVolatileState(stateName)
    local ownerGained = canControl and runtime.canControl ~= true
    local stateTransition = runtime.stateName ~= stateName
    local predictionEntry = entry.localPrediction == true and entry or nil
    local predictionChanged = runtime.predictionEntry ~= predictionEntry
    local configChanged = runtime.stateConfigEpoch ~= runtimeConfigEpoch
    local stateEntryNeeded = identityChanged or ownerGained or stateTransition
        or predictionChanged or configChanged
    local revisionChanged = identityChanged
        or runtime.stateVersion ~= authoritativeEntry.stateVersion
    local routeChanged = identityChanged
        or runtime.moveX ~= entry.moveX
        or runtime.moveY ~= entry.moveY
        or runtime.moveZ ~= entry.moveZ
    local uvRouteChanged = identityChanged
        or runtime.uvUntil ~= entry.uvUntil
        or runtime.targetX ~= entry.targetX
        or runtime.targetY ~= entry.targetY
        or runtime.targetZ ~= entry.targetZ

    -- Prediction is process-local behavior only. Never publish uv-pending in
    -- zombie modData, which may migrate with MP ownership. In singleplayer the
    -- entry was read from this same modData table, so copying it back is waste.
    if CLIENT_MODE and (identityChanged or revisionChanged or ownerGained
        or runtime.copiedGeneration ~= authoritativeEntry.generation
        or runtime.copiedStateVersion ~= authoritativeEntry.stateVersion) then
        copyAuthoritativeEntryToZombie(zombie, entry)
        runtime.copiedGeneration = authoritativeEntry.generation
        runtime.copiedStateVersion = authoritativeEntry.stateVersion
    end

    -- Animation variables may be replaced by native state transitions. Refresh
    -- immediately at identity/state/owner/config boundaries, then use a cheap
    -- one-second watchdog instead of two protected setters every update.
    refreshAnimationSpeedIfNeeded(
        zombie,
        runtime,
        now,
        identityChanged or stateTransition or ownerGained or configChanged
    )

    -- Audio is client-local, so mute sleeping zombies even when this client is
    -- not their MP simulation owner. Entry is immediate; the watchdog prevents
    -- vanilla idle vocals from returning without doing stopAll() every frame.
    if stateName == "sleeping" then
        muteSleepingEmitterIfNeeded(
            zombie, runtime, now,
            identityChanged or stateTransition or ownerGained or configChanged
        )
    else
        runtime.nextSleepingMuteAt = nil
    end

    -- Remote zombies are simulated by another client. Applying paths or stats to
    -- them causes MP tug-of-war, so wait until this client becomes their owner.
    if not canControl then
        ownerStates[entry.id] = false
        commitObjectRuntimeState(
            runtime, authoritativeEntry, entry, false, stateName, mode
        )
        return entry
    end

    ownerStates[entry.id] = true

    if not BP.IsUVMechanicsEnabled() then
        local data = zombie:getModData()
        if data and (data.BP_State == "uv" or data.BP_UVUntil ~= nil
            or data.BP_UVTargetX ~= nil or data.BP_UVTargetY ~= nil
            or data.BP_UVTargetZ ~= nil or data.BP_UVLightX ~= nil
            or data.BP_UVLightY ~= nil or data.BP_UVLightZ ~= nil) then
            BP.ReleaseUVFlee(zombie)
            data.BP_LastUVReport = nil
            data.BP_State = entry.state
        end
    end

    if mode == "sleeping" then
        if stateEntryNeeded then
            BP.ApplyVolatileLore(zombie, false, ownerGained or configChanged)
            enterSleepingState(zombie, true)
        else
            guardSleepingState(zombie)
        end
    elseif mode == "uv-pending" then
        if stateEntryNeeded then enterUVPendingState(zombie) end
        -- UV attack suppression is deliberately not throttled.
        BP.HoldUVGuard(zombie, false)
    elseif mode == "uv" then
        -- Reapplying doSprinter on every OnZombieUpdate replaces PathFindState
        -- and destroys the UV route. Ownership changes still need full lore;
        -- the animation watchdog keeps the scale ready for the next track.
        if ownerGained or configChanged then
            BP.ApplyVolatileLore(zombie, true, true)
        end
        worldNow = worldNow or BP.GetWorldHour()
        if uvRouteChanged or stateEntryNeeded then nextUVDrives[entry.id] = nil end
        if (entry.uvUntil or 0) > worldNow then
            -- Every owner receiving an authoritative UV lease gets a short
            -- post-expiry grace, including immediately after ownership handoff.
            localUVGuards[entry.id] = math.max(
                localUVGuards[entry.id] or 0,
                (entry.uvUntil or worldNow) + 0.01
            )
            -- Path refresh is throttled, attack suppression is not. Vanilla AI
            -- may reacquire a player between two path refreshes otherwise.
            BP.HoldUVGuard(zombie, false)
            runtime.uvGuarded = true
            if (nextUVDrives[entry.id] or 0) <= worldNow then
                BP.DriveUVFlee(zombie)
                nextUVDrives[entry.id] = worldNow + 0.003
            end
        elseif (localUVGuards[entry.id] or 0) > worldNow then
            -- A recently observed lamp remains a local safety barrier while an
            -- authoritative refresh is travelling over the network.
            BP.HoldUVGuard(zombie, false)
            runtime.uvGuarded = true
        else
            -- The authoritative record can remain "uv" for a callback after
            -- lease expiry. Release once at that edge, not on every update.
            if runtime.uvGuarded ~= false or stateEntryNeeded then
                BP.ReleaseUVFlee(zombie)
                applyAwakeState(zombie, ownerGained or configChanged)
                runtime.uvGuarded = false
            else
                reassertAwakeLoreIfNeeded(zombie, entry, now)
            end
        end
    elseif mode == "directed" then
        if routeChanged or stateEntryNeeded then nextMoveDrives[entry.id] = nil end
        if stateEntryNeeded or routeChanged then
            enterDirectedState(
                zombie, entry, true, ownerGained or configChanged
            )
        end
        -- Target/attack suppression stays per-update; only invariant setup and
        -- route construction moved to transition/deadline boundaries.
        worldNow = worldNow or BP.GetWorldHour()
        guardDirectedMovement(
            zombie, entry, stateEntryNeeded or routeChanged, worldNow
        )
    else
        if stateEntryNeeded then
            applyAwakeState(zombie, ownerGained or configChanged)
        end
        if (stateEntryNeeded or routeChanged)
            and not zombie:getTarget() and entry.moveX and entry.moveY then
            BP.PathZombieTo(
                zombie, entry.moveX, entry.moveY, entry.moveZ or zombie:getZ()
            )
        end
        reassertAwakeLoreIfNeeded(zombie, entry, now)
    end

    if mode ~= "uv" then runtime.uvGuarded = nil end
    appliedStates[entry.id] = authoritativeEntry.stateVersion
    commitObjectRuntimeState(
        runtime, authoritativeEntry, entry, true, stateName, mode
    )
    return entry
end

local function applyUntrackedVolatileFallback(zombie, data)
    if not isClient() or not zombie or not data then return end
    if BP.IsCorpseDragProxy(zombie) then return end
    if not BP.IsUVMechanicsEnabled() and BP.CanControlZombie(zombie)
        and (data.BP_State == "uv" or data.BP_UVUntil ~= nil
            or data.BP_UVTargetX ~= nil or data.BP_UVTargetY ~= nil
            or data.BP_UVTargetZ ~= nil or data.BP_UVLightX ~= nil
            or data.BP_UVLightY ~= nil or data.BP_UVLightZ ~= nil) then
        BP.ReleaseUVFlee(zombie)
        data.BP_LastUVReport = nil
        data.BP_State = BP.IsNight()
            and (data.BP_Ambient == true and "ambient-night" or "night")
            or (data.BP_Ambient == true and "ambient-escaping" or "returning")
    end

    -- The fallback below can change native AI without advancing an
    -- authoritative generation/stateVersion. Force the same Java object to
    -- receive a full state entry when its server record becomes relevant again.
    objectRuntimeStates[zombie] = nil

    local now = soundNow()
    if now >= nextMissingStateSyncAt then
        nextMissingStateSyncAt = now + MISSING_STATE_SYNC_INTERVAL_MS
        sendAuthoritativeSyncRequest()
    end
    if not BP.CanControlZombie(zombie) then return end
    local state = tostring(data.BP_State or "")
    local atNight = BP.IsNight()

    -- Missing-record retries may stay throttled, but attack barriers cannot.
    -- Ownership or native AI can change between two 500 ms repair passes.
    if state == "sleeping" then
        guardSleepingState(zombie)
    elseif state == "uv" then
        BP.HoldUVGuard(zombie, false)
    elseif state == "returning" or state == "day-escaping"
        or state == "ambient-escaping" then
        guardDirectedSafety(zombie, false)
    end

    if now < (tonumber(untrackedVolatileChecks[zombie]) or 0) then return end
    untrackedVolatileChecks[zombie] = now + UNTRACKED_VOLATILE_RETRY_MS

    local stateToken = state
    if state == "" or state == "night" or state == "ambient-night"
        or state == "alert" then
        stateToken = state .. (atNight and ":night" or ":day")
    end
    local stateChanged = untrackedVolatileStates[zombie] ~= stateToken
    untrackedVolatileStates[zombie] = stateToken
    if state == "sleeping" then
        if stateChanged then
            BP.ApplyVolatileLore(zombie, false, true)
            enterSleepingState(zombie, true)
        else
            BP.ApplyVolatileAnimationSpeed(zombie)
        end
        -- This fallback already runs at 500 ms, matching the normal sleeping
        -- emitter watchdog without adding another per-frame path.
        pcall(function()
            local emitter = zombie:getEmitter()
            if emitter then emitter:stopAll() end
        end)
        return
    end
    if state == "uv" then
        BP.DriveUVFlee(zombie)
        return
    end
    if state == "returning" or state == "day-escaping"
        or state == "ambient-escaping" then
        local moveX = tonumber(data.BP_MoveTargetX)
        local moveY = tonumber(data.BP_MoveTargetY)
        local fallbackEntry = {
            id = BP.GetZombieID(zombie) or zombie,
            moveX = moveX,
            moveY = moveY,
            moveZ = tonumber(data.BP_MoveTargetZ) or zombie:getZ(),
        }
        if stateChanged then
            nextMoveDrives[fallbackEntry.id] = nil
            enterDirectedState(zombie, fallbackEntry, true, true)
            guardDirectedSafety(zombie, true)
        end
        driveDirectedMovement(zombie, fallbackEntry, BP.GetWorldHour())
        return
    end
    if atNight then
        if stateChanged then
            applyAwakeState(zombie, true)
        elseif awakeLoreHasDrifted(zombie) then
            -- A missing initial MP record must not turn the Volatile into a
            -- vanilla walker, but rebuilding doSprinter every retry destroys
            -- chase and attack states. Repair only observable lore drift.
            BP.ApplyVolatileLore(zombie, true, true)
        end
        return
    end

    -- If daylight state data arrives before its authoritative record, safety
    -- wins: do not let the temporary vanilla owner keep attacking players.
    pcall(function() zombie:clearAggroList() end)
    pcall(function() zombie:setTarget(nil) end)
    local attacking = BP.IsZombieAttacking(zombie)
    if stateChanged or (attacking and directedAttackCancels[zombie] ~= true) then
        BP.CancelZombieAttack(zombie, true, true)
        directedAttackCancels[zombie] = true
    elseif not attacking then
        directedAttackCancels[zombie] = nil
    end
    pcall(function() zombie:setUseless(true) end)
    BP.ApplyVolatileAnimationSpeed(zombie)
end


local function markKnownZombie(zombie)
    if not zombie then return end
    local data = zombie:getModData()
    local mayBeCorpseProxy = data and (data.BP_IsVolatile == true
        or data.BP_AppearanceCandidateHeld == true)
    if mayBeCorpseProxy and BP.IsCorpseDragProxy(zombie) then
        return
    end
    if CLIENT_MODE and data and data.BP_AppearanceCandidateHeld == true
        and not BP.CanControlZombie(zombie) then
        -- Ownership handoff ends this client's authority to suppress AI.
        releaseAppearanceCandidate(zombie)
        appearanceIdentityChecks[zombie] = nil
    end
    local markedVolatile = data and data.BP_IsVolatile == true
    local id, entry

    if CLIENT_MODE and not markedVolatile then
        -- The normal-zombie path dominates this global event. Resolve its
        -- protected Java network ID at most once per second; authoritative
        -- packet handlers still apply a newly-arrived record immediately.
        local now = appearanceClockMs > 0 and appearanceClockMs or soundNow()
        if now < (tonumber(ordinaryIdentityChecks[zombie]) or 0) then return end

        id = BP.GetZombieID(zombie)
        entry = id and knownVolatiles[id] or nil
        if not entry then
            probeVolatileAppearance(zombie)
            if id == nil then
                -- A newborn replicated zombie can expose no network ID for a
                -- few callbacks. Do not hide the first usable ID behind the
                -- ordinary-zombie cooldown; retry on the next update.
                ordinaryIdentityChecks[zombie] = nil
            else
                local appearance = appearanceIdentityChecks[zombie]
                ordinaryIdentityChecks[zombie] = math.min(
                    now + ORDINARY_IDENTITY_RECHECK_MS,
                    tonumber(appearance and appearance.nextAt)
                        or (now + ORDINARY_IDENTITY_RECHECK_MS)
                )
            end
            return
        end
    else
        if not CLIENT_MODE and not markedVolatile then return end
        id = BP.GetZombieID(zombie)
        entry = id and knownVolatiles[id] or nil
    end

    ordinaryIdentityChecks[zombie] = nil
    local volatile = entry ~= nil or markedVolatile
    if not volatile then return end

    if entry then
        local effectiveEntry = applyEntryToZombie(zombie, entry)
        if not effectiveEntry then
            -- A current ID can briefly point at a different pooled zombie.
            -- Leave the ordinary object untouched, but let strong appearance
            -- request a token-correlated server decision before retrying at
            -- the normal-zombie cadence.
            if CLIENT_MODE then
                local now = appearanceClockMs > 0 and appearanceClockMs or soundNow()
                local appearance = appearanceIdentityChecks[zombie]
                if appearance and appearance.serverStatus == "volatile"
                    and normalizeInteger(appearance.confirmedGeneration)
                        ~= normalizeInteger(entry.generation) then
                    appearanceIdentityChecks[zombie] = nil
                end
                probeVolatileAppearance(zombie)
                appearance = appearanceIdentityChecks[zombie]
                ordinaryIdentityChecks[zombie] = math.min(
                    now + ORDINARY_IDENTITY_RECHECK_MS,
                    tonumber(appearance and appearance.nextAt)
                        or (now + ORDINARY_IDENTITY_RECHECK_MS)
                )
            end
            return
        end
        if BP.IsUVMechanicsEnabled() then uvTrackedVolatiles[zombie] = true end
        updateVolatileSounds(
            zombie,
            effectiveEntry,
            appearanceClockMs > 0 and appearanceClockMs or nil
        )
    elseif CLIENT_MODE and volatile then
        -- The server record can arrive one update after the MP zombie object,
        -- or vice versa. Keep the local owner safe/Volatile-like and request a
        -- rate-limited repair snapshot instead of running vanilla zombie AI.
        if BP.IsUVMechanicsEnabled() then uvTrackedVolatiles[zombie] = true end
        applyUntrackedVolatileFallback(zombie, data)
    elseif not CLIENT_MODE and markedVolatile then
        -- Singleplayer uses the same owner-side applier without network packets.
        if BP.IsUVMechanicsEnabled() then uvTrackedVolatiles[zombie] = true end
        local localEntry = {
            id = id or -1,
            generation = tonumber(data.BP_Generation) or 0,
            stateVersion = tonumber(data.BP_StateVersion) or 0,
            loreVersion = math.floor(tonumber(BP.GetConfig().VolatileLoreVersion) or 1),
            state = tostring(data.BP_State or "night"),
            nestId = data.BP_NestId,
            slot = tonumber(data.BP_Slot),
            ambient = data.BP_Ambient == true,
            uvUntil = tonumber(data.BP_UVUntil),
            targetX = tonumber(data.BP_UVTargetX),
            targetY = tonumber(data.BP_UVTargetY),
            targetZ = tonumber(data.BP_UVTargetZ),
            moveX = tonumber(data.BP_MoveTargetX),
            moveY = tonumber(data.BP_MoveTargetY),
            moveZ = tonumber(data.BP_MoveTargetZ),
        }
        applyEntryToZombie(zombie, localEntry)
        updateVolatileSounds(
            zombie,
            localEntry,
            appearanceClockMs > 0 and appearanceClockMs or nil
        )
    end
end

local function isKnownVolatile(zombie)
    if BP.IsCorpseDragProxy(zombie) then return false end
    if BP.IsVolatile(zombie) then return true end
    local id = BP.GetZombieID(zombie)
    return id ~= nil and knownVolatiles[id] ~= nil
end

-- Stay Away From Windows-style collision handling, restricted to genuine
-- Blackout Predators. In MP only the owning client may mutate the window, and
-- the server-synced generation must match the loaded zombie identity. This
-- keeps appearance-only impostors and ordinary zombies completely untouched.
local function confirmedLocalVolatileForWindow(zombie)
    if not zombie or zombie:isDead() or not BP.CanControlZombie(zombie) then
        return false, nil
    end
    if not BP.IsVolatile(zombie) then return false, nil end
    if not isClient() then return true, nil end
    if not authoritativeConfigReady then return false, nil end

    local id = BP.GetZombieID(zombie)
    local entry = id and knownVolatiles[id] or nil
    local data = zombie:getModData()
    if not entry or not data or data.BP_IsVolatile ~= true then
        return false, nil
    end

    local entryGeneration = normalizeInteger(entry.generation)
    local dataGeneration = normalizeInteger(data.BP_Generation)
    return entryGeneration ~= nil and dataGeneration ~= nil
        and entryGeneration == dataGeneration, entry
end

local function onVolatileObjectCollide(character, collider)
    if not collider or not instanceof(collider, "IsoWindow") then return end
    if not character or not instanceof(character, "IsoZombie") then return end

    local confirmed, entry = confirmedLocalVolatileForWindow(character)
    if not confirmed then return end

    local data = character:getModData()
    local state = tostring((entry and entry.state) or (data and data.BP_State) or "")
    if state == "sleeping" then return end

    -- smashWindow() already sends the B42 MP packet, updates the sprite and
    -- invalidates pathfinding. collider:update() from the source mod is not
    -- needed and can cause duplicate work.
    pcall(function()
        if collider:isBarricaded() or collider:IsOpen() or collider:isSmashed()
            or collider:isDestroyed() or collider:isInvincible() then
            return
        end
        collider:smashWindow()
    end)
end

local function sendUVReport(zombie, light, reporter)
    if not BP.IsUVMechanicsEnabled() or BP.IsCorpseDragProxy(zombie) then return end
    local id = BP.GetZombieID(zombie)
    if not id then return end

    local entry = knownVolatiles[id]
    if isClient() and (not authoritativeConfigReady or not entry) then return end
    local canControl = BP.CanControlZombie(zombie)
    if not isClient() and not canControl then return end

    local now = BP.GetWorldHour()

    local lightX = light:getX()
    local lightY = light:getY()
    local lightZ = light:getZ()

    if isClient() then
        local predictionExpiresAt = now + math.max(
            0.01,
            BP.GetUVFleeDurationHours()
        )
        -- The owning client must stop the attack immediately; waiting for the
        -- server round-trip lets fast Volatiles reach and bite through the UV.
        -- The prediction is deliberately directionless: only the server may
        -- choose and version an escape target.
        if canControl then
            localUVGuards[id] = predictionExpiresAt
        end

        -- Any nearby client may report a server-validated UV source. Only the
        -- simulation owner may mutate the zombie while that report is in flight.
        if canControl and entry.state ~= "uv" then
            local prediction = uvPredictions[id]
            local reusablePrediction = prediction
                and prediction.generation == entry.generation
                and prediction.baseVersion == entry.stateVersion
                and prediction.entry
                and prediction.entry.localPrediction == true
            if reusablePrediction then
                -- Repeated UV scans extend the same prediction identity. This
                -- keeps the guard immediate without re-entering uv-pending and
                -- resetting the native path on every scan.
                prediction.expiresAt = math.max(
                    tonumber(prediction.expiresAt) or 0,
                    predictionExpiresAt
                )
            else
                local predictedEntry = {}
                for key, value in pairs(entry) do predictedEntry[key] = value end
                predictedEntry.state = "uv-pending"
                predictedEntry.uvUntil = nil
                predictedEntry.targetX = nil
                predictedEntry.targetY = nil
                predictedEntry.targetZ = nil
                predictedEntry.localPrediction = true
                uvPredictions[id] = {
                    generation = entry.generation,
                    baseVersion = entry.stateVersion,
                    -- Keep the local shield through realistic MP latency. A
                    -- server state with a newer version replaces this at once.
                    expiresAt = predictionExpiresAt,
                    entry = predictedEntry,
                }
                nextUVDrives[id] = nil
                nextMoveDrives[id] = nil
            end
            applyEntryToZombie(zombie, entry)
        elseif canControl then
            BP.HoldUVGuard(zombie, false)
        end

        -- Protection above is immediate and independent from packet throttling.
        if (reportCooldowns[id] or 0) > now then return end
        reportCooldowns[id] = now
            + (tonumber(BP.GetConfig().UVReportCooldownHours) or 0.004)
        local args = {
            id = id,
            generation = entry.generation,
            lightX = lightX,
            lightY = lightY,
            lightZ = lightZ,
        }
        if reporter then
            sendClientCommand(reporter, BP.Module, "UVReport", args)
        else
            sendClientCommand(BP.Module, "UVReport", args)
        end
    else
        if (reportCooldowns[id] or 0) > now then
            local data = zombie:getModData()
            if (tonumber(data.BP_UVUntil) or 0) > now then
                BP.HoldUVGuard(zombie, false)
            end
            return
        end
        local reacted = BP.FleeFromUV(zombie, lightX, lightY, lightZ)
        if reacted then
            reportCooldowns[id] = now
                + (tonumber(BP.GetConfig().UVReportCooldownHours) or 0.004)
        else
            -- The lamp was valid even if path construction failed. Fail closed:
            -- freeze attack permission and let the server-side lease retry.
            local data = zombie:getModData()
            data.BP_State = "uv"
            data.BP_UVUntil = now + BP.GetUVFleeDurationHours()
            data.BP_UVLightX = lightX
            data.BP_UVLightY = lightY
            data.BP_UVLightZ = lightZ
            data.BP_UVTargetX = nil
            data.BP_UVTargetY = nil
            data.BP_UVTargetZ = nil
            data.BP_NextUVDriveAt = nil
            BP.HoldUVGuard(zombie, false)
            reportCooldowns[id] = now
                + (tonumber(BP.GetConfig().UVReportCooldownHours) or 0.004)
        end
    end
end

local function processUVLightResult(zombie, reporter, light)
    if not BP.IsUVMechanicsEnabled() then return end
    local id = BP.GetZombieID(zombie)
    if light then
        local distance = BP.Distance2D(
            zombie:getX(), zombie:getY(), light:getX(), light:getY()
        )
        local previous = id and uvDistances[id] or nil
        -- If pathfinding turns toward the UV center, invalidate the cooldown
        -- and immediately choose another escape angle.
        if id and previous and distance < previous - 0.1 then
            reportCooldowns[id] = nil
        end
        if id then uvDistances[id] = distance end
        sendUVReport(zombie, light, reporter)
    elseif id then
        uvDistances[id] = nil
    end
end

local function clearUVDetectionCaches(clearTracked)
    uvNegativeSearches = setmetatable({}, { __mode = "k" })
    uvPositiveSources = setmetatable({}, { __mode = "k" })
    uvScanCursor = 1
    if clearTracked == true then
        uvTrackedVolatiles = setmetatable({}, { __mode = "k" })
    end
end

local function clearAllUVRuntimeCaches()
    nextUVDrives = {}
    reportCooldowns = {}
    uvDistances = {}
    uvPredictions = {}
    localUVGuards = {}
    clearUVDetectionCaches(true)
end

local function resetUVSearchState()
    clearUVDetectionCaches(true)
end

local function rememberUVSource(zombie, light)
    if not BP.IsUVLightSource(light) then return nil end
    uvPositiveSources[zombie] = {
        x = math.floor(tonumber(light:getX()) or 0),
        y = math.floor(tonumber(light:getY()) or 0),
        z = math.floor(tonumber(light:getZ()) or 0),
    }
    return light
end

local function getCachedUVSource(zombie, cell, radius)
    local source = uvPositiveSources[zombie]
    if not source then return nil end

    local zombieX = math.floor(zombie:getX())
    local zombieY = math.floor(zombie:getY())
    local zombieZ = math.floor(zombie:getZ())
    local dx = zombieX - source.x
    local dy = zombieY - source.y
    if source.z ~= zombieZ or dx * dx + dy * dy > radius * radius then
        uvPositiveSources[zombie] = nil
        return nil
    end

    -- Cache only coordinates. Resolve the live object from its exact square on
    -- every pass so switching off, replacing or unloading a lamp is immediate.
    local light = cell:getLightSourceAt(source.x, source.y, source.z)
    if not BP.IsUVLightSource(light) then
        uvPositiveSources[zombie] = nil
        return nil
    end
    return light
end

local function hasValidNegativeUVSearch(
    zombie, now, radius, playerDistance
)
    local cached = uvNegativeSearches[zombie]
    if not cached then return false end

    playerDistance = tonumber(playerDistance) or math.huge
    if playerDistance <= radius + UV_NEGATIVE_CACHE_DANGER_PADDING
        or (playerDistance <= UV_NEGATIVE_CACHE_NEAR_DISTANCE
            and now - (tonumber(cached.createdAt) or 0)
                >= UV_NEGATIVE_CACHE_NEAR_MS)
        or now >= (tonumber(cached.expiresAt) or 0)
        or cached.radius ~= radius
        or cached.x ~= math.floor(zombie:getX())
        or cached.y ~= math.floor(zombie:getY())
        or cached.z ~= math.floor(zombie:getZ()) then
        uvNegativeSearches[zombie] = nil
        return false
    end
    return true
end

local function rememberNegativeUVSearch(zombie, now, radius, playerDistance)
    -- Close to a player the old per-pass behavior is retained. This avoids
    -- adding even a short cache window while a Volatile is in biting range.
    if playerDistance <= radius + UV_NEGATIVE_CACHE_DANGER_PADDING then
        uvNegativeSearches[zombie] = nil
        return
    end

    local ttl = playerDistance <= UV_NEGATIVE_CACHE_NEAR_DISTANCE
        and UV_NEGATIVE_CACHE_NEAR_MS or UV_NEGATIVE_CACHE_FAR_MS
    uvNegativeSearches[zombie] = {
        x = math.floor(zombie:getX()),
        y = math.floor(zombie:getY()),
        z = math.floor(zombie:getZ()),
        radius = radius,
        createdAt = now,
        expiresAt = now + ttl,
    }
end

local function scanUVLights()
    if not BP.IsUVMechanicsEnabled() or not BP.IsNight() then return end

    local cell = getCell()
    local localPlayers = getLocalPlayers()
    if #localPlayers == 0 or not cell then return end

    local now = soundNow()
    local maxDistance = tonumber(BP.GetConfig().SyncRadius) or 120
    local radius = math.max(1, math.ceil(BP.GetUVGuardRadius()))
    local pending = {}
    for zombie in pairs(uvTrackedVolatiles) do
        local square = zombie and zombie:getCurrentSquare() or nil
        if not zombie or zombie:isDead() or not square or not isKnownVolatile(zombie) then
            uvTrackedVolatiles[zombie] = nil
            uvNegativeSearches[zombie] = nil
            uvPositiveSources[zombie] = nil
        else
            local reporter, playerDistance = nearestLocalPlayer(
                zombie, maxDistance, localPlayers
            )
            if reporter then
                -- Prefer the server-selected source while a UV reaction is
                -- active. The local coordinate cache covers MP handoff/join
                -- cases where those modData coordinates are not replicated.
                local light = BP.GetStoredUVLight(zombie, 0)
                if not light then
                    light = getCachedUVSource(zombie, cell, radius)
                end

                if light then
                    rememberUVSource(zombie, light)
                    uvNegativeSearches[zombie] = nil
                    processUVLightResult(zombie, reporter, light)
                elseif not hasValidNegativeUVSearch(
                    zombie, now, radius, playerDistance
                ) then
                    pending[#pending + 1] = {
                        zombie = zombie,
                        reporter = reporter,
                        playerDistance = playerDistance,
                    }
                end
            end
        end
    end

    local pendingCount = #pending
    if pendingCount == 0 then
        uvScanCursor = 1
        return
    end
    if uvScanCursor < 1 or uvScanCursor > pendingCount then uvScanCursor = 1 end

    local budget = math.min(pendingCount, UV_LIGHT_SEARCH_BUDGET)
    for offset = 0, budget - 1 do
        local index = ((uvScanCursor + offset - 1) % pendingCount) + 1
        local candidate = pending[index]
        local light = BP.FindUVLightNear(candidate.zombie)
        if light then
            rememberUVSource(candidate.zombie, light)
        else
            rememberNegativeUVSearch(
                candidate.zombie,
                now,
                radius,
                candidate.playerDistance
            )
        end
        if light then uvNegativeSearches[candidate.zombie] = nil end
        processUVLightResult(candidate.zombie, candidate.reporter, light)
    end
    uvScanCursor = ((uvScanCursor + budget - 1) % pendingCount) + 1
end

local function onTick()
    local now = soundNow()
    appearanceClockMs = now
    if now >= nextScopePruneAt then
        nextScopePruneAt = now + SNAPSHOT_PRUNE_INTERVAL_MS
        pruneInactiveSnapshotScopes()
    end
    if isClient() and not authoritativeConfigReady then return end
    playNightStartWarning()
    if not BP.IsUVMechanicsEnabled() then
        tickCounter = 0
        return
    end
    tickCounter = tickCounter + 1
    if tickCounter < (tonumber(BP.GetConfig().UVScanTicks) or 5) then return end
    tickCounter = 0
    scanUVLights()
end

local function applyLoadedEntry(entry)
    if isClient() and not authoritativeConfigReady then return end
    local zombie = BP.FindZombieByOnlineID(entry.id)
    if zombie then applyEntryToZombie(zombie, entry) end
end

local function buildLoadedZombieIndex()
    local index = {}
    local cell = getCell and getCell() or nil
    local zombies = cell and cell:getZombieList() or nil
    if not zombies then return index end
    for listIndex = 0, zombies:size() - 1 do
        local zombie = zombies:get(listIndex)
        local id = BP.GetZombieID(zombie)
        if id ~= nil then index[id] = zombie end
    end
    return index
end

local function applyChangedEntries(context)
    if not context or (isClient() and not authoritativeConfigReady) then return end
    local loadedById = buildLoadedZombieIndex()
    for id in pairs(context.changedIds) do
        local entry = knownVolatiles[id]
        local zombie = entry and loadedById[id] or nil
        if zombie then applyEntryToZombie(zombie, entry) end
    end
end

local function forceApplyKnownEntries()
    for id in pairs(knownVolatiles) do
        clearRevisionCaches(id)
        ownerStates[id] = nil
    end
    local loadedById = buildLoadedZombieIndex()
    for id, entry in pairs(knownVolatiles) do
        local zombie = loadedById[id]
        if zombie then applyEntryToZombie(zombie, entry) end
    end
end

local function acceptVolatileSound(args)
    local id = normalizeInteger(args.id or args.zombieId)
    local generation = normalizeInteger(args.generation)
    local snapshotSequence = normalizeInteger(args.snapshotSequence)
    local water = id and revisionHighWater[id] or nil
    if not id or not generation or not snapshotSequence or not water
        or water.removed == true or water.softPruned == true
        or generation ~= water.generation
        or snapshotSequence < water.snapshotSequence then
        return nil
    end
    return id
end

local function clearAuthoritativelyOrdinaryZombie(zombie, id)
    if not zombie or BP.IsCorpseDragProxy(zombie) then return end

    -- The token/id/fingerprint handshake makes this an authoritative negative
    -- identity decision. Release first so a stale BP_IsVolatile marker cannot
    -- preserve the provisional setUseless(true) guard.
    releaseAppearanceCandidate(zombie, true)
    local data = zombie:getModData()
    local wasContaminated = data and data.BP_IsVolatile == true
    if data then
        data.BP_AppearanceCandidateHeld = nil
        data.BP_IsVolatile = nil
        data.BP_Generation = nil
        data.BP_StateVersion = nil
        data.BP_State = nil
        data.BP_NestId = nil
        data.BP_Slot = nil
        data.BP_Ambient = nil
        data.BP_AppearanceApplied = nil
        data.BP_UVUntil = nil
        data.BP_UVTargetX = nil
        data.BP_UVTargetY = nil
        data.BP_UVTargetZ = nil
        data.BP_UVLightX = nil
        data.BP_UVLightY = nil
        data.BP_UVLightZ = nil
        data.BP_NextUVDriveAt = nil
        data.BP_MoveTargetX = nil
        data.BP_MoveTargetY = nil
        data.BP_MoveTargetZ = nil
    end

    BP.ClearZombieAttackOverrides(zombie)
    pcall(function() zombie:setVariable("BP_Sleeping", false) end)
    pcall(function() zombie:setVariable("ReactUV", false) end)
    pcall(function() zombie:setVariable("isVolatile", false) end)
    pcall(function() zombie:setVariable("BPVolatileSpeed", 1) end)

    -- A stale entry may already have applied Volatile lore before the negative
    -- result arrived. Restore ordinary sandbox stats only on the local owner and
    -- preserve combat damage already taken.
    if wasContaminated and BP.CanControlZombie(zombie) then
        local health = nil
        pcall(function() health = zombie:getHealth() end)
        pcall(function() zombie:DoZombieStats() end)
        if tonumber(health) and tonumber(health) > 0 then
            pcall(function() zombie:setHealth(health) end)
        end
    end

    objectRuntimeStates[zombie] = nil
    ordinaryIdentityChecks[zombie] = nil
    untrackedVolatileChecks[zombie] = nil
    untrackedVolatileStates[zombie] = nil
    directedAttackCancels[zombie] = nil
    uvTrackedVolatiles[zombie] = nil
    uvNegativeSearches[zombie] = nil
    uvPositiveSources[zombie] = nil
    volatileSoundStates[zombie] = nil
    if id ~= nil then clearIdentityCaches(id) end
end

local function handleVolatileProbeResult(args)
    local id = normalizeInteger(args.id or args.zombieId)
    local probeToken = normalizeInteger(args.probeToken)
    local generation = normalizeInteger(args.generation)
    local result = tostring(args.result or args.status or "")
    if not BP.IsValidNetworkZombieID(id) or not probeToken
        or (result ~= "ordinary" and result ~= "repairing"
            and result ~= "volatile" and result ~= "corpse")
        or (result == "volatile" and (not generation or generation < 1)) then
        return
    end

    local zombie = BP.FindZombieByOnlineID(id)
    local check = zombie and appearanceIdentityChecks[zombie] or nil
    if not zombie or zombie:isDead() or not check
        or check.appearanceConfirmed ~= true
        or normalizeInteger(check.probeToken) ~= probeToken
        or normalizeInteger(check.onlineId) ~= id
        or normalizeInteger(BP.GetZombieID(zombie)) ~= id then
        return
    end

    if BP.IsCorpseDragProxy(zombie) then
        -- Mixed-version or late packets may classify the fresh drag-proxy ID
        -- as ordinary/volatile. The engine flag wins: discard only Lua caches
        -- and leave native grapple state plus copied corpse metadata untouched.
        appearanceIdentityChecks[zombie] = nil
        ordinaryIdentityChecks[zombie] = nil
        uvTrackedVolatiles[zombie] = nil
        uvNegativeSearches[zombie] = nil
        uvPositiveSources[zombie] = nil
        return
    end

    local now = appearanceClockMs > 0 and appearanceClockMs or soundNow()
    if now >= (tonumber(check.holdUntil) or 0) then
        -- A reply received after the bounded authority window must not attach
        -- to an ID/object that may already have been recycled.
        releaseAppearanceCandidate(zombie)
        check.appearanceConfirmed = nil
        check.complete = true
        check.timedOut = true
        check.nextAt = now + APPEARANCE_FINGERPRINT_RECHECK_MS
        return
    end

    local fingerprint = BP.GetZombieAppearanceFingerprint
        and BP.GetZombieAppearanceFingerprint(zombie) or tostring(zombie)
    if check.fingerprint ~= fingerprint then return end

    if result == "corpse" then return end

    if result == "volatile" then
        check.serverStatus = "volatile"
        check.confirmedGeneration = generation
        check.confirmedProbeToken = probeToken
        check.confirmedUntil = now + APPEARANCE_CANDIDATE_HOLD_MS
        check.appearanceConfirmed = nil
        check.complete = true
        check.nextAt = now + APPEARANCE_FINGERPRINT_RECHECK_MS
        releaseAppearanceCandidate(zombie, true)

        local entry = knownVolatiles[id]
        if entry and normalizeInteger(entry.generation) == generation then
            local effectiveEntry = applyEntryToZombie(zombie, entry)
            if effectiveEntry then
                if BP.IsUVMechanicsEnabled() then
                    uvTrackedVolatiles[zombie] = true
                end
                updateVolatileSounds(
                    zombie,
                    effectiveEntry,
                    appearanceClockMs > 0 and appearanceClockMs or nil
                )
            end
        end
        return
    end

    if result == "repairing" then
        -- Acknowledgement never extends the bounded client hold. The server's
        -- repair worker owns the durable replacement/recovery decision.
        check.serverStatus = "repairing"
        return
    end

    clearAuthoritativelyOrdinaryZombie(zombie, id)
    check.appearanceConfirmed = nil
    check.complete = true
    check.serverStatus = "ordinary"
    check.nextAt = now + APPEARANCE_FINGERPRINT_RECHECK_MS
end

local function onServerCommand(module, command, args)
    if module ~= BP.Module then return end
    args = args or {}

    if command == "ConfigSync" then
        local configSequence = normalizeInteger(args.snapshotSequence)
        if not configSequence or configSequence < lastConfigSequence then return end
        local payload = args.config or args.authoritativeConfig or args
        local accepted, changedCount = false, 0
        local uvWasEnabled = BP.IsUVMechanicsEnabled()
        if BP.ApplyAuthoritativeConfig then
            accepted, changedCount = BP.ApplyAuthoritativeConfig(payload)
        end
        if accepted then
            local firstConfig = not authoritativeConfigReady
            lastConfigSequence = configSequence
            authoritativeConfigReady = true
            if firstConfig or (tonumber(changedCount) or 0) > 0 then
                runtimeConfigEpoch = runtimeConfigEpoch + 1
                if uvWasEnabled and not BP.IsUVMechanicsEnabled() then
                    clearAllUVRuntimeCaches()
                else
                    clearUVDetectionCaches(false)
                end
                forceApplyKnownEntries()
            end
        end
    elseif command == "VolatileSync" then
        applyChangedEntries(reduceSnapshot(args))
    elseif command == "VolatileState" then
        local entry, changed = reduceDelta("state", args)
        if entry then
            rememberScopeMembership(
                snapshotScopeKey(args), entry.id, entry.generation
            )
        end
        if changed and entry then applyLoadedEntry(entry) end
    elseif command == "VolatileProbeResult" then
        handleVolatileProbeResult(args)
    elseif command == "VolatileSound" then
        local id = acceptVolatileSound(args)
        local zombie = id and BP.FindZombieByOnlineID(id) or nil
        if zombie and not BP.IsCorpseDragProxy(zombie) then
            playVolatileSound(zombie, tostring(args.kind or ""))
        end
    elseif command == "VolatileRemove" then
        local _, changed = reduceDelta("remove", args)
        if changed then
            removeMembershipEverywhere(normalizeInteger(args.id or args.zombieId))
        end
    elseif command == "UVReaction" then
        -- Backward compatibility for older server code during an in-place update.
        if not BP.IsUVMechanicsEnabled() then return end
        local entry, changed = reduceDelta("state", {
            id = args.id or args.zombieId,
            generation = args.generation,
            stateVersion = args.stateVersion,
            snapshotSequence = args.snapshotSequence,
            state = "uv",
            uvUntil = BP.GetWorldHour() + BP.GetUVFleeDurationHours(),
            targetX = args.targetX,
            targetY = args.targetY,
            targetZ = args.targetZ,
        })
        if entry then
            rememberScopeMembership(
                snapshotScopeKey(args), entry.id, entry.generation
            )
        end
        if changed and entry then applyLoadedEntry(entry) end
    end
end

local function resetProtocolState()
    knownVolatiles = {}
    revisionHighWater = {}
    lastSnapshotSequences = {}
    snapshotMembership = {}
    lastConfigSequence = -1
    authoritativeConfigReady = false
    appliedStates = {}
    ownerStates = {}
    nextLoreChecks = {}
    nextUVDrives = {}
    nextMoveDrives = {}
    reportCooldowns = {}
    uvDistances = {}
    uvPredictions = {}
    localUVGuards = {}
    resetUVSearchState()
    untrackedVolatileChecks = setmetatable({}, { __mode = "k" })
    untrackedVolatileStates = setmetatable({}, { __mode = "k" })
    appearanceIdentityChecks = setmetatable({}, { __mode = "k" })
    objectRuntimeStates = setmetatable({}, { __mode = "k" })
    ordinaryIdentityChecks = setmetatable({}, { __mode = "k" })
    runtimeConfigEpoch = runtimeConfigEpoch + 1
    volatileSoundStates = setmetatable({}, { __mode = "k" })
    activeVoiceSlots = {}
    nextAmbientVoiceAt = 0
    nextIdleAmbientVoiceAt = 0
    nextCombatVoiceAt = 0
    uvScanCursor = 1
    nextScopePruneAt = 0
    nextMissingStateSyncAt = 0
end

local function requestSync()
    if isClient() then
        resetProtocolState()
        sendAuthoritativeSyncRequest()
    end
end

local VOLATILE_KNOCKDOWN_RESIST_CHANCE = 67
local SHOVE_PRESSURE_RESET_MS = 10000
local shovePressureStates = setmetatable({}, { __mode = "k" })
local flinchProtectedStates = {
    ["sleeping"] = true,
    ["uv"] = true,
    ["returning"] = true,
    ["day-escaping"] = true,
    ["ambient-escaping"] = true,
}

local function isVolatileHitReactionProtected(zombie, data)
    if not zombie or zombie:isDead() then return true end
    if not BP.CanControlZombie(zombie) then return true end
    if data and flinchProtectedStates[tostring(data.BP_State or "")] then return true end

    local protected = false
    pcall(function()
        protected = zombie:isOnFloor()
            or zombie:getVariableBoolean("BP_Sleeping")
            or zombie:getVariableBoolean("ReactUV")
    end)
    if protected then return true end

    local actionState = ""
    pcall(function() actionState = tostring(zombie:getActionStateName() or ""):lower() end)
    return BP.IsClimbingFence(zombie) or actionState == "climbwindow"
end

local function resistVolatileKnockdown(zombie)
    -- Keep HitReaction and StaggerBack so weapon impacts and shoves remain
    -- visible. Only remove the animation branch that would put it on the floor.
    pcall(function() zombie:setKnockedDown(false) end)
end

local function forceVolatileKnockdown(zombie)
    -- A failed resistance roll must produce the configured result directly.
    -- Falling back to vanilla here would multiply our chance by the weapon's
    -- own knockdown roll and make Volatiles appear almost impossible to drop.
    pcall(function() zombie:setStaggerBack(true) end)
    pcall(function() zombie:setKnockedDown(true) end)
end

local function tryResistVolatileKnockdown(zombie, data, resistanceChance)
    if isVolatileHitReactionProtected(zombie, data) then return false end
    resistanceChance = math.max(0, math.min(100, tonumber(resistanceChance) or 0))
    if ZombRand(100) >= resistanceChance then
        forceVolatileKnockdown(zombie)
        return false
    end

    -- Run only on the client that owns this zombie. Its normal NetworkZombieAI
    -- state replication carries the resulting knockdown flag to other peers.
    resistVolatileKnockdown(zombie)
    return true
end

local function getShoveFallChance(zombie)
    local now = soundNow()
    local pressure = shovePressureStates[zombie]
    if not pressure or now - (tonumber(pressure.lastAt) or 0) > SHOVE_PRESSURE_RESET_MS then
        pressure = { count = 0, lastAt = now }
        shovePressureStates[zombie] = pressure
    end

    pressure.count = (tonumber(pressure.count) or 0) + 1
    pressure.lastAt = now

    if pressure.count < 3 then return 0 end
    if pressure.count == 3 then return 30 end
    if pressure.count == 4 then return 50 end
    return 75
end

local function onVolatileWeaponHit(attacker, weapon, zombie)
    if not zombie or not instanceof(zombie, "IsoZombie") or zombie:isDead()
        or BP.IsCorpseDragProxy(zombie) then return end

    local data = zombie:getModData()
    local id = BP.GetZombieID(zombie)
    if not ((data and data.BP_IsVolatile == true) or (id and knownVolatiles[id])) then return end
    if isVolatileHitReactionProtected(zombie, data) then return end

    local isShove = false
    pcall(function()
        isShove = weapon ~= nil and weapon:getType() == "BareHands" and not zombie:isOnFloor()
    end)

    if not isShove then
        tryResistVolatileKnockdown(zombie, data, VOLATILE_KNOCKDOWN_RESIST_CHANCE)
        return
    end

    local fallChance = getShoveFallChance(zombie)
    if ZombRand(100) < fallChance then
        shovePressureStates[zombie] = nil
        forceVolatileKnockdown(zombie)
        return
    end

    resistVolatileKnockdown(zombie)
end

local function onVolatileHit(zombie)
    if not zombie or BP.IsCorpseDragProxy(zombie) then return end
    local data = zombie:getModData()
    local id = BP.GetZombieID(zombie)
    if (data and data.BP_IsVolatile == true) or (id and knownVolatiles[id]) then
        playVolatileSound(zombie, "hit")
    end
end

local function onVolatileDead(zombie)
    if not zombie then return end
    objectRuntimeStates[zombie] = nil
    ordinaryIdentityChecks[zombie] = nil
    untrackedVolatileChecks[zombie] = nil
    untrackedVolatileStates[zombie] = nil
    appearanceIdentityChecks[zombie] = nil
    directedAttackCancels[zombie] = nil
    volatileSoundStates[zombie] = nil
    shovePressureStates[zombie] = nil
    uvTrackedVolatiles[zombie] = nil
    uvNegativeSearches[zombie] = nil
    uvPositiveSources[zombie] = nil
    if BP.IsCorpseDragProxy(zombie) then return end
    local data = zombie:getModData()
    local id = BP.GetZombieID(zombie)
    if (data and data.BP_IsVolatile == true) or (id and knownVolatiles[id]) then
        playVolatileSound(zombie, "death")
    end
end

local function debugSpawnMenuEnabled()
    if isDebugEnabled then
        local ok, enabled = pcall(isDebugEnabled)
        if ok and enabled == true then return true end
    end
    if getDebug then
        local ok, enabled = pcall(getDebug)
        if ok and enabled == true then return true end
    end
    return false
end

local function spawnTwoDebugVolatiles(player)
    if not player or player:isDead() then return end
    if isClient() then
        sendClientCommand(player, BP.Module, "DebugSpawn2", {})
    elseif BP.DebugSpawnVolatiles then
        BP.DebugSpawnVolatiles(player, 2)
    end
end

local function onFillDebugWorldContextMenu(playerIndex, context, worldObjects, test)
    if test then return true end
    if not context or not debugSpawnMenuEnabled() then return end

    local player = getSpecificPlayer(playerIndex)
    if not player or player:isDead() then return end

    if context.addDebugOption then
        context:addDebugOption("2 Tane Volatile Spawnla", player, spawnTwoDebugVolatiles)
    else
        context:addOption("2 Tane Volatile Spawnla", player, spawnTwoDebugVolatiles)
    end
end

-- Version 1.3.29 removed handheld UV conversion. Restore converted flashlights
-- from older saves once they enter a local player's inventory and refund the
-- installed purple bulb. Clearing BP_UVBulbInstalled makes this migration idempotent.
local legacyFlashlightStrength = {
    ["Base.HandTorch"] = 1.8,
    ["Base.Torch"] = 2.0,
    ["Base.GunLight"] = 1.3,
}

local function restoreLegacyUVFlashlights()
    for _, player in ipairs(getLocalPlayers()) do
        local inventory = player:getInventory()
        local items = inventory and inventory:getAllEvalRecurse(function(item)
            local data = item and item:getModData() or nil
            return data and data.BP_UVBulbInstalled == true
        end) or nil

        if items then
            for index = 0, items:size() - 1 do
                local item = items:get(index)
                local data = item and item:getModData() or nil
                if data and data.BP_UVBulbInstalled == true then
                    local wasActive = item:isActivated() == true
                    if wasActive then item:setActivated(false) end
                    item:setColorRed(tonumber(data.BP_OriginalLightR) or 1)
                    item:setColorGreen(tonumber(data.BP_OriginalLightG) or 1)
                    item:setColorBlue(tonumber(data.BP_OriginalLightB) or 1)
                    item:setCustomColor(data.BP_OriginalCustomColor == true)
                    item:setLightStrength(tonumber(data.BP_OriginalLightStrength)
                        or legacyFlashlightStrength[item:getFullType()] or 1)
                    data.BP_UVBulbInstalled = nil
                    data.BP_OriginalLightR = nil
                    data.BP_OriginalLightG = nil
                    data.BP_OriginalLightB = nil
                    data.BP_OriginalCustomColor = nil
                    data.BP_OriginalLightStrength = nil
                    if wasActive then item:setActivated(true) end
                    pcall(function() item:syncItemFields() end)

                    local returnedBulb = inventory:AddItem("Base.LightBulbPurple")
                    if returnedBulb and isClient() then
                        pcall(function()
                            sendAddItemToContainer(inventory, returnedBulb)
                        end)
                    end
                end
            end
        end
    end
end

if not BP.ClientEventsRegistered then
    Events.OnTick.Add(onTick)
    Events.OnServerCommand.Add(onServerCommand)
    Events.OnGameStart.Add(resetUVSearchState)
    Events.OnGameStart.Add(requestSync)
    Events.OnGameStart.Add(restoreLegacyUVFlashlights)
    if Events.OnContainerUpdate then
        Events.OnContainerUpdate.Add(restoreLegacyUVFlashlights)
    end
    Events.OnZombieUpdate.Add(markKnownZombie)
    if Events.OnWeaponHitXp then Events.OnWeaponHitXp.Add(onVolatileWeaponHit) end
    if Events.OnHitZombie then Events.OnHitZombie.Add(onVolatileHit) end
    Events.OnZombieDead.Add(onVolatileDead)
    Events.OnFillWorldObjectContextMenu.Add(onFillDebugWorldContextMenu)
    BP.ClientEventsRegistered = true
end

-- Register outside ClientEventsRegistered so an in-place Lua reload can add
-- this new handler without duplicating an older callback.
if Events.OnObjectCollide then
    if BP._VolatileWindowCollisionHandler and Events.OnObjectCollide.Remove then
        pcall(function()
            Events.OnObjectCollide.Remove(BP._VolatileWindowCollisionHandler)
        end)
    end
    BP._VolatileWindowCollisionHandler = onVolatileObjectCollide
    Events.OnObjectCollide.Add(BP._VolatileWindowCollisionHandler)
end
