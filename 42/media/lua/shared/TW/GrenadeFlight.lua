-- Optional VP grenade launch: metric source physics, Gunworks collision/payload/lifecycle APIs.
local D=require('TW/Data/GrenadeFlight')
local Config=require('TW/Data/Launchers')
local Relief=require('TW/FlightRelief')
local M={data=D,module='TWGrenadeFlight',enabled=false,failed=false,trace=false,launches=0,impacts=0,terminals=0,fallbacks=0,
    reliefs={},relieved=0,reliefRejected=0}
local E,Factory,step
local profiles,aliases,peers,pending,retired={},{},{},{},{}
-- Viewpoint's relief of a launch (TW/FlightRelief) by ordnance id: the authority's round and every visual copy of it
-- fly the same drawn arc. Kept for the rounds' longest life, then dropped.
local RELIEF_KEEP_MS=60000
local function finite(x) return type(x)=='number' and x==x and x>-math.huge and x<math.huge end
local function log(s) print('[TW-GRENADE] '..s) end
function M.fail(reason)
    if not M.failed then log('OFF '..tostring(reason)..'; new launches use Gunworks') end
    M.failed=true;M.enabled=false
end
function M.alias(full) return 'EscapeFromZomboidFirearms.TWGF_'..full:sub(#'EscapeFromZomboidFirearms.A_'+1) end
function M.supports(weapon,ammo)
    for _,full in ipairs(D.launchers[weapon] or {}) do if full==ammo then return true end end
    return false
end

-- Source G1 lookup: the row index is a Mach/0.05 index, not a search of Mach knots.
function M.g1(speed)
    local index=math.floor(speed/D.soundSpeed/D.machStep)
    if index<=0 then return 0 end
    if index>=#D.g1 then return D.g1[#D.g1][2] end
    local a,b=D.g1[index],D.g1[index+1]
    return a[2]+(b[2]-a[2])*(speed-a[1]*D.soundSpeed)/((b[1]-a[1])*D.soundSpeed)
end
function M.flight(profile,vx,vy,vz)
    return {profile=profile,time=0,computed=0,nodes={[0]={0,0,0,vx,vy,vz}}}
end
local function node(f,index)
    while f.computed<index do
        local a=f.nodes[f.computed]
        local vx,vy,vz=a[4],a[5],a[6]
        local speed=math.sqrt(vx*vx+vy*vy+vz*vz)
        local mass,diameter=f.profile.massGrams/1000,f.profile.diameterMillimetres/1000
        local area=math.pi*diameter*diameter/4
        local conversion=mass*D.dragConversion/(diameter*diameter*f.profile.bc)
        local drag=speed>1e-9 and D.airDensity*area*M.g1(speed)*conversion*speed/(2*mass) or 0
        local ax,ay,az=-vx*drag,-vy*drag,-D.gravity-vz*drag
        local dt=D.step
        f.computed=f.computed+1
        f.nodes[f.computed]={a[1]+vx*dt+ax*dt*dt/2,a[2]+vy*dt+ay*dt*dt/2,a[3]+vz*dt+az*dt*dt/2,
            vx+ax*dt,vy+ay*dt,vz+az*dt}
    end
    return f.nodes[index]
end
function M.predict(f,time)
    local i=math.floor(time/D.step+1e-10)
    if i>=D.cacheNodes-1 then
        local a=node(f,D.cacheNodes-1)
        local dt=time-(D.cacheNodes-1)*D.step
        return {a[1]+a[4]*dt,a[2]+a[5]*dt,a[3]+a[6]*dt-D.gravity*dt*dt/2,a[4],a[5],a[6]-D.gravity*dt}
    end
    local a,b=node(f,i),node(f,i+1)
    local u=(time-i*D.step)/D.step
    return {a[1]+(b[1]-a[1])*u,a[2]+(b[2]-a[2])*u,a[3]+(b[3]-a[3])*u,a[4],a[5],a[6]}
end

local function copy(t) local out={} for k,v in pairs(t) do out[k]=v end return out end
local function restore(t,old)
    local remove={} for k in pairs(t) do if old[k]==nil then remove[#remove+1]=k end end
    for _,k in ipairs(remove) do t[k]=nil end
    for k,v in pairs(old) do t[k]=v end
end
local function impact(ord,status)
    if not ord.twGFImpact then
        ord.twGFImpact=true;M.impacts=M.impacts+1
        local row={ammo=ord.twGF.profile.full,x=ord.square:getX()+ord.x,y=ord.square:getY()+ord.y,
            z=ord.square:getZ()+ord.z,seconds=ord.twGF.time,status=status,authority=ord.player~=nil,ordnanceId=ord.ordnanceId}
        -- The drawn impact is this point lifted by the relief, relative to the launch point (QA, logs).
        row.relief=ord.twGFRelief~=nil;row.liftM=ord.twGFRelief and Relief.lift(ord.twGFRelief,row.x,row.y) or 0
        M.lastImpact=row
        if row.authority then M.lastAuthorityImpact=row end
        if M.trace then log(string.format('IMPACT id=%s ammo=%s authority=%s xyz=%.6f,%.6f,%.6f seconds=%.4f status=%s relief=%s liftM=%.4f',
            tostring(row.ordnanceId),row.ammo,tostring(row.authority),row.x,row.y,row.z,row.seconds,status,tostring(row.relief),row.liftM)) end
        if M.onImpact then pcall(M.onImpact,row) end
    end
end
local function observed(ord)
    return {x=ord.square:getX()+ord.x,y=ord.square:getY()+ord.y,z=ord.square:getZ()+ord.z,seconds=ord.twGF.time}
end
local function unloaded(ord)
    -- No geometry or payload is invented beyond the observable world column.
    local row=copy(ord.twGFLastObserved or {})
    row.observed=ord.twGFLastObserved~=nil;row.status='unloaded'
    row.ammo=ord.twGF.profile.full;row.authority=ord.player~=nil;row.ordnanceId=ord.ordnanceId
    ord.twGFTerminal=row.status;ord.active=false
    M.terminals=M.terminals+1;M.lastTerminal=row
    if row.authority then M.lastAuthorityTerminal=row end
    retired[#retired+1]=row
    if M.trace then log('TERMINAL id='..tostring(row.ordnanceId)..' status=unloaded landing=unobserved lastObserved='
        ..tostring(row.observed)..' xyz='..tostring(row.x)..','..tostring(row.y)..','..tostring(row.z)..' seconds='..tostring(row.seconds)) end
    return row.status,false
end
local function integrate(ord,scale)
    if not finite(scale) or scale<=0 or scale>240 then error('step delta') end
    local f=ord.twGF
    if not f then
        local p=profiles[ord.params]
        f=M.flight(p,ord.velocityX*E.XY_STEP*60*D.metresPerCell,
            ord.velocityY*E.XY_STEP*60*D.metresPerCell,ord.velocityZ*E.Z_STEP*60*D.metresPerLevel)
        ord.twGF=f
    end
    local remaining=scale/60
    local status,first
    local cell=getCell()
    local relief=ord.twGFRelief
    if not relief then
        local kept=ord.ordnanceId and M.reliefs[ord.ordnanceId]
        relief=kept and kept.profile;ord.twGFRelief=relief
    end
    while remaining>1e-9 do
        local x,y,z=ord.square:getX()+ord.x,ord.square:getY()+ord.y,ord.square:getZ()
        -- Check the supporting column, not an empty high-altitude air square.
        if not cell:getGridSquare(math.floor(x),math.floor(y),z) then return unloaded(ord) end
        ord.twGFLastObserved=observed(ord)
        local a=M.predict(f,f.time)
        local speed=math.sqrt(a[4]^2+a[5]^2+a[6]^2)
        -- Short collision segments also retain ceiling/level transitions in Gunworks.
        local dt=math.min(remaining,D.step,0.35*D.metresPerCell/math.max(1,speed))
        local b=M.predict(f,f.time+dt)
        local nextX,nextY=x+(b[1]-a[1])/D.metresPerCell,y+(b[2]-a[2])/D.metresPerCell
        if not cell:getGridSquare(math.floor(nextX),math.floor(nextY),z) then return unloaded(ord) end
        local s=dt*60
        -- The source arc is the drawn flight: PZ traces it at its drawn height less the relief's lift there.
        local rise=b[3]-a[3]
        if relief then rise=rise-(Relief.lift(relief,nextX,nextY)-Relief.lift(relief,x,y)) end
        local targetZ=ord.square:getZ()+ord.z+rise/D.metresPerLevel
        ord.velocityX=(b[1]-a[1])/D.metresPerCell/(E.XY_STEP*s)
        ord.velocityY=(b[2]-a[2])/D.metresPerCell/(E.XY_STEP*s)
        ord.velocityZ=rise/D.metresPerLevel/(E.Z_STEP*s)+E.GRAVITY*s
        status,first=step(ord,s)
        f.time=f.time+dt;remaining=remaining-dt
        ord.twGFLastObserved=observed(ord)
        if status~='flying' then impact(ord,status);return status,first end
        if b[6]>0 and ord.velocityZ<0 and math.abs(ord.square:getZ()+ord.z-targetZ)>1e-7 then
            -- Gunworks owns ceiling restitution. Start a new source trajectory there.
            f=M.flight(f.profile,b[4],b[5],-math.abs(b[6])*E.BOUNCE_RESTITUTION_CEIL);ord.twGF=f
        end
    end
    return status,first
end

function M.install()
    if M.enabled then return true end
    if M.failed or not Config.enabled then return false end
    local ok,why=pcall(function()
        local L=require('TW/Launchers');L.register()
        E=require('ExplosivesSystems/Physics');Factory=require('ExplosivesSystems/OrdnanceFactory')
        for _,name in ipairs({'computeLaunchVelocity','stepOrdnance'}) do
            if type(E[name])~='function' then error('Gunworks signature '..name) end
        end
        for _,name in ipairs({'RegisterAmmo','GetAmmoParams'}) do
            if type(Factory[name])~='function' then error('Gunworks factory '..name) end
        end
        for _,name in ipairs({'XY_STEP','Z_STEP','GRAVITY','DRAG_XY','DRAG_Z','BOUNCE_RESTITUTION_CEIL'}) do
            if not finite(E[name]) or E[name]<=0 then error('Gunworks units '..name) end
        end
        local compute=E.computeLaunchVelocity
        local vx,vy,vz=compute({directProjectile=true,throwSpeed=6,maxThrowDist=10,throwForce=8},0,0,0,1,0,0)
        if not finite(vx) or not finite(vy) or not finite(vz) or math.abs(vx*E.XY_STEP*60-6)>1e-6 or vy~=0 then
            error('Gunworks computeLaunchVelocity(params,ox,oy,oz,dx,dy,dz)')
        end
        local registrations={ordnance={}}
        for full,profile in pairs(D.ammo) do
            local source=Factory.GetAmmoParams(full)
            if not source or not source.directProjectile or not source.detonateOnImpact then error('unregistered '..full) end
            local alias=M.alias(full)
            if Factory.GetAmmoParams(alias) then error('registry collision '..alias) end
            local params=copy(source)
            Factory.RegisterAmmo(alias,params)
            profiles[Factory.GetAmmoParams(alias)]=setmetatable({full=full},{__index=profile})
            aliases[alias]=full;registrations.ordnance[alias]=params
        end
        -- Public API registration only: these are service keys, never inventory items.
        require('TW/LauncherPhysics').install(registrations)
        step=E.stepOrdnance
        E.computeLaunchVelocity=function(params,ox,oy,oz,dx,dy,dz)
            local p=profiles[params]
            if not p then return compute(params,ox,oy,oz,dx,dy,dz) end
            local x,y,z=dx-ox,dy-oy,dz-oz
            local length=math.sqrt(x*x+y*y+z*z)
            if not finite(length) or math.abs(length-1)>0.001 then
                M.fail('invalid encoded launch direction');return compute(params,ox,oy,oz,dx,dy,dz)
            end
            return x*p.speed/(E.XY_STEP*60*D.metresPerCell),y*p.speed/(E.XY_STEP*60*D.metresPerCell),
                z*p.speed/(E.Z_STEP*60*D.metresPerLevel),dx,dy,length
        end
        E.stepOrdnance=function(ord,scale)
            if ord and profiles[ord.params] and ord.twGFTerminal then return ord.twGFTerminal,false end
            if M.failed or not ord or not profiles[ord.params] then return step(ord,scale) end
            local old=copy(ord)
            local success,status,first=pcall(integrate,ord,scale)
            if success then return status,first end
            restore(ord,old);ord.twGF=nil;M.fail(status)
            return step(ord,scale)
        end
    end)
    if not ok then M.fail(why);return false end
    M.enabled=true;return true
end

function M.validate(player,args)
    if type(args)~='table' or args.protocol~=D.protocol or args.digest~=D.digest then return false,'protocol' end
    if not player or player:isDead() then return false,'player' end
    local gun=player:getPrimaryHandItem()
    if not gun or gun:getID()~=args.weaponId or gun:getFullType()~=args.weapon or not M.supports(args.weapon,args.ammo) then return false,'weapon-ammo' end
    if args.view~=1 and args.view~=2 then return false,'view' end
    if not finite(args.sequence) or args.sequence%1~=0 or args.sequence<1 or args.sequence>2147483647 then return false,'sequence' end
    for _,key in ipairs({'ox','oy','oz','bx','by','bz','fallbackX','fallbackY','fallbackZ'}) do
        if not finite(args[key]) then return false,'nonfinite-'..key end
    end
    local mx=(args.ox-player:getX())*D.metresPerCell
    local my=(args.oy-player:getY())*D.metresPerCell
    local mz=(args.oz-player:getZ())*D.metresPerLevel
    if mx*mx+my*my+mz*mz>16 or mz<0 then return false,'muzzle-distance' end
    if math.abs(args.bx^2+args.by^2+args.bz^2-1)>0.001 then return false,'direction' end
    local md=gun:getModData().TWDisposable
    if args.weapon==Config.disposable.weapon and (not md or not md.initialized or md.pending or md.spent) then return false,'disposable' end
    return true,gun
end

-- Keep a launch's relief for its round and visuals (by ordnance id); drop expired ones.
function M.keepRelief(id,profile)
    local now=getTimestampMs()
    for key,row in pairs(M.reliefs) do if row.untilMs<now then M.reliefs[key]=nil end end
    if id and profile then M.reliefs[id]={profile=profile,untilMs=now+RELIEF_KEEP_MS} end
end
-- A server's copy of a launch's relief for the observers' visuals of the round (TW_GrenadeFlight.lua).
function M.receiveRelief(args)
    if type(args)~='table' or not finite(args.ordnanceId) then return false end
    local profile=Relief.read(args.relief,args.ox,args.oy,args.hx,args.hy,D.metresPerCell)
    if not profile then return false end
    M.keepRelief(args.ordnanceId,profile)
    for _,visual in ipairs(E and E.visualOrdnance or {}) do
        if visual.ordnanceId==args.ordnanceId then visual.twGFRelief=profile end
    end
    return true
end
function M.spawn(player,gun,args)
    local valid,held=M.validate(player,args)
    if not valid or held~=gun then return false,'launch-identity' end
    if not M.install() or type(E.doSpawnOrdnance)~='function' or type(E.activeOrdnance)~='table' or not finite(E.nextOrdnanceId) then return false,'spawn-api' end
    -- Viewpoint's relief along the launch (its client's GrenadeFlightPose.pose), bounded; none in isometric.
    local relief,why=Relief.read(args.relief,args.ox,args.oy,args.bx,args.by,D.metresPerCell)
    if not relief and why~='none' then M.reliefRejected=M.reliefRejected+1 end
    local before=E.nextOrdnanceId
    local ok,err=pcall(E.doSpawnOrdnance,player,M.alias(args.ammo),args.ox,args.oy,args.oz,
        args.ox+args.bx,args.oy+args.by,args.oz+args.bz,true)
    -- A changed ID is a commit even on an exception: never create a second grenade.
    if E.nextOrdnanceId==before then if not ok then M.fail(err) end return false,err or 'no-spawn' end
    if not ok then M.fail('spawn partially committed: '..tostring(err)) end
    if relief then
        local id=E.nextOrdnanceId
        M.keepRelief(id,relief);M.relieved=M.relieved+1
        -- The authority's round and, in single player, its visual were made inside doSpawnOrdnance.
        for _,list in ipairs({E.activeOrdnance,E.visualOrdnance or {}}) do
            for _,ord in ipairs(list) do if ord.ordnanceId==id then ord.twGFRelief=relief end end
        end
        if isServer() then
            local sent,failure=pcall(sendServerCommand,M.module,'Relief',{ordnanceId=id,ox=args.ox,oy=args.oy,hx=args.bx,hy=args.by,
                relief=Relief.wire(relief)})
            if not sent then log('relief transport: '..tostring(failure)) end
            if not M.trace then log('RELIEF id='..tostring(id)..' '..Relief.describe(relief)) end
        end
    end
    if args.weapon==Config.disposable.weapon then
        gun:getModData().TWDisposable.pending=true
        pending[#pending+1]={player=player,gun=gun}
    end
    M.launches=M.launches+1
    M.lastLaunch={id=E.nextOrdnanceId,ammo=args.ammo,ox=args.ox,oy=args.oy,oz=args.oz,bx=args.bx,by=args.by,bz=args.bz,sequence=args.sequence,
        relief=relief}
    if M.trace then log(string.format('LAUNCH id=%s ammo=%s view=%s range=%s origin=%.6f,%.6f,%.6f bore=%.6f,%.6f,%.6f speed=%s %s',
        tostring(E.nextOrdnanceId),args.ammo,tostring(args.view),tostring(args.range),args.ox,args.oy,args.oz,args.bx,args.by,args.bz,D.ammo[args.ammo].speed,
        relief and Relief.describe(relief) or 'relief=none'..(why~='none' and '('..tostring(why)..')' or ''))) end
    return true
end

function M.receive(module,command,player,args)
    if module~=M.module or not isServer() or not player or type(args)~='table' then return end
    if command=='Hello' then
        if args.protocol==D.protocol and args.digest==D.digest and M.install() and type(E.doSpawnOrdnance)=='function' then
            sendServerCommand(player,M.module,'Ready',{protocol=D.protocol,digest=D.digest})
        end
        return
    end
    if command~='Launch' then return end
    local valid,gun=M.validate(player,args)
    if not valid then return end
    local now=getTimestampMs()
    local old=peers[player]
    if old and (args.sequence<=old.sequence or now-old.time<80) then return end
    peers[player]={sequence=args.sequence,time=now}
    local before=E and E.nextOrdnanceId
    local safe,accepted=pcall(M.spawn,player,gun,args)
    if not safe then
        M.fail('spawn adapter: '..tostring(accepted))
        accepted=before~=nil and E.nextOrdnanceId~=before
    end
    if not accepted and E and type(E.onClientCommand)=='function' then
        -- Explicit failover only. The VP launch never uses the client's landing point.
        M.fallbacks=M.fallbacks+1
        E.onClientCommand(E.MODULE_NAME,'throwOrdnance',player,{sourceWeapon=args.ammo,isAmmoLaunch=true,
            destX=args.fallbackX,destY=args.fallbackY,destZ=args.fallbackZ})
    end
end
function M.tick()
    if #retired>0 then
        -- Run outside Gunworks' update loops so removing one row cannot skip its neighbour.
        local work=retired;retired={}
        for _,list in ipairs({E.activeOrdnance or {},E.visualOrdnance or {}}) do
            for i=#list,1,-1 do
                if profiles[list[i].params] and list[i].twGFTerminal then table.remove(list,i) end
            end
        end
        if isServer() then
            for _,row in ipairs(work) do
                if row.authority then
                    local ok,why=pcall(sendServerCommand,E.MODULE_NAME,'removeOrdnanceVisual',{ordnanceId=row.ordnanceId})
                    if not ok then M.fail('terminal visual cleanup: '..tostring(why)) end
                end
            end
        end
    end
    if isClient() then return end
    local work=pending;pending={}
    for _,row in ipairs(work) do
        local ok,why=pcall(require('TW/Launchers').spend,row.player,row.gun)
        if not ok then M.fail('spent tube: '..tostring(why)) end
    end
end
function M.reset() peers={};pending={};retired={};M.reliefs={} end
return M
