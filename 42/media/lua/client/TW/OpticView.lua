-- Night vision and thermal scopes of Tarkov: while the local player aims
-- through one, the view inside the vision cone shows the scope's image.
-- Data: TW/Data/Optics (tools/extract_optic_fx.py), the image of every scope
-- mode from the vanilla prefab (the thermal scopes from FiveF's
-- realisticThermalScopes 2.0.0: no camera aberration or motion blur, higher
-- frame rates, pixelation by sensor resolution), and the formulas of the Tarkov shaders
-- (Hidden/ThermalVision and Hidden/NightVision, read from their bytecode):
--   thermal       u = sat(sat(L*MainTexColorCoef)*fade + MinimumTemperatureValue/2
--                         - RampShift/(1-RampShift)),  colour = ramp(u),
--                 fade = min(1,exp(-distance*DepthFade))^(1/4);
--                 noise: sqrt(colour^2 + noise*NoiseIntensity*.785)
--   night vision  sat((r+g+b)*Color*Intensity*.45) of the brightened scene
--                 (recorded; the game's own night vision is shown, see below).
-- Project Zomboid has no scene temperature and Lua cannot put a shader on the
-- world, so (documented approximations):
--   * the image is replaced inside the field of the scope (one for all
--     scopes, M.FIELD), never wider than the vision cone of the aiming
--     character (the B42 cone of LightingJNI.calculateVisionCone without its
--     darkness term: the device sees in the dark);
--   * the thermal world is the scene brightness through the Tarkov ramp,
--     drawn by blending: per colour channel the least-squares line of
--     ramp(u(L)) over L, with L the brightness times ENV; at night the
--     brightness is first stretched (automatic gain, up to GAIN);
--   * warm characters (players, animals, zombies cooler) get a heat
--     silhouette at their temperature over it, only in clear line of sight:
--     walls and glass stop a thermal image;
--   * night vision is the game's own (IsoPlayer.setWearingNightVisionGoggles:
--     the scene lit as at day and the green screen shader), with the
--     periphery outside the cone darkened.
-- Drawn into the world frame (OnPostRender, before the screen shader): the
-- B42 interface is rendered off-screen and cannot be blended with the world.
local Data=require('TW/Data/Optics')
local T=require('TW/Tactical')
local Ray=require('TW/LaserRay')
local M={views={},own={},los=setmetatable({},{__mode='k'}),frames=0,passes=0,bodies=0,noiseDrawn=0,frameworkSkipped=0,errors=0}
TWOpticView=M

-- Our approximations of the Tarkov thermal input (the thermal camera image).
M.ENV=.35          -- scene brightness of PZ -> thermal input of the cold world
M.LIVING=2         -- a living body saturates every Tarkov MainTexColorCoef
M.ZOMBIE=1.4       -- the walking dead are cooler
M.ANIMAL=1.8
M.HALO=.5          -- warm air around a body: this share of its input
M.GAIN=4           -- automatic gain of the scene brightness at full night
-- Light is no heat (Java image): the scene term saturates softly at this input,
-- below the coldest body, so a lit car or lamp-lit wall never reads as a body.
M.ENV_CAP=.8
M.NV_GAIN=6        -- night vision (Java image): amplification of the scene at full night
M.HEAT_RANGE=60    -- warm bodies are looked for this far (tiles)
-- Tarkov counts pixelation blocks across the scope lens (about 2/5 of the
-- screen height in its first-person view); here the image fills the view.
-- 2.5 matched the lens; the user asked for less pixelation (2026-10-13): 5.
M.LENS=5
-- Share of the Tarkov camera aberration (radial, clean at the lens centre, see
-- tw_nightvision.frag) kept; reduced at the user's request (2026-10-13). Night
-- vision: less again, its colour ghosts were the strongest (2026-10-15).
-- The thermal scan line glitch and motion blur of the Tarkov data are not
-- drawn at all (the user's request, 2026-10-15).
M.ABERRATION=.6
M.NV_ABERRATION=.3
-- Centre of the scope lens: this far ahead of the viewer along the facing
-- (tiles), where the aim is; the aberration grows away from it.
M.LENS_AHEAD=6
-- Bodies: a 1.75 m adult (a level is 2.449 m, TW/LaserGeometry).
M.HEIGHT=1.75/2.44949
M.EYE=.62          -- the viewer's eye and the target's chest, levels above the floor
M.CHEST=.52
-- Night vision: the periphery seen by the naked eye.
M.PERIPHERY=.45
-- The cone: soft edge (degrees; at most 2/5 of the half angle of a narrow
-- scope field), segments, rings of the distance falloff (tiles).
M.EDGE=4
M.STEP=8
M.RINGS={0,6,16,36}
M.REFRESH=200      -- line of sight checks, ms

local GL_ZERO,GL_ONE,GL_SRC_COLOR,GL_ONE_MINUS_SRC_COLOR,GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA=0,1,768,769,770,771
local GL_DST_COLOR,GL_ONE_MINUS_DST_COLOR=774,775

local function sat(x) return x<0 and 0 or (x>1 and 1 or x) end
-- Colour blending of a pass; the frame's alpha is kept (sprites with a zero
-- alpha are not drawn at all, so every pass draws with alpha 1).
local function blend(src,dst)
    SpriteRenderer.instance:glBlendFuncSeparate(src,dst,GL_ZERO,GL_ONE)
end
local textures={}
local function texture(name)
    if textures[name]==nil then textures[name]=getTexture('media/textures/'..name..'.png') or false end
    return textures[name] or nil
end

-- Tarkov formulas ---------------------------------------------------------------
function M.ramp(index,u)
    local c=Data.ramps[index].colors;local n=#c
    local x=sat(u)*(n-1);local i=math.floor(x);local f=x-i
    local a=c[i+1];local b=c[math.min(n,i+2)]
    return a[1]+(b[1]-a[1])*f,a[2]+(b[2]-a[2])*f,a[3]+(b[3]-a[3])*f
end

function M.u(mode,input,fade)
    local s=mode.shift
    return sat(sat(input*mode.coef)*fade+mode.minimum*.5-s/(1-s))
end

function M.fade(mode,distance)
    return math.min(1,math.exp(-distance*(mode.depthFade or 0)))^.25
end

-- out = A + B*brightness per channel: the least-squares line of the Tarkov
-- ramp over the scene brightness 0..1 (cached by mode and fade).
local fits={}
function M.fit(mode,fade)
    local key=tostring(mode)..':'..string.format('%.3f',fade)
    if fits[key] then return fits[key] end
    local n=17;local sx,sxx=0,0;local sy,sxy={0,0,0},{0,0,0}
    for k=0,n-1 do
        local x=k/(n-1)
        local c={M.ramp(mode.palette,M.u(mode,x*M.ENV,fade))}
        sx=sx+x;sxx=sxx+x*x
        for i=1,3 do sy[i]=sy[i]+c[i];sxy[i]=sxy[i]+x*c[i] end
    end
    local A,B={},{}
    for i=1,3 do
        B[i]=(n*sxy[i]-sx*sy[i])/(n*sxx-sx*sx)
        A[i]=(sy[i]-B[i]*sx)/n
    end
    fits[key]={A=A,B=B}
    return fits[key]
end

-- B42 vision cone (LightingJNI.calculateVisionCone) as seen by day: degrees.
function M.coneDegrees(player)
    local ok,cone=pcall(function()
        local stats=player:getStats()
        local c=math.min(144,144-72*stats:get(CharacterStat.FATIGUE))
        if stats:isAtMaximum(CharacterStat.FATIGUE) then c=c-36 end
        local moodles=player:getMoodles()
        if moodles:getMoodleLevel(MoodleType.DRUNK)>=2 then c=c-.36*stats:get(CharacterStat.INTOXICATION) end
        if moodles:getMoodleLevel(MoodleType.PANIC)==4 then c=c-36 end
        c=math.max(18,math.min(180,c))
        if player:hasTrait(CharacterTrait.EAGLE_EYED) then c=c+36 end
        return c
    end)
    return ok and type(cone)=='number' and cone or 144
end

-- The field of a night vision or thermal scope in degrees: one for every
-- scope and mode, at most a share of the character's vision cone. FB2 item 2
-- (2026-10-17) halved the previous 76.5 degrees and capped it at half the
-- cone; the user's report of 2026-10-21 (item 6) takes 35 percent off both.
-- The scope cameras' own fields of view (Tarkov ScopeCameraData, FiveF's for
-- the thermal scopes) stay in the data (mode.fov) and do not set it.
M.NARROW=0.65
M.FIELD=38.25*M.NARROW
M.CONE_SHARE=0.5*M.NARROW
function M.viewDegrees(player,mode)
    return math.min(M.FIELD,M.coneDegrees(player)*M.CONE_SHARE)
end

-- Soft edge of a field of this half angle (radians).
function M.edge(half)
    return math.min(math.rad(M.EDGE),half*.4)
end

-- Scope state ---------------------------------------------------------------------
function M.modeIndex(part,item)
    local md=part:getModData()
    local s=md.TWOptic
    if type(s)~='table' then s={mode=1};md.TWOptic=s end
    if type(s.mode)~='number' or s.mode~=math.floor(s.mode) or s.mode<1 or s.mode>#item.modes then s.mode=1 end
    return s.mode
end

-- The active Tarkov sight of the weapon in hands, when it is a night vision or
-- thermal scope: its item data, mode, template and part.
function M.sight(player)
    local weapon=player and player:getPrimaryHandItem()
    local Runtime=TWRuntime
    if not Runtime or not Runtime.isWeapon(weapon) then return end
    local md=weapon:getModData().TW
    local path=md and md.activeSight
    if not path then return end
    local tree,_,parts=(Runtime.view or Runtime.read)(weapon)
    local tpl=tree[path]
    local item=tpl and Data.items[tpl]
    local part=parts[path]
    if not item or not part then return end
    local index=M.modeIndex(part,item)
    return item.modes[index],index,item,tpl,part
end

function M.enabled()
    return not T.opticOptions or T.opticOptions()~=false
end

-- The game's night vision flag belongs to whoever set it last. While aiming
-- through a scope this module asserts its value every update (after every
-- other handler, see OnGameStart); a change by another mod meanwhile is kept
-- as the value to restore.
function M.native(player,value)
    local n=player:getPlayerNum()
    local o=M.own[n]
    local current=player:isWearingNightVisionGoggles()
    if value==nil then
        if o then
            if current==o.value and current~=o.prior then player:setWearingNightVisionGoggles(o.prior) end
            M.own[n]=nil
        end
        return
    end
    if not o then o={prior=current};M.own[n]=o
    elseif current~=o.value then o.prior=current end
    if current~=value then player:setWearingNightVisionGoggles(value) end
    o.value=value
end

-- The Java part (ZombieBuddy, TWZB): the scope image as Tarkov's shaders draw
-- it, computed from the raw world frame. Without it the Lua drawing below is
-- used, with the game's own night vision.
function M.java()
    local java=rawget(_G,'TWZB')
    return type(java)=='table' and java.setOptic and java or nil
end

-- Automatic gain of a dark scene (as a thermal imager or an image intensifier
-- has): 1 by day .. GAIN at full night.
function M.gain(limit)
    local climate=getClimateManager()
    local night=climate and climate:getNightStrength() or 0
    return 1/math.max(1/limit,1-(1-1/limit)*night)
end

-- The view as the Java part reads it (pz.escapefromzomboidfirearms.zb.OpticState.read).
function M.javaView(player,mode)
    local f=player:getForwardDirection()
    local half=math.rad(M.viewDegrees(player,mode)/2)
    local t={kind=mode.kind,px=player:getX(),py=player:getY(),pz=player:getZ(),base=math.atan2(f:getY(),f:getX()),half=half,edge=M.edge(half)}
    if mode.bloom then t.bloomIntensity,t.bloomThreshold,t.bloomBlur=mode.bloom[1],mode.bloom[2],mode.bloom[3] end
    -- The camera aberration of the mode (0 where the prefab turns it off).
    t.aberration=(mode.aberration or 0)*(mode.kind=='thermal' and M.ABERRATION or M.NV_ABERRATION)
    t.lx,t.ly=t.px+f:getX()*M.LENS_AHEAD,t.py+f:getY()*M.LENS_AHEAD
    if M.DEBUG_MIX then t.debugMix=M.DEBUG_MIX end -- QA only (see-through image)
    if mode.kind=='thermal' then
        t.palette=mode.palette;t.coef=mode.coef;t.minimum=mode.minimum;t.shift=mode.shift;t.depthFade=mode.depthFade or 0
        t.noise=mode.noise or 0;t.fps=mode.fps or 0
        t.pixelBlocks=mode.pixelation and mode.pixelation.blocks*M.LENS or 0;t.pixelAlpha=mode.pixelation and mode.pixelation.alpha or 0
        t.env=M.ENV;t.gain=M.gain(M.GAIN);t.envCap=M.ENV_CAP
    else
        local c=mode.color or {.18,.41,.18}
        t.nvR,t.nvG,t.nvB=c[1],c[2],c[3];t.nvIntensity=mode.intensity or 2;t.nvNoise=mode.noise or 0
        t.nvGain=M.gain(M.NV_GAIN)
    end
    return t
end

-- Warm things of the thermal image for the Java heat buffer, renewed with the
-- line of sight checks.
local heatSent={}
function M.sendHeat(java,n,player,now,mode)
    local sent=heatSent[n]
    if sent and now-sent<M.REFRESH then return end
    heatSent[n]=now
    local f=player:getForwardDirection()
    local half=math.rad(M.viewDegrees(player,mode)/2)
    local fan={half=half,edge=M.edge(half),base=math.atan2(f:getY(),f:getX()),px=player:getX(),py=player:getY(),pz=player:getZ(),radius=M.HEAT_RANGE}
    local list={}
    for _,b in ipairs(M.warmBodies(fan,player,now)) do list[#list+1]={obj=b.obj,input=b.input} end
    if M.heatSources then for _,h in ipairs(M.heatSources(fan,player)) do list[#list+1]=h end end
    java.setHeat(n,list)
    M.heatSent=#list
end

-- Viewpoint's first or third person view (Java bridge, TWZB.vpState): the scope
-- image is made for the isometric frame (the field is a cone on the floor), so
-- it is not shown there at all - neither the Java image nor the Lua drawing -
-- until a perspective one exists. The scope modes stay as they are.
function M.viewpoint()
    local compat=rawget(_G,'TWViewpoint')
    if type(compat)=='table' and type(compat.isoBlocked)=='function' then
        local ok,blocked=pcall(compat.isoBlocked)
        if ok and blocked then return true end
    end
    local zb=rawget(_G,'TWZB')
    if type(zb)~='table' or not zb.vpState then return false end
    local ok,state=pcall(zb.vpState)
    return ok and type(state)=='number' and state>0
end

function M.update(player)
    if not player or not player:isLocalPlayer() then return end
    local n=player:getPlayerNum()
    if M.viewpoint() then
        local java=M.java()
        if java and M.views[n] then java.clearOptic(n);heatSent[n]=nil end
        -- The game's night vision flag goes back to whoever set it.
        M.native(player,nil)
        local previous=M.views[n]
        M.views[n]=nil
        if previous and T.refreshNVG then T.refreshNVG(player) end
        return
    end
    local mode,index,tpl,part,_
    if M.enabled() and not player:isDead() and (TWZB and TWZB.aimIntent or player.isAiming)(player) then mode,index,_,tpl,part=M.sight(player) end
    local java=M.java()
    if java then
        -- The image is drawn from the raw scene: the game's night vision flag stays as other mods set it.
        M.native(player,nil)
        if mode then
            java.setOptic(n,M.javaView(player,mode))
            if mode.kind=='thermal' then M.sendHeat(java,n,player,getTimestampMs(),mode) end
        elseif M.views[n] then
            java.clearOptic(n)
            heatSent[n]=nil
        end
    elseif mode and mode.kind=='nv' then M.native(player,true)
    elseif mode and mode.kind=='thermal' then M.native(player,false)
    else M.native(player,nil) end
    local previous=M.views[n]
    if mode then
        M.views[n]={kind=mode.kind,mode=mode,index=index,tpl=tpl,part=part,java=java~=nil}
    else
        M.views[n]=nil
    end
    if (previous and previous.kind)~=(mode and mode.kind) and T.refreshNVG then T.refreshNVG(player) end
end

-- What the local player sees through the scope now: 'nv', 'thermal' or nil.
function T.opticView(player)
    local v=player and M.views[player:getPlayerNum()]
    return v and v.kind
end

-- Night vision / thermal data of a scope template (nil for other sights).
function M.item(tpl)
    return tpl and Data.items[tpl] or nil
end

-- Name of a scope mode: night vision or the thermal palette.
function M.modeText(item,index)
    local chosen=item.modes[index]
    return getText(chosen.kind=='nv' and 'IGUI_TW_OpticMode_nv' or ('IGUI_TW_OpticMode_thermal_'..chosen.palette))
end

-- Set the mode of a scope part (Tarkov's sight mode switch); a short note names it.
function M.setMode(player,part,item,tpl,index)
    if not part or not item or not item.modes[index] then return false end
    M.modeIndex(part,item)
    part:getModData().TWOptic.mode=index
    local short=getText('IGUI_TW_Short_'..tpl)
    local note=string.format('%s %d/%d: %s',short,index,#item.modes,M.modeText(item,index))
    -- Viewpoint first person: a screen line (TW_SightNotice), not a note at the camera.
    local notice=rawget(_G,'TWSightNotice')
    if not (notice and notice(player,note,'opticmode')) and player.setHaloNote then player:setHaloNote(note,220,220,220,250) end
    if player.playSound then player:playSound(T.sounds and T.sounds.mode or 'TW_DeviceMode') end
    return true
end

-- Next mode of the active scope.
function M.cycle(player)
    local mode,index,item,tpl,part=M.sight(player)
    if not mode or #item.modes<2 then return false end
    return M.setMode(player,part,item,tpl,index%#item.modes+1)
end

-- Geometry ------------------------------------------------------------------------
local function project(n,x,y,z,zoom)
    return (isoToScreenX(n,x,y,z)-getPlayerScreenLeft(n))*zoom,(isoToScreenY(n,x,y,z)-getPlayerScreenTop(n))*zoom
end

-- World radius that covers the viewport from the player.
local function reach(n,player)
    local w,h=getCore():getScreenWidth(),getCore():getScreenHeight()
    local z=math.floor(player:getZ())
    local px,py=player:getX(),player:getY()
    local r=20
    for _,c in ipairs({{0,0},{w,0},{0,h},{w,h}}) do
        local x,y=screenToIsoX(n,c[1],c[2],z),screenToIsoY(n,c[1],c[2],z)
        r=math.max(r,math.sqrt((x-px)^2+(y-py)^2)+3)
    end
    return math.min(r,140)
end

-- The fan of the cone on the floor plane: angle columns (with the weight of
-- the soft edge, 1 inside .. 0 outside) and rings, projected to the frame.
function M.fan(n,player,half,inverse)
    local zoom=getCore():getZoom(n)
    local f=player:getForwardDirection()
    local base=math.atan2(f:getY(),f:getX())
    local px,py,pz=player:getX(),player:getY(),player:getZ()
    local radius=reach(n,player)
    local angles={}
    local function add(a,w) angles[#angles+1]={a=a,w=w} end
    local edge=M.edge(half)
    if inverse then
        -- Everything outside the cone: from its right edge round to its left.
        local from,to=half,2*math.pi-half
        add(from,0)
        local span=to-from-2*edge
        local steps=math.max(2,math.ceil(math.deg(span)/M.STEP))
        for k=0,steps do add(from+edge+span*k/steps,1) end
        add(to,0)
    else
        add(-half-edge,0)
        local steps=math.max(2,math.ceil(math.deg(2*half)/M.STEP))
        for k=0,steps do add(-half+2*half*k/steps,1) end
        add(half+edge,0)
    end
    local rings={}
    for _,r in ipairs(M.RINGS) do if r<radius then rings[#rings+1]=r end end
    rings[#rings+1]=radius
    local points={}
    for i,a in ipairs(angles) do
        local c,s=math.cos(base+a.a),math.sin(base+a.a)
        points[i]={}
        for j,r in ipairs(rings) do
            local x,y=project(n,px+c*r,py+s*r,pz,zoom)
            points[i][j]={x,y}
        end
    end
    return {angles=angles,rings=rings,points=points,zoom=zoom,base=base,px=px,py=py,pz=pz,radius=radius,half=half,edge=edge}
end

-- One blending pass over the fan; colour(i,j) gives each vertex r,g,b,a.
local function pass(fan,colour)
    local renderer=SpriteRenderer.instance
    local P=fan.points
    for i=1,#fan.angles-1 do
        for j=1,#fan.rings-1 do
            local a,b,c,d=P[i][j],P[i+1][j],P[i+1][j+1],P[i][j+1]
            local r1,g1,b1,a1=colour(i,j);local r2,g2,b2,a2=colour(i+1,j)
            local r3,g3,b3,a3=colour(i+1,j+1);local r4,g4,b4,a4=colour(i,j+1)
            renderer:render(nil,a[1],a[2],b[1],b[2],c[1],c[2],d[1],d[2],r1,g1,b1,a1,r2,g2,b2,a2,r3,g3,b3,a3,r4,g4,b4,a4,nil)
        end
    end
    M.passes=M.passes+1
end

-- Thermal image -----------------------------------------------------------------
-- The world inside the cone through the ramp: out = A + B*in per channel, as
-- three blending passes (invert the falling channels, scale, offset); the
-- soft edge interpolates every pass to the identity.
function M.thermalWorld(fan,mode)
    local lines={}
    for j,r in ipairs(fan.rings) do lines[j]=M.fit(mode,M.fade(mode,r)) end
    local sign={}
    local ref=lines[1]
    local inverted=false
    for c=1,3 do sign[c]=ref.B[c]<0;inverted=inverted or sign[c] end
    local function w(i) return fan.angles[i].w end
    -- Automatic gain, as a thermal imager has: a PZ night scene is dark where a
    -- thermal one is not, so the brightness is stretched by up to GAIN at night.
    local night=getClimateManager():getNightStrength() or 0
    local gain=1/math.max(1/M.GAIN,1-(1-1/M.GAIN)*night)
    while gain>1.001 do
        local step=math.min(2,gain)
        blend(GL_DST_COLOR,GL_ONE)
        pass(fan,function(i) local k=w(i)*(step-1);return k,k,k,1 end)
        gain=gain/step
    end
    if inverted then
        blend(GL_ONE_MINUS_DST_COLOR,GL_ONE_MINUS_SRC_COLOR)
        pass(fan,function(i)
            local k=w(i)
            return sign[1] and k or 0,sign[2] and k or 0,sign[3] and k or 0,1
        end)
    end
    blend(GL_ZERO,GL_SRC_COLOR)
    pass(fan,function(i,j)
        local k,B=w(i),lines[j].B
        local function s(c) local v=sign[c] and -B[c] or B[c];return 1-k+k*math.min(1,math.max(0,v)) end
        return s(1),s(2),s(3),1
    end)
    blend(GL_ONE,GL_ONE)
    pass(fan,function(i,j)
        local k,L=w(i),lines[j]
        local function o(c) return k*math.max(0,sign[c] and L.A[c]+L.B[c] or L.A[c]) end
        return o(1),o(2),o(3),1
    end)
end

local function warmth(obj)
    if instanceof(obj,'IsoZombie') then return M.ZOMBIE end
    if instanceof(obj,'IsoAnimal') then return M.ANIMAL,true end
    if instanceof(obj,'IsoPlayer') then return M.LIVING end
end

local function call(obj,name)
    local f=obj[name]
    return f~=nil and f(obj)==true
end

function M.pose(obj)
    if call(obj,'isProne') or call(obj,'isCrawling') or call(obj,'isKnockedDown') or call(obj,'isOnFloor') then return 'prone' end
    if call(obj,'isSitOnGround') then return 'sit' end
    if call(obj,'isSneaking') then return 'crouch' end
    return 'stand'
end

-- Walls, glass and vehicles block a thermal image. Characters are depth-tested
-- as models in ModelHeat: a single ray hitting another body's coarse laser
-- shape must not remove the target's entire visible silhouette from the heat
-- buffer (overlapping crowds still have visible heads, shoulders and limbs).
function M.clear(player,obj,context)
    local x1,y1,z1=player:getX(),player:getY(),player:getZ()+M.EYE
    local x2,y2,z2=obj:getX(),obj:getY(),obj:getZ()+(M.pose(obj)=='prone' and .1 or M.CHEST)
    local dx,dy,dz=x2-x1,y2-y1,z2-z1
    local length=math.sqrt(dx*dx+dy*dy+dz*dz)
    if length<.5 then return true end
    local ray={x=x1,y=y1,z=z1,dx=dx/length,dy=dy/length,dz=dz/length,range=length-.4,begin=0,owner=player}
    return not Ray.blocked(ray,player,context,'body')
end

-- Warm characters in the cone with a clear line of sight, far ones first.
function M.warmBodies(fan,player,now)
    local list={}
    -- IsoCell.getObjectList is a Java Set; the list view is the one Lua can index.
    local objects=getCell():getObjectListForLua()
    local cosHalf=math.cos(fan.half+(fan.edge or M.edge(fan.half)))
    local fx,fy=math.cos(fan.base),math.sin(fan.base)
    local context=Ray.newContext()
    for i=0,objects:size()-1 do
        local obj=objects:get(i)
        -- Level, distance and cone first (three reads), the kind and state of the body only inside the fan: the same
        -- bodies, without five more reflective calls for every zombie of the cell (performance pass 23.10.2026).
        if math.abs(obj:getZ()-fan.pz)<1.5 then
            local dx,dy=obj:getX()-fan.px,obj:getY()-fan.py
            local d=math.sqrt(dx*dx+dy*dy)
            local input,animal
            if d<fan.radius and (d<.75 or (dx*fx+dy*fy)/d>=cosHalf) then input,animal=warmth(obj) end
            if input and not obj:isDead() and not call(obj,'isInvisible') then
                local entry=M.los[obj]
                if not entry or now-entry.time>=M.REFRESH then
                    entry={time=now,clear=obj==player or M.clear(player,obj,context)}
                    M.los[obj]=entry
                end
                if entry.clear then list[#list+1]={obj=obj,input=input,animal=animal,d=d,depth=obj:getX()+obj:getY()} end
            end
        end
    end
    table.sort(list,function(a,b) return a.depth<b.depth end)
    return list
end

-- One body: a halo of warm air, then the silhouette, as a billboard (or flat on
-- the floor for a lying body), in the ramp colour of its temperature.
function M.drawBody(n,zoom,mode,body)
    local obj=body.obj
    local renderer=SpriteRenderer.instance
    local fade=M.fade(mode,body.d)
    local r,g,b=M.ramp(mode.palette,M.u(mode,body.input,fade))
    local hr,hg,hb=M.ramp(mode.palette,M.u(mode,body.input*M.HALO,fade))
    local x,y,z=obj:getX(),obj:getY(),obj:getZ()
    local f=obj:getForwardDirection()
    local fx,fy=f:getX(),f:getY()
    local pose=M.pose(obj)
    local halo,silhouette=texture('TW_HeatHalo'),texture(pose=='prone' and 'TW_HeatBodyProne' or 'TW_HeatBody')
    if body.animal then
        local size=math.max(.4,math.min(2,obj.getAnimalSize and obj:getAnimalSize() or 1))
        silhouette=halo
        local sx,sy=project(n,x,y,z,zoom)
        local ex,ey=project(n,x+.5,y-.5,z,zoom)
        local w=math.sqrt((ex-sx)^2+(ey-sy)^2)*size*1.2
        if halo then renderer:render(halo,sx-w,sy-w*.9,w*2,w*1.4,hr,hg,hb,.45,nil) end
        if silhouette then renderer:render(silhouette,sx-w*.6,sy-w*.6,w*1.2,w*.8,r,g,b,1,nil) end
        M.bodies=M.bodies+1
        return
    end
    if pose=='prone' then
        local L,W=1.75*.5,.875*.5
        local nx,ny=-fy,fx
        local cz=z+.02
        local c={{x-fx*L-nx*W,y-fy*L-ny*W},{x+fx*L-nx*W,y+fy*L-ny*W},{x+fx*L+nx*W,y+fy*L+ny*W},{x-fx*L+nx*W,y-fy*L+ny*W}}
        local s={}
        for i,p in ipairs(c) do s[i]={project(n,p[1],p[2],cz,zoom)} end
        if halo then
            local cx,cy=project(n,x,y,cz,zoom)
            local hw=math.sqrt((s[2][1]-s[1][1])^2+(s[2][2]-s[1][2])^2)*.85
            renderer:render(halo,cx-hw,cy-hw*.6,hw*2,hw*1.2,hr,hg,hb,.4,nil)
        end
        if silhouette then
            renderer:render(silhouette,s[1][1],s[1][2],s[2][1],s[2][2],s[3][1],s[3][2],s[4][1],s[4][2],r,g,b,1,nil)
        end
        M.bodies=M.bodies+1
        return
    end
    local height=M.HEIGHT*(pose=='sit' and .55 or (pose=='crouch' and .8 or 1))
    local bx,by=project(n,x,y,z,zoom)
    local _,ty=project(n,x,y,z+height,zoom)
    -- Screen-lateral metre: along (1,-1)/sqrt(2) on the floor.
    local lx,ly=project(n,x+.70711,y-.70711,z,zoom)
    local metre=math.sqrt((lx-bx)^2+(ly-by)^2)
    -- Front or back to the camera (it looks along -x,-y): full shoulders; side-on narrower.
    local facing=math.abs((fx+fy)*.70711)
    local w=.875*metre*(.6+.4*facing)
    local h=by-ty
    if halo then renderer:render(halo,bx-w*1.1,ty-h*.12,w*2.2,h*1.2,hr,hg,hb,.35,nil) end
    if silhouette then renderer:render(silhouette,bx-w/2,ty,w,h,r,g,b,1,nil) end
    M.bodies=M.bodies+1
end

-- Tarkov noise (pass 1 of Hidden/ThermalVision): sqrt(c^2 + n*k), k =
-- NoiseIntensity*.785; drawn additively as c + n*sqrt(k/mean(n)), which is the
-- same increase on a dark pixel. Screen-fixed grain, a new offset every frame
-- of the scope (its stuck frame rate).
function M.noise(fan,mode,seed)
    local k=(mode.noise or 0)*.785
    local tex=texture(Data.noise.texture)
    if k<=0 or not tex then return end
    local gain=math.sqrt(k/math.max(.001,Data.noise.meanAlpha))
    local renderer=SpriteRenderer.instance
    blend(GL_SRC_ALPHA,GL_ONE)
    local size=tex:getWidth()
    local ox,oy=(seed*0.6180339)%1,(seed*0.7548776)%1
    local P=fan.points
    local layers=math.ceil(gain)
    local a=gain/layers
    for _=1,layers do
        for i=2,#fan.angles-2 do
            for j=1,#fan.rings-1 do
                local p1,p2,p3,p4=P[i][j],P[i+1][j],P[i+1][j+1],P[i][j+1]
                renderer:render(tex,p1[1],p1[2],p2[1],p2[2],p3[1],p3[2],p4[1],p4[2],
                    p1[1]/size+ox,p1[2]/size+oy,p2[1]/size+ox,p2[2]/size+oy,p3[1]/size+ox,p3[2]/size+oy,p4[1]/size+ox,p4[2]/size+oy,1,1,1,a)
            end
        end
    end
    M.noiseDrawn=M.noiseDrawn+1
end

-- The scope's stuck frame rate (StuckFPSUtilities): the set of warm bodies
-- and the noise change only this often (the bodies themselves move with the
-- characters).
local stuck={}
function M.thermal(n,player,view)
    local mode=view.mode
    local now=getTimestampMs()
    local half=math.rad(M.viewDegrees(player,mode)/2)
    local fan=M.fan(n,player,half)
    M.thermalWorld(fan,mode)
    local s=stuck[n] or {}
    stuck[n]=s
    local interval=(mode.fps or 0)>0 and 1000/mode.fps or 0
    if not s.bodies or s.mode~=mode or now-(s.time or 0)>=interval then
        s.time,s.mode=now,mode
        s.seed=(s.seed or 0)+1
        s.bodies={}
        for _,b in ipairs(M.warmBodies(fan,player,now)) do
            s.bodies[#s.bodies+1]={obj=b.obj,input=b.input,animal=b.animal,d=b.d}
        end
    end
    blend(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA)
    for _,b in ipairs(s.bodies) do
        if b.obj and not b.obj:isDead() then M.drawBody(n,fan.zoom,mode,b) end
    end
    M.noise(fan,mode,s.seed)
end

-- Night vision: the game's own image (set in M.update); the rest of the
-- screen, seen past the scope, is darkened.
function M.nightVision(n,player,view)
    local half=math.rad(M.viewDegrees(player,view.mode)/2)
    local fan=M.fan(n,player,half,true)
    blend(GL_ZERO,GL_SRC_COLOR)
    local k=M.PERIPHERY
    pass(fan,function(i)
        local v=1-fan.angles[i].w*(1-k)
        return v,v,v,1
    end)
end

function M.render()
    local player=getPlayer()
    if not player then return end
    local n=player:getPlayerNum()
    local view=M.views[n]
    if not view or view.java or M.viewpoint() then return end
    local renderer=SpriteRenderer.instance
    -- The sprite styles set their own blending for every batch; as the game's
    -- night vision overlay does (RenderSettings), they are ignored while the
    -- passes below choose theirs, then the standard blending comes back.
    renderer:glIgnoreStyles(true)
    local ok,err=pcall(view.kind=='thermal' and M.thermal or M.nightVision,n,player,view)
    renderer:glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA)
    renderer:glIgnoreStyles(false)
    if not ok then error(err) end
    M.frames=M.frames+1
end

Events.OnPostRender.Add(function()
    local ok,err=pcall(M.render)
    if not ok then
        M.errors=M.errors+1
        if M.lastError~=tostring(err) then M.lastError=tostring(err);print('[TW Optic] render error: '..M.lastError) end
    end
end)

-- Beathoven's Night Vision Framework: its goggles count as night vision for
-- IR light (T.viewerNVG) and its screen overlay steps aside while the scope
-- image is shown, or the head device's image in Viewpoint's view
-- (TW_ViewpointSensors). Only its public table is read; nothing else is changed.
function M.nightVisionFramework()
    local nv=rawget(_G,'NV')
    if type(nv)=='table' and type(nv.State)=='table' then return nv end
end

function M.hookFramework()
    local nv=M.nightVisionFramework()
    if M.frameworkHooked or not nv or type(nv.NVPanel)~='table' or type(nv.NVPanel.render)~='function' then return end
    local render=nv.NVPanel.render
    nv.NVPanel.render=function(self,...)
        local player=getPlayer()
        local sensors=rawget(_G,'TWViewpointSensors')
        if player and (M.views[player:getPlayerNum()] or sensors and sensors.headShown) then M.frameworkSkipped=M.frameworkSkipped+1;return end
        return render(self,...)
    end
    M.frameworkHooked=true
end

-- Registered at game start, after every handler added while mods load: the
-- night vision flag asserted here is the one the frame is rendered with.
local registered=false
Events.OnGameStart.Add(function()
    M.views,M.own={},{}
    M.los=setmetatable({},{__mode='k'})
    stuck={}
    M.hookFramework()
    if not registered then
        registered=true
        Events.OnPlayerUpdate.Add(M.update)
    end
end)
return M
