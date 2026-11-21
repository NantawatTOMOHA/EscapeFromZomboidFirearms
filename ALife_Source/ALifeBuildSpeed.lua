require "ProjectALife/Core/ALifeExperiments"

ProjectALife = ProjectALife or {}

local BuildSpeed = {
    defaults = {
        multiplier = 10,
        ticksPerCycle = 10,
        maxMsPerSecond = 60,
        sliceCapMs = 8,
        windowMs = 1000,
    },
    window = { startMs = 0, spentMs = 0 },
    metrics = { bursts = 0, capped = 0, steps = 0 },
}
ProjectALife.BuildSpeed = BuildSpeed

function BuildSpeed.active()
    local experiments = ProjectALife.Experiments
    return experiments ~= nil and experiments.enabled("buildspeed_x10") == true
end

function BuildSpeed.factor()
    if BuildSpeed.active() then
        return math.max(1, tonumber(BuildSpeed.defaults.multiplier) or 1)
    end
    local rules, data = ProjectALife.FiestaRules, ProjectALife.FiestaData
    if rules ~= nil and data ~= nil and rules.on("fiesta_buildspeed") then
        return tonumber(data.n.buildSpeed) or 1
    end
    return 1
end

function BuildSpeed.gap()
    local factor = BuildSpeed.factor()
    if factor <= 1 then return nil end
    return math.max(1, math.ceil(BuildSpeed.defaults.ticksPerCycle / factor))
end

function BuildSpeed.reset()
    BuildSpeed.window = { startMs = 0, spentMs = 0 }
    BuildSpeed.metrics = { bursts = 0, capped = 0, steps = 0 }
end

function BuildSpeed.afterStep(startedMs, nowMs)
    local gap = BuildSpeed.gap()
    if gap == nil or not BuildSpeed.active() then return false end
    local slice = ProjectALife.TickSlice
    if slice == nil then return false end
    local d, w = BuildSpeed.defaults, BuildSpeed.window
    BuildSpeed.metrics.steps = BuildSpeed.metrics.steps + 1
    if nowMs - w.startMs >= d.windowMs or nowMs < w.startMs then
        w.startMs, w.spentMs = nowMs, 0
    end
    w.spentMs = w.spentMs + math.max(0, nowMs - startedMs)
    if w.spentMs > d.maxMsPerSecond then
        BuildSpeed.metrics.capped = BuildSpeed.metrics.capped + 1
        return false
    end
    slice.more("outposts", gap)
    BuildSpeed.metrics.bursts = BuildSpeed.metrics.bursts + 1
    return true
end

function BuildSpeed.burstTick()
    local slice = ProjectALife.TickSlice
    if slice == nil or slice.viaMore == nil or slice.viaMore.outposts ~= true then return false end
    slice.viaMore.outposts = false
    local own = ProjectALife.OutpostSpeed
    return BuildSpeed.active() or (own ~= nil and own.takeBurst() == true)
end

function BuildSpeed.qaLine()
    local m = BuildSpeed.metrics
    return "buildspeed_x10 factor=" .. tostring(BuildSpeed.factor()) .. " steps=" .. m.steps
        .. " bursts=" .. m.bursts .. " capped=" .. m.capped
end

return BuildSpeed
