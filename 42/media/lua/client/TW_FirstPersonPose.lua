-- First-person pose layer (FpPose.java through ZombieBuddy, VP-W4-A): every tick each local
-- player holding an own weapon hands over its static targets - the grip class and grip shift
-- (TW/GripTargets), the camera of the selected sight channel (TWSight.current) in the weapon's
-- Prop1 space, the assembly's ergonomics and weight (Tarkov's aim time), the rear point
-- (TW/WeaponLength) and the obstacle retreat parameters (TW/Data/WeaponObstacle) - and its
-- state: aiming, carry stance, obstacle ratio (TWObstacle), a timed action, whether the
-- first-person poses may be used (Viewpoint support on, Shoot to Thrill not given our
-- weapons). Java holds the weapon at Viewpoint's eye only in its first-person view; elsewhere
-- it lays the obstacle retreat over the pose. Remote players: nothing (their poses as before).
-- With the weapon go its parts, grip offset, pistol roll and rate of fire: Tarkov's recoil over
-- the pose (FpRecoil.java; its per-tick state comes from TW_FirstPersonRecoil).
local C=require('TW/Core')
local R=require('TW/Runtime')
local Targets=require('TW/GripTargets')
local Length=require('TW/WeaponLength')
local Sight=require('TW/SightState')
local Obstacle=require('TW/Data/WeaponObstacle')
local Launchers=require('TW/Data/Launchers')
local Geometry=require('TW/GeometryState')
local Anim=require('TW/AnimOptOut') -- W8 animopt: the two "vanilla animations" options
local Manual=require('TW/Data/ManualActions')

local M={sent={},count=0,errors=0,lastError='',enabled=true,cache=setmetatable({},{__mode='k'}),sights=setmetatable({},{__mode='k'}),holdSet=setmetatable({},{__mode='k'}),manualSet=setmetatable({},{__mode='k'})}
TWFirstPersonPose=M
local SIGHT_MS=250

local function dot(a,b) return a[1]*b[1]+a[2]*b[2]+a[3]*b[3] end
local function sub(a,b) return {a[1]-b[1],a[2]-b[2],a[3]-b[3]} end
local function scale(a,s) return {a[1]*s,a[2]*s,a[3]*s} end
local function cross(a,b) return {a[2]*b[3]-a[3]*b[2],a[3]*b[1]-a[1]*b[3],a[1]*b[2]-a[2]*b[1]} end
local function unit(v)
    local n=math.sqrt(dot(v,v))
    if n<1e-9 then return nil end
    return {v[1]/n,v[2]/n,v[3]/n}
end

-- The camera of a sight channel in the weapon's Prop1 space with the eye's axes (+X left, +Y up,
-- -Z ahead along the line of sight): 12 numbers, rows; nil without a usable frame. The line of
-- sight is the frame axis nearest the bore (+Y); the camera's lateral axis is the frame's X (so
-- in both conventions of the extracted aim points, and a canted sight keeps its cant); a result
-- more than 60 degrees off the weapon's top takes the frame axis nearest the top instead.
function M.sightFrame(key,tree,view)
    local frame=view and view.frame
    if not (key and frame) then return nil end
    local place=(view.path==nil or view.path=='') and C.identity or C.placement(key,tree or {},view.path)
    if not place then return nil end
    local m=C.mul(place,frame)
    local function col(j) return unit({m[1][j],m[2][j],m[3][j]}) end
    local f,best=nil,-1
    for j=1,3 do
        local c=col(j)
        if c and math.abs(c[2])>best then best=math.abs(c[2]);f=c[2]<0 and scale(c,-1) or c end
    end
    if not f or best<(view.rangeMove and .75 or .9) then return nil end -- W10 launchersight: a range pose (M2A1 400 m: 29.4 deg) may turn further
    local up
    local x=col(1)
    if x and math.abs(dot(x,f))<0.5 then
        up=unit(cross(f,unit(sub(x,scale(f,dot(x,f))))))
    end
    if not up or up[3]<0.5 then
        local top=-1
        up=nil
        for j=1,3 do
            local c=col(j)
            if c and math.abs(dot(c,f))<0.5 and math.abs(c[3])>top then top=math.abs(c[3]);up=c[3]<0 and scale(c,-1) or c end
        end
        if not up then return nil end
        up=unit(sub(up,scale(f,dot(up,f))))
    end
    local left=cross(f,up)
    local k={{left[1],up[1],-f[1],m[1][4]},{left[2],up[2],-f[2],m[2][4]},{left[3],up[3],-f[3],m[3][4]}}
    k=C.renderMatrix(key,k)
    local out={}
    for i=1,3 do for j=1,4 do out[#out+1]=k[i][j] end end
    return out
end

-- FB5 (the user's items 5 and 7, 20.10.2026: "the thermal's picture vanishes while firing", "a long scope is cut
-- entering the camera"): the selected sight's eyepiece (centre, radius, normal) and its part's box corners in the
-- weapon's Prop1 space (the render datum, as TW_ScopePipOptions places the eyepiece). Java keeps them beyond
-- Viewpoint's near plane while the weapon comes to the eye and kicks back.
-- W7 (the user's item 3, 25.10.2026: "some weapons like the SKS enter the camera and are cut open, showing the
-- internals"): an iron/built-in sight has no eyepiece - its own near geometry is the rear sight's measured visible
-- feature (the notch/blade top the eye aims over, TW/SightState.ironFrame's rear datum), returned as a disc of
-- IRON_RADIUS round it. Java keeps that beyond the near plane (the sight picture) and, as the sight has no tube
-- round the eye, lets the depth clamp flatten the weapon body behind it instead of cutting it open. A built-in sight
-- without a measured datum (ironAlignment 'unavailable') has no near geometry of its own; it is iron too, which the
-- request's ironSight flag says (FpViewLatch.adsClampSafe).
local IRON_RADIUS = 2 -- cm, the rear sight's notch/blade round its measured top edge
local function ironNear(key,view)
    local a=view and view.ironAlignment
    if not (a and a.status=='aligned' and a.rear and a.rear.position) then return nil end
    local root=C.renderMatrix(key,C.identity)
    local p=C.transformPoint(root,a.rear.position)
    local q=a.front and a.front.position and C.transformPoint(root,a.front.position) or nil
    local n=q and unit(sub(q,p)) or nil
    if not n and view.frame then n=unit({-view.frame[3],-view.frame[7],-view.frame[11]}) end
    if not n then return nil end
    local radius=IRON_RADIUS*C.data.modelScale/100
    return {p[1],p[2],p[3],radius,n[1],n[2],n[3]}
end

function M.nearGeometry(key,tree,view)
    if not (view and view.tpl) then return nil end
    local out={}
    local iron=ironNear(key,view)
    if iron then
        out.iron=iron
        return out
    end
    if not (view.path and view.path~='') then return nil end
    local place=C.placement(key,tree or {},view.path)
    if not place then return nil end
    local root=C.renderMatrix(key,place)
    local lens=Geometry.lens(view)
    local ocular=lens and lens.ocular
    if ocular and ocular.center and ocular.normal then
        local radius=ocular.radius
        if not (type(radius)=='number' and radius>0) and ocular.boundary then
            radius=0
            for _,p in ipairs(ocular.boundary) do
                radius=math.max(radius,math.sqrt(dot(sub(p,ocular.center),sub(p,ocular.center))))
            end
        end
        local axis=unit(ocular.normal)
        local side=axis and unit(cross(axis,math.abs(axis[3])<0.9 and {0,0,1} or {1,0,0}))
        if type(radius)=='number' and radius>0 and side then
            local c,zero,n=C.transformPoint(root,ocular.center),C.transformPoint(root,{0,0,0}),C.transformPoint(root,ocular.normal)
            -- the radius where the centre is: the render matrix may scale
            local rim=sub(C.transformPoint(root,{ocular.center[1]+side[1]*radius,ocular.center[2]+side[2]*radius,
                ocular.center[3]+side[3]*radius}),c)
            out.ocular={c[1],c[2],c[3],math.sqrt(dot(rim,rim)),n[1]-zero[1],n[2]-zero[2],n[3]-zero[3]}
        end
    end
    local b=C.data.items[view.tpl] and C.data.items[view.tpl].bounds
    if b then
        out.box={}
        for corner=0,7 do
            local p={}
            for a=1,3 do p[a]=math.floor(corner/2^(a-1))%2==0 and b.min[a] or b.max[a] end
            local v=C.transformPoint(root,p)
            for a=1,3 do out.box[#out.box+1]=v[a] end
        end
    end
    return (out.ocular or out.box) and out or nil
end

-- The retreat of a class (TW/Data/WeaponObstacle classes; metres -> model units).
function M.retreat(class)
    local p=Obstacle.classes[class]
    local u=C.data.modelScale
    return {p.pullUntil,p.maxBackMetres*u,p.maxUpMetres*u,p.maxPitchDegrees,p.elbowOutMetres*u,p.minChestClearanceMetres*u}
end

local EMPTY={}
M.treeSigs=setmetatable({},{__mode='k'})

-- The view inputs of M.params' signature, kept as values (a view table may be refilled in place).
local function sameView(c,view)
    if c.view~=view then return false end
    if not view then return true end
    if c.tpl~=view.tpl or c.path~=view.path or c.channel~=view.channel or c.mode~=view.mode then return false end
    local f,k=view.frame,c.frame
    if (f==nil)~=(k==nil) then return false end
    if f then
        for i=1,3 do for j=1,4 do if k[(i-1)*4+j]~=f[i][j] then return false end end end
    end
    return true
end
local function remember(c,tree,key,isSpent,launcher,female,v,view)
    c.tree,c.key,c.isSpent,c.launcher,c.female,c.v,c.refClass,c.view=tree,key,isSpent,launcher,female,v,v and v.refClass,view
    c.tpl,c.path,c.channel,c.mode,c.frame=nil,nil,nil,nil,nil
    if view then
        c.tpl,c.path,c.channel,c.mode=view.tpl,view.path,view.channel,view.mode
        if view.frame then
            c.frame={}
            for i=1,3 do for j=1,4 do c.frame[(i-1)*4+j]=view.frame[i][j] end end
        end
    end
end

local function signature(tree)
    local keys={}
    for path,tpl in pairs(tree or {}) do keys[#keys+1]=path..'='..tpl end
    table.sort(keys)
    return table.concat(keys,';')
end

local function spent(gun)
    return gun and Launchers.enabled and Launchers.disposable and gun:getFullType()==Launchers.disposable.spent or false
end

-- A deployed GL is a different item from its host. It aims through the GL camera,
-- never through the host's selected optic. The original camera lives in our binary;
-- Java composes it with this exact installed part placement (including grip shift)
-- and the launcher channel's range move (params.launcherRange).
function M.launcherMount(gun,key,tree)
    if not (TWLaunchers and TWLaunchers.hostKey(gun)==key) then return end
    local found
    for path,tpl in pairs(tree) do
        if gun:getFullType()=='EscapeFromZomboidFirearms.GL_'..tpl then
            if found then return end
            found={tpl=tpl,path=path}
        end
    end
    if not found then return end
    local place=C.placement(key,tree,found.path)
    if not place then return end
    place=C.renderMatrix(key,place)
    local rows={}
    for i=1,3 do for j=1,4 do rows[#rows+1]=place[i][j] end end
    return found.tpl,rows,found.path
end

-- The selected channel, re-read at most every SIGHT_MS (and at once when aiming starts).
local function sightView(player,gun,aiming,assembly)
    local s=M.sights[gun]
    local now=getTimestampMs()
    if s and s.assembly==assembly and s.revision==Sight.revision and now-s.time<SIGHT_MS and (s.aiming or not aiming) then return s.view end
    local ok,view=pcall(Sight.current,player)
    s={time=now,aiming=aiming,assembly=assembly,revision=Sight.revision,view=ok and view or nil}
    M.sights[gun]=s
    return s.view
end

-- The static targets of a held weapon; the same table while nothing changes (Java copies a new one only).
function M.params(player,gun)
    local isSpent=spent(gun)
    local key=isSpent and 'RSHG2' or gun and R.visualKey(gun)
    if not key then return nil end
    local female=player:isFemale()
    local v=not isSpent and Targets.forGun(gun,female) or nil
    -- The shared read-only snapshot (R.view); its tree is the same table until a part changes, and so is its signature.
    local tree=not isSpent and (R.viewTree or R.tree)(gun) or EMPTY
    tree=tree or EMPTY
    local launcher,mount,launcherPath=M.launcherMount(gun,key,tree)
    local treeSig=M.treeSigs[gun]
    if not treeSig or treeSig.tree~=tree then treeSig={tree=tree,value=signature(tree)};M.treeSigs[gun]=treeSig end
    -- Kept with the tree's signature (player report 23.10.2026: the whole signature was concatenated anew every tick).
    if R.tickMemoOff or treeSig.assembly==nil or treeSig.key~=key or treeSig.isSpent~=isSpent or treeSig.launcher~=launcher then
        treeSig.key,treeSig.isSpent,treeSig.launcher=key,isSpent,launcher
        treeSig.assembly=key..'|'..tostring(isSpent)..'|'..tostring(launcher)..'|'..treeSig.value
    end
    local assembly=treeSig.assembly
    -- A deployed launcher's channel is its own (TWSight launcher channel): only its range move is used here.
    local view=(R.key(gun) or launcher) and sightView(player,gun,(TWZB and TWZB.aimIntent or player.isAiming)(player),assembly) or nil
    if launcher and not (view and view.path==launcherPath and view.tpl==launcher) then view=nil end
    -- Performance pass 23.10.2026: the inputs of the signature below compared as values first (no strings every tick).
    local c=M.cache[gun]
    if c and c.tree==tree and c.key==key and c.isSpent==isSpent and c.launcher==launcher and c.female==female and c.v==v
        and c.refClass==(v and v.refClass) and sameView(c,view) then return c.value end
    local frame={}
    if view and view.frame then for i=1,3 do for j=1,4 do frame[#frame+1]=tostring(view.frame[i][j]) end end end
    -- FB2-D: the grip targets' own value too - TW/GripTargets reuses its previous assembly's for up to 250 ms,
    -- and cached under the new tree that stale support contact stayed until the next change (AK-74N + Vulcan: 4.2 cm).
    local sig=assembly..'|'..(female and 'F' or 'M')..'|'..tostring(v and v.refClass)..'|'..tostring(v)..'|'
        ..(view and (tostring(view.tpl)..':'..tostring(view.path)..':'..tostring(view.channel)..':'..tostring(view.mode)) or '-')
        ..'|'..table.concat(frame,',')
    if c and c.sig==sig then remember(c,tree,key,isSpent,launcher,female,v,view);return c.value end
    local length=Length.weapon(gun)
    local handgun=(v and v.kind=='handgun') or (length and (length.kind=='handgun' or length.kind=='revolver'))
    local ergonomics,weight=50,3.5
    local ok,stats=pcall(C.stats,R.key(gun) or key,tree)
    if ok and stats then ergonomics,weight=stats.ergonomics,stats.weight end
    -- handgun: the class of the weapon itself (a pistol or a revolver: TW/WeaponLength), not a guess from the
    -- obstacle data's pitch sign - Java keeps the pistol High Ready and its ADS hand clearance by this flag.
    local t={weapon=key,class=v and v.refClass or '',ergonomics=ergonomics,weight=weight,spent=isSpent,
        launcher=launcher,mount=mount,launcherPath=launcherPath,handgun=handgun and true or false,
        retreat=M.retreat(handgun and 'handgun' or 'rifle')}
    if v and v.params then t.dR={v.params[1],v.params[2],v.params[3]} end
    -- The normal assembly contact, independent of the body's carry clip and VP arm pitch.
    -- Handguns expose the same reference-to-own transform in their public params table.
    if v then
        t.left=v.left;t.right=v.right
        t.supportBack=v.params and v.params[61] or 0
        if v.kind=='handgun' and v.params then
            t.handA={}
            for i=4,15 do t.handA[#t.handA+1]=v.params[i] end
            -- pistol in both hands (TW/PistolGripData): the class's hands on its grip and their fingers
            t.hgHands=v.params.fpHands;t.hgFingers=v.params.fpFingers
        end
    end
    local bounds=C.bounds(key,tree)
    if bounds then t.bounds={bounds.min[1],bounds.min[2],bounds.min[3],bounds.max[1],bounds.max[2],bounds.max[3]} end
    if view and not isSpent and not launcher then t.sight=M.sightFrame(key,tree,view) end
    -- W7 (the user's item 3, 25.10.2026): the channel is an iron/built-in sight of the weapon itself. Such a sight
    -- has no eyepiece/eyecup round the eye, so Java lets the depth clamp flatten the weapon body behind it in ADS
    -- instead of cutting it open (FpViewLatch.adsClampSafe); optics keep their eyepiece rule.
    if view and not isSpent and not launcher then t.ironSight=(view.kind=='iron' or view.kind=='aim') and true or false end
    -- FB5 items 5, 7: the eyepiece and the sight's box, kept beyond Viewpoint's near plane (FpPose.keepOut)
    if t.sight then
        local near=M.nearGeometry(key,tree,view)
        if near then t.ocular=near.ocular;t.sightBox=near.box;t.iron=near.iron end
    end
    -- The launcher sight's range pose (Tarkov SightingRange): a rigid move of its part frame;
    -- Java applies it to the original launcher camera before the installed placement.
    if launcher and view and view.rangeMove then
        local move={}
        for i=1,3 do for j=1,4 do move[#move+1]=view.rangeMove[i][j] end end
        t.launcherRange=move
    end
    if length and length.rearProp then t.rear={length.rearProp[1],length.rearProp[2],length.rearProp[3]} end
    -- Tarkov recoil (FpRecoil): the weapon itself (a deployed underbarrel or a spent tube has none),
    -- its installed parts, the grip offset and pistol roll of its Prop1 space, its rate of fire
    local own=not isSpent and not launcher and R.key(gun) or nil
    if own then
        local parts={}
        for path,tpl in pairs(tree) do parts[#parts+1]=path..'='..tpl end
        table.sort(parts)
        local w=C.data.weapons[own]
        t.recoil=own;t.parts=parts;t.gripOffset=w.gripOffset or 0;t.rolled=C.renderRoll(own);t.rpm=w.rpm or 0
    end
    c={sig=sig,value=t}
    remember(c,tree,key,isSpent,launcher,female,v,view)
    M.cache[gun]=c
    return t
end

-- A timed action runs (as TW_GripIK): the layer leaves the pose to it unless it is one of ours.
local function acting(p)
    if ISTimedActionQueue and ISTimedActionQueue.isPlayerDoingAction and ISTimedActionQueue.isPlayerDoingAction(p) then return true end
    local actions=p:getCharacterActions()
    return actions~=nil and not actions:isEmpty()
end

-- First-person poses allowed: Viewpoint support on (TW_ViewpointCompat), our weapons not handed to STT.
local function allowed()
    local V=rawget(_G,'TWViewpoint')
    if not V then return true end
    local ok,on=pcall(V.enabled)
    if ok and on==false then return false end
    local okStt,stt=pcall(V.stt)
    local okGive,give=pcall(V.giveToStt)
    return not (okStt and stt and okGive and give)
end

local function setVar(p,k,v)
    if p and p.setVariable then p:setVariable(k,v) end
end

local function clearVar(p,k)
    if p and p.clearVariable then p:clearVariable(k) end
end

local function setHold(p,on)
    if not p then return end
    if on then
        if not M.holdSet[p] then M.holdSet[p]=true;setVar(p,'TWFpActionHold',true) end
    elseif M.holdSet[p] then
        M.holdSet[p]=nil;clearVar(p,'TWFpActionHold')
    end
end

function M.clearManual(p)
    if not p or not M.manualSet[p] then return end
    M.manualSet[p]=nil
    clearVar(p,'TWManualKind')
    clearVar(p,'TWManualAction')
    clearVar(p,'TWManualSpeed')
end

local function manualEyeOn()
    return not (TWZB and TWZB.fpManualEyeState and not TWZB.fpManualEyeState())
end

local function reloadSpeedOf(p)
    local rs=p and p.getVariableFloat and tonumber(p:getVariableFloat('ReloadSpeed',1.0)) or 1.0
    return (rs and rs>0) and rs or 1.0
end

local function manualQueueHold(p,gun)
    if not (p and gun and manualEyeOn() and TWZB and TWZB.vpState and TWZB.vpState()==1) then return false end
    local key=R.key(gun)
    if not (key and Manual.weapons[key]) then return false end
    local q=ISTimedActionQueue and ISTimedActionQueue.queues and ISTimedActionQueue.queues[p]
    local a=q and q.queue and q.queue[1]
    if not (a and a.gun==gun) then return false end
    local tp=a.Type
    return tp=='ISRackFirearm' or tp=='ISReloadWeaponAction' or tp=='ISUnloadBulletsFromFirearm'
end

-- PZ's aimed-rack branch selects AimBoltRack, while our Tarkov handle clips live in
-- the ordinary rack branch. Change this action's selector once, after the original
-- start; held aim input stays intact. Java fades ADS for every running action.
function M.rackStart(action)
    local p=action and action.character
    local gun=action and action.gun
    -- W8 animopt: with the first-person animation option on, the vanilla aimed rack plays (no RackAiming selector).
    if Anim.blocks() then return false end
    if not (M.enabled and p and gun and p:isLocalPlayer() and p:getPrimaryHandItem()==gun and R.key(gun) and allowed()) then return false end
    if not (TWZB and TWZB.vpState and TWZB.vpState()==1) then return false end
    if TWZB.fpPoseStats and tostring(TWZB.fpPoseStats()):find('enabled=false',1,true) then return false end
    action:setAnimVariable('RackAiming',false)
    if manualEyeOn() then
        local key=R.key(gun)
        local w=Manual.weapons[key]
        if w then
            local entry
            if gun:haveChamber() then
                if w.close and TWZB.isBreakopenReloadOpen and TWZB.isBreakopenReloadOpen(gun) then
                    entry=w.close
                elseif key=='M3S90' and not gun:isRackAfterShoot() and w.rackSemi then
                    entry=w.rackSemi
                else
                    entry=w.rack
                end
            else
                entry=w.unload
            end
            if entry then
                M.manualSet[p]=true
                setVar(p,'TWManualKind',entry.kind)
                setVar(p,'TWManualAction',entry.id)
                setVar(p,'TWManualSpeed',reloadSpeedOf(p)*entry.speed)
                setHold(p,true)
                action.twManualActive=true
            end
        end
    end
    return true
end

function M.unloadStart(action)
    local p=action and action.character
    local gun=action and action.gun
    if Anim.blocks() or not manualEyeOn() then return false end
    if not (M.enabled and p and gun and p:isLocalPlayer() and p:getPrimaryHandItem()==gun and R.key(gun) and allowed()) then return false end
    if not (TWZB and TWZB.vpState and TWZB.vpState()==1) then return false end
    if TWZB.fpPoseStats and tostring(TWZB.fpPoseStats()):find('enabled=false',1,true) then return false end
    local key=R.key(gun)
    local w=Manual.weapons[key]
    if not w then return false end
    local entry=(key=='M3S90' and not gun:isRackAfterShoot() and w.unloadSemi) or w.unload
    if not entry then return false end
    M.manualSet[p]=true
    setVar(p,'TWManualKind','unload')
    setVar(p,'TWManualAction',entry.id)
    setVar(p,'TWManualSpeed',reloadSpeedOf(p)*entry.speed)
    setHold(p,true)
    action.twManualActive=true
    action.twManualWeapon=w
    return true
end

function M.unloadAnimEvent(action,event)
    if not (action and action.twManualActive and event=='unloadFinished') then return end
    local w=action.twManualWeapon
    local gun=action.gun
    local p=action.character
    if w and w.unloadLoop and gun and p and gun:getCurrentAmmoCount()>0 then
        M.manualSet[p]=true
        setVar(p,'TWManualAction',w.unloadLoop.id)
        setVar(p,'TWManualSpeed',reloadSpeedOf(p)*w.unloadLoop.speed)
    end
end

function M.installActions()
    pcall(require,'TimedActions/ISRackFirearm')
    pcall(require,'TimedActions/ISUnloadBulletsFromFirearm')
    local class=rawget(_G,'ISRackFirearm')
    if class and not class.twFpPoseRack and class.start then
        class.twFpPoseRack=true
        local start=class.start
        function class:start(...)
            local result=start(self,...)
            M.rackStart(self)
            return result
        end
        local stop=class.stop
        if stop then
            function class:stop(...)
                if self.twManualActive then self.twManualActive=nil;M.clearManual(self.character) end
                return stop(self,...)
            end
        end
        local perform=class.perform
        if perform then
            function class:perform(...)
                if self.twManualActive then self.twManualActive=nil;M.clearManual(self.character) end
                return perform(self,...)
            end
        end
    end
    local unload=rawget(_G,'ISUnloadBulletsFromFirearm')
    if unload and not unload.twFpPoseUnload and unload.start then
        unload.twFpPoseUnload=true
        local uStart=unload.start
        function unload:start(...)
            local result=uStart(self,...)
            M.unloadStart(self)
            return result
        end
        local uEvent=unload.animEvent
        if uEvent then
            function unload:animEvent(event,parameter,...)
                local result=uEvent(self,event,parameter,...)
                M.unloadAnimEvent(self,event)
                return result
            end
        end
        local uStop=unload.stop
        if uStop then
            function unload:stop(...)
                if self.twManualActive then self.twManualActive=nil;self.twManualWeapon=nil;M.clearManual(self.character) end
                return uStop(self,...)
            end
        end
        local uPerform=unload.perform
        if uPerform then
            function unload:perform(...)
                if self.twManualActive then self.twManualActive=nil;self.twManualWeapon=nil;M.clearManual(self.character) end
                return uPerform(self,...)
            end
        end
    end
end
Events.OnGameStart.Add(M.installActions)

local function update(p)
    -- W8 animopt (the user's item 9): in Viewpoint's first person with "First person: vanilla animations" on, the eye
    -- pose, the arms, the carry stances and the recoil overlay belong to Viewpoint and to vanilla. The request is
    -- withdrawn at once, so the layers that read it (the ADS camera's narrowing, the hip-fire input, the shadow) yield.
    -- W9-inertia (Hardship Inertia): while that mod owns the body on the floor this layer yields to its own clips too -
    -- except aiming from the ground in Viewpoint's first person, where the Tarkov ADS (eye on the sight) stays on top.
    local hardship=rawget(_G,'TWCompatHI')
    local floor=hardship and hardship.offFeet and hardship.offFeet(p) and not hardship.keepFpPose(p)
    -- W12: the animation opt-out yields outside aim/actions, but must not remove sight alignment in ADS.
    if (Anim.blocks() and not (Anim.keepsAds(p) and not acting(p))) or floor then
        if M.sent[p] then TWZB.fpPoseOff(p);M.sent[p]=nil;M.count=M.count-1 end
        return
    end
    local gun=p:getPrimaryHandItem()
    local ok,t=pcall(M.params,p,gun)
    if ok and t then
        TWZB.fpPose(p,gun,t)
        -- FB4: the selected stance itself (a handgun's Patrol is Tarkov's own patrol clip here, its body
        -- plays Low Ready), none at the first-person ready after a shot or the side mouse button.
        local hold=rawget(_G,'TWTacHoldOverride')
        local carry=hold and hold.fpCarry and hold.fpCarry(p) or (p:getVariableBoolean('TWCarryActive') and p:getVariableString('TWCarryMode') or '')
        local O=rawget(_G,'TWObstacle')
        local state=O and O.state and O.state(p)
        local ratio=state and state.enabled and tonumber(state.ratio) or 0
        local actNow=acting(p)
        if manualQueueHold(p,gun) then
            setHold(p,true)
        else
            setHold(p,false)
            if not actNow then M.clearManual(p) end
        end
        TWZB.fpPoseState(p,not t.spent and (TWZB and TWZB.aimIntent or p.isAiming)(p),t.spent and 'low' or carry or '',ratio,actNow,allowed())
        if not M.sent[p] then M.sent[p]=true;M.count=M.count+1 end
        return
    end
    if not ok then M.errors=M.errors+1;M.lastError=tostring(t) end
    if M.sent[p] then
        TWZB.fpPoseOff(p)
        M.sent[p]=nil;M.count=M.count-1
    end
end

-- Disposal/equip can occur between ticks. Withdraw the old item request at the
-- equipment event too; a spent tube gets its own low pose and identity immediately.
-- removeFromHands precedes the spent equip. Keep that same-tick handoff through
-- its empty event; Java rejects the old item by identity, and the tick clears it
-- if the hand remains empty.
-- Equip events also come for NPCs of other mods (A-Life shells are IsoZombies): players only.
Events.OnEquipPrimary.Add(function(p)
    if TWZB and TWZB.fpPose and p and instanceof(p,'IsoPlayer') and p:isLocalPlayer() and not p:isDead() and p:getPrimaryHandItem() then update(p) end
end)

-- A dead or replaced local player keeps no request in the Java map (performance pass 23.10.2026:
-- it held the character and kept every zombie's animation update looking it up).
local updated={}
Events.OnTick.Add(function()
    if not (TWZB and TWZB.fpPose) then return end
    for i=0,getNumActivePlayers()-1 do
        local p=getSpecificPlayer(i)
        if p and p:isLocalPlayer() and not p:isDead() then update(p);updated[p]=true end
    end
    for p in pairs(M.sent) do
        if not updated[p] then TWZB.fpPoseOff(p);M.sent[p]=nil;M.count=M.count-1 end
    end
    for p in pairs(updated) do updated[p]=nil end
end)

-- QA: the Java layer on or off; its counters; what it did for a player.
function M.setEnabled(on)
    M.enabled=on and true or false
    if TWZB and TWZB.fpPoseEnable then TWZB.fpPoseEnable(on and true or false) end
end

function M.stats()
    return TWZB and TWZB.fpPoseStats and TWZB.fpPoseStats() or 'Java part absent'
end

function M.describe(p)
    return TWZB and TWZB.fpPoseOf and TWZB.fpPoseOf(p or getSpecificPlayer(0)) or nil
end

return M
