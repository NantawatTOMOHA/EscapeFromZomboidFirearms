-- Our sight state, independent of Viewpoint and of any rendering backend.
-- Scope indices are Tarkov's zero-based indices; Lua mode indices start at one.
local C=require('TW/Core')
local Data=require('TW/Data/ViewpointOptics')
local Channels=require('TW/Data/SightChannels')
local Flight=require('TW/Data/GrenadeFlight')
local S={revision=0,seen=setmetatable({},{__mode='k'}),pending=setmetatable({},{__mode='k'}),
    pendingZero=setmetatable({},{__mode='k'}),vtbSeen=setmetatable({},{__mode='k'}),
    outgoing=setmetatable({},{__mode='k'}),ironCache=setmetatable({},{__mode='k'}),latest={},
    published={},remote={},pendingSync={},syncRevision=0}
TWSight=S

-- Reticle brightness dial of a sight whose mode draws a mark the illumination multiplier reaches (user's
-- item 14, 26.10.2026: "the ability to change the brightness of the reticle (the dot) with a hotkey,
-- default Left Alt + Left Shift + mouse wheel"). 1.00 is the default step and exactly the brightness the
-- sight had before this dial, so a sight without a saved step looks the same; the ladder below it is a dim
-- dot, above it a bright one. The dial multiplies the authored Tarkov illumination (ReticleGeometry.color);
-- the renderer has no bloom pass, so the bright end is the source colour saturating, not added glare.
S.BRIGHTNESS={.15,.30,.55,1.00,1.55,2.20,3.00}
S.BRIGHTNESS_DEFAULT=4

local EPS=.0001
local function number(x) return type(x)=='number' and x==x and math.abs(x)<1e20 end
local function clamp(x,a,b) return math.max(a,math.min(b,x)) end

-- The stored step (1..#S.BRIGHTNESS); a missing or stale value is the default, never a guessed brightness.
function S.brightnessIndex(state)
    local value=state and tonumber(state.brightness)
    if not value or value~=math.floor(value) or value<1 or value>#S.BRIGHTNESS then return S.BRIGHTNESS_DEFAULT end
    return value
end

function S.brightnessValue(state) return S.BRIGHTNESS[S.brightnessIndex(state)] end

local function rgbMax(row)
    if not row then return 0 end
    return math.max(tonumber(row[1]) or 0,tonumber(row[2]) or 0,tonumber(row[3]) or 0)
end

-- Can this mark carry light the brightness dial changes? The branches are ReticleGeometry.color's:
-- a collimator's _Color *HDR/1.3, an OpticSight texture's rgb + _MarkLightness, a Custom/Crossfair
-- mesh's _Color * vertex colour, and a lens layer's _MarkLightness/_EmissionColor. A black etched mark
-- (a mesh of black vertices, a black texture with no lightness) has no light to modulate: Tarkov has
-- no brightness control for such a sight either, so the dial is not offered there.
local function litLayer(view,layer)
    local material=layer.material and view.materials and view.materials[layer.material]
    local properties=material and material.properties
    local params=material and material.shaderParameters
    local projection=layer.projection
    local kind=projection and projection.kind
    if kind=='collimatorAngular' then
        local hdr=tonumber(params and params._HDR) or 1
        return hdr>0 and rgbMax(properties and properties._Color)>0
    elseif kind=='cameraMeshPostScale' then
        if rgbMax(properties and properties._Color)<=0 then return false end
        local colors=layer.vertexColors
        for _,row in ipairs(colors and colors.palette or {}) do
            if rgbMax(row)>0 then return true end
        end
        return false
    elseif kind=='scopeTexture' then
        if (tonumber(properties and properties._MarkLightness) or 0)>0 then return true end
        local texture=layer.textureId and Data.textures and Data.textures[layer.textureId]
        local extrema=texture and texture.rgbaExtrema
        for i=1,3 do
            local row=extrema and extrema[i]
            if row and (tonumber(row[2]) or 0)>0 then return true end
        end
        return false
    end
    local lightness=tonumber(properties and properties._MarkLightness)
    if not lightness then return false end
    return (lightness>0 and rgbMax(properties and properties._Color)>0)
        or rgbMax(properties and properties._EmissionColor)>0
end

-- A mode of a sight whose mark the multiplier reaches: collimators and holographic sights, illuminated and
-- coloured optic reticles (the PiP picture too, where optical 1x uses it), and night-sight/thermal reticles.
-- A black etched mark, an iron sight and an aim-only channel carry no such light, so there the wheel keeps
-- its other meaning (zoom, hotbar) and the dial is not offered.
function S.brightnessAdjustable(view)
    local mode=view and view.modeProfile
    if not (mode and mode.reticles) then return false end
    for i=1,#mode.reticles do
        if litLayer(view,mode.reticles[i]) then return true end
    end
    return false
end

function S.distances(channel,weapon)
    -- An underbarrel launcher's own range list (Tarkov launcher item SightingRange).
    if channel.launcher then return channel.launcher.distances end
    -- W10 launchersight: an iron sight on a standalone launcher zeroes at the launcher's IronSightRange, as Tarkov's iron
    -- branch does (ProceduralWeaponAnimation: the point Max(25, IronSightRange) along the align line;
    -- Weapon.CalculateShotDirectionForIron(..., Template.IronSightRange)), not at the rifle list of a rear sight part.
    if (channel.kind=='iron' or channel.kind=='aim') and weapon and TWRuntime and Flight.launchers[weapon:getFullType()] then
        local key=TWRuntime.key(weapon)
        local root=key and Channels.calibrationDistances[C.root(key)]
        if root then return root[1] end
    end
    local scopes=Channels.calibrationDistances[channel.tpl]
    if scopes and scopes[channel.channel+1] then return scopes[channel.channel+1] end
    -- A barrel/cover/receiver camera without a SightComponent retains weapon iron zero.
    if (channel.kind=='iron' or channel.kind=='aim') and weapon and TWRuntime then
        local key=TWRuntime.key(weapon)
        local root=key and Channels.calibrationDistances[C.root(key)]
        if root then return root[1] end
    end
    return {}
end

function S.zeroIndex(channel,weapon,distance)
    if not number(distance) then return end
    for i,d in ipairs(S.distances(channel,weapon)) do if d==distance then return i end end
end

function S.curve(id,x)
    local curve=Data.curves[id]
    if not curve then return x end
    local p=curve.points
    if x<=p[1][1] then return p[1][2] end
    for i=2,#p do
        if x<=p[i][1] then
            local a,b=p[i-1],p[i]
            return a[2]+(b[2]-a[2])*(x-a[1])/(b[1]-a[1])
        end
    end
    return p[#p][2]
end

function S.range(channel)
    local mode=channel.modes[1]
    local camera=mode and mode.profile and mode.profile.camera
    -- Hybrid owners also contain main-view collimators/backup aiming points.
    -- Their owner's adjustable template is not their own optical camera.
    if not camera or camera.status~='continuous' then return end
    local zoom=channel.profile and channel.profile.zoom
    local r=zoom and zoom.minMaxFov
    if zoom and zoom.kind=='continuous' and zoom.adjustable and r and number(r.x) and number(r.y)
        and r.x>r.y and r.y>0 then return r.y,r.x,r.z end
end

function S.steps(channel)
    local result,used={},{}
    if S.range(channel) then return result end
    for i,mode in ipairs(channel.modes) do
        local z=mode.nominalZoom
        if number(z) and z>0 and not used[z] then
            result[#result+1]={zoom=z,mode=i};used[z]=true
        end
    end
    table.sort(result,function(a,b) return a.zoom<b.zoom end)
    return result
end

function S.adjustable(channel)
    return S.range(channel)~=nil or #S.steps(channel)>1
end

-- A deployed underbarrel launcher: the SWMG GL item of a TW host assembly
-- (TWLaunchers.hostKey), its host key, tree and parts, and the launcher's path/tpl.
function S.deployed(weapon)
    local L=rawget(_G,'TWLaunchers')
    local key=L and L.hostKey and weapon and L.hostKey(weapon)
    if not key or not TWRuntime then return end
    local tree,_,parts=TWRuntime.read(weapon)
    local found
    for path,tpl in pairs(tree or {}) do
        if weapon:getFullType()=='EscapeFromZomboidFirearms.GL_'..tpl then
            if found then return end
            found={key=key,tree=tree,parts=parts,path=path,tpl=tpl}
        end
    end
    return found
end

-- In grenade mode Tarkov aims only through the launcher's own camera
-- (FindUnderbarrelWeaponSight); the host's channels are untouched. Its range
-- (launcher item SightingRange) is stored apart from them on the launcher part.
function S.launcherChannel(tpl,path)
    local row=Channels.launchers and Channels.launchers[tpl]
    if not row or row.kind~='underbarrel' or not row.frame then return end
    return {path=path,tpl=tpl,channel=0,kind='launcher',storage=row.storage,launcher=row,modes={},
        frame=row.frame,hierarchyOrder=0,sight={zoomMin=1,zoomMax=1}}
end

-- Our weapon or a deployed TW underbarrel launcher is held: PageUp/PageDown act on its channel.
function S.held(player)
    local weapon=player and player:getPrimaryHandItem()
    return TWRuntime~=nil and weapon~=nil and (TWRuntime.key(weapon)~=nil or S.deployed(weapon)~=nil)
end

-- The range pose of a launcher sight channel at a distance: one rigid move of its
-- part frame (GP-25 quadrant by SightingRange, M2A1 WeaponScopeAxis by AngleByRange),
-- applied to the line of sight as to the visible geometry (TW/GeometryState).
function S.rangeMove(channel,distance)
    local row=channel.launcher or (Channels.launchers and Channels.launchers[channel.tpl])
    if not row or (not channel.launcher and (row.kind~='scope' or tostring(channel.channel)~=row.storage)) then return end
    for i,d in ipairs(row.distances) do if d==distance then return row.moves[i],row end end
end

-- Channel state stays on the physical part, including after detach/reinstall.
-- The old TWOptic.mode seeds scope zero and remains the NV/thermal reader's view.
function S.storage(weapon,channel,parts)
    local owner=parts[channel.path] or weapon
    local md=owner:getModData()
    md.TWOptic=type(md.TWOptic)=='table' and md.TWOptic or {}
    local optic=md.TWOptic
    optic.channels=type(optic.channels)=='table' and optic.channels or {}
    local key=channel.storage or tostring(channel.channel)
    if type(optic.channels[key])~='table' then
        optic.channels[key]={mode=channel.channel==0 and optic.mode or 1}
    end
    local state=optic.channels[key]
    if not number(state.mode) or state.mode~=math.floor(state.mode) or state.mode<1 or state.mode>math.max(1,#channel.modes) then state.mode=1 end
    -- The brightness dial stays on the physical part with the mode and the zero; an old save or another
    -- mod's step outside the ladder falls back to the default step, never to a guessed multiplier.
    local brightness=tonumber(state.brightness)
    if not brightness or brightness~=math.floor(brightness) or brightness<1 or brightness>#S.BRIGHTNESS then
        state.brightness=S.BRIGHTNESS_DEFAULT
    end
    if not S.zeroIndex(channel,weapon,state.zeroDistance) then
        -- An underbarrel starts at its default mark (50 m past its close marks), others at their first.
        local default=channel.launcher and channel.launcher.default
        state.zeroDistance=S.zeroIndex(channel,weapon,default) and default or S.distances(channel,weapon)[1]
    end
    local lo,hi=S.range(channel)
    if lo then
        state.fov=clamp(number(state.fov) and state.fov or hi,lo,hi)
        state.fovTarget=clamp(number(state.fovTarget) and state.fovTarget or state.fov,lo,hi)
        state.raw=clamp(number(state.raw) and state.raw or state.fov,lo,hi)
        state.velocity=number(state.velocity) and state.velocity or 0
        state.input=number(state.input) and state.input or 0
    end
    return state,owner,optic
end

-- The step the server accepted, written onto this machine's copy of that sight part (multiplayer,
-- 26.10.2026): only the brightness changes here - the mode, zero and zoom of another player's sight
-- are not this client's to rewrite. The channel key is the one S.storage uses, so the same stored
-- table drives the view (S.bind), the renderer and a later save of the item.
function S.storeBrightness(weapon,channel,parts,index)
    local value=tonumber(index)
    if not weapon or not channel or not value or value~=math.floor(value) or value<1 or value>#S.BRIGHTNESS then return false end
    local owner=parts and parts[channel.path] or weapon
    local md=owner:getModData()
    md.TWOptic=type(md.TWOptic)=='table' and md.TWOptic or {}
    local optic=md.TWOptic
    optic.channels=type(optic.channels)=='table' and optic.channels or {}
    local key=channel.storage or tostring(channel.channel)
    local state=optic.channels[key]
    if type(state)~='table' then
        state={mode=channel.channel==0 and optic.mode or 1}
        optic.channels[key]=state
    end
    state.brightness=value
    return true
end

function S.magnification(channel,state)
    local lo,hi=S.range(channel)
    local nominals=channel.scope and channel.scope.nominals or {}
    if lo then
        local smallest
        for _,z in ipairs(nominals) do if z>0 and (not smallest or z<smallest) then smallest=z end end
        return (smallest or 1)*hi/state.fov
    end
    local mode=channel.modes[state.mode]
    return mode and mode.nominalZoom or nominals[1] or 1
end

local function dot(a,b) return a[1]*b[1]+a[2]*b[2]+a[3]*b[3] end
local function sub(a,b) return {a[1]-b[1],a[2]-b[2],a[3]-b[3]} end
local function scale(a,s) return {a[1]*s,a[2]*s,a[3]*s} end
local function cross(a,b) return {a[2]*b[3]-a[3]*b[2],a[3]*b[1]-a[1]*b[3],a[1]*b[2]-a[2]*b[1]} end
local function unit(a)
    local n=math.sqrt(dot(a,a))
    return n>1e-9 and scale(a,1/n) or nil
end
local function axis(m,j) return {m[1][j],m[2][j],m[3][j]} end

local function localFrame(place,eye,f,up)
    up=unit(sub(up,scale(f,dot(up,f))))
    if not up then return end
    local left=cross(f,up)
    local a,b,c=axis(place,1),axis(place,2),axis(place,3)
    local bc,ca,ab=cross(b,c),cross(c,a),cross(a,b)
    local determinant=dot(a,bc)
    if math.abs(determinant)<1e-9 then return end
    local function vector(v) return {dot(bc,v)/determinant,dot(ca,v)/determinant,dot(ab,v)/determinant} end
    local x,y,z=vector(left),vector(f),vector(up)
    local p=vector(sub(eye,axis(place,4)))
    return {{x[1],y[1],z[1],p[1]},{x[2],y[2],z[2],p[2]},{x[3],y[3],z[3],p[3]}}
end

-- Optical backup irons belong to that optic's subtree, never to the host's
-- main sights. Underbarrel launchers have their own pose/camera implementation.
local function ironContext(tree,path)
    while path~='' do
        if Data.items[tree[path]] then return path end
        path=C.parent(path)
    end
    return ''
end

-- Original cameras include a viewing offset, not a promise that the eye is on
-- the physical sight line. Build that line after every installed mount transform.
-- Prefer visible mesh features and the installed rear's original camera relief.
-- Return the result in the selected owner's local space, without moving parts.
function S.ironFrame(key,tree,channel)
    if not key or not channel.frame or (channel.kind~='iron' and channel.kind~='aim') then return end
    local path=channel.path or ''
    local place=path=='' and C.identity or C.placement(key,tree,path)
    if not place then return nil,{status='unavailable',reason='missing-camera-placement'} end
    local camera=C.mul(place,channel.frame)
    local origin=axis(camera,4)
    local context=ironContext(tree,path)
    local paths={''}
    for p in pairs(tree) do paths[#paths+1]=p end
    table.sort(paths)
    local rear,front,rearScore,frontScore,foldedFront
    local candidates={}
    for _,p in ipairs(paths) do
        if not ('/'..p..'/'):find('/mod_launcher',1,true) and ironContext(tree,p)==context then
            local tpl=p=='' and C.root(key) or tree[p]
            local row=Channels.alignment and Channels.alignment[tpl]
            local m=row and (p=='' and C.identity or C.placement(key,tree,p))
            if m then
                for _,role in ipairs({'rear','front'}) do
                    local datum=row[role]
                    if datum then
                        local visual=datum.visual
                        candidates[#candidates+1]={role=role,path=p,tpl=tpl,node=datum.node,
                            position=C.transformPoint(m,visual and visual.position or datum.position),
                            sourcePosition=C.transformPoint(m,datum.position),visual=visual,
                            renderException=datum.renderException and require('TW/GeometryState').folded(key,tree) and datum.renderException or nil}
                    end
                end
            end
        end
    end
    -- Source camera conventions differ; the axis nearest the bore is forward.
    local forward,best=nil,-1
    for j=1,3 do
        local v=unit(axis(camera,j))
        if v and math.abs(v[2])>best then best=math.abs(v[2]);forward=v[2]<0 and scale(v,-1) or v end
    end
    if not forward or best<0.9 then return nil,{status='unavailable',reason='invalid-camera-axis'} end
    for _,datum in ipairs(candidates) do
        if datum.role=='rear' then
            local delta=sub(datum.position,origin)
            local perpendicular=sub(delta,scale(forward,dot(delta,forward)))
            local score=dot(perpendicular,perpendicular)
            -- A physical rear's own channel has explicit ownership. A root or
            -- receiver camera chooses the rear nearest its source viewing axis.
            if datum.path==path then score=-1 end
            if not rearScore or score<rearScore then rear,rearScore=datum,score end
        end
    end
    if not rear then return nil,{status='unavailable',reason='missing-rear-datum'} end
    for _,datum in ipairs(candidates) do
        if datum.role=='front' then
            if datum.renderException then foldedFront=datum end
            local delta=sub(datum.position,rear.position)
            local along=dot(delta,forward)
            if along>1e-6 and not datum.renderException then
                local perpendicular=sub(delta,scale(forward,along))
                local score=dot(perpendicular,perpendicular)/(along*along)
                if not frontScore or score<frontScore-1e-12 or
                    (math.abs(score-frontScore)<1e-12 and along>dot(sub(front.position,rear.position),forward)) then
                    front,frontScore=datum,score
                end
            end
        end
    end
    if not front then return nil,{status='unavailable',reason=foldedFront and 'folded-front-geometry' or 'missing-front-datum',
        rear=rear,front=foldedFront} end
    local f=unit(sub(front.position,rear.position))
    local eyeSource={tpl=channel.tpl,path=path,position=origin}
    local station=dot(sub(origin,rear.position),f)
    local owner=Channels.item(rear.tpl)
    local m=rear.path=='' and C.identity or C.placement(key,tree,rear.path)
    for _,point in ipairs(owner and owner.points or {}) do
        if point.activeInPrefab and m then
            local original=C.transformPoint(m,axis(point.frame,4))
            local s=dot(sub(original,rear.position),f)
            if s<-1e-6 then
                station=s;eyeSource={tpl=rear.tpl,path=rear.path,node=point.node,position=original};break
            end
        end
    end
    if station>=-1e-6 then return nil,{status='unavailable',reason='camera-not-behind-rear',rear=rear,front=front} end
    local eye={rear.position[1]+station*f[1],rear.position[2]+station*f[2],rear.position[3]+station*f[3]}
    local up,top=nil,-1
    for j=1,3 do
        local v=unit(axis(camera,j))
        if v and math.abs(dot(v,f))<0.5 and math.abs(v[3])>top then top=math.abs(v[3]);up=v[3]<0 and scale(v,-1) or v end
    end
    if not up then return nil,{status='unavailable',reason='invalid-camera-up'} end
    local frame=localFrame(place,eye,f,up)
    if not frame then return nil,{status='unavailable',reason='singular-camera-placement'} end
    return frame,{status='aligned',rear=rear,front=front,eye=eye,direction=f,sourceEye=origin,eyeSource=eyeSource,
        visualStatus=rear.visual and rear.visual.position and front.visual and front.visual.position and 'verified' or 'datum-only'}
end

function S.ironCamera(weapon,channel)
    if channel.kind~='iron' and channel.kind~='aim' then return channel.frame end
    local key=TWRuntime and TWRuntime.key(weapon)
    if not key then return channel.frame end
    -- The shared snapshot's tree (R.view) is the same table until a part changes: its signature is built only then.
    local tree=(TWRuntime.viewTree or TWRuntime.tree)(weapon)
    local cache=S.ironCache[weapon]
    if not cache or cache.tree~=tree then
        local parts={key}
        for path,tpl in pairs(tree) do parts[#parts+1]=path..'='..tpl end
        table.sort(parts)
        local signature=table.concat(parts,';')
        if not cache or cache.signature~=signature then cache={signature=signature,channels={}};S.ironCache[weapon]=cache end
        cache.tree=tree
    end
    local signature=cache.signature
    local id=(channel.path or '')..':'..channel.channel
    local entry=cache.channels[id]
    if not entry then
        local frame,alignment=S.ironFrame(key,tree,channel)
        if alignment then alignment.signature=signature end
        entry={frame=frame or channel.frame,alignment=alignment};cache.channels[id]=entry
    end
    return entry.frame,entry.alignment
end

-- quiet: a view of a channel that is not the one in use (FB8 item 5: the glass of the other collimators) - the
-- weapon's view revision (S.seen) stays the selected channel's.
function S.bind(weapon,channel,parts,quiet)
    if not channel then return end
    local state,owner,optic=S.storage(weapon,channel,parts)
    local mode=channel.modes[state.mode]
    local profile=mode and mode.profile
    local lo,hi=S.range(channel)
    local fov=lo and state.fov or profile and profile.camera and profile.camera.fovDegrees
    local image=profile and profile.imageProfile
    if image then optic.mode=image.mode end
    local focal=channel.profile and channel.profile.focalPlane.kind or 'na'
    if channel.kind=='aim' or channel.kind=='collimator' then focal='na' end
    local sensitivities=channel.profile and channel.profile.zoom.sensitivities
    local sensitivity
    if lo and sensitivities then
        local a,b=sensitivities.AdjustableOpticSensitivity,sensitivities.AdjustableOpticSensitivityMax
        if number(a) and number(b) then sensitivity=a+(b-a)*(hi-fov)/(hi-lo) end
    elseif sensitivities then
        local group=sensitivities.AimSensitivity and sensitivities.AimSensitivity[channel.channel+1]
        sensitivity=group and group[(mode and mode.index or 0)+1]
    end
    local reticleScale
    if focal=='sfp' or focal=='na' then reticleScale=1
    elseif focal=='ffp' and lo and fov then reticleScale=math.tan(math.rad(hi/2))/math.tan(math.rad(fov/2)) end
    local frame,ironAlignment=S.ironCamera(weapon,channel)
    local move,launcher=S.rangeMove(channel,state.zeroDistance)
    if move and frame then frame=C.mul(move,frame) end
    local view={weapon=weapon,path=channel.path,tpl=channel.tpl,channel=channel.channel,scopeIndex=channel.channel,
        launcher=launcher,rangeMove=move,
        part=owner~=weapon and owner or nil,mode=state.mode,modeIndex=mode and mode.index or 0,
        profile=channel.profile,scope=channel.scope,modeProfile=profile,channelProfile=channel,
        fov=fov,fovTarget=lo and state.fovTarget or fov,focalPlane=focal,reticleScale=reticleScale,
        illumination=profile and profile.illumination,materials=Data.materials,frame=frame,ironAlignment=ironAlignment,
        point=channel.point,imageProfile=image,kind=profile and profile.kind or channel.kind,
        magnification=S.magnification(channel,state),sensitivity=sensitivity,state=state,
        brightness=S.brightnessValue(state),brightnessStep=S.brightnessIndex(state),
        zeroDistance=state.zeroDistance,zeroDistances=S.distances(channel,weapon)}
    view.calibrationDistances=view.zeroDistances
    local signature=channel.path..':'..channel.channel..':'..state.mode..':'..tostring(fov)..':'..tostring(view.fovTarget)..':'..tostring(state.zeroDistance)
        ..':'..tostring(view.brightnessStep)
        ..':'..(ironAlignment and ironAlignment.signature or '')
    local previous=S.seen[weapon]
    if quiet then
        view.revision=previous and previous.revision or 0
        return view
    end
    if not previous or previous.signature~=signature then
        S.revision=S.revision+1
        previous={signature=signature,revision=S.revision};S.seen[weapon]=previous
    end
    view.revision=previous.revision
    return view
end

function S.current(player)
    local weapon=player and player:getPrimaryHandItem()
    local R=TWRuntime
    if not R or not weapon then return end
    local deployed=not R.key(weapon) and S.deployed(weapon)
    if not R.key(weapon) and not deployed then return end
    local tree,parts
    -- The shared read-only snapshot (R.view): S.current runs every frame (zeroing, ADS camera, poses).
    if deployed then tree,parts=deployed.tree,deployed.parts else local _;tree,_,parts=(R.view or R.read)(weapon) end
    -- An older native acknowledgement must not replace a newer local zero.
    -- The server stamps accepted distances with the original command sequence.
    local zeros=isClient() and S.pendingZero[player] or {}
    for i=#zeros,1,-1 do
        local entry=zeros[i]
        local owner=parts[entry.channel.path] or (entry.channel.path=='' and weapon)
        if getTimestampMs()-entry.time>5000 then table.remove(zeros,i)
        elseif entry.weapon==weapon then
            local tpl=entry.channel.path=='' and C.root(R.key(weapon)) or tree[entry.channel.path]
            if not owner or owner:getID()~=entry.partId or tpl~=entry.channel.tpl then table.remove(zeros,i)
            else
                local state=S.storage(weapon,entry.channel,parts)
                if state.zeroSession==entry.session and number(state.zeroSequence) and state.zeroSequence>=entry.sequence then
                    table.remove(zeros,i)
                else state.zeroDistance=entry.distance end
            end
        end
    end
    -- A native Select acknowledgement includes older server part ModData.
    -- Preserve this client's unconfirmed zoom until its debounced Zoom command.
    for _,entry in ipairs(isClient() and S.pending[player] or {}) do
        local part=parts[entry.channel.path]
        if entry.weapon==weapon and part and part:getID()==entry.partId and tree[entry.channel.path]==entry.channel.tpl then
            local state=S.storage(weapon,entry.channel,parts)
            if state~=entry.state then
                for _,field in ipairs({'fov','fovTarget','raw','velocity','input'}) do state[field]=entry.state[field] end
                entry.state=state
            end
        end
    end
    if deployed then
        local channel=S.launcherChannel(deployed.tpl,deployed.path)
        return channel and S.bind(weapon,channel,parts)
    end
    -- R.updateSight binds the channel it selects; S.bind depends only on the channel, the parts' state and the tree,
    -- none of which the rest of updateSight changes, so its view is the view a second bind would return.
    local channel,view=R.updateSight(weapon,tree,parts)
    return channel and (view or S.bind(weapon,channel,parts))
end

function S.send(player,weapon,channel,state,command)
    if not player then return end
    if isClient() then
        local outgoing=S.outgoing[player]
        if not outgoing then outgoing={session=tostring(getTimestampMs()),sequence=0};S.outgoing[player]=outgoing end
        outgoing.sequence=outgoing.sequence+1
        local parts=TWRuntime.parts(weapon)
        local part=parts[channel.path] or weapon
        local args={weaponId=weapon:getID(),path=channel.path,channel=channel.channel,tpl=channel.tpl,
            partId=part:getID(),mode=state.mode,zero=state.zeroDistance,brightness=state.brightness,
            session=outgoing.session,sequence=outgoing.sequence}
        if command=='Zoom' then args.fov=state.fov end
        if args.zero~=nil then
            local queue=S.pendingZero[player] or {};S.pendingZero[player]=queue
            for i=#queue,1,-1 do
                if queue[i].weapon==weapon and queue[i].channel.path==channel.path and queue[i].channel.channel==channel.channel then table.remove(queue,i) end
            end
            queue[#queue+1]={weapon=weapon,channel=channel,partId=part:getID(),distance=args.zero,
                session=args.session,sequence=args.sequence,time=getTimestampMs()}
        end
        sendClientCommand(player,'TWSight',command,args)
    elseif syncHandWeaponFields then syncHandWeaponFields(player,weapon) end
end

function S.sync(player,weapon)
    if not player then return end
    local deployed=not TWRuntime.key(weapon) and S.deployed(weapon)
    if deployed then
        local channel=S.launcherChannel(deployed.tpl,deployed.path)
        if channel then S.send(player,weapon,channel,S.storage(weapon,channel,deployed.parts),'Select') end
        -- The deployed item renders as its host: show the launcher sight's new range pose now.
        local L=rawget(_G,'TWLaunchers')
        if not isServer() and L and L.refreshAppearance then L.refreshAppearance(weapon,player,false) end
        return
    end
    local tree,_,parts=TWRuntime.read(weapon)
    local channel=TWRuntime.updateSight(weapon,tree,parts)
    if channel then
        S.send(player,weapon,channel,S.storage(weapon,channel,parts),'Select')
        S.applyZero(player,S.bind(weapon,channel,parts))
    end
end

-- Public optional Lua API only. Tarkov calibration distances are already metres.
-- Apply on channel/equipment/load changes too; the VTB zero is viewer-global.
function S.applyZero(player,view)
    if not player or isServer() or not player.isLocalPlayer or not player:isLocalPlayer() then return false end
    -- A grenade launcher range is not a bullet zero.
    if not view or not view.zeroDistance or view.channelProfile.kind=='launcher' or not TWViewpoint or not TWViewpoint.vtb or not VTB or not VTB.setZero then
        S.vtbSeen[player]=nil;return false
    end
    local ok,active=pcall(TWViewpoint.vtb)
    if not ok or not active then S.vtbSeen[player]=nil;return false end
    local previous=S.vtbSeen[player]
    if previous and previous.weapon==view.weapon and previous.path==view.path and previous.channel==view.channel
        and previous.distance==view.zeroDistance and previous.api==VTB.setZero then return false end
    S.vtbSeen[player]={weapon=view.weapon,path=view.path,channel=view.channel,distance=view.zeroDistance,api=VTB.setZero}
    return pcall(VTB.setZero,view.zeroDistance)
end

function S.queueZoom(player,view)
    if not player or not isClient() then return end
    local queue=S.pending[player] or {};S.pending[player]=queue
    for _,entry in ipairs(queue) do
        if entry.weapon==view.weapon and entry.channel.path==view.path and entry.channel.channel==view.channel then
            entry.time=getTimestampMs();entry.state=view.state;entry.partId=view.part:getID();return
        end
    end
    queue[#queue+1]={weapon=view.weapon,channel=view.channelProfile,state=view.state,partId=view.part:getID(),time=getTimestampMs()}
end

-- At most one FOV message after a gesture, after 500 ms without wheel input
-- and after its active interpolation finishes. Inactive scopes keep current FOV.
function S.flush(player)
    local queue=player and S.pending[player]
    if not queue or player:isDead() then return end
    local view=S.current(player)
    for i=#queue,1,-1 do
        local entry=queue[i]
        if getTimestampMs()-entry.time>=500 and player:getPrimaryHandItem()==entry.weapon then
            local active=view and view.path==entry.channel.path and view.channel==entry.channel.channel
            local state=entry.state
            if not active or math.abs(state.fov-state.fovTarget)<EPS then
                local parts=TWRuntime.parts(entry.weapon)
                if parts[entry.channel.path] and parts[entry.channel.path]:getID()==entry.partId
                    and TWRuntime.tree(entry.weapon)[entry.channel.path]==entry.channel.tpl then
                    if not active then state.fovTarget=state.fov;state.raw=state.fov;state.velocity=0;state.input=0 end
                    S.send(player,entry.weapon,entry.channel,state,'Zoom')
                end
                table.remove(queue,i)
            end
        end
    end
end

function S.name(channel)
    local short=getText('IGUI_TW_Short_'..channel.tpl)
    if channel.kind=='iron' then return getText('IGUI_TW_SightIron',short) end
    if channel.profile and #channel.profile.scopes>1 then return getText('IGUI_TW_SightChannel',short,channel.channel+1) end
    return short
end

function S.modeText(channel,index)
    local mode=channel.modes[index]
    local profile=mode and mode.profile
    local image=profile and profile.imageProfile
    if image then
        local Images=require('TW/Data/Optics')
        local item=Images.items[image.tpl]
        local m=item and item.modes[image.mode]
        if m then return getText(m.kind=='nv' and 'IGUI_TW_OpticMode_nv' or ('IGUI_TW_OpticMode_thermal_'..m.palette)) end
    end
    if mode and mode.nominalZoom then return getText('IGUI_TW_SightModeZoom',index,mode.nominalZoom) end
    return getText('IGUI_TW_SightMode',index)
end

-- The client notice (TW_SightNotice) shows the text of a topic at once, replacing the
-- previous one of that topic (a screen line in Viewpoint first person, the halo note
-- elsewhere); without it (server, older client) the halo note.
local function halo(player,text,topic)
    local notice=rawget(_G,'TWSightNotice')
    if notice and notice(player,text,topic) then return end
    if player.setHaloNote then player:setHaloNote(text,220,220,220,250) end
end

function S.note(player,channel,mode)
    if not player then return end
    local text=S.name(channel)
    if mode and #channel.modes>1 then text=text..' - '..S.modeText(channel,mode) end
    halo(player,text,'sight')
    if player.playSound then player:playSound('TW_DeviceMode') end
end

function S.noteZero(player,view)
    if player and view.zeroDistance then
        halo(player,getText('IGUI_TW_SightZero',S.name(view.channelProfile),view.zeroDistance),'zero')
    end
end

-- The brightness step of the sight, as the short notice of its own topic: the line replaces itself while
-- the player keeps turning the wheel, so the level is readable without covering the sight picture.
function S.noteBrightness(player,index)
    if not player then return end
    local step=clamp(tonumber(index) or S.BRIGHTNESS_DEFAULT,1,#S.BRIGHTNESS)
    halo(player,getText('IGUI_TW_ReticleBrightness',step,#S.BRIGHTNESS),'brightness')
end

-- One wheel notch onto the ladder of the active sight (positive: brighter). Returns false when the channel
-- has no reticle overlay; the caller then leaves the wheel to the zoom/hotbar. The stored step is clamped at
-- both ends and kept on the sight's own part, so it survives save/load and follows a detached sight.
function S.adjustBrightness(player,direction)
    local view=S.current(player)
    if not view or not S.brightnessAdjustable(view) or not number(direction) or direction==0 then return false end
    local state=view.state
    local previous=S.brightnessIndex(state)
    local index=clamp(previous+(direction>0 and 1 or -1),1,#S.BRIGHTNESS)
    state.brightness=index
    S.noteBrightness(player,index)
    if index~=previous then S.sync(player,view.weapon) end
    return true
end

function S.setZero(player,distance)
    local view=S.current(player)
    if not view or not S.zeroIndex(view.channelProfile,view.weapon,distance) then return false end
    S.noteZero(player,{channelProfile=view.channelProfile,zeroDistance=distance})
    if view.zeroDistance==distance then return false end
    view.state.zeroDistance=distance
    S.sync(player,view.weapon)
    if player.playSound then player:playSound('TW_DeviceMode') end
    return true
end

function S.adjustZero(player,direction)
    if not number(direction) or direction==0 then return false end
    local view=S.current(player)
    local index=view and S.zeroIndex(view.channelProfile,view.weapon,view.zeroDistance)
    if not index then return false end
    local target=clamp(index+(direction>0 and 1 or -1),1,#view.zeroDistances)
    return S.setZero(player,view.zeroDistances[target])
end

function S.select(player,weapon,path,index,mode)
    if not TWRuntime or not TWRuntime.key(weapon) then return false end
    if mode then
        local valid=false
        for _,channel in ipairs(C.sights(TWRuntime.tree(weapon),TWRuntime.key(weapon))) do
            if channel.path==path and (index==nil or channel.channel==index) then valid=channel.modes[mode]~=nil;break end
        end
        if not valid then return false end
    end
    local selected=TWRuntime.selectSight(weapon,path,index)
    if not selected then return false end
    local _,_,parts=TWRuntime.read(weapon)
    local state=S.storage(weapon,selected,parts)
    if mode then
        if not selected.modes[mode] then return false end
        state.mode=mode
    end
    TWRuntime.updateSight(weapon,TWRuntime.tree(weapon),parts)
    S.sync(player,weapon)
    S.note(player,selected,state.mode)
    return true
end

function S.cycle(player)
    local view=S.current(player)
    if not view then return false end
    local selected=TWRuntime.cycleSight(view.weapon)
    if not selected then return false end
    S.sync(player,view.weapon);S.note(player,selected)
    return true
end

function S.cycleMode(player)
    local view=S.current(player)
    if not view or #view.channelProfile.modes<2 then return false end
    return S.select(player,view.weapon,view.path,view.channel,view.mode%#view.channelProfile.modes+1)
end

-- ScopeZoomHandler: accumulated wheel velocity moves an uncurved FOV target;
-- the prefab curve maps its normalized value, then MoveTowards approaches it.
-- Reverse an unfinished interior move by resetting at current FOV first.
function S.wheel(player,scroll,sensitivity)
    local view=S.current(player)
    if not view or not number(scroll) or scroll==0 then return false end
    local channel,state=view.channelProfile,view.state
    local lo,hi=S.range(channel)
    if lo then
        local pending=math.abs(state.fov-state.fovTarget)>=EPS
        local reverse=scroll*state.input<0
        if reverse and pending and state.fov>lo+EPS and state.fov<hi-EPS then
            state.velocity=0;state.raw=state.fov
        else
            state.velocity=state.velocity+scroll*(sensitivity or 1)*channel.profile.zoom.sensitivities.ZoomSensitivity
            state.raw=state.raw-state.velocity
        end
        state.raw=clamp(state.raw,lo,hi)
        local mode=channel.modes[state.mode].profile
        state.fovTarget=clamp(lo+(hi-lo)*S.curve(mode.camera.fieldOfViewCurve,(state.raw-lo)/(hi-lo)),lo,hi)
        state.input=scroll
    else
        local steps=S.steps(channel)
        if #steps<2 then return false end
        local current=1
        for i,step in ipairs(steps) do if step.zoom<=view.magnification then current=i end end
        state.mode=steps[clamp(current+(scroll>0 and 1 or -1),1,#steps)].mode
    end
    local tree,_,parts=TWRuntime.read(view.weapon)
    TWRuntime.updateSight(view.weapon,tree,parts)
    if lo then S.queueZoom(player,view) else S.sync(player,view.weapon) end
    return true
end

function S.advance(player,seconds)
    local view=S.current(player)
    if not view then return false end
    local state,channel=view.state,view.channelProfile
    local lo,hi,speed=S.range(channel)
    if not lo or not number(seconds) or seconds<=0 then return false end
    local difference=state.fovTarget-state.fov
    if math.abs(difference)<EPS then state.velocity=0;return false end
    local delta=math.min(math.abs(difference),(speed or 0)*math.abs(state.velocity)*seconds)
    if delta==0 then return false end
    state.fov=clamp(state.fov+(difference>0 and delta or -delta),lo,hi)
    if state.fov==lo or state.fov==hi then state.velocity=0;state.input=0 end
    local tree,_,parts=TWRuntime.read(view.weapon)
    TWRuntime.updateSight(view.weapon,tree,parts)
    return true
end

-- A client may only select an installed channel of its held TW weapon. The
-- existing native SyncHandWeaponFields packet carries weapon/part modData.
function S.validateMessage(player,weapon,args,previous,command)
    if type(args)~='table' or not player or player:isDead() then return false,'args' end
    local R=TWRuntime
    local deployed=R and weapon and not R.key(weapon) and S.deployed(weapon)
    if not R or not (R.key(weapon) or deployed) or player:getPrimaryHandItem()~=weapon or weapon:getID()~=args.weaponId then return false,'weapon' end
    if type(args.session)~='string' or #args.session==0 or #args.session>40 or not number(args.sequence)
        or args.sequence<1 or args.sequence>2147483647 or args.sequence~=math.floor(args.sequence) then return false,'sequence' end
    if previous and previous.player==player then
        if previous.session==args.session and args.sequence<=previous.sequence then return false,'replay' end
        if getTimestampMs()-previous.window<1000 and previous.count>=20 then return false,'rate' end
    end
    if type(args.path)~='string' or #args.path>1024 or not number(args.channel) or args.channel~=math.floor(args.channel) then return false,'channel' end
    local tree,parts,selected
    if deployed then
        -- A deployed launcher has exactly its own channel.
        tree,parts=deployed.tree,deployed.parts
        local channel=S.launcherChannel(deployed.tpl,deployed.path)
        if channel and channel.path==args.path and channel.channel==args.channel then selected=channel end
    else
        local _;tree,_,parts=R.read(weapon)
        for _,channel in ipairs(C.sights(tree,R.key(weapon))) do
            if channel.path==args.path and channel.channel==args.channel then selected=channel;break end
        end
    end
    if not selected or selected.tpl~=args.tpl or (parts[args.path] or weapon):getID()~=args.partId then return false,'channel' end
    if not number(args.mode) or args.mode~=math.floor(args.mode) or args.mode<1 or args.mode>math.max(1,#selected.modes) then return false,'mode' end
    if args.zero~=nil and not S.zeroIndex(selected,weapon,args.zero) then return false,'zero' end
    if args.brightness~=nil and (not number(args.brightness) or args.brightness~=math.floor(args.brightness)
        or args.brightness<1 or args.brightness>#S.BRIGHTNESS) then return false,'brightness' end
    if command=='Zoom' then
        local lo,hi=S.range(selected)
        if not lo or not number(args.fov) or args.fov<lo or args.fov>hi then return false,'fov' end
    elseif command~='Select' or args.fov~=nil then return false,'command' end
    return true,nil,selected,tree,parts
end

function S.receive(module,command,player,args)
    if module~='TWSight' or (command~='Select' and command~='Zoom') or not isServer() then return end
    local weapon=player and player:getPrimaryHandItem()
    local id=player and player:getOnlineID()
    local previous=id and S.latest[id]
    local ok,reason,channel,tree,parts=S.validateMessage(player,weapon,args,previous,command)
    if not ok then return false,reason end
    local launcher=channel.kind=='launcher'
    -- The deployed item's own channel: the host's selected sight stays as it was.
    if command=='Select' and not launcher then TWRuntime.selectSight(weapon,channel.path,channel.channel) end
    local state=S.storage(weapon,channel,parts)
    state.mode=args.mode
    if args.zero~=nil then
        state.zeroDistance=args.zero;state.zeroSession=args.session;state.zeroSequence=args.sequence
    end
    if args.brightness~=nil then state.brightness=args.brightness end
    if command=='Zoom' then state.fov=args.fov;state.fovTarget=args.fov;state.raw=args.fov;state.velocity=0;state.input=0 end
    if not launcher then TWRuntime.updateSight(weapon,tree,parts) end
    local now=getTimestampMs()
    local sameWindow=previous and previous.player==player and now-previous.window<1000
    S.latest[id]={player=player,session=args.session,sequence=args.sequence,
        window=sameWindow and previous.window or now,count=sameWindow and previous.count+1 or 1}
    if syncHandWeaponFields then syncHandWeaponFields(player,weapon) end
    if args.brightness~=nil then S.publish(player,weapon) end
    return true
end

-- Multiplayer (W9, 26.10.2026): the brightness step is stored on the sight part's ModData, and PZ's
-- syncHandWeaponFields carries a hand weapon's own ModData to its owner only - every other client's
-- copy of that part stayed at the default step while the server held the new one. The server now
-- publishes the accepted step as TWSight Sync (same module as Select/Zoom, server -> clients), a
-- client that joins later or sees the weapon/part change hands asks for the current step
-- (RequestState), and an answer for a copy this client cannot see yet is kept until it can.
-- Values come from the server's own item; a client's claim is only ever the Select/Zoom payload,
-- which S.receive validates before it stores anything.

-- One row for the server's current step on that player's active sight (nil: no sight).
function S.remoteRow(player,weapon)
    local R=TWRuntime
    local key=weapon and R and R.key(weapon)
    if not key then return end
    local tree,_,parts=R.read(weapon)
    local md=weapon:getModData()
    local tw=type(md.TW)=='table' and md.TW or {}
    local channel
    for _,row in ipairs(R.sights(weapon,tree,key)) do
        if row.path==tw.activeSight and row.channel==(tw.activeSightChannel or 0) then channel=row;break end
    end
    if not channel then local _;channel=R.updateSight(weapon,tree,parts) end
    if not channel then return end
    local state=S.storage(weapon,channel,parts)
    local owner=parts[channel.path] or weapon
    S.syncRevision=S.syncRevision+1
    return {playerId=player:getOnlineID(),weaponId=weapon:getID(),path=channel.path,channel=channel.channel,
        partId=owner:getID(),brightness=S.brightnessIndex(state),revision=S.syncRevision}
end

-- Server: publish the stored step when this sight's step changed (a Select or a Zoom carries it).
function S.publish(player,weapon)
    if not isServer() or not player then return end
    local row=S.remoteRow(player,weapon)
    if not row then return end
    local id=player:getOnlineID()
    local previous=S.published[id]
    if previous and previous.weaponId==row.weaponId and previous.path==row.path and previous.channel==row.channel
        and previous.partId==row.partId and previous.brightness==row.brightness then return end
    S.published[id]=row
    sendServerCommand('TWSight','Sync',row)
end

-- Server: reply to one client with the step the server holds for that player's active sight.
function S.requestState(module,command,player,args)
    if module~='TWSight' or command~='RequestState' or not isServer() or not player then return false end
    if type(args)~='table' or type(args.playerId)~='number' then return false end
    local target
    local players=getOnlinePlayers()
    for i=0,players:size()-1 do
        local other=players:get(i)
        if other:getOnlineID()==args.playerId then target=other;break end
    end
    if not target then return false end
    local weapon=target:getPrimaryHandItem()
    if not weapon or (args.weaponId~=nil and weapon:getID()~=args.weaponId) then return false end
    local row=S.remoteRow(target,weapon)
    if not row then return false end
    S.published[args.playerId]=row
    sendServerCommand(player,'TWSight','Sync',row)
    return true
end

-- Client: write one row onto this machine's copy of that player's sight part. False while the
-- weapon or the part is not visible here yet (the row then waits in S.pendingSync).
function S.applySync(row)
    local online=rawget(_G,'getOnlinePlayers')
    if not online then return false end
    local player
    local players=online()
    for i=0,players:size()-1 do
        local other=players:get(i)
        if other:getOnlineID()==row.playerId then player=other;break end
    end
    if not player then return false end
    if player:isLocalPlayer() then return true end
    local weapon=player:getPrimaryHandItem()
    if not weapon or weapon:getID()~=row.weaponId then return false end
    local R=TWRuntime
    local key=R and R.key(weapon)
    if not key then return false end
    local tree,_,parts=R.read(weapon)
    local channel
    for _,candidate in ipairs(R.sights(weapon,tree,key)) do
        if candidate.path==row.path and candidate.channel==row.channel then channel=candidate;break end
    end
    if not channel then return false end
    if row.partId and (parts[channel.path] or weapon):getID()~=row.partId then return false end
    return S.storeBrightness(weapon,channel,parts,row.brightness)
end

function S.receiveSync(module,command,args)
    if module~='TWSight' or command~='Sync' or not isClient() or type(args)~='table' then return false end
    if type(args.playerId)~='number' or type(args.revision)~='number' or type(args.weaponId)~='number'
        or type(args.path)~='string' or #args.path>1024 or not number(args.channel) or not number(args.brightness) then return false end
    if args.brightness~=math.floor(args.brightness) or args.brightness<1 or args.brightness>#S.BRIGHTNESS then return false end
    local id=args.playerId
    local known=S.remote[id]
    if known and known.revision and known.revision>=args.revision then return false end
    S.remote[id]={revision=args.revision,weaponId=args.weaponId}
    if not S.applySync(args) then S.pendingSync[id]={row=args,expires=getTimestampMs()+15000} end
    return true
end

-- One client poll per second: retry a row whose part appeared later, and ask for the step of a
-- remote sight this client has not heard about (a late join, a weapon or a sight that changed
-- hands). One request per player and weapon per 5 s - the answer is small and the server holds the
-- only copy. It rides OnPlayerUpdate, not OnTick: the offline harnesses of other areas count OnTick
-- handlers and would never see an idle frame (this module is loaded by them too).
local requested={}
if Events.OnPlayerUpdate and Events.OnPlayerUpdate.Add then
    Events.OnPlayerUpdate.Add(function(player)
        local client=rawget(_G,'isClient')
        if not client or not client() or not player or not player:isLocalPlayer() then return end
        local online=rawget(_G,'getOnlinePlayers')
        if not online then return end
        local now=getTimestampMs()
        if now-(S.syncTick or 0)<1000 then return end
        S.syncTick=now
        for id,entry in pairs(S.pendingSync) do
            if entry.expires<now then S.pendingSync[id]=nil
            elseif S.applySync(entry.row) then S.pendingSync[id]=nil end
        end
        local players=online()
        for i=0,players:size()-1 do
            local other=players:get(i)
            if not other:isLocalPlayer() then
                local id=other:getOnlineID()
                local weapon=other:getPrimaryHandItem()
                local key=weapon and TWRuntime and TWRuntime.key(weapon)
                local asked=requested[id]
                if key then
                    local want=weapon:getID()
                    if not asked or asked.weapon~=want or now-asked.time>5000 then
                        requested[id]={weapon=want,time=now}
                        sendClientCommand(player,'TWSight','RequestState',{playerId=id,weaponId=want})
                    end
                elseif asked then requested[id]=nil end
            end
        end
    end)
end
if Events.OnGameStart and Events.OnGameStart.Add then
    Events.OnGameStart.Add(function() S.published={};S.remote={};S.pendingSync={};S.syncRevision=0;requested={} end)
end

local function event(name)
    return rawget(_G,name)~=nil
end
if event('isServer') and isServer() and Events.OnClientCommand and Events.OnClientCommand.Add then
    Events.OnClientCommand.Add(S.receive);Events.OnClientCommand.Add(S.requestState)
end
if event('isClient') and isClient() and Events.OnServerCommand and Events.OnServerCommand.Add then
    Events.OnServerCommand.Add(S.receiveSync)
end

return S
