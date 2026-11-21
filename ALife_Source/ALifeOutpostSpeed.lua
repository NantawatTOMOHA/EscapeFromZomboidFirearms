require "ProjectALife/Core/ALifeFeatures"

ProjectALife = ProjectALife or {}

local Speed = {
    defaults = {
        rounds = 4,
        fixedRounds = 8,
        tickCapMs = 12,
        windowMs = 1000,
        maxMsPerSecond = 120,
        logMs = 15000,
        wakeMs = 250,
        margin = 6,
    },
    adapters = {},
    waiting = {},
    waitCount = 0,
    logAt = {},
    reasons = {},
    window = { startMs = 0, spentMs = 0 },
    lastWake = -1000000,
    rerunSet = false,
    metrics = { bursts = 0, extra = 0, capped = 0, wakes = 0, logged = 0 },
}
ProjectALife.OutpostSpeed = Speed

function Speed.reset()
    Speed.waiting, Speed.waitCount, Speed.logAt, Speed.reasons = {}, 0, {}, {}
    Speed.window = { startMs = 0, spentMs = 0 }
    Speed.lastWake, Speed.rerunSet = -1000000, false
    Speed.metrics = { bursts = 0, extra = 0, capped = 0, wakes = 0, logged = 0 }
end

function Speed.clock()
    if type(Speed.adapters.nowMs) == "function" then return Speed.adapters.nowMs() end
    if type(getTimestampMs) == "function" then return getTimestampMs() end
    return 0
end

function Speed.on()
    local options = ProjectALife.Options
    if options == nil or type(options.get) ~= "function" then return false end
    local features = ProjectALife.Features
    return features ~= nil and features.on("OutpostFastBuild") == true
end

function Speed.xTen()
    local own = ProjectALife.BuildSpeed
    return own ~= nil and own.active() == true
end

function Speed.rounds(fixed)
    local d = Speed.defaults
    return math.max(1, math.floor(fixed and d.fixedRounds or d.rounds))
end

function Speed.units(sheet)
    local builder = ProjectALife.OutpostBuilder
    local total, done = 0, 0
    local cursor = math.max(1, math.floor(tonumber(sheet.cursor) or 1))
    for index, op in ipairs(sheet.ops) do
        local cost = builder ~= nil and builder.opCost(op) or 1
        total = total + cost
        if index < cursor or (type(sheet.finished) == "table" and sheet.finished[index]) then done = done + cost end
    end
    return total, done
end

function Speed.note(site, reason, sheet, now)
    now = now or Speed.clock()
    local last = Speed.logAt[site.id]
    local gap = tonumber(Speed.defaults.logMs) or 15000
    if last ~= nil and now >= last and now - last < gap and Speed.reasons[site.id] == reason then return false end
    Speed.logAt[site.id], Speed.reasons[site.id] = now, reason
    local needed, done = "?", tonumber(site.buildCursor) and (site.buildCursor - 1) or 0
    if type(sheet) == "table" and type(sheet.ops) == "table" then needed, done = Speed.units(sheet) end
    Speed.metrics.logged = Speed.metrics.logged + 1
    print("[ALIFE-OUTPOST] build site=" .. tostring(site.id) .. " needed=" .. tostring(needed)
        .. " done=" .. tostring(done) .. " waiting=" .. tostring(reason))
    return true
end

function Speed.watch(site, reason)
    local known = Speed.waiting[site.id]
    if reason ~= "ground_unloaded" then
        if known ~= nil then Speed.waiting[site.id] = nil; Speed.waitCount = Speed.waitCount - 1 end
        return
    end
    local b = type(site.bounds) == "table" and site.bounds or {}
    local m = Speed.defaults.margin
    local x1, y1 = tonumber(b.x1) or tonumber(site.x) or 0, tonumber(b.y1) or tonumber(site.y) or 0
    local x2, y2 = tonumber(b.x2) or x1, tonumber(b.y2) or y1
    if known == nil then Speed.waitCount = Speed.waitCount + 1 end
    Speed.waiting[site.id] = { x1 = x1 - m, y1 = y1 - m, x2 = x2 + m, y2 = y2 + m }
end

function Speed.clear(id)
    if Speed.waiting[id] ~= nil then Speed.waiting[id] = nil; Speed.waitCount = Speed.waitCount - 1 end
    Speed.logAt[id], Speed.reasons[id] = nil, nil
end

function Speed.wake(now)
    now = now or Speed.clock()
    if now >= Speed.lastWake and now - Speed.lastWake < Speed.defaults.wakeMs then return false end
    local slice = ProjectALife.TickSlice
    if slice == nil then return false end
    Speed.lastWake = now
    Speed.rerunSet = true
    slice.more("outposts", 1)
    Speed.metrics.wakes = Speed.metrics.wakes + 1
    return true
end

function Speed.onSquare(square)
    if Speed.waitCount <= 0 or square == nil or not Speed.on() then return false end
    local x, y = square:getX(), square:getY()
    for _, box in pairs(Speed.waiting) do
        if x >= box.x1 and x <= box.x2 and y >= box.y1 and y <= box.y2 then return Speed.wake() end
    end
    return false
end

function Speed.takeBurst()
    if Speed.rerunSet ~= true then return false end
    Speed.rerunSet = false
    return true
end

function Speed.afterStep(startedMs, nowMs)
    if Speed.xTen() then return false end
    local slice = ProjectALife.TickSlice
    if slice == nil then return false end
    local d, w = Speed.defaults, Speed.window
    if nowMs - w.startMs >= d.windowMs or nowMs < w.startMs then w.startMs, w.spentMs = nowMs, 0 end
    w.spentMs = w.spentMs + math.max(0, nowMs - startedMs)
    if w.spentMs > d.maxMsPerSecond then
        Speed.metrics.capped = Speed.metrics.capped + 1
        return false
    end
    Speed.rerunSet = true
    slice.more("outposts", 1)
    return true
end

function Speed.begin(sheet)
    Speed.mark = Speed.clock()
    Speed.cursor = type(sheet) == "table" and tonumber(sheet.cursor) or nil
end

function Speed.burst(builder, sheet, site, roll, budget, hold, fixed, placed)
    local started = Speed.mark or Speed.clock()
    local total = tonumber(placed) or 0
    local d = Speed.defaults
    local rounds = Speed.rounds(fixed)
    local ok, last, done = true, total, false
    local before = Speed.cursor
    for _ = 2, rounds do
        local at = tonumber(sheet.cursor) or 1
        if before ~= nil and at <= before then break end
        if Speed.clock() - started >= d.tickCapMs then break end
        before = at
        ok, last, done = builder.execute(sheet, site, roll, budget, hold)
        if not ok then return false, last, done end
        Speed.metrics.extra = Speed.metrics.extra + 1
        total = total + (tonumber(last) or 0)
        if done ~= false then return true, total, done end
    end
    Speed.metrics.bursts = Speed.metrics.bursts + 1
    return true, total, false
end

function Speed.fixedFirst(ids, isFixed)
    local first, rest = {}, {}
    for _, id in ipairs(ids) do
        if isFixed(id) then first[#first + 1] = id else rest[#rest + 1] = id end
    end
    for _, id in ipairs(rest) do first[#first + 1] = id end
    return first
end

if Events ~= nil and Events.LoadGridsquare ~= nil then
    Events.LoadGridsquare.Add(Speed.onSquare)
end

return Speed
