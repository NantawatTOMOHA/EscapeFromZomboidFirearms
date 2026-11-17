-- Immersive Snow (Workshop 3810071828, mod id ImmersiveSnow) compatibility (bug wave W7, 25.10.2026, the user's
-- item 1: "the weapon and the model break when snow builds up on the character's body; brushing it off fixes them").
--
-- Why our weapons broke: the snow mod puts snow on an item in the hands by swapping the item's own model for a copy
-- of its model script with an isnow_item_<stage> shader (ISnow_CharSnow.lua snowModel/setItemStage). Its copy is built
-- from the model script's loaded text bodies - and our part mounts do not exist there: every weapon root RENDER_POOL
-- copies carry their attachment anchors (t_<tpl>_<copy>, muzzle, world) only as objects added and placed at runtime
-- (TW/Render.configure). On the snow copy every anchor was absent, so the engine placed every part model at the
-- weapon's origin (a bare receiver with the barrel, handguard, magazine and stock piled on it), and the programmes
-- that look a drawn model up by the item's name (the chamber and magazine rounds, the moving bolt) stopped finding
-- the drawn weapon. Brushing the snow off restored the item's model, hence the "it fixes itself" report.
--
-- What this file does (their files are never changed):
--  1. Before the snow mod can swap a weapon, the snow copies of its root model for all six stages are made here (the
--     same name and content their snowModel would make, plus every attachment of our model as it is now: anchors,
--     muzzle, world). Their swap then finds our copy and the parts stay in place. Every refresh re-syncs the copies
--     from our model (a pool copy is re-placed for another assembly) and adds the anchors other code of the mod adds
--     later (chamber and magazine round nodes).
--  2. The part list of the drawn weapon is dressed for the current snow stage: each part's model is replaced by a
--     snow copy of that part's script (same mesh and texture, the same isnow_item_<stage> shader their root copy uses),
--     so snow shows on the parts too. Isometric draws it through the shader; Viewpoint's bridge (Immersive Snow -
--     Viewpoint) bakes every model of the character whose shader starts with isnow_item, our parts included.
--  3. The stage follows the mod's own state (ISnow.CharSnow.levelOf and ISnow.opt("CharSnow"/"CharItemSnow"), the
--     same inputs and the same six stages as its itemStageIndex); when it changes, the part list is dressed again and
--     the hands are rebuilt once. Without Immersive Snow, without its item snow option or with no snow on the player,
--     nothing is created, nothing is swapped and the weapon is drawn exactly as before.
-- Multiplayer: the stage is the one the mod reports for this player (client synced), so the local player's weapon
-- follows the snow the others see; other players' weapons are not snowed by the mod itself (its item swap is local),
-- and neither are ours.
local Render=require('TW/Render')
local Snow={MOD='ImmersiveSnow',STAGES={'s1','s2','s3','s4','s5','s6'},MIN_LEVEL=0.05}
TWSnow=Snow

local copies={}       -- [base full type .. "|" .. stage] = ModelScript (our copy of a root model)
local partCopies={}   -- [part full type .. "|" .. stage] = ModelScript (our copy of a part model)
local synced={}       -- [base full type] = attachment count when it was last copied
local registered={}   -- [copy full type] = true (the Java renderer knows the model it was made from)
local copied={}       -- [base full type] = true (the six stage copies of this pool copy were made and synced)
local asserted={}     -- [copy full type] = true (put into the script map once when the lookup missed it)
local dressed={}      -- [weapon] = { stage, plain, snow, root, parts = { [base] = copy } }
Snow.stats={syncs=0,asserts=0,refreshes=0,rebuilds=0}

local function api()
    local s=rawget(_G,'ISnow')
    return type(s)=='table' and type(s.CharSnow)=='table' and type(s.opt)=='function' and s or nil
end

local function modActive()
    local mods=getActivatedMods and getActivatedMods()
    if not mods then return false end
    for i=0,mods:size()-1 do
        local id=tostring(mods:get(i)):gsub('^\\','')
        if id==Snow.MOD then return true end
    end
    return false
end

local function holder(weapon)
    if not weapon or not getNumActivePlayers then return nil end
    for i=0,getNumActivePlayers()-1 do
        local p=getSpecificPlayer(i)
        if p and not p:isDead() and (p:getPrimaryHandItem()==weapon or p:getSecondaryHandItem()==weapon) then return p end
    end
end

-- The snow stage of a character, exactly as ISnow_CharSnow.lua itemStageIndex/syncItems/pollCharacter pick it: nothing
-- while the snow on characters or on items is off or below MIN_LEVEL, else one of the six stages.
function Snow.stageOf(player)
    local s=api()
    if not s or not player then return nil end
    local ok,charSnow=pcall(s.opt,'CharSnow')
    if not ok or not charSnow then return nil end
    local okItem,itemSnow=pcall(s.opt,'CharItemSnow')
    if not okItem or not itemSnow then return nil end
    local okLevel,level=pcall(s.CharSnow.levelOf,player)
    if not okLevel or type(level)~='number' or level<Snow.MIN_LEVEL then return nil end
    local i=math.max(1,math.min(#Snow.STAGES,math.ceil(level*#Snow.STAGES)))
    return Snow.STAGES[i]
end

-- Whether snow may reach this character at all (falling, settled on them or on the ground): the copies are made then,
-- well before the level reaches the first stage, so the mod's first swap already finds one with our anchors.
local function wanted(player)
    local s=api()
    if not s then return false end
    local ok,snowing=pcall(function() return getClimateManager():isSnowing() end)
    if ok and snowing then return true end
    local okGround,cover=pcall(function() return s.groundCover and s.groundCover() or 0 end)
    if okGround and (tonumber(cover) or 0)>0 then return true end
    if player then
        local okLevel,level=pcall(s.CharSnow.levelOf,player)
        if okLevel and type(level)=='number' and level>0 then return true end
    end
    return false
end

local function shader(stage)
    local s=api()
    local dark=false
    if s and s.darkSnow then local ok,value=pcall(s.darkSnow);dark=ok and value==true end
    return (dark and 'isnow_itemd_' or 'isnow_item_')..stage
end

-- The model script name the snow mod makes for a base model (ISnow_CharSnow.lua snowModel): the full type with every
-- non-word character replaced, then the stage, in the base model's own module.
local function copyName(base,stage)
    local full=base:getFullType()
    return base:getModule():getName()..'.ISnowItem_'..full:gsub('[^%w]','_')..'_'..stage
end

local function vecCopy(dst,src)
    local ok,x,y,z=pcall(function() return src:x(),src:y(),src:z() end)
    if ok and type(x)=='number' then return dst:set(x,y,z) end
    return dst:set(src)
end

-- Every attachment of base on copy, as it is now: offset, rotation and scale; missing ones are added. Nothing is
-- removed: a drawn snow copy gets its own nodes from other code (TW_CartridgeVisual attaches the round nodes to the
-- drawn script) and they must stay.
local function syncAttachments(copy,base)
    local n=base:getAttachmentCount()
    Snow.stats.syncs=Snow.stats.syncs+1
    for i=0,n-1 do
        local a=base:getAttachment(i)
        local id=a:getId()
        local c=copy:getAttachmentById(id)
        if not c then c=copy:addAttachment(ModelAttachment.new(id)) end
        vecCopy(c:getOffset(),a:getOffset())
        vecCopy(c:getRotate(),a:getRotate())
        c:setScale(a:getScale())
        local ok,bone=pcall(a.getBone,a)
        if ok and type(bone)=='string' and bone~='' then pcall(c.setBone,c,bone) end
    end
    synced[base:getFullType()]=n
    return n
end

local function register(copy,base)
    local key=copy:getFullType()
    if registered[key] then return end
    local zb=rawget(_G,'TWZB')
    if zb and zb.snowBase then
        local ok=pcall(zb.snowBase,key,base:getFullType())
        if ok then registered[key]=true end
    end
end

-- The snow copy of a model script for one stage, made the way ISnow_CharSnow.lua snowModel makes it (all loaded text
-- bodies under the new name, then the shader body) and then given every attachment of the model as it is now.
local function ensure(base,stage,cache)
    if not base then return nil end
    local key=base:getFullType()..'|'..stage
    local copy=cache[key]
    if copy then register(copy,base);return copy end
    local sm=getScriptManager()
    local short='ISnowItem_'..base:getFullType():gsub('[^%w]','_')..'_'..stage
    local name=base:getModule():getName()..'.'..short
    copy=sm:getModelScript(name)   -- another run of the mod (or this one) may have made it already
    if not copy then
        copy=ModelScript.new()
        copy:setModule(base:getModule())
        copy:InitLoadPP(short)
        local made=false
        local okBodies,bodies=pcall(base.getLoadedScriptBodies,base)
        if okBodies and bodies then
            for i=0,bodies:size()-1 do
                local body=bodies:get(i)
                local open=string.find(body,'{',1,true)
                if open then
                    local ok=pcall(copy.Load,copy,short,'model '..short..'\n'..string.sub(body,open))
                    made=made or ok
                end
            end
        end
        if not made then
            local mesh,tex=base:getMeshName(),base:getTextureName()
            if not mesh then return nil end
            pcall(copy.Load,copy,short,'model '..short..'\n{\n    mesh = '..mesh..',\n    texture = '..tex
                ..',\n    static = true,\n}\n')
        end
        pcall(copy.Load,copy,short,'model '..short..'\n{\n    shader = '..shader(stage)..',\n}\n')
        sm:addModelScript(copy)
        copy=sm:getModelScript(name) or copy
    end
    syncAttachments(copy,base)
    cache[key]=copy
    register(copy,base)
    return copy
end

-- QA/diagnostics: the copy this file made for a base model and stage (nil if it made none).
function Snow.cached(baseFull,stage) return copies[baseFull..'|'..stage] end

-- The snow copies of our root model of a pool copy: all six stages, so the mod's swap never finds a copy without the
-- anchors; called before TW/Render sets the item's model (its setter may re-run the swap at once).
function Snow.prepare(weapon,key,index,force)
    if not Snow.enabled then return end
    local p=holder(weapon)
    if not wanted(p) then return end
    local base=getScriptManager():getModelScript(Render.modelName(key,index))
    if not base then return end
    local baseFull=base:getFullType()
    -- only after a refresh (the pool copy is re-placed for another assembly), the first snow and a node count change:
    -- writing the attachments every tick makes the engine rebuild the hands every tick
    if not force and copied[baseFull] and (synced[baseFull] or -1)==base:getAttachmentCount() then return end
    copied[baseFull]=true
    for _,stage in ipairs(Snow.STAGES) do
        local copy=ensure(base,stage,copies)
        if copy then syncAttachments(copy,base) end
    end
end

-- The drawn model of a weapon: while the snow mod's swap is in place the engine draws its copy, not the item's own
-- model (its Lua getter hides that from other mods), and every programme that looks the drawn model up by name has to
-- ask here. Nil when no snow copy is drawn.
function Snow.drawnName(weapon)
    local st=weapon and dressed[weapon]
    if st and st.root and weapon:hasModData() and weapon:getModData().ISnowOrigIndex~=nil then return st.root end
    return nil
end

-- The drawn model of a part model name (a magazine's own script while it is installed on a snowed weapon).
function Snow.partModel(weapon,model)
    local st=weapon and dressed[weapon]
    if not st or not st.parts then return model end
    return st.parts[model] or model
end

local function partRows(plain,stage)
    -- Kahlua cannot read ModelWeaponPart's fields: the Java part names the rows and copies the list with our names.
    local zb=rawget(_G,'TWZB')
    if not plain or not zb or not zb.partModelNames or not zb.partRowsWithModels then return nil end
    local names=zb.partModelNames(plain)
    local map={}
    local i=1
    while true do
        local base=names[i]
        if type(base)~='string' then break end
        local copy=ensure(getScriptManager():getModelScript(base),stage,partCopies)
        if copy then map[base]=copy:getFullType() end
        i=i+1
    end
    local out=zb.partRowsWithModels(plain,map)
    return out,map
end

-- Dress the drawn part list for the holder's snow stage; nil without snow (the plain list stays as Render set it).
-- key/index name the pool copy the weapon shows, whose snow root the mod swaps to.
local assertCopy
function Snow.dress(weapon,plain,key,index)
    if not Snow.enabled then return nil end
    local p=holder(weapon)
    local stage=p and Snow.stageOf(p) or nil
    local st=dressed[weapon]
    if not st then st={};dressed[weapon]=st end
    local samePlain=st.plain==plain
    st.plain=plain
    if not stage then
        st.snow,st.stage,st.root,st.parts=nil,nil,nil,nil
        return nil
    end
    local rootBase=getScriptManager():getModelScript(Render.modelName(key,index))
    st.root=rootBase and copyName(rootBase,stage) or nil
    if rootBase then
        local ours=copies[rootBase:getFullType()..'|'..stage] or ensure(rootBase,stage,copies)
        if ours then
            if rootBase:getAttachmentCount()>ours:getAttachmentCount() then syncAttachments(ours,rootBase) end
            assertCopy(ours,rootBase)
        end
    end
    if samePlain and st.stage==stage and st.snow then return st.snow end
    local rows,maps=partRows(plain,stage)
    st.snow,st.stage,st.parts=rows,stage,maps
    return rows
end

-- The stage changed under a held weapon (the mod's poll): dress the list again and rebuild the hands once (the mod
-- rebuilds them after its own swap; this one is for our part list).
local function refresh(weapon,player)
    local st=dressed[weapon]
    if not st or not st.plain then return end
    local stage=Snow.stageOf(player)
    if st.stage==stage then return end
    local state=Render.owner[weapon]
    if not state then return end
    -- dress() also clears its stage and its snow list when there is no snow any more, so the next tick is quiet
    local rows=Snow.dress(weapon,st.plain,state.key,state.index)
    if rows then
        local base=getScriptManager():getModelScript(Render.modelName(state.key,state.index))
        local ours=base and copies[base:getFullType()..'|'..st.stage]
        if ours then
            if base:getAttachmentCount()>ours:getAttachmentCount() then syncAttachments(ours,base) end
            assertCopy(ours,base)
        end
    end
    weapon:setModelWeaponPart(rows or st.plain)
    Snow.stats.refreshes=Snow.stats.refreshes+1
    player:resetEquippedHandsModels()
end

-- The snow mod makes its copy with a short name lookup, which resolves in the Base module and so misses the copies
-- registered in our module: it then adds its own copy under the same name and the script map serves that one (made
-- from the text, without our mounts). Our copy (with every mount) is put back into the map; nodes other code attached
-- to the copy that was drawn meanwhile (the chamber round nodes of TW_CartridgeVisual) are carried over.
function assertCopy(copy,base)
    if not copy then return false end
    local manager=getScriptManager()
    local mapped=manager:getModelScript(copy:getFullType())
    if mapped==copy then return false end
    if mapped==nil then
        -- the name is not in the script map at all: put it there once, but never rebuild on a lookup that keeps missing
        if asserted[copy:getFullType()] then return false end
        asserted[copy:getFullType()]=true
    else
        for i=0,mapped:getAttachmentCount()-1 do
            local a=mapped:getAttachment(i)
            local id=a:getId()
            if id and not copy:getAttachmentById(id) then
                local c=copy:addAttachment(ModelAttachment.new(id))
                vecCopy(c:getOffset(),a:getOffset())
                vecCopy(c:getRotate(),a:getRotate())
                c:setScale(a:getScale())
            end
        end
    end
    manager:addModelScript(copy)
    Snow.stats.asserts=Snow.stats.asserts+1
    return true
end

-- Attachments other code adds to the drawn model after the copies were made (the chamber and magazine round nodes of
-- TW_CartridgeVisual) are mirrored to the copies of the drawn weapon; counts only, their frames are placed once.
local function refreshAttachments(weapon)
    local st=dressed[weapon]
    if not st or not st.parts then return end
    local state=Render.owner[weapon]
    if not state then return end
    local player=holder(weapon)
    local rebuilt=false
    local base=getScriptManager():getModelScript(Render.modelName(state.key,state.index))
    if base and st.root then
        local copy=getScriptManager():getModelScript(st.root)
        local ours=copies[base:getFullType()..'|'..st.stage]
        if ours then
            if base:getAttachmentCount()>ours:getAttachmentCount() then syncAttachments(ours,base) end
            if assertCopy(ours,base) then rebuilt=true end
        elseif copy and (synced[base:getFullType()] or -1)~=base:getAttachmentCount() then
            syncAttachments(copy,base)
        end
    end
    for model,copyNow in pairs(st.parts) do
        local plain=getScriptManager():getModelScript(model)
        local ours=partCopies[model..'|'..st.stage]
        if ours then
            if plain and plain:getAttachmentCount()>ours:getAttachmentCount() then syncAttachments(ours,plain) end
            if assertCopy(ours,plain) then rebuilt=true end
        else
            local copy=getScriptManager():getModelScript(copyNow)
            if plain and copy and (synced[model] or -1)~=plain:getAttachmentCount() then syncAttachments(copy,plain) end
        end
    end
    if rebuilt and player then
        Snow.stats.rebuilds=Snow.stats.rebuilds+1
        player:resetEquippedHandsModels()
    end
end

function Snow.tick()
    if not Snow.enabled then return end
    for i=0,getNumActivePlayers()-1 do
        local p=getSpecificPlayer(i)
        if p and not p:isDead() then
            local w=p:getPrimaryHandItem()
            local state=w and Render.owner[w]
            if w and state then
                if wanted(p) then Snow.prepare(w,state.key,state.index) end
                refresh(w,p)   -- the stage may have changed either way (snow started, melted or was brushed off)
                if dressed[w] and dressed[w].parts then refreshAttachments(w) end
            end
            local s=p:getSecondaryHandItem()
            if s and s~=w and Render.owner[s] then refresh(s,p) end
        end
    end
end

-- QA / diagnostics: the stage this weapon is dressed for, the snow root the mod draws and the number of part models.
function Snow.describe(weapon)
    local st=weapon and dressed[weapon]
    if not st then return 'not dressed' end
    local parts=0
    for _ in pairs(st.parts or {}) do parts=parts+1 end
    return string.format('stage=%s root=%s parts=%d',tostring(st.stage),tostring(st.root),parts)
end

-- The snow mod's own swap was restored (the item left the hands or Render.clear took the copy away): the list goes
-- back to the plain one; the item's next draw is rebuilt by the mod or by TW/Render.
function Snow.undress(weapon)
    local st=dressed[weapon]
    if not st then return end
    if st.plain and st.snow then pcall(function() weapon:setModelWeaponPart(st.plain) end) end
    dressed[weapon]=nil
end

Snow.enabled=modActive()
if Snow.enabled then
    local function guarded(name,fn)
        return function(...)
            local ok,err=pcall(fn,...)
            if not ok then print('[TW] Immersive Snow '..name..' failed: '..tostring(err)) end
        end
    end
    Render.snowPrepare=guarded('prepare',function(weapon,key,index)
        return Snow.prepare(weapon,key,index,true)
    end)
    Render.snowDress=guarded('dress',function(weapon,plain,key,index)
        local rows=Snow.dress(weapon,plain,key,index)
        if rows then weapon:setModelWeaponPart(rows) end
    end)
    Render.snowUndress=guarded('undress',Snow.undress)
    Events.OnTick.Add(guarded('tick',Snow.tick))
    Events.OnGameStart.Add(guarded('tick',Snow.tick))
    print('[TW] Immersive Snow compatibility on: our part mounts survive its item model swap and the parts show its snow')
end
return Snow
