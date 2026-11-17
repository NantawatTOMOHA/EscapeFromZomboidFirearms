require('TimedActions/ISBaseTimedAction')
local M=require('TW/WeaponInspect')
TWWeaponUtility=ISBaseTimedAction:derive('TWWeaponUtility')

function TWWeaponUtility:isValid()
    return self.source~=nil and self.character:getPrimaryHandItem()==self.gun and not self.character:isDead()
end

function TWWeaponUtility:start()
    if not self:isValid() then self:forceStop();return end
    self.startedAt=getTimestampMs()
    self.progressAt=self.startedAt
    self.progressFraction=nil
    self.progressTrack=nil
    self.watchUpdateAt=nil
    self.played={}
    M.active[self.character]=self
    self:setAnimVariable('TWWeaponUtility',self.actionId)
    self:setAnimVariable('isLoading',true)
    self:setActionAnim(CharacterActionAnims.Reload)
    self:setOverrideHandModels(self.gun,nil)
    M.beginContact(self)
end

function TWWeaponUtility:update()
    -- Missing/stale animation data is a cancelled utility, never a successful
    -- chamber check or a synthetic selector click.
    if not self.startedAt or isClient() or self.twUtilityDone then return end
    local now=getTimestampMs()
    local expired
    if TWReloadVisualNative and TWReloadVisualNative.snapshot then
        -- A render stall or a paused window is not elapsed animation time.
        -- Observe the actual track; a stopped/missing track still cancels,
        -- and only its authored TWUtilityDone event can complete this action.
        if self.watchUpdateAt and now-self.watchUpdateAt>500 then self.progressAt=now end
        self.watchUpdateAt=now
        local state=TWReloadVisualNative.snapshot(self.character,'')
        local fraction=state and tonumber(state.fraction)
        if state and state.track and state.track:sub(1,#self.clipPrefix)==self.clipPrefix and fraction then
            if self.progressTrack~=state.track or not self.progressFraction or fraction>self.progressFraction+0.000001 then
                self.progressAt=now
                self.progressTrack=state.track
                self.progressFraction=fraction
            end
        end
        expired=now-self.progressAt>1500
    else
        expired=now-self.startedAt>self.source.seconds*1000+1500
    end
    if expired then
        M.errors=M.errors+1
        print('[TW] utility animation did not finish: '..self.actionId)
        self:forceStop()
    end
end

function TWWeaponUtility:serverStart()
    emulateAnimEventOnce(self.netAction,math.ceil(self.source.seconds*1000),'TWUtilityDone',self.actionId)
end

function TWWeaponUtility:animEvent(event,parameter)
    if event=='TWUtilitySound' then M.play(self,parameter)
    elseif event=='TWUtilityCommit' and parameter==self.actionId and self.utility=='Switch' and not self.committed then
        if self:isValid() and M.applyMode and self.character:isLocalPlayer() then
            self.committed=M.applyMode(self.character,self.gun,self.mode,self.rpmStage)==true
        end
    elseif event=='TWUtilityDone' and parameter==self.actionId then
        self.twUtilityDone=true
        if isServer() then self.netAction:forceComplete()
        elseif not isClient() then self:forceComplete() end
    end
end

function TWWeaponUtility:getDuration() return -1 end
function TWWeaponUtility:complete() return true end

local function release(self)
    if M.active[self.character]~=self then return end
    M.active[self.character]=nil
    M.endContact(self)
    self.character:clearVariable('TWWeaponUtility')
    self.character:clearVariable('isLoading')
end

function TWWeaponUtility:stop()
    release(self)
    M.cancelled=M.cancelled+1
    ISBaseTimedAction.stop(self)
end

function TWWeaponUtility:perform()
    release(self)
    if self.twUtilityDone then
        M.completed=M.completed+1
        if self.utility=='Chamber' then
            local key,arg=M.chamber(self.gun)
            M.notice(self.character,arg and getText(key,arg) or getText(key))
        end
    end
    ISBaseTimedAction.perform(self)
end

-- Multiplayer (B42): the server builds its copy of the queued action by calling new() with the
-- client action's fields named as new()'s parameters (NetTimedAction), so each parameter is kept
-- under its own name. The clip data (TW/WeaponInspectData, shared) is resolved here from the
-- weapon: only the weapon, the kind and the mode cross the network. Before, kind/id/key were kept
-- as utility/actionId/twUtilityKey, reached the server as nil and its new() failed on id:gsub.
function TWWeaponUtility:new(character,gun,kind,mode,rpmStage)
    local o=ISBaseTimedAction.new(self,character)
    local source,id,key=M.source(gun,kind,mode)
    o.gun,o.kind,o.mode,o.rpmStage=gun,kind,mode,rpmStage
    o.utility,o.source,o.actionId,o.twUtilityKey=kind,source,id,key
    o.clipPrefix='TWInspect_'..tostring(id):gsub(':','_')..'_'
    o.stopOnWalk=true
    o.stopOnRun=true
    o.stopOnAim=false
    o.useProgressBar=false
    o.maxTime=o:getDuration()
    return o
end
