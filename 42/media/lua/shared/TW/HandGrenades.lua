-- Server-authoritative, persisted hand grenades. Gunworks supplies collision
-- and restitution through its public Physics API; no implementation is copied.
local D=require('TW/Data/HandGrenades')
local Smoke=require('TW/HandGrenadeSmoke')
local Relief=require('TW/FlightRelief')
local M={data=D,active={},smokeCount=0,errors=0,throws=0,detonations=0,last=nil,relieved=0,reliefRejected=0}
TWHandGrenades=M
local Physics,Factory
local peers=setmetatable({},{__mode='k'})
local runtime={}
local byType={}
for tpl,row in pairs(D.items) do byType[row.full]=tpl end
local function finite(v) return type(v)=='number' and v==v and v>-math.huge and v<math.huge end
local function log(s) print('[TW-HAND-GRENADE] '..s) end
local function authority() return not (isClient and isClient()) end
local function safe(fn,...)
    local ok,value=pcall(fn,...)
    if not ok then M.errors=M.errors+1;log('ERROR '..tostring(value)) end
    return ok,value
end
function M.row(item) return item and D.items[byType[item:getFullType()]] or nil end
function M.store()
    if not M.saved then
        M.saved=ModData.getOrCreate('TWHandGrenadesV1')
        M.saved.nextId=M.saved.nextId or 0
        M.saved.entries=M.saved.entries or {}
        M.active=M.saved.entries
        M.smokeCount=0
        for _,s in pairs(M.active) do if s.phase=='smoke' then M.smokeCount=M.smokeCount+1 end end
    end
    return M.saved
end
local function square(x,y,z)
    return getCell():getGridSquare(math.floor(x),math.floor(y),math.floor(z))
end
function M.install()
    if Physics then return true end
    local ok,why=pcall(function()
        local p=require('ExplosivesSystems/Physics')
        local f=require('ExplosivesSystems/OrdnanceFactory')
        if type(p.stepOrdnance)~='function' or type(f.RegisterAmmo)~='function' or type(f.GetAmmoParams)~='function' then error('Gunworks API') end
        for _,k in ipairs({'XY_STEP','Z_STEP','GRAVITY'}) do if not finite(p[k]) or p[k]<=0 then error('Gunworks units '..k) end end
        for tpl,row in pairs(D.items) do
            -- Registry keys only; no native fire callback or inventory item is registered.
            local key='EscapeFromZomboidFirearms.TWHG_'..tpl
            if not f.GetAmmoParams(key) then f.RegisterAmmo(key,{worldModel=row.full,parentItem=row.full,
                detonateOnImpact=false,detonationDelay=0,floorBounces=3,forwardOffset=0,heightOffset=0,
                soundBounce=nil,soundThrow=nil,soundDetonate=nil}) end
        end
        Physics,Factory=p,f
    end)
    if not ok then log('disabled: '..tostring(why));return false end
    return true
end
function M.validate(player,args)
    if not player or player:isDead() or type(args)~='table' then return nil,'player' end
    if args.protocol~=D.protocol or args.digest~=D.digest then return nil,'protocol' end
    local item=player:getPrimaryHandItem()
    local row=M.row(item)
    if not row or args.itemId~=item:getID() or args.tpl~=row.tpl or item:getContainer()~=player:getInventory() then return nil,'held-item' end
    if type(args.weak)~='boolean' then return nil,'throw-mode' end
    if not finite(args.seq) or args.seq%1~=0 or args.seq<1 or args.seq>2147483647 then return nil,'sequence' end
    local old=peers[player]
    if old and args.seq<=old.seq then return nil,'replay' end
    for _,k in ipairs({'ox','oy','oz','dx','dy','dz'}) do if not finite(args[k]) then return nil,'coordinate' end end
    local norm=args.dx^2+args.dy^2+args.dz^2
    if math.abs(norm-1)>0.002 then return nil,'direction' end
    local m=D.throw.metresPerCell
    local distance=((args.ox-player:getX())*m)^2+((args.oy-player:getY())*m)^2+((args.oz-player:getZ())*D.throw.metresPerLevel)^2
    if distance>16 or args.oz<player:getZ() or args.oz>player:getZ()+1 then return nil,'hand-origin' end
    if not square(args.ox,args.oy,player:getZ()) then return nil,'unloaded-origin' end
    return item,row
end
local function broadcast(command,args,player)
    if isServer and isServer() then
        if player then sendServerCommand(player,'TWHandGrenades',command,args)
        else sendServerCommand('TWHandGrenades',command,args) end
    elseif M.clientEvent then safe(M.clientEvent,command,args) end
end
local function packet(s)
    return {id=s.id,tpl=s.tpl,x=s.x,y=s.y,z=s.z,baseZ=s.baseZ,elapsed=s.elapsed,phase=s.phase,seed=s.seed,
        weak=s.weak,vx=s.vx,vy=s.vy,vz=s.vz,smokeElapsed=s.smokeElapsed or 0,
        -- Bug wave 324 (24.10.2026, item 4): come to rest - the client draws the grenade lying on the floor.
        rest=s.rest==true}
end
local function shooter(s)
    if isServer and isServer() then return getPlayerByOnlineID(s.shooter) end
    return getSpecificPlayer(s.playerNum or 0)
end
local function makeRuntime(s)
    local support=square(s.x,s.y,s.baseZ)
    if not support then return nil end
    local r={square=support,x=s.x-support:getX(),y=s.y-support:getY(),z=s.z-support:getZ(),
        velocityX=s.vx,velocityY=s.vy,velocityZ=s.vz,remainingBounces=s.remainingBounces or 3,
        hasHitFloor=s.rest==true,params=Factory.GetAmmoParams('EscapeFromZomboidFirearms.TWHG_'..s.tpl)}
    if s.relief and s.reliefOrigin then
        local o=s.reliefOrigin
        r.relief=Relief.read(s.relief,o[1],o[2],o[3],o[4],D.throw.metresPerCell)
    end
    runtime[s.id]=r
    return r
end
function M.accept(player,args)
    if not authority() or not M.install() then return nil,'unavailable' end
    M.store()
    local item,row=M.validate(player,args)
    if not item then return nil,row end
    local count=0 for _ in pairs(M.active) do count=count+1 end
    if count>=128 then return nil,'capacity' end
    local resource=D.resources[row.tpl]
    local speed=D.throw.impulseNs/resource.rigidbody.m_Mass*(args.weak and D.throw.weakMultiplier or 1)
    M.saved.nextId=M.saved.nextId+1
    local id=tostring(M.saved.nextId)
    local s={id=id,tpl=row.tpl,x=args.ox,y=args.oy,z=args.oz,baseZ=math.floor(player:getZ()),elapsed=0,
        phase='flight',seed=M.saved.nextId,weak=args.weak,shooter=player:getOnlineID(),playerNum=player:getPlayerNum(),
        vx=args.dx*speed/(D.throw.metresPerCell*Physics.XY_STEP*60),
        vy=args.dy*speed/(D.throw.metresPerCell*Physics.XY_STEP*60),
        vz=args.dz*speed/(D.throw.metresPerLevel*Physics.Z_STEP*60)}
    -- Viewpoint's relief along the throw (its client's HandGrenadeNative.throwPose), bounded; none in isometric.
    -- It shapes the free flight up to the first contact (TW/FlightRelief); the throw keeps it through a save.
    local relief,why=Relief.read(args.relief,args.ox,args.oy,args.dx,args.dy,D.throw.metresPerCell)
    if relief then s.relief=Relief.wire(relief);s.reliefOrigin={args.ox,args.oy,args.dx,args.dy};M.relieved=M.relieved+1
    elseif why~='none' then M.reliefRejected=M.reliefRejected+1 end
    if not makeRuntime(s) then return nil,'unloaded-origin' end
    -- Commit the request identity before any inventory or network mutation.
    peers[player]={seq=args.seq}
    M.active[id]=s
    player:setPrimaryHandItem(nil)
    if player:getSecondaryHandItem()==item then player:setSecondaryHandItem(nil) end
    player:getInventory():Remove(item)
    if sendRemoveItemFromContainer then sendRemoveItemFromContainer(player:getInventory(),item) end
    M.throws=M.throws+1;M.last=s
    log('THROW id='..id..' tpl='..row.tpl..' weak='..tostring(args.weak)..' '..Relief.describe(relief)
        ..(relief==nil and why~='none' and '('..tostring(why)..')' or ''))
    broadcast('Accepted',{seq=args.seq,itemId=args.itemId},player)
    broadcast('Flight',packet(s))
    return id
end
local function ownerMode()
    if isServer and isServer() then return 1 end -- dedicated has no client Java classes
    local mode=2
    if PZAPI and PZAPI.ModOptions then
        local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
        local option=options and options:getOption('ViewpointBallistics')
        if option then mode=option:getValue() end
    end
    return mode
end
function M.nativeBlast(s,row,fragmentsOwned)
    local sq=square(s.x,s.y,s.z)
    if not sq then return false end
    local weapon=instanceItem(row.full)
    -- The native trap is a detached item and never enters inventory/loot.
    weapon:setExplosionPower(row.Strength)
    weapon:setExplosionRange(math.ceil(row.MaxExplosionDistance/D.throw.metresPerCell))
    weapon:setSmokeRange(0);weapon:setFireRange(0);weapon:setNoiseRange(0);weapon:setExplosionTimer(0)
    local attacker=shooter(s)
    local trap=IsoTrap.new(attacker,weapon,getCell(),sq)
    trap:setInstantExplosion(true)
    -- Hit treats a player's current shove as a no-health-damage hit even for
    -- explosives. Keep the real attacker for attribution and native PvP rules;
    -- only the synchronous radial call ignores that unrelated, later action.
    local shove=attacker and attacker:isDoShove()
    if shove then attacker:setDoShove(false) end
    local ok=safe(function()
        log('BLAST id='..s.id..' tpl='..row.tpl..' x='..s.x..' y='..s.y..' z='..s.z
            ..' isExplosive='..tostring(weapon:isExplosive())..' sourceShove='..tostring(shove)
            ..' effectiveShove='..tostring(attacker and attacker:isDoShove())
            ..' attacker='..tostring(attacker and attacker:getOnlineID())..' rangeCells='..weapon:getExplosionRange())
        trap:place()
    end)
    if shove then attacker:setDoShove(shove) end
    return ok
end
function M.detonate(s)
    if s.phase~='flight' then return false end
    local row=D.items[s.tpl]
    -- Persistent terminal phase precedes native effects: reload/retries cannot
    -- replay damage after a partial native exception.
    s.phase=row.kind=='smoke' and 'smoke' or 'done';s.smokeElapsed=0
    if s.phase=='smoke' then M.smokeCount=M.smokeCount+1 end
    runtime[s.id]=nil
    M.detonations=M.detonations+1;M.last=s
    if row.kind=='frag' then
        local owned=false
        if HandGrenadeNative and HandGrenadeNative.startFragments then
            local ok,status=safe(HandGrenadeNative.startFragments,shooter(s),row.tpl,row.FragmentType,row.FragmentsCount,
                s.id,s.seed,s.x,s.y,s.z,ownerMode())
            owned=ok and status=='live'
            s.fragmentOwner=ok and status or 'native'
        end
        -- W11 hostauth stage 3: a server without the mod's Java asks the thrower's client to fly the fragments and checks
        -- them (TW_ShotAuthority); a thrower who is gone or holds no client authority leaves PZ's blast alone.
        local A=isServer and isServer() and not owned and rawget(_G,'TWShotAuthority')
        if A and A.grenade then
            local ok,asked=safe(A.grenade,shooter(s),s,row)
            if ok and asked then s.fragmentOwner='client' end
        end
        -- Native radial blast is independent of B6's fragment ray ownership.
        safe(M.nativeBlast,s,row,owned)
    end
    broadcast('Detonate',packet(s))
    return true
end
function M.advance(s,dt)
    if s.phase=='smoke' then
        s.smokeElapsed=(s.smokeElapsed or 0)+dt
        if s.smokeElapsed>=D.items[s.tpl].EmitTime then s.phase='done';M.smokeCount=math.max(0,M.smokeCount-1);Smoke.stop(s.id);broadcast('Stop',packet(s))
        else safe(Smoke.apply,s) end
        return
    end
    if s.phase~='flight' then return end
    local r=runtime[s.id] or makeRuntime(s)
    if not r then
        s.suspended=true -- keep last observed state; never invent a landing in unloaded columns
        return
    end
    local left=math.min(dt,math.max(0,D.items[s.tpl].explDelay-s.elapsed))
    while left>1e-8 and not s.rest do
        local speed=math.sqrt((r.velocityX*Physics.XY_STEP*60)^2+(r.velocityY*Physics.XY_STEP*60)^2)
        local step=math.min(left,0.01,0.35/math.max(1,speed))
        local nx=s.x+r.velocityX*Physics.XY_STEP*step*60
        local ny=s.y+r.velocityY*Physics.XY_STEP*step*60
        if not square(nx,ny,r.square:getZ()) then s.suspended=true;return end
        if r.relief then
            -- Free flight on the drawn relief: the step ends at its drawn height less the lift there (the launchers'
            -- TW/GrenadeFlight contract). Position only, so Gunworks' floor, wall and bounce velocities stay its own.
            local warp=(Relief.lift(r.relief,nx,ny)-Relief.lift(r.relief,s.x,s.y))/D.throw.metresPerLevel
            r.z=r.z-warp;r.prevWorldZ=(r.prevWorldZ or r.square:getZ()+r.z+warp)-warp
        end
        local vx0,vy0=r.velocityX,r.velocityY
        local status=Physics.stepOrdnance(r,step*60)
        s.x=r.square:getX()+r.x;s.y=r.square:getY()+r.y;s.z=r.square:getZ()+r.z;s.baseZ=r.square:getZ()
        s.vx=r.velocityX;s.vy=r.velocityY;s.vz=r.velocityZ;s.remainingBounces=r.remainingBounces
        if r.relief and (status~='flying' or vx0~=0 and r.velocityX*vx0<=0 or vy0~=0 and r.velocityY*vy0<=0) then
            -- The first contact (floor, or a wall it bounced off) ends the free flight along the profile's line: from
            -- here it bounces and rolls on PZ's floor, drawn on the relief.
            r.relief=nil;s.relief=nil;s.reliefOrigin=nil
        end
        left=left-step
        if status=='rest' then s.rest=true end
        if status=='floorbounce' or status=='wallhit' then broadcast('Bounce',packet(s)) end
        local armed=D.items[s.tpl].MinTimeToContactExplode
        if armed and armed>=0 and s.elapsed+dt-left>=armed and status~='flying' then s.elapsed=s.elapsed+dt-left;return M.detonate(s) end
    end
    s.suspended=nil;s.elapsed=s.elapsed+dt
    if s.elapsed>=D.items[s.tpl].explDelay then M.detonate(s) end
end
function M.tick(dt)
    if not authority() or not M.install() then return end
    M.store()
    dt=dt or getGameTime():getRealworldSecondsSinceLastUpdate()
    if not finite(dt) or dt<=0 or dt>1 then return end
    local remove
    for id,s in pairs(M.active) do
        safe(M.advance,s,dt)
        if s.phase=='done' then remove=remove or {};remove[#remove+1]=id
        else s.net=(s.net or 0)+dt;if s.net>=0.1 then s.net=0;broadcast('Flight',packet(s)) end end
    end
    for _,id in ipairs(remove or {}) do M.active[id]=nil;runtime[id]=nil end
    if HandGrenadeNative and HandGrenadeNative.tick then safe(HandGrenadeNative.tick,dt) end
end
function M.receive(module,command,player,args)
    if module~='TWHandGrenades' or not authority() then return end
    if command=='Hello' and player then
        M.store()
        broadcast('Ready',{protocol=D.protocol,digest=D.digest},player)
        for _,s in pairs(M.active) do broadcast('Flight',packet(s),player) end
    elseif command=='Throw' then
        local ok,id,reason=pcall(M.accept,player,args)
        if not ok or not id then broadcast('Rejected',{seq=args and args.seq,reason=ok and reason or 'server-error'},player) end
    end
end
function M.reset()
    Smoke.reset()
    runtime={};peers=setmetatable({},{__mode='k'});M.saved=nil;M.active={};M.smokeCount=0
    if HandGrenadeNative and HandGrenadeNative.reset then safe(HandGrenadeNative.reset) end
end
M.broadcast=broadcast
if Events then
    -- Only a server acts here (single player and clients returned at once): elsewhere the handler is not
    -- registered at all, as the game called it for every zombie on every update (performance pass 23.10.2026).
    if Events.OnZombieUpdate and isServer and isServer() then Events.OnZombieUpdate.Add(function(zombie)
        if M.smokeCount==0 or not Smoke.visionEnabled() or not (isServer and isServer()) then return end
        -- The dedicated companion supplies the exact native TestVision hook.
        -- Without it, clear only a currently visible player target through our
        -- own cloud; sound/path memory and all other zombie state remain native.
        if HandGrenadeSmokeServer and HandGrenadeSmokeServer.ready() then return end
        local target=zombie:getTarget()
        if target and instanceof(target,'IsoPlayer') and Smoke.blocksLine(zombie:getX(),zombie:getY(),zombie:getZ(),
                target:getX(),target:getY(),target:getZ(),M.active) then zombie:setTarget(nil) end
    end) end
    if Events.OnClientCommand then Events.OnClientCommand.Add(M.receive) end
    if Events.OnTick then Events.OnTick.Add(function() M.tick() end) end
    if Events.OnInitGlobalModData then Events.OnInitGlobalModData.Add(function() if authority() then M.store() end end) end
    if Events.OnMainMenuEnter then Events.OnMainMenuEnter.Add(M.reset) end
end
return M
