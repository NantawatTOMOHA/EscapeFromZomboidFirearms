require "ProjectALife/Core/ALifePerf"

ProjectALife = ProjectALife or {}

if ProjectALife.TickSlice ~= nil then return ProjectALife.TickSlice end

local Slice = {
    adapters = {},
    settings = { share = 0.5, floorMs = 1, maxDefer = 3, topRows = 14, topSubs = 10 },
    tickAtMs = nil,
    wanted = {},
    deferred = {},
    stats = {},
    order = {},
    deferrals = 0,
    notBefore = {},
    viaMore = {},
    ticksNow = nil,
}
ProjectALife.TickSlice = Slice

function Slice.clock()
    if type(Slice.adapters.nowMs) == "function" then return Slice.adapters.nowMs() end
    if type(getTimestampMs) == "function" then return getTimestampMs() end
    return math.floor(os.time() * 1000)
end

function Slice.budgetMs()
    local perf = ProjectALife.Perf
    if perf ~= nil and type(perf.tickBudgetMs) == "function" then return perf.tickBudgetMs() end
    return 8
end

function Slice.sliceMs()
    return math.max(Slice.settings.floorMs, Slice.budgetMs() * Slice.settings.share)
end

function Slice.beginTick(now)
    Slice.tickAtMs = now or Slice.clock()
end

function Slice.spent()
    if Slice.tickAtMs == nil then return 0 end
    return math.max(0, Slice.clock() - Slice.tickAtMs)
end

function Slice.left()
    return math.max(0, Slice.budgetMs() - Slice.spent())
end

function Slice.over()
    return Slice.tickAtMs ~= nil and Slice.spent() >= Slice.budgetMs()
end

function Slice.exhausted(began, ms)
    return Slice.clock() - began >= (ms or Slice.sliceMs())
end

function Slice.row(name)
    local row = Slice.stats[name]
    if row == nil then
        row = { runs = 0, yields = 0, maxMs = 0, totalMs = 0, items = 0 }
        Slice.stats[name] = row
        Slice.order[#Slice.order + 1] = name
    end
    return row
end

function Slice.note(name, startedAt, items, yielded)
    local now = Slice.clock()
    return Slice.record(name, math.max(0, now - (startedAt or now)), items, yielded)
end

function Slice.record(name, spent, items, yielded)
    local row = Slice.row(name)
    row.runs = row.runs + 1
    row.totalMs = row.totalMs + spent
    row.items = row.items + (tonumber(items) or 0)
    if spent > row.maxMs then row.maxMs = spent end
    if yielded == true then row.yields = row.yields + 1 end
    return spent
end

function Slice.more(name, gap)
    Slice.wanted[name] = true
    if tonumber(gap) ~= nil and gap > 1 and Slice.ticksNow ~= nil then
        Slice.notBefore[name] = Slice.ticksNow + gap
    end
end

function Slice.wants(name)
    return Slice.wanted[name] == true
end

function Slice.take(name)
    Slice.wanted[name] = nil
    Slice.deferred[name] = nil
    Slice.notBefore[name] = nil
end

function Slice.shouldDefer(name)
    if not Slice.over() then return false end
    local count = Slice.deferred[name] or 0
    if count >= Slice.settings.maxDefer then return false end
    Slice.deferred[name] = count + 1
    Slice.wanted[name] = true
    Slice.deferrals = Slice.deferrals + 1
    local row = Slice.row("defer:" .. name)
    row.runs = row.runs + 1
    row.yields = row.yields + 1
    return true
end

function Slice.due(name, slot, ticks)
    Slice.ticksNow = ticks
    local early = Slice.notBefore[name]
    local carried = Slice.wanted[name] == true and (early == nil or ticks >= early)
    local slotHit = ticks % 10 == slot
    if not carried and not slotHit then return false end
    if Slice.shouldDefer(name) then return false end
    Slice.take(name)
    Slice.viaMore[name] = carried and not slotHit
    return true
end

function Slice.reset()
    Slice.tickAtMs = nil
    Slice.wanted, Slice.deferred = {}, {}
    Slice.notBefore, Slice.viaMore, Slice.ticksNow = {}, {}, nil
    Slice.stats, Slice.order = {}, {}
    Slice.deferrals = 0
end

function Slice.rowOrder(a, b)
    if a.row.maxMs ~= b.row.maxMs then return a.row.maxMs > b.row.maxMs end
    if a.row.yields ~= b.row.yields then return a.row.yields > b.row.yields end
    return a.name < b.name
end

function Slice.rowsText(rows, limit)
    table.sort(rows, Slice.rowOrder)
    local parts = {}
    local shown = math.min(limit, #rows)
    for index = 1, shown do
        local row = rows[index].row
        parts[#parts + 1] = rows[index].name .. "=" .. tostring(row.runs) .. "/" .. tostring(row.yields)
            .. "/" .. tostring(row.items) .. "/" .. tostring(math.floor(row.maxMs * 10 + 0.5) / 10) .. "ms"
    end
    if #rows > shown then parts[#parts + 1] = "+" .. tostring(#rows - shown) end
    return table.concat(parts, " ")
end

function Slice.summary()
    local rows, subs = {}, {}
    for index = 1, #Slice.order do
        local name = Slice.order[index]
        local row = { name = name, row = Slice.stats[name] }
        if string.sub(name, 1, 4) == "sub:" then subs[#subs + 1] = row else rows[#rows + 1] = row end
    end
    Slice.stats, Slice.order = {}, {}
    local text = #rows > 0 and Slice.rowsText(rows, Slice.settings.topRows) or nil
    local subText = #subs > 0 and Slice.rowsText(subs, Slice.settings.topSubs) or nil
    return text, subText
end

return Slice
