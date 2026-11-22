BlackoutPredators = BlackoutPredators or {}

local BP = BlackoutPredators

BP.Module = "BlackoutPredators"
BP.Outfit = "BP_Volatile"
BP.VolatileItemType = "BlackoutPredators.BP_Volatile"
BP.StateKey = "BlackoutPredatorsState"
BP.VolatileWalkType = "sprint1"
-- B42.20 serializes zombie health*1000 through a signed 16-bit packet field.
-- Staying at or below 32 prevents MP overflow from becoming negative health.
BP.MaxNetworkVolatileHealth = 32.0

function BP.GetConfig()
    return BP.Config or {}
end

function BP.IsUVMechanicsEnabled()
    return BP.GetConfig().UVMechanicsEnabled ~= false
end

local function sandboxInteger(value, fallback, minimum, maximum)
    value = tonumber(value)
    if not value or value ~= value or value == math.huge or value == -math.huge then
        value = fallback
    end
    value = math.floor(tonumber(value) or 0)
    return math.max(minimum, math.min(maximum, value))
end

local function sandboxNumber(value, fallback, minimum, maximum)
    value = tonumber(value)
    if not value or value ~= value or value == math.huge or value == -math.huge then
        value = fallback
    end
    value = tonumber(value) or 0
    return math.max(minimum, math.min(maximum, value))
end

local function sandboxBoolean(value, fallback)
    if type(value) == "boolean" then return value end
    if value == nil then return fallback ~= false end
    if value == 1 or value == "1" or value == "true" then return true end
    if value == 0 or value == "0" or value == "false" then return false end
    return fallback ~= false
end

-- Read custom Sandbox settings only on the authoritative simulation. In MP the
-- resulting scalar config is sent to clients through ConfigSync.
function BP.ApplySandboxConfig()
    local sandbox = SandboxVars and SandboxVars.BlackoutPredators or nil
    if not sandbox then return false, 0 end

    local config = BP.GetConfig()
    local speedScale = sandbox.VolatileSpeedScale
    if speedScale == nil and sandbox.VolatileSpeedPercent ~= nil then
        -- One-way compatibility for saves created before the direct scale option.
        speedScale = 0.80 * sandboxInteger(sandbox.VolatileSpeedPercent, 120, 50, 200) / 100
    end

    local values = {
        UVMechanicsEnabled = sandboxBoolean(
            sandbox.UVMechanicsEnabled, config.UVMechanicsEnabled
        ),
        VolatileHealth = sandboxInteger(
            sandbox.VolatileHealth, config.VolatileHealth or 12,
            1, math.floor(BP.MaxNetworkVolatileHealth)
        ),
        VolatileSpeedScale = sandboxNumber(
            speedScale, config.VolatileSpeedScale or 0.96, 0.40, 1.60
        ),
        VolatileStrength = sandboxInteger(
            sandbox.VolatileStrength, config.VolatileStrength or 1, 1, 4
        ),
        VolatileSight = sandboxInteger(
            sandbox.VolatileSight, config.VolatileSight or 1, 1, 5
        ),
        VolatileMemory = sandboxInteger(
            sandbox.VolatileMemory, config.VolatileMemory or 1, 1, 6
        ),
        VolatileHearing = sandboxInteger(
            sandbox.VolatileHearing, config.VolatileHearing or 1, 1, 5
        ),
        NightStartHour = sandboxInteger(sandbox.NightStartHour, config.NightStartHour or 0, 0, 23),
        DayStartHour = sandboxInteger(sandbox.DayStartHour, config.DayStartHour or 6, 0, 23),
        AmbientSpawnChancePercent = sandboxInteger(
            sandbox.AmbientSpawnChancePercent, config.AmbientSpawnChancePercent or 9, 0, 100
        ),
        ActiveNestLimit = sandboxInteger(
            sandbox.ActiveNestLimit, config.ActiveNestLimit or 572, 0, 572
        ),
        NestCountMin = sandboxInteger(sandbox.NestCountMin, config.NestCountMin or 4, 1, 20),
        NestCountMax = sandboxInteger(sandbox.NestCountMax, config.NestCountMax or 7, 1, 20),
    }

    if values.NestCountMax < values.NestCountMin then
        values.NestCountMin, values.NestCountMax = values.NestCountMax, values.NestCountMin
    end

    local changed = 0
    for name, value in pairs(values) do
        if config[name] ~= value then
            config[name] = value
            changed = changed + 1
        end
    end
    return true, changed
end

function BP.Debug(message)
    if BP.GetConfig().Debug then
        print("[Blackout Predators] " .. tostring(message))
    end
end

function BP.GetWorldHour()
    local gameTime = getGameTime and getGameTime() or nil
    return gameTime and gameTime:getWorldAgeHours() or 0
end

function BP.IsNight()
    local gameTime = getGameTime and getGameTime() or nil
    if not gameTime then return false end

    local hour = gameTime:getHour()
    local config = BP.GetConfig()
    local nightStart = tonumber(config.NightStartHour) or 21
    local dayStart = tonumber(config.DayStartHour) or 6

    if nightStart > dayStart then
        return hour >= nightStart or hour < dayStart
    end

    return hour >= nightStart and hour < dayStart
end

function BP.Distance2D(x1, y1, x2, y2)
    local dx = (tonumber(x1) or 0) - (tonumber(x2) or 0)
    local dy = (tonumber(y1) or 0) - (tonumber(y2) or 0)
    return math.sqrt(dx * dx + dy * dy)
end

-- B42 stores MP zombie identities in a signed Java short. After 32767 the
-- allocator wraps through -32768..-2; only -1 means "not assigned yet".
function BP.IsValidNetworkZombieID(value)
    value = tonumber(value)
    return value ~= nil and value == math.floor(value)
        and value >= -32768 and value <= 32767 and value ~= -1
end

function BP.GetZombieID(zombie)
    if not zombie then return nil end

    local onlineId = nil
    if zombie.getOnlineID then
        local ok, id = pcall(function() return zombie:getOnlineID() end)
        if ok then onlineId = tonumber(id) end
    end

    -- B42 only assigns an online ID on MP runtimes. In singleplayer every
    -- zombie otherwise reports -1, which makes unrelated Volatiles share all
    -- ID-keyed cooldown, movement and UV state caches. IsoMovingObject:getID()
    -- is process-local and unique, so it is the correct SP identity fallback.
    -- Never use that fallback in MP: a newly spawned zombie may temporarily
    -- have onlineId -1 and its local object ID is not a valid network identity.
    if BP.IsValidNetworkZombieID(onlineId) then return onlineId end

    local clientMode = isClient and isClient() == true
    local serverMode = isServer and isServer() == true
    if not clientMode and not serverMode and zombie.getID then
        local ok, localId = pcall(function() return zombie:getID() end)
        if ok and tonumber(localId) ~= nil then return tonumber(localId) end
    end

    -- A newborn MP zombie may temporarily expose -1. It is not a usable
    -- network identity and must never enter packets or ID-keyed caches.
    return nil
end

-- IsoZombie objects are pooled and may be reused. A weak-key cache alone can
-- therefore outlive the zombie identity it audited. This cheap fingerprint
-- invalidates those caches when either the network ID or persistent outfit
-- changes, without scanning item visuals every update.
function BP.GetZombieAppearanceFingerprint(zombie)
    if not zombie then return "missing" end

    local persistentOutfitId = nil
    if zombie.getPersistentOutfitID then
        local ok, value = pcall(function()
            return zombie:getPersistentOutfitID()
        end)
        if ok then persistentOutfitId = tonumber(value) end
    end

    return tostring(BP.GetZombieID(zombie) or "pending")
        .. ":" .. tostring(persistentOutfitId or "none")
end

-- B42 does not drag an IsoDeadBody directly. It temporarily reanimates the
-- body as an IsoZombie, copies all corpse modData/visuals to that object and
-- marks it grapple-only. Never let copied BP identity turn this engine-owned
-- transport proxy into a live Volatile or an appearance-repair candidate.
function BP.IsCorpseDragProxy(zombie)
    if not zombie then return false end
    local ok, result = pcall(function()
        return zombie.isReanimatedForGrappleOnly
            and zombie:isReanimatedForGrappleOnly() == true
    end)
    return ok and result == true
end

function BP.IsVolatile(zombie)
    if not zombie or zombie:isDead() then return false end
    local data = zombie:getModData()
    if not data or data.BP_IsVolatile ~= true then return false end
    -- Keep the Java flag lookup off the overwhelmingly common ordinary-zombie
    -- path; only copied BP identity can make a drag proxy look authoritative.
    return not BP.IsCorpseDragProxy(zombie)
end

local cachedVolatilePersistentOutfitIndex = nil

local function getVolatilePersistentOutfitIndex()
    if cachedVolatilePersistentOutfitIndex ~= nil then
        return cachedVolatilePersistentOutfitIndex
    end
    if not getAllOutfits then return nil end

    local ok, names = pcall(function() return getAllOutfits(true) end)
    if not ok or not names then return nil end

    -- PersistentOutfits assigns its shared data index from this same sorted
    -- female outfit list, collapsing names case-insensitively.
    local seen = {}
    local dataIndex = 0
    local target = string.lower(BP.Outfit)
    for index = 0, names:size() - 1 do
        local name = tostring(names:get(index) or "")
        local key = string.lower(name)
        if not seen[key] then
            if key == target then
                cachedVolatilePersistentOutfitIndex = dataIndex
                return dataIndex
            end
            seen[key] = true
            dataIndex = dataIndex + 1
        end
    end
    -- OutfitManager may not be initialized yet; do not cache a failed lookup.
    return nil
end

local function getPersistentOutfitIndex(zombie)
    if not zombie or not zombie.getPersistentOutfitID then return nil end
    local raw = nil
    local ok = pcall(function()
        raw = tonumber(zombie:getPersistentOutfitID())
    end)
    if not ok or not raw or raw == 0 then return nil end

    -- bit 31 is the female flag; bits 16..30 contain the outfit data index.
    if raw < 0 then raw = raw + 2147483648 end
    return math.floor(raw / 65536)
end

local function getZombieOutfitName(zombie)
    if not zombie or not zombie.getOutfitName then return nil end
    local ok, outfitName = pcall(function() return zombie:getOutfitName() end)
    if not ok then return nil end
    return tostring(outfitName or "")
end

-- Item-visual evidence is deliberately separate from the named outfit. B42 can
-- assign the persistent outfit before its clothing visuals finish loading; the
-- server uses this stronger check during the bounded spawn-initialization grace
-- so a failed custom model never remains a normal-looking sleeping zombie.
function BP.HasVolatileVisualItem(zombie)
    if not zombie then return false end
    local visuals = nil
    pcall(function() visuals = zombie:getItemVisuals() end)
    if not visuals then return false end

    local size = 0
    local sizeOk = pcall(function() size = visuals:size() end)
    if not sizeOk then return false end
    for index = 0, size - 1 do
        local visual = visuals:get(index)
        if visual then
            local itemType = ""
            local clothingName = ""
            pcall(function() itemType = tostring(visual:getItemType() or "") end)
            pcall(function()
                clothingName = tostring(visual:getClothingItemName() or "")
            end)
            if itemType == BP.VolatileItemType or clothingName == BP.Outfit then
                return true
            end
        end
    end
    return false
end

function BP.HasCompleteVolatileAppearance(zombie)
    if BP.IsCorpseDragProxy(zombie) then return false end
    return BP.HasVolatileVisualItem(zombie)
end

-- Strong evidence may arrive in either order over MP: the exact named outfit
-- or its actual clothing visual. Unlike HasVolatileAppearance below, this does
-- not accept the legacy Generic01/persistent-ID migration signal and is safe to
-- use before attaching an authoritative Volatile record to a Java object.
function BP.HasStrongVolatileAppearance(zombie)
    if not zombie or BP.IsCorpseDragProxy(zombie) then return false end
    if getZombieOutfitName(zombie) == BP.Outfit then return true end
    return BP.HasVolatileVisualItem(zombie)
end

-- BP_Volatile must be registered as a named outfit so B42 can include the
-- model in the zombie creation packet. The same engine registry is also used
-- by a rare vanilla fallback that picks any registered outfit, which can make
-- an ordinary zombie look like a Volatile. Check both the strong evidence and
-- the old Generic01 identity left by the 1.3.53 migration.
function BP.HasVolatileAppearance(zombie)
    if not zombie or BP.IsCorpseDragProxy(zombie) then return false end
    if BP.HasStrongVolatileAppearance(zombie) then return true end

    -- Version 1.3.53 changed only the visible outfit to Generic01, leaving the
    -- networked persistent outfit ID as BP_Volatile. Keep this weak evidence
    -- solely for server recovery/audit; clients must never promote from it.
    if getZombieOutfitName(zombie) == "Generic01" then
        local persistentIndex = getPersistentOutfitIndex(zombie)
        local volatileIndex = getVolatilePersistentOutfitIndex()
        if persistentIndex ~= nil and volatileIndex ~= nil
            and persistentIndex == volatileIndex then
            return true
        end
    end
    return false
end

function BP.CanControlZombie(zombie)
    if not zombie or zombie:isDead() then return false end
    if isClient and isClient() and zombie.isRemoteZombie then
        local ok, remote = pcall(function() return zombie:isRemoteZombie() end)
        if ok and remote == true then return false end
    end
    return true
end

local attackOverrideNames = {
    "ZombieBiteDone",
    "AttackOutcome",
}

function BP.IsZombieAttacking(zombie)
    if not zombie or not zombie.isZombieAttacking then return false end
    local attacking = false
    pcall(function() attacking = zombie:isZombieAttacking() == true end)
    return attacking
end

function BP.CancelZombieAttack(zombie, resetState, reassert)
    if not zombie then return end
    local data = zombie:getModData()
    if data and data.BP_AttackOverridesApplied ~= true then
        data.BP_AttackOverridesApplied = true
        if reassert ~= true then
            pcall(function() zombie:setVariable("AttackOutcome", "interrupted") end)
            pcall(function() zombie:setVariable("ZombieBiteDone", true) end)
        end
    end

    if zombie.setTargetSeenTime then
        pcall(function() zombie:setTargetSeenTime(0) end)
    end

    local isAttacking = BP.IsZombieAttacking(zombie)
    if (resetState == true or isAttacking) and zombie.setDefaultState then
        pcall(function() zombie:setDefaultState() end)
    end

    -- setDefaultState may rebuild animation variables. During a held guard the
    -- interruption must be the final value seen by the attack state machine.
    if data and reassert == true then
        pcall(function() zombie:setVariable("AttackOutcome", "interrupted") end)
        pcall(function() zombie:setVariable("ZombieBiteDone", true) end)
    end
end

function BP.ClearZombieAttackOverrides(zombie)
    if not zombie then return end
    local data = zombie:getModData()
    if not data or data.BP_AttackOverridesApplied ~= true then return end
    for _, name in ipairs(attackOverrideNames) do
        pcall(function() zombie:clearVariable(name) end)
    end
    data.BP_AttackOverridesApplied = nil
end

-- This cache is intentionally process-local. Server modData must not prevent a
-- newly owning MP client from applying the same lore after zombie ownership moves.
-- IsoZombie Java objects are pooled, so the weak object key is not itself a
-- zombie identity; each entry also carries the authoritative generation and
-- appearance fingerprint and is invalidated when either changes.
local appliedLore = setmetatable({}, { __mode = "k" })

BP.AuthoritativeConfigSchemaVersion = 8
BP.AuthoritativeConfigFields = {
    "NightStartHour",
    "DayStartHour",
    "MidnightWarningEnabled",
    "MidnightWarningMinRadius",
    "MidnightWarningMaxRadius",
    "VolatileLoreVersion",
    "VolatileSpeedType",
    "VolatileSpeedScale",
    "VolatileStrength",
    "VolatileToughness",
    "VolatileHealth",
    "ActiveNestLimit",
    "NestCountMin",
    "NestCountMax",
    "VolatileCognition",
    "VolatileMemory",
    "VolatileSight",
    "VolatileHearing",
    "ProximityWakeRadius",
    "NoiseWakeMultiplier",
    "NoiseWakeMaxRadius",
    "PlayerSoundSourceTolerance",
    "DayAlertHours",
    "DayLostSightHours",
    "DayChaseRadius",
    "ReturnRadius",
    "RoamRadius",
    "RoamRetargetMinutes",
    "AmbientVolatilesEnabled",
    "AmbientSpawnChancePercent",
    "AmbientSpawnRollVersion",
    "AmbientDespawnDistance",
    "AmbientFleeDistance",
    "AmbientFleeRetargetMinutes",
    "AmbientDespawnGraceHours",
    "UVMechanicsEnabled",
    "UVRadius",
    "UVSafetyBuffer",
    "UVFleeDistance",
    "UVFleeHours",
    "UVReportCooldownHours",
    "UVScanTicks",
    "RespawnHours",
    "MissingRecoveryHours",
    "SyncRadius",
}

local authoritativeBooleanFields = {
    MidnightWarningEnabled = true,
    AmbientVolatilesEnabled = true,
    UVMechanicsEnabled = true,
}

local function finiteNumber(value)
    value = tonumber(value)
    if not value or value ~= value or value == math.huge or value == -math.huge then
        return nil
    end
    return value
end

local function normalizedAuthoritativeValue(name, value)
    if authoritativeBooleanFields[name] then
        if type(value) ~= "boolean" then return nil end
        return value
    end
    local number = finiteNumber(value)
    if number then
        if name == "VolatileHealth" then
            return math.max(0.1, math.min(BP.MaxNetworkVolatileHealth, number))
        end
        if name == "ActiveNestLimit" then
            return math.max(0, math.min(572, math.floor(number)))
        end
    end
    return number
end

-- Build a network-safe scalar snapshot. Debug and Nests are deliberately not
-- whitelisted: clients receive gameplay tuning, never server diagnostics or
-- nest coordinates.
function BP.SerializeAuthoritativeConfig(sourceConfig)
    local source = sourceConfig or BP.GetConfig()
    local values = {}

    for _, name in ipairs(BP.AuthoritativeConfigFields) do
        local value = normalizedAuthoritativeValue(name, source[name])
        if value ~= nil then values[name] = value end
    end

    return {
        schemaVersion = BP.AuthoritativeConfigSchemaVersion,
        values = values,
    }
end


-- Apply only the explicit scalar whitelist. Returns (accepted, changedCount).
-- Callers can reject a packet cleanly when its schema is not understood.
function BP.ApplyAuthoritativeConfig(payload, targetConfig)
    if type(payload) ~= "table"
        or finiteNumber(payload.schemaVersion) ~= BP.AuthoritativeConfigSchemaVersion
        or type(payload.values) ~= "table" then
        return false, 0
    end

    local target = targetConfig or BP.GetConfig()
    local changed = 0
    for _, name in ipairs(BP.AuthoritativeConfigFields) do
        local value = normalizedAuthoritativeValue(name, payload.values[name])
        if value ~= nil and target[name] ~= value then
            target[name] = value
            changed = changed + 1
        end
    end

    if changed > 0 then
        -- Values may change without a lore-version bump during server tuning.
        appliedLore = setmetatable({}, { __mode = "k" })
    end
    return true, changed
end

local function getLoreOption(sandbox, name)
    local option = sandbox and sandbox:getOptionByName(name) or nil
    if not option then return nil end
    local ok, configOption = pcall(function() return option:asConfigOption() end)
    if ok and configOption then return configOption end
    return option
end

local function buildVolatileLoreRequests(config)
    return {
        { name = "ZombieLore.Speed", value = math.floor(tonumber(config.VolatileSpeedType) or 1) },
        { name = "ZombieLore.Strength", value = math.floor(tonumber(config.VolatileStrength) or 1) },
        { name = "ZombieLore.Toughness", value = math.floor(tonumber(config.VolatileToughness) or 1) },
        { name = "ZombieLore.Cognition", value = math.floor(tonumber(config.VolatileCognition) or 2) },
        { name = "ZombieLore.Memory", value = math.floor(tonumber(config.VolatileMemory) or 1) },
        { name = "ZombieLore.Sight", value = math.floor(tonumber(config.VolatileSight) or 1) },
        { name = "ZombieLore.Hearing", value = math.floor(tonumber(config.VolatileHearing) or 1) },
    }
end

-- B42.20 initializes some lore fields while IsoZombie is being constructed
-- and does not reliably overwrite them later. Run a callback inside a very
-- small, protected Sandbox override window, then restore every global option.
local function withVolatileLoreOptions(config, callback)
    if type(callback) ~= "function" then return false, nil, false end
    if not getSandboxOptions then
        local ok, result = pcall(callback)
        return ok, result, false
    end

    local sandbox = getSandboxOptions()
    if not sandbox then
        local ok, result = pcall(callback)
        return ok, result, false
    end

    local requested = buildVolatileLoreRequests(config)
    local saved = {}
    for _, item in ipairs(requested) do
        local option = getLoreOption(sandbox, item.name)
        if not option or not option.getValue or not option.setValue then
            BP.Debug("Missing sandbox option " .. tostring(item.name))
            local ok, result = pcall(callback)
            return ok, result, false
        end
        saved[#saved + 1] = { option = option, value = option:getValue() }
        item.option = option
    end

    local setOk, setError = pcall(function()
        for _, item in ipairs(requested) do item.option:setValue(item.value) end
    end)
    if not setOk then
        for _, item in ipairs(saved) do
            pcall(function() item.option:setValue(item.value) end)
        end
        BP.Debug("Could not prepare Volatile lore: " .. tostring(setError))
        local ok, result = pcall(callback)
        return ok, result, false
    end

    local ok, result = pcall(callback)
    for _, item in ipairs(saved) do
        pcall(function() item.option:setValue(item.value) end)
    end
    return ok, result, true
end

-- Use the full B42.20 factory signature. Explicit live-state flags and health
-- prevent an incomplete MP spawn packet from inheriting a corpse/fake-dead
-- state, while the temporary lore window initializes immutable zombie fields.
function BP.SpawnVolatileInOutfit(x, y, z, femaleChance)
    local config = BP.GetConfig()
    local health = math.max(0.1, math.min(
        BP.MaxNetworkVolatileHealth,
        tonumber(config.VolatileHealth) or 12.0
    ))
    local ok, list = withVolatileLoreOptions(config, function()
        return addZombiesInOutfit(
            x, y, z, 1, BP.Outfit, femaleChance,
            false, false, false, false, false, false,
            health, false, 0, false, false
        )
    end)
    if not ok then
        BP.Debug("Could not create Volatile: " .. tostring(list))
        return nil
    end
    return list
end

local function applyLoreThroughEngine(zombie, config)
    local oldHealth
    pcall(function() oldHealth = zombie:getHealth() end)

    local ok, err, overridden = withVolatileLoreOptions(config, function()
        zombie:DoZombieStats()
    end)
    if tonumber(oldHealth) and tonumber(oldHealth) > 0 then
        pcall(function() zombie:setHealth(oldHealth) end)
    end

    if not ok then
        BP.Debug("Could not apply Volatile lore: " .. tostring(err))
    end
    return ok and overridden == true
end

function BP.GetVolatileAnimationSpeed()
    local scale = tonumber(BP.GetConfig().VolatileSpeedScale) or 0.96
    return math.max(0.40, math.min(1.60, scale))
end

function BP.ApplyVolatileAnimationSpeed(zombie)
    if not zombie then return false end
    local scale = BP.GetVolatileAnimationSpeed()

    -- Set the speed before isVolatile can activate the custom node. AnimNode
    -- reads this variable when the track starts. AdvancedAnimator's Java-side
    -- updateSpeedScale method is not exposed to B42 Lua, so it must not be
    -- called here; doing so floods the log even when wrapped in pcall.
    pcall(function() zombie:setVariable("BPVolatileSpeed", scale) end)
    pcall(function() zombie:setVariable("isVolatile", true) end)
    return true
end

function BP.ApplyVolatileLore(zombie, active, force)
    if not BP.CanControlZombie(zombie) then return false end

    local config = BP.GetConfig()
    local speedType = math.floor(tonumber(config.VolatileSpeedType) or 1)
    local loreVersion = math.floor(tonumber(config.VolatileLoreVersion) or 1)
    local data = zombie:getModData()
    local generation = tonumber(data and data.BP_Generation)
    local fingerprint = BP.GetZombieAppearanceFingerprint
        and BP.GetZombieAppearanceFingerprint(zombie) or tostring(zombie)
    local cached = appliedLore[zombie]

    if force == true then appliedLore[zombie] = nil end
    if force == true or type(cached) ~= "table"
        or cached.loreVersion ~= loreVersion
        or cached.generation ~= generation
        or cached.fingerprint ~= fingerprint then
        if applyLoreThroughEngine(zombie, config) then
            appliedLore[zombie] = {
                loreVersion = loreVersion,
                generation = generation,
                fingerprint = fingerprint,
            }
        end
    end
    BP.ApplyVolatileAnimationSpeed(zombie)

    if active == true then
        pcall(function() zombie:doZombieSpeed(speedType) end)
        if speedType == 1 then
            pcall(function() zombie:doSprinter() end)
            -- Keep B42's recognized Sprinter walk type. A more-specific
            -- isVolatile animation node reads the synchronized speed setting.
            pcall(function() zombie:setWalkType(BP.VolatileWalkType) end)
        end
        -- Keep the scale variable ready before the replacement animation track
        -- is selected on the next animator update.
        BP.ApplyVolatileAnimationSpeed(zombie)
    end

    return true
end

local nestLookup
local nestLookupSource
local nestLookupCount = -1

local function addNestAlias(lookup, alias, nest)
    if alias == nil then return end
    alias = tostring(alias)
    if alias == "" or lookup[alias] ~= nil then return end
    lookup[alias] = nest
end

function BP.RebuildNestLookup()
    local nests = BP.GetConfig().Nests or {}
    local lookup = {}

    -- Stable IDs always win over a legacy alias with the same spelling.
    for _, nest in ipairs(nests) do
        if nest.id ~= nil then lookup[tostring(nest.id)] = nest end
    end
    for _, nest in ipairs(nests) do
        addNestAlias(lookup, nest.legacyId, nest)
        for _, legacyId in ipairs(nest.legacyIds or {}) do
            addNestAlias(lookup, legacyId, nest)
        end
    end

    nestLookup = lookup
    nestLookupSource = nests
    nestLookupCount = #nests
    return lookup
end

function BP.InvalidateNestLookup()
    nestLookup = nil
    nestLookupSource = nil
    nestLookupCount = -1
end

local function getNestLookup()
    local nests = BP.GetConfig().Nests or {}
    if not nestLookup or nestLookupSource ~= nests or nestLookupCount ~= #nests then
        return BP.RebuildNestLookup()
    end
    return nestLookup
end

function BP.GetNestById(nestId)
    if nestId == nil then return nil end
    return getNestLookup()[tostring(nestId)]
end

-- Converts either a stable ID or a legacy alias to the canonical save key.
function BP.GetStableNestId(nestId)
    local nest = BP.GetNestById(nestId)
    return nest and tostring(nest.id) or nil
end

function BP.GetSleepSpot(nest, slot)
    if not nest then return nil end

    local spots = nest.sleepSpots or {}
    local spot = spots[tonumber(slot) or 1]
    if spot then
        return {
            x = math.floor(tonumber(spot.x) or 0),
            y = math.floor(tonumber(spot.y) or 0),
            z = math.floor(tonumber(spot.z) or 0),
        }
    end

    if nest.x and nest.y then
        return {
            x = math.floor(tonumber(nest.x) or 0),
            y = math.floor(tonumber(nest.y) or 0),
            z = math.floor(tonumber(nest.z) or 0),
        }
    end

    return nil
end

function BP.IsClimbingFence(zombie)
    if not zombie then return false end
    local actionState
    local ok = pcall(function() actionState = zombie:getActionStateName() end)
    actionState = ok and tostring(actionState or ""):lower() or ""
    return actionState == "climbfence"
end

function BP.PathZombieTo(zombie, x, y, z, forced)
    if not zombie then return false end
    -- Do not replace the native climb route while its root-motion animation is
    -- carrying the Volatile across a fence. The existing destination remains
    -- valid and pathfinding resumes after ClimbOverFenceState exits.
    if BP.IsClimbingFence(zombie) then return true end

    local ok = pcall(function()
        -- Replace any stale roam/chase path before assigning a state-owned path.
        local behavior = zombie:getPathFindBehavior2()
        if behavior then behavior:cancel() end
        zombie:setPath2(nil)
        if behavior then behavior:reset() end
        local targetX = (tonumber(x) or 0) + 0.5
        local targetY = (tonumber(y) or 0) + 0.5
        local targetZ = tonumber(z) or 0
        if forced == true and behavior then
            behavior:pathToSound(targetX, targetY, targetZ)
        end
        zombie:pathToLocationF(targetX, targetY, targetZ)
        if forced == true then
            zombie:faceLocation(targetX, targetY)
            if behavior then behavior:moveToPoint(targetX, targetY, 1.0) end
        end
    end)

    if ok then return true end

    return pcall(function()
        zombie:pathToLocation(math.floor(tonumber(x) or 0), math.floor(tonumber(y) or 0), math.floor(tonumber(z) or 0))
    end)
end

-- Directed daylight movement must use the same B42 PathFindBehavior2 sequence
-- as the proven UV escape. The general PathZombieTo helper also invokes
-- pathToSound/moveToPoint; either call may rebuild zombieWalkType as a walker.
function BP.DriveVolatileMovement(zombie, x, y, z)
    if not BP.IsVolatile(zombie) then return false end
    if not BP.CanControlZombie(zombie) then return false end
    if BP.IsClimbingFence(zombie) then return true end

    local targetX = tonumber(x)
    local targetY = tonumber(y)
    local targetZ = tonumber(z) or zombie:getZ()
    if not targetX or not targetY then return false end

    -- doSprinter must run before the path state is constructed.
    BP.ApplyVolatileLore(zombie, true)
    local driven = pcall(function()
        local behavior = zombie:getPathFindBehavior2()
        if not behavior then error("missing directed path behavior") end
        behavior:cancel()
        zombie:setPath2(nil)
        behavior:reset()
        behavior:pathToLocationF(targetX + 0.5, targetY + 0.5, targetZ)
        zombie:setVariable("bMoving", false)
        zombie:setVariable("bPathfind", true)
        zombie:faceLocation(targetX + 0.5, targetY + 0.5)
    end)

    if not driven then
        driven = BP.PathZombieTo(zombie, targetX, targetY, targetZ, true)
    end
    if not driven then return false end

    -- Path construction may choose a generic walker node. These assignments
    -- are animation-only and deliberately happen after the route is ready.
    BP.ApplyVolatileAnimationSpeed(zombie)
    pcall(function()
        zombie:setWalkType(BP.VolatileWalkType)
        zombie:setVariable("shouldSprint", true)
        zombie:setVariable("bPathfind", true)
    end)
    return true
end

function BP.FindZombieByOnlineID(onlineId)
    local cell = getCell and getCell() or nil
    local zombies = cell and cell:getZombieList() or nil
    if not zombies then return nil end

    onlineId = tonumber(onlineId)
    for i = 0, zombies:size() - 1 do
        local zombie = zombies:get(i)
        if zombie and BP.GetZombieID(zombie) == onlineId then
            return zombie
        end
    end

    return nil
end

function BP.IsUVLightSource(light)
    -- Empty squares dominate the radius scan, so avoid a config lookup for
    -- them while UV mechanics are enabled.
    if not light or not BP.IsUVMechanicsEnabled() then return false end

    local ok, active, red, green, blue = pcall(function()
        return light:isActive(), light:getR(), light:getG(), light:getB()
    end)

    if not ok or not active then return false end

    red = tonumber(red) or 0
    green = tonumber(green) or 0
    blue = tonumber(blue) or 0

    -- DyingLightZombie checks the purple bulb's red and blue channels only.
    -- B42 may alter the green channel through fixture/climate lighting, so it
    -- must not be used to reject an otherwise valid UV source.
    return math.abs(red - 0.501960813999176) <= 0.08 and blue >= 0.95
end

function BP.GetUVGuardRadius()
    local config = BP.GetConfig()
    local radius = tonumber(config.UVRadius) or 8
    local buffer = tonumber(config.UVSafetyBuffer) or 1
    return math.max(1, radius + math.max(0, buffer))
end

function BP.GetUVFleeDurationHours()
    local config = BP.GetConfig()
    local baseDuration = math.max(0.01, tonumber(config.UVFleeHours) or 0.15)
    local speedScale = math.max(0.40, math.min(
        1.60, tonumber(config.VolatileSpeedScale) or 0.96
    ))
    -- A slower Sandbox speed gets proportionally more time to complete the
    -- same long escape. Reaching the target releases the guard early.
    return baseDuration * (0.96 / speedScale)
end

function BP.HasReachedUVTarget(zombie, arrivalRadius)
    if not BP.IsUVMechanicsEnabled() or not zombie then return false end
    local data = zombie:getModData()
    if not data then return false end

    local targetX = tonumber(data.BP_UVTargetX)
    local targetY = tonumber(data.BP_UVTargetY)
    local targetZ = tonumber(data.BP_UVTargetZ)
    if not targetX or not targetY then return false end
    if targetZ and math.floor(targetZ) ~= math.floor(zombie:getZ()) then return false end

    return BP.Distance2D(zombie:getX(), zombie:getY(), targetX, targetY)
        <= math.max(1, tonumber(arrivalRadius) or 2.5)
end

function BP.FindUVLightNear(zombie, radiusOverride)
    if not BP.IsUVMechanicsEnabled() or not zombie then return nil end

    local cell = getCell and getCell() or nil
    if not cell then return nil end

    local radius = math.max(1, math.ceil(
        tonumber(radiusOverride) or BP.GetUVGuardRadius()
    ))
    local zx = math.floor(zombie:getX())
    local zy = math.floor(zombie:getY())
    local zz = math.floor(zombie:getZ())
    local nearest
    local nearestDistanceSquared

    for dx = -radius, radius do
        for dy = -radius, radius do
            if dx * dx + dy * dy <= radius * radius then
                local light = cell:getLightSourceAt(zx + dx, zy + dy, zz)
                if BP.IsUVLightSource(light) then
                    local lightX = tonumber(light:getX()) or (zx + dx)
                    local lightY = tonumber(light:getY()) or (zy + dy)
                    local distanceX = zombie:getX() - lightX
                    local distanceY = zombie:getY() - lightY
                    local distanceSquared = distanceX * distanceX + distanceY * distanceY
                    if not nearestDistanceSquared
                        or distanceSquared < nearestDistanceSquared then
                        nearest = light
                        nearestDistanceSquared = distanceSquared
                    end
                end
            end
        end
    end

    return nearest
end

-- The stored source lets the authoritative server keep a stuck Volatile
-- guarded without waiting for another client report. Exit padding provides
-- spatial hysteresis so the boundary cannot flicker attack permission.
function BP.GetStoredUVLight(zombie, exitPadding)
    if not BP.IsUVMechanicsEnabled() or not zombie then return nil end
    local data = zombie:getModData()
    if not data then return nil end

    local lightX = tonumber(data.BP_UVLightX)
    local lightY = tonumber(data.BP_UVLightY)
    local lightZ = tonumber(data.BP_UVLightZ)
    if not lightX or not lightY or not lightZ then return nil end
    if math.floor(lightZ) ~= math.floor(zombie:getZ()) then return nil end

    local padding = math.max(0, tonumber(exitPadding) or 0)
    if BP.Distance2D(zombie:getX(), zombie:getY(), lightX, lightY)
        > BP.GetUVGuardRadius() + padding then
        return nil
    end

    local cell = getCell and getCell() or nil
    local light = cell and cell:getLightSourceAt(
        math.floor(lightX), math.floor(lightY), math.floor(lightZ)
    ) or nil
    if not BP.IsUVLightSource(light) then return nil end
    return light
end

-- Keep this lightweight: it is deliberately called on every owner-side zombie
-- update while UV is active. Useless disables vanilla sight/sound targeting,
-- while DriveUVFlee uses PathFindState directly so movement remains possible.
function BP.HoldUVGuard(zombie, resetState)
    if not BP.IsUVMechanicsEnabled() or not BP.IsVolatile(zombie) then
        return false
    end
    if not BP.CanControlZombie(zombie) then return false end

    BP.CancelZombieAttack(zombie, resetState, true)
    pcall(function() zombie:clearAggroList() end)
    pcall(function() zombie:setTarget(nil) end)
    pcall(function() zombie:setFakeDead(false) end)
    pcall(function() zombie:setVariable("BP_Sleeping", false) end)
    pcall(function() zombie:setVariable("ReactUV", true) end)
    -- Without this, spotted() can reacquire the player later in the same Java
    -- update and replace the UV destination with another chase path.
    pcall(function() zombie:setUseless(true) end)
    return true
end

function BP.DriveUVFlee(zombie)
    if not BP.IsUVMechanicsEnabled() or not BP.IsVolatile(zombie) then
        return false
    end
    if not BP.CanControlZombie(zombie) then return false end
    if BP.IsClimbingFence(zombie) then return true end
    local data = zombie:getModData()
    local targetX = tonumber(data.BP_UVTargetX)
    local targetY = tonumber(data.BP_UVTargetY)
    local targetZ = tonumber(data.BP_UVTargetZ) or zombie:getZ()
    if not targetX or not targetY then return false end

    if not BP.HoldUVGuard(zombie, false) then return false end
    -- Apply the Sprinter type before constructing the directed path. Calling
    -- doZombieSpeed/doSprinter afterwards can reset B42's new path state.
    BP.ApplyVolatileLore(zombie, true)

    local driven = pcall(function()
        local behavior = zombie:getPathFindBehavior2()
        if not behavior then error("missing UV path behavior") end
        behavior:cancel()
        zombie:setPath2(nil)
        behavior:reset()
        -- IsoZombie:pathToLocationF silently ignores a new destination while
        -- allowRepathDelay is active (normally true during a chase). Calling
        -- PathFindBehavior2 directly bypasses that throttle. A forced
        -- PathFindState can still move while useless blocks target reacquisition.
        behavior:pathToLocationF(targetX + 0.5, targetY + 0.5, targetZ)
        zombie:setVariable("bMoving", false)
        zombie:setVariable("bPathfind", true)
        zombie:faceLocation(targetX + 0.5, targetY + 0.5)
    end)
    if not driven then
        -- Never leave a failed directed path in a half-applied UV state.
        BP.ReleaseUVFlee(zombie)
        return false
    end
    -- These animation variables do not rebuild or cancel the path state.
    BP.ApplyVolatileAnimationSpeed(zombie)
    pcall(function()
        zombie:setWalkType(BP.VolatileWalkType)
        zombie:setVariable("ReactUV", true)
        zombie:setUseless(true)
    end)
    return true
end

-- Cancel only the native path created by UV. This is deliberately separate
-- from ordinary lease expiry: it is used when the Sandbox switch is turned
-- off live, so an already-running Volatile cannot finish an obsolete 85-tile
-- escape route after UV mechanics have been disabled.
function BP.CancelUVFleeRoute(zombie)
    if not zombie then return false end
    return pcall(function()
        local behavior = zombie:getPathFindBehavior2()
        if behavior then behavior:cancel() end
        zombie:setPath2(nil)
        if behavior then behavior:reset() end
        if zombie.setDefaultState then zombie:setDefaultState() end
        -- setDefaultState can reconstruct native path state; clear it once
        -- more at the transition boundary.
        zombie:setPath2(nil)
        behavior = zombie:getPathFindBehavior2()
        if behavior then behavior:reset() end
        zombie:setVariable("bPathfind", false)
    end)
end

function BP.ReleaseUVFlee(zombie)
    if not zombie then return end
    -- A disabled world can load a zombie carrying an old saved UV state after
    -- the one-time transition scan. Cancel that stale native path whenever it
    -- is released while the feature is off.
    if not BP.IsUVMechanicsEnabled() then BP.CancelUVFleeRoute(zombie) end
    local data = zombie:getModData()
    -- The guard reasserts interrupted attack variables while UV is active.
    -- Clear them at the same boundary so a released Volatile can attack again.
    BP.ClearZombieAttackOverrides(zombie)
    zombie:setUseless(false)
    zombie:setVariable("ReactUV", false)
    data.BP_UVUntil = nil
    data.BP_UVTargetX = nil
    data.BP_UVTargetY = nil
    data.BP_UVTargetZ = nil
    data.BP_UVLightX = nil
    data.BP_UVLightY = nil
    data.BP_UVLightZ = nil
    data.BP_NextUVDriveAt = nil
end

function BP.FleeFromUV(zombie, lightX, lightY, lightZ)
    if not BP.IsUVMechanicsEnabled() or not BP.IsVolatile(zombie) then
        return false
    end

    lightX = tonumber(lightX)
    lightY = tonumber(lightY)
    lightZ = tonumber(lightZ) or zombie:getZ()
    if not lightX or not lightY then return false end

    local dx = zombie:getX() - lightX
    local dy = zombie:getY() - lightY
    local length = math.sqrt(dx * dx + dy * dy)
    if length < 0.1 then
        local angle = (ZombRand(10000) / 10000) * math.pi * 2
        dx = math.cos(angle)
        dy = math.sin(angle)
        length = 1
    end

    local distance = math.max(12, tonumber(BP.GetConfig().UVFleeDistance) or 85)
    local baseAngle = math.atan2(dy / length, dx / length)
    local targetX
    local targetY
    local targetZ = math.floor(lightZ)
    local cell = getCell and getCell() or nil

    -- A straight line away from the lamp may end behind an inaccessible wall.
    -- Try progressively wider angles that still point away from UV and accept
    -- only free, loaded squares.
    if cell then
        -- Always try straight away first. Wide alternatives are only fallbacks;
        -- choosing one randomly first can make the pathfinder cut across the UV.
        local side = ZombRand(2) == 0 and 1 or -1
        local angleOffsets = {
            0, 15 * side, -15 * side, 30 * side, -30 * side,
            45 * side, -45 * side, 60 * side, -60 * side,
        }
        local distances = {
            distance,
            math.max(12, distance - 5),
            distance * 0.85,
            distance * 0.65,
            math.min(12, distance),
        }
        for _, candidateDistance in ipairs(distances) do
            for _, offset in ipairs(angleOffsets) do
                local angle = baseAngle + math.rad(offset)
                local candidateX = math.floor(zombie:getX() + math.cos(angle) * candidateDistance)
                local candidateY = math.floor(zombie:getY() + math.sin(angle) * candidateDistance)
                local square = cell:getGridSquare(candidateX, candidateY, targetZ)
                if square then
                    local freeOk, isFree = pcall(function() return square:isFree(false) end)
                    if not freeOk or isFree then
                        targetX = candidateX
                        targetY = candidateY
                        break
                    end
                end
            end
            if targetX then break end
        end
    end

    targetX = targetX or math.floor(zombie:getX() + (dx / length) * math.min(12, distance))
    targetY = targetY or math.floor(zombie:getY() + (dy / length) * math.min(12, distance))

    local data = zombie:getModData()
    local previousState = data.BP_State
    data.BP_State = "uv"
    data.BP_UVUntil = BP.GetWorldHour() + BP.GetUVFleeDurationHours()
    data.BP_UVTargetX = targetX
    data.BP_UVTargetY = targetY
    data.BP_UVTargetZ = targetZ
    data.BP_UVLightX = lightX
    data.BP_UVLightY = lightY
    data.BP_UVLightZ = lightZ
    data.BP_NextUVDriveAt = nil

    if not BP.DriveUVFlee(zombie) then
        data.BP_State = previousState or (BP.IsNight() and "night" or "returning")
        return false
    end
    return true, targetX, targetY, targetZ
end
