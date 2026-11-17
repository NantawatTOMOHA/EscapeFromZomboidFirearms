-- Tarkov's sight sensitivity for Viewpoint's first person (W10 user item 9, 27.10.2026: "add sensitivity
-- options and a separate sensitivity in the sight depending on magnification - if Tarkov already has it,
-- use its logic").
--
-- Tarkov has two settings, MouseSensitivity (hip) and MouseAimingSensitivity (aim), and every sight mode
-- carries its own AimSensitivity; a variable optic uses its smooth pair instead. Read from the installed
-- client (C:\SPT, EscapeFromTarkov_Data\Managed\Assembly-CSharp.dll, ILSpy; raw output kept outside Git in
-- qa/evidence/20261016/viewpoint/R3 and W3-B1):
--
--   Player.GetRotationMultiplier (EFT.Player): aiming -> HandsController.AimingSensitivity, else
--   GetSensitivity() * (1 + mouseSensitivityModifiers); Player.FirearmController.UpdateSensitivity:
--     num = Item.Template.AimSensitivity                                  (no sight component: 1)
--     if currentAimingMod then num = currentAimingMod.AdjustableOpticData.IsAdjustableOptic
--                                  and CurrentScope.IsOptic
--            ? AimingSmoothSensitivity                                   (set by ScopeZoomHandler)
--            : currentAimingMod.GetCurrentSensitivity                    (AimSensitivity[scope][mode])
--     _aimingSens = num * player.GetAimingSensitivity()                  (the aim setting)
--   SightComponent.GetCurrentSensitivity: AimSensitivity[SelectedScope][SelectedScopeMode], 1 outside.
--   ScopeZoomHandler: Lerp(AdjustableOpticSensitivity, AdjustableOpticSensitivityMax,
--                          InverseLerp(MinMaxFov.x, MinMaxFov.y, currentFov))
--
-- So the aim multiplier is the sight's own value times the aim setting; at a variable optic the value
-- moves from AdjustableOpticSensitivity (widest FOV, least magnified) to ...Max (narrowest). A Tarkov sight
-- without a SightComponent (iron sights, a bare camera) and every channel we have no data for count as 1.
-- The data is the extracted one (TW/Data/ViewpointOptics.lua, assets/viewpoint_optics.json, generator
-- tools/viewpoint_optics_runtime.py).
--
-- This module is data and arithmetic only: no Viewpoint, no Java, no options object. It is shared so that a
-- camera-zoom mode (W10 task `nopip`) can ask for the sensitivity at the FOV it shows
-- (S.sensitivity(view, fovDegrees)); the client module TW_Sensitivity.lua adds the options and hands the
-- multiplier to the Java part, which scales Viewpoint's own mouse delta by it.
local S={}

-- The options when nothing is registered yet or the Java/Lua side is old: today's behaviour.
S.DEFAULT={mouse=1,aim=1,bySight=true}

local function number(x) return type(x)=='number' and x==x and x>-1e20 and x<1e20 end
local function clamp(x,a,b) return math.max(a,math.min(b,x)) end

local function modeOf(view)
    local channel=view and view.channelProfile
    local modes=channel and channel.modes
    return modes and modes[view.mode or 1]
end

-- The channel's continuous range: the widest and the narrowest FOV in degrees, Tarkov's MinMaxFov.x/.y.
-- Only an adjustable optic with the source's own smooth camera has one (the NXS keeps its smooth prefab
-- but IsAdjustableOptic=false, so the database mode value stays in force).
local function continuousRange(view)
    local channel=view and view.channelProfile
    local zoom=channel and channel.profile and channel.profile.zoom
    local mode=modeOf(view)
    local camera=mode and mode.profile and mode.profile.camera
    local range=zoom and zoom.minMaxFov
    if not (zoom and zoom.adjustable and camera and camera.status=='continuous') then return end
    if not (number(range and range.x) and number(range.y) and range.x>range.y and range.y>0) then return end
    return range.x,range.y
end

-- Tarkov's sight sensitivity for this channel and mode at a FOV (degrees; nil: the sight's own current
-- FOV). Returns the value and where it comes from: "smooth" (a variable optic), "table" (the sight's
-- AimSensitivity) or "default" (no sight data: 1).
function S.sensitivity(view,fov)
    local channel=view and view.channelProfile
    local profile=channel and channel.profile
    local sensitivities=profile and profile.zoom and profile.zoom.sensitivities
    if not sensitivities then return 1,'default' end
    local wide,narrow=continuousRange(view)
    if wide then
        local a,b=sensitivities.AdjustableOpticSensitivity,sensitivities.AdjustableOpticSensitivityMax
        if number(a) and number(b) then
            local current=fov or view.fov
            -- An unknown or nonsense FOV takes the widest end: the least magnified, the fastest value.
            if not number(current) or current<=0 then current=wide end
            local t=clamp((wide-current)/(wide-narrow),0,1)
            return a+(b-a)*t,'smooth'
        end
    end
    local group=sensitivities.AimSensitivity and sensitivities.AimSensitivity[(view.channel or 0)+1]
    local value=group and group[(view.modeIndex or 0)+1]
    if number(value) then return value,'table' end
    return 1,'default'
end

-- The sight's magnification at a FOV (degrees; nil: its current one), the same way SightState reports it:
-- a variable optic scales its smallest nominal by the wide FOV over the shown one.
function S.magnification(view,fov)
    local channel=view and view.channelProfile
    if not channel then return 1 end
    if fov==nil and number(view.magnification) then return view.magnification end
    local wide,narrow=continuousRange(view)
    if wide then
        local current=fov or view.fov
        if not number(current) or current<=0 then current=wide end
        local smallest
        local nominals=channel.scope and channel.scope.nominals or {}
        for _,z in ipairs(nominals) do if number(z) and z>0 and (not smallest or z<smallest) then smallest=z end end
        return (smallest or 1)*wide/current
    end
    local mode=modeOf(view)
    local nominals=channel.scope and channel.scope.nominals or {}
    return (mode and mode.nominalZoom) or nominals[1] or 1
end

local function spec(s)
    s=s or S.DEFAULT
    local mouse,aim,bySight=tonumber(s.mouse),tonumber(s.aim),s.bySight
    return {mouse=number(mouse) and mouse or S.DEFAULT.mouse,
        aim=number(aim) and aim or S.DEFAULT.aim,
        bySight=bySight==nil and S.DEFAULT.bySight or bySight~=false}
end

-- The multiplier Viewpoint's first person mouse delta takes: the hip setting, or the aim setting times the
-- sight's own value while the sight-per-magnification switch is on. Aiming with a sight whose data is
-- missing gives the aim setting alone (Tarkov: 1). Both defaults are 1, so with the switch off the hip and
-- aim speeds are exactly today's.
function S.multiplier(aiming,sight,s)
    s=spec(s)
    if not aiming then return s.mouse end
    if s.bySight and number(sight) then return s.aim*sight end
    return s.aim
end

-- The multiplier for a view now (aiming state, optional FOV override for a camera-zoom mode, and the options:
-- nil takes the ones the client module set with S.setOptions, so a caller cannot silently lose them).
function S.effective(aiming,view,fov,s)
    local sight
    if view then sight=S.sensitivity(view,fov) end
    return S.multiplier(aiming,sight,s or S.spec())
end

-- The current options {mouse=,aim=,bySight=}; TW_Sensitivity.lua keeps them here (S.setOptions).
function S.spec() return spec(S.optionValues) end
function S.setOptions(s) S.optionValues=spec(s);return S.optionValues end

return S
