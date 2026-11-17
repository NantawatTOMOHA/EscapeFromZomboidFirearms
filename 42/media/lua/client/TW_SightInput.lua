-- Consume only our aiming-wheel gesture through the native UI wheel contract.
-- UIManager skips doZoomScroll when onMouseWheel returns true. The global
-- OnMouseWheel notification has no cancellation result and must not be used.
require('ISUI/ISUIElement')
require('ISUI/ISInventoryPane')
require('TW_Tactical')
local Sight=require('TW/SightState')
local R=require('TW/Runtime')
local Combo=require('TW_KeyCombo')
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
-- The zoom modifier is held with the mouse wheel; zeroing is a key press. Both are combinations like every
-- binding of the mod: TW_KeyCombo keeps the modifier and matches it with vanilla's rules (item 6).
options:addKeyBind('SightZoomModifier',getText('UI_TW_SightZoomModifier'),Keyboard.KEY_LMENU)
options:addSlider('SightZoomSensitivity',getText('UI_TW_SightZoomSensitivity'),.1,3,.1,1)
options:addDescription(getText('UI_TW_SightZoomHelp'))
options:addKeyBind('SightZeroIncrease',getText('UI_TW_SightZeroIncrease'),Keyboard.KEY_PRIOR)
options:addKeyBind('SightZeroDecrease',getText('UI_TW_SightZeroDecrease'),Keyboard.KEY_NEXT)
options:addDescription(getText('UI_TW_SightZeroHelp'))
-- User's item 14 (26.10.2026): the brightness of an illuminated reticle through a hotkey. The trigger is the
-- wheel with modifiers (TW_KeyCombo keeps that kind of binding); the code below is TWKeyCombo.WHEEL_CODE and
-- the default row there is Left Alt + Left Shift, so the wheel with those modifiers never zooms the scope.
options:addKeyBind('ReticleBrightness',getText('UI_TW_ReticleBrightness'),20000)

local Input=rawget(_G,'TWSightInput') or {}
TWSightInput=Input
local Wheel=ISUIElement:derive('TWSightWheel')

function Input.blocked(player)
    if isGamePaused() or not player or player:isDead() or not player:isLocalPlayer() then return true end
    if UIManager.getSpeedControls and UIManager.getSpeedControls() and UIManager.getSpeedControls():getCurrentGameSpeed()==0 then return true end
    if getCore():isDoingTextEntry() then return true end
    if ISMouseDrag and ISMouseDrag.dragging then return true end
    if getCell() and getCell():getDrag(0) then return true end
    local radial=getPlayerRadialMenu(0)
    if radial and radial:isReallyVisible() then return true end
    local list=UIManager.getUI()
    for i=0,list:size()-1 do
        local ui=list:get(i)
        local visible
        if ui.isReallyVisible then visible=ui:isReallyVisible() elseif ui.isVisible then visible=ui:isVisible() end
        if (not Input.layer or ui~=Input.layer.javaObject) and visible then
            local t=ui.getTable and ui:getTable()
            if t and (t.Type=='ISContextMenu' or t.Type=='TWAssemblyWindow' or t.Type=='TWInspectWindow'
                or (t.instanceof and t:instanceof(t,'ISCollapsableWindow'))) then return true end
            if ui.isConsumeMouseEvents and ui:isConsumeMouseEvents() and ui:isMouseOver() then return true end
        end
    end
    return false
end

-- Input.blocked once per frame for the per-frame callers (hip fire in first person, a held grenade, hold breath):
-- the UI list scan cost hundreds of reflective calls each time (performance pass 23.10.2026). The UI does not
-- change inside one frame's updates; key and wheel handlers keep asking Input.blocked itself.
local frame,blockedFrame,blockedPlayer,blockedValue=0
Events.OnTickEvenPaused.Add(function() frame=frame+1 end)
function Input.blockedNow(player)
    -- A drag can begin after another caller cached this frame's UI state.
    if ISMouseDrag and ISMouseDrag.dragging then return true end
    if blockedFrame==frame and blockedPlayer==player then return blockedValue end
    blockedValue=Input.blocked(player)
    blockedFrame,blockedPlayer=frame,player
    return blockedValue
end

function Input.ready(player)
    if not Combo.held('SightZoomModifier') or Input.blocked(player) then return false end
    if not (TWZB and TWZB.aimIntent or player.isAiming)(player) or not R.key(player:getPrimaryHandItem()) then return false end
    local view=Sight.current(player)
    return view and Sight.adjustable(view.channelProfile) or false
end

function Input.key(key)
    local direction
    if Combo.matches('SightZeroIncrease',key) then direction=1
    elseif Combo.matches('SightZeroDecrease',key) then direction=-1 end
    if not direction then return false end
    local player=getSpecificPlayer(0)
    -- Our weapon, or a deployed TW underbarrel launcher (its range: Tarkov SightingRange).
    if Input.blocked(player) or not Sight.held(player) then return false end
    return Sight.adjustZero(player,direction)
end

function Wheel:onMouseWheel(delta)
    local player=getSpecificPlayer(0)
    -- The brightness gesture comes first (user's item 14): with its modifiers held the wheel must not zoom
    -- a variable scope, move the game camera or scroll the hotbar, so it is consumed here and nowhere else.
    if Combo.wheelHeld('ReticleBrightness') and not Input.blocked(player) then
        if Sight.adjustBrightness(player,-delta) then return true end
    end
    if not Input.ready(player) then return false end
    -- PZ UI direction: negative is wheel up; Tarkov positive narrows FOV.
    return Sight.wheel(player,-delta,options:getOption('SightZoomSensitivity'):getValue())
end
function Wheel:onMouseDown() return false end
function Wheel:onMouseUp() return false end
function Wheel:onRightMouseDown() return false end
function Wheel:onRightMouseUp() return false end
function Wheel:onMouseMove() return false end
function Wheel:render() end
function Wheel:prerender() end

function Input.start()
    -- B42's floor-drop path tests every native UIElement:isPointOver, even when
    -- consumeMouseEvents is false. Hide only our wheel layer before the real
    -- inventory update handles a released drag; this also covers a press and
    -- release between OnTick callbacks. Native item movement remains unchanged.
    if not Input.inventoryUpdate then
        Input.inventoryUpdate=ISInventoryPane.update
        ISInventoryPane.update=function(self,...)
            if Input.layer and ISMouseDrag and ISMouseDrag.dragging then Input.layer:setVisible(false) end
            return Input.inventoryUpdate(self,...)
        end
    end
    if Input.layer then Input.layer:removeFromUIManager() end
    local layer=Wheel:new(0,0,getCore():getScreenWidth(),getCore():getScreenHeight())
    layer:initialise();layer:instantiate()
    layer.javaObject:setConsumeMouseEvents(false)
    layer:setVisible(false)
    layer:addToUIManager();layer:backMost()
    Input.layer=layer
    Input.lastTime=nil
end

-- Player report of 03.11.2026 ("from the third person Alt takes the character out of the sight, Alt + wheel never
-- zooms"): B42's Sprint (Left Alt) and Run (Left Shift) drop a latched aim, and the zoom modifier and the brightness
-- row are those keys by default. Java (AimGestureInput) keeps the aim of a mod weapon while one of these gestures is
-- held; it reads the keys live, this hands it the bindings whenever they change (0 / no row: unbound).
local pushed,pushTicks=nil,0
function Input.pushGestureKeys(now)
    -- A binding changes in the options screen: once a second is soon enough, and the option scan stays off each tick.
    pushTicks=pushTicks-1
    if pushTicks>0 and not now then return end
    pushTicks=60
    local f=rawget(_G,'TWZB') and TWZB.aimGestureKeys
    if not f then return end
    local zoom=Combo.option('SightZoomModifier')
    local key=zoom and tonumber(zoom.key or (zoom.getValue and zoom:getValue())) or 0
    if Combo.isWheel(key) then key=0 end
    local row=Combo.option('ReticleBrightness')
    local wheel=row and Combo.isWheel(row.key or (row.getValue and row:getValue()))
    local shift,ctrl,alt=wheel and row.shift==true,wheel and row.ctrl==true,wheel and row.alt==true
    local text=key..':'..tostring(shift)..tostring(ctrl)..tostring(alt)
    if text==pushed then return end
    if pcall(f,key,shift==true,ctrl==true,alt==true) then pushed=text end
end

function Input.update()
    Input.pushGestureKeys()
    local player=getSpecificPlayer(0)
    local now=getTimestampMs()
    local seconds=Input.lastTime and math.min(.1,math.max(0,(now-Input.lastTime)/1000)) or 0
    Input.lastTime=now
    if player and not player:isDead() and not isGamePaused() and (TWZB and TWZB.aimIntent or player.isAiming)(player) then Sight.advance(player,seconds) end
    Sight.flush(player)
    Sight.applyZero(player,Sight.current(player))
    if not Input.layer then return end
    Input.layer:setWidth(getCore():getScreenWidth());Input.layer:setHeight(getCore():getScreenHeight())
    Input.refresh(player)
end

-- The layer is up only while a wheel gesture of the mod is held with a weapon of the mod: the zoom modifier (the
-- gesture still asks for ADS, Input.ready) or the brightness modifiers (item 14, which also work before the sight is
-- raised). Without a modifier onMouseWheel returns false anyway. A visible full-screen layer is "the cursor over
-- the UI" to every isMouseOver test - B42's UIElement.isPointOver ignores consumeMouseEvents: OldPoint 0.6.153 hid
-- its overhead aim marker and crosshair whenever our weapon was in hand (player's report of 02.11.2026), and the
-- floor drop needed its own exception (W12 item 11). A key press refreshes it at once, so the first notch after
-- the modifier is not lost to the hotbar or the camera.
function Input.refresh(player)
    if not Input.layer then return end
    player=player or getSpecificPlayer(0)
    local held=player~=nil and not player:isDead() and R.key(player:getPrimaryHandItem())~=nil
    local gesture=held and (Combo.held('SightZoomModifier') or Combo.wheelHeld('ReticleBrightness'))
    Input.layer:setVisible(gesture==true and not (ISMouseDrag and ISMouseDrag.dragging))
end

Events.OnGameStart.Add(Input.start)
Events.OnTickEvenPaused.Add(Input.update)
Events.OnKeyPressed.Add(Input.key)
if Events.OnKeyStartPressed then Events.OnKeyStartPressed.Add(function() Input.refresh() end) end
return Input
