-- Radial menus of the weapon in hands (TW/HoldMenu; keys in TW_Tactical.lua):
--   devices  holding the device key: every tactical device of the weapon - turn all on/off, and
--            per device its Tarkov modes (light, laser, IR laser, IR illuminator and their pairs)
--            or off; a weapon with one device shows its modes straight away;
--   sights   the sight key: the installed sights to aim through, a night vision or thermal scope
--            once per mode (palette), which also makes it the active sight.
-- Same rules as the context menu (TW_AssemblyMenu.lua): TW/Tactical.set and .toggle (multiplayer
-- through TW/LaserSync), the sight choice in the weapon's mod data.
local C=require('TW/Core')
local R=require('TW/Runtime')
local T=require('TW/Tactical')
local U=require('TW/UIHelpers')
local H=require('TW/HoldMenu')
local Optic=require('TW/OpticView')
local Sight=require('TW/SightState')

local M={}
TWRadialMenus=M

local function modeIcon(tpl,mode)
    return H.texture('Mode_'..(T.modeKey(tpl,mode):gsub('%+','_')))
end

local function current(on)
    return on and getText('IGUI_TW_Radial_Current') or nil
end

-- Devices ----------------------------------------------------------------------------------
function M.devices(weapon)
    if not weapon or not R.visualKey(weapon) then return {} end
    local tree,_,parts=R.read(weapon)
    return T.devices(tree,parts)
end

function M.fillDeviceModes(menu,player,weapon,d)
    local s=T.state(d.part,d.tpl)
    local name=U.shortName(d.tpl)
    for mode=2,#T.modes(d.tpl) do
        menu:addSlice(H.text(name,U.modeName(d.tpl,mode),current(s.on and s.mode==mode)),modeIcon(d.tpl,mode),T.set,player,weapon,d.part,mode,true)
    end
    if s.on then menu:addSlice(H.text(name,getText('IGUI_TW_Radial_Off')),H.texture('Off'),T.set,player,weapon,d.part,nil,false) end
end

function M.fillDevices(menu,player)
    local weapon=player:getPrimaryHandItem()
    local list=M.devices(weapon)
    if #list==0 then return end
    if #list==1 then return M.fillDeviceModes(menu,player,weapon,list[1]) end
    local anyOn=false
    for _,d in ipairs(list) do if T.state(d.part,d.tpl).on then anyOn=true end end
    if anyOn then menu:addSlice(getText('ContextMenu_TW_DevicesOff'),H.texture('AllOff'),T.toggle,player,weapon)
    else menu:addSlice(getText('ContextMenu_TW_DevicesOn'),H.texture('AllOn'),T.toggle,player,weapon) end
    for _,d in ipairs(list) do
        local s=T.state(d.part,d.tpl)
        local text=H.text(U.shortName(d.tpl),s.on and U.modeName(d.tpl,s.mode) or getText('ContextMenu_TW_Mode_off'))
        menu:addSlice(text,d.part:getTex(),H.sub,'devices',M.fillDeviceModes,player,weapon,d)
    end
end

-- Sights -----------------------------------------------------------------------------------
local function opticIcon(channel,index)
    local profile=channel.modes[index].profile
    local image=profile.imageProfile
    local item=image and Optic.item(image.tpl)
    local mode=item and item.modes[image.mode]
    if mode then return H.texture(mode.kind=='nv' and 'Optic_nv' or 'Optic_thermal_'..tostring(mode.palette)) end
end

function M.chooseSight(player,weapon,path,mode,channel)
    if player:getPrimaryHandItem()~=weapon then return false end
    return Sight.select(player,weapon,path,channel,mode)
end

function M.fillSights(menu,player)
    local weapon=player:getPrimaryHandItem()
    if not weapon or not R.key(weapon) then return end
    local tree,_,parts=R.read(weapon)
    local view=Sight.current(player)
    for _,s in ipairs(C.sights(tree,R.key(weapon))) do
        local part=parts[s.path] or weapon
        local name=Sight.name(s)
        local active=view and view.path==s.path and view.channel==s.channel
        if #s.modes>1 then
            local state=Sight.storage(weapon,s,parts)
            for i=1,#s.modes do
                local text=H.text(name,i..'/'..#s.modes..': '..Sight.modeText(s,i),current(active and i==state.mode))
                menu:addSlice(text,opticIcon(s,i) or part:getTex(),M.chooseSight,player,weapon,s.path,i,s.channel)
            end
        else
            menu:addSlice(H.text(name,current(active)),part:getTex(),M.chooseSight,player,weapon,s.path,nil,s.channel)
        end
    end
    -- A single sight without modes leaves nothing to choose.
    if #menu.slices<2 then menu:clear() end
end

return M
