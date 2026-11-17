-- Tarkov geometry states of installed parts (tools/sight_geometry_states.py).
--
-- Folding sights (AutoFoldableSight) of the whole weapon fold while a straight
-- sight is installed anywhere on it: ProceduralWeaponAnimation.FindAimTransforms
-- looks for an installed sight (an iron sight only when held by a sight) with
-- an aim camera turned less than SCOPE_ROTATION_THRESHOLD about the bore; canted
-- sights do not fold them. A sight with mode geometry (HHS-1 magnifier, mode
-- switches, NV/reticle lenses, BOSS Xe indicators) shows the geometry of its
-- current mode (ScopePrefabCache.SetMode). Each state is its own render library
-- row of the same part type; the default prefab state keeps the ordinary row.
-- Its lens glass rows belong to that mesh (LensGlassData key States/<tpl>_<state>).
-- A launcher sight with a Tarkov range setting shows the pose of its stored range.
-- A belt box installed in its weapon (FB8 item 8) shows its belt as the weapon's
-- idle clip holds it, up into the feed tray, without the spent links
-- (tools/belt_feed.py); loose (world, inventory, hand) it keeps its own model.
-- Read only: nothing is written to the parts.
local C=require('TW/Core')
local D=C.data
local S=require('TW/Data/SightGeometryStates')
local F=require('TW/Data/BeltFeed')
local G={data=S,belt=F}

-- Quaternion.LookRotation(forward,up).eulerAngles.y (degrees) of an aim camera
-- whose PZ part-space forward/up are turned by the part placement `place`.
-- Unity's Y-X-Z decomposition puts the whole angle into y past its 0.999 gimbal
-- limit; aim cameras look along the weapon's Y axis, so y is the cant.
function G.eulerY(place,forward,up)
    local b=S.basis
    local function turn(v,i) return b[i]*(place[i][1]*v[1]+place[i][2]*v[2]+place[i][3]*v[3]) end
    local f={turn(forward,1),turn(forward,2),turn(forward,3)}
    local u={turn(up,1),turn(up,2),turn(up,3)}
    local n=math.sqrt(f[1]*f[1]+f[2]*f[2]+f[3]*f[3])
    if n<1e-12 then return nil end
    f={f[1]/n,f[2]/n,f[3]/n}
    local r={u[2]*f[3]-u[3]*f[2],u[3]*f[1]-u[1]*f[3],u[1]*f[2]-u[2]*f[1]}
    n=math.sqrt(r[1]*r[1]+r[2]*r[2]+r[3]*r[3])
    if n<1e-12 then return nil end
    r={r[1]/n,r[2]/n,r[3]/n}
    local upX=f[2]*r[3]-f[3]*r[2]
    local y
    if f[2]<0.999 then
        if f[2]>-0.999 then y=math.atan2(f[1],f[3]) else y=math.atan2(upX,r[1]) end
    else
        y=math.atan2(-upX,r[1])
    end
    return math.deg(y)%360
end

-- The aim cameras the breadth-first search reaches below the slot of `path`:
-- the part's own and those of parts installed in it, not through a mod_scope.
local function straightBelow(key,tree,path)
    local tpl=tree[path]
    local bones=S.bones[tpl]
    if bones then
        local place=C.placement(key,tree,path)
        if place then
            for _,bone in ipairs(bones) do
                local y=G.eulerY(place,bone.f,bone.u)
                if y and math.abs(180-y)<S.threshold and math.abs(180-y)<S.aimLimit then return true end
            end
        end
    end
    local blocked=S.blocked[tpl]
    for _,slot in ipairs(D.items[tpl] and D.items[tpl].slots or {}) do
        local child=path..'/'..slot.name
        if tree[child] and not (blocked and blocked[slot.name]) and straightBelow(key,tree,child) then return true end
    end
    return false
end

-- True: the folding sights of this assembly are folded (Tarkov `flag`).
function G.folded(key,tree)
    if not key or not D.weapons[key] then return false end
    local root=C.root(key)
    for path,tpl in pairs(tree) do
        local item=D.items[tpl]
        if item and S.sight[item.category] then
            local parent=C.parent(path)
            local holder=D.items[parent=='' and root or tree[parent]]
            if item.category~=S.iron or (holder and S.sight[holder.category]) then
                if straightBelow(key,tree,path) then return true end
            end
        end
    end
    return false
end

-- The stored mode of a sight channel, as TW/SightState.storage reads it, without
-- creating the state: TWOptic.channels[scope].mode, 1-based in the channel modes.
local function storedMode(part,scope,count)
    local md=part and part:getModData()
    local optic=md and type(md.TWOptic)=='table' and md.TWOptic or nil
    local channels=optic and type(optic.channels)=='table' and optic.channels or nil
    local state=channels and channels[tostring(scope)]
    local mode
    if type(state)=='table' then mode=state.mode elseif scope==0 and optic then mode=optic.mode end
    if type(mode)~='number' or mode~=math.floor(mode) or mode<1 or mode>math.max(1,count) then mode=1 end
    return mode
end

-- The mode geometry of the sight at path: per mode cache, the Tarkov mode index
-- of its scope (the first scope with as many modes as the cache) or mode 0.
function G.modeState(tpl,path,part)
    local row=S.modes[tpl]
    if not row then return nil end
    local channels=C.sightChannels(tpl,path)
    local names={}
    for _,cache in ipairs(row.caches) do
        local index=0
        for _,channel in ipairs(cache.scope and channels or {}) do
            if channel.channel==cache.scope then
                local mode=channel.modes[storedMode(part,cache.scope,#channel.modes)]
                index=mode and mode.index or 0
                break
            end
        end
        names[#names+1]=cache.states[index] or cache.states[0]
    end
    return table.concat(names,'_')
end

-- The stored range of a launcher sight (TW/SightState storage key of its channel:
-- TWOptic.channels[storage].zeroDistance), without creating it; the default otherwise.
local function storedRange(part,row)
    local md=part and part:getModData()
    local optic=md and type(md.TWOptic)=='table' and md.TWOptic or nil
    local channels=optic and type(optic.channels)=='table' and optic.channels or nil
    local state=channels and channels[row.storage]
    local distance=type(state)=='table' and state.zeroDistance or nil
    return row.states[distance] and distance or row.default
end

-- The range pose of a launcher sight (GP-25 quadrant by SightingRange, M2A1
-- WeaponScopeAxis by AngleByRange): the state of its stored distance.
function G.rangeState(tpl,part)
    local row=S.ranges and S.ranges[tpl]
    return row and row.states[storedRange(part,row)] or nil
end

-- Sets e.state on every render entry whose current state has its own library
-- row (nil: the ordinary model). Entries of an installed part carry e.part.
-- The state annotate sets on one entry, without setting it (TW_ZoomRings reads it for a shared snapshot entry).
-- folded: G.folded(key,tree) when already known; returned too, so the next entry reuses it.
function G.entryState(key,tree,e,folded)
    local names={}
    if S.fold[e.tpl] then
        if folded==nil then folded=G.folded(key,tree) end
        names[#names+1]=folded and 'folded' or 'unfolded'
    end
    if S.modes[e.tpl] then names[#names+1]=G.modeState(e.tpl,e.path,e.part) end
    if S.ranges and S.ranges[e.tpl] then names[#names+1]=G.rangeState(e.tpl,e.part) end
    local feed=F.feed[e.tpl]
    if feed and e.path==feed.slot then names[#names+1]=feed.state end
    local state=#names>0 and table.concat(names,'_') or nil
    local rows=state and D.libraryStates[e.fullType]
    return rows and rows[state] and state or nil,folded
end

function G.annotate(key,tree,entries)
    local folded
    for _,e in ipairs(entries) do e.state,folded=G.entryState(key,tree,e,folded) end
    return entries
end

function G.libraryIndex(e)
    local rows=e.state and D.libraryStates[e.fullType]
    return rows and rows[e.state] or D.libraryIndex[e.fullType]
end

-- Part of TWRuntime.fingerprint: a sight mode or range change re-renders the weapon. withoutModes (FB4 item 9):
-- the signature of everything but the mode states (equal: TWRuntime.swapStates replaces only the changed meshes).
-- Whether a part type has any state signature (TWRuntime's snapshot reads only those parts again).
function G.hasStates(fullType)
    local t=D.types[fullType]
    return t~=nil and (S.modes[t.tpl]~=nil or (S.ranges~=nil and S.ranges[t.tpl]~=nil))
end

function G.partSignature(part,withoutModes)
    local t=D.types[part:getFullType()]
    local row=t and S.modes[t.tpl]
    local range=t and S.ranges and S.ranges[t.tpl]
    if withoutModes then row=nil end
    if not row and not range then return '' end
    local values={}
    for _,cache in ipairs(row and row.caches or {}) do values[#values+1]=cache.scope and storedMode(part,cache.scope,cache.count) or 0 end
    if range then values[#values+1]='r'..storedRange(part,range) end
    return ':g'..table.concat(values,',')
end

-- A range-posed sight moves its window as well as its camera and model. Return
-- a per-view copy in the same part datum; never modify the shared source profile.
function G.lens(view)
    local lens=view and view.modeProfile and view.modeProfile.lens
    local move=view and view.rangeMove
    if not lens or not move then return lens end
    local function copy(row)
        local out={};for k,v in pairs(row) do out[k]=v end;return out
    end
    local zero=C.transformPoint(move,{0,0,0})
    local function direction(v)
        local p=C.transformPoint(move,v)
        return {p[1]-zero[1],p[2]-zero[2],p[3]-zero[3]}
    end
    local result=copy(lens)
    for _,name in ipairs({'ocular','objective'}) do
        local source=lens[name]
        if source then
            local surface=copy(source)
            if source.center then surface.center=C.transformPoint(move,source.center) end
            if source.normal then surface.normal=direction(source.normal) end
            if source.boundary then
                surface.boundary={}
                for _,p in ipairs(source.boundary) do surface.boundary[#surface.boundary+1]=C.transformPoint(move,p) end
            end
            if source.planeAxes then
                surface.planeAxes={}
                for _,v in ipairs(source.planeAxes) do surface.planeAxes[#surface.planeAxes+1]=direction(v) end
            end
            result[name]=surface
        end
    end
    return result
end

return G
