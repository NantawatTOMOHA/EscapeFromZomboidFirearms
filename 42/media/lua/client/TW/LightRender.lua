-- Tarkov flashlight effects over the native PZ torch light (OnPostRender,
-- SpriteRenderer). Values and textures come from the vanilla prefabs
-- (tools/extract_light_fx.py):
--   beam   VolumetricLight: the light cone seen in the air. White lights only,
--          Tarkov IR illuminators have none. Across the cone the profile of
--          the Tarkov cookie, along it the Unity spot attenuation; walls cut
--          every ray of the cone separately.
--   spot   the Tarkov cookie where the beam axis meets a surface, with the
--          faint bounce light around it (SpotLightFakeGI).
--   flare  MultiFlareLight elements of the Tarkov flare atlas at the lens:
--          a small glow always, the full flare when the light points at the
--          camera (or another player's light at the local player).
-- Everything scales with the darkness where the light is: by day a Tarkov
-- beam is not seen in the air. IR light is visible only through night vision.
-- The light follows the weapon: while aiming along the aim line (the native
-- torch cone looks the same way); with the weapon lowered along the bore of
-- the B42 hold. The native torch follows the bore of the animated weapon
-- through the Java part (TWZB.torch); without it the native torch cannot leave
-- the look direction and only spills light around the character while the
-- weapon is lowered (T.aimLight).
local T=require('TW/Tactical')
local R=require('TW/LaserRay')
local P=require('TW/LaserGeometry')
local M={cache=setmetatable({},{__mode='k'}),frames=0,errors=0,cones=0,spots=0,flares=0,lights=0}
local CULL=60
-- Rays across the beam (r of the cookie radius) and segments along it.
M.across={-.75,-.375,0,.375,.75}
M.slices=6
-- Visible beam length in tiles at most; Tarkov ranges are metres = tiles.
M.maxLength=18
-- Cookie radius drawn on a surface (fraction of the spot cone).
M.cookieExtent=.75
-- World size (tiles) of a flare element of scale 1.
M.flareTile=.32
-- The isometric camera looks from +x+y, about 30 degrees above the floor.
M.camera={.6124,.6124,.5}

local function clamp(v,lo,hi) return math.max(lo,math.min(hi,v)) end
local textures={}
local function texture(name)
    if textures[name]==nil then textures[name]=getTexture('media/textures/'..name..'.png') or false end
    return textures[name] or nil
end

-- Viewer options (TW_Tactical): effects on/off, brightness, light along the
-- lowered weapon.
function M.options()
    local on,brightness,follow=true,1,true
    if T.lightOptions then on,brightness,follow=T.lightOptions() end
    return on~=false,clamp(tonumber(brightness) or 1,.25,3),follow~=false
end

-- Unity's built-in spot light attenuation (legacy lookup): 1/(1+25(d/range)^2).
function M.attenuation(d,range)
    local t=d/math.max(.1,range or 10)
    return 1/(1+25*t*t)
end

-- Cookie intensity at r (0 centre .. 1 edge of the spot cone), from the
-- profile sampled by the extractor.
function M.profileAt(profile,r)
    if not profile or #profile<2 then return math.max(0,1-math.abs(r)) end
    local x=clamp(math.abs(r),0,1)*(#profile-1)
    local i=math.floor(x)
    local a=profile[i+1];local b=profile[math.min(#profile,i+2)]
    return a+(b-a)*(x-i)
end

-- Darkness 0 (daylight) .. 1 (dark) from a square's light (0..1) and the night.
function M.darkness(light,night)
    if light then return clamp((.9-light)/.75,0,1) end
    return clamp(night or 0,0,1)
end

-- How much a light points at a viewer direction: 1 inside the spot cone,
-- fading to 0 at 90 degrees outside it.
function M.facing(dx,dy,dz,tx,ty,tz,spotAngle)
    local n=math.sqrt(dx*dx+dy*dy+dz*dz);local m=math.sqrt(tx*tx+ty*ty+tz*tz)
    if n<1e-6 or m<1e-6 then return 0 end
    local c=(dx*tx+dy*ty+dz*tz)/(n*m)
    local edge=math.cos(math.rad(clamp((spotAngle or 60)/2,1,89)))
    if c>=edge then return 1 end
    if c<=0 then return 0 end
    local t=c/edge
    return t*t
end

-- Screen point of a world point; the viewport origin is read once by the caller (W13: it was read for every point,
-- the same values with half the Java calls).
local function projectAt(n,x,y,z,zoom,left,top)
    return (isoToScreenX(n,x,y,z)-left)*zoom,(isoToScreenY(n,x,y,z)-top)*zoom
end

-- Darkness around a light: the night strength of the climate. The squares'
-- own light is no measure here, the flashlight itself lights them (its spill
-- reaches behind the character).
function M.ownerDarkness()
    local climate=getClimateManager()
    local night=climate and climate:getNightStrength() or 0
    return M.darkness(nil,night),night
end

-- Rays of one light: the axis while aiming follows the common aim line (as the
-- laser); without aiming the bore of the lowered weapon (measured idle hold),
-- or, with that option off, the facing direction, level, as the native torch.
-- The lens point is the device on its mount.
local function axis(player,weapon,light,aim,follow)
    if aim and aim.aiming then
        local pitch=math.rad(aim.pitch)
        local x,y,z,reach=P.origin(player,weapon,light.position,true,player:getCurrentVerticalAimAngle())
        if not x then return end
        return x,y,z,reach,aim.dx*math.cos(pitch),aim.dy*math.cos(pitch),math.sin(pitch)/P.verticalScale
    end
    local x,y,z,reach,f=P.origin(player,weapon,light.position,false)
    if not x then return end
    if follow then
        local vx,vy,vz=P.axis(player,f)
        if vx then return x,y,z,reach,vx,vy,vz,true end
    end
    local f=player:getForwardDirection()
    local fx,fy=f:getX(),f:getY();local l=math.sqrt(fx*fx+fy*fy)
    if l<1e-6 then return end
    return x,y,z,reach,fx/l,fy/l,0
end

local function trace(player,x,y,z,reach,vx,vy,vz,range)
    local back=math.max(.05,reach)
    local ray={x=x-vx*back,y=y-vy*back,z=z-vz*back,dx=vx,dy=vy,dz=vz,range=back+range,begin=back,owner=player}
    local result=R.trace(ray,player)
    return result,ray
end

function M.traceLight(player,weapon,light,aim,n,follow)
    local x,y,z,reach,vx,vy,vz,bore=axis(player,weapon,light,aim,follow)
    if not x then return end
    local entry={light=light,x=x,y=y,z=z,dx=vx,dy=vy,dz=vz,rays={},bore=bore==true}
    -- Along a lowered rifle the bore points up and aside: the beam stays short, as the laser.
    local length=math.min(bore and P.idleRange or M.maxLength,(light.range or 30)*.8)
    local tanHalf=math.tan(math.rad(clamp((light.spotAngle or 60)/2,1,80)))
    local h=math.sqrt(vx*vx+vy*vy)
    -- Visible beam: white lights with a Tarkov VolumetricLight only.
    if light.kind=='light' and (light.fx.scatter or 0)>0 and h>1e-4 then
        for _,r in ipairs(M.across) do
            local a=math.atan(r*tanHalf)
            local c,s=math.cos(a),math.sin(a)
            local rx,ry=vx*c-vy*s,vx*s+vy*c
            local result,ray=trace(player,x,y,z,reach,rx,ry,vz,length)
            local d=math.max(0,math.min(length,result.distance-ray.begin))
            entry.rays[#entry.rays+1]={r=r,dx=rx,dy=ry,dz=vz,length=d}
        end
    end
    -- Spot: where the axis meets a surface within the light's range.
    local result,ray=trace(player,x,y,z,reach,vx,vy,vz,math.min(light.range or 30,bore and P.idleRange*2 or 40))
    -- What the axis met (kept for QA logs): the surface kind, or range/unloaded.
    entry.axis=result.reason
    if result.hit then
        local hit=result.hit
        local px,py,pz=result.x+hit.nx*.01,result.y+hit.ny*.01,result.z+hit.nz*.01
        entry.axisVisible=R.visible(px,py,pz,n,player,result.context)
        if entry.axisVisible then
            entry.spot={x=px,y=py,z=pz,nx=hit.nx,ny=hit.ny,nz=hit.nz,d=result.distance-ray.begin,
                radius=clamp((result.distance-ray.begin)*tanHalf*M.cookieExtent,.12,3)}
        end
    end
    result.context=nil
    entry.lensVisible=R.visible(x,y,z,n,player)
    return entry
end

-- The beam: quads with a colour per corner (corners in order around the quad).
M.batch=true
function M.drawBeam(entry,n,zoom,alpha)
    local rays=entry.rays
    if #rays<2 or alpha<=.002 then return end
    local light=entry.light;local col=light.color
    local renderer=SpriteRenderer.instance
    local K=M.slices
    -- Each point of the beam grid is projected once (neighbouring quads share their corners) and the
    -- viewport origin once per frame: the same corners with a quarter of the Java calls, no tables per quad.
    local left,top=getPlayerScreenLeft(n),getPlayerScreenTop(n)
    -- The same loop in Java (zb/IsoLights: one call instead of ~94 and the profile and attenuation per corner in Lua,
    -- W13); M.batch false (QA A/B) or no Java part: here.
    local iso=M.batch and rawget(_G,'TWIsoLights')
    if type(iso)=='table' and iso.cone then
        -- M.profileAt takes a false profile as none (its linear fallback): it reaches Java as nil.
        iso.cone(n,rays,entry.x,entry.y,entry.z,zoom,left,top,K,alpha,light.fx.profile or nil,light.range or 10,col[1],col[2],col[3])
        M.cones=M.cones+1
        return
    end
    local sx,sy={},{}
    for i,r in ipairs(rays) do
        local xs,ys={},{}
        for k=0,K do
            local t=r.length*k/K
            local x,y,z=entry.x+r.dx*t,entry.y+r.dy*t,entry.z+r.dz*t
            xs[k],ys[k]=(isoToScreenX(n,x,y,z)-left)*zoom,(isoToScreenY(n,x,y,z)-top)*zoom
        end
        sx[i],sy[i]=xs,ys
    end
    local cr,cg,cb=col[1],col[2],col[3]
    local range=light.range
    for i=1,#rays-1 do
        local a,b=rays[i],rays[i+1]
        local pa,pb=M.profileAt(light.fx.profile,a.r),M.profileAt(light.fx.profile,b.r)
        local ax,ay,bx,by=sx[i],sy[i],sx[i+1],sy[i+1]
        for k=0,K-1 do
            local ta0,ta1=a.length*k/K,a.length*(k+1)/K
            local tb0,tb1=b.length*k/K,b.length*(k+1)/K
            local a1=alpha*pa*M.attenuation(ta0,range);local a2=alpha*pb*M.attenuation(tb0,range)
            local a3=alpha*pb*M.attenuation(tb1,range);local a4=alpha*pa*M.attenuation(ta1,range)
            if k==K-1 then a3,a4=0,0 end
            renderer:render(nil,ax[k],ay[k],bx[k],by[k],bx[k+1],by[k+1],ax[k+1],ay[k+1],
                cr,cg,cb,a1,cr,cg,cb,a2,cr,cg,cb,a3,cr,cg,cb,a4,nil)
        end
    end
    M.cones=M.cones+1
end

-- The cookie on the surface: a square of the spot radius in the surface plane.
function M.spotCorners(spot)
    local r=spot.radius
    local ux,uy,uz,vx,vy,vz
    if math.abs(spot.nz)>.5 then
        ux,uy,uz,vx,vy,vz=r,0,0,0,r,0
    else
        local l=math.sqrt(spot.nx*spot.nx+spot.ny*spot.ny);if l<1e-6 then l=1 end
        ux,uy,uz=-spot.ny/l*r,spot.nx/l*r,0
        vx,vy,vz=0,0,r/P.verticalScale
    end
    local x,y,z=spot.x,spot.y,spot.z
    return {x-ux-vx,y-uy-vy,z-uz-vz, x+ux-vx,y+uy-vy,z+uz-vz, x+ux+vx,y+uy+vy,z+uz+vz, x-ux+vx,y-uy+vy,z-uz+vz}
end

function M.drawSpot(entry,n,zoom,alpha,color)
    local spot=entry.spot
    if not spot or alpha<=.002 then return end
    local tex=texture(entry.light.fx.cookie or 'TW_LightCookie_weapon_flashlight_cookie')
    if not tex then return end
    local renderer=SpriteRenderer.instance
    local left,top=getPlayerScreenLeft(n),getPlayerScreenTop(n)
    local function draw(scale,a)
        local s={radius=spot.radius*scale,x=spot.x,y=spot.y,z=spot.z,nx=spot.nx,ny=spot.ny,nz=spot.nz}
        local w=M.spotCorners(s)
        local x1,y1=projectAt(n,w[1],w[2],w[3],zoom,left,top);local x2,y2=projectAt(n,w[4],w[5],w[6],zoom,left,top)
        local x3,y3=projectAt(n,w[7],w[8],w[9],zoom,left,top);local x4,y4=projectAt(n,w[10],w[11],w[12],zoom,left,top)
        renderer:render(tex,x1,y1,x2,y2,x3,y3,x4,y4,color[1],color[2],color[3],a,nil)
    end
    local gi=entry.light.fx.gi
    -- Bounce light around the spot, then the spot.
    if gi then draw(gi[2] or 2,math.min(.35,alpha*(gi[1] or .05)*6)) end
    draw(1,alpha)
    M.spots=M.spots+1
end

-- A flare element's texture name and texture, kept per element of the light data (W13: the name was built every frame;
-- texture() keeps its answer per name for good, so the element's is the same).
local flareNames,flareTextures={},{}
local function flareName(part)
    local name=flareNames[part]
    if not name then name='TW_Flare_'..part.tex;flareNames[part]=name end
    return name
end
local function flareTexture(part)
    local tex=flareTextures[part]
    if tex==nil then tex=texture(flareName(part)) or false;flareTextures[part]=tex end
    return tex or nil
end
local WHITE={1,1,1}
local IR_GREEN={.72,1,.72}

-- Flare elements at the lens: always a small glow, the full flare as the
-- light points at the camera (or at the local player).
function M.drawFlare(entry,n,zoom,dark,brightness,facing,color)
    local flare=entry.light.fx.flare
    if not flare or not flare.parts then return end
    local renderer=SpriteRenderer.instance
    local left,top=getPlayerScreenLeft(n),getPlayerScreenTop(n)
    local sx,sy=projectAt(n,entry.x,entry.y,entry.z,zoom,left,top)
    local ex,ey=projectAt(n,entry.x+1,entry.y,entry.z,zoom,left,top)
    local perTile=math.sqrt((ex-sx)^2+(ey-sy)^2)
    local night=.35+.65*dark
    for i,part in ipairs(flare.parts) do
        local tex=flareTexture(part)
        if tex then
            local strength=i==1 and math.max(.3,facing) or facing
            local a=math.min(1,(part.alpha or 1)*(flare.alpha or 1)*strength*night*math.min(1.5,brightness))
            if a>.004 then
                local size=M.flareTile*perTile*(flare.scale or 1)*(part.scale or 1)*(i==1 and 1 or (.35+.65*facing))
                local c=part.color or WHITE
                renderer:render(tex,sx-size/2,sy-size/2,size,size,c[1]*color[1],c[2]*color[2],c[3]*color[3],a,nil)
            end
        end
    end
    M.flares=M.flares+1
end

-- W13: T.lights built the sorted device list, each device's placement (a chain of 3x4 matrix products) and new light
-- tables every frame for every player drawn. Its inputs are the weapon's key, the shared snapshot (R.view: the same
-- tree until a part changes) and each device's on and mode. PartsVersion keeps a snapshot through a device switch
-- (TWDevice changes in place, also from TW/LaserSync), so those are read every frame (T.state, as T.lights reads them)
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
    if stale then m.list=T.lights(key,tree,parts);m.text=nil end
    return m.list,m
end

function M.lightsFor(player)
    local weapon=player:getPrimaryHandItem()
    local Runtime=TWRuntime
    local key=Runtime and Runtime.visualKey(weapon)
    if not key then return end
    -- The shared read-only snapshot (TW/Runtime R.view): every frame, unchanged until a part changes.
    local tree,_,parts,_,shared=(Runtime.view or Runtime.read)(weapon)
    if shared and not M.memoOff then return weapon,remembered(player,key,tree,parts) end
    return weapon,T.lights(key,tree,parts)
end

-- The lights' part of the retrace signature: kind and lens point of each light drawn. W13: once per remembered list
-- and night vision state instead of formatting the numbers every frame; the same string.
local function lightsText(visible)
    local text=''
    for _,l in ipairs(visible) do text=text..':'..l.kind..l.position[1]..l.position[2] end
    return text
end

-- The native torch along the bore of the animated weapon (Java part): its
-- horizontal direction; a bore pointing down meets the floor sooner (shorter
-- light), one pointing up lights less of it. False without the Java part, the
-- hand model or the option.
M.torches=0
M.lastTorch={}
function M.boreTorch(player,weapon,lights,follow)
    local zb=rawget(_G,'TWZB')
    if type(zb)~='table' or not zb.torch then return false end
    local t=T.torch[weapon]
    local f=follow and t and P.boneFrame(player)
    if not f then zb.torchOff(player);return false end
    local light=lights[1]
    for _,l in ipairs(lights) do if l.kind=='light' then light=l;break end end
    local _,_,z=P.origin(player,weapon,light.position,nil,nil,f)
    local vx,vy,vz=P.axis(player,f)
    if not z or not vx then zb.torchOff(player);return false end
    local h=math.sqrt(vx*vx+vy*vy)
    local rise=vz*P.verticalScale
    local dist,strength=t.distance,t.strength
    if rise<0 then
        local height=math.max(0,(z-player:getZ())*P.verticalScale)
        dist=math.max(2,math.min(dist,height*h/-rise+1))
    else
        strength=strength*math.max(.35,h)
    end
    zb.torch(player,weapon,vx,vy,dist,strength)
    M.torches=M.torches+1
    -- The last applied torch (QA: its reach on the floor, tools/check_torch_shots.py).
    local last=M.lastTorch
    last.dx,last.dy,last.dz,last.dist,last.strength=vx,vy,vz,dist,strength
    return true
end

-- The light of a weapon hidden by an action (P.weaponDrawn: a magazine top-off, unloading) goes
-- dark for the action: no effects, and the native torch keeps nearly no reach or strength
-- through the Java part (without it, the weak spill). The device state is not changed; the next
-- frame with the weapon drawn restores the torch.
M.dark={distance=.01,strength=.001}
M.hiddenFrames=0
function M.hiddenTorch(player,weapon)
    M.hiddenFrames=M.hiddenFrames+1
    local zb=rawget(_G,'TWZB')
    if T.torch[weapon] and type(zb)=='table' and zb.torch then
        local f=player:getForwardDirection()
        zb.torch(player,weapon,f:getX(),f:getY(),M.dark.distance,M.dark.strength)
    else
        T.aimLight(weapon,false)
    end
end

function M.renderPlayer(player,n,nvg,brightness,viewer,follow,draw)
    if not player or player:isDead() then return end
    local weapon,lights,memo=M.lightsFor(player)
    if not lights or #lights==0 then return end
    if not P.weaponDrawn(player) then M.hiddenTorch(player,weapon);return end
    local remote=not player:isLocalPlayer() and T.remoteAim and T.remoteAim(player,weapon)
    local aiming
    if player:isLocalPlayer() then aiming=player:isAiming() else aiming=remote and remote.aiming end
    -- The native torch: along the bore (Java part), else a cone along the look
    -- vector only when that is the bore.
    local bore=M.boreTorch(player,weapon,lights,follow)
    T.aimLight(weapon,bore or aiming==true or not follow)
    if draw==false then return end
    local visible={}
    for _,l in ipairs(lights) do if l.kind=='light' or nvg then visible[#visible+1]=l end end
    if #visible==0 then return end
    local now=getTimestampMs();local views=M.cache[player] or {};M.cache[player]=views
    views.seen=now
    local cached=views[n]
    local text
    if memo then
        -- The drawn lights are the remembered list filtered by night vision alone.
        memo.text=memo.text or {}
        local slot=nvg and 2 or 1
        text=memo.text[slot]
        if not text then text=lightsText(visible);memo.text[slot]=text end
    else
        text=lightsText(visible)
    end
    local signature=weapon:getID()..(aiming and ':aim' or ':idle')..(follow and ':bore' or '')..text
    if not cached or cached.signature~=signature or now-cached.time>=50 then
        cached={signature=signature,time=now,entries={}}
        local shared
        if aiming then shared=remote or R.aimState(player,weapon,visible[1]) else shared={aiming=false} end
        for _,l in ipairs(visible) do
            local entry=shared and M.traceLight(player,weapon,l,shared,n,follow)
            if entry then cached.entries[#cached.entries+1]=entry end
        end
        cached.dark,cached.light=M.ownerDarkness()
        views[n]=cached
    end
    local zoom=getCore():getZoom(n)
    local climate=getClimateManager();local fog=climate and climate:getFogIntensity() or 0
    local dark=cached.dark or 0
    for _,entry in ipairs(cached.entries) do
        local light=entry.light
        -- IR through night vision: the green phosphor image.
        local color=light.kind=='irlight' and IR_GREEN or light.color
        if light.kind=='light' then
            local scatter=(light.fx.scatter or 0)/.09
            M.drawBeam(entry,n,zoom,math.min(.5,.16*scatter*(.06+.94*dark)*(1+fog)*brightness))
        end
        local intensity=(light.intensity or 1)/1.4
        if entry.spot then
            local a=intensity*M.attenuation(entry.spot.d,light.range)*(.1+.9*dark)*brightness*.75
            M.drawSpot(entry,n,zoom,clamp(a,0,.9),color)
        end
        if entry.lensVisible then
            local facing=M.facing(entry.dx,entry.dy,entry.dz*P.verticalScale,M.camera[1],M.camera[2],M.camera[3],light.spotAngle)
            if viewer and player~=viewer then
                -- Another player's light pointing at the local player.
                local tx,ty,tz=viewer:getX()-entry.x,viewer:getY()-entry.y,(viewer:getZ()+.6-entry.z)*P.verticalScale
                if tx*tx+ty*ty<=(light.range or 30)^2 then
                    facing=math.max(facing,M.facing(entry.dx,entry.dy,entry.dz*P.verticalScale,tx,ty,tz,light.spotAngle))
                end
            end
            M.drawFlare(entry,n,zoom,dark,brightness,facing,color)
        end
        M.lights=M.lights+1
    end
    M.frames=M.frames+1
end

-- Viewpoint (optional, Java bridge TWZB.vp*): with its first or third person
-- view on, the effects are drawn in perspective in the Viewpoint frame (Java
-- ViewpointPass, depth-tested against its scene) instead of the sprites above.
-- Viewpoint lights the scene with its own flashlight; the viewer's starts at our
-- lens and shines along the bore (the call below: Java VpLights, VpFlashAim).
-- The beam, its spot and the native torch follow the weapon: every player's
-- along the bore of the animated weapon in the frame (the first-person pose,
-- recoil and actions included), not the camera or the cursor - a lowered
-- weapon lights the ground (user report 19.10.2026, item 20). Without the
-- animated frame (no hand model yet) the isometric idle axis.
-- The visible beam is a column of camera-facing slices of the Tarkov cookie
-- along the axis (additive, faded where they meet the scene: seen from behind,
-- the cookie itself), cut where the axis meets a surface; the spot is the cookie
-- on that surface, additive over Viewpoint's own lit spot; the flare stays at
-- the lens, facing the camera. The trace is renewed every 50 ms or after a
-- turn; the geometry follows the lens every frame.
M.vp={slices=14,near=.2,gain=2,soft=.03,spot=.6,spotSoft=.08,spotBias=.03,flareSoft=.02,flareBias=.03,flareScreen=.12,retrace=50,turn=.035,turnRetrace=20,
    cones=0,spots=0,flares=0,traces=0}
M.vpCache=setmetatable({},{__mode='k'})

-- The view of Viewpoint now: 0 isometric (or no bridge), 1 first person, 2 third person; and the Java table.
function M.vpState()
    local zb=rawget(_G,'TWZB')
    if type(zb)~='table' or not zb.vpState then return 0 end
    local ok,state=pcall(zb.vpState)
    if ok and type(state)=='number' and state>0 then return state,zb end
    return 0
end

-- Lens point and axis of a light in the Viewpoint frame (see above).
function M.vpAxis(player,weapon,light,f)
    local x,y,z,reach
    if f then x,y,z,reach=P.origin(player,weapon,light.position,nil,nil,f) end
    if x then
        local vx,vy,vz=P.axis(player,f)
        if vx then return x,y,z,reach,vx,vy,vz end
    end
    local ax,ay,az,ar,avx,avy,avz=axis(player,weapon,light,{aiming=false},true)
    return ax,ay,az,ar,avx,avy,avz
end

function M.vpTrace(player,light,x,y,z,reach,vx,vy,vz)
    local result,ray=trace(player,x,y,z,reach,vx,vy,vz,math.min(light.range or 30,40))
    local d=math.max(0,result.distance-ray.begin)
    local c={dx=vx,dy=vy,dz=vz,length=math.min(M.maxLength,(light.range or 30)*.8,d),axis=result.reason}
    if result.hit then c.spot=d;c.nx,c.ny,c.nz=result.hit.nx,result.hit.ny,result.hit.nz end
    result.context=nil
    M.vp.traces=M.vp.traces+1
    return c
end

function M.drawVP(zb,light,c,x,y,z,vx,vy,vz,dark,fog,brightness,eye)
    local V=M.vp
    local color=light.kind=='irlight' and IR_GREEN or light.color
    local tanHalf=math.tan(math.rad(clamp((light.spotAngle or 60)/2,1,80)))
    local cookie=light.fx.cookie or 'TW_LightCookie_weapon_flashlight_cookie'
    -- Visible beam: white lights with a Tarkov VolumetricLight only.
    if light.kind=='light' and (light.fx.scatter or 0)>0 and c.length>V.near then
        local scatter=(light.fx.scatter or 0)/.09
        local alpha=math.min(.5,.16*scatter*(.06+.94*dark)*(1+fog)*brightness)*V.gain/V.slices
        if alpha>.0005 then
            for k=1,V.slices do
                local t=V.near+(c.length-V.near)*(k-.5)/V.slices
                zb.vpSprite(x+vx*t,y+vy*t,z+vz*t,t*tanHalf*M.cookieExtent,0,color[1],color[2],color[3],alpha*M.attenuation(t,light.range),cookie,1,V.soft,0)
            end
            M.cones=M.cones+1
            V.cones=V.cones+1
        end
    end
    if c.spot then
        local d=c.spot
        local a=clamp((light.intensity or 1)/1.4*M.attenuation(d,light.range)*(.1+.9*dark)*brightness*.75,0,.9)*V.spot
        if a>.002 then
            local spot={x=x+vx*d+c.nx*.01,y=y+vy*d+c.ny*.01,z=z+vz*d+c.nz*.01,nx=c.nx,ny=c.ny,nz=c.nz,radius=clamp(d*tanHalf*M.cookieExtent,.12,3)}
            local function quad(scale,alpha)
                local s={radius=spot.radius*scale,x=spot.x,y=spot.y,z=spot.z,nx=spot.nx,ny=spot.ny,nz=spot.nz}
                zb.vpQuad(M.spotCorners(s),color[1],color[2],color[3],{alpha},cookie,1,V.spotSoft,V.spotBias)
            end
            local gi=light.fx.gi
            -- Bounce light around the spot, then the spot.
            if gi then quad(gi[2] or 2,math.min(.35,a*(gi[1] or .05)*6)) end
            quad(1,a)
            M.spots=M.spots+1
            V.spots=V.spots+1
        end
    end
    local flare=light.fx.flare
    if flare and flare.parts then
        -- The full flare as the light points at the camera, a small glow always. A lens flare seen
        -- from a few centimetres (the viewer's own weapon light) must not cover the weapon and the
        -- hand: its world size is capped to a small angle of the view, as a lens glow is (W9 torch,
        -- item 9: the light looked like it came from the hands).
        local facing=0
        if eye then facing=M.facing(vx,vy,vz*P.verticalScale,eye.x-x,eye.y-y,(eye.z-z)*P.verticalScale,light.spotAngle) end
        local cap
        if eye then
            local dx,dy,dz=eye.x-x,eye.y-y,(eye.z-z)*P.verticalScale
            cap=V.flareScreen*math.sqrt(dx*dx+dy*dy+dz*dz)
        end
        local night=.35+.65*dark
        for i,part in ipairs(flare.parts) do
            local strength=i==1 and math.max(.3,facing) or facing
            local a=math.min(1,(part.alpha or 1)*(flare.alpha or 1)*strength*night*math.min(1.5,brightness))
            if a>.004 then
                local size=M.flareTile*(flare.scale or 1)*(part.scale or 1)*(i==1 and 1 or (.35+.65*facing))
                if cap and size>cap then size=cap end
                local pc=part.color or WHITE
                zb.vpSprite(x,y,z,size/2,1,pc[1]*color[1],pc[2]*color[2],pc[3]*color[3],a,flareName(part),0,V.flareSoft,V.flareBias)
            end
        end
        M.flares=M.flares+1
        V.flares=V.flares+1
    end
end

function M.renderPlayerVP(zb,player,nvg,brightness,viewer,follow,draw,eye)
    if not player or player:isDead() then return end
    local weapon,lights=M.lightsFor(player)
    if not lights or #lights==0 then return end
    if not P.weaponDrawn(player) then
        -- Viewpoint's own flashlight (the viewer's weapon light) would shine from the hand: off this frame.
        M.hiddenTorch(player,weapon)
        if player==viewer and zb.vpFlashHidden then zb.vpFlashHidden() end
        return
    end
    local remote=not player:isLocalPlayer() and T.remoteAim and T.remoteAim(player,weapon)
    local aiming
    if player:isLocalPlayer() then aiming=player:isAiming() else aiming=remote and remote.aiming end
    -- The native torch along the bore of the animated weapon (other players' torches are what
    -- Viewpoint lights with): in Viewpoint the light always follows the weapon, lowered or raised
    -- (user report 19.10.2026, item 20), whatever the isometric option says.
    local bore=M.boreTorch(player,weapon,lights,true)
    T.aimLight(weapon,bore or aiming==true)
    if draw==false then return end
    -- IR light also when a night vision view of this frame wants it (Java VpSensor: a night
    -- vision scope); marked as IR, it shows only through night vision there.
    local ir=nvg or (zb.vpSensorIr~=nil and zb.vpSensorIr()==true)
    local visible={}
    for _,l in ipairs(lights) do if l.kind=='light' or ir then visible[#visible+1]=l end end
    if #visible==0 then return end
    -- Viewpoint's flashlight is a white light: with a white light of this weapon drawn it stands for
    -- that one; a drawn IR illuminator is it only when no white light is drawn (W9 torch, item 9).
    local white=false
    for _,l in ipairs(visible) do if l.kind=='light' then white=true;break end end
    local f=P.boneFrame(player)
    local now=getTimestampMs();local views=M.vpCache[player] or {};M.vpCache[player]=views
    views.seen=now
    local climate=getClimateManager();local fog=climate and climate:getFogIntensity() or 0
    local dark=M.ownerDarkness()
    for i,light in ipairs(visible) do
        local x,y,z,reach,vx,vy,vz=M.vpAxis(player,weapon,light,f)
        if x and vx then
            -- The viewer's own light Viewpoint's flashlight stands for starts at its lens: the white
            -- light, or an IR illuminator drawn through night vision; never a second light of the same
            -- weapon once the white one took this frame (once per frame in Java).
            local flash=light.kind=='light' or (light.kind=='irlight' and not white)
            if f and player==viewer and flash and zb.vpFlashLens then zb.vpFlashLens(x,y,z,vx,vy,vz) end
            local key=tostring(light.path)..':'..light.kind..':'..i
            local c=views[key]
            if not c or c.weapon~=weapon or now-c.time>=M.vp.retrace or now-c.time>=M.vp.turnRetrace
                and math.abs(c.dx-vx)+math.abs(c.dy-vy)+math.abs(c.dz-vz)*P.verticalScale>M.vp.turn then
                c=M.vpTrace(player,light,x,y,z,reach,vx,vy,vz);c.weapon=weapon;c.time=now;views[key]=c
            end
            if zb.vpSensorMark then zb.vpSensorMark(light.kind=='irlight' and 1 or 0) end
            M.drawVP(zb,light,c,x,y,z,vx,vy,vz,dark,fog,brightness,eye)
            if zb.vpSensorMark then zb.vpSensorMark(0) end
            M.lights=M.lights+1
        end
    end
    M.frames=M.frames+1
end

Events.OnGameStart.Add(function() M.cache=setmetatable({},{__mode='k'});M.vpCache=setmetatable({},{__mode='k'});M.memo={} end)

local nextPrune=0
function M.render()
    local viewer=getPlayer();if not viewer then return end
    -- An IR illuminator becomes the native torch when the viewer puts night vision on (a Tarkov NV
    -- scope while aiming): re-evaluate it once per change, so Viewpoint's flashlight for it starts
    -- at its lens and shines along the bore (W9 torch, item 9).
    if T.refreshNVG then T.refreshNVG(viewer) end
    -- With the effects off the native torch still follows the option below.
    local on,brightness,follow=M.options()
    local n=viewer:getPlayerNum()
    local nvg=T.viewerNVG(viewer)
    -- A thermal scope image shows no light (TW/OpticView).
    if T.viewerThermal(viewer) then on=false end
    -- Viewpoint's perspective view: into its frame while it takes primitives, never isometric.
    local vp,zb=M.vpState()
    local eye
    if vp>0 then
        if not zb.vpAccepting() then on=false end
        eye=on and zb.vpEye() or nil
    elseif TWViewpoint and TWViewpoint.isoBlocked and TWViewpoint.isoBlocked() then
        on=false
    end
    local function draw(p)
        if vp>0 then M.renderPlayerVP(zb,p,nvg,brightness,viewer,follow,on,eye) else M.renderPlayer(p,n,nvg,brightness,viewer,follow,on) end
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
        -- A player's remembered lights go with its caches (W13; Kahlua tables are never weak).
        for owner in pairs(M.memo) do if not M.cache[owner] and not M.vpCache[owner] then M.memo[owner]=nil end end
    end
end

Events.OnPostRender.Add(function()
    local ok,err=pcall(M.render)
    if not ok then
        M.errors=M.errors+1
        if M.lastError~=tostring(err) then M.lastError=tostring(err);print('[TW Light] render error: '..M.lastError) end
    end
end)
return M
