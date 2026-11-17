-- A side mouse button raises the weapon from its carry stance and lowers it back in Viewpoint's first
-- person (FB1, the user's item 16, 17.10.2026). The carry stances are TW_TacHoldOverride's
-- (low/high/patrol, the G key and its menu). FB4 (the user's item 2, 19.10.2026): there is no
-- "no stance" any more (it played the vanilla pose); a press raises the weapon to Tarkov's
-- first-person ready over the stance (TWTacHoldOverride.setReady, as after a shot), the next press
-- lowers it back into the stance. The stance itself stays selected and is never sent or saved here, so
-- multiplayer approval, the G key and the menu stay its single owners. Viewpoint names the mouse
-- buttons from 1 (MOUSE4 is its default free look); PZ counts them from 0, so MOUSE5 is button 4.
-- Bug wave 324 (the user's item 2, 24.10.2026: "Viewpoint's free-look key does not work with our mod"; the user had
-- keys.freeLook=MOUSE5, this option's default): while the button is Viewpoint's free-look key (any of its chords, read
-- from Viewpoint.Keys at the press) a press is left to the free look and changes no stance (freeLookSkips).
-- Viewpoint's codes are 10000 + the PZ button (MOUSE5 = 10004 = button 4, as tools/keybind_codes.py).
-- W9-advmove (item 10): a button that is also a vanilla ATTACK key (Melee, Attack - Advanced Movement moves Melee
-- to Mouse 5 on its first run, this option's default) is left to the game too: no shove from a stance press
-- (actionSkips).
-- W10 (the user's item 23, 27.10.2026: "make the exit from and the entry into the last stance configurable not
-- only on the side mouse buttons"): the same two actions on keyboard keys of the options screen. The keys are
-- ordinary bindings of the mod (Shift/Ctrl/Alt combinations through TW_KeyCombo, as every key of the mod) and are
-- unbound by default, so a player who never binds them keeps exactly the old behaviour; both may be used beside
-- the side mouse button. The keys act where the button does - Viewpoint's first person - and in the isometric and
-- third-person views a press changes nothing and says why ([TW-STANCE]), as the button there.
require 'PZAPI/ModOptions'
require('TW_TacHoldOverride')
local G=require('TW/Grips')
local Anim=require('TW/AnimOptOut') -- W8 animopt: the two "vanilla animations" options
local Combo=require('TW_KeyCombo')
local Carry=G.Carry
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearmsCarry')
local button=options:addComboBox('StanceMouse',getText('UI_TW_StanceMouse'),getText('UI_TW_StanceMouse_tooltip'))
button:addItem(getText('UI_TW_StanceMouseOff'))
button:addItem(getText('UI_TW_StanceMouse4'))
button:addItem(getText('UI_TW_StanceMouse5'),true)
-- W10 (item 23): the two actions of the side mouse button on keyboard keys. Unbound by default (0): the key
-- bindings are an addition, the button keeps its current default.
options:addKeyBind('StanceLeave',getText('UI_TW_StanceLeave'),0)
options:addKeyBind('StanceEnter',getText('UI_TW_StanceEnter'),0)

local M={down=false,presses=0,toggles=0,keyPresses=0,keySkips=0,lastKeyRefusal='',lastKey='',
    freeLookSkips=0,actionSkips=0,errors=0,lastError=''}
TWStanceMouse=M
M.BUTTONS={nil,3,4}
M.MOUSE=10000

function M.button()
    local value=options:getOption('StanceMouse'):getValue()
    return M.BUTTONS[value]
end

-- Viewpoint's free-look key: the codes of its chords (keyboard keys, 10000 + mouse button), none without Viewpoint.
function M.freeLookCodes()
    local vp=rawget(_G,'Viewpoint')
    local keys=type(vp)=='table' and vp.Keys or nil
    if not (keys and keys.get and keys.trigger) then return {} end
    local ok,text=pcall(keys.get,'keys.freeLook')
    if not ok or type(text)~='string' then return {} end
    local out={}
    for chord in text:gmatch('[^,]+') do
        local good,code=pcall(keys.trigger,chord)
        code=good and tonumber(code) or nil
        if code and code>0 then out[#out+1]=code end
    end
    return out
end

-- The PZ mouse button index is Viewpoint's free-look key now.
function M.freeLookButton(index)
    if index==nil then return false end
    for _,code in ipairs(M.freeLookCodes()) do
        if code==M.MOUSE+index then return true end
    end
    return false
end

-- Vanilla actions that must keep a button of their own: a press that attacks must never also change the carry
-- stance. Viewpoint Advanced Movement (W9-advmove, item 10) moves vanilla Melee to Mouse 5 on its first run,
-- which is this option's default button: without this rule every stance press shoved with the weapon in hand.
-- Same policy as the free look above: the press is left to the game, nothing is toggled.
M.VANILLA_ACTIONS={'Melee','Attack'}
function M.actionCodes()
    local out={}
    local core=getCore and getCore()
    if not (core and core.getKey and core.getAltKey) then return out end
    for _,name in ipairs(M.VANILLA_ACTIONS) do
        for _,alt in ipairs({false,true}) do
            local fn=alt and core.getAltKey or core.getKey
            local ok,code=pcall(fn,core,name)
            code=ok and tonumber(code) or nil
            if code and code>0 then out[#out+1]=code end
        end
    end
    return out
end

-- The PZ mouse button index is a vanilla attack key now.
function M.actionButton(index)
    if index==nil then return false end
    for _,code in ipairs(M.actionCodes()) do
        if code==M.MOUSE+index then return true end
    end
    return false
end

local actionWarned
function M.actionSkipOnce()
    if actionWarned then return end
    actionWarned=true
    print('[TW-ADVMOVE] the carry-stance button is also a vanilla attack key (Viewpoint Advanced Movement moves '
        ..'Melee to Mouse 5): the press is left to the game; pick another stance button or rebind Melee under '
        ..'Options > Keybinds')
end

-- Viewpoint's first person with the mod's weapon (as the pose layer and hip fire, TW_HipFire).
function M.allowed(player)
    if not (player and player:isLocalPlayer() and player:getPlayerNum()==0 and not player:isDead()) then return false end
    if player:getVehicle() then return false end
    if not (TWZB and TWZB.vpState and TWZB.vpState()==1) then return false end
    -- W8 animopt: the ready this button raises to is our first-person pose; with it given to vanilla the press does
    -- nothing (no stance change either), so the key does not silently toggle an animation that is not shown.
    if Anim.blocks() then return false end
    local vp=rawget(_G,'TWViewpoint')
    if not vp or not vp.enabled() or (vp.stt() and vp.giveToStt()) then return false end
    return Carry.canCarry(player)
end

-- The stance a press lowers the weapon into: the selected one (the G key, the menu).
function M.target(player)
    local hold=rawget(_G,'TWTacHoldOverride')
    return hold and hold.mode and hold.mode(player) or Carry.preference(player)
end

-- One action: raise to the ready (leave the carry stance) or lower into the last stance. Returns the state
-- reached ('ready' or the stance) or nil when the weapon was already there; why is for TWTacHoldOverride
-- ('mouse', 'key').
local function apply(player,raise,why)
    local hold=rawget(_G,'TWTacHoldOverride')
    if not (hold and hold.isReady and hold.setReady) then return nil end
    if hold.isReady(player)==(raise==true) then return nil end
    hold.setReady(player,raise==true,why)
    M.toggles=M.toggles+1
    return raise and 'ready' or M.target(player)
end

-- Leave the carry stance: raise to the first-person ready (the key binding of W10 item 23).
function M.leave(player,why) return apply(player,true,why or 'key') end

-- Enter the last stance: lower from the ready into the stance selected last (the key binding of W10 item 23).
function M.enter(player,why) return apply(player,false,why or 'key') end

-- One press of the side mouse button: in the stance - raise to the ready; at the ready - back into the stance.
function M.toggle(player,why)
    local hold=rawget(_G,'TWTacHoldOverride')
    if not (hold and hold.isReady) then return nil end
    return apply(player,not hold.isReady(player),why or 'mouse')
end

function M.tick()
    local player=getSpecificPlayer(0)
    local ok,err=pcall(function()
        if not player then return end
        local index=M.button()
        local pressed=index~=nil and isMouseButtonDown(index)==true
        if pressed and not M.down then
            M.presses=M.presses+1
            if M.freeLookButton(index) then M.freeLookSkips=M.freeLookSkips+1
            elseif M.actionButton(index) then M.actionSkips=M.actionSkips+1;M.actionSkipOnce()
            elseif M.allowed(player) and not isGamePaused() then M.toggle(player) end
        end
        M.down=pressed
    end)
    if not ok then M.errors=M.errors+1;M.lastError=tostring(err) end
end
Events.OnTick.Add(M.tick)

-- Why a key press may not change the stance now (nil: it may). The carry key's own gates answer first (game
-- paused, aiming, a running or queued weapon action, chat text entry, the options that hand the stances or the
-- animations to vanilla, another menu), then the button's own view rule: the ready lives in Viewpoint's first
-- person only, so in the isometric and third-person views a key press changes nothing, exactly like the button.
function M.keyRefusal(player)
    local hold=rawget(_G,'TWTacHoldOverride')
    if hold and hold.refusal then
        local ok,why=pcall(hold.refusal,player,0)
        if ok and why then return why end
    end
    if not M.allowed(player) then return 'not Viewpoint first person' end
end

-- A refused key press is counted and its reason printed (at most every 5 s for the same reason), the way the
-- carry key says why it did nothing ([TW-CARRY]).
local function keySkip(why)
    M.keySkips=M.keySkips+1
    local now=getTimestampMs()
    if why~=M.lastKeyRefusal or not M.lastKeyRefusalAt or now-M.lastKeyRefusalAt>5000 then
        print('[TW-STANCE] stance key refused: '..why)
    end
    M.lastKeyRefusal,M.lastKeyRefusalAt=why,now
end

-- One press of a bound key: leave the carry stance (up to the ready) or enter the last stance (down from the
-- ready). The keys are matched with the mod's shared combination rules, so a binding may be Shift/Ctrl/Alt + key;
-- the event is the key's release, as every key of the mod reads it.
function M.key(key)
    local player=getSpecificPlayer(0)
    if not (player and player:isLocalPlayer()) then return end
    local enter=Combo.matches('StanceEnter',key)
    local leave=(not enter) and Combo.matches('StanceLeave',key)
    if not (enter or leave) then return end
    M.keyPresses=M.keyPresses+1
    if isGamePaused() then keySkip('game paused') return end
    local why=M.keyRefusal(player)
    if why then keySkip(why) return end
    local result
    if enter then result=M.enter(player,'key') else result=M.leave(player,'key') end
    M.lastKey=result or 'already'
end
local function onKey(key)
    local ok,err=pcall(M.key,key)
    if not ok then M.errors=M.errors+1;M.lastError=tostring(err) end
end
Events.OnKeyPressed.Add(onKey)

-- A binding's text for the QA log; an offline harness may not provide the key names (nil: no text).
local function keyText(id)
    local ok,text=pcall(function() return Combo.textOf(Combo.option(id)) end)
    return ok and tostring(text) or ''
end

function M.stats()
    local index=M.button()
    return string.format('presses=%d toggles=%d keys=%d keySkips=%d freeLookSkips=%d actionSkips=%d errors=%d button=%s '
        ..'enter="%s" leave="%s" freeLookKey=%s freeLookButton=%s actionKeys=%s lastKey=%s lastKeyRefusal=%s last=%s',
        M.presses,M.toggles,M.keyPresses,M.keySkips,M.freeLookSkips,M.actionSkips,M.errors,tostring(index),
        keyText('StanceEnter'),keyText('StanceLeave'),table.concat(M.freeLookCodes(),','),
        tostring(M.freeLookButton(index)),table.concat(M.actionCodes(),','),tostring(M.lastKey),
        tostring(M.lastKeyRefusal),M.lastError)
end

return M
