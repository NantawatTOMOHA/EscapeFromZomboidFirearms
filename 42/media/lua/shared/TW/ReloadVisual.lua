-- Magazine visuals of a reload: the magazine shown in the support hand.
--
-- The hand model bone Bip01_Prop2 is a root child in the PZ skeleton; the Java part
-- (GripIK.mags, TWZB.gripIKMag) places it on the weapon's magazine frame (mode 1), so
-- the magazine model drawn there (an action's secondary hand model) covers the one on
-- the weapon exactly. Hand models attach through the model's 'Bip01_Prop2' attachment
-- (vanilla GunMagazine); magazine models get an identity one here at runtime, so the
-- generated model scripts stay as they are.
local C=require('TW/Core')
local D=C.data
local Render=require('TW/Render')
local RV={assemblies=setmetatable({},{__mode='k'})}
-- TW/Runtime requires this file: loaded on first use and kept (a require inside the per-tick update scanned the
-- game's list of loaded files on every call; performance pass 23.10.2026).
local Runtime
local function runtime() Runtime=Runtime or require('TW/Runtime');return Runtime end

function RV.modelName(tpl)
    return 'EscapeFromZomboidFirearms.M_'..tpl
end

-- The magazine model with a hand attachment (identity: the model origin is the
-- magazine's mount frame on the weapon). nil if the model script is missing.
function RV.handModel(tpl)
    local script=getScriptManager():getModelScript(RV.modelName(tpl))
    if not script then return nil end
    if not script:getAttachmentById('Bip01_Prop2') then script:addAttachment(ModelAttachment.new('Bip01_Prop2')) end
    return RV.modelName(tpl)
end

-- The installed magazine of a weapon: {key,tree,entries,entry,matrix}; matrix is the
-- 3x4 render frame of the magazine in the weapon model's space (as Render places it).
function RV.magazine(weapon)
    local R=runtime()
    local key=R.key(weapon)
    if not key then return nil end
    local tree,entries=R.read(weapon)
    for _,e in ipairs(entries) do
        local item=D.items[e.tpl]
        if item and item.clip then
            local m=C.renderMatrix(key,C.placement(key,tree,e.path))
            if m then return {key=key,tree=tree,entries=entries,entry=e,matrix=m} end
        end
    end
    return nil
end

function RV.frame(mode,m)
    local t={mode}
    for i=1,3 do for j=1,4 do t[#t+1]=m[i][j] end end
    return t
end

-- Prop2 on the weapon's magazine frame (on=true) or released (nil).
function RV.place(character,mag,on)
    if not (TWZB and TWZB.gripIKMag) then return false end
    TWZB.gripIKMag(character,on and mag and RV.frame(1,mag.matrix) or nil)
    return true
end

-- The weapon drawn without (hidden=true) or with its magazine; the magazine part stays
-- installed, only the visible assembly changes until the next refresh.
function RV.hideWeaponMagazine(weapon,mag,hidden)
    if isServer() or not mag then return end
    local entries=mag.entries
    if hidden then
        entries={}
        for _,e in ipairs(mag.entries) do if e~=mag.entry then entries[#entries+1]=e end end
    end
    Render.apply(weapon,mag.key,mag.tree,entries)
end

-- A reload's visible assembly is distinct from its installed inventory parts. Every
-- rebuild must use the same projection until the inventory acknowledges the handoff.
-- Keep this state outside modData: it is neither an attachment nor saved ammunition.
function RV.project(weapon,key,tree,entries)
    local state=RV.assemblies[weapon]
    if not state or state.key~=key then return tree,entries end
    local shown,visual={},{}
    for path,tpl in pairs(tree) do visual[path]=tpl end
    visual.mod_magazine=state.visible and state.tpl or nil
    for _,e in ipairs(entries) do
        if e.path~='mod_magazine' then shown[#shown+1]=e end
    end
    if state.visible then
        shown[#shown+1]={path='mod_magazine',tpl=state.tpl,copy=0,fullType=C.clipType(key,state.tpl)}
    end
    return visual,shown
end

function RV.setAssembly(action,tpl,visible)
    local gun=action.gun
    local R=runtime()
    local key=R.key(gun)
    if isServer() or not key or not tpl then return false end
    RV.assemblies[gun]={owner=action,character=action.character,key=key,tpl=tpl,visible=visible}
    R.applyRender(gun)
    return true
end

function RV.releaseAssembly(action,completed)
    local state=RV.assemblies[action.gun]
    if not state or state.owner~=action then return end
    local R=runtime()
    -- The MP inventory receipt can follow perform(). Retain a seated magazine
    -- through that receipt, but never through a cancelled action or an unequip.
    if completed and state.visible and R.tree(action.gun).mod_magazine~=state.tpl then
        state.pendingUntil=getTimestampMs()+10000
    else RV.assemblies[action.gun]=nil end
    R.applyRender(action.gun)
end

function RV.updateAssemblies()
    local R=runtime()
    for gun,state in pairs(RV.assemblies) do
        local p=state.character
        local ended=not p or p:getPrimaryHandItem()~=gun or p:isDead()
        if state.pendingUntil then
            ended=ended or R.tree(gun).mod_magazine==state.tpl or getTimestampMs()>state.pendingUntil
        end
        if ended then
            RV.assemblies[gun]=nil
            R.applyRender(gun)
        end
    end
end

local apply=Render.apply
function Render.apply(weapon,key,tree,entries)
    tree,entries=RV.project(weapon,key,tree,entries)
    return apply(weapon,key,tree,entries)
end

return RV
