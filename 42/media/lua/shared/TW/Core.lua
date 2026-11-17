-- Pure assembly rules for every supported weapon. No PZ objects, UI, global
-- script edits or accumulated stats. A tree maps slot paths to vanilla templates.
local D = require('TW/Data')
local Range = require('TW/RangeOptions')
local C = {data=D}

local function copy(tree)
    local result = {}
    for path, tpl in pairs(tree or {}) do result[path]=tpl end
    return result
end
C.copy = copy

local function has(array, value)
    for _,entry in ipairs(array or {}) do if entry==value then return true end end
    return false
end

function C.weapon(key) return D.weapons[key] end
-- A detachable magazine (a Gunworks Clip): changed by reloading, never by the
-- assembly actions. Built-in magazines (a revolver cylinder, a shotgun tube cap,
-- the SKS or Mosin internal box) are ordinary parts that give the capacity.
function C.isClip(tpl)
    local item=tpl and D.items[tpl]
    return item~=nil and item.clip==true
end
-- Item type of a detachable magazine for this weapon: a magazine shared with another
-- calibre (AR-15 STANAG for .300 BLK, AKM for .366 TKM) has one type per calibre.
function C.clipType(key,tpl)
    local item=D.items[tpl]
    local calibre=D.weapons[key] and D.weapons[key].ammoKey
    return item.variants and item.variants[calibre] or item.pz
end
function C.root(key) return D.weapons[key].tpl end
function C.keyForType(fullType) return D.byType[fullType] end

function C.parent(path)
    return path:match('^(.*)/[^/]+$') or ''
end

function C.slot(path)
    return path:match('([^/]+)$')
end

function C.depth(path)
    local n=1
    for _ in path:gmatch('/') do n=n+1 end
    return n
end

function C.slotDefinition(key, tree, path)
    local parent=C.parent(path)
    local tpl=parent=='' and C.root(key) or tree[parent]
    local definition=D.items[tpl]
    if not definition then return nil end
    for _,slot in ipairs(definition.slots) do
        if slot.name==C.slot(path) then return slot end
    end
end

function C.count(tree, tpl)
    local n=0
    for _,value in pairs(tree) do if value==tpl then n=n+1 end end
    return n
end

function C.validate(key, tree)
    if not D.weapons[key] then return false,'unknownWeapon' end
    local installed={[C.root(key)]=true}
    for path,tpl in pairs(tree) do
        local item=D.items[tpl]
        if not item or item.isRoot then return false,'unknownPart',path end
        local slot=C.slotDefinition(key,tree,path)
        if not slot then return false,'missingParent',path end
        if not has(slot.allowed,tpl) then return false,'incompatible',path end
        if not D.items[C.parent(path)=='' and C.root(key) or tree[C.parent(path)]].frames[slot.name] then return false,'unsupportedRoute',path end
        if C.count(tree,tpl)>(item.copies or 1) then return false,'maxCopies',path end
        installed[tpl]=true
    end
    for tpl in pairs(installed) do
        for _,conflict in ipairs(D.items[tpl].ConflictingItems or {}) do
            if installed[conflict] then
                -- Vanilla magazine/chassis conflicts (M700): the magazine is changed by reloading.
                local magazine=C.isClip(tpl) or C.isClip(conflict)
                return false,magazine and 'magazineConflict' or 'conflict',tpl..':'..conflict
            end
        end
    end
    return true
end

-- The third value names the offending detail (conflict: 'tplA:tplB').
function C.install(key,tree,path,tpl)
    if tree[path] then return nil,'occupied' end
    local nextTree=copy(tree)
    nextTree[path]=tpl
    local valid,reason,detail=C.validate(key,nextTree)
    if not valid then return nil,reason,detail end
    return nextTree
end

-- The installed part a candidate conflicts with, from a validation detail.
function C.conflictPart(tpl,detail)
    if type(detail)~='string' then return nil end
    local a,b=detail:match('^(%w+):(%w+)$')
    if a==tpl then return b end
    if b==tpl then return a end
    return b
end

function C.children(tree,path)
    local result={}
    for child in pairs(tree) do
        if child:sub(1,#path+1)==path..'/' then result[#result+1]=child end
    end
    -- Deepest first: a child is always removed before its parent.
    table.sort(result,function(a,b)
        local da,db=C.depth(a),C.depth(b)
        if da~=db then return da>db end
        return a<b
    end)
    return result
end

function C.remove(key,tree,path)
    if not tree[path] then return nil,'empty' end
    if #C.children(tree,path)>0 then return nil,'hasChildren' end
    local nextTree=copy(tree)
    nextTree[path]=nil
    return nextTree
end

-- Removal order for a whole branch (children first, then the part itself).
function C.removeBranch(key,tree,path)
    if not tree[path] then return nil,'empty' end
    local order=C.children(tree,path)
    order[#order+1]=path
    local nextTree=copy(tree)
    for _,p in ipairs(order) do nextTree[p]=nil end
    return nextTree,order
end

function C.replace(key,tree,path,tpl)
    if not tree[path] then return nil,'empty' end
    if tree[path]==tpl then return nil,'alreadyInstalled' end
    local removed,reason=C.remove(key,tree,path)
    if not removed then return nil,reason end
    return C.install(key,removed,path,tpl)
end

-- Draft editing: like C.replace, but the children of the old part stay where
-- the new part offers the same slot and accepts them; the others are dropped
-- (fourth value). Swapping a handguard keeps its rails and devices.
function C.swap(key,tree,path,tpl)
    if not tree[path] then return C.install(key,tree,path,tpl) end
    if tree[path]==tpl then return nil,'alreadyInstalled' end
    local children=C.children(tree,path)
    local nextTree=copy(tree)
    for _,child in ipairs(children) do nextTree[child]=nil end
    nextTree[path]=tpl
    local valid,reason,detail=C.validate(key,nextTree)
    if not valid then return nil,reason,detail end
    -- Parents before children; a child returns only if its whole route is valid.
    table.sort(children,function(a,b)
        local da,db=C.depth(a),C.depth(b)
        if da~=db then return da<db end
        return a<b
    end)
    local dropped={}
    for _,child in ipairs(children) do
        -- C.install refuses a child whose parent is gone or lacks that slot.
        local attempt=C.install(key,nextTree,child,tree[child])
        if attempt then nextTree=attempt else dropped[#dropped+1]=child end
    end
    return nextTree,nil,nil,dropped
end

local function slotsOf(tree,tpl,parent,list,onlyEmpty)
    for _,slot in ipairs(D.items[tpl].slots) do
        if #slot.allowed>0 and D.items[tpl].frames and D.items[tpl].frames[slot.name] then
            local path=parent=='' and slot.name or parent..'/'..slot.name
            if not onlyEmpty or not tree[path] then
                list[#list+1]={path=path,definition=slot,tpl=tree[path],parent=parent}
            end
        end
    end
end

-- Include occupied slots for the assembly window; each child belongs to the
-- currently installed parent, never a phantom parent from another preset.
function C.allSlots(key,tree)
    local slots={}
    slotsOf(tree,C.root(key),'',slots,false)
    for path,tpl in pairs(tree) do slotsOf(tree,tpl,path,slots,false) end
    table.sort(slots,function(a,b) return a.path<b.path end)
    return slots
end

function C.availableSlots(key,tree)
    local slots={}
    slotsOf(tree,C.root(key),'',slots,true)
    for path,tpl in pairs(tree) do slotsOf(tree,tpl,path,slots,true) end
    table.sort(slots,function(a,b) return a.path<b.path end)
    return slots
end

-- Every slot of the assembly in tree order: root slots in vanilla order, each
-- installed part followed by its own slots. depth 1 = slot on the receiver.
function C.slotTree(key,tree)
    local result={}
    local function walk(tpl,parent,depth)
        local item=D.items[tpl]
        for _,slot in ipairs(item.slots) do
            if #slot.allowed>0 and item.frames and item.frames[slot.name] then
                local path=parent=='' and slot.name or parent..'/'..slot.name
                result[#result+1]={path=path,definition=slot,tpl=tree[path],parent=parent,depth=depth}
                if tree[path] then walk(tree[path],path,depth+1) end
            end
        end
    end
    walk(C.root(key),'',1)
    return result
end

-- Render entries for a tree without items (drafts, previews): native copy
-- types are assigned in a stable order, as the default preset does.
-- key: the weapon, for the item type of a magazine shared with another calibre.
function C.entries(tree,key)
    local paths={}
    for path in pairs(tree) do paths[#paths+1]=path end
    table.sort(paths,function(a,b)
        local da,db=C.depth(a),C.depth(b)
        if da~=db then return da<db end
        return a<b
    end)
    local used,result={},{}
    for _,path in ipairs(paths) do
        local tpl=tree[path]
        local copyIndex=used[tpl] or 0
        used[tpl]=copyIndex+1
        local item=D.items[tpl]
        local full=copyIndex==0 and item.pz or item.pz..'_'..copyIndex
        if key and item.clip then full=C.clipType(key,tpl) end
        result[#result+1]={path=path,tpl=tpl,copy=copyIndex,fullType=full}
    end
    table.sort(result,function(a,b) return a.path<b.path end)
    return result
end

function C.requiredMissing(key,tree)
    local missing={}
    local function inspect(tpl,parent)
        for _,slot in ipairs(D.items[tpl].slots) do
            local path=parent=='' and slot.name or parent..'/'..slot.name
            if slot.required and not tree[path] then missing[#missing+1]=path end
        end
    end
    inspect(C.root(key),'')
    for path,tpl in pairs(tree) do inspect(tpl,path) end
    table.sort(missing)
    return missing
end

-- 3x4 affine matrices (rows). Frames are local to their parent item.
local function mul(a,b)
    local r={}
    for i=1,3 do
        local row={}
        for j=1,4 do
            local v=a[i][1]*b[1][j]+a[i][2]*b[2][j]+a[i][3]*b[3][j]
            if j==4 then v=v+a[i][4] end
            row[j]=v
        end
        r[i]=row
    end
    return r
end
C.mul=mul
C.identity={{1,0,0,0},{0,1,0,0},{0,0,1,0}}

function C.transformPoint(m,p)
    return {m[1][1]*p[1]+m[1][2]*p[2]+m[1][3]*p[3]+m[1][4],
            m[2][1]*p[1]+m[2][2]*p[2]+m[2][3]*p[3]+m[2][4],
            m[3][1]*p[1]+m[3][2]*p[2]+m[3][3]*p[3]+m[3][4]}
end

-- Placement of the part installed at path, in the root model space.
function C.placement(key,tree,path)
    local m=C.identity
    local parent=C.root(key)
    local current=''
    for slot in path:gmatch('[^/]+') do
        current=current=='' and slot or current..'/'..slot
        local frame=D.items[parent] and D.items[parent].frames and D.items[parent].frames[slot]
        if not frame then return nil end
        m=mul(m,frame)
        parent=tree[current]
        if not parent and current~=path then return nil end
    end
    return m
end

-- B42 hangs every hand-held model from the prop bone; the handgun animations
-- hold it with model +Z down, and vanilla handgun models are authored that way
-- (sight on -Z, light and laser on +Z). Tarkov pistols keep +Z up in the rules,
-- slot frames and bounds; their root model is exported turned 180 degrees
-- about the bore (x,z -> -x,-z) and the native attachments follow it.
function C.renderRoll(key)
    return D.weapons[key]~=nil and D.weapons[key].rolled==true
end
-- A root-space point / placement in the space of the exported root model.
function C.toRender(key,p)
    if p and C.renderRoll(key) then return {-p[1],p[2],-p[3]} end
    return p
end
function C.renderMatrix(key,m)
    if not (m and C.renderRoll(key)) then return m end
    return {{-m[1][1],-m[1][2],-m[1][3],-m[1][4]},{m[2][1],m[2][2],m[2][3],m[2][4]},{-m[3][1],-m[3][2],-m[3][3],-m[3][4]}}
end

-- Offset, JOML rotateXYZ angles (degrees) and uniform scale of an affine
-- matrix, as ModelInstanceRenderData.makeAttachmentTransform composes them:
-- translation(offset) * Rx*Ry*Rz * scale.
function C.attachmentValues(m)
    local r={}
    for i=1,3 do r[i]={m[i][1],m[i][2],m[i][3]} end
    -- Normalise columns: Unity transforms can carry a uniform scale.
    local scale=0
    for j=1,3 do
        local n=math.sqrt(r[1][j]^2+r[2][j]^2+r[3][j]^2)
        scale=scale+n/3
        if n>1e-9 then for i=1,3 do r[i][j]=r[i][j]/n end end
    end
    local sy=math.max(-1,math.min(1,r[1][3]))
    local x,y,z
    y=math.asin(sy)
    if math.abs(sy)<0.999999 then
        x=math.atan2(-r[2][3],r[3][3])
        z=math.atan2(-r[1][2],r[1][1])
    else
        x=math.atan2(r[3][2],r[2][2])
        z=0
    end
    return {m[1][4],m[2][4],m[3][4]},{math.deg(x),math.deg(y),math.deg(z)},scale
end

function C.bounds(key,tree)
    local b=D.items[C.root(key)].bounds
    local lo,hi={b.min[1],b.min[2],b.min[3]},{b.max[1],b.max[2],b.max[3]}
    for path,tpl in pairs(tree) do
        local part=D.items[tpl].bounds
        local matrix=C.placement(key,tree,path)
        if part and matrix then
            for corner=0,7 do
                local p={}
                for a=1,3 do p[a]=math.floor(corner/2^(a-1))%2==0 and part.min[a] or part.max[a] end
                local v=C.transformPoint(matrix,p)
                for a=1,3 do lo[a]=math.min(lo[a],v[a]); hi[a]=math.max(hi[a],v[a]) end
            end
        end
    end
    return {min=lo,max=hi}
end

-- Front-most muzzle marker (barrel fireport, muzzle device flash point).
function C.muzzle(key,tree)
    local best=D.items[C.root(key)].muzzle
    for path,tpl in pairs(tree) do
        local local_=D.items[tpl].muzzle
        if local_ then
            local matrix=C.placement(key,tree,path)
            local p=matrix and C.transformPoint(matrix,local_)
            if p and (not best or p[2]>best[2]) then best=p end
        end
    end
    return best or D.weapons[key].muzzle
end

local OPTIC={Collimator=true,CompactCollimator=true,OpticScope=true,AssaultScope=true,SpecialScope=true}
C.optic=OPTIC

-- Optics in a stable order; the default active sight is the highest magnification.
local sightProfiles=require('TW/Data/ViewpointOptics')
local sightCameras=require('TW/Data/SightChannels')
local sightReported={}

local function unavailableSight(tpl,index,reason)
    local id=tpl..':'..tostring(index)
    if sightReported[id] then return end
    sightReported[id]=true
    print('[TW] Sight channel unavailable '..id..': '..tostring(reason))
end

-- The database scope index and prefab cache index are different namespaces.
-- Never turn an unknown ownership binding into a working channel by guessing.
function C.sightChannels(tpl,path)
    local result={}
    local item=sightProfiles.item(tpl)
    if item then
        local profiles={}
        for _,mode in ipairs(item.modes) do profiles[mode.id]=mode end
        for _,scope in ipairs(item.scopes) do
            local modes,reason={},nil
            for _,binding in ipairs(scope.modes) do
                local profile=profiles[binding.profile or binding.default]
                if binding.binding=='unknown' or not profile then
                    reason=binding.reason or 'no bound mode profile'
                    break
                end
                modes[#modes+1]={index=binding.index,profile=profile,nominalZoom=binding.nominalZoom,binding=binding}
            end
            if reason or #modes==0 then unavailableSight(tpl,scope.index,reason or 'no modes')
            else
                local point
                -- D2 binds the database scope, preserving older mode profiles.
                local first=modes[1]
                local node=scope.aimPointNode or first.binding.aimPointNode or first.profile.aimPointNode
                local frame=scope.scopeFrame or first.binding.scopeFrame
                for _,p in ipairs(item.aimPoints) do
                    if p.role=='camera' and p.node==node then point=p;break end
                end
                result[#result+1]={path=path,tpl=tpl,channel=scope.index,scope=scope,profile=item,modes=modes,
                    kind=first.profile.kind,point=point,frame=frame or point and point.frame or first.profile.scopeFrame,
                    hierarchyOrder=point and point.hierarchyOrder or scope.index,sight=D.items[tpl].sight}
            end
        end
    else
        local owner=sightCameras.item(tpl)
        for i,point in ipairs(owner and owner.points or {}) do
            if point.activeInPrefab then
                result[#result+1]={path=path,tpl=tpl,channel=i-1,kind='iron',point=point,frame=point.frame,
                    hierarchyOrder=point.hierarchyOrder,customAimPlane=owner.customAimPlane,modes={},sight={zoomMin=1,zoomMax=1}}
            end
        end
    end
    return result
end

-- Preserve source scopes/points and original slot order. R3 does not establish
-- a global runtime order across owners: use assembly preorder, not zoom or names.
function C.sights(tree,key)
    local result,visited={},{}
    local function visit(tpl,path)
        visited[path]=true
        for _,s in ipairs(C.sightChannels(tpl,path)) do result[#result+1]=s end
        for _,slot in ipairs(D.items[tpl] and D.items[tpl].slots or {}) do
            local child=path=='' and slot.name or path..'/'..slot.name
            if tree[child] then visit(tree[child],child) end
        end
    end
    if key and D.weapons[key] then visit(C.root(key),'') end
    local paths={}
    for path in pairs(tree) do if not visited[path] then paths[#paths+1]=path end end
    table.sort(paths)
    for _,path in ipairs(paths) do if not visited[path] then visit(tree[path],path) end end
    return result
end

-- Same native sight-band mapping as generate_scripts.sight_values, evaluated
-- at the selected channel's current magnification rather than its largest zoom.
function C.sightBand(zoom)
    local minimum=math.floor(math.min(15,math.max(2,2*zoom))*10+.5)/10
    local maximum=math.floor(math.min(24,10+3*(zoom-1))*10+.5)/10
    return minimum,math.max(maximum,minimum+4)
end

function C.stats(key,tree)
    local base=D.items[C.root(key)]
    local weight,ergo,recoil,velocity,accuracy,loudness=base.Weight,base.Ergonomics,0,0,0,0
    -- A break action's barrels are the magazine; removing a cylinder or box
    -- still leaves zero rounds, so only explicit fixed-capacity kinds opt in.
    local capacity=D.weapons[key].nativeCapacity or 0
    local silenced=false
    for _,tpl in pairs(tree) do
        local part=D.items[tpl]
        weight=weight+(part.Weight or 0)
        ergo=ergo+(part.Ergonomics or 0)
        recoil=recoil+(part.Recoil or 0)
        velocity=velocity+(part.Velocity or 0)
        accuracy=accuracy+(part.Accuracy or 0)
        loudness=loudness+(part.Loudness or 0)
        if part.clip or part.internalMagazine then capacity=part.capacity end
        if part.category=='Silencer' then silenced=true end
    end
    local missing=C.requiredMissing(key,tree)
    local recoilFactor=math.max(0.05,1+recoil/100)
    return {weight=weight,ergonomics=math.max(0,math.min(100,ergo)),recoilUp=base.RecoilForceUp*recoilFactor,recoilBack=base.RecoilForceBack*recoilFactor,
        velocityMultiplier=math.max(0.05,1+velocity/100),velocity=velocity,accuracyModifier=accuracy,loudness=loudness,capacity=capacity,silenced=silenced,
        missing=missing,ready=#missing==0}
end

function C.presetTree(key,name)
    local weapon=D.weapons[key]
    return copy(weapon.presets[name or weapon.defaultPreset])
end

-- Stats of the vanilla default preset: the PZ base values belong to it.
C.referenceCache={}
function C.reference(key)
    if not C.referenceCache[key] then C.referenceCache[key]=C.stats(key,C.presetTree(key)) end
    return C.referenceCache[key]
end

-- A suppressor in PZ terms. B42 uses two weapon fields for a shot: SoundRadius
-- (radius of the world sound zombies hear, x FirearmNoiseMultiplier, x0.5
-- indoors) and SoundVolume (its volume); the audible shot is SwingSound.
-- Strength follows the Tarkov Loudness of the assembly (suppressors -12..-35):
-- -25 gives the Brita B42 port factors (radius x0.30, volume x0.45), weaker
-- and stronger suppressors scale around them, with lower bounds.
function C.suppression(loudness)
    local strength=math.max(0.6,math.min(1.4,-(loudness or -25)/25))
    return math.max(0.15,1-0.70*strength),math.max(0.25,1-0.55*strength)
end

-- Native PZ values an assembly produces, recomputed from the base (never
-- accumulated): ergonomics -> aiming time, recoil -> recoil delay, velocity ->
-- range, accuracy -> hit chance, suppressor -> sound radius, volume and the
-- silenced shot, magazine -> ammo.
function C.pzStats(key,tree,s)
    s=s or C.stats(key,tree)
    local weapon=D.weapons[key]
    local base=weapon.base
    local ref=C.reference(key)
    local radius,volume=1,1
    if s.silenced then radius,volume=C.suppression(s.loudness) end
    -- W11 range: the sandbox FirearmRange multiplies the PZ class reach (TW/RangeOptions). At its default 2
    -- this is the doubled reach of W10 and the assembly's velocity bonus stays on top of it.
    return {aimingTime=math.floor(base.AimingTime*(100+ref.ergonomics)/(100+s.ergonomics)+0.5),
        recoilDelay=math.max(1,math.floor(base.RecoilDelay*s.recoilUp/ref.recoilUp+0.5)),
        maxRange=Range.modReach(base.MaxRangeBase or base.MaxRange,s.velocity-ref.velocity),
        hitChance=base.HitChance+math.max(-8,math.min(8,math.floor((s.accuracyModifier-ref.accuracyModifier)/4+0.5))),
        soundRadius=math.max(1,math.floor(base.SoundRadius*radius+0.5)),
        soundVolume=math.max(1,math.floor((base.SoundVolume or 0)*volume+0.5)),
        swingSound=weapon.sounds and (s.silenced and weapon.sounds.silenced or weapon.sounds.shoot) or nil,
        silenced=s.silenced,loudness=s.loudness,
        maxAmmo=s.capacity}
end

function C.equalTrees(a,b)
    for path,tpl in pairs(a) do if b[path]~=tpl then return false end end
    for path,tpl in pairs(b) do if a[path]~=tpl then return false end end
    return true
end

-- Steps that turn the installed assembly into a draft with loose parts.
-- owned: tpl -> loose count. The magazine is not part of the plan (Gunworks
-- reload); a magazine that conflicts with the draft is ejected first. Every
-- intermediate tree is validated, so queued actions cannot fail on rules.
function C.plan(key,current,draft,owned)
    local function kept(path)
        local p=path
        while p~='' do
            if current[p]==nil or current[p]~=draft[p] then return false end
            p=C.parent(p)
        end
        return true
    end
    local result={steps={},missing={},changes=0}
    local removeRoots,installs,available,need={},{},{},{}
    for tpl,count in pairs(owned or {}) do available[tpl]=count end
    for path,tpl in pairs(current) do
        if not C.isClip(tpl) and not kept(path) then
            available[tpl]=(available[tpl] or 0)+1
            local parent=C.parent(path)
            if parent=='' or kept(parent) then removeRoots[#removeRoots+1]=path end
        end
    end
    for path,tpl in pairs(draft) do
        if not C.isClip(tpl) and not kept(path) then
            installs[#installs+1]={path=path,tpl=tpl}
            need[tpl]=(need[tpl] or 0)+1
        end
    end
    table.sort(removeRoots)
    table.sort(installs,function(a,b)
        local da,db=C.depth(a.path),C.depth(b.path)
        if da~=db then return da<db end
        return a.path<b.path
    end)
    for tpl,count in pairs(need) do
        local have=available[tpl] or 0
        if have<count then result.missing[#result.missing+1]={tpl=tpl,count=count-have} end
    end
    table.sort(result.missing,function(a,b) return a.tpl<b.tpl end)
    result.need=need
    local draftClip=C.isClip(draft.mod_magazine) and draft.mod_magazine or nil
    local currentClip=C.isClip(current.mod_magazine) and current.mod_magazine or nil
    if draftClip~=currentClip then result.magazine=draftClip or false end
    local function build(useReplace)
        local steps,replaced={},{}
        if useReplace then
            for _,path in ipairs(removeRoots) do
                if draft[path] and #C.children(current,path)==0 then replaced[path]=true end
            end
        end
        for _,path in ipairs(removeRoots) do
            if not replaced[path] then
                steps[#steps+1]={mode=#C.children(current,path)>0 and 'removeBranch' or 'remove',path=path,tpl=current[path]}
            end
        end
        for _,i in ipairs(installs) do
            steps[#steps+1]={mode=replaced[i.path] and 'replace' or 'install',path=i.path,tpl=i.tpl}
        end
        return steps
    end
    local function simulate(steps,withMagazine)
        local tree=copy(current)
        if not withMagazine and C.isClip(tree.mod_magazine) then tree.mod_magazine=nil end
        for index,step in ipairs(steps) do
            local nextTree,reason,detail
            if step.mode=='remove' then nextTree,reason=C.remove(key,tree,step.path)
            elseif step.mode=='removeBranch' then nextTree,reason=C.removeBranch(key,tree,step.path)
            elseif step.mode=='replace' then nextTree,reason,detail=C.replace(key,tree,step.path,step.tpl)
            else nextTree,reason,detail=C.install(key,tree,step.path,step.tpl) end
            if not nextTree then return nil,reason,index,detail end
            tree=nextTree
        end
        return tree
    end
    local last
    for _,variant in ipairs({{true,true},{false,true},{true,false},{false,false}}) do
        if variant[2] or C.isClip(current.mod_magazine) then
            local steps=build(variant[1])
            local final,reason,index,detail=simulate(steps,variant[2])
            if final then
                result.steps=steps
                result.eject=not variant[2]
                result.changes=#steps
                result.final=final
                return result
            end
            last={reason=reason,step=steps[index],detail=detail}
        end
    end
    result.error=last and last.reason or 'unavailable'
    result.errorStep=last and last.step
    result.errorDetail=last and last.detail
    return result
end

return C
