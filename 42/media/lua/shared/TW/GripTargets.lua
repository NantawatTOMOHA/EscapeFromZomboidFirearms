-- Runtime grip targets (tools/grip_targets.py is the same computation offline and
-- generates TW/GripTargetsData). Every rifle-type weapon plays the grip clips of
-- the reference class of the support its actual assembly holds (bare handguard,
-- vertical / angled foregrip, handstop, underbarrel launcher); handguns play one
-- handgun reference. M.params returns what GripIK.java needs to move the weapon
-- and both arms from the reference onto this assembly (Bip01_Prop1 space):
-- dR(3), A(12), hold(3), hasButt, butt(3), refButt(3), pocket(3), hasElbow, elbow(3),
-- hasHigh, highA(12), highElbow(3), highBore(elevation, yaw), hasLock, hold rotation (9 rows),
-- lowBack, lowLift, patrol weight, bare handguard (FB2-D: a comfortable reach); named field wrap (FB2-E: the
-- bare handguard's cross-section stations round the hold, y, n, n (x, z) pairs, for the finger fit); named field hold
-- (FB2-N: kind, the grip surface's frame (nil: Prop1), the slide along it from the hold, a vertical grip's finger rests -
-- GripIK.java chooses the hold on the surface with the arm; wrap then carries every station of it, in that frame).
local R=require('TW/Runtime')
local C=require('TW/Core')
local D=C.data
local G=require('TW/Grips')
local T=require('TW/GripTargetsData')

-- Pistol in both hands (tools/pistol_grip.py -> TW/PistolGripData): each handgun class's fingers round its grip as
-- named fields of its params, so the 64 numbers stay as they are - fingers and shift (isometric / third person,
-- GripIK.java), fpFingers and fpHands (first person, FpPose.java; through TW_FirstPersonPose).
do
    local PG=require('TW/PistolGripData')
    for cls,bySex in pairs(T.handguns or {}) do
        local g=PG[cls]
        for sex,p in pairs(g and bySex or {}) do
            local f=g[sex]
            if f then p.fingers=f.fingers;p.shift=f.shift;p.fpFingers=f.fpFingers;p.fpHands=f.fpHands end
        end
    end
end

local M={data=T,cache=setmetatable({},{__mode='k'})}
TWGripTargets=M
local KINDS={'bare','vertical','angled','handstop','ubgl'}
M.kinds=KINDS
local ROCKET_CLASS='TW_Contact_RSHG'

-- --- 3x4 affine matrices as 12 numbers (rows) ------------------------------------------------
local function flat(m) -- TW/Core 3x4 rows -> 12
    return {m[1][1],m[1][2],m[1][3],m[1][4],m[2][1],m[2][2],m[2][3],m[2][4],m[3][1],m[3][2],m[3][3],m[3][4]}
end
local function mul(a,b)
    local r={}
    for i=0,2 do
        for j=1,4 do
            local v=a[i*4+1]*b[j]+a[i*4+2]*b[4+j]+a[i*4+3]*b[8+j]
            if j==4 then v=v+a[i*4+4] end
            r[i*4+j]=v
        end
    end
    return r
end
local function point(m,p)
    return {m[1]*p[1]+m[2]*p[2]+m[3]*p[3]+m[4],m[5]*p[1]+m[6]*p[2]+m[7]*p[3]+m[8],m[9]*p[1]+m[10]*p[2]+m[11]*p[3]+m[12]}
end
-- Inverse of a rigid transform (orthonormal rotation).
local function inverseRigid(m)
    local r={m[1],m[5],m[9],0,m[2],m[6],m[10],0,m[3],m[7],m[11],0}
    for i=0,2 do r[i*4+4]=-(r[i*4+1]*m[4]+r[i*4+2]*m[8]+r[i*4+3]*m[12]) end
    return r
end
local function slotName(path) return path:match('([^/]+)$') end

-- Prop-space box of an installed part; grip: a handguard's grip span (T.grips, the part of its mesh
-- below the middle of its box - rails running back over the receiver are not held) where it has one.
local function partBox(key,tree,path,grip)
    local tpl=tree[path]
    local item=tpl and D.items[tpl]
    local b=item and (grip and T.grips and T.grips[tpl] or item.bounds)
    local pl=C.placement(key,tree,path)
    if not b or not pl then return nil end
    pl=flat(pl)
    local lo,hi={math.huge,math.huge,math.huge},{-math.huge,-math.huge,-math.huge}
    for corner=0,7 do
        local p={}
        for a=1,3 do p[a]=math.floor(corner/2^(a-1))%2==0 and b.min[a] or b.max[a] end
        local v=point(pl,p)
        for a=1,3 do lo[a]=math.min(lo[a],v[a]);hi[a]=math.max(hi[a],v[a]) end
    end
    return lo,hi
end

-- An installed part the support hand holds as the handguard: a mod_handguard slot, or a gas tube carrying the
-- handguard (FB2-L: T.handguards, tools/grip_targets.py integral_handguards).
local function handguardPart(tree,path)
    return slotName(path):sub(1,13)=='mod_handguard' or (T.handguards~=nil and T.handguards[tree[path]]==true)
end
M.handguardPart=handguardPart

local function unionBoxes(key,tree,test,grip)
    local lo,hi
    for path in pairs(tree) do
        if test(slotName(path),path) then
            local a,b=partBox(key,tree,path,grip)
            if a then
                if not lo then lo,hi={a[1],a[2],a[3]},{b[1],b[2],b[3]}
                else for i=1,3 do lo[i]=math.min(lo[i],a[i]);hi[i]=math.max(hi[i],b[i]) end end
            end
        end
    end
    return lo,hi
end

-- FB2-E: the bare handguard's cross-section (tools/grip_targets.py convex_hull .. bare_hold, same order and
-- ties). T.sections[tpl]: stations along the bore of its grip span (part space, integers of T.sectionQuant) as
-- y, n, n (x, z) pairs; an
-- assembly merges its handguard parts' stations per T.sectionStep in Prop1 space: {{y=..,poly={x,z,..}},..}.
local function cross2(o,a,b) return (a[1]-o[1])*(b[2]-o[2])-(a[2]-o[2])*(b[1]-o[1]) end

local function hull(pts) -- {{x,z},..} -> counter-clockwise hull, monotone chain
    local p,seen={},{}
    for _,q in ipairs(pts) do
        local k=q[1]..','..q[2]
        if not seen[k] then seen[k]=true;p[#p+1]=q end
    end
    table.sort(p,function(a,b) return a[1]<b[1] or a[1]==b[1] and a[2]<b[2] end)
    if #p<3 then return p end
    local lower,upper={},{}
    for i=1,#p do
        while #lower>=2 and cross2(lower[#lower-1],lower[#lower],p[i])<=0 do lower[#lower]=nil end
        lower[#lower+1]=p[i]
    end
    for i=#p,1,-1 do
        while #upper>=2 and cross2(upper[#upper-1],upper[#upper],p[i])<=0 do upper[#upper]=nil end
        upper[#upper+1]=p[i]
    end
    local out={}
    for i=1,#lower-1 do out[#out+1]=lower[i] end
    for i=1,#upper-1 do out[#out+1]=upper[i] end
    return out
end

local function simplify(poly,n) -- drop the least-area vertex until n remain
    while #poly>n do
        local best,k=math.huge,1
        for i=1,#poly do
            local a,b,c=poly[i==1 and #poly or i-1],poly[i],poly[i==#poly and 1 or i+1]
            local area=math.abs((b[1]-a[1])*(c[2]-a[2])-(b[2]-a[2])*(c[1]-a[1]))
            if area<best then best,k=area,i end
        end
        table.remove(poly,k)
    end
    return poly
end

local function onPartFor(w,lo,hi)
    if w and w.pumpForend and lo and hi then
        return math.min(T.forendOnPart,math.max(T.onPart,T.forendMaxFrac*(hi[2]-lo[2])))
    end
    return T.onPart
end

-- FB2-N: extra - a support part (angled grip, hand stop) whose own stations (T.supportSections) join the handguard's.
local function sections(key,tree,extra)
    local buckets,keys={},{}
    local paths={}
    for path in pairs(tree) do if handguardPart(tree,path) or path==extra then paths[#paths+1]=path end end
    table.sort(paths)
    for _,path in ipairs(paths) do
        local s
        if path==extra then s=T.supportSections and T.supportSections[tree[path]]
        else s=T.sections and T.sections[tree[path]] end
        local pl=s and C.placement(key,tree,path)
        if pl then
            pl=flat(pl)
            local i,q=1,T.sectionQuant
            while i<=#s do
                local y,n=s[i]*q,s[i+1]
                local pts,sy={},0
                for j=0,n-1 do
                    local v=point(pl,{s[i+2+2*j]*q,y,s[i+3+2*j]*q})
                    pts[#pts+1]={v[1],v[3]};sy=sy+v[2]
                end
                local k=math.floor(sy/n/T.sectionStep+0.5)
                if not buckets[k] then buckets[k]={};keys[#keys+1]=k end
                for _,q in ipairs(pts) do table.insert(buckets[k],q) end
                i=i+2+2*n
            end
        end
    end
    local w=T.weapons[key]
    if w and w.forendBarrel and T.barrelSections then
        local hgLo,hgHi=unionBoxes(key,tree,function(_,path) return handguardPart(tree,path) end,true)
        if hgLo then
            local loY,hiY=hgLo[2]-T.sectionStep/2,hgHi[2]+T.sectionStep/2
            local bpaths={}
            for path in pairs(tree) do if slotName(path):sub(1,10)=='mod_barrel' then bpaths[#bpaths+1]=path end end
            table.sort(bpaths)
            for _,path in ipairs(bpaths) do
                local s=T.barrelSections[tree[path]]
                local pl=s and C.placement(key,tree,path)
                if pl then
                    pl=flat(pl)
                    local i,q=1,T.sectionQuant
                    while i<=#s do
                        local y,n=s[i]*q,s[i+1]
                        local pts,sy={},0
                        for j=0,n-1 do
                            local v=point(pl,{s[i+2+2*j]*q,y,s[i+3+2*j]*q})
                            pts[#pts+1]={v[1],v[3]};sy=sy+v[2]
                        end
                        local ym=sy/n
                        if ym>=loY and ym<=hiY then
                            local k=math.floor(ym/T.sectionStep+0.5)
                            if not buckets[k] then buckets[k]={};keys[#keys+1]=k end
                            for _,q in ipairs(pts) do table.insert(buckets[k],q) end
                        end
                        i=i+2+2*n
                    end
                end
            end
        end
    end
    table.sort(keys)
    local out={}
    for _,k in ipairs(keys) do
        local h=hull(buckets[k])
        if #h>=3 then out[#out+1]={y=k*T.sectionStep,poly=simplify(h,T.sectionVerts)} end
    end
    return out
end

local function stationAt(st,y)
    local best,d=nil,math.huge
    for _,s in ipairs(st) do if math.abs(s.y-y)<d then best,d=s,math.abs(s.y-y) end end
    return best.poly
end

local function polySd(x,z,poly) -- signed distance to a ccw convex polygon, - inside (finger_wrap.poly_sd)
    local best,inside=math.huge,true
    for i=1,#poly do
        local a,b=poly[i],poly[i==#poly and 1 or i+1]
        local ex,ez=b[1]-a[1],b[2]-a[2]
        local ll=ex*ex+ez*ez
        local t=ll<=0 and 0 or math.min(1,math.max(0,((x-a[1])*ex+(z-a[2])*ez)/ll))
        local dx,dz=x-a[1]-t*ex,z-a[2]-t*ez
        best=math.min(best,dx*dx+dz*dz)
        if ex*(z-a[2])-ez*(x-a[1])<0 then inside=false end
    end
    return inside and -math.sqrt(best) or math.sqrt(best)
end

local function sectionSd(st,p) -- finger_wrap.wrap_sd: the bracketing stations interpolated along the bore
    if p[2]<=st[1].y then return polySd(p[1],p[3],st[1].poly) end
    for i=1,#st-1 do
        local a,b=st[i],st[i+1]
        if p[2]<=b.y then
            local t=b.y>a.y and (p[2]-a.y)/(b.y-a.y) or 0
            return (1-t)*polySd(p[1],p[3],a.poly)+t*polySd(p[1],p[3],b.poly)
        end
    end
    return polySd(p[1],p[3],st[#st].poly)
end

-- W7 (grip_targets.section_centre): the lateral middle of the stations' hull within +-T.sectionWindow of y. One
-- station's polygon can be a sliver - the mesh holds no vertices across some places - so its min/max x is not the
-- part's side; the hull of the neighbours is.
local function sectionCentre(st,y)
    local pts
    for _,s in ipairs(st) do
        if math.abs(s.y-y)<=T.sectionWindow+1e-9 then
            pts=pts or {}
            for _,v in ipairs(s.poly) do pts[#pts+1]=v end
        end
    end
    if not pts then
        local best,d=nil,math.huge
        for _,s in ipairs(st) do if math.abs(s.y-y)<d then best,d=s,math.abs(s.y-y) end end
        pts=best.poly
    end
    local lo,hi=math.huge,-math.huge
    for _,v in ipairs(pts) do lo=math.min(lo,v[1]);hi=math.max(hi,v[1]) end
    return (lo+hi)/2
end

-- The support hand on the stations with its knuckles y along the bore (grip_targets.bare_hold): Tarkov's
-- rotation, the knuckles off the far side and underside of the nearest station by the reference offset,
-- then along Z until the palm proxy lies T.palmSink inside the section (palm_contact), then the palm's lateral
-- centre onto the section's (W7: sectionCentre).
local function bareHold(st,y,ref)
    local R,t1,off,palm=ref.rotation,ref.knuckle,ref.offset,ref.palm
    local poly=stationAt(st,y)
    local minX,minZ=math.huge,math.huge
    for _,v in ipairs(poly) do minX=math.min(minX,v[1]);minZ=math.min(minZ,v[2]) end
    local L={R[1],R[2],R[3],0, R[4],R[5],R[6],0, R[7],R[8],R[9],0}
    local k={minX+off[1],y,minZ+off[3]}
    for r=0,2 do L[r*4+4]=k[r+1]-(R[r*3+1]*t1[1]+R[r*3+2]*t1[2]+R[r*3+3]*t1[3]) end
    local lo,hi=st[1].y-T.sectionStep/2,st[#st].y+T.sectionStep/2
    local pts={}
    for i=1,#palm,3 do
        local v=point(L,{palm[i],palm[i+1],palm[i+2]})
        if v[2]>=lo and v[2]<=hi then pts[#pts+1]=v end
    end
    if #pts>0 then
        local function depth(dz)
            local d=-math.huge
            for _,v in ipairs(pts) do d=math.max(d,-sectionSd(st,{v[1],v[2],v[3]+dz})) end
            return d
        end
        local a,b=-T.palmRange,T.palmRange
        for _=1,24 do
            local m=(a+b)/2
            if depth(m)>T.palmSink then b=m else a=m end
        end
        L[12]=L[12]+(a+b)/2
    end
    -- W7: the palm's lateral centre onto the section's - the knuckle anchor above keeps the fingers on the far side, but
    -- the palm followed the widest vertex there, so the hand stood to one side of the handguard (user item 4)
    local mx,sy,n=0,0,0
    for i=1,#palm,3 do
        local v=point(L,{palm[i],palm[i+1],palm[i+2]})
        mx=mx+v[1];sy=sy+v[2];n=n+1
    end
    if n>0 then L[4]=L[4]+(sectionCentre(st,sy/n)-mx/n) end
    return L
end

-- FB2-N: every station, flat: y, n, n (x, z) pairs (grip_targets.wrap_flat) - the hold slides along the whole surface.
local function allFlat(st)
    local out={}
    for _,s in ipairs(st) do
        out[#out+1]=s.y;out[#out+1]=#s.poly
        for _,v in ipairs(s.poly) do out[#out+1]=v[1];out[#out+1]=v[2] end
    end
    return out
end

-- Stations stored as integers of T.sectionQuant (a vertical grip's, in its own frame), flat as GripIK.java reads them.
local function scaledFlat(s)
    local out,q={},T.sectionQuant
    local i=1
    while i<=#s do
        local n=s[i+1]
        out[#out+1]=s[i]*q;out[#out+1]=n
        for j=0,2*n-1 do out[#out+1]=s[i+2+j]*q end
        i=i+2+2*n
    end
    return out
end

-- The stations GripIK.java fits the fingers on (grip_targets.wrap_window), flat: y, n, n (x, z) pairs.
local function wrapFlat(st,y,back)
    local w={}
    for _,s in ipairs(st) do
        if s.y>=y-T.wrapBack-back-1e-9 and s.y<=y+T.wrapFront+1e-9 then w[#w+1]=s end
    end
    if #w==0 then
        local best,d=nil,math.huge
        for _,s in ipairs(st) do if math.abs(s.y-y)<d then best,d=s,math.abs(s.y-y) end end
        w[1]=best
    end
    local out={}
    for _,s in ipairs(w) do
        out[#out+1]=s.y;out[#out+1]=#s.poly
        for _,v in ipairs(s.poly) do out[#out+1]=v[1];out[#out+1]=v[2] end
    end
    return out
end

-- TW/Grips chooseSupport: highest priority, equal priority -> smallest path.
local function chooseSupport(tree)
    local sel,selTpl,kind,rank=nil,nil,nil,-1
    for path,tpl in pairs(tree) do
        local info=G.supports[tpl]
        if info then
            local pr=info.priority or 50
            if pr>rank or pr==rank and path<sel then sel,selTpl,kind,rank=path,tpl,info.type,pr end
        end
    end
    return sel,selTpl,kind
end

-- Support anchor frame: top-centre of the part's Prop1-space box, axes as a rail
-- foregrip slot (part +Z hangs down); slot frames turn arbitrarily on chassis/mounts.
local function boxFrame(lo,hi)
    return {-1,0,0,(lo[1]+hi[1])/2, 0,1,0,(lo[2]+hi[2])/2, 0,0,-1,hi[3]}
end

function M.kindOf(key,tree)
    local w=T.weapons[key]
    local path,tpl,kind=chooseSupport(tree)
    -- PKM/PKP: own hold over the ammo box, only without a support part (FB8 item 7: "does not hold the foregrip")
    if w and w.fixedLeft and not (kind and T.refs[kind]) then return 'bare' end
    if kind and T.refs[kind] then return kind,path,tpl end
    if w and w.integral then return 'vertical' end
    return 'bare'
end

local function rightWrist(w,ref)
    if w.trigger then
        local t=w.trigger
        if w.neckZ then return {t[1]+T.neck[1],t[2]+T.neck[2],w.neckZ} end
        return {t[1]+ref.triggerOffset[1],t[2]+ref.triggerOffset[2],t[3]+ref.triggerOffset[3]}
    end
    if w.gripAnchor then
        local a=w.gripAnchor
        return {a[1]+ref.gripOffset[1],a[2]+ref.gripOffset[2],a[3]+ref.gripOffset[3]}
    end
    return w.right
end

local function buttPoint(key,tree,w)
    local lo,hi=unionBoxes(key,tree,function(s) return s:find('mod_stock',1,true)~=nil end)
    local b
    local rb=w.receiverButt
    if not lo then b=rb
    elseif rb and rb[2]<lo[2] then b=rb
    else b={(lo[1]+hi[1])/2,lo[2],(lo[3]+hi[3])/2} end
    local front=w.trigger and w.trigger[2] or 0
    if not b or front-b[2]<T.minPull then return nil end
    return b
end

-- FB2-N (grip_targets.support_hold / hold_grip): a support part's own hold and grip surface, or nil (the box hold stays).
-- vertical: Tarkov's right hand on the pistol grip, mirrored, on the grip's axis frame (T.vgrips); angled grip / hand stop:
-- the bare-handguard hand on the handguard and the part together, the knuckles T.supportFront behind the part's front.
local function supportHold(key,tree,kind,path,sex)
    local tpl=tree[path]
    local pl=C.placement(key,tree,path)
    if not pl then return nil end
    if kind=='vertical' then
        local vg=T.vgrips and T.vgrips[tpl]
        if not vg then return nil end
        local G=mul(flat(pl),vg.frame)
        local ref=T.refs.vertical[sex]
        return mul(G,ref.grip),{kind=T.holdKinds.vertical,frame=G,slide=ref.slide,rest=ref.rest},scaledFlat(vg.sections)
    end
    if kind=='angled' or kind=='handstop' then
        if not (T.supportSections and T.supportSections[tpl]) then return nil end
        local st=sections(key,tree,path)
        local lo,hi=partBox(key,tree,path)
        if #st==0 or not lo then return nil end
        local y=math.min(math.max(hi[2]-T.supportFront,st[1].y),st[#st].y)
        return bareHold(st,y,T.refs.bare[sex]),{kind=T.holdKinds[kind],slide=T.holdSlide[kind]},allFlat(st)
    end
    -- FB7: an underbarrel launcher - Tarkov's rifle-mode (grip mode) hand on it (T.ubglGrips, its part frame), chosen round
    -- on the handguard and launcher stations together
    if kind=='ubgl' then
        local g=T.ubglGrips and T.ubglGrips[tpl]
        if not (g and T.supportSections and T.supportSections[tpl]) then return nil end
        local st=sections(key,tree,path)
        if #st==0 then return nil end
        return mul(flat(pl),g[sex]),{kind=T.holdKinds.ubgl,slide=T.holdSlide.ubgl},allFlat(st)
    end
    return nil
end

local function leftTarget(key,tree,kind,path,female,w,rw)
    local sex=female and 'F' or 'M'
    -- FB7: Tarkov's rifle-mode hold on an underbarrel launcher (grip_targets.ubgl_grip), not the reference hand on its box
    local ug=kind=='ubgl' and path and T.ubglGrips and T.ubglGrips[tree[path]]
    local upl=ug and C.placement(key,tree,path)
    if upl then return mul(flat(upl),ug[sex]) end
    if kind~='bare' then
        local ref=T.refs[kind][sex]
        local lo,hi
        if path then lo,hi=partBox(key,tree,path)
        elseif w.integral then lo,hi=w.integral.min,w.integral.max end
        if not lo then return nil end
        local frame=boxFrame(lo,hi)
        return mul(frame,ref.hand)
    end
    -- PKM/PKP: the gas tube right in front of the ammo box, the forearm over the box.
    if w.fixedLeft then return w.fixedLeft[sex] end
    local lo,hi=unionBoxes(key,tree,function(_,path) return handguardPart(tree,path) end,true)
    if not lo then return w['bare'..sex] end
    -- FB2-E: Tarkov's hand on the handguard's own sections, the knuckles (Finger1 origin) at T.forward of the
    -- grip span, T.onPart onto it, T.frontMargin behind its front; the wrist at most T.maxSpread ahead of the right
    local st=sections(key,tree)
    if #st==0 then return nil end
    local ref=T.refs.bare[sex]
    local R,t1=ref.rotation,ref.knuckle
    local spread=rw[2]+T.maxSpread+R[4]*t1[1]+R[5]*t1[2]+R[6]*t1[3]
    local y=lo[2]+T.forward*(hi[2]-lo[2])
    y=math.min(y,hi[2]-T.frontMargin,spread)
    y=math.max(y,lo[2]+onPartFor(w,lo,hi))
    -- and how far forward the knuckles may go clear of a magazine (T.magFront from the front)
    return bareHold(st,y,ref),st,y,math.max(y,math.min(hi[2]-T.magFront,spread))
end

-- The magazine under the support hand (user 23.10.2026: "on the MPX the hand now goes into the magazine instead of the
-- handguard or the grip"; grip_targets.MAG_*): the installed magazines' slices (T.mags, part space) and the Prop1 -> part
-- transform of each.
local function magazines(key,tree)
    local paths,out={},{}
    for path in pairs(tree) do -- a belt box is not one (grip_targets.magazine_stations: the PK's hand is tuned over it)
        if slotName(path):sub(1,12)=='mod_magazine' and not (T.beltBoxes and T.beltBoxes[tree[path]]) then paths[#paths+1]=path end
    end
    table.sort(paths)
    for _,path in ipairs(paths) do
        local m=T.mags and T.mags[tree[path]]
        local pl=m and C.placement(key,tree,path)
        if pl then out[#out+1]={inv=inverseRigid(flat(pl)),m=m} end
    end
    return out
end

-- How deep the palm proxy of a hand L lies inside the magazines (<= 0: outside; grip_targets.magazine_depth).
local function magDepth(L,palm,mags)
    local worst,q,s=-math.huge,T.sectionQuant,T.magSlice
    for _,g in ipairs(mags) do
        local m=g.m
        local y0,n=m[1]*q,(#m-1)/4
        for i=1,#palm,3 do
            local w=point(g.inv,point(L,{palm[i],palm[i+1],palm[i+2]}))
            local k=math.floor((w[2]-y0)/s+0.5)
            local b=2+4*k
            if k>=0 and k<n and not (m[b]==0 and m[b+1]==0 and m[b+2]==0 and m[b+3]==0) then
                worst=math.max(worst,math.min(w[1]-m[b]*q,m[b+1]*q-w[1],w[3]-m[b+2]*q,m[b+3]*q-w[3]))
            end
        end
    end
    return worst
end

-- place(y) slid forward T.magStep at a time from y until its palm is at most T.magTol inside the magazines, at most to yMax
-- (there: the least deep tried; grip_targets.clear_magazine).
local function clearMagazine(place,y,yMax,palm,mags)
    local L=place(y)
    if #mags==0 then return L end
    local d=magDepth(L,palm,mags)
    local best,bestL=d,L
    while d>T.magTol and y+T.magStep<=yMax+1e-9 do
        y=y+T.magStep
        L=place(y)
        d=magDepth(L,palm,mags)
        if d<best then best,bestL=d,L end
    end
    if d<=T.magTol then return L end
    return bestL
end

-- GripIK.java 'mags': per magazine the Prop1 -> part transform (12), the first slice's y and x0, x1, z0, z1 per slice; nil: none.
local function magFlat(mags)
    if #mags==0 then return nil end
    local out,q={},T.sectionQuant
    for _,g in ipairs(mags) do
        local f={}
        for i=1,12 do f[i]=g.inv[i] end
        for i=1,#g.m do f[12+i]=g.m[i]*q end
        out[#out+1]=f
    end
    return out
end

-- A hold not on stations moved along the bore so that y0 goes to y (grip_targets.shifted).
local function shifted(L,y0)
    return function(y)
        local r={}
        for i=1,12 do r[i]=L[i] end
        r[8]=L[8]+y-y0
        return r
    end
end

-- Everything GripIK.java needs for one rifle assembly, or nil.
function M.compute(key,tree,female)
    local w=T.weapons[key]
    if not w then return nil end
    local kind,path=M.kindOf(key,tree)
    local sex=female and 'F' or 'M'
    local ref=T.refs[kind][sex]
    local rw=rightWrist(w,ref)
    if not rw then return nil end
    -- No measurable support (unreadable part, unknown geometry): the reference hold stays.
    local L,st,yb,yFront=leftTarget(key,tree,kind,path,female,w,rw)
    L=L or ref.hold
    -- FB2-N: a support part's own hold is the base GripIK.java chooses from (A carries the reference hold onto it); the
    -- first-person layer keeps its contact on the part's box (left: FB2-K's tuned first-person poses), except on an
    -- angled grip (user 23.10.2026): the reference clip's hand there (TW_Contact_P121) is turned half against its own
    -- elbow (thumb down, ArmNatural's twist -170 degrees), and no elbow carries it - the first person takes the base hold
    local Lg,hold,ownWrap=L,nil,nil
    if kind~='bare' and path then
        local own,h,ow=supportHold(key,tree,kind,path,female and 'F' or 'M')
        if own then Lg,hold,ownWrap=own,h,ow end
    end
    -- the first person's hand clear of the magazine (T.mag*): slid along its stations, or shifted along the bore; on an
    -- angled grip from the base slid back to the tuned contact's wrist (FB2-K: the support elbow out of view in high ready)
    local refB,mags=T.refs.bare[sex],magazines(key,tree)
    local left
    if (kind=='angled' or kind=='handstop') and hold then
        local sst=sections(key,tree,path)
        local _,hi=partBox(key,tree,path)
        local y=math.min(math.max(hi[2]-T.supportFront,sst[1].y),sst[#sst].y)
        left=clearMagazine(function(y_) return bareHold(sst,y_,refB) end,math.min(math.max(y-(Lg[8]-L[8]),sst[1].y),sst[#sst].y),
            sst[#sst].y,refB.palm,mags)
    elseif kind=='bare' and yb then
        left=clearMagazine(function(y_) return bareHold(st,y_,refB) end,yb,yFront,refB.palm,mags)
    else
        left=clearMagazine(shifted(L,L[8]),L[8],L[8]+T.magShift,refB.palm,mags)
    end
    -- third person: a hold GripIK.java does not choose on a surface is the first person's, clear of the magazine
    if not ((kind=='bare' and yb) or hold) then Lg=left end
    local A=mul(Lg,inverseRigid(ref.hold))
    local p={ref.R[1]-rw[1],ref.R[2]-rw[2],ref.R[3]-rw[3]}
    for i=1,12 do p[#p+1]=A[i] end
    p[#p+1]=ref.hold[4];p[#p+1]=ref.hold[8];p[#p+1]=ref.hold[12]
    local butt=buttPoint(key,tree,w)
    if butt then
        p[#p+1]=1
        for _,v in ipairs({butt,ref.butt,ref.pocket}) do for i=1,3 do p[#p+1]=v[i] end end
    else
        for _=1,10 do p[#p+1]=0 end
    end
    local box=kind=='bare' and w.fixedLeft~=nil -- PKM/PKP on the box hold (no support part)
    if box and w.elbow then -- weapon-space elbow direction (the forearm over the ammo box)
        p[#p+1]=1;p[#p+1]=w.elbow[1];p[#p+1]=w.elbow[2];p[#p+1]=w.elbow[3]
    else
        for _=1,4 do p[#p+1]=0 end
    end
    if box and w.highLeft then -- own high-ready carry: hold, model-space elbow, bore
        local A2=mul(w.highLeft[sex],inverseRigid(ref.hold))
        p[#p+1]=1
        for i=1,12 do p[#p+1]=A2[i] end
        p[#p+1]=w.highElbow[1];p[#p+1]=w.highElbow[2];p[#p+1]=w.highElbow[3]
        p[#p+1]=w.highBore[1];p[#p+1]=w.highBore[2]
    else
        for _=1,18 do p[#p+1]=0 end
    end
    p[#p+1]=1 -- the reference hand locked on its hold while near it (GripIK.java LOCK_*)
    for _,i in ipairs({1,2,3,5,6,7,9,10,11}) do p[#p+1]=ref.hold[i] end
    -- low ready: how far back along the bore the support hand moves - to the start of the handguard
    -- (palm centre T.onPart onto it; GripIK.java LOW_*); 0 without a handguard part (PKM/PKP, integral)
    local back,bare,wrap=0,0,nil
    if kind=='bare' and not w.fixedLeft then
        -- FB2-E: low ready slides the knuckles back to T.onPart onto the grip span; the stations round the hold
        -- go to GripIK.java ('wrap') for the finger fit
        local k=ref.knuckle
        local ky=L[5]*k[1]+L[6]*k[2]+L[7]*k[3]+L[8]
        local lo,hiBox=unionBoxes(key,tree,function(_,path) return handguardPart(tree,path) end,true)
        local onPart=onPartFor(w,lo,hiBox)
        if lo then back=math.max(0,ky-(lo[2]+onPart));bare=1 end -- (integral handguards: FB2-D's hold, no sections)
        if st and lo then
            -- FB2-N: the hold slides along the whole span (knuckles from T.onPart to T.frontMargin of it, the wrist at most
            -- T.maxSpread ahead of the right one, the front preferred); the finger fit sees every station
            local R=ref.rotation
            local lowY=lo[2]+onPart
            local hiY=math.max(lowY,math.min(hiBox[2]-T.frontMargin,rw[2]+T.maxSpread+R[4]*k[1]+R[5]*k[2]+R[6]*k[3]))
            hold={kind=T.holdKinds.bare,slide={lowY-ky,hiY-ky,hiY-ky}}
            wrap=allFlat(st)
        elseif st then wrap=wrapFlat(st,ky,back) end
    elseif ownWrap then
        wrap=ownWrap
    end
    p[#p+1]=back
    p[#p+1]=1 -- low ready: the weapon to the hand when its support is out of reach (GripIK.java LOW_RAISE_*)
    p[#p+1]=0 -- patrol weight; the client selects a separate table while patrol is active
    p[#p+1]=bare -- bare handguard: a comfortable reach (GripIK.java ELBOW_MAX)
    p.wrap=wrap -- FB2-E: named, so the 64 numbers stay as they are
    p.hold=hold -- FB2-N: the grip surface the hold is chosen on with the arm (GripIK.java holdFit), its frame and slide
    p.mags=magFlat(mags) -- the installed magazines, the chosen hold's palm out of them (GripIK.java HOLD_K_MAG)
    -- FB8 item 7: an installed belt box is the support arm's obstacle (its Prop1 box; GripIK.java OB_*): the hold search
    -- and the elbow clearance keep the arm out of it (grip_targets.obstacle); the PK box hold is tuned over its box
    local magazine=tree.mod_magazine
    if not box and magazine and T.beltBoxes and T.beltBoxes[magazine] then
        local lo,hi=partBox(key,tree,'mod_magazine')
        if lo then p.obstacle={lo[1],lo[2],lo[3],hi[1],hi[2],hi[3]} end
    end
    return {kind=kind,refClass=T.refs[kind].class,params=p,supportPath=path,right=rw,left=left,butt=butt,hold=hold}
end

-- Never mutate the cached assembly params: Java copies only a new table identity.
-- Keep lowBack (61) and all other targets; patrol suppresses only lowLift.
function M.carryParams(value,patrol)
    if not patrol or value.kind=='handgun' then return value.params end
    if not value.patrolParams then
        local p={}
        for i,v in ipairs(value.params) do p[i]=v end
        p[63]=1
        p.wrap=value.params.wrap
        value.patrolParams=p
        p.hold=value.params.hold
        p.mags=value.params.mags
        p.obstacle=value.params.obstacle
    end
    return value.patrolParams
end

function M.isRocket(key)
    local row=G.weapons[key]
    return row~=nil and row.baseClass~=nil and row.baseClass:sub(1,#ROCKET_CLASS)==ROCKET_CLASS
end

local function signature(tree)
    local keys={}
    for path,tpl in pairs(tree) do keys[#keys+1]=path..'='..tpl end
    table.sort(keys)
    return table.concat(keys,';')
end

-- Cached per item, sex and assembly (re-read at most every 250 ms); nil for weapons
-- the transfer does not handle. The params table stays the same object while the
-- assembly does, so GripIK.java copies it only on a change.
function M.forGun(gun,female)
    local key=R.visualKey(gun)
    if not key then return nil end
    local sex=female and 'F' or 'M'
    local slots=M.cache[gun]
    if not slots then slots={};M.cache[gun]=slots end
    local c=slots[sex]
    local now=getTimestampMs()
    if c and now-c.time<250 then return c.value or nil end
    local hgClass=T.handgunKeys[R.key(gun) or key]
    local value,sig,tree=false,nil,nil
    if hgClass then
        local p=T.handguns[hgClass]
        value=c and c.value or (p and {kind='handgun',refClass=T.handgunClass,params=p[sex],handgunClass=hgClass}) or false
    elseif T.weapons[key] and not M.isRocket(key) then
        -- The shared read-only snapshot (R.view): its tree is the same table until a part changes, so is its signature.
        tree=(R.viewTree or R.tree)(gun)
        sig=c and c.tree==tree and c.sig or signature(tree)
        if c and c.sig==sig then value=c.value
        else value=M.compute(key,tree,female) or false end
    end
    slots[sex]={time=now,sig=sig,value=value,tree=tree}
    return value or nil
end

-- Gunworks grip routing: one handler per reference class (support kind).
function M.kindFor(gun)
    local key=R.visualKey(gun)
    if not key or not T.weapons[key] or M.isRocket(key) then return nil end
    local v=M.forGun(gun,false)
    return v and v.kind or nil
end

function M.register(Grip)
    for _,kind in ipairs(KINDS) do
        Grip.RegisterWeapon(nil,{id='TW:gripref:'..kind,animId=T.refs[kind].class,
            matches=function(gun) return M.kindFor(gun)==kind end})
    end
end

return M
