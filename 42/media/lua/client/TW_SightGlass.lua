-- Our unmagnified marks. No separate world camera, UI overlay, or third-party optic registry.
local Pip=require('TW_ScopePipOptions')
local C=require('TW/Core')
local R=require('TW/Runtime')
local Sight=require('TW/SightState')
local Geometry=require('TW/GeometryState')
local Optics=require('TW/Data/ViewpointOptics')
local Render=require('TW/Render')
local G={memoOff=false} -- QA A/B: fresh geometry and part reads, as before W13.
TWSightGlass=G

local function dot(a,b) return a[1]*b[1]+a[2]*b[2]+a[3]*b[3] end
local function cross(a,b) return {a[2]*b[3]-a[3]*b[2],a[3]*b[1]-a[1]*b[3],a[1]*b[2]-a[2]*b[1]} end
local function normal(a)
    local n=math.sqrt(dot(a,a))
    if n<1e-9 then return end
    return {a[1]/n,a[2]/n,a[3]/n}
end
local function offset(p,v,r) return {p[1]+v[1]*r,p[2]+v[2]*r,p[3]+v[3]*r} end

function G.geometry(view,key,tree)
    if not view or (view.kind~='collimator' and not (view.kind=='optic'
        and view.magnification and view.magnification>0 and view.magnification<=1.0001)) then return end
    local lens=Geometry.lens(view)
    local ocular=lens and lens.ocular
    if not ocular or not ocular.center or not ocular.normal or not view.frame then return end
    local boundary=ocular.boundary
    local radius=ocular.radius
    if not radius then
        if not boundary or #boundary<3 or #boundary>64 then return end
        radius=0
        for _,p in ipairs(boundary) do
            local delta={p[1]-ocular.center[1],p[2]-ocular.center[2],p[3]-ocular.center[3]}
            radius=math.max(radius,math.sqrt(dot(delta,delta)))
        end
    end
    if radius<=0 then return end
    local placement=C.placement(key,tree,view.path)
    if not placement then return end
    local aim=view.frame
    local up=normal({-aim[1][2],-aim[2][2],-aim[3][2]})
    local forward=normal({-aim[1][3],-aim[2][3],-aim[3][3]})
    if not up or not forward then return end
    local eye={aim[1][4],aim[2][4],aim[3][4]}
    local toLens={ocular.center[1]-eye[1],ocular.center[2]-eye[2],ocular.center[3]-eye[3]}
    if dot(forward,toLens)<0 then forward={-forward[1],-forward[2],-forward[3]} end
    local right=normal(cross(forward,up))
    local n=normal(ocular.normal)
    if not right or not n then return end
    local d=dot(right,n)
    right=normal({right[1]-d*n[1],right[2]-d*n[2],right[3]-d*n[3]})
    if not right then return end
    local vertical=normal(cross(n,right))
    if dot(vertical,up)<0 then vertical={-vertical[1],-vertical[2],-vertical[3]} end
    local root=C.renderMatrix(key,placement)
    local function point(p) return C.transformPoint(root,p) end
    local function direction(v)
        local zero=point({0,0,0})
        local p=point(v)
        return {p[1]-zero[1],p[2]-zero[2],p[3]-zero[3]}
    end
    local mask
    if boundary then
        mask={}
        for _,p in ipairs(boundary) do
            local delta={p[1]-ocular.center[1],p[2]-ocular.center[2],p[3]-ocular.center[3]}
            mask[#mask+1]=dot(delta,right)/radius
            mask[#mask+1]=-dot(delta,vertical)/radius
        end
    end
    return {kind=view.kind,magnification=view.magnification,fov=view.fov,glassBoundary=mask,
        key=view.path..':'..view.tpl..':'..view.channel..':'..view.mode,
        reticleInventory=Optics,reticleItem=view.profile,reticleMode=view.modeProfile,
        reticleBrightness=view.brightness,
        camera=point(ocular.center),ocular=point(ocular.center),
        edgeX=point(offset(ocular.center,right,radius)),edgeY=point(offset(ocular.center,vertical,radius)),
        eye=point(eye),forward=direction(forward),up=direction(up)}
end

-- FB8 item 5 (22.10.2026, "when another sight is chosen the others stop working, replaced by black glass"): the glass
-- of every collimator of the held assembly is open and shows its mark - the other sights while aiming, all of them at
-- the hip - as Tarkov's collimators do (their mark is on the lens, not tied to the chosen sight); the mark lies at
-- infinity along the sight's axis, so it shows only where that direction crosses the glass. The one aimed through is
-- listed too: until its own window (glassSet) is drawn the extras keep it open, then they leave it to that window
-- (SightGlassExtras). Magnified optics keep their eyepiece glass: an eyepiece seen off its axis is dark.
function G.extras(player)
    local weapon=player:getPrimaryHandItem()
    local key=weapon and R.key(weapon)
    if not key then return nil end
    local tree,_,parts=(not G.memoOff and R.view or R.read)(weapon)
    local copies={}
    for _,entry in ipairs(C.entries(tree,key)) do copies[entry.path]=entry end
    local list={}
    for _,channel in ipairs(R.sights and R.sights(weapon,tree,key) or C.sights(tree,key)) do
        local entry=copies[channel.path]
        if entry and #list<8 then
            local view=Sight.bind(weapon,channel,parts,true)
            if view and view.kind=='collimator' and view.modeProfile then
                local g=G.geometry(view,key,tree)
                if g then
                    g.anchor=Render.anchorName(entry.tpl,entry.copy);g.tpl=entry.tpl;g.profile=view.modeProfile.id
                    list[#list+1]=g
                end
            end
        end
    end
    return #list>0 and list or nil
end

-- The extras are built again only when the weapon, its drawn assembly (Render fingerprint) or a sight state changes.
local extrasKey
function G.update()
    local zb=rawget(_G,'TWZB')
    if type(zb)~='table' or not zb.glassSet then return end
    local player=getSpecificPlayer(0)
    local fp=player and not player:isDead() and TWViewpoint and TWViewpoint.enabled() and zb.vpState and zb.vpState()==1
    local aiming=fp and (TWZB and TWZB.aimIntent or player.isAiming)(player)
    local view=fp and Sight.current(player)
    if zb.glassExtras then
        local weapon=fp and player:getPrimaryHandItem()
        local drawn=weapon and Render.owner[weapon]
        local key=weapon and (weapon:getID()..':'..tostring(drawn and drawn.fp)..':'..tostring(Sight.revision)) or ''
        if key~=extrasKey then
            extrasKey=key
            zb.glassExtras(player,weapon and G.extras(player) or nil)
        end
    end
    if not aiming then G.geometryMemo=nil;zb.glassSet(player,nil);return end
    local key=view and R.key(view.weapon)
    local tree,shared
    if key then
        local entries,parts,problem
        tree,entries,parts,problem,shared=(not G.memoOff and R.view or R.read)(view.weapon)
    else G.geometryMemo=nil end
    zb.glassSet(player,key and Pip.cachedGeometry(G,view,key,tree,shared) or nil)
end

Events.OnTickEvenPaused.Add(G.update)
Events.OnGameStart.Add(G.update)
return G
