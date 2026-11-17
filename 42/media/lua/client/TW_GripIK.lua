-- Runtime grip transfer: every tick, each character holding an own weapon (local
-- players and remote ones) hands its targets (TW/GripTargets) to the Java part
-- (GripIK.java through ZombieBuddy), which moves the weapon and both arms from the
-- reference grip class onto the weapon's own geometry after the clips blended.
local Targets=require('TW/GripTargets')
local Anim=require('TW/AnimOptOut') -- W8 animopt: the two "vanilla animations" options
local okR,R=pcall(require,'TW/Runtime')
local okL,Launchers=pcall(require,'TW/Data/Launchers')
R=okR and R or nil
Launchers=okL and Launchers or nil

local M={sent={},count=0}
TWGripIK=M
local FLOOR_ZERO={}

-- Local players: does a timed action run (reload, rack, equip)? Then the Java part lets
-- the support hand leave the weapon as the clip does; otherwise the hand stays on it
-- through the blends between poses. Remote players: unknown, the hand follows the clip.
local function acting(p)
    if not p:isLocalPlayer() then return true end
    if ISTimedActionQueue and ISTimedActionQueue.isPlayerDoingAction and ISTimedActionQueue.isPlayerDoingAction(p) then return true end
    local actions=p:getCharacterActions()
    return actions~=nil and not actions:isEmpty()
end

local function update(p)
    -- W8 animopt (the user's item 9): the hand IK on the grips and the weapon transfer belong to vanilla and to
    -- other animation mods in a view whose option is on; the request goes away at once (no stale transfer). A remote
    -- character is always drawn in the third person, so the third-person option alone governs it.
    -- W10 prone / proneback: a per-character yield (Hardship Inertia floor postures) keeps the weapon's grip data
    -- current so GripIK.seatYielded can orient Bip01_Prop1 in the clip's right hand on prone/fours/crawl and hold
    -- the weapon on the chest with the right hand on its grip when lying on the back.
    local yielded=not Anim.poses(p)
    if yielded and (Anim.optionsBlock(p) or not Anim.yielded(p)) then
        if M.sent[p] then TWZB.gripIKOff(p);M.sent[p]=nil;M.count=M.count-1 end
        return
    end
    -- W9-advmove: Viewpoint Advanced Movement lays its own prone / climb-up / hang pose on the drawn body and
    -- keeps the gun in the hand itself (ClimbPose.keepProps); our grip transfer would put the standing Tarkov
    -- grip over a lying or climbing body. The local player lying still and aiming keeps it: our first-person pose
    -- owns the arms then (TWAdvMove.yielding answers exactly that; TW_CompatAdvancedMovement.lua).
    local adv=rawget(_G,'TWAdvMove')
    if adv and adv.yielding and adv.yielding(p) then
        if M.sent[p] then TWZB.gripIKOff(p);M.sent[p]=nil;M.count=M.count-1 end
        return
    end
    local gun=p:getPrimaryHandItem()
    local sec=p:getSecondaryHandItem()
    local loading=rawget(_G,'TWMagazineLoading')
    local action=p:getVariableString('PerformingAction')
    local released=(loading and loading.isLoading and loading.isLoading(p)) or action=='InsertBullets' or action=='RemoveBullets'
    local item=gun
    local v=not released and gun and Targets.forGun(gun,p:isFemale())
    -- W10 torchpistol (item 29): passive carry of a one-handed mod firearm in SecondaryHandItem (e.g. Torch in primary).
    if not v and not released and sec and sec~=gun then
        v=Targets.forGun(sec,p:isFemale())
        if v then item=sec end
    end
    -- FB5 (the user's item 2): the stance's body clip stays through the mod's own weapon actions (TWCarryActionStance)
    local patrol=not yielded and item==gun and p:getVariableString('TWCarryMode')=='patrol' and (p:getVariableBoolean('TWCarryActive') or p:getVariableBoolean('TWCarryActionStance'))
    local params=v and Targets.carryParams(v,patrol) or nil
    if not params and yielded and not released and gun then
        local key=(R and R.visualKey(gun)) or (Launchers and Launchers.disposable and gun:getFullType()==Launchers.disposable.spent and 'RSHG2' or nil)
        if key then params=FLOOR_ZERO end
    end
    if params then
        TWZB.gripIK(p,item,params)
        if TWZB.gripIKActing then TWZB.gripIKActing(p,acting(p)) end
        if not M.sent[p] then M.sent[p]=true;M.count=M.count+1 end
    elseif M.sent[p] then
        TWZB.gripIKOff(p)
        M.sent[p]=nil;M.count=M.count-1
    end
end
M.update=update

-- Supply the new item before its first animation evaluation, rather than waiting
-- for the following tick with the previous item's request still installed.
-- Equip events also come for NPCs of other mods (A-Life shells are IsoZombies): players only.
Events.OnEquipPrimary.Add(function(p) if p and TWZB and instanceof(p,'IsoPlayer') then update(p) end end)
if Events.OnEquipSecondary then
    Events.OnEquipSecondary.Add(function(p) if p and TWZB and instanceof(p,'IsoPlayer') then update(p) end end)
end

-- A player no longer updated (left the game, or a dead body replaced by a new character) keeps no
-- request: the Java map would hold its whole character for the session and keep every zombie's
-- animation update looking it up (performance pass 23.10.2026). Local players are updated once.
local updated={}
Events.OnTick.Add(function()
    if not TWZB then return end
    for i=0,getNumActivePlayers()-1 do
        local p=getSpecificPlayer(i)
        if p then update(p);updated[p]=true end
    end
    local online=getOnlinePlayers()
    if online then
        for i=0,online:size()-1 do
            local p=online:get(i)
            if p and not updated[p] then update(p);updated[p]=true end
        end
    end
    for p in pairs(M.sent) do
        if not updated[p] then TWZB.gripIKOff(p);M.sent[p]=nil;M.count=M.count-1 end
    end
    for p in pairs(updated) do updated[p]=nil end
end)

-- QA: the Java transfer can be switched off to compare with the bare reference clips.
function M.setEnabled(on)
    if TWZB then TWZB.gripIKEnable(on and true or false) end
end

function M.stats()
    return TWZB and TWZB.gripIKStats() or 'Java part absent'
end

return M
