-- Native weapon-part rendering without a combinatorial list of placements.
--
-- Every weapon root has RENDER_POOL copies of its model script. A distinct
-- visible assembly owns one copy: its attachments t_<tpl>_<copy> are placed
-- from the local vanilla mount frames of the current tree, and the instance's
-- ModelWeaponPart list maps each installed part type to that attachment.
-- Copy 00 is never assigned and carries only the bare receiver. A part in a
-- Tarkov geometry state (folded sight, sight mode) uses that state's row of
-- the library (TW/GeometryState).
local C=require('TW/Core')
local GeometryState=require('TW/GeometryState')
local D=C.data
local Render={pool={},owner=setmetatable({},{__mode='k'}),failed={},clock=0,evicted=0,configured=0}

function Render.modelName(key,index)
    return string.format('EscapeFromZomboidFirearms.M_%s_P%02d',D.weapons[key].tpl,index)
end

function Render.anchorName(tpl,copy)
    return 't_'..tpl..'_'..tostring(copy or 0)
end

-- Entries: {path=,tpl=,copy=,fullType=[,state=]}. The fingerprint names every
-- model and its exact slot route; equal assemblies share one pool copy.
function Render.fingerprint(key,entries)
    local values={}
    for _,e in ipairs(entries) do values[#values+1]=e.fullType..'@'..e.path..(e.state and '#'..e.state or '') end
    table.sort(values)
    return key..'#'..table.concat(values,'|')
end

local function library()
    if not Render.library then
        local item=instanceItem('EscapeFromZomboidFirearms.RenderLibrary')
        Render.library=item and item:getModelWeaponPart()
        assert(Render.library and Render.library:size()==#D.library,'[TW] Native render library unavailable')
    end
    return Render.library
end

local function attachment(script,id)
    return script:getAttachmentById(id) or script:addAttachment(ModelAttachment.new(id))
end

-- Pure placement data, also used by tests and the assembly preview. Values are
-- in the space of the exported root model (pistols turned about the bore).
function Render.layout(key,tree,entries)
    local result={}
    for _,e in ipairs(entries) do
        local matrix=C.renderMatrix(key,C.placement(key,tree,e.path))
        if matrix then
            local offset,rotate,scale=C.attachmentValues(matrix)
            result[#result+1]={id=Render.anchorName(e.tpl,e.copy),offset=offset,rotate=rotate,scale=scale,fullType=e.fullType,tpl=e.tpl}
        end
    end
    return result,C.toRender(key,C.muzzle(key,tree))
end

-- Ground rest pose of an assembly (bug wave 324, 24.10.2026, the user's item 4: "models have no normal look on the
-- ground ... others without half of their parts"). A weapon on the ground is drawn through the HandWeapon branch of
-- ItemModelRenderer: its render copy turned on its side (rotateXYZ(0,pi,pi/2): (x,y,z) -> (y,x,-z)), then the inverse
-- of the copy's `attachment world`, Y up from the item's floor point. The copy's `world` lays the whole assembly
-- (C.bounds: receiver and every installed part) on its side with the physical right side up, as vanilla ground
-- firearms lie, its lowest point REST_LIFT above the floor and its footprint centred on the item's point. The same
-- values as tools/generate_scripts.py rest_world(weapon=True) (the generated copies carry the bare receiver's).
local REST_LIFT=0.001
function Render.restWorld(key,bounds)
    local lo,hi=bounds.min,bounds.max
    local rolled=C.renderRoll(key)
    if rolled then lo,hi={-hi[1],lo[2],-hi[3]},{-lo[1],hi[2],-lo[3]} end
    -- World coordinates of a model point: rifles (x,y,z) -> (y,-x,z) (attachment rotate 180 0 180), pistols in their
    -- turned space (x,y,z) -> (y,x,-z) (rotate 0 0 0); both put the logical -X (the physical right) up.
    local cy,cz=(lo[2]+hi[2])/2,(lo[3]+hi[3])/2
    if rolled then return {lo[1]-REST_LIFT,cy,cz},{0,0,0} end
    return {hi[1]+REST_LIFT,cy,cz},{180,0,180}
end

local function notifyAnchorSource(weapon,name)
    if not weapon or (type(weapon)=='table' and weapon.preview) then return end
    local zb=rawget(_G,'TWZB')
    if zb and zb.partAnchorSource then zb.partAnchorSource(weapon,name) end
end

local function notifyAnchorForget(weapon)
    if not weapon or (type(weapon)=='table' and weapon.preview) then return end
    local zb=rawget(_G,'TWZB')
    if zb and zb.partAnchorForget then zb.partAnchorForget(weapon) end
end

local function hasFirstAnchor(key,index,entries)
    local script=getScriptManager():getModelScript(Render.modelName(key,index))
    if not script then return false end
    local first=entries and entries[1]
    if not first then return true end
    return script:getAttachmentById(Render.anchorName(first.tpl,first.copy))~=nil
end

local function logConfigureFailed(key,fp,err)
    local id=tostring(key)..'|'..tostring(fp)
    if Render.failed[id] then return end
    Render.failed[id]=true
    print('[TW] render copy configure failed: key='..tostring(key)..' fp='..tostring(fp)..' err='..tostring(err))
end

local function configure(key,index,tree,entries)
    local script=getScriptManager():getModelScript(Render.modelName(key,index))
    if not script then return false end
    local layout,muzzle=Render.layout(key,tree,entries)
    for _,a in ipairs(layout) do
        local target=attachment(script,a.id)
        target:getOffset():set(a.offset[1],a.offset[2],a.offset[3])
        target:getRotate():set(a.rotate[1],a.rotate[2],a.rotate[3])
        target:setScale(a.scale)
    end
    if muzzle then attachment(script,'muzzle'):getOffset():set(muzzle[1],muzzle[2],muzzle[3]) end
    local offset,rotate=Render.restWorld(key,C.bounds(key,tree))
    local world=attachment(script,'world')
    world:getOffset():set(offset[1],offset[2],offset[3])
    world:getRotate():set(rotate[1],rotate[2],rotate[3])
    Render.configured=Render.configured+1
    return true
end

local function configureSlot(key,index,fp,tree,entries)
    local ok,res=pcall(configure,key,index,tree,entries)
    if not ok or not res then
        if Render.pool[key] then Render.pool[key][index]=nil end
        logConfigureFailed(key,fp,not ok and res or 'missing model script')
        return false
    end
    local slot=Render.pool[key] and Render.pool[key][index]
    if slot then slot.fp=fp end
    return true
end

function Render.clear(weapon)
    local state=Render.owner[weapon]
    Render.owner[weapon]=nil
    if not weapon then return end
    -- Window previews own a copy through a plain Lua table, not an item.
    if type(weapon)=='table' and weapon.preview then weapon.evicted=true; return end
    notifyAnchorForget(weapon)
    -- Immersive Snow compatibility: the part list goes back to its plain models (the snow mod restores the item's
    -- own model itself when it leaves the hands).
    if Render.snowUndress then Render.snowUndress(weapon) end
    weapon:setModelWeaponPart(ArrayList.new())
    if state then weapon:setWeaponSprite(Render.modelName(state.key,0)) end
    if Render.onEvicted then Render.onEvicted(weapon) end
end

-- An item that left the world (its square unloaded: TW_RenderWatch): its ownership is dropped, the copy stays as it is.
-- Kahlua tables keep their keys alive (no weak keys), so these tables otherwise held every weapon ever seen.
function Render.forget(weapon)
    local state=Render.owner[weapon]
    Render.owner[weapon]=nil
    if not weapon then return end
    notifyAnchorForget(weapon)
    if Render.snowUndress and not (type(weapon)=='table' and weapon.preview) then Render.snowUndress(weapon) end
    local slot=state and Render.pool[state.key] and Render.pool[state.key][state.index]
    if slot then slot.users[weapon]=nil end
end

function Render.current(weapon,fingerprint)
    local state=Render.owner[weapon]
    if not state then return false end
    local slot=Render.pool[state.key] and Render.pool[state.key][state.index]
    return slot~=nil and slot.fp==state.fp and (fingerprint==nil or state.fp==fingerprint)
end

function Render.touch(weapon)
    local state=Render.owner[weapon]
    if not state then return end
    Render.clock=Render.clock+1
    local slot=Render.pool[state.key] and Render.pool[state.key][state.index]
    if slot then slot.used=Render.clock end
end

-- Bug wave 324 (24.10.2026, the user's item 4): weapons on the ground share the pool with the ones in hands, slung and
-- in windows. A ground weapon (TW_RenderWatch) takes a copy only from nobody, or from ground weapons not touched since
-- the latest ground pass started (Render.groundEpoch: the pass touches the ground weapons near a local player), never
-- from a held, slung or previewed assembly; past RENDER_POOL-1 distinct assemblies of one weapon near the player the
-- nearest keep theirs and the others wait for a copy to free (no ping-pong between them). Hands keep plain LRU.
-- Until the first ground pass a ground request takes free copies only.
Render.groundEpoch=0
local function groundOnly(slot)
    if slot.used>=Render.groundEpoch then return false end
    for other in pairs(slot.users) do
        -- A window preview's owner is a Lua table {preview=true}; items answer getWorldItem.
        if (type(other)=='table' and other.preview) or other:getWorldItem()==nil then return false end
    end
    return true
end

-- Pick the copy already showing this assembly, a free copy, the caller's own
-- previous copy when nobody else uses it (a window preview that changes on
-- every hovered row reconfigures one copy), or the least recently used copy.
-- Weapons still pointing to an evicted copy are cleared. A ground request (ground)
-- evicts only groundOnly copies; nil when there is none.
local function allocate(key,fp,weapon,own,ground)
    local pool=Render.pool[key] or {}
    Render.pool[key]=pool
    local free,oldest
    for i=1,D.renderPool-1 do
        local slot=pool[i]
        if slot and slot.fp==fp then return i,false end
        if not slot then free=free or i
        elseif (not ground or groundOnly(slot)) and (not oldest or slot.used<pool[oldest].used) then oldest=i end
    end
    if ground and not free and not oldest then return nil end
    -- Kahlua has no next(): an empty user list is checked with pairs.
    local unused=own and pool[own] ~= nil
    if unused then for _ in pairs(pool[own].users) do unused=false;break end end
    if not free and unused then oldest=own end
    local index=free or oldest
    local previous=pool[index]
    if previous then
        Render.evicted=Render.evicted+1
        for other in pairs(previous.users) do
            local state=Render.owner[other]
            if other~=weapon and state and state.key==key and state.index==index then Render.clear(other) end
        end
    end
    pool[index]={used=0,users=setmetatable({},{__mode='k'})}
    return index,true
end

-- ground: the request of a weapon on the ground. TW/ReloadVisual wraps Render.apply with four arguments, so the flag
-- also comes as Render.groundRequest (set by TWRuntime.applyRender just before the call), read and cleared here.
function Render.apply(weapon,key,tree,entries,ground)
    ground=ground or Render.groundRequest
    Render.groundRequest=nil
    if isServer() then return end
    Render.applied=(Render.applied or 0)+1
    GeometryState.annotate(key,tree,entries)
    local fp=Render.fingerprint(key,entries)
    local before=Render.owner[weapon]
    local index,fresh=allocate(key,fp,weapon,nil,ground)
    if not index then return false end
    if fresh or not hasFirstAnchor(key,index,entries) then
        if not configureSlot(key,index,fp,tree,entries) then return false end
    end
    if before and (before.key~=key or before.index~=index) then
        local old=Render.pool[before.key] and Render.pool[before.key][before.index]
        if old then old.users[weapon]=nil end
    end
    local lib=library()
    local models=ArrayList.new()
    for _,e in ipairs(entries) do
        local i=GeometryState.libraryIndex(e)
        if i then models:add(lib:get(i)) end
    end
    local name=Render.modelName(key,index)
    notifyAnchorSource(weapon,name)
    -- Immersive Snow compatibility (TW_SnowCompat): the snow copies of this pool copy for every stage, with our part
    -- anchors, must exist before the item's model setter below; the mod's own hook may swap the model at once.
    if Render.snowPrepare then Render.snowPrepare(weapon,key,index) end
    if weapon:getWeaponSprite()~=name then weapon:setWeaponSprite(name) end
    weapon:setModelWeaponPart(models)
    -- ... and the part list shows that snow too (the same isnow_item_<stage> material on every part model).
    if Render.snowDress then Render.snowDress(weapon,models,key,index) end
    local slot=Render.pool[key][index]
    slot.users[weapon]=true
    local states,rows={},{}
    for i,e in ipairs(entries) do
        if e.state then states[e.path]=e.state end
        rows[i]={path=e.path,tpl=e.tpl,copy=e.copy,fullType=e.fullType,state=e.state}
    end
    Render.owner[weapon]={key=key,index=index,fp=fp,states=states,tree=C.copy(tree),entries=rows}
    Render.touch(weapon)
    -- Client consumers of the rendered assembly (TW/MuzzleFx: muzzle effect nodes).
    if Render.onApplied then Render.onApplied(weapon,key,tree) end
    return true
end

-- A pool copy for an assembly that exists only in a window (a draft). The
-- owner is a Lua table {preview=true}; owner.evicted is set when the copy is
-- reused, and the caller simply asks again. Returns the model script name.
function Render.preview(owner,key,tree,entries)
    if isServer() then return nil end
    entries=GeometryState.annotate(key,tree,entries or C.entries(tree,key))
    local fp=Render.fingerprint(key,entries)
    local state=Render.owner[owner]
    if state and state.key==key and state.fp==fp and Render.current(owner) and hasFirstAnchor(key,state.index,entries) then
        Render.touch(owner)
        return Render.modelName(key,state.index)
    end
    local own
    if state then
        local old=Render.pool[state.key] and Render.pool[state.key][state.index]
        if old then old.users[owner]=nil end
        if state.key==key then own=state.index end
    end
    local index,fresh=allocate(key,fp,owner,own)
    if not index then Render.owner[owner]=nil;return false end
    if fresh or not hasFirstAnchor(key,index,entries) then
        if not configureSlot(key,index,fp,tree,entries) then
            Render.owner[owner]=nil
            return false
        end
    end
    Render.pool[key][index].users[owner]=true
    Render.owner[owner]={key=key,index=index,fp=fp}
    owner.evicted=false
    Render.touch(owner)
    return Render.modelName(key,index)
end

-- Reconfigure the owner's pool copy with its stored tree and rows (W9 item 16: TWZB 'own-missing' recovery).
function Render.reconfigure(weapon)
    if isServer() or not weapon then return false end
    local state=Render.owner[weapon]
    if not state or not state.tree or not state.entries then return false end
    local slot=Render.pool[state.key] and Render.pool[state.key][state.index]
    if not slot or (slot.fp~=nil and slot.fp~=state.fp) then return false end
    if not configureSlot(state.key,state.index,state.fp,state.tree,state.entries) then return false end
    local name=Render.modelName(state.key,state.index)
    notifyAnchorSource(weapon,name)
    if Render.snowPrepare then Render.snowPrepare(weapon,state.key,state.index) end
    Render.touch(weapon)
    return true
end

function Render.stats()
    local used=0
    for _,pool in pairs(Render.pool) do for _ in pairs(pool) do used=used+1 end end
    return {used=used,configured=Render.configured,evicted=Render.evicted}
end

-- FB4 (19.10.2026, user's item 9): new geometry states (a sight mode's mesh) of a weapon whose copy is current. The
-- copy's attachments do not depend on states (Render.layout), so the copy and its fingerprint stay; the part list
-- takes the new rows and swap(from,to) replaces each changed part model in the drawn hands: 'done', 'same',
-- 'none' (nothing drawn), 'wait' (the mesh is still loading; retried), anything else refuses. Returns 'done',
-- 'wait' or nil: the caller applies the whole assembly as before.
function Render.swapStates(weapon,key,tree,entries,swap)
    local owner=Render.owner[weapon]
    if isServer() or not owner or owner.key~=key or not owner.states or not Render.current(weapon) then return nil end
    GeometryState.annotate(key,tree,entries)
    local lib=library()
    local models,states,changed=ArrayList.new(),{},{}
    for _,e in ipairs(entries) do
        local i=GeometryState.libraryIndex(e)
        if i then models:add(lib:get(i)) end
        if e.state then states[e.path]=e.state end
        if owner.states[e.path]~=e.state then
            local from=GeometryState.libraryIndex({fullType=e.fullType,state=owner.states[e.path]})
            if not (i and from) then return nil end
            changed[#changed+1]={lib:get(from),lib:get(i)}
        end
    end
    for _,pair in ipairs(changed) do
        local result=swap(pair[1],pair[2])
        if result=='wait' then return 'wait' end
        if result~='done' and result~='same' and result~='none' then return nil end
    end
    weapon:setModelWeaponPart(models)
    if Render.snowDress then Render.snowDress(weapon,models,key,owner.index) end
    owner.states=states
    Render.touch(weapon)
    return 'done'
end

-- Every row of the installed parts with mode geometry (the ordinary one and its states), loaded ahead
-- (preload(row)), so that a mode switch finds its mesh ready.
function Render.preloadStates(entries,preload)
    if isServer() then return end
    local lib=library()
    for _,e in ipairs(entries) do
        if GeometryState.data.modes[e.tpl] then
            if D.libraryIndex[e.fullType] then preload(lib:get(D.libraryIndex[e.fullType])) end
            for _,index in pairs(D.libraryStates[e.fullType] or {}) do preload(lib:get(index)) end
        end
    end
end

return Render
