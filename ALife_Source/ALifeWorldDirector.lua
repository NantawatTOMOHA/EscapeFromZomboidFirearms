require "ProjectALife/Core/ALifeExperiments"
require "ProjectALife/World/ALifeWorldData"
require "ProjectALife/Core/ALifePersistence"
require "ProjectALife/Offline/ALifeWorldHunt"
require "ProjectALife/Offline/ALifeWorldDrift"

ProjectALife = ProjectALife or {}

local Data = ProjectALife.WorldData

local World = {
    adapters = {},
    metrics = { pity = 0, pityFailed = 0, contact = 0, contactFailed = 0, drift = 0, leashed = 0, trailExpired = 0,
        dispatched = 0, lone = 0, deeds = 0, hubs = 0, refused = {}, refusedTotal = 0 },
    state = { driftAt = nil },
    fallback = nil,
}
ProjectALife.WorldDirector = World

local function dist(ax, ay, bx, by)
    local dx, dy = ax - bx, ay - by
    return math.sqrt(dx * dx + dy * dy)
end

function World.reset()
    World.metrics = { pity = 0, pityFailed = 0, contact = 0, contactFailed = 0, drift = 0, leashed = 0,
        trailExpired = 0, dispatched = 0, lone = 0, deeds = 0, hubs = 0, refused = {}, refusedTotal = 0 }
    World.state = { driftAt = nil }
    World.fallback = nil
end

function World.hours()
    if type(World.adapters.hours) == "function" then return tonumber(World.adapters.hours()) or 0 end
    local offline = ProjectALife.OfflineDirector
    if offline ~= nil and type(offline.nowHours) == "function" then return tonumber(offline.nowHours()) or 0 end
    local value = 0
    pcall(function() value = getGameTime():getWorldAgeHours() end)
    return tonumber(value) or 0
end

function World.bucket()
    local store
    if type(World.adapters.store) == "function" then
        store = World.adapters.store()
    else
        local persistence = ProjectALife.Persistence
        if persistence ~= nil and type(persistence.bucket) == "function" then
            store = persistence.bucket("world_director", {})
        end
    end
    if type(store) ~= "table" then
        if World.fallback == nil then World.fallback = {} end
        store = World.fallback
    end
    if type(store.heat) ~= "table" then store.heat = {} end
    if type(store.cooldown) ~= "table" then store.cooldown = {} end
    if type(store.pity) ~= "table" then store.pity = {} end
    return store
end

function World.touch()
    if type(World.adapters.touch) == "function" then World.adapters.touch(); return end
    local persistence = ProjectALife.Persistence
    if persistence ~= nil and type(persistence.touch) == "function" then persistence.touch() end
end

function World.noteRefused(reason)
    local m = World.metrics
    m.refused[reason] = (m.refused[reason] or 0) + 1
    m.refusedTotal = m.refusedTotal + 1
end

function World.noteLeashed() World.metrics.leashed = World.metrics.leashed + 1 end
function World.noteTrailExpired() World.metrics.trailExpired = World.metrics.trailExpired + 1 end

function World.noteDispatched(band)
    World.metrics.dispatched = World.metrics.dispatched + 1
    if band == "lone" then World.metrics.lone = World.metrics.lone + 1 end
end

function World.heatLimits(factionId)
    local row = Data.data.heat
    local own = row.perFaction[factionId]
    local threshold = tonumber(row.threshold) or 25
    local lone = tonumber(row.loneThreshold) or 12
    if type(own) == "table" then
        threshold = tonumber(own.threshold) or threshold
        lone = tonumber(own.lone) or lone
    end
    return { threshold = threshold, lone = math.min(lone, threshold) }
end

function World.heatOf(key, factionId, hours)
    local rows = World.bucket().heat[key]
    local row = type(rows) == "table" and rows[factionId] or nil
    if type(row) ~= "table" then return 0 end
    local value = tonumber(row.v) or 0
    local at = tonumber(row.at) or hours
    local gone = math.max(0, (tonumber(hours) or at) - at) / 24 * (tonumber(Data.data.heat.decayPerDay) or 0)
    return math.max(0, value - gone)
end

function World.addHeat(key, factionId, amount, hours)
    if type(key) ~= "string" or key == "" or type(factionId) ~= "string" or factionId == "" then return 0 end
    local store = World.bucket()
    local rows = store.heat[key]
    if type(rows) ~= "table" then rows = {}; store.heat[key] = rows end
    local value = World.heatOf(key, factionId, hours) + (tonumber(amount) or 0)
    value = math.max(0, math.min(tonumber(Data.data.heat.cap) or 100, value))
    rows[factionId] = { v = value, at = hours }
    World.touch()
    return value
end

function World.setHeat(key, factionId, value, hours)
    local store = World.bucket()
    local rows = store.heat[key]
    if type(rows) ~= "table" then rows = {}; store.heat[key] = rows end
    rows[factionId] = { v = math.max(0, tonumber(value) or 0), at = hours }
    World.touch()
end

function World.noteDeed(player, who, deed)
    if not Data.rowOn("heat") then return false end
    local amount = tonumber(Data.data.heat.deeds[tostring(deed or "")])
    if amount == nil then return false end
    local factionId = type(who) == "table" and who.factionId or who
    local reputation = ProjectALife.Reputation
    local key = reputation ~= nil and type(reputation.playerKey) == "function" and reputation.playerKey(player) or nil
    if type(factionId) ~= "string" or type(key) ~= "string" then return false end
    World.addHeat(key, factionId, amount, World.hours())
    World.metrics.deeds = World.metrics.deeds + 1
    return true
end

function World.cooldownUntil(key, factionId)
    local value = World.bucket().cooldown[tostring(key) .. "|" .. tostring(factionId)]
    return tonumber(value) or -math.huge
end

function World.setCooldown(key, factionId, untilHours)
    World.bucket().cooldown[tostring(key) .. "|" .. tostring(factionId)] = untilHours
    World.touch()
end

function World.activeCount()
    local ledger = ProjectALife.SquadLedger
    local count = 0
    if ledger == nil then return 0 end
    ledger.eachLive(function(q)
        local task = q.task
        if type(task) == "table" and task.kind == "assassin" and task.forced ~= true then count = count + 1 end
    end)
    return count
end

function World.playerKey(player)
    local reputation = ProjectALife.Reputation
    local key = reputation ~= nil and type(reputation.playerKey) == "function" and reputation.playerKey(player) or nil
    if type(key) == "string" and key ~= "" then return key end
    if player ~= nil and type(player.getUsername) == "function" then return tostring(player:getUsername()) end
    return "player0"
end

function World.pityRow(key, hours)
    local rows = World.bucket().pity
    local row = rows[key]
    if type(row) ~= "table" then
        row = { budget = 0, days = {}, contactMade = false }
        rows[key] = row
    end
    return row
end

function World.hostileFaction(faction)
    local rules = ProjectALife.FactionSpawnRules
    return type(faction) == "table" and rules ~= nil and type(rules.hostile) == "function" and rules.hostile(faction) == true
end

function World.forceFor(player, px, py, hours)
    local pityOn, contactOn = Data.rowOn("pity"), Data.rowOn("contact")
    if not pityOn and not contactOn then return nil end
    local director = ProjectALife.EncounterDirector
    local accepted = director ~= nil and director.metrics ~= nil and tonumber(director.metrics.accepted) or 0
    local key = World.playerKey(player)
    local row = World.pityRow(key, hours)
    local pity = Data.data.pity
    if row.pending ~= nil and accepted == row.pendingAccepted then
        if row.pending == "contact" then
            World.metrics.contactFailed = World.metrics.contactFailed + 1
            row.nextTry = hours + (tonumber(Data.data.contact.retryHours) or 2)
        else
            World.metrics.pityFailed = World.metrics.pityFailed + 1
        end
    end
    row.pending = nil
    if row.at ~= nil then
        local step = dist(row.x or px, row.y or py, px, py)
        step = math.min(step, tonumber(pity.stepTilesMax) or 3000)
        local gap = math.max(0, hours - row.at)
        row.budget = (tonumber(row.budget) or 0) + gap * (tonumber(pity.perHour) or 1)
            + step / 100 * (tonumber(pity.per100Tiles) or 0)
    end
    row.x, row.y, row.at = px, py, hours
    local kind
    local contact = Data.data.contact
    if contactOn and row.contactMade ~= true and hours >= (tonumber(contact.fromHours) or 8)
            and hours <= (tonumber(contact.untilHours) or 72) and hours >= (tonumber(row.nextTry) or 0) then
        kind = "contact"
    elseif pityOn and (tonumber(row.budget) or 0) >= (tonumber(pity.threshold) or 18)
            and hours >= (tonumber(pity.minHours) or 6) then
        local day = tostring(math.floor(hours / 24))
        if (tonumber(row.days[day]) or 0) < (tonumber(pity.maxPerDay) or 3) then kind = "pity" end
    end
    if kind ~= nil then
        row.pending, row.pendingAccepted = kind, accepted
        World.metrics[kind] = World.metrics[kind] + 1
    end
    World.touch()
    return kind
end

function World.kindAllowed(kind, encounterId)
    local row = kind == "contact" and Data.data.contact or Data.data.pity
    return row.kinds[encounterId] == true
end

function World.noteEncounter(player, definition, faction, forced)
    if not Data.rowOn("pity") and not Data.rowOn("contact") then return false end
    local hours = World.hours()
    local row = World.pityRow(World.playerKey(player), hours)
    row.budget = 0
    row.pending = nil
    if forced == "pity" then
        local day = tostring(math.floor(hours / 24))
        row.days[day] = (tonumber(row.days[day]) or 0) + 1
    end
    if not World.hostileFaction(faction) and hours <= (tonumber(Data.data.contact.untilHours) or 72) then
        row.contactMade = true
    end
    World.touch()
    return true
end

function World.hubWeight(preset, day)
    if not Data.rowOn("hubs") or type(preset) ~= "table" then return 1 end
    local row = Data.data.hubs
    if (tonumber(day) or 0) >= (tonumber(row.days) or 5) then return 1 end
    local weight = 1
    local siting = preset.siting
    if type(siting) == "table" and siting.road == "near" then weight = weight * (tonumber(row.roadWeightMul) or 1) end
    local family = preset.family
    if family == "settler" or family == "trader" or family == "law" then
        weight = weight * (tonumber(row.peacefulWeightMul) or 1)
    end
    return weight
end

function World.hubChance(hours)
    if not Data.rowOn("hubs") then return 1 end
    local row = Data.data.hubs
    if (tonumber(hours) or 0) >= (tonumber(row.days) or 5) * 24 then return 1 end
    World.metrics.hubs = World.metrics.hubs + 1
    return tonumber(row.chanceMul) or 1
end

function World.tick()
    if not Data.rowOn("drift") then return 0 end
    local now = type(getTimestampMs) == "function" and getTimestampMs() or 0
    if now < (tonumber(World.state.nextMs) or 0) then return 0 end
    World.state.nextMs = now + (tonumber(Data.data.drift.pollMs) or 5000)
    local offline = ProjectALife.OfflineDirector
    local drift = ProjectALife.WorldDrift
    if offline == nil or drift == nil or type(offline.playerList) ~= "function" then return 0 end
    local hours = World.hours()
    local last = World.state.driftAt
    if last == nil or hours < last then
        World.state.driftAt = hours
        return 0
    end
    if hours - last < (tonumber(Data.data.drift.everyHours) or 2) then return 0 end
    World.state.driftAt = hours
    return drift.pass(hours, offline.playerList())
end

function World.inspect(key)
    local hours = World.hours()
    local store = World.bucket()
    local heat, rows = {}, store.heat[key]
    if type(rows) == "table" then
        for factionId in pairs(rows) do heat[factionId] = math.floor(World.heatOf(key, factionId, hours) * 10) / 10 end
    end
    local row = store.pity[key]
    return {
        ok = true, hours = math.floor(hours * 10) / 10, heat = heat,
        pity = type(row) == "table" and { budget = math.floor((tonumber(row.budget) or 0) * 10) / 10,
            contactMade = row.contactMade == true, nextTry = row.nextTry } or nil,
        metrics = World.metrics, hunt = ProjectALife.WorldHunt ~= nil and ProjectALife.WorldHunt.metrics or nil,
        drift = ProjectALife.WorldDrift ~= nil and ProjectALife.WorldDrift.metrics or nil,
        active = World.activeCount(),
        rows = { leash = Data.rowOn("leash"), trail = Data.rowOn("trail"), heat = Data.rowOn("heat"),
            telegraph = Data.rowOn("telegraph"), pity = Data.rowOn("pity"), drift = Data.rowOn("drift"),
            contact = Data.rowOn("contact"), hubs = Data.rowOn("hubs") },
    }
end

function World.debugDispatch(entry, factionId, hours)
    local assassins = ProjectALife.Assassins
    if assassins == nil or type(assassins.order) ~= "function" then return false, "no_assassins" end
    local key = tostring(entry.key or "player0")
    local store = assassins.bucket()
    if type(store.intel) ~= "table" then store.intel = {} end
    local own = store.intel[key]
    if type(own) ~= "table" then own = {}; store.intel[key] = own end
    own[factionId] = { x = entry.x, y = entry.y, z = entry.z or 0, hours = hours, stampMs = 0, source = "last_seen" }
    World.setHeat(entry.repKey or key, factionId, (tonumber(Data.data.heat.threshold) or 25) + 5, hours)
    World.setCooldown(entry.repKey or key, factionId, 0)
    return assassins.order(entry, key, hours, false)
end

function World.admin(Service, player, args)
    local answer = World.run(player, type(args) == "table" and args or {})
    Service.sendReply(player, "adminWorldResult", answer)
    return answer.ok == true
end

function World.run(player, args)
    local offline = ProjectALife.OfflineDirector
    local action = tostring(args.action or "inspect")
    local entry
    if offline ~= nil and type(offline.playerList) == "function" then
        local name = player ~= nil and type(player.getUsername) == "function" and tostring(player:getUsername()) or nil
        for _, position in ipairs(offline.playerList()) do
            if name == nil or tostring(position.key) == name then entry = position; break end
        end
    end
    local hours = World.hours()
    local key = entry ~= nil and tostring(entry.repKey or entry.key) or World.playerKey(player)
    if action == "inspect" then return World.inspect(key) end
    if entry == nil and action ~= "reset" then return { ok = false, reason = "no_player" } end
    if action == "heat" then
        local factionId = tostring(args.factionId or "")
        if factionId == "" then return { ok = false, reason = "no_faction" } end
        World.setHeat(key, factionId, tonumber(args.value) or 50, hours)
        return World.inspect(key)
    elseif action == "dispatch" then
        local factionId = tostring(args.factionId or "")
        if factionId == "" then return { ok = false, reason = "no_faction" } end
        local sent, why = World.debugDispatch(entry, factionId, hours)
        local answer = World.inspect(key)
        answer.dispatched, answer.why = sent == true, why
        return answer
    elseif action == "pity" then
        World.pityRow(key, hours).budget = (tonumber(Data.data.pity.threshold) or 18) + 1
        return World.inspect(key)
    elseif action == "contact" then
        local row = World.pityRow(key, hours)
        row.contactMade, row.nextTry = false, nil
        return World.inspect(key)
    elseif action == "drift" then
        local drift = ProjectALife.WorldDrift
        local moved = drift ~= nil and drift.pass(hours, offline.playerList()) or 0
        local answer = World.inspect(key)
        answer.moved = moved
        return answer
    elseif action == "reset" then
        local store = World.bucket()
        store.heat, store.cooldown, store.pity = {}, {}, {}
        World.touch()
        return World.inspect(key)
    end
    return { ok = false, reason = "unknown_action" }
end

return World
