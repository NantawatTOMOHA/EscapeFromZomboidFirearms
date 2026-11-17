-- Defer the public Gunworks mode operation to the source selector event. Its
-- existing API retains rate, instance persistence and server authority.
local R=require('TW/Runtime')
local M=require('TW/WeaponInspect')
local Modes=require('WeaponSystems/ISUI/RateOfFire_ISUI')
local Client=require('WeaponSystems/Client')
local NativeApply=Modes.ApplyFiremode
M.applyMode=function(player,gun,mode,rpmStage)
    M.committing=true
    local ok,result=pcall(NativeApply,player,gun,mode,rpmStage)
    M.committing=false
    if not ok then M.errors=M.errors+1;print('[TW] utility mode apply rejected: '..tostring(result));return false end
    return result
end

local function request(player,gun,mode,rpmStage)
    if not (gun and R.key(gun)) or M.committing then return NativeApply(player,gun,mode,rpmStage) end
    -- A press during a gesture: its pending step first, then this one (FB4-A: every press is one step).
    M.flushGesture(player)
    if not Modes.HasMultipleFiremodes(gun) then return false end
    if gun:getFireMode()==mode then return NativeApply(player,gun,mode,rpmStage) end
    if not M.source(gun,'Switch',mode) then
        M.notice(player,getText('IGUI_TW_UtilityUnsupported'))
        return NativeApply(player,gun,mode,rpmStage)
    end
    local ok,action=M.request(player,gun,'Switch',mode,rpmStage)
    if ok then local note=rawget(_G,'TWFireModeNotice');if note then note(player,gun,mode,rpmStage) end end
    return ok,action
end
Modes.ApplyFiremode=request
-- Gunworks' fire-mode key steps from the current mode: a pending step of a running gesture is applied first, so
-- rapid presses each advance the mode once (FB4-A, user report 19.10.2026, item 22 with FB4-B).
local NativeCycle=Modes.CycleFiremode
Modes.CycleFiremode=function(player,gun,...)
    if gun and R.key(gun) then M.flushGesture(player) end
    return NativeCycle(player,gun,...)
end

-- Vanilla's fire-mode key first selects a standard mode; Gunworks calls this
-- public normalization hook before sending its authoritative mode command.
local nativeCheck=Client.FiremodeSwitchCheck
local seen=setmetatable({},{__mode='k'})
local function remember(p)
    local gun=p and p:getPrimaryHandItem()
    if gun and R.key(gun) and not Client.isFiremodeStandard(gun:getFireMode()) then seen[gun]=gun:getFireMode() end
end
Client.FiremodeSwitchCheck=function(player,gun,...)
    if gun and R.key(gun) and Client.isFiremodeStandard(gun:getFireMode()) and seen[gun] and not M.committing then
        local target=Modes.NormalizeFiremode(gun:getFireMode(),gun)
        gun:setFireMode(seen[gun]);player:setFireMode(seen[gun])
        return request(player,gun,target)
    end
    local result=nativeCheck(player,gun,...)
    remember(player)
    return result
end
Events.OnEquipPrimary.Add(remember)
Events.OnPlayerUpdate.Add(remember)
Events.OnWeaponSwing.Remove(nativeCheck)
Events.OnWeaponSwing.Add(Client.FiremodeSwitchCheck)

function M.switch(player)
    local gun=player and player:getPrimaryHandItem()
    if not gun or not R.key(gun) or not Modes.HasMultipleFiremodes(gun) then return false end
    M.flushGesture(player)
    -- Step to the entry after the current one (wrapping), so a three-position
    -- selector reaches every position instead of toggling the first two.
    local entries=Modes.GetFiremodeEntries(gun) or {}
    local choice
    for i,entry in ipairs(entries) do
        if entry.isCurrent then choice=entries[i%#entries+1];break end
    end
    choice=choice or (Modes.GetSelectableFiremodeEntries(gun) or {})[1]
    if not choice or choice.isCurrent then return false end
    return request(player,gun,choice.mode,choice.rpmStage)
end

-- The fire-mode gesture's clock (TW/WeaponInspect): every tick for each local player running one. An error clears
-- it (never left set), and the watchdog below clears one whose update stopped or ran past the clip.
if LuaEventManager and LuaEventManager.AddEvent then LuaEventManager.AddEvent('OnTWFireModeCommit') end
local lastTick
Events.OnTick.Add(function()
    local now=getTimestampMs()
    local dt=lastTick and (now-lastTick)/1000 or 0
    lastTick=now
    for player,_ in pairs(M.gestures) do
        local ok,err=pcall(M.updateGesture,player,dt)
        if not ok then
            M.errors=M.errors+1
            print('[TW] fire-mode gesture error: '..tostring(err))
            pcall(M.clearGesture,player,'error')
            M.gestures[player]=nil
        end
    end
end)
-- The watchdog keeps its own clock - a player update counts at most gestureStepCap, so one long frame (an autosave,
-- a 4K screenshot) is not the clip running out - and counts the player updates the gesture missed: past its clip
-- (+ gestureGrace) or WATCH_FRAMES updates without its own, it is cleared. (A wall-clock "1 s without an update"
-- cleared a live gesture after one stall, before the selector: the press was lost - in-game QA 20.10.)
local WATCH_FRAMES=30
Events.OnPlayerUpdate.Add(function(player)
    local g=M.gestures[player]
    if not g then return end
    local now=getTimestampMs()
    g.watchT=(g.watchT or 0)+math.max(0,math.min((now-(g.watchAt or now))/1000,M.gestureStepCap))
    g.watchAt=now
    g.missed=(g.missed or 0)+1
    if g.watchT>g.source.seconds+M.gestureGrace or g.missed>WATCH_FRAMES then
        pcall(M.clearGesture,player,'watchdog')
        M.gestures[player]=nil
        if (player:getVariableString('TWWeaponUtility') or '')==g.actionId then player:clearVariable('TWWeaponUtility') end
    end
end)
-- Never kept across a load: a fresh player carries no gesture or its name.
Events.OnCreatePlayer.Add(function(_,player)
    if player and not M.gestures[player] and (player:getVariableString('TWWeaponUtility') or ''):find(':Switch',1,true) then
        player:clearVariable('TWWeaponUtility')
    end
end)

return M
