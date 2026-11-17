-- Reload with the magazine in the support hand (tools/reload_clips.py clips, TW/ReloadData,
-- TW/ReloadVisual, GripIK.java mode 2).
--
-- Eject (ISEjectMagazine): the clip's hand grips the magazine in the well (event TWMagGrab):
-- the weapon is drawn without its magazine and the action's secondary hand model shows it in
-- the hand instead; the hand stows it in the pouch (TWMagStow: hidden). Insert
-- (ISInsertMagazine): the hand draws the new magazine (TWMagDraw: shown), seats it (TWMagSeat:
-- hidden in the hand, drawn on the weapon until the part itself is installed at loadFinished).
-- The Java part places the magazine bone and the hand on this weapon's magazine well.
-- Quick reload (TWDropMagazine, an ISEjectMagazine): the magazine falls out of the well - shown
-- falling from TWMagGrab, hidden at TWMagRelease; the floor item appears at unloadFinished.
-- Magazine check (TWCheckMagazine): out of the well in the hand at TWMagGrab, back at TWMagSeat,
-- with the weapon's magazine sounds at the node's playReloadSound events.
-- Local players only: a remote player's action does not run here (the clips still play).
local C=require('TW/Core')
local D=C.data
local R=require('TW/Runtime')
local Render=require('TW/Render')
local RV=require('TW/ReloadVisual')
local Data=require('TW/ReloadData')
local Targets=require('TW/GripTargets')
local Anim=require('TW/AnimOptOut') -- W8 animopt: the two "vanilla animations" options

local M={started=0,events={}}
local InspectData
local function inspectData() InspectData=InspectData or require('TW/WeaponInspectData');return InspectData end
M.observers=setmetatable({},{__mode='k'})
TWReloadVisual=M
-- Each action takes only its own clip's events: when a lag spike ends the eject on its last
-- frames, the blended-out tail of its clip still fires TWMagStow - delivered to the insert that
-- has just started, it hid the new magazine (game sheets 2026-10-15).
local OWN={eject={TWMagGrab=true,TWMagStow=true,TWMagRelease=true},insert={TWMagDraw=true,TWMagSeat=true},
    check={TWMagGrab=true,TWMagSeat=true}}

-- The grip and slap points of a magazine box (Data.rule; tools/reload_clips.py grip_points).
local function points(box,pistol)
    local cm,rule=Data.cm,Data.rule
    local lo,hi=box.min,box.max
    local mid,bottom=(lo[2]+hi[2])/2,lo[3]
    local g=pistol and {hi[1]+rule.pistol[1]*cm,mid+rule.pistol[2]*cm,bottom+rule.pistol[3]*cm}
        or {hi[1]+rule.grip[1]*cm,mid+rule.grip[2]*cm,bottom*rule.grip[3]}
    return g,{hi[1]+rule.slap[1]*cm,mid+rule.slap[2]*cm,bottom+rule.slap[3]*cm}
end

-- GripIK mode 2: {2, own magazine frame (12), reference frame (12), grip (3), slap (3), grip
-- shift (3), slap shift (3), carry push (1)[, anchored check (1)]} - the shifts move the hand onto
-- this magazine's own 3D body target (Data.magTargets; tools/reload_clips.py mag_body_target).
local function frames(player,gun,tpl,isCheck)
    local key=R.key(gun)
    local v=key and Targets.forGun(gun,player:isFemale())
    local row=v and Data[v.refClass]
    local hand=row and row[player:isFemale() and 'F' or 'M']
    if not hand then return nil end
    local own=C.renderMatrix(key,C.placement(key,R.tree(gun),'mod_magazine'))
    if not own then return nil end
    local t={2}
    for i=1,3 do for j=1,4 do t[#t+1]=own[i][j] end end
    for _,x in ipairs(row.frame) do t[#t+1]=x end
    for _,x in ipairs(hand.grip) do t[#t+1]=x end
    for _,x in ipairs(hand.slap) do t[#t+1]=x end
    local ot,rt=tpl and Data.magTargets and Data.magTargets[tpl],row.target
    local box=tpl and D.items[tpl] and D.items[tpl].bounds
    local function reach(b) return math.max(math.abs(b.min[1]),math.abs(b.max[1]),math.abs(b.min[2]),math.abs(b.max[2])) end
    if ot and rt then
        for i=1,3 do t[#t+1]=ot[i]-rt[i] end
        for i=1,3 do t[#t+1]=ot[i]-rt[i] end
        t[#t+1]=box and row.box and math.max(0,reach(box)-reach(row.box)) or 0
        if isCheck then t[#t+1]=1 end
    elseif box and row.box then
        local og,oslap=points(box,row.pistol)
        local rg,rs=points(row.box,row.pistol)
        for i=1,3 do t[#t+1]=og[i]-rg[i] end
        for i=1,3 do t[#t+1]=oslap[i]-rs[i] end
        -- carry push: how much further the magazine reaches across than the reference one
        t[#t+1]=math.max(0,reach(box)-reach(row.box))
        if isCheck then t[#t+1]=1 end
    end
    return t,key
end
M.frames=frames

local function begin(action,tpl,own)
    local player,gun=action.character,action.gun
    if not (TWZB and TWZB.gripIKMag and player and gun and player:isLocalPlayer()) then return end
    -- W8 animopt (the user's item 9): the magazine in the support hand belongs to the mod's animation; in a view
    -- that has given its animations to vanilla the hand stays where the vanilla clip puts it.
    if Anim.blocks() then return end
    local t,key=frames(player,gun,tpl,own==OWN.check)
    if not t then return end
    if TWReloadMagazineNative then TWReloadMagazineNative.off(player,gun) end
    TWZB.gripIKMag(player,t)
    action.twMag={key=key,tpl=tpl,model=tpl and RV.handModel(tpl),own=own}
    M.started=M.started+1
end

local function count(event) M.events[event]=(M.events[event] or 0)+1 end

local function event(action,name)
    local m=action.twMag
    if not m or not (m.own and m.own[name]) then return end
    -- W8 animopt: no own magazine prop in the hand, no assembly swap of ours in a vanilla-animation view.
    if Anim.blocks() then return end
    local gun=action.gun
    if name=='TWMagGrab' then
        local mag=RV.magazine(gun)
        if m.model then action:setOverrideHandModels(gun,m.model) end
        if mag then RV.setAssembly(action,m.tpl,false);m.drawn=true end
        count(name)
    elseif name=='TWMagStow' or name=='TWMagRelease' then
        action:setOverrideHandModels(gun,nil)
        count(name)
    elseif name=='TWMagDraw' then
        if m.model then action:setOverrideHandModels(gun,m.model) end
        count(name)
    elseif name=='TWMagSeat' then
        if m.tpl and not isServer() then
            RV.setAssembly(action,m.tpl,true)
            -- ModelWeaponPart is a mapping of installed inventory parts. The
            -- incoming magazine is not one yet: pin Prop2 to its seat until
            -- the native temporary child takes over, then to the real part.
            local own=C.renderMatrix(m.key,C.placement(m.key,R.tree(gun),'mod_magazine'))
            if own and TWZB and TWZB.gripIKMag then TWZB.gripIKMag(action.character,RV.frame(1,own)) end
            m.native=TWReloadMagazineNative and TWReloadMagazineNative.seat(action.character,gun,RV.modelName(m.tpl),Render.anchorName(m.tpl,0))
            m.drawn=true
        end
        if m.native then action:setOverrideHandModels(gun,nil)
        else action:setOverrideHandModels(gun,m.model) end
        m.seated=true
        count(name)
    end
end

-- Back to the installed parts (an interrupted action, or a magazine drawn ahead of its part).
local function finish(action,completed)
    local m=action.twMag
    if not m then return end
    action.twMag=nil
    if not completed and TWReloadMagazineNative then TWReloadMagazineNative.off(action.character,action.gun) end
    if completed and TWReloadMagazineNative then TWReloadMagazineNative.complete(action.character,action.gun) end
    if TWZB and TWZB.gripIKMag then TWZB.gripIKMag(action.character,nil) end
    if m.drawn and action.gun and R.key(action.gun) then RV.releaseAssembly(action,completed and m.seated) end
end

-- The magazine sounds of an action that plays none itself (the check): the weapon's eject and
-- insert sounds, as ISEjectMagazine / ISInsertMagazine play them.
local function sound(action,parameter)
    local gun=action.gun
    local name=gun and (parameter=='unload' and gun:getEjectAmmoSound() or parameter=='load' and gun:getInsertAmmoSound())
    if name then action.character:playSound(name) end
end

-- A detachable magazine can temporarily give the empty gun zero capacity.
-- Vanilla's insert event then suppresses its sound (currentAmmo < maxAmmo),
-- even though the authored clip really seats a magazine. Keep the queued event
-- and native emitter; repair only a missing own manipulation sound, once.
local function ensureSound(action,parameter)
    local gun,player=action.gun,action.character
    if not gun or not R.key(gun) or not player or not player:isLocalPlayer() then return end
    local name=parameter=='load' and gun:getInsertAmmoSound()
        or parameter=='unload' and gun:getEjectAmmoSound()
        or parameter=='rack' and gun:getRackSound()
    if not name then return end
    local seen=action.twReloadSoundEvents
    if not seen then seen={};action.twReloadSoundEvents=seen end
    if seen[parameter] then return end
    local emitter=player:getEmitter()
    local playing=emitter and emitter:isPlaying(name)
    local handle
    if not playing then
        handle=player:playSound(name)
        playing=emitter and emitter:isPlaying(name)
        M.soundFallbacks=(M.soundFallbacks or 0)+1
    end
    seen[parameter]={name=name,handle=handle,playing=playing}
end

local function wrap(class,tplOf,own,sounds)
    if not class or class.twReloadVisual then return end
    class.twReloadVisual=true
    local start,animEvent,stop,perform=class.start,class.animEvent,class.stop,class.perform
    function class:start()
        start(self)
        local ok,err=pcall(begin,self,tplOf(self),own)
        if not ok then print('[TW] reload visuals: '..tostring(err)) end
    end
    function class:animEvent(name,parameter)
        if self.twMag and name and name:sub(1,5)=='TWMag' then
            local ok,err=pcall(event,self,name)
            if not ok then print('[TW] reload visuals: '..tostring(err)) end
        elseif sounds and name=='playReloadSound' then
            local ok,err=pcall(sound,self,parameter)
            if not ok then print('[TW] reload visuals: '..tostring(err)) end
        end
        local result=animEvent(self,name,parameter)
        if name=='playReloadSound' then ensureSound(self,parameter) end
        return result
    end
    function class:stop()
        pcall(finish,self)
        return stop(self)
    end
    function class:perform()
        pcall(finish,self,true)
        return perform(self)
    end
end

-- Racking (ISRackFirearm): the styled rack clips (tools/reload_clips.py; the AnimSets node by
-- TWRackStyle) take the hand to the reference charging handle; GripIK mode 3 {3, own handle (3),
-- reference handle (3), reference wrist on it (3), pulled (3)[, 1, reference grip wrist (3) - the
-- right hand racks]} moves it onto this weapon's own.
local function rackFrames(player,gun)
    local key=R.visualKey(gun)
    local own=key and Data.rackWeapons and Data.rackWeapons[key]
    local v=own and Targets.forGun(gun,player:isFemale())
    local row=v and Data[v.refClass]
    local hand=row and row[player:isFemale() and 'F' or 'M']
    local path=hand and hand.rack and hand.rack[own[1]]
    local ref=path and Data.rackRef[own[1]]
    if not ref then return nil end
    local t={3,own[2],own[3],own[4],ref[1],ref[2],ref[3],path.grab[1],path.grab[2],path.grab[3],path.pulled[1],path.pulled[2],path.pulled[3]}
    -- Tarkov's right-hand rack (AK family): the right wrist leaves its grip for the handle
    if path.hand=='R' and path.grip then t[14]=1;t[15]=path.grip[1];t[16]=path.grip[2];t[17]=path.grip[3] end
    return t
end

local function wrapRack(class)
    if not class or class.twRackVisual then return end
    class.twRackVisual=true
    local start,stop,perform,animEvent=class.start,class.stop,class.perform,class.animEvent
    local function release(self)
        if self.twRack and TWZB and TWZB.gripIKMag then TWZB.gripIKMag(self.character,nil) end
        self.twRack=nil
    end
    function class:start()
        start(self)
        local player,gun=self.character,self.gun
        if TWZB and TWZB.gripIKMag and player and gun and player:isLocalPlayer() and not Anim.blocks() then
            local ok,t=pcall(rackFrames,player,gun)
            if ok and t then TWZB.gripIKMag(player,t);self.twRack=true;M.racks=(M.racks or 0)+1
            elseif not ok then print('[TW] rack visuals: '..tostring(t)) end
        end
    end
    function class:animEvent(name,parameter)
        if name=='changeWeaponSprite' and self.gun and R.key(self.gun) then return end
        local result=animEvent(self,name,parameter)
        if name=='playReloadSound' then ensureSound(self,parameter) end
        return result
    end
    function class:stop() pcall(release,self);return stop(self) end
    function class:perform() pcall(release,self);return perform(self) end
end

-- W9 breakopen: vanilla double-barrel animNodes fire changeWeaponSprite (ShotgunDoubleBarrel_OPEN /
-- ShotgunDoubleBarrelSawn_OPEN), replacing our Tarkov weapon model with the vanilla low-poly sprite.
-- Mod break-action weapons hinge their real Tarkov barrel assembly through MovingWeaponParts instead.
local function wrapKeepSprite(class)
    if not class or class.twKeepWeaponSprite then return end
    class.twKeepWeaponSprite=true
    local animEvent=class.animEvent
    function class:animEvent(name,parameter)
        if name=='changeWeaponSprite' and self.gun and R.key(self.gun) then
            M.suppressedSpriteSwaps=(M.suppressedSpriteSwaps or 0)+1
            return
        end
        return animEvent(self,name,parameter)
    end
end

-- The handle style of a weapon (Data.rackWeapons): the rack node a class plays (TWRackStyle).
function M.rackStyle(gun)
    local key=gun and R.visualKey(gun)
    local own=key and Data.rackWeapons and Data.rackWeapons[key]
    return own and own[1] or nil
end

local function installedTpl(action)
    local tpl=action.gun and R.tree(action.gun).mod_magazine
    return tpl and C.isClip(tpl) and tpl or nil
end
local function newTpl(action)
    local meta=action.magazine and R.meta(action.magazine)
    return meta and C.isClip(meta.tpl) and meta.tpl or nil
end

local function install()
    pcall(require,'TimedActions/ISReloadWeaponAction')
    pcall(require,'TimedActions/ISUnloadBulletsFromFirearm')
    wrap(ISEjectMagazine,installedTpl,OWN.eject)
    wrap(ISInsertMagazine,newTpl,OWN.insert)
    wrap(TWCheckMagazine,installedTpl,OWN.check,true)
    wrapRack(ISRackFirearm)
    wrapKeepSprite(ISReloadWeaponAction)
    wrapKeepSprite(ISUnloadBulletsFromFirearm)
end

-- TimedAction Lua belongs to its local owner. An MP observer uses the actual
-- replicated track and the incoming hand model's identity, never a guessed mag.
-- Inventory parts still acknowledge the same transient visual projection.
-- W10 maghandmp: the remote body as the owner's own third person draws it (begin/event above). B42 sends an action's
-- hand models only at its start, so the track's magazine in the support hand from its grab or draw to its seat, stow
-- or release is named here (ReloadMagazineVisual.remoteHand), and GripIK gets this weapon's magazine payload (frames:
-- the magazine bone on this weapon's well, the hand on this magazine's body) through the track.
M.lastTpl=setmetatable({},{__mode='k'})
local HAND_FROM,HAND_TO={TWMagGrab=true,TWMagDraw=true},{TWMagSeat=true,TWMagStow=true,TWMagRelease=true}
local function inHand(times,u)
    local from,to
    for name,t in pairs(times) do
        if HAND_FROM[name] then from=t elseif HAND_TO[name] then to=t end
    end
    return from~=nil and u>=from and (to==nil or u<to)
end

-- One observed track let go (finished, another track or weapon, a vanilla-animation view): its visible assembly,
-- seated magazine, magazine in the hand and GripIK magazine payload.
local function release(player,old,switched)
    RV.releaseAssembly(old.action,old.seated)
    if TWReloadMagazineNative then
        if old.seated and not switched then TWReloadMagazineNative.complete(player,old.action.gun)
        else TWReloadMagazineNative.off(player,old.action.gun) end
        if old.hand and TWReloadMagazineNative.remoteHand then TWReloadMagazineNative.remoteHand(player,nil) end
    end
    if old.mag and TWZB and TWZB.gripIKMag then TWZB.gripIKMag(player,nil) end
end

function M.observe(player)
    -- W10 maghandmp: through another player's action (a magazine check, eject, insert, drop, rack) the engine draws
    -- its weapon by name without the part models (ReloadMagazineVisual.remoteParts) - in any animation view.
    if player and TWReloadMagazineNative and TWReloadMagazineNative.remoteParts then TWReloadMagazineNative.remoteParts(player) end
    -- W8 animopt (the user's item 9): a remote body drawn with vanilla animations keeps no magazine prop of ours;
    -- one already shown is released the same way a finished track releases it.
    if player and not Anim.poses(player) then
        local old=M.observers[player]
        if old then release(player,old);M.observers[player]=nil end
        return
    end
    if not player or player:isLocalPlayer() or not TWReloadVisualNative then return end
    local gun=player:getPrimaryHandItem()
    local key=gun and R.key(gun)
    local old=M.observers[player]
    local snapshot=key and TWReloadVisualNative.snapshot(player,'')
    local times=snapshot and inspectData().reloadEvents[snapshot.track]
    if not times or (snapshot.weight or 0)<0.5 then
        if old then release(player,old);M.observers[player]=nil end
        return
    end
    if old and (old.action.gun~=gun or old.track~=snapshot.track) then
        release(player,old,true);old=nil
    end
    if not old then old={action={character=player,gun=gun},track=snapshot.track};M.observers[player]=old end
    local tpl=(snapshot.secondary or ''):match('%.M_(%x+)$')
    if tpl and C.isClip(tpl) then old.tpl=tpl end
    -- an insert after an eject: the weapon holds no magazine yet - the one this player last handled
    old.tpl=old.tpl or R.tree(gun).mod_magazine or M.lastTpl[player]
    if old.tpl then M.lastTpl[player]=old.tpl end
    local u=snapshot.fraction or 0
    local visible
    if times.TWMagSeat and u>=times.TWMagSeat then visible=true;old.seated=true
    elseif times.TWMagGrab and u>=times.TWMagGrab then visible=false end
    if visible~=nil and old.tpl and old.visible~=visible then
        RV.setAssembly(old.action,old.tpl,visible);old.visible=visible
    end
    if visible and old.tpl and not old.native and TWReloadMagazineNative then
        local model=RV.handModel(old.tpl)
        if model then old.native=TWReloadMagazineNative.seat(player,gun,model,Render.anchorName(old.tpl,0)) end
    end
    if old.tpl and not old.mag and TWZB and TWZB.gripIKMag then
        local t=frames(player,gun,old.tpl,snapshot.track:find('_Check',1,true)~=nil)
        if t then TWZB.gripIKMag(player,t) end
        old.mag=true
    end
    local hand=old.tpl and inHand(times,u) and RV.handModel(old.tpl) or nil
    if hand~=old.hand and TWReloadMagazineNative and TWReloadMagazineNative.remoteHand
            and TWReloadMagazineNative.remoteHand(player,hand) then
        old.hand=hand
    end
end
Events.OnGameStart.Add(install)
-- The mod's action animations leave (and its Tarkov utilities enter) over Tarkov's exit cross-fade, not in one
-- blend-in time from the reload pose (Java ActionExits; user report 19.10.2026, item 18). Once per game.
Events.OnGameStart.Add(function() if TWActionExitsNative then TWActionExitsNative.apply() end end)
Events.OnTick.Add(RV.updateAssemblies)
Events.OnTick.Add(function()if TWReloadMagazineNative then TWReloadMagazineNative.tick() end end)
Events.OnTick.Add(function()
    if not TWReloadVisualNative then return end
    local players=getOnlinePlayers()
    if players then for i=0,players:size()-1 do M.observe(players:get(i)) end end
end)
install()
return M
