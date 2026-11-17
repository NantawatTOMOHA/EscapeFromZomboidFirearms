-- Own optical view, including 1x. TWSight owns the channel and its source FOV.
require('TW_Tactical')
require('TW_SightInput')
local C=require('TW/Core')
local R=require('TW/Runtime')
local Sight=require('TW/SightState')
local Geometry=require('TW/GeometryState')
local Optics=require('TW/Data/ViewpointOptics')
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
options:addTickBox('ScopePip',getText('UI_TW_ScopePip'),true,getText('UI_TW_ScopePip_tooltip'))
options:addSlider('ScopePipResolution',getText('UI_TW_ScopePipResolution'),.25,1,.25,.5)
local frequency=options:addComboBox('ScopePipFrequency',getText('UI_TW_ScopePipFrequency'),getText('UI_TW_ScopePipFrequency_tooltip'))
frequency:addItem(getText('UI_TW_ScopePipEveryFrame'),true)
frequency:addItem(getText('UI_TW_ScopePipAlternate'))
frequency:addItem(getText('UI_TW_ScopePipEveryFourth'))
-- W10 item 11 (27.10.2026, the player: "add a way to turn PIP off and make a plain zoom of the whole camera, as in other
-- games, for performance"): 1 - the picture in the eyepiece, a second world render (as before, the default); 2 - the
-- camera zoom: no second render, the main camera narrows by the sight's magnification and the eyepiece with its reticle
-- is drawn over it (ScopePipZoom.java); 3 - the same with the sight's own field across the eyepiece (the coordinator's
-- variant C: a stronger zoom, every reticle mark matches the world). Magnifying optics and variable ones at their 1x;
-- collimators, irons and fixed 1x optics keep their glass. W10 nopip2 (the rest of the class): night vision and thermal
-- sights - every mode, their 1x screens too - zoom as well, their device's image drawn over the zoomed frame inside the
-- eyepiece by the same sensor stage (VpSensor), so no value 2 or 3 renders the world twice. 30.10.2026 (the player, with
-- a Tarkov and a S.T.A.L.K.E.R. frame): 2 keeps the eyepiece where the scope is, the zoomed world round its housing;
-- 3 puts the eyepiece across the screen, black round it.
local picture=options:addComboBox('ScopePicture',getText('UI_TW_ScopePicture'),getText('UI_TW_ScopePicture_tooltip'))
picture:addItem(getText('UI_TW_ScopePicturePip'),true)
picture:addItem(getText('UI_TW_ScopePictureZoom'))
picture:addItem(getText('UI_TW_ScopePictureField'))

local P={frontCamera=true} -- QA (FB8 item 1, A/B): false keeps the eyepiece camera of every sight, as before
TWScopePip=P
local function cross(a,b) return {a[2]*b[3]-a[3]*b[2],a[3]*b[1]-a[1]*b[3],a[1]*b[2]-a[2]*b[1]} end
local function dot(a,b) return a[1]*b[1]+a[2]*b[2]+a[3]*b[3] end
local function normal(a)
    local n=math.sqrt(dot(a,a))
    if n<1e-9 then return end
    return {a[1]/n,a[2]/n,a[3]/n}
end
local function offset(p,v,r) return {p[1]+v[1]*r,p[2]+v[2]*r,p[3]+v[3]*r} end

-- Geometry stays in the existing scaled model datum. No model/class scale fixes.
function P.geometry(view,key,tree)
    if not view or (view.kind~='optic' and view.kind~='thermal' and view.kind~='nv')
        or type(view.fov)~='number' or not (view.fov>0 and view.fov<179)
        or type(view.magnification)~='number' or not (view.magnification>0 and view.magnification<math.huge) then return end
    -- A 1x electronic screen owns its source camera too. Sampling the main
    -- frame here would sample the screen housing rather than the viewed world.
    local lens=Geometry.lens(view)
    local ocular=lens and lens.ocular
    if not view.frame or not ocular or not ocular.center or not ocular.normal then return end
    local radius=ocular.radius
    local circle=type(radius)=='number' and radius>0
    -- A source screen may be planar without being circular (Ultima). Its
    -- ordered mesh boundary remains the mask; no substitute circle is fitted.
    local sensorScreen=view.kind=='thermal' or view.kind=='nv' and view.magnification<=1.0001
    local boundary=(not circle or sensorScreen) and ocular.boundary
    if sensorScreen and not boundary then return end
    if not circle and (not boundary or #boundary<3 or #boundary>64) then return end
    local placement=C.placement(key,tree,view.path)
    if not placement then return end
    local aim=view.frame
    local up=normal({-aim[1][2],-aim[2][2],-aim[3][2]})
    local forward=normal({-aim[1][3],-aim[2][3],-aim[3][3]})
    if not up or not forward then return end
    -- The source aim marker faces the eye; choose the optical direction by
    -- the authored eye-to-ocular displacement rather than a weapon-name rule.
    local eye={aim[1][4],aim[2][4],aim[3][4]}
    local toLens={ocular.center[1]-eye[1],ocular.center[2]-eye[2],ocular.center[3]-eye[3]}
    if dot(forward,toLens)<0 then forward={-forward[1],-forward[2],-forward[3]} end
    local right=normal(cross(forward,up))
    if not right then return end
    local n=normal(ocular.normal)
    if not n then return end
    local d=dot(right,n)
    right=normal({right[1]-d*n[1],right[2]-d*n[2],right[3]-d*n[3]})
    if not right then return end
    local vertical=normal(cross(n,right))
    if dot(vertical,up)<0 then vertical={-vertical[1],-vertical[2],-vertical[3]} end
    local halfX,halfY=radius,radius
    if boundary then
        halfX,halfY=0,0
        for _,p in ipairs(boundary) do
            local v={p[1]-ocular.center[1],p[2]-ocular.center[2],p[3]-ocular.center[3]}
            if math.abs(dot(v,n))>1e-6 then return end -- unproven nonplanar surface
            halfX=math.max(halfX,math.abs(dot(v,right)))
            halfY=math.max(halfY,math.abs(dot(v,vertical)))
        end
        if halfX<=1e-9 or halfY<=1e-9 then return end
    end
    local root=C.renderMatrix(key,placement)
    local function point(p) return C.transformPoint(root,p) end
    local function direction(v)
        local zero=point({0,0,0})
        local p=point(v)
        return {p[1]-zero[1],p[2]-zero[2],p[3]-zero[3]}
    end
    local mask
    if boundary then
        mask={};for _,p in ipairs(boundary) do mask[#mask+1]=point(p) end
    end
    local objective=lens.objective
    -- FB7 item 5 (22.10.2026: through the scope the mount and the RMR on it showed where the tube goes on): the
    -- picture's camera stands on the eyepiece, so everything along the tube ahead of it - its own mount, a top
    -- sight, an offset adapter - was in the scope picture. The front of the active sight's box along the line of
    -- sight is its objective end: the scope picture starts there (Java: near plane at that depth), the barrel and
    -- front sight ahead of the objective stay in it (FB4 item 15).
    local front
    local b=C.data.items[view.tpl] and C.data.items[view.tpl].bounds
    if b then
        local depth
        for corner=0,7 do
            local p={}
            for a=1,3 do p[a]=math.floor(corner/2^(a-1))%2==0 and b.min[a] or b.max[a] end
            local along=dot({p[1]-ocular.center[1],p[2]-ocular.center[2],p[3]-ocular.center[3]},forward)
            depth=math.max(depth or along,along)
        end
        if depth and depth>0 then front=offset(ocular.center,forward,depth) end
    end
    -- FB4 (item 15): the own weapon stays in the picture as Tarkov's optic camera sees it; only the sight itself,
    -- its subtree and the mounts carrying it (the tube and rings round the camera) are kept out.
    local hide={}
    for path,tpl in pairs(view.path~='' and tree or {}) do
        if path==view.path or path:sub(1,#view.path+1)==view.path..'/' then hide[#hide+1]=tpl end
    end
    local at=view.path
    while true do
        local parent=at:match('^(.*)/[^/]+$')
        local tpl=parent and tree[parent]
        if not (tpl and C.data.items[tpl] and C.data.items[tpl].category=='Mount') then break end
        hide[#hide+1]=tpl
        at=parent
    end
    -- FB8 item 1 (22.10.2026, "parts of the mount are still visible in the sight"): a near plane at the front end cuts
    -- what crosses it (at 1x the handguard below the line of sight showed its inside). An optical sight's picture is
    -- formed at its objective, so its camera stands at the front end on its axis; nothing of the weapon lies between.
    -- Thermal and night sensors keep the eyepiece camera (their veil of near glows is measured from it, FB5).
    local cameraPoint,cameraSource=objective and objective.center or ocular.center,objective and 'objective' or 'ocular'
    if P.frontCamera and front and not objective and view.kind=='optic' then cameraPoint,cameraSource=front,'front' end
    return {kind=view.kind,magnification=view.magnification,fov=view.fov,
        reticleInventory=Optics,reticleItem=view.profile,reticleMode=view.modeProfile,
        reticleBrightness=view.brightness,
        key=view.path..':'..view.tpl..':'..view.channel..':'..view.mode,
        camera=point(cameraPoint),ocular=point(ocular.center),
        objective=objective and point(objective.center),cameraSource=cameraSource,
        front=front and point(front),
        edgeX=point(offset(ocular.center,right,halfX)),edgeY=point(offset(ocular.center,vertical,halfY)),
        boundary=mask,planeDepth=boundary~=nil or (view.kind~='optic' and view.magnification<=1.0001),
        screen=view.kind~='optic' and view.modeProfile.ignoreOpticsForCameraPlane==true,
        eye=point(eye),forward=direction(forward),up=direction(up),hide=hide}
end

-- W10 item 11 + W11 nopipzoom: the option's mode, and the channels the camera zoom shows: every optical mode, its 1x
-- too (30.10.2026, the player: on the 3D SightGlass a 1x optic - fixed 1x modes, variable ones at 1x - showed its
-- opaque tube with the reticle on it, as the main image has no picture in the lens); nopip2: every NV/thermal mode.
-- Collimators and irons keep their glass.
function P.zoomMode() return options:getOption('ScopePicture'):getValue()>=2 end
function P.fieldMode() return options:getOption('ScopePicture'):getValue()==3 end
function P.sensor(view) return view~=nil and (view.kind=='thermal' or view.kind=='nv') end
function P.zoomed(view)
    if not view or (view.kind~='optic' and not P.sensor(view)) then return false end
    return type(view.magnification)=='number'
end

-- W13: only the local, weapon-space geometry is memoized. The Java setters and Sight.current still run every
-- frame. R.view's fifth value confirms an immutable parts snapshot; a fresh read always builds fresh geometry.
-- Source profiles are immutable loaded data. Copy both matrices: a caller changing one in place must invalidate.
P.memoOff=false -- QA A/B: fresh geometry and part reads, as before W13.
local geometryFields={'weapon','path','tpl','channel','mode','kind','fov','magnification','brightness','profile','modeProfile'}
local function copyFrame(m)
    if not m then return nil end
    local out={}
    for i=1,3 do for j=1,4 do out[#out+1]=m[i][j] end end
    return out
end
local function sameFrame(flat,m)
    if not flat or not m then return flat==nil and m==nil end
    for i=1,3 do for j=1,4 do if flat[(i-1)*4+j]~=m[i][j] then return false end end end
    return true
end
-- Shared by the PIP and the unmagnified glass handlers, each with its own single, bounded memo.
function P.cachedGeometry(owner,view,key,tree,shared)
    if not shared or owner.memoOff then owner.geometryMemo=nil;return owner.geometry(view,key,tree) end
    local cached=owner.geometryMemo
    local same=cached and cached.key==key and cached.tree==tree and cached.builder==owner.geometry
        and cached.frontCamera==P.frontCamera
    if same then
        for _,field in ipairs(geometryFields) do if cached[field]~=view[field] then same=false;break end end
    end
    if same and sameFrame(cached.frame,view.frame) and sameFrame(cached.rangeMove,view.rangeMove) then return cached.value end
    local value=owner.geometry(view,key,tree)
    cached={key=key,tree=tree,builder=owner.geometry,frontCamera=P.frontCamera,
        frame=copyFrame(view.frame),rangeMove=copyFrame(view.rangeMove),value=value}
    for _,field in ipairs(geometryFields) do cached[field]=view[field] end
    owner.geometryMemo=cached
    return value
end

function P.update()
    local zb=rawget(_G,'TWZB')
    if type(zb)~='table' or not zb.pipOptions or not zb.pipSet then return end
    local player=getSpecificPlayer(0)
    local on=options:getOption('ScopePip'):getValue()==true and TWViewpoint and TWViewpoint.enabled()
    local every=options:getOption('ScopePipFrequency'):getValue()
    local zoom=on and zb.zoomSet~=nil and P.zoomMode()
    local active=on and player and not player:isDead() and (TWZB and TWZB.aimIntent or player.isAiming)(player)
        and zb.vpState and zb.vpState()==1
    local view=active and Sight.current(player) or nil
    local key=view and R.key(view.weapon)
    local tree,shared
    if key then
        local entries,parts,problem
        tree,entries,parts,problem,shared=(not P.memoOff and R.view or R.read)(view.weapon)
    else P.geometryMemo=nil end
    local geometry=key and P.cachedGeometry(P,view,key,tree,shared) or nil
    -- In the camera-zoom mode the second render is off for every sight (not even built ahead).
    local pip=on and not zoom
    zb.pipOptions(pip==true,options:getOption('ScopePipResolution'):getValue(),every==3 and 4 or every)
    if zb.zoomOptions then zb.zoomOptions(zoom==true,zoom==true and P.fieldMode()) end
    if zb.zoomSet then zb.zoomSet(player,zoom and geometry and P.zoomed(view) and geometry or nil) end
    zb.pipSet(player,pip and geometry or nil)
end

Events.OnTickEvenPaused.Add(P.update)
Events.OnGameStart.Add(P.update)
return P
