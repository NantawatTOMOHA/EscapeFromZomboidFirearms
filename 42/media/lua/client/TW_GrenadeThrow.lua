-- Original grenade actions in each local view. T1's public throw owns origin, impulse,
-- inventory, authority, flight and fuse; it is called once at FiringBullet.
local H=require('TW/HandGrenades')
local C=require('TW_HandGrenadesClient')
local D=require('TW/Data/GrenadeThrows')
local Anim=require('TW/AnimOptOut') -- W8 animopt: the two "vanilla animations" options
local M={data=D,seq=0,releases=0,errors=0,cancels=0,status='off',left={},right={}}
TWGrenadeThrow=M
TW_GrenadeThrow=M

function M.excludeVat()
    local vt=rawget(_G,'ViewpointThrowables')
    local vat=rawget(_G,'VAT')
    local reg=vat and vat.Registry
    if not (vt or reg) then return end
    for tpl,row in pairs(H.data.items) do
        local hgv='EscapeFromZomboidFirearms.HGV_'..tpl
        if vt and vt.excludeItem then pcall(vt.excludeItem,row.full);pcall(vt.excludeItem,hgv) end
        if reg and reg.exclude then pcall(reg.exclude,row.full);pcall(reg.exclude,hgv) end
    end
end
local function clearVatAction(p)
    if not (p and H.row(p:getPrimaryHandItem()) and ISTimedActionQueue and ISTimedActionQueue.queues) then return false end
    local q=ISTimedActionQueue.queues[p]
    local list=q and q.queue
    if type(list)=='table' then
        for i=1,#list do
            local act=list[i]
            if type(act)=='table' and (act.Type=='VATThrowAction' or (rawget(_G,'VATThrowAction') and getmetatable(act)==VATThrowAction)) then
                if ISTimedActionQueue.clear then pcall(ISTimedActionQueue.clear,p) end
                return true
            end
        end
    end
    return false
end

local function finite(n) return type(n)=='number' and n==n and n>-math.huge and n<math.huge end
local function api() return rawget(_G,'GrenadeThrowPose') end
local function now() return getTimestampMs() end
local function allowed()
    local v=rawget(_G,'TWViewpoint')
    if not (v and v.enabled and v.enabled()) then return false end
    return not (v.stt and v.stt() and v.giveToStt and v.giveToStt())
end
function M.firstPerson(p)
    return p and p:isLocalPlayer() and p:getPlayerNum()==0 and not p:isDead()
        and TWZB and TWZB.vpState and TWZB.vpState()==1 and allowed()
end
local function acting(p)
    clearVatAction(p)
    if ISTimedActionQueue and ISTimedActionQueue.isPlayerDoingAction and ISTimedActionQueue.isPlayerDoingAction(p) then return true end
    local actions=p:getCharacterActions()
    return actions and not actions:isEmpty()
end
function M.blocked(p,menu)
    if p:getVehicle() then return 'vehicle' end
    if acting(p) then return 'action' end
    if not menu and TWSightInput and TWSightInput.blocked and (TWSightInput.blockedNow or TWSightInput.blocked)(p) then return 'ui' end
    return nil
end
function M.supported(p)
    if M.failed then return false end
    M.excludeVat()
    local ok,value=pcall(function()
        local a=api()
        local perspective=TWZB and TWZB.vpState and TWZB.vpState()~=0
        -- W8 animopt (the user's item 9): the throw pose is the mod's animation. With this view's option on the throw
        -- itself stays (C.throw's own hand-frame path, the mod's grenade action and flight), only the pose does not.
        return p and p:isLocalPlayer() and p:getPlayerNum()==0 and not p:isDead()
            and (not perspective or allowed()) and not Anim.blocks()
            and a and a.available and a.available(D.digest)
    end)
    if not ok then
        M.failed=true;M.errors=M.errors+1;M.lastError=tostring(value)
        if M.stop then pcall(M.stop,'off:input-api') end
        print('[TW-GRENADE-THROW] input off '..tostring(value))
    end
    return ok and value==true
end
function M.stop(reason)
    local s=M.state
    M.state=nil;M.status=reason or 'off'
    if s and api() then api().clear(s.player) end
    if s and s.previewHide~=nil then s.player:setHideWeaponModel(s.previewHide) end
end
local function phase(s,name,gate)
    local previous=s.phase
    M.seq=M.seq+1
    s.phase=name;s.time=0;s.token=M.seq;s.gate=gate==true
    s.fade=(s.meta.fades or {})[tostring(previous)..':'..name] or 0
    s.played={};s.frameWait=now()
    M.status=name
end
local function held(p)
    local item=p and p:getPrimaryHandItem()
    local row=H.row(item)
    return item,row
end
local function state(p)
    local item,row=held(p)
    if not (row and D.items[row.tpl]) then return nil,'held-item' end
    local s=M.state
    if not s or s.item~=item or s.player~=p then
        M.stop('new-item')
        s={player=p,item=item,tpl=row.tpl,meta=D.items[row.tpl],active=false,released=false}
        M.state=s;phase(s,'equip')
    end
    return s
end
local function throwPhase(s) phase(s,s.weak and 'weak' or 'strong',true) end
local function begin(s,weak,auto)
    s.active=true;s.weak=weak==true;s.releaseRequested=auto==true;s.cancelRequested=false;s.attempted=false
    if s.meta.combinedPreparation then
        -- The RDG-2 controller starts its complete three-second action on press.
        -- Its Cook event is data only: this stage preserves T1 fuse-on-release.
        throwPhase(s)
    else phase(s,s.weak and 'weakPin' or 'pin') end
end
function M.begin(p,weak,auto)
    if not M.supported(p) then return false,'pose-unavailable' end
    local why=M.blocked(p,true)
    if why then return false,why end
    if C.pending then return false,'throw-pending' end
    local s,reason=state(p)
    if not s then return false,reason end
    if s.active or s.preview then return false,'busy' end
    if s.phase=='equip' then s.queued={weak=weak==true,auto=auto==true};return true end
    begin(s,weak,auto)
    return true
end
function M.cancel(p)
    local s=M.state
    if not (s and s.player==p and s.active) then return false,'inactive' end
    if s.released or s.attempted then return false,'released' end
    if s.meta.combinedPreparation or s.phase=='strong' or s.phase=='weak' then return false,'source-no-abort' end
    s.cancelRequested=true;s.releaseRequested=false
    if s.phase=='hold' or s.phase=='weakHold' then phase(s,'cancel') end
    return true
end

-- Original sound resources, triggered on the controller's event clock. Throw,
-- lever and fuse remain T1's release sounds, so they cannot play a second time.
local SOUND={Draw='SndDraw',Pin='SndPin',Holster='SndHolster'}
local function sounds(s,track)
    for index,event in ipairs(track.events) do
        if not s.played[index] and event.seconds<=s.time then
            s.played[index]=true
            local key=event.parameter
            local soundKey=SOUND[key] or ((key=='firston' or key=='secondon' or key=='ignition') and key or nil)
            local eventName=soundKey and H.data.resources[s.tpl].sounds[soundKey]
            if eventName then s.player:playSound(eventName) end
        end
    end
end
local function send(s)
    return api().set(s.player,s.item,s.tpl,s.phase,s.time,s.token,D.digest,s.gate,s.fade)
end
local function originError(sample)
    for _,key in ipairs({'ox','oy','oz','ex','ey','ez','dx','dy','dz'}) do if not finite(sample[key]) then return nil end end
    local units=H.data.throw
    return math.sqrt(((sample.ox-sample.ex)*units.metresPerCell)^2+((sample.oy-sample.ey)*units.metresPerCell)^2
        +((sample.oz-sample.ez)*units.metresPerLevel)^2)
end
local function commit(s,sample)
    if s.attempted then return end
    local errorM=originError(sample)
    if not errorM or errorM>.002 or math.abs(sample.time-sample.release)>1e-9 then M.stop('release-origin');return end
    -- Reserve before crossing T1: an exception after sending/consuming cannot
    -- replay the grenade, restore it or submit an immediate fallback throw.
    s.attempted=true
    local record={tpl=s.tpl,weak=s.weak,clip=sample.clip,token=s.token,frame=sample.frame,
        sourceSeconds=sample.release,sampledSeconds=sample.time,originErrorM=errorM,
        ox=sample.ox,oy=sample.oy,oz=sample.oz,ex=sample.ex,ey=sample.ey,ez=sample.ez,
        dx=sample.dx,dy=sample.dy,dz=sample.dz,atMs=now(),submitted=false,authority=isClient() and 'pending' or 'local'}
    record.gripError=sample.gripError;record.handX=sample.handX;record.handY=sample.handY;record.handZ=sample.handZ;record.view=sample.view
    M.last=record
    local ok,accepted,why=pcall(C.throw,s.player,s.weak,true)
    record.submitted=ok and accepted==true;record.reason=ok and why or tostring(accepted)
    if not record.submitted then M.stop('release:'..tostring(record.reason));return end
    s.released=true;s.gate=false;M.releases=M.releases+1
    api().released(s.player,s.token)
    print(string.format('[TW-GRENADE-THROW] RELEASE tpl=%s weak=%s clip=%s time=%.9f source=%.9f originErrorM=%.9f token=%d',
        s.tpl,tostring(s.weak),sample.clip,sample.time,sample.release,errorM,s.token))
end

-- Debug-only static clip sampling for coordinator captures. It never calls T1
-- and cannot become a live throw when the QA case stops or changes view.
function M.preview(p,action,fraction)
    if not getDebug() or not finite(fraction) or fraction<0 or fraction>1 or not M.supported(p) then return false,'preview-gate' end
    local s,why=state(p)
    local track=s and s.meta.actions[action]
    if not track then return false,why or 'preview-action' end
    if s.active then return false,'preview-busy' end
    if s.previewHide==nil then s.previewHide=p:isHideWeaponModel() end
    p:setHideWeaponModel(s.previewHide or track.release>=0 and fraction*track.seconds>=track.release)
    s.active=false;s.preview=true;s.queued=nil;s.released=false
    phase(s,action,false);s.time=fraction*track.seconds;s.fade=0
    return send(s)
end
function M.resume(p)
    local s=M.state
    if not (s and s.player==p and s.preview) then return false end
    if s.previewHide~=nil then p:setHideWeaponModel(s.previewHide);s.previewHide=nil end
    s.preview=false;s.active=false;phase(s,'idle')
    return true
end
function M.buttons(p)
    if M.qaButtons then return M.qaButtons.left==true,M.qaButtons.right==true end
    local pad=rawget(_G,'TWGamepad')
    if p and p:getJoypadBind()~=-1 and pad then
        return pad.held('GamepadGrenadeStrong',p),pad.held('GamepadGrenadeWeak',p)
    end
    return isMouseButtonDown(0)==true,isMouseButtonDown(1)==true
end
function M.mouseOwned(p)
    if not M.supported(p) or M.blocked(p,false) then return false end
    local item=p:getPrimaryHandItem()
    local s=M.state
    if s and s.player==p and s.active and (item==s.item or s.released and not item) then return true end
    local left,right=M.buttons(p)
    return H.row(item)~=nil and (left or right)
end

-- Wrap the functions present at installation, including H3's wrappers. Every
-- forwarded argument/result keeps the existing chain; reloads never add a layer.
function M.wrapClicks()
    local clicks=rawget(_G,'ISObjectClickHandler')
    if clicks and not clicks.twGrenadeThrow and clicks.doClickSpecificObject and clicks.doClick then
        local previous={specific=clicks.doClickSpecificObject,click=clicks.doClick}
        clicks.twGrenadeThrow=previous
        clicks.doClickSpecificObject=function(object,index,player)
            local current=rawget(_G,'TWGrenadeThrow')
            local ok,owned=false,false
            if index==0 and current then ok,owned=pcall(current.mouseOwned,player) end
            if ok and owned then return true end
            return previous.specific(object,index,player)
        end
        clicks.doClick=function(object,x,y)
            local current=rawget(_G,'TWGrenadeThrow')
            local ok,owned=false,false
            if current then ok,owned=pcall(current.mouseOwned,getSpecificPlayer(0)) end
            if ok and owned then return end
            return previous.click(object,x,y)
        end
    end
    local menu=rawget(_G,'ISWorldObjectContextMenu')
    if menu and not menu.twGrenadeThrow and menu.createMenu then
        local previous=menu.createMenu
        menu.twGrenadeThrow=previous
        menu.createMenu=function(index,objects,x,y,test)
            local current=rawget(_G,'TWGrenadeThrow')
            local ok,owned=false,false
            if index==0 and not test and current then ok,owned=pcall(current.mouseOwned,getSpecificPlayer(0)) end
            if ok and owned then return end
            return previous(index,objects,x,y,test)
        end
    end
end
function M.update(p,dt)
    if not (p and p:getPlayerNum()==0) then return end
    local left,right=M.buttons(p)
    if type(M.left)~='table' then M.left={} end
    if type(M.right)~='table' then M.right={} end
    local leftPress,rightPress=left and not M.left[p],right and not M.right[p]
    M.left[p]=left;M.right[p]=right
    if not M.supported(p) then M.stop('off:viewpoint');return end
    local s=M.state
    local item,row=held(p)
    if s and s.released and not item then
        -- Continue only the own empty-hand follow-through until its source end.
    elseif not row then M.stop('off:item');return
    elseif s and s.item~=item then M.stop('off:item-change');s=nil end
    local why=M.blocked(p,false)
    if why then M.stop('off:'..why);return end
    if not s then s=state(p) end
    if not s then return end
    if not finite(dt) or dt<0 then M.stop('off:time');return end
    if s.preview then if not send(s) then M.stop('off:preview-pose') end return end
    local sample=api().snapshot(p)
    local paired=sample and sample.applied and sample.token==s.token and sample.action==s.phase
    if paired then s.frameWait=now() end
    if now()-s.frameWait>3000 then M.stop('off:pose-timeout');return end
    if not s.active and (leftPress or rightPress) then M.begin(p,rightPress,false) end
    if s.queued and not (s.queued.weak and right or not s.queued.weak and left) then s.queued.auto=true end
    if s.active and not s.released then
        local opposite=s.weak and leftPress or not s.weak and rightPress
        if opposite then M.cancel(p) end
        local down=s.weak and right or not s.weak and left
        if not down then s.releaseRequested=true end
    end
    if paired and s.gate and sample.releaseReady then commit(s,sample);if M.state~=s then return end end
    local track=s.meta.actions[s.phase]
    if paired then
        if s.phase=='hold' or s.phase=='weakHold' then
            if s.cancelRequested then phase(s,'cancel')
            elseif s.releaseRequested then throwPhase(s)
            else s.time=(s.time+dt)%track.seconds end
        else
            s.time=math.min(track.seconds,s.time+dt)
            if s.time>=track.seconds then
                if s.phase=='equip' then
                    local queued=s.queued;s.queued=nil
                    if queued then begin(s,queued.weak,queued.auto) else phase(s,'idle') end
                elseif s.phase=='pin' or s.phase=='weakPin' then
                    if s.cancelRequested then phase(s,'cancel')
                    elseif s.releaseRequested then throwPhase(s)
                    else phase(s,s.weak and 'weakHold' or 'hold') end
                elseif s.phase=='cancel' then s.active=false;M.cancels=M.cancels+1;phase(s,'idle')
                elseif s.phase=='strong' or s.phase=='weak' then
                    if s.released then M.stop('done');return end
                    -- Keep requesting the end: Java holds the release key until
                    -- its pose is applied. No full-dt skip can bypass T1 admission.
                elseif s.phase=='idle' then s.time=s.time%track.seconds end
            end
        end
    end
    track=s.meta.actions[s.phase]
    sounds(s,track)
    if not send(s) then M.stop('off:pose-request') end
end

C.fpThrow=function(p,weak)
    if not M.supported(p) then return false end
    local ok,why=M.begin(p,weak,true)
    return true,ok,why
end
C.fpMouse=function(p)
    return M.supported(p) and H.row(p:getPrimaryHandItem())~=nil
end
local function safeUpdate(p)
    local ok,why=pcall(M.update,p,getGameTime():getRealworldSecondsSinceLastUpdate())
    if not ok then M.failed=true;M.errors=M.errors+1;M.lastError=tostring(why);pcall(M.stop,'error');print('[TW-GRENADE-THROW] off '..tostring(why)) end
end
Events.OnPlayerUpdate.Add(safeUpdate)
Events.OnGameStart.Add(function() M.stop('start');M.left={};M.right={};M.excludeVat();M.wrapClicks() end)
if Events.OnMainMenuEnter then Events.OnMainMenuEnter.Add(function() M.stop('menu') end) end
M.excludeVat()
M.wrapClicks()
if PZAPI and PZAPI.ModOptions then require('TW_Gamepad') end
return M
