-- Utility operations use the ordinary action queue and authored animation events.
-- Pose/hand ownership stays with the same public path as magazine reloads.
local R=require('TW/Runtime')
local Data=require('TW/WeaponInspectData')
local Anim=require('TW/AnimOptOut') -- W8 animopt: the two "vanilla animations" options
local M={active=setmetatable({},{__mode='k'}),sounds={},completed=0,cancelled=0,errors=0}
TWWeaponInspect=M

function M.profile(gun)
    local key=gun and R.key(gun)
    return key and Data.catalog[key],key
end

function M.source(gun,kind,mode)
    local profile,key=M.profile(gun)
    if not profile then return nil,'foreign weapon' end
    if kind=='ModeCheck' and profile.modeCount<2 then return nil,'SINGLE_MODE' end
    local status=kind=='Chamber' and profile.chamber or kind=='Switch' and profile.switch or profile.inspect
    if status~='SUPPORTED' then return nil,status end
    local action=kind
    if kind=='Switch' then
        local lower=tostring(mode):lower()
        local index=lower:find('auto',1,true) and 0 or lower:find('burst',1,true) and 3 or 1
        action=action..tostring(index)
        if not Data.actions[profile.family..':'..action] then action='Switch0' end
    end
    local id=profile.family..':'..action
    return Data.actions[id],id,key
end

function M.notice(player,text)
    if not (player and player:isLocalPlayer()) then return end
    local notice=rawget(_G,'TWSightNotice')
    if not (notice and notice(player,text,'utility')) and HaloTextHelper then HaloTextHelper.addText(player,text) end
end

function M.chamber(gun)
    if not gun:isRoundChambered() then return 'IGUI_TW_ChamberEmpty' end
    -- Gunworks keeps the chamber last, including a mixed magazine. The selected
    -- ammo enum alone does not prove which cartridge is actually chambered.
    local list=gun:getModData().AmmoList
    local full=type(list)=='table' and list[#list] or nil
    if type(full)=='string' and full~='' then return 'IGUI_TW_ChamberRound',getItemNameFromFullType(full) end
    return 'IGUI_TW_ChamberUnknown'
end

function M.beginContact(action)
    if not (TWZB and TWZB.gripIKMag) then return end
    local profile=M.profile(action.gun)
    local isMode=action.utility=='Switch'
    if not (profile and (isMode or action.utility=='Chamber')) then return end
    local target=isMode and profile.selector or profile.handle
    local reference=isMode and profile.referenceSelector or profile.referenceHandle
    local targets=require('TW/GripTargets').forGun(action.gun,action.character:isFemale())
    local key=targets and targets.refClass..':'..(action.character:isFemale() and 'F' or 'M')
    local contact=key and action.source.contacts and action.source.contacts[key]
    if not (target and reference and contact) then return end
    local values={3}
    for _,v in ipairs(target) do values[#values+1]=v end
    for _,v in ipairs(reference) do values[#values+1]=v end
    for _,v in ipairs(contact.grab) do values[#values+1]=v end
    for _,v in ipairs(contact.pulled) do values[#values+1]=v end
    if contact.hand=='R' then
        values[#values+1]=1
        for _,v in ipairs(contact.grip) do values[#values+1]=v end
    end
    -- The gesture (no body clip) drives the hand by its own weight (GripIK: an 18th value).
    if action.gesture and contact.hand=='R' then values[#values+1]=action.weight or 0 end
    TWZB.gripIKMag(action.character,values)
    action.twUtilityContact=true
end

function M.endContact(action)
    if action.twUtilityContact and TWZB and TWZB.gripIKMag then TWZB.gripIKMag(action.character,nil) end
    action.twUtilityContact=nil
end

function M.play(action,parameter)
    local player=action.character
    if not player or not player:isLocalPlayer() or isServer() then return end
    local id,index,event=tostring(parameter):match('^([^|]+)|(%d+)|([^|]+)$')
    index=tonumber(index)
    local row=index and action.source.sounds[index]
    if id~=action.actionId or not row or row.event~=event or action.played[index] then return end
    local sounds=Data.sounds[action.twUtilityKey]
    local name=sounds and sounds[event]
    if not name then return end
    -- The node carries its action identity and event index: a blended-out tail
    -- cannot consume the next operation's sound, even after a long render stall.
    action.played[index]=true
    local handle=player:playSound(name)
    local emitter=player:getEmitter()
    M.sounds[#M.sounds+1]={name=name,handle=handle,playing=emitter and emitter:isPlaying(name),
        event=event,action=action.utility,at=getTimestampMs()}
    if #M.sounds>64 then table.remove(M.sounds,1) end
    return handle
end

-- The fire-mode switch is a gesture over the current hold, not a timed action (FB4-A, user report 19.10.2026,
-- items 13 and 19): no body clip plays, the stance (aim, low/high ready, patrol) and ADS stay; the selector sound
-- and the Gunworks mode come at Tarkov's times; the right hand reaches the selector by GripIK (the source clip's
-- reach is its weight) and Viewpoint plays the same Tarkov track on the gesture's clock (FpPose). While it runs,
-- TWWeaponUtility names it: fire is blocked (WeaponUtilityGate) and the pose layer keeps ADS. It ends at the clip's
-- length; a timed action, another weapon, death, a run or a changed view clears it, and TW_FireModeSwitch's watchdog
-- clears one whose update stopped. Every press is one mode step: a press during a gesture applies its pending step at
-- once and restarts it where the hand's reach is the same. Observers in MP see no gesture; Gunworks syncs the mode.
M.gestures=setmetatable({},{__mode='k'})
M.gestureCount={started=0,restarted=0,committed=0,finished=0,cleared=0}
-- Gesture time per tick at most (a stalled frame is not the hand's motion), and the watchdog's grace past the clip.
M.gestureStepCap=0.1
M.gestureGrace=0.5
local gestureToken=0

local function reachAt(source,u)
    local r=source.reach
    if not r or #r<2 then return 0 end
    local x=math.max(0,math.min(1,u))*(#r-1)
    local i=math.floor(x)
    local a,b=r[i+1],r[math.min(#r,i+2)]
    return a+(b-a)*(x-i)
end
M.reachAt=reachAt

-- The fraction on the rising side of the clip (up to the selector) where the hand's reach is w.
local function risingAt(source,w)
    local r=source.reach
    if not r or #r<2 or w<=0 then return 0 end
    local n=#r
    for i=1,n-1 do
        local ua,ub=(i-1)/(n-1),i/(n-1)
        if ua>=source.commitAt then break end
        if r[i+1]>=w and r[i+1]>r[i] then
            return math.min(source.commitAt,ua+(ub-ua)*math.max(0,w-r[i])/(r[i+1]-r[i]))
        end
    end
    return source.commitAt
end
M.risingAt=risingAt

-- A gesture cannot run now (a reason) or can (nil): the old timed action's checks, the sprint rule, the same view.
function M.gestureBlocked(player,gun,view)
    if not player or player:isDead() then return 'dead' end
    if not player:isLocalPlayer() then return 'remote' end
    if not gun or player:getPrimaryHandItem()~=gun then return 'weapon' end
    if M.active[player] or not player:getCharacterActions():isEmpty() then return 'action' end
    if player:isRunning() or player:isSprinting() then return 'run' end
    if view~=nil and M.view()~=view then return 'view' end
end

function M.view()
    local zb=rawget(_G,'TWZB')
    local ok,state=pcall(function() return zb and zb.vpState and zb.vpState() or 0 end)
    return ok and state or 0
end

-- The FP pose track's clip name (FpPose aliases): the family's clip on the weapon's reference class and body.
local function gestureClip(player,gun,id)
    local targets=require('TW/GripTargets').forGun(gun,player:isFemale())
    return targets and 'TWInspect_'..id:gsub(':','_')..'_'..targets.refClass..(player:isFemale() and '_F' or '')
end

-- The pending mode step of a gesture, now (the selector sound with it, once).
function M.commitGesture(g)
    if g.committed then return end
    g.committed=true
    for i,row in ipairs(g.source.sounds) do
        if row.event=='SndSelector' and not g.played[i] then M.play(g,g.actionId..'|'..i..'|'..row.event) end
    end
    if M.applyMode and g.character:getPrimaryHandItem()==g.gun then
        g.applied=M.applyMode(g.character,g.gun,g.mode,g.rpmStage)==true
    end
    M.gestureCount.committed=M.gestureCount.committed+1
    if triggerEvent then pcall(triggerEvent,'OnTWFireModeCommit',g.character,g.gun,g.mode) end
end

-- Apply a running gesture's pending step at once (a press during it; the hand goes on).
function M.flushGesture(player)
    local g=player and M.gestures[player]
    if g and not g.committed then M.commitGesture(g) end
end

function M.clearGesture(player,reason)
    local g=M.gestures[player]
    if not g then return end
    M.gestures[player]=nil
    if (player:getVariableString('TWWeaponUtility') or '')==g.actionId then player:clearVariable('TWWeaponUtility') end
    M.endContact(g)
    if TWZB and TWZB.fpPoseGesture then pcall(TWZB.fpPoseGesture,player,nil,0,0) end
    if reason=='done' then M.gestureCount.finished=M.gestureCount.finished+1
    else
        M.gestureCount.cleared=M.gestureCount.cleared+1
        print('[TW] fire-mode gesture cleared ('..tostring(reason)..'): '..g.actionId..(g.committed and ' after' or ' before')..' the selector')
    end
end

function M.startGesture(player,gun,source,id,key,mode,rpmStage)
    local old=M.gestures[player]
    local weight=0
    if old then
        M.flushGesture(player)
        weight=old.weight or 0
        M.gestureCount.restarted=M.gestureCount.restarted+1
        M.endContact(old)
    end
    gestureToken=gestureToken+1
    local u0=risingAt(source,weight)
    local g={character=player,gun=gun,source=source,actionId=id,twUtilityKey=key,mode=mode,rpmStage=rpmStage,
        utility='Switch',gesture=true,played={},t=0,u0=u0,u=u0,weight=reachAt(source,u0),committed=false,
        view=M.view(),token=gestureToken,clip=gestureClip(player,gun,id),updatedAt=getTimestampMs()}
    -- Sounds before the restart point belong to the hand's way already made.
    for i,row in ipairs(source.sounds) do if row.at<u0 then g.played[i]=true end end
    M.gestures[player]=g
    player:setVariable('TWWeaponUtility',id)
    M.beginContact(g)
    M.gestureCount.started=M.gestureCount.started+1
    return true,g
end

-- One tick of a running gesture (client): time, sounds, the selector, the hand's weight, the FP track, the end.
function M.updateGesture(player,dt)
    local g=M.gestures[player]
    if not g then return end
    local reason=M.gestureBlocked(player,g.gun,g.view)
    if reason then M.clearGesture(player,reason);return end
    g.t=g.t+math.max(0,math.min(dt,M.gestureStepCap))
    g.u=math.min(1,g.u0+g.t/math.max(0.05,g.source.seconds))
    for i,row in ipairs(g.source.sounds) do
        if not g.played[i] and g.u>=row.at and not (row.event=='SndSelector' and g.committed) then
            M.play(g,g.actionId..'|'..i..'|'..row.event)
        end
    end
    if g.u>=g.source.commitAt then M.commitGesture(g) end
    g.weight=reachAt(g.source,g.u)
    -- W8 animopt (the user's item 9): the hand on the selector and the first-person gesture are the mod's own hand
    -- animation; the selector itself (TWWeaponUtility, the fire gate and the sounds) keeps working.
    if not Anim.blocks() then
        if TWZB and TWZB.gripIKMagWeight then TWZB.gripIKMagWeight(player,g.weight) end
        if TWZB and TWZB.fpPoseGesture and g.clip then TWZB.fpPoseGesture(player,g.clip,g.u,g.token) end
    end
    g.updatedAt=getTimestampMs()
    g.missed=0
    if g.u>=1 then M.clearGesture(player,'done') end
end

function M.request(player,gun,kind,mode,rpmStage)
    if not player or not gun or player:getPrimaryHandItem()~=gun or player:isDead() then return false end
    if kind=='Switch' then
        if M.gestureBlocked(player,gun) then return false end
        local source,id,key=M.source(gun,kind,mode)
        if not source then
            if id=='UNSUPPORTED' then M.notice(player,getText('IGUI_TW_UtilityUnsupported')) end
            return false,id
        end
        return M.startGesture(player,gun,source,id,key,mode,rpmStage)
    end
    if M.active[player] or not player:getCharacterActions():isEmpty() then return false end
    local source,id,key=M.source(gun,kind,mode)
    if not source then
        if id=='UNSUPPORTED' then M.notice(player,getText('IGUI_TW_UtilityUnsupported')) end
        return false,id
    end
    require('TimedActions/TWWeaponUtility')
    local action=TWWeaponUtility:new(player,gun,kind,mode,rpmStage)
    ISTimedActionQueue.add(action)
    return true,action
end

return M
