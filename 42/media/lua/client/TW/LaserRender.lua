-- Beam and spot drawing (SpriteRenderer in OnPostRender, independent of HUD).
-- Ported from the Brita B42 port; beams, colours and IR visibility come from
-- the installed Tarkov devices and their selected modes. As in Tarkov
-- (LaserBeam.LateUpdate) a switched-on device shines every frame, with or
-- without aiming; intensity falls linearly with distance, (1-d/MaxDistance),
-- and the spot grows from PointSizeClose to PointSizeFar of the prefab.
local T=require('TW/Tactical')
local R=require('TW/LaserRay')
local P=require('TW/LaserGeometry')
local M={cache=setmetatable({},{__mode='k'}),frames=0,errors=0,beams=0,idleBeams=0,spots=0}
local spot
-- Remote lasers farther than this from the viewer are off screen.
local CULL=60
-- Tarkov LaserBeam defaults of the vanilla devices (metres = tiles).
M.pointClose,M.pointFar=.017,.2
-- Smallest visible spot core after the world buffer is scaled to screen pixels
-- (Brita day calibration).
M.minSpot=3.8

-- Viewer options (TW_Tactical): beams without aiming and a brightness factor.
function M.options()
    local idle,brightness=true,1
    if T.laserOptions then idle,brightness=T.laserOptions() end
    return idle~=false,math.max(.25,math.min(3,tonumber(brightness) or 1))
end

-- Perceived brightness: a 532 nm green looks several times brighter than a
-- 635 nm red at the same power (Brita day calibration: red .10, green .13).
-- The CIELAB contrast of the blue NcSTAR line against PZ ground is above the
-- red one at the same alpha, so the same rule holds for blue.
function M.colour(beam,nvg)
    if beam.kind=='ir' then return .72,1,.72,.11 end
    local c=beam.color or {1,0,0}
    local r,g,b=math.max(.03,c[1]),math.max(.03,c[2]),math.max(.03,c[3])
    local luminance=.2126*c[1]+.7152*c[2]+.0722*c[3]
    local alpha=.093+.05*luminance
    if nvg then alpha=alpha+.03 end
    return r,g,b,alpha
end

-- The bright core of a laser line reads paler than its colour.
function M.core(r,g,b)
    return r+(1-r)*.35,g+(1-g)*.35,b+(1-b)*.35
end

function M.visibleTo(beam,nvg)
    return beam.kind~='ir' or nvg
end

-- Tarkov LaserBeam: intensity (1-d/MaxDistance), spot half-size lerp(close,far,d/MaxDistance).
function M.falloff(beam,d)
    local max=beam.maxDistance or 100
    return math.max(0,1-d/max)
end

function M.spotSize(beam,d)
    local max=beam.maxDistance or 100
    local t=math.max(0,math.min(1,d/max))
    local close,far=beam.pointClose or M.pointClose,beam.pointFar or M.pointFar
    return close+(far-close)*t
end

M.batch=true
local EMPTY={}

local function samples(result,n)
    local ray=result.ray;local points={};local count=math.min(80,math.max(1,math.ceil(result.distance/.4)))
    for i=0,count do
        local t=ray.begin+(result.distance-ray.begin)*i/count
        if t>=ray.begin and t<=result.distance then
            local x,y,z=ray.x+ray.dx*t,ray.y+ray.dy*t,ray.z+ray.dz*t
            points[#points+1]={x=x,y=y,z=z,t=t,visible=R.visible(x,y,z,n,ray.owner,result.context)}
        end
    end
    return points
end

function M.draw(result,points,n,beam,nvg,brightness)
    local zoom=getCore():getZoom(n);local renderer=SpriteRenderer.instance
    local climate=getClimateManager();local fog=climate:getFogIntensity();local night=climate:getNightStrength()
    local red,g,b,base=M.colour(beam,nvg)
    local cr,cg,cb=M.core(red,g,b)
    local alpha=(base+night*.07+fog*.16)*(brightness or 1)
    -- The viewport origin once per frame and every sample point projected once (a segment shares its
    -- start with the previous one): the same screen values with a quarter of the Java calls.
    local left,top=getPlayerScreenLeft(n),getPlayerScreenTop(n)
    -- The same loop in Java (zb/IsoBeams: one call instead of ~4 per segment); M.batch false (QA A/B) or no Java part: here.
    local zb=M.batch and rawget(_G,'TWZB')
    if type(zb)=='table' and zb.laserLines then
        zb.laserLines(n,points,result,zoom,left,top,alpha,beam.maxDistance or 100,fog,red,g,b,cr,cg,cb)
        points=EMPTY
    end
    local last,lx,ly
    for _,p in ipairs(points) do
        local px,py
        if p.visible and last and last.visible then
            -- Distance from the emitter (the ray starts behind it by ray.begin).
            local d=p.t-result.ray.begin
            local a=alpha*M.falloff(beam,d)*math.exp(-d*fog*.055);local power=1
            if result.reason=='range' then a=a*math.min(1,math.max(0,(result.ray.range-p.t)/4)) end
            for _,h in ipairs(result.transmissions) do if h.distance<=p.t then power=h.power end end
            a=math.min(1,a*power)
            if not lx then lx,ly=(isoToScreenX(n,last.x,last.y,last.z)-left)*zoom,(isoToScreenY(n,last.x,last.y,last.z)-top)*zoom end
            px,py=(isoToScreenX(n,p.x,p.y,p.z)-left)*zoom,(isoToScreenY(n,p.x,p.y,p.z)-top)*zoom
            renderer:renderlinef(nil,lx,ly,px,py,red,g,b,a*.35,2.6*zoom,2.6*zoom)
            renderer:renderlinef(nil,lx,ly,px,py,cr,cg,cb,a,.9*zoom,.9*zoom)
        end
        last,lx,ly=p,px,py
    end
    if result.hit and result.impactVisible then
        local h=result.hit;local x,y,z=result.x+h.nx*.007,result.y+h.ny*.007,result.z+h.nz*.007
        local sx,sy=(isoToScreenX(n,x,y,z)-left)*zoom,(isoToScreenY(n,x,y,z)-top)*zoom
        local d=result.distance-result.ray.begin
        -- Tarkov spot size in world metres, never below the visible core.
        local ex,ey=(isoToScreenX(n,x+1,y,z)-left)*zoom,(isoToScreenY(n,x+1,y,z)-top)*zoom
        local perTile=math.sqrt((ex-sx)^2+(ey-sy)^2)
        local radius=math.max(M.minSpot*zoom,M.spotSize(beam,d)*perTile)
        local a=math.min(1,result.power*math.max(.25,M.falloff(beam,d))*math.exp(-d*fog*.055)*math.min(1.4,brightness or 1))
        spot=spot or getTexture('media/textures/TW_LaserSpot.png')
        if spot then
            local flatten=math.abs(h.nz)>.5 and .65 or 1
            -- Halo in place of the Tarkov point light, then the spot itself.
            local halo=radius*3.2
            renderer:render(spot,sx-halo,sy-halo*flatten,halo*2,halo*2*flatten,red,g,b,a*.22,nil)
            renderer:render(spot,sx-radius,sy-radius*flatten,radius*2,radius*2*flatten,cr,cg,cb,a,nil)
            M.spots=M.spots+1
        end
    end
end

-- W13: T.beams built the sorted device list, each device's placement (a chain of 3x4 matrix products) and new beam
-- tables every frame for every player drawn. Its inputs are the weapon's key, the shared snapshot (R.view: the same
-- tree until a part changes) and each device's on and mode. PartsVersion keeps a snapshot through a device switch
-- (TWDevice changes in place, also from TW/LaserSync), so those are read every frame (T.state, as T.beams reads them)
-- and any difference builds the list again. One entry per player, dropped with its caches; a fresh read (no Java
-- part, R.snapshotOff) or M.memoOff (QA A/B) builds the list every frame, as before.
M.memo={}
M.memoOff=false
local function remembered(player,key,tree,parts)
    local m=M.memo[player]
    if not m or m.tree~=tree or m.key~=key then
        m={tree=tree,key=key,devices=T.devices(tree,parts),on={},mode={}}
        M.memo[player]=m
    end
    local stale=m.list==nil
    for i,d in ipairs(m.devices) do
        local s=T.state(d.part,d.tpl)
        if m.on[i]~=s.on or m.mode[i]~=s.mode then m.on[i],m.mode[i]=s.on,s.mode;stale=true end
    end
    if stale then m.list=T.beams(key,tree,parts);m.text=nil end
    return m.list,m
end

function M.beamsFor(player)
    local weapon=player:getPrimaryHandItem()
    local Runtime=TWRuntime
    local key=Runtime and Runtime.visualKey(weapon)
    if not key then return end
    -- The shared read-only snapshot (TW/Runtime R.view): every frame, unchanged until a part changes.
    local tree,_,parts,_,shared=(Runtime.view or Runtime.read)(weapon)
    if shared and not M.memoOff then return weapon,remembered(player,key,tree,parts) end
    return weapon,T.beams(key,tree,parts)
end

-- The beams' part of the retrace signature: kind, emitter point and housing axis of each beam. W13: once per
-- remembered list instead of formatting the numbers every frame; the same string.
local function beamsText(beams)
    local text=''
    for _,beam in ipairs(beams) do
        local dir=beam.direction
        text=text..':'..beam.kind..beam.position[1]..beam.position[2]..(dir and (':'..dir[1]..':'..dir[2]..':'..dir[3]) or '')
    end
    return text
end

function M.renderPlayer(player,n,nvg,idle,brightness)
    if not player or player:isDead() then return end
    local weapon,beams,memo=M.beamsFor(player)
    if not beams or #beams==0 then return end
    -- A weapon hidden by an action (P.weaponDrawn) shows no beam.
    if not P.weaponDrawn(player) then M.hidden=(M.hidden or 0)+1;return end
    local remote=not player:isLocalPlayer() and T.remoteAim and T.remoteAim(player,weapon)
    local aiming
    if player:isLocalPlayer() then aiming=player:isAiming() else aiming=remote and remote.aiming end
    if not aiming and not idle then return end
    local now=getTimestampMs();local views=M.cache[player] or {};M.cache[player]=views
    views.seen=now
    local cached=views[n]
    local text=memo and memo.text
    if not text then
        text=beamsText(beams)
        if memo then memo.text=text end
    end
    local signature=weapon:getID()..(aiming and ':aim' or ':idle')..text
    if not cached or cached.signature~=signature or now-cached.time>=50 then
        cached={signature=signature,time=now,rays={}}
        local shared
        if aiming then shared=remote or R.aimState(player,weapon,beams[1]) else shared={aiming=false} end
        for _,beam in ipairs(beams) do
            local ray=shared and R.forBeam(player,weapon,beam,shared)
            if ray then
                local result=R.trace(ray,player)
                local points=samples(result,n)
                if result.hit then
                    local h=result.hit
                    result.impactVisible=R.visible(result.x+h.nx*.007,result.y+h.ny*.007,result.z+h.nz*.007,n,player,result.context)
                end
                result.context=nil
                cached.rays[#cached.rays+1]={beam=beam,result=result,points=points}
            end
        end
        views[n]=cached
    end
    for _,entry in ipairs(cached.rays) do
        if M.visibleTo(entry.beam,nvg) then
            M.draw(entry.result,entry.points,n,entry.beam,nvg,brightness)
            M.beams=M.beams+1
            if not aiming then M.idleBeams=M.idleBeams+1 end
        end
    end
    M.frames=M.frames+1
end

-- Viewpoint (optional, Java bridge TWZB.vp*): with its first or third person
-- view on, the beams are drawn in perspective in the Viewpoint frame (Java
-- ViewpointPass, depth-tested against its scene) instead of the lines above.
-- A beam leaves its emitter along the emitter's own axis, the bore of the
-- animated weapon (Tarkov's LaserBeam shines along the device), not toward the
-- cursor on the floor. The trace (walls, glass, vehicles) is the same; it is
-- renewed every 50 ms or after a turn, and in between the beam keeps its length
-- along the present bore, so it stays on the emitter every frame. What hides a
-- beam is the depth test, not the isometric square checks. IR beams only
-- through night vision (in Viewpoint: goggles or Beathoven's framework; the
-- mod's night vision scope image is not drawn there yet). Other players: their
-- device states from TW/LaserSync, the bore of their animated weapon.
-- Widths in pixels are for 1080 lines; world sizes in tiles (metres). While
-- turning, a trace at most every turnRetrace ms (a long trace every frame would
-- cost the frame rate).
M.vp={range=100,samples=48,halo={2.6,.005},core={.9,.0015},soft=.25,spotSoft=.02,spotBias=.05,spotWallBias=.095,spotBiasMax=.22,retrace=50,turn=.001,turnRetrace=16,
    legacyRail=false,beams=0,spots=0,traces=0}
M.vpCache=setmetatable({},{__mode='k'})
local SPOT='TW_LaserSpot'
local vpTarget={}

-- The view of Viewpoint now: 0 isometric (or no bridge), 1 first person, 2 third person; and the Java table.
function M.vpState()
    local zb=rawget(_G,'TWZB')
    if type(zb)~='table' or not zb.vpState then return 0 end
    local ok,state=pcall(zb.vpState)
    if ok and type(state)=='number' and state>0 then return state,zb end
    return 0
end

-- The ray of a beam along its emitter's housing axis on the animated weapon (or to its ballistic impact target).
function M.vpRay(player,weapon,beam,f,remote,maximumRange,target)
    local x,y,z,reach,vx,vy,vz
    if f then
        x,y,z,reach=P.origin(player,weapon,beam.position,nil,nil,f)
        vx,vy,vz=P.axis(player,f,weapon,beam and beam.direction)
    end
    -- Without the animated frame (no hand model yet): the synced or idle aim of the isometric path.
    if not x or not vx then return R.forBeam(player,weapon,beam,remote or {aiming=false},maximumRange) end
    if target and target[1] then
        local dx,dy,dz=target[1]-x,target[2]-y,(target[3]-z)*P.verticalScale
        local len=math.sqrt(dx*dx+dy*dy+dz*dz)
        if len>.01 then vx,vy,vz=dx/len,dy/len,dz/(len*P.verticalScale) end
    end
    local back=math.max(.05,reach)
    local range=maximumRange or math.min(beam.maxDistance or 100,M.vp.range)
    return {x=x-vx*back,y=y-vy*back,z=z-vz*back,dx=vx,dy=vy,dz=vz,range=back+range,begin=back,owner=player},x,y,z
end

-- What the world does to a beam: its length from the emitter, the glass passed and the surface met.
function M.vpTrace(ray,player)
    local result=R.trace(ray,player)
    local c={dx=ray.dx,dy=ray.dy,dz=ray.dz,distance=math.max(0,result.distance-ray.begin),reason=result.reason,power=result.power or 1,steps={}}
    for _,h in ipairs(result.transmissions or {}) do c.steps[#c.steps+1]={d=h.distance-ray.begin,power=h.power} end
    if result.hit then c.nx,c.ny,c.nz=result.hit.nx,result.hit.ny,result.hit.nz end
    result.context=nil
    M.vp.traces=M.vp.traces+1
    return c
end

-- The beam (halo and core strips, the isometric colours and falloff) and its spot.
function M.vpSpotBias(eye,sx,sy,sz,c)
    local V=M.vp
    if not eye or not c or not c.nx then return V.spotBias end
    local dx,dy,dz=eye.x-sx,eye.y-sy,(eye.z-sz)*P.verticalScale
    local dot=dx*(c.nx or 0)+dy*(c.ny or 0)+dz*(c.nz or 0)
    if dot<=0 then return 0 end
    local dist=math.sqrt(dx*dx+dy*dy+dz*dz)
    local cos=dist>1e-4 and (dot/dist) or 1
    if math.abs(c.nz or 0)>.5 then
        return math.min(V.spotBiasMax,V.spotBias/math.max(.22,cos))
    end
    return math.min(V.spotBiasMax,V.spotWallBias/math.max(.4,cos))
end

function M.drawVP(zb,ray,c,beam,nvg,brightness,eye)
    local V=M.vp
    local length=c.distance
    if length<=.01 then return end
    local climate=getClimateManager();local fog=climate:getFogIntensity();local night=climate:getNightStrength()
    local red,g,b,base=M.colour(beam,nvg)
    local cr,cg,cb=M.core(red,g,b)
    local alpha=(base+night*.07+fog*.16)*(brightness or 1)
    local x,y,z=ray.x+ray.dx*ray.begin,ray.y+ray.dy*ray.begin,ray.z+ray.dz*ray.begin
    local count=math.max(2,math.min(V.samples,math.ceil(length)))
    local points={}
    for i=0,count do
        local d=length*i/count
        local a=alpha*M.falloff(beam,d)*math.exp(-d*fog*.055)
        if c.reason=='range' then a=a*math.min(1,math.max(0,(length-d)/4)) end
        local power=1
        for _,s in ipairs(c.steps) do if s.d<=d then power=s.power end end
        local n=#points
        points[n+1],points[n+2],points[n+3],points[n+4]=x+ray.dx*d,y+ray.dy*d,z+ray.dz*d,math.min(1,a*power)
    end
    zb.vpStrip(points,red,g,b,.35,V.halo[1],V.halo[2],0,V.soft)
    zb.vpStrip(points,cr,cg,cb,1,V.core[1],V.core[2],0,V.soft)
    V.lastPoints=points -- QA (FB4-A item 16): the straightness of the drawn beam (TWZB.vpLaserStraightness)
    V.beams=V.beams+1
    if c.nx then
        local sx,sy,sz=x+ray.dx*length,y+ray.dy*length,z+ray.dz*length
        local bias=M.vpSpotBias(eye,sx,sy,sz,c)
        local a=math.min(1,c.power*math.max(.25,M.falloff(beam,length))*math.exp(-length*fog*.055)*math.min(1.4,brightness or 1))
        local radius=M.spotSize(beam,length)
        -- Halo in place of the Tarkov point light, then the spot itself (Tarkov size, never below the visible core).
        zb.vpSprite(sx,sy,sz,radius*3.2,M.minSpot*3.2,red,g,b,a*.22,SPOT,0,V.spotSoft,bias)
        zb.vpSprite(sx,sy,sz,radius,M.minSpot,cr,cg,cb,a,SPOT,0,V.spotSoft,bias)
        M.spots=M.spots+1
        V.spots=V.spots+1
    end
end

function M.renderPlayerVP(zb,player,nvg,idle,brightness,eye)
    if not player or player:isDead() then return end
    local weapon,beams=M.beamsFor(player)
    if not beams or #beams==0 then return end
    if not P.weaponDrawn(player) then M.hidden=(M.hidden or 0)+1;return end
    local remote=not player:isLocalPlayer() and T.remoteAim and T.remoteAim(player,weapon)
    local aiming
    if player:isLocalPlayer() then aiming=player:isAiming() else aiming=remote and remote.aiming end
    if not aiming and not idle then return end
    local f=P.boneFrame(player)
    local now=getTimestampMs();local views=M.vpCache[player] or {};M.vpCache[player]=views
    views.seen=now
    -- IR beams also when a night vision view of this frame wants them (Java VpSensor: a night
    -- vision scope); marked as IR, they show only through night vision there.
    local ir=nvg or (zb.vpSensorIr~=nil and zb.vpSensorIr()==true)
    local usePoi=player:isLocalPlayer() and f~=nil and not M.vp.legacyRail and zb.shotLaserTarget~=nil
    for i,beam in ipairs(beams) do
        if M.visibleTo(beam,ir) then
            local key=tostring(beam.path)..':'..beam.kind..':'..i
            local c=views[key]
            local ray,ex,ey,ez
            local maxRange=math.min(beam.maxDistance or 100,M.vp.range)
            local poiOk=false
            if usePoi and zb.shotLaserTarget(player,weapon,0,0/0,vpTarget) then
                local mx,my,mz,ldx,ldy,ldz=vpTarget[4],vpTarget[5],vpTarget[6],vpTarget[7],vpTarget[8],vpTarget[9]
                local needRetrace=not c or c.weapon~=weapon or not c.poi or c.aiming~=aiming
                    or now-c.time>=M.vp.retrace
                    or (now-c.time>=M.vp.turnRetrace and math.abs((c.lx or 0)-ldx)+math.abs((c.ly or 0)-ldy)+math.abs((c.lz or 0)-ldz)*P.verticalScale>M.vp.turn)
                if needRetrace then
                    -- Where Viewpoint draws its far relief, the probe follows the shot's own flat flight (Java,
                    -- bent=true), not the straight ray, which meets surfaces drawn above or below that flight.
                    local probe=zb.shotLaserProbe and zb.shotLaserProbe(player,weapon,maxRange)
                        or R.trace({x=mx,y=my,z=mz,dx=ldx,dy=ldy,dz=ldz,range=maxRange,begin=0,owner=player},player)
                    local bent=probe.bent==true
                    M.vp.traces=M.vp.traces+1
                    local dMetres=math.max(.05,probe.distance)*1.1534025374855825
                    local surfaceZ=(probe.hit and math.abs(probe.hit.nz or 0)>.5) and probe.z or (0/0)
                    if zb.shotLaserTarget(player,weapon,dMetres,surfaceZ,vpTarget) then
                        ray,ex,ey,ez=M.vpRay(player,weapon,beam,f,remote,nil,vpTarget)
                        if ray and ex then
                            c=M.vpTrace(ray,player)
                            if not bent and not probe.hit and c.nx and math.abs(c.nz or 0)>.5 then
                                local floorZ=ez+ray.dz*c.distance
                                if zb.shotLaserTarget(player,weapon,maxRange*1.1534025374855825,floorZ,vpTarget) then
                                    surfaceZ=floorZ
                                    ray,ex,ey,ez=M.vpRay(player,weapon,beam,f,remote,nil,vpTarget)
                                    c=M.vpTrace(ray,player)
                                end
                            end
                            local dTarget=math.sqrt((vpTarget[1]-ex)^2+(vpTarget[2]-ey)^2+((vpTarget[3]-ez)*P.verticalScale)^2)
                            local hitTarget=probe.hit~=nil or surfaceZ==surfaceZ
                            local sameFloor=c.reason=='floor' and math.abs((ez+ray.dz*c.distance)-vpTarget[3])<=.006
                            if bent then
                                -- The beam's own straight trace is not the drawn flight: the probe's surface (or none) ends it.
                                c.distance=math.min(maxRange,dTarget)
                                if probe.hit then c.nx,c.ny,c.nz=probe.hit.nx,probe.hit.ny,probe.hit.nz else c.nx,c.ny,c.nz=nil,nil,nil end
                                c.reason=probe.reason or c.reason
                                c.clampedToImpact=true
                            elseif hitTarget and (not c.nx or c.distance>=dTarget-.35 or sameFloor) then
                                c.distance=math.min(maxRange,dTarget)
                                if not c.nx and probe.hit then c.nx,c.ny,c.nz=probe.hit.nx,probe.hit.ny,probe.hit.nz end
                                c.reason=probe.hit and probe.reason or c.reason
                                c.clampedToImpact=true
                            else
                                c.clampedToImpact=false
                            end
                            c.weapon=weapon;c.time=now;c.poi=true;c.aiming=aiming
                            c.muzzleDistMetres=dMetres;c.surfaceZ=surfaceZ
                            c.lx,c.ly,c.lz=ldx,ldy,ldz
                            views[key]=c;poiOk=true
                        end
                    end
                    probe.context=nil
                elseif c and c.muzzleDistMetres and zb.shotLaserTarget(player,weapon,c.muzzleDistMetres,c.surfaceZ or (0/0),vpTarget) then
                    ray,ex,ey,ez=M.vpRay(player,weapon,beam,f,remote,nil,vpTarget)
                    if ray and ex then
                        if c.clampedToImpact then
                            local dTarget=math.sqrt((vpTarget[1]-ex)^2+(vpTarget[2]-ey)^2+((vpTarget[3]-ez)*P.verticalScale)^2)
                            c.distance=math.min(maxRange,dTarget)
                        end
                        poiOk=true
                    end
                end
            end
            if not poiOk then
                ray=M.vpRay(player,weapon,beam,f,remote)
                if ray then
                    if not c or c.weapon~=weapon or c.poi or now-c.time>=M.vp.retrace or now-c.time>=M.vp.turnRetrace
                        and math.abs(c.dx-ray.dx)+math.abs(c.dy-ray.dy)+math.abs(c.dz-ray.dz)*P.verticalScale>M.vp.turn then
                        c=M.vpTrace(ray,player);c.weapon=weapon;c.time=now;views[key]=c
                    end
                end
            end
            if ray and c then
                local sd=(ray.begin or 0)+(c.distance or 0)
                c.ray=ray;c.spotX,c.spotY,c.spotZ=ray.x+ray.dx*sd,ray.y+ray.dy*sd,ray.z+ray.dz*sd
                if zb.vpSensorMark then zb.vpSensorMark(beam.kind=='ir' and 1 or 0) end
                M.drawVP(zb,ray,c,beam,nvg,brightness,eye)
                if zb.vpSensorMark then zb.vpSensorMark(0) end
                M.beams=M.beams+1
                if not aiming then M.idleBeams=M.idleBeams+1 end
            end
        end
    end
    M.frames=M.frames+1
end

Events.OnGameStart.Add(function() M.cache=setmetatable({},{__mode='k'});M.vpCache=setmetatable({},{__mode='k'});M.memo={} end)

local nextPrune=0
function M.render()
    -- OnPostRender runs once per player viewport with that player as getPlayer().
    local viewer=getPlayer();if not viewer then return end
    local n=viewer:getPlayerNum()
    -- A thermal scope image shows no laser, visible or IR (TW/OpticView).
    if T.viewerThermal(viewer) then return end
    local nvg=T.viewerNVG(viewer)
    local idle,brightness=M.options()
    -- Viewpoint's perspective view: into its frame while it takes primitives, never isometric.
    local vp,zb=M.vpState()
    if vp>0 and not zb.vpAccepting() then return end
    if vp==0 and TWViewpoint and TWViewpoint.isoBlocked and TWViewpoint.isoBlocked() then return end
    local eye=vp>0 and ((zb.vpEye and zb.vpEye()) or {x=viewer:getX(),y=viewer:getY(),z=viewer:getZ()+.53}) or nil
    local function draw(p)
        if vp>0 then M.renderPlayerVP(zb,p,nvg,idle,brightness,eye) else M.renderPlayer(p,n,nvg,idle,brightness) end
    end
    draw(viewer)
    local vx,vy=viewer:getX(),viewer:getY()
    if isClient() then
        local players=getOnlinePlayers()
        for i=0,players:size()-1 do
            local p=players:get(i)
            if p~=viewer and math.abs(p:getX()-vx)<=CULL and math.abs(p:getY()-vy)<=CULL then draw(p) end
        end
    else
        -- Split-screen: the other local players' lasers in this viewport.
        for i=0,getNumActivePlayers()-1 do
            local p=getSpecificPlayer(i)
            if p and p~=viewer then draw(p) end
        end
    end
    local now=getTimestampMs()
    if now>=nextPrune then
        nextPrune=now+5000
        for _,cache in ipairs({M.cache,M.vpCache}) do
            for owner,views in pairs(cache) do if now-(views.seen or 0)>5000 then cache[owner]=nil end end
        end
        -- A player's remembered beams go with its caches (W13; Kahlua tables are never weak).
        for owner in pairs(M.memo) do if not M.cache[owner] and not M.vpCache[owner] then M.memo[owner]=nil end end
    end
end

Events.OnPostRender.Add(function()
    local ok,err=pcall(M.render)
    if not ok then
        M.errors=M.errors+1
        if M.lastError~=tostring(err) then M.lastError=tostring(err);print('[TW Laser] render error: '..M.lastError) end
    end
end)
return M
