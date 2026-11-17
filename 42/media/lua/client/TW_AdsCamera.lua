-- Tarkov's main ADS camera (FpAdsCamera.java): user vFOV - 15 degrees for irons/collimators,
-- 35 degrees for an OpticSight. The selected source component, not magnification or image
-- kind, decides this: optical 1x and thermal reflex displays can still be OpticSight.
-- FpPose owns the ADS clock and eye alignment; no pose, scope FOV, settings or modData writes.
require('TW_Tactical')
local R=require('TW/Runtime')
local Sight=require('TW/SightState')
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
options:addTickBox('AdsCamera',getText('UI_TW_AdsCamera'),true,getText('UI_TW_AdsCamera_tooltip'))
-- FB2 (17.10.2026): share of Tarkov's narrowing (item 9, default a quarter) and no crosshair dot while aiming (item 3).
-- FB3 (18.10.2026, item 3): the share in percent, separately for irons/collimators and optical sights
-- (it was one 0..1 slider 'AdsCameraNarrowing'; TW_OptionsLayout carries a saved value over once).
-- W11 (item 16): irons/collimators scale widened to 50 deg (floored at 35 deg) with default 30 % (= 15 deg, the former 100 %); optics stay at 25 %.
options:addSlider('AdsNarrowIron',getText('UI_TW_AdsNarrowIron'),0,100,5,30)
options:addSlider('AdsNarrowOptic',getText('UI_TW_AdsNarrowOptic'),0,100,5,25)
options:addTickBox('AdsHideCrosshair',getText('UI_TW_AdsHideCrosshair'),true,getText('UI_TW_AdsHideCrosshair_tooltip'))

local M={sent={},errors=0,lastError=''}
TWAdsCamera=M

function M.optical(view)
    if not view then return nil end
    local source=view.modeProfile and view.modeProfile.sourceKind
    if source=='optic' then return true end
    if source=='collimator' or source=='camera_plane' or source=='iron_or_camera_plane'
        or view.kind=='iron' or view.kind=='aim' or view.kind=='collimator' then return false end
    return nil -- Unknown source: preserve Viewpoint's FOV.
end

function M.enabled()
    return options:getOption('AdsCamera'):getValue()~=false
end

local function off(p)
    if M.sent[p] then TWZB.adsCameraOff(p);M.sent[p]=nil end
end

-- One function for every tick, not a new closure (player report 23.10.2026).
local function state(p,gun)
    local view=Sight.current(p)
    local optical=M.optical(view)
    if optical==nil then off(p);return end
    TWZB.adsCameraState(p,gun,optical,true,isGamePaused()==true)
    M.sent[p]=true
end

function M.update(p)
    -- Equip events also come for NPCs of other mods (A-Life shells are IsoZombies): players only.
    if not (p and instanceof(p,'IsoPlayer') and p:isLocalPlayer()) then return end
    local gun=p:getPrimaryHandItem()
    if p:isDead() or not (gun and R.key(gun)) or not M.enabled() then off(p);return end
    local ok,err=pcall(state,p,gun)
    if not ok then M.errors=M.errors+1;M.lastError=tostring(err);off(p) end
end

local function share(id,def)
    local v=tonumber(options:getOption(id):getValue())
    return v and math.max(0,math.min(1,v/100)) or (def or 0.25)
end

-- Shares 0..1 of the ADS narrowing: irons and collimators (default 0.30), optical sights (default 0.25).
function M.narrowing()
    return share('AdsNarrowIron',0.30),share('AdsNarrowOptic',0.25)
end

function M.hideCrosshair()
    return options:getOption('AdsHideCrosshair'):getValue()~=false
end

Events.OnTickEvenPaused.Add(function()
    if not (TWZB and TWZB.adsCameraState and TWZB.adsCameraOff) then return end
    if TWZB.adsCameraShares then
        local iron,optic=M.narrowing()
        TWZB.adsCameraShares(iron,optic,M.hideCrosshair())
    elseif TWZB.adsCameraOptions then TWZB.adsCameraOptions((M.narrowing()),M.hideCrosshair()) end
    for i=0,getNumActivePlayers()-1 do M.update(getSpecificPlayer(i)) end
end)
Events.OnPlayerDeath.Add(function(p) if TWZB and TWZB.adsCameraOff then off(p) end end)
Events.OnEquipPrimary.Add(function(p) if TWZB and TWZB.adsCameraState and TWZB.adsCameraOff then M.update(p) end end)

function M.stats()
    return TWZB and TWZB.adsCameraStats and TWZB.adsCameraStats() or 'Java part absent'
end

function M.describe(p)
    return TWZB and TWZB.adsCameraOf and TWZB.adsCameraOf(p or getSpecificPlayer(0)) or nil
end

return M
