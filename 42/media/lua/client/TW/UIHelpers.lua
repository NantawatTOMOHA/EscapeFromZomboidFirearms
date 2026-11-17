local C=require('TW/Core')
local U={}
local KNOWN={mod_barrel=true,mod_bipod=true,mod_charge=true,mod_charge_001=true,mod_flashlight=true,mod_foregrip=true,mod_gas_block=true,mod_handguard=true,
    mod_magazine=true,mod_mount=true,mod_muzzle=true,mod_pistol_grip=true,mod_pistolgrip=true,mod_reciever=true,mod_scope=true,mod_sight_front=true,
    mod_sight_rear=true,mod_stock=true,mod_tactical=true,mod_trigger=true,mod_hammer=true,mod_catch=true}

-- mod_mount_002 -> base mod_mount, number 3; mod_tactical001 -> mod_tactical, 2.
function U.slotBase(slot)
    if KNOWN[slot] then return slot,nil end
    local base,index=slot:match('^(.-)_?(%d+)$')
    if base and KNOWN[base] then return base,tonumber(index)+1 end
    return slot,nil
end

function U.slotName(path,key)
    local slot=C.slot(path)
    -- AR-15 buffer tube and the butt pad of a stock share the vanilla mod_stock name.
    if key=='M4A1' and path=='mod_stock' then return getText('IGUI_TW_BufferTube') end
    if slot=='mod_stock' and C.depth(path)>=3 then return getText('IGUI_TW_Buttpad') end
    local base,index=U.slotBase(slot)
    local text=getText('ContextMenu_TW_Slot_'..base)
    if text=='ContextMenu_TW_Slot_'..base then text=slot end
    if index then text=text..' '..index end
    return text
end

-- Short upper-case label of a slot cell in the 3D slot view.
function U.slotShort(path,key)
    local slot=C.slot(path)
    local base,index=U.slotBase(slot)
    local id=KNOWN[base] and base or nil
    if key=='M4A1' and path=='mod_stock' then id='buffer'
    elseif base=='mod_stock' and C.depth(path)>=3 then id='buttpad' end
    if not id then return U.slotName(path,key) end
    local text=getText('IGUI_TW_SlotShort_'..id)
    if index then text=text..' '..index end
    return text
end

function U.partName(tpl)
    return getItemNameFromFullType(C.data.items[tpl].pz)
end

-- Tarkov short name (ShortName of the vanilla locale), else the full name.
function U.shortName(tpl)
    local key='IGUI_TW_Short_'..tpl
    local text=getText(key)
    if text==key or text=='' then return U.partName(tpl) end
    return text
end

function U.weaponName(key)
    return getItemNameFromFullType(C.data.weapons[key].pz)
end

-- Tarkov short weapon name (M4A1, AKM, SCAR-H ...).
function U.weaponShort(key)
    return U.shortName(C.data.weapons[key].tpl)
end

-- The builder draws the picture the Blender icon pass rendered (2:1, the whole part in its own frame): that
-- render is kept byte-for-byte as textures/TW_ItemWide/ItemWide_<tpl>.png, while Item_TW_<tpl>.png is the
-- square inventory icon the game fits into its square cell (tools/item_icons.py). Only the picture changes;
-- the builder's draw rects and layout stay as they are.
function U.icon(tpl)
    if not tpl then return nil end
    return getTexture('media/textures/TW_ItemWide/ItemWide_'..tpl..'.png')
        or getTexture('media/textures/Item_TW_'..tpl..'.png')
end

function U.reason(reason)
    return getText('IGUI_TW_Reason_'..(reason or 'unavailable'))
end

-- A refusal with the conflicting part named, when the rules report one.
function U.reasonText(reason,detail,tpl)
    if (reason=='conflict' or reason=='magazineConflict') and detail then
        local other=C.conflictPart(tpl,detail)
        if other and C.data.items[other] then
            local text=getText('IGUI_TW_ConflictWith',U.partName(other))
            if reason=='magazineConflict' then text=text..' '..getText('IGUI_TW_UnloadFirst') end
            return text
        end
    end
    return U.reason(reason)
end

function U.modeName(tpl,mode)
    local T=require('TW/Tactical')
    return getText('ContextMenu_TW_Mode_'..T.modeKey(tpl,mode):gsub('%+','_'))
end

local chars=require('TW/Text').chars

function U.fit(text,width,font)
    font=font or UIFont.Small
    if getTextManager():MeasureStringX(font,text)<=width then return text end
    local list=chars(text)
    repeat
        list[#list]=nil
        text=table.concat(list)..'...'
    until #list==0 or getTextManager():MeasureStringX(font,text)<=width
    return text
end

-- Word wrap into at most maxLines lines; the last line is shortened.
function U.wrap(text,width,maxLines,font)
    font=font or UIFont.Small
    local lines,line={},''
    for word in tostring(text):gmatch('%S+') do
        local candidate=line=='' and word or line..' '..word
        if line~='' and getTextManager():MeasureStringX(font,candidate)>width then
            lines[#lines+1]=line
            line=word
        else
            line=candidate
        end
    end
    if line~='' then lines[#lines+1]=line end
    if #lines>maxLines then
        local rest={}
        for i=maxLines,#lines do rest[#rest+1]=lines[i] end
        for i=#lines,maxLines,-1 do lines[i]=nil end
        lines[maxLines]=table.concat(rest,' ')
    end
    for i,value in ipairs(lines) do lines[i]=U.fit(value,width,font) end
    return lines
end

function U.signed(value,decimals,suffix)
    return string.format('%+.'..(decimals or 0)..'f',value)..(suffix or '')
end
return U
