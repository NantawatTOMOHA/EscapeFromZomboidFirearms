-- Tarkov's mouse sensitivity in Viewpoint's first person (W10 user item 9, 27.10.2026: "add sensitivity
-- options and a separate sensitivity in the sight depending on magnification - if Tarkov already has it,
-- use its logic"). Read the formula and its source in TW/Sensitivity.lua.
--
-- Three options on the Viewpoint page, section Aiming:
--   Mouse sensitivity      multiplier of Viewpoint's first person look when not aiming (default 1: today's);
--   Aiming sensitivity     Tarkov's own aiming multiplier (default 1: Tarkov's own value);
--   Sight sensitivity by   on (default): the sight's AimSensitivity by its magnification, as in Tarkov;
--   magnification          off: one aiming value for every sight (the aiming multiplier alone).
-- With the first two at 1 and the switch off, every mouse delta is exactly today's (the Java part keeps the
-- untouched value). With the switch on (default) an iron sight or a collimator at 1x reads 1 or 0.65 of the
-- aim speed and a 4x prism 0.1138, a 3-24x optic from 0.1517 to 0.019 with the zoom - Tarkov's data.
--
-- One update per tick for the local player, only with one of the mod's weapons in hands and only with the
-- Java part: the factor goes to FpSensitivity, which scales the mouse delta where Viewpoint turns it into
-- yaw and pitch (its own mouse-speed setting is read once per read). Nothing of Viewpoint is stored, no
-- setting, item or save field is written, and no network message is sent: the look is local. The isometric
-- view, the third person and other players are not touched; without Viewpoint the factor is never read.
require('TW_Tactical')
local R=require('TW/Runtime')
local Sight=require('TW/SightState')
local S=require('TW/Sensitivity')
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
options:addSlider('MouseSensitivity',getText('UI_TW_MouseSensitivity'),.1,3,.05,1,getText('UI_TW_MouseSensitivity_tooltip'))
options:addSlider('AimingSensitivity',getText('UI_TW_AimingSensitivity'),.1,3,.05,1,getText('UI_TW_AimingSensitivity_tooltip'))
options:addTickBox('SightSensitivityByZoom',getText('UI_TW_SightSensitivityByZoom'),true,
    getText('UI_TW_SightSensitivityByZoom_tooltip'))

local M={sent={},errors=0,lastError='',last={}}
TW_Sensitivity=M

local function slider(id,fallback)
    local ok,v=pcall(function() return tonumber(options:getOption(id):getValue()) end)
    if not ok or not v or v~=v or v<0 then return fallback end
    return v
end

local function enabled(id,fallback)
    local ok,v=pcall(function() return options:getOption(id):getValue() end)
    if not ok or v==nil then return fallback end
    return v~=false
end

function M.mouse() return slider('MouseSensitivity',1) end
function M.aiming() return slider('AimingSensitivity',1) end
function M.bySight() return enabled('SightSensitivityByZoom',true) end
function M.options() return {mouse=M.mouse(),aim=M.aiming(),bySight=M.bySight()} end

local function off(p)
    if M.sent[p] then M.sent[p]=nil;M.last[p]=nil;if TWZB and TWZB.sensOff then TWZB.sensOff(p) end end
end

-- The multiplier now: the hip option, or the aim option times the sight's own value (Tarkov's formula).
function M.factor(p,view,fov)
    view=view or Sight.current(p)
    return S.effective(p and p:isAiming() and true or false,view,fov,M.options())
end

-- One function for every tick, not a new closure (player report 23.10.2026).
local function state(p,gun)
    local view=Sight.current(p)
    local spec=M.options()
    S.setOptions(spec)
    local aiming=p:isAiming() and true or false
    local factor=S.effective(aiming,view)
    TWZB.sensState(p,gun,factor)
    M.sent[p]=true
    if M.trace then
        local sight,source=S.sensitivity(view)
        M.last[p]={factor=factor,aiming=aiming,sight=sight,source=source,fov=view and view.fov,
            magnification=S.magnification(view)}
    end
end

function M.update(p)
    -- Equip events also come for NPCs of other mods (A-Life shells are IsoZombies): players only.
    if not (p and instanceof(p,'IsoPlayer') and p:isLocalPlayer()) then return end
    local gun=p:getPrimaryHandItem()
    if p:isDead() or not (gun and R.key(gun)) then off(p);return end
    local ok,err=pcall(state,p,gun)
    if not ok then M.errors=M.errors+1;M.lastError=tostring(err);off(p) end
end

local function available()
    return TWZB and TWZB.sensState and TWZB.sensOff
end

Events.OnTickEvenPaused.Add(function()
    if not available() then return end
    for i=0,getNumActivePlayers()-1 do M.update(getSpecificPlayer(i)) end
end)
Events.OnPlayerDeath.Add(function(p) if TWZB and TWZB.sensOff then off(p) end end)
Events.OnEquipPrimary.Add(function(p) if available() then M.update(p) end end)

-- QA (TW_Sensitivity QA cases and the report): the Java layer's counters and one player's row.
function M.stats()
    return TWZB and TWZB.sensStats and TWZB.sensStats() or 'Java part absent'
end

function M.describe(p)
    return TWZB and TWZB.sensOf and TWZB.sensOf(p or getSpecificPlayer(0)) or nil
end

function M.row(p)
    return M.last and M.last[p or getSpecificPlayer(0)] or nil
end

return M
