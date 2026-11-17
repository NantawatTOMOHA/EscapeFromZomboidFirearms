-- Hip fire in Viewpoint's first person (VP-W12-H3, the user's decision on Q31): with an own firearm or
-- launcher the attack button without aiming fires from Tarkov's ready (hip) pose - no ADS, no FOV
-- narrowing, no PIP (the player does not aim) - and the shot owner takes the bore, as it does for every
-- shot without aiming. PZ fires only while aiming (IsoPlayer's attack branch needs isAiming()); this
-- module calls the entry that branch calls, AttemptAttack(0), under the same conditions (CanAttack, no
-- ban, recoil and melee delays spent), so ammunition, sound, effects, Gunworks' cadence and the network
-- path stay the native ones. A carry stance (low/high ready, patrol) is left first on Tarkov's time
-- (FpPose: the family's PATROL OUT, TW/Data/HipFire); the attack waits for the layer's "ready", a held
-- trigger in an automatic mode fires on after it. PZ starts a ranged attack only from the idle, aim and
-- strafe states (actiongroups/player): a walking press enters strafe (movement/to_strafe.xml) until the
-- shot or held burst finishes and returns to movement right after, while running and sprinting wait. A
-- weapon Tarkov fires only aimed (RSHG-2) is refused by the layer. Inert in isometric and third person,
-- without Viewpoint, while aiming, with our weapons handed to Shoot to Thrill, in a vehicle, during a
-- timed action, with UI over the world, with the shove key and with the option off.
-- A world click and a hip shot never share one press: a press that clicked a door, window, curtain,
-- light switch or thumpable (ISObjectClickHandler on mouse down) does not fire; a press that fired
-- opens no container on its release (the vanilla click of an aiming player does neither).
require('TW_Tactical')
require('TW_SightInput')
local R=require('TW/Runtime')
local HipData=require('TW/Data/HipFire')
local Anim=require('TW/AnimOptOut') -- W8 animopt: the two "vanilla animations" options
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
options:addTickBox('HipFire',getText('UI_TW_HipFire'),true,getText('UI_TW_HipFire_tooltip'))

local previous=rawget(_G,'TWHipFire')
if previous and previous.onShot then Events.OnWeaponSwingHitPoint.Remove(previous.onShot) end
if previous and previous.onFinished then Events.OnPlayerAttackFinished.Remove(previous.onFinished) end
if previous and previous.tick then Events.OnTick.Remove(previous.tick) end
local M={shots=0,accepted=0,attempts=0,clicks=0,suppressed=0,errors=0,lastError='',status='off:none',why='-',clickSeq=0,dataSent=false}
TWHipFire=M
-- Action states with a transition into 'ranged' (media/actiongroups/player/*/to_ranged.xml),
-- plus walking 'movement' which enters 'strafe' via movement/to_strafe.xml for the shot/burst.
M.FIRE_STATES={idle=true,aim=true,strafe=true,ranged=true}
M.WALK_STATES={movement=true}
-- the current press: held, ticks since it began, the world-click counter before it, clicked, fired
local press={down=false,ticks=0,seq=0,click=false,fired=false,accepted=false,up=0,id=0}
M.press=press

function M.enabled()
    return options:getOption('HipFire'):getValue()~=false
end

-- An own ranged weapon in the hands: a weapon of the data, a launcher, a deployed underbarrel.
function M.weapon(gun)
    if not (gun and instanceof(gun,'HandWeapon') and gun:isRanged()) then return nil end
    return R.visualKey(gun) and gun or nil
end

function M.aimIntent(p)
    if TWZB and TWZB.aimIntent then return TWZB.aimIntent(p) end
    return p:isAiming()
end

local function acting(p)
    if ISTimedActionQueue and ISTimedActionQueue.isPlayerDoingAction and ISTimedActionQueue.isPlayerDoingAction(p) then return true end
    local actions=p:getCharacterActions()
    return actions~=nil and not actions:isEmpty()
end

-- Viewpoint support on, our weapons not handed to Shoot to Thrill (as TW_FirstPersonPose).
local function allowed()
    local V=rawget(_G,'TWViewpoint')
    if not V then return false end
    local ok,on=pcall(V.enabled)
    if not ok or on~=true then return false end
    local okStt,stt=pcall(V.stt)
    local okGive,give=pcall(V.giveToStt)
    return not (okStt and stt and okGive and give)
end

-- The weapon hip fire may use now, or nil and why not.
function M.eligible(p)
    if not (p and p:isLocalPlayer() and not p:isDead()) or p:getPlayerNum()~=0 then return nil,'player' end
    if not M.enabled() then return nil,'option' end
    if not allowed() then return nil,'viewpoint' end
    if not (TWZB and TWZB.vpState and TWZB.vpState()==1) then return nil,'view' end
    local gun=M.weapon(p:getPrimaryHandItem())
    if not gun then return nil,'weapon' end
    if M.aimIntent(p) then return nil,'aiming' end
    if p:getVehicle() then return nil,'vehicle' end
    if acting(p) then return nil,'action' end
    local input=rawget(_G,'TWSightInput')
    if input and input.blocked and (input.blockedNow or input.blocked)(p) then return nil,'ui' end
    local state=tostring(p:getCurrentActionContextStateName() or ''):lower()
    if state=='movement' or state=='strafe' then
        if p.isSprinting and p:isSprinting()==true then return nil,'state:sprint' end
        if p.isRunning and p:isRunning()==true then return nil,'state:run' end
    end
    if not (M.FIRE_STATES[state] or M.WALK_STATES[state]) then return nil,'state:'..state end
    local adv=rawget(_G,'TWAdvMove')
    if (p.isSitOnGround and p:isSitOnGround()==true) or not Anim.poses(p)
        or (adv and adv.yielding and adv.yielding(p)==true) then return nil,'yield' end
    return gun
end

-- The attack button in the world (PZ's own binding), not the shove key. QA uses this
-- same native input through HipFireInput, so the engine sees exactly the same hold.
function M.attackDown(p)
    return p:isAttackButtonDown()==true and p:isMeleeButtonDown()~=true
end

-- The engine's own attack, as its aimed branch starts it (IsoPlayer.updateInternal2).
function M.attack(p,gun)
    if not p:CanAttack() or p:isAttackStarted() or p:isBannedAttacking() or p:getRecoilDelay()>0 or p:getMeleeDelay()>0 then return false end
    M.attempts=M.attempts+1
    p:updateBallistics()
    p:setDoShove(false)
    local before=M.pending
    local candidate={player=p,gun=gun,press=press.id,at=getTimestampMs()}
    M.pending=candidate
    local result=p:AttemptAttack(0)
    -- Hook.Attack can handle the attack and make AttemptAttack return false. The
    -- native started flag confirms admission; only the native hitpoint counts a round.
    local accepted=result==true or p:isAttackStarted() or candidate.fired==true
    if accepted then
        M.owned=candidate
        press.accepted=true;M.accepted=M.accepted+1
    elseif M.pending==candidate then M.pending=before end
    return accepted
end

function M.onShot(p,gun)
    local pending=M.pending
    if not pending or pending.player~=p or pending.gun~=gun then return end
    pending.fired=true
    M.shots=M.shots+1
    if pending.press==press.id then press.fired=true end
    -- The accepted native attack owns at most one hitpoint, including after release.
    M.pending=nil
end

local function burstActive(gun)
    if not gun or not gun.getFireMode or gun:getFireMode()~='RealBurst' then return false end
    local rof=rawget(_G,'RateOfFire')
    local s=rof and rof.burstState and rof.burstState[0]
    return type(s)=='table' and (tonumber(s.shotsRemaining) or 0)>0
end

function M.onFinished(p,gun)
    local ours=false
    if M.pending and M.pending.player==p and M.pending.gun==gun then M.pending=nil;ours=true end
    if M.owned and M.owned.player==p and M.owned.gun==gun then M.owned=nil;ours=true end
    if ours and TWZB and TWZB.hipFireState then
        local mode=gun and gun.getFireMode and gun:getFireMode()
        local auto=mode=='RealAuto' or mode=='Auto' or burstActive(gun)
        if not (press.down and not press.click and auto) then pcall(TWZB.hipFireState,p,false) end
    end
end

function M.update(p)
    if not (TWZB and TWZB.fpPoseTrigger) then return end
    -- W8 animopt (the user's item 9): the hip pose is the mod's first-person animation. With "First person: vanilla
    -- animations" on both the pose and its own trigger go: the weapon fires from aim as in vanilla (no pose, no
    -- Tarkov hip shot). The layer's state is released once, so nothing stays held.
    if Anim.blocks() then
        if M.status~='off:animations' then
            M.status,M.why='off:animations','animations'
            pcall(TWZB.fpPoseTrigger,p,false)
            if TWZB.hipFireState then pcall(TWZB.hipFireState,p,false) end
        end
        return
    end
    if not (TWZB.hipFireState and TWZB.aimIntent) then
        M.status='off:input-api';M.why='input-api';TWZB.fpPoseTrigger(p,false);return
    end
    if not M.dataSent and TWZB.fpPoseHipData then TWZB.fpPoseHipData(HipData);M.dataSent=true end
    local gun,why=M.eligible(p)
    local down=M.attackDown(p)
    if down and not press.down then
        press.down,press.ticks,press.seq,press.click,press.fired,press.accepted,press.up=true,0,M.seenSeq or M.clickSeq,false,false,false,0
        press.id=press.id+1
    elseif down then
        press.ticks=press.ticks+1
    else
        -- the release's mouse-up click comes in the same or the next frame; then the shot is history
        press.down,press.up=false,(press.up or 0)+1
        if press.up>2 then press.fired=false;press.accepted=false end
    end
    -- a world click by this press (mouse down precedes or follows the press's first tick by one frame)
    if press.down and M.clickSeq>press.seq then press.click=true end
    M.seenSeq=M.clickSeq
    local mode=gun and gun:getFireMode()
    local automatic=mode=='RealAuto' or mode=='Auto'
    local held=gun~=nil and down and not press.click
    local native=M.owned and M.owned.player==p and M.owned.gun==gun and (p:isAttackStarted() or p:isPerformingHostileAnimation())
    local active=held and (automatic or not press.accepted or M.pending~=nil or burstActive(gun))
    local context=gun~=nil and (active or native==true)
    if TWZB.hipFireState and not TWZB.hipFireState(p,context) and active then held=false;why='input' end
    M.why=held and '-' or (press.click and down and 'click' or why or 'released')
    M.status=TWZB.fpPoseTrigger(p,held)
    -- from the press's second tick, once in a ranged-capable action state ('movement' has entered 'strafe')
    local state=gun and tostring(p:getCurrentActionContextStateName() or ''):lower() or ''
    if held and press.ticks>=1 and M.status=='ready' and M.FIRE_STATES[state] and (automatic or not press.accepted) then
        M.attack(p,gun)
    end
end

-- A press that fired opens nothing on its release: VP first person, an own ranged weapon, the option on.
function M.suppressClick()
    local p=getSpecificPlayer(0)
    if not ((press.fired or press.accepted) and p and M.enabled() and TWZB and TWZB.vpState and TWZB.vpState()==1
        and M.weapon(p:getPrimaryHandItem())) then return false end
    M.suppressed=M.suppressed+1
    return true
end

-- The vanilla world click handlers, wrapped once (originals kept on the table; a reload of this file
-- finds the wrappers and reads the current TWHipFire): the originals run unchanged in every case
-- except a click released by a press that fired a hip shot.
function M.wrapClicks()
    local H=rawget(_G,'ISObjectClickHandler')
    if not H or H.twHipFire then return end
    H.twHipFire={doClickSpecificObject=H.doClickSpecificObject,doClick=H.doClick}
    H.doClickSpecificObject=function(object,playerNum,playerObj)
        local done=H.twHipFire.doClickSpecificObject(object,playerNum,playerObj)
        local hf=rawget(_G,'TWHipFire')
        if done and playerNum==0 and hf then hf.clickSeq=hf.clickSeq+1;hf.clicks=hf.clicks+1 end
        return done
    end
    H.doClick=function(object,x,y)
        local hf=rawget(_G,'TWHipFire')
        if hf and hf.suppressClick and hf.suppressClick() then return end
        return H.twHipFire.doClick(object,x,y)
    end
end

Events.OnGameStart.Add(function()
    M.wrapClicks()
    if TWZB and TWZB.fpPoseHipData then TWZB.fpPoseHipData(HipData);M.dataSent=true end
end)

M.tick=function()
    local p=getSpecificPlayer(0)
    if not p then return end
    local ok,err=pcall(M.update,p)
    if not ok then
        M.errors=M.errors+1;M.lastError=tostring(err)
        if TWZB and TWZB.fpPoseTrigger then pcall(TWZB.fpPoseTrigger,p,false) end
        if TWZB and TWZB.hipFireState then pcall(TWZB.hipFireState,p,false) end
    end
end
Events.OnTick.Add(M.tick)
Events.OnWeaponSwingHitPoint.Add(M.onShot)
Events.OnPlayerAttackFinished.Add(M.onFinished)

-- QA: counters and the last answer of the pose layer.
function M.stats()
    return 'enabled='..tostring(M.enabled())..' shots='..M.shots..' accepted='..M.accepted..' attempts='..M.attempts..' clicks='..M.clicks
        ..' suppressed='..M.suppressed..' status='..tostring(M.status)..' why='..tostring(M.why)..' errors='..M.errors
        ..(M.lastError~='' and (' lastError='..M.lastError) or '')
end

return M
