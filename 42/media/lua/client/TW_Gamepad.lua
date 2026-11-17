require 'PZAPI/ModOptions'
require 'ISUI/Gamepad/JoyPadSetup'

local G={HOLD_MS=500,actions={},byId={},down={},active={},chord={},back={},qaLT={},presses={},native={press=0,release=0,radial=0}}
TWGamepad=G
G.PAGE='EscapeFromZomboidFirearmsGamepad'
G.FILE='TWGamepadBindings.ini'
G.BUTTONS={
    {name='A',enum='A',label='A'},{name='B',enum='B',label='B'},
    {name='X',enum='X',label='X'},{name='Y',enum='Y',label='Y'},
    {name='L3',enum='LeftStick',label='L3'},{name='R3',enum='RightStick',label='R3'},
    {name='LB',enum='LeftBump',label='LB'},{name='RB',enum='RightBump',label='RB'},
    {name='Back',enum='Back',label='VIEW'},{name='Start',enum='Start',label='MENU'},
    {name='DPadLeft',enum='DPadLeft',label='D-PAD LEFT'},{name='DPadRight',enum='DPadRight',label='D-PAD RIGHT'},
    {name='DPadUp',enum='DPadUp',label='D-PAD UP'},{name='DPadDown',enum='DPadDown',label='D-PAD DOWN'},
    {name='Guide',enum='Guide',label='GUIDE'},
}
for _,row in ipairs(G.BUTTONS) do G.BUTTONS[row.name]=row end

local function button(name) return JoypadButton and JoypadButton[G.BUTTONS[name].enum] or nil end
local function buttonName(value)
    for _,row in ipairs(G.BUTTONS) do if value==button(row.name) then return row.name end end
end
local function playerOf(controller)
    local data=controller and controller.joypad
    local n=data and data.player
    return n~=nil and getSpecificPlayer(n) or nil,n
end
local function weapon(player)
    local R=require('TW/Runtime')
    local item=player and player:getPrimaryHandItem()
    return item and R.visualKey(item) and item or nil
end
local function usable(def,player)
    if not player or player:isDead() or player:getVehicle() then return false end
    if def.grenade then return require('TW/HandGrenades').row(player:getPrimaryHandItem())~=nil end
    return weapon(player)~=nil
end
local function lt(controller)
    if not controller then return false end
    if G.qaLT[controller.id]~=nil then return G.qaLT[controller.id]==true end
    return isJoypadLTPressed and isJoypadLTPressed(controller.id)==true or false
end
local function binding(def)
    local option=G.options and G.options:getOption(def.id)
    return option and option.binding or {primary=def.primary,modifier=def.modifier}
end
local function modifierDown(def,controller)
    local mod=binding(def).modifier
    if mod=='LT' then return lt(controller) end
    return mod~=nil and G.down[controller.id] and G.down[controller.id][mod]==true or false
end
local function matches(def,controller)
    local held=G.down[controller.id]
    return held and held[binding(def).primary]==true and modifierDown(def,controller) or false
end
local function count(def)
    G.presses[def.id]=(G.presses[def.id] or 0)+1
end
local function openRadial(def,player,state)
    local H=require('TW/HoldMenu')
    local registered
    for _,candidate in ipairs(H.defs) do if candidate.id==def.radial then registered=candidate;break end end
    if not registered or (registered.ready and not registered.ready(player)) then return false end
    local menu=H.open(registered,player)
    if not menu then return false end
    menu=getPlayerRadialMenu(player:getPlayerNum())
    menu:setHideWhenButtonReleased(button(binding(def).primary))
    setJoypadFocus(player:getPlayerNum(),menu)
    state.opened=true
    return true
end
local function runHold(def,player,state)
    if def.id=='GamepadWeaponInspect' then
        local gun=player:getPrimaryHandItem()
        TWAssemblyWindow.open(player,gun,'live',require('TW/Runtime').key(gun))
        state.opened=true
        return true
    end
    return openRadial(def,player,state)
end
local function tap(def,player)
    local gun=player:getPrimaryHandItem()
    local id=def.id
    if id=='GamepadWeaponInspect' then require('TW/WeaponInspect').request(player,gun,'Inspect')
    elseif id=='GamepadCheckChamber' then require('TW/WeaponInspect').request(player,gun,'Chamber')
    elseif id=='GamepadMagazineCheck' then require('TW_ReloadRadial').checkMagazine(player,gun)
    elseif id=='GamepadFireModeCheck' then require('TW/WeaponInspect').request(player,gun,'ModeCheck')
    elseif id=='GamepadStanceLeave' then TWStanceMouse.leave(player,'gamepad')
    elseif id=='GamepadStanceEnter' then TWStanceMouse.enter(player,'gamepad')
    elseif id=='GamepadToggleDevice' then require('TW/Tactical').toggle(player,gun)
    elseif id=='GamepadCycleDeviceMode' then require('TW/Tactical').cycle(player,gun)
    elseif id=='GamepadCycleSight' then require('TW/SightState').cycle(player)
    elseif id=='GamepadReadyToggle' then
        local hold=TWTacHoldOverride
        local Carry=require('TW/Grips').Carry
        if hold.isReady(player) then hold.setReady(player,false,'gamepad') else hold.setMode(player,Carry.nextMode(player,hold.mode(player))) end
    elseif id=='GamepadSightZoomIncrease' or id=='GamepadSightZoomDecrease' then
        local amount=id=='GamepadSightZoomIncrease' and 1 or -1
        local sensitivity=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms'):getOption('SightZoomSensitivity'):getValue()
        require('TW/SightState').wheel(player,amount,sensitivity)
    elseif id=='GamepadSightZeroIncrease' then require('TW/SightState').adjustZero(player,1)
    elseif id=='GamepadSightZeroDecrease' then require('TW/SightState').adjustZero(player,-1)
    elseif id=='GamepadReticleBrightnessUp' then require('TW/SightState').adjustBrightness(player,1)
    elseif id=='GamepadReticleBrightnessDown' then require('TW/SightState').adjustBrightness(player,-1)
    elseif id=='GamepadFireModeSwitch' then require('TW_FireModeSwitch').switch(player)
    elseif id=='GamepadCycleOpticMode' then require('TW/SightState').cycleMode(player)
    end
    count(def)
end
local function add(id,key,label,primary,modifier,extra)
    local def={id=id,key=key,label=label,primary=primary,modifier=modifier}
    if extra then for k,v in pairs(extra) do def[k]=v end end
    G.actions[#G.actions+1]=def;G.byId[id]=def
    return def
end
add('GamepadWeaponInspect','WeaponInspect','UI_TW_WeaponInspect','Y','Back')
add('GamepadCheckChamber','CheckChamber','UI_TW_CheckChamber','X','Back')
add('GamepadMagazineCheck','MagazineCheck','UI_TW_MagazineCheck','A','Back')
add('GamepadFireModeCheck','FireModeCheck','UI_TW_FireModeCheck','RB','Back')
add('GamepadStanceLeave','StanceLeave','UI_TW_StanceLeave','DPadLeft','Back')
add('GamepadStanceEnter','StanceEnter','UI_TW_StanceEnter','DPadRight','Back')
add('GamepadToggleDevice','ToggleDevice','UI_TW_ToggleDevice','Y','RB',{radial='devices'})
add('GamepadCycleDeviceMode','CycleDeviceMode','UI_TW_CycleDeviceMode','X','RB')
add('GamepadSightMenu','SightMenu','UI_TW_SightMenu','A','RB',{radial='sights',instant=true})
add('GamepadCycleSight','CycleSight','UI_TW_CycleSight','B','RB')
add('GamepadReadyToggle','ReadyToggle','UI_TW_ReadyToggle','DPadDown','RB',{radial='stance'})
add('GamepadGrenadeStrong','GrenadeStrong','UI_TW_GamepadGrenadeStrong','DPadLeft','RB',{grenade=true,continuous=true})
add('GamepadGrenadeWeak','GrenadeWeak','UI_TW_GamepadGrenadeWeak','DPadRight','RB',{grenade=true,continuous=true})
add('GamepadHoldBreath','HoldBreath','UI_TW_HoldBreath','L3','LT',{continuous=true})
add('GamepadSightZoomIncrease','SightZoomModifier','UI_TW_GamepadZoomIn','DPadUp','LT')
add('GamepadSightZoomDecrease','SightZoomModifier','UI_TW_GamepadZoomOut','DPadDown','LT')
add('GamepadSightZeroIncrease','SightZeroIncrease','UI_TW_SightZeroIncrease','DPadRight','LT')
add('GamepadSightZeroDecrease','SightZeroDecrease','UI_TW_SightZeroDecrease','DPadLeft','LT')
add('GamepadReticleBrightnessUp','ReticleBrightness','UI_TW_GamepadBrightnessUp','A','LT')
add('GamepadReticleBrightnessDown','ReticleBrightness','UI_TW_GamepadBrightnessDown','B','LT')
add('GamepadFireModeSwitch','FireModeSwitch','UI_TW_FireModeSwitch','X','LT')
add('GamepadCycleOpticMode','CycleOpticMode','UI_TW_CycleOpticMode','Y','LT')

function G.held(id,player)
    local def=G.byId[id]
    local controllerId=player and player:getJoypadBind() or -1
    if not def or controllerId<0 or not usable(def,player) then return false end
    local controller={id=controllerId}
    return matches(def,controller)
end

local function begin(def,controller,player)
    local state={at=getTimestampMs(),controller=controller.id,opened=false}
    G.active[player]=G.active[player] or {}
    G.active[player][def.id]=state
    G.chord[controller.id]=G.chord[controller.id] or {}
    local bound=binding(def)
    G.chord[controller.id][bound.primary]=true
    if bound.modifier and bound.modifier~='LT' then G.chord[controller.id][bound.modifier]=true end
    if def.instant then runHold(def,player,state);count(def) end
    return true
end

function G.press(controller,value)
    if G.capturePress and G.capturePress(controller,value) then return true end
    local name=buttonName(value)
    if not name then return false end
    local player,playerNum=playerOf(controller)
    G.down[controller.id]=G.down[controller.id] or {}
    G.down[controller.id][name]=true
    if TWGamepadBuilder and TWGamepadBuilder.press(controller,value,player) then return true end
    if not player then return false end
    if require('TW/HoldMenu').visible(nil,playerNum) then return false end
    if getFocusForPlayer(playerNum) then return false end
    for _,def in ipairs(G.actions) do
        if binding(def).primary==name and modifierDown(def,controller) and usable(def,player) then
            return begin(def,controller,player)
        end
    end
    return false
end

function G.hold(controller,value,milliseconds)
    local name=buttonName(value)
    local player=playerOf(controller)
    local states=player and G.active[player]
    if states then
        for id,state in pairs(states) do
            local def=G.byId[id]
            if binding(def).primary==name and not state.opened and not def.continuous
                and getTimestampMs()-state.at>=G.HOLD_MS then
                if def.radial or def.id=='GamepadWeaponInspect' then
                    state.opened=true
                    runHold(def,player,state)
                end
                return true
            end
        end
    end
    return false
end

function G.release(controller,value)
    if G.captureRelease and G.captureRelease(controller,value) then return true end
    local name=buttonName(value)
    if not name then return false end
    local player=playerOf(controller)
    local consumed=false
    local states=player and G.active[player]
    if states then
        for id,state in pairs(states) do
            local def=G.byId[id]
            if binding(def).primary==name then
                consumed=true
                if state.opened then
                    local menu=getPlayerRadialMenu(player:getPlayerNum())
                    if menu and def.radial and require('TW/HoldMenu').visible(def.radial,player:getPlayerNum())
                        and not (def.instant and getTimestampMs()-state.at<G.HOLD_MS)
                        and not getCore():getOptionRadialMenuKeyToggle() then menu:undisplay() end
                elseif not def.continuous and not def.instant then tap(def,player) end
                states[id]=nil
            end
        end
    end
    local chords=G.chord[controller.id]
    if chords and chords[name] then consumed=true;chords[name]=nil end
    if G.down[controller.id] then G.down[controller.id][name]=false end
    return consumed
end

function G.nativeChord(playerNum,name)
    local player=getSpecificPlayer(playerNum)
    local id=player and player:getJoypadBind() or -1
    return id>=0 and G.chord[id] and G.chord[id][name]==true or false
end

local function backDelayed(controller)
    local row=G.back[controller.id]
    return row and row.delayed==true
end
local function installNativeGuards()
    if not ISFirearmRadialMenu or ISFirearmRadialMenu.twGamepadGuard then return end
    local repeatRB=ISFirearmRadialMenu.onRepeatRBumper
    local releaseRB=ISFirearmRadialMenu.onJoypadButtonReleased
    ISFirearmRadialMenu.onRepeatRBumper=function(prompt,...)
        if G.nativeChord(prompt.player,'RB') then G.native.radial=G.native.radial+1;return false end
        return repeatRB(prompt,...)
    end
    ISFirearmRadialMenu.onJoypadButtonReleased=function(prompt,value,...)
        if G.nativeChord(prompt.player,'RB') then G.native.release=G.native.release+1;return end
        return releaseRB(prompt,value,...)
    end
    ISFirearmRadialMenu.twGamepadGuard=true
    if ISJoystickButtonRadialMenu then
        local repeatL3=ISJoystickButtonRadialMenu.onRepeatLeftStickButton
        ISJoystickButtonRadialMenu.onRepeatLeftStickButton=function(data,...)
            local player=data and getSpecificPlayer(data.player)
            if player and G.held('GamepadHoldBreath',player) then return end
            return repeatL3(data,...)
        end
    end
end

function G.install()
    if not JoypadControllerData or G.installed then installNativeGuards();return false end
    G.installed=true
    G.wraps={}
    local function wrap(name)
        local previous=JoypadControllerData[name]
        G.wraps[name]=previous
        JoypadControllerData[name]=function(self,...)
            local args={...}
            local value=args[1]
            local isBack=value==button('Back')
            if name=='onPressButton' then
                local consumed=G.press(self,value)
                if isBack then
                    local player=playerOf(self)
                    if player and weapon(player) then
                        G.back[self.id]={delayed=true,forwarded=false}
                        return
                    end
                end
                if not consumed then return previous(self,...) end
                return
            elseif name=='onHoldButton' then
                local consumed=G.hold(self,value,args[2])
                if isBack and backDelayed(self) then
                    local row=G.back[self.id]
                    local chords=G.chord[self.id]
                    if not (chords and chords.Back) and not row.forwarded then
                        row.forwarded=true
                        G.native.press=G.native.press+1
                        G.wraps.onPressButton(self,value)
                    end
                    return
                end
                if not consumed then return previous(self,...) end
                return
            elseif name=='onReleaseButton' then
                local consumed=G.release(self,value)
                if isBack and backDelayed(self) then
                    local row=G.back[self.id];G.back[self.id]=nil
                    if not consumed then
                        if not row.forwarded then G.native.press=G.native.press+1;G.wraps.onPressButton(self,value) end
                        G.native.release=G.native.release+1
                        previous(self,...)
                    end
                    return
                end
                if not consumed then return previous(self,...) end
                return
            end
        end
    end
    wrap('onPressButton');wrap('onHoldButton');wrap('onReleaseButton')
    for _,direction in ipairs({'Up','Down','Left','Right'}) do
        for _,kind in ipairs({'Press','Release'}) do
            local name='on'..kind..direction
            local previous=JoypadControllerData[name]
            G.wraps[name]=previous
            JoypadControllerData[name]=function(self)
                local value=button('DPad'..direction)
                local consumed=kind=='Press' and G.press(self,value) or G.release(self,value)
                if not consumed then return previous(self) end
            end
        end
    end
    installNativeGuards()
    return true
end

function G.qaController(id,playerNum)
    return {id=id,joypad={player=playerNum},connected=true}
end
function G.qaPress(id,name,playerNum)
    local controller=G.qaController(id,playerNum)
    local value=button(name)
    if name=='Back' then
        G.down[id]=G.down[id] or {}
        G.back[id]={delayed=true,forwarded=false}
    end
    return G.press(controller,value)
end
function G.qaHold(id,name,playerNum,milliseconds)
    return G.hold(G.qaController(id,playerNum),button(name),milliseconds or G.HOLD_MS)
end
function G.qaRelease(id,name,playerNum)
    return G.release(G.qaController(id,playerNum),button(name))
end
function G.qaSetLT(id,value) G.qaLT[id]=value==true end
function G.statsText()
    local parts={}
    for _,def in ipairs(G.actions) do parts[#parts+1]=def.id..'='..(G.presses[def.id] or 0) end
    return table.concat(parts,' ')
end

G.install()
Events.OnGameBoot.Add(G.install)
Events.OnGameStart.Add(function() G.down={};G.active={};G.chord={};G.back={};G.install() end)
require('TW_GamepadBuilder').install(G)
require('TW_GamepadOptions').install(G)
return G
