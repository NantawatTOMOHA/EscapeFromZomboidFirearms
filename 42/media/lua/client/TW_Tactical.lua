-- Keys for Tarkov tactical devices and sights: Z toggles devices (FB3, user's item 2,
-- 18.10.2026: L and K are vanilla's skills panel and FPS display; Z is vanilla's only in a
-- vehicle seat, where the device key yields), held it opens the device menu (TW/HoldMenu,
-- TW/RadialMenus); Shift+Z cycles the device mode and Ctrl+Z the mode of a night vision or
-- thermal scope, unless separate keys are chosen. 9 opens the sight menu: the sights and the
-- modes of night vision and thermal scopes. All keys are rebindable in the mod options, and every
-- one of them may be a combination (Shift/Ctrl/Alt + key): TW_KeyCombo stores and matches the
-- modifier with vanilla's rules (item 6, 25.10.2026).
require 'PZAPI/ModOptions'
local T=require('TW/Tactical')
local R=require('TW/Runtime')
require('TW/LaserSync')
require('TW/LaserRender')
require('TW/LightRender')
local Optic=require('TW/OpticView')
local Sight=require('TW/SightState')
local H=require('TW/HoldMenu')
local Menus=require('TW/RadialMenus')
local Combo=require('TW_KeyCombo')
local options=PZAPI.ModOptions:create('EscapeFromZomboidFirearms','Escape From Zomboid Firearms')
options:addKeyBind('ToggleDevice',getText('UI_TW_ToggleDevice'),Keyboard.KEY_Z)
options:addKeyBind('CycleDeviceMode',getText('UI_TW_CycleDeviceMode'),0)
options:addKeyBind('SightMenu',getText('UI_TW_SightMenu'),Keyboard.KEY_9)
options:addKeyBind('CycleSight',getText('UI_TW_CycleSight'),0)
options:addKeyBind('CycleOpticMode',getText('UI_TW_CycleOpticMode'),0)
options:addDescription(getText('UI_TW_DeviceHelp'))
options:addTickBox('LaserIdle',getText('UI_TW_LaserIdle'),true,getText('UI_TW_LaserIdle_tooltip'))
options:addSlider('LaserBrightness',getText('UI_TW_LaserBrightness'),0.5,3,0.1,1)
options:addTickBox('LightEffects',getText('UI_TW_LightEffects'),true,getText('UI_TW_LightEffects_tooltip'))
options:addSlider('LightBrightness',getText('UI_TW_LightBrightness'),0.5,3,0.1,1)
options:addTickBox('LightFollowsWeapon',getText('UI_TW_LightFollowsWeapon'),true,getText('UI_TW_LightFollowsWeapon_tooltip'))
options:addTickBox('OpticEffects',getText('UI_TW_OpticEffects'),true,getText('UI_TW_OpticEffects_tooltip'))
-- Optional Viewpoint support and add-on ownership are local viewer settings.
local Viewpoint=require('TW_ViewpointCompat')
options:addTickBox('ViewpointSupport',getText('UI_TW_ViewpointSupport'),true,getText('UI_TW_ViewpointSupport_tooltip'))
options:addTickBox('ViewpointGiveToStt',getText('UI_TW_ViewpointGiveToStt'),false,getText('UI_TW_ViewpointGiveToStt_tooltip'))
Viewpoint.bindOptions(options)
-- Read by TW/LaserRender and TW/LightRender every frame (viewer settings, never synchronised).
function T.laserOptions()
    return options:getOption('LaserIdle'):getValue(),options:getOption('LaserBrightness'):getValue()
end
function T.lightOptions()
    return options:getOption('LightEffects'):getValue(),options:getOption('LightBrightness'):getValue(),options:getOption('LightFollowsWeapon'):getValue()
end
-- Read by TW/OpticView every update.
function T.opticOptions()
    return options:getOption('OpticEffects'):getValue()
end

local function shift()
    return isKeyDown(Keyboard.KEY_LSHIFT) or isKeyDown(Keyboard.KEY_RSHIFT)
end
local function ctrl()
    return isKeyDown(Keyboard.KEY_LCONTROL) or isKeyDown(Keyboard.KEY_RCONTROL)
end
local function value(name)
    return options:getOption(name):getValue()
end
-- The assembly window takes its own keys (Ctrl+Z undo): no device or sight action under it.
local function windowOpen(player)
    local window=rawget(_G,'TWAssemblyWindow')
    local open=window and window.instances and window.instances[player:getPlayerNum()]
    return open~=nil and open.getIsVisible~=nil and open:getIsVisible()==true
end

-- The device key without the modifiers of the mode keys: a tap toggles the devices, holding it
-- opens the device menu.
H.register({id='devices',key=function() return value('ToggleDevice') end,
    matches=function(key) return Combo.matches('ToggleDevice',key) end,
    ready=function(player)
        if (ctrl() and value('CycleOpticMode')==0) or (shift() and value('CycleDeviceMode')==0) then return false end
        -- In a vehicle the key is vanilla's (Z switches the seat).
        if player:getVehicle() or windowOpen(player) then return false end
        return #Menus.devices(player:getPrimaryHandItem())>0
    end,
    tap=function(player) T.toggle(player,player:getPrimaryHandItem()) end,
    fill=Menus.fillDevices})
-- The sight key opens the sight menu at once (a tap leaves it open until a choice).
H.register({id='sights',instant=true,key=function() return value('SightMenu') end,
    matches=function(key) return Combo.matches('SightMenu',key) end,
    ready=function(player) return R.key(player:getPrimaryHandItem())~=nil end,
    fill=Menus.fillSights})

Events.OnKeyPressed.Add(function(key)
    if isGamePaused() then return end
    local player=getSpecificPlayer(0)
    if not player or player:isDead() then return end
    local weapon=player:getPrimaryHandItem()
    if not R.visualKey(weapon) or player:getVehicle() or windowOpen(player) then return end
    -- A separate mode key is a binding of its own; unbound, the mode is Shift (device) or Ctrl (optic)
    -- on the device key, as it always was.
    local toggle=value('ToggleDevice')
    if Combo.matches('CycleOpticMode',key) then Sight.cycleMode(player)
    elseif value('CycleOpticMode')==0 and toggle~=0 and key==toggle and ctrl() then Sight.cycleMode(player)
    elseif Combo.matches('CycleDeviceMode',key) then T.cycle(player,weapon)
    elseif Combo.matches('CycleSight',key) then Sight.cycle(player)
    elseif value('CycleDeviceMode')==0 and toggle~=0 and key==toggle and shift() then T.cycle(player,weapon)
    end
    -- A plain device key press is TW/HoldMenu's (the 'devices' menu above).
end)
return options
