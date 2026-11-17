-- Hold-key radial menus of the mod's keys, the vanilla pattern (ISLightSourceRadialMenu,
-- ISFirearmRadialMenu): a short press does the key's own action when it is released; held
-- HOLD_MS the key opens the player's radial menu instead. The mouse picks a slice; releasing the
-- key closes the menu unless the game option "radial menu key toggle" is on (then the next press
-- closes it). An instant menu opens on the press itself and also stays open after a short tap.
-- A slice may open a sub-menu in the same radial (M.sub), as the Gunworks reload radial does.
-- Keyboard of the first local player only (the vanilla radials of keys do the same).
local M={HOLD_MS=500,defs={},state={},owner=nil}
TWHoldMenu=M

-- def: id; key() -> the bound key (0 = none); matches(key) optional -> does this key event belong to the
-- binding (a combination: TW_KeyCombo.matches); ready(player, key) -> may the menu or the tap run;
-- fill(menu, player) -> adds the slices; tap(player, key) optional; instant optional.
function M.register(def)
    for i,d in ipairs(M.defs) do
        if d.id==def.id then M.defs[i]=def;M.state[def.id]=M.state[def.id] or {};return def end
    end
    M.defs[#M.defs+1]=def
    M.state[def.id]={}
    return def
end

local function radial(playerNum)
    return getPlayerRadialMenu(playerNum or M.ownerPlayer or 0)
end

-- Free to open a menu: the game runs, the player lives, no text field or dragged item takes keys.
function M.free(player)
    if isGamePaused() or not player or player:isDead() then return false end
    if UIManager.getSpeedControls() and UIManager.getSpeedControls():getCurrentGameSpeed()==0 then return false end
    if ISChat and ISChat.instance and ISChat.instance.textEntry and ISChat.instance.textEntry:isFocused() then return false end
    if getCell() and getCell():getDrag(0) then return false end
    return true
end

local function centre(menu,playerNum)
    menu:setX(getPlayerScreenLeft(playerNum)+getPlayerScreenWidth(playerNum)/2-menu:getWidth()/2)
    menu:setY(getPlayerScreenTop(playerNum)+getPlayerScreenHeight(playerNum)/2-menu:getHeight()/2)
end

-- The radial is shared with the vanilla menus: it is ours while it shows the slice list we filled
-- (ISRadialMenu:clear starts a new list).
local function display(id,menu,player)
    local playerNum=player and player:getPlayerNum() or M.ownerPlayer or 0
    centre(menu,playerNum)
    menu:addToUIManager()
    M.owner,M.slices,M.ownerPlayer=id,menu.slices,playerNum
    M.players=M.players or {}
    M.players[playerNum]={owner=id,slices=menu.slices}
end

-- Fill and show a def's menu; false when it has no slices (nothing is shown then).
function M.open(def,player)
    local menu=radial(player and player:getPlayerNum())
    menu:clear()
    local ok,err=pcall(def.fill,menu,player)
    if not ok then print('[TW] radial menu '..tostring(def.id)..': '..tostring(err)) end
    if not ok or menu:isEmpty() then menu:clear();return false end
    display(def.id,menu,player)
    M.lastOpened=def.id
    return true
end

-- A slice's command: the same radial shows fill(menu, ...) instead (ISRadialMenu has already
-- hidden itself before the command runs).
function M.sub(id,fill,a,b,c,d)
    local menu=radial(a and a.getPlayerNum and a:getPlayerNum())
    menu:clear()
    local ok,err=pcall(fill,menu,a,b,c,d)
    if not ok then print('[TW] radial sub-menu '..tostring(id)..': '..tostring(err)) end
    if not ok or menu:isEmpty() then menu:clear();return end
    display(id,menu,a)
end

function M.visible(id,playerNum)
    playerNum=playerNum or 0
    local menu=radial(playerNum)
    if not menu or not menu:isReallyVisible() then return false end
    local owner=M.players and M.players[playerNum]
    return id==nil or (owner and owner.owner==id and menu.slices==owner.slices)
end

-- Does a key event belong to a def's binding? A combination asks its own matcher (TW_KeyCombo);
-- a plain def keeps the key code comparison.
local function keyed(def,key)
    if def.matches then return def.matches(key)==true end
    local bound=def.key and def.key()
    return bound~=nil and bound~=0 and bound==key
end

local function matching(key,player)
    for _,def in ipairs(M.defs) do
        if keyed(def,key) and def.ready(player,key) then return def end
    end
end

-- The key event in progress belongs to a menu (its release after opening one, or a press that
-- closed one): other handlers of the same key - Gunworks toggles the underbarrel on U, the stance
-- key - skip it (the carry key's own file guards that toggle).
function M.swallowing()
    if M.swallowAt and getTimestampMs()-M.swallowAt<100 then return true end
    for _,def in ipairs(M.defs) do
        local s=M.state[def.id]
        if s.pressed and (s.opened or s.wasVisible) then return true end
    end
    return false
end

function M.onPress(key)
    local player=getSpecificPlayer(0)
    for _,def in ipairs(M.defs) do
        if keyed(def,key) then
            local s=M.state[def.id]
            s.pressed,s.opened,s.wasVisible=nil,false,false
        end
    end
    if not M.free(player) then return end
    local def=matching(key,player)
    if not def then return end
    local s=M.state[def.id]
    s.pressed=getTimestampMs()
    if M.visible(def.id) and (def.instant or getCore():getOptionRadialMenuKeyToggle()) then
        s.wasVisible=true
        radial():removeFromUIManager()
        return
    end
    if def.instant then s.opened=M.open(def,player) end
end

function M.onRepeat(key)
    local player=getSpecificPlayer(0)
    for _,def in ipairs(M.defs) do
        local s=M.state[def.id]
        if s.pressed and not s.opened and not s.wasVisible and keyed(def,key)
            and getTimestampMs()-s.pressed>=M.HOLD_MS and not M.visible() then
            -- A long hold is never a tap, even when there is nothing to choose.
            s.opened=true
            if M.free(player) then M.open(def,player) end
        end
    end
end

function M.onRelease(key)
    local player=getSpecificPlayer(0)
    for _,def in ipairs(M.defs) do
        local s=M.state[def.id]
        if s.pressed and keyed(def,key) then
            local held=getTimestampMs()-s.pressed
            s.pressed=nil
            if s.opened or s.wasVisible then M.swallowAt=getTimestampMs() end
            if s.wasVisible then return end
            if s.opened then
                local short=def.instant and held<M.HOLD_MS
                if M.visible(def.id) and not short and not getCore():getOptionRadialMenuKeyToggle() then radial():removeFromUIManager() end
                return
            end
            if held<M.HOLD_MS and def.tap and M.free(player) then def.tap(player,key) end
            return
        end
    end
end

-- Slice texts: a name over a value line; the radial shows them in its centre on hover.
function M.text(...)
    local lines={}
    for _,v in ipairs({...}) do if v and v~='' then lines[#lines+1]=tostring(v) end end
    return table.concat(lines,'\n')
end

function M.texture(name)
    return getTexture('media/textures/TW_Radial_'..name..'.png')
end

local installed=false
local function install()
    if installed then return end
    installed=true
    Events.OnKeyStartPressed.Add(M.onPress)
    Events.OnKeyKeepPressed.Add(M.onRepeat)
    Events.OnKeyPressed.Add(M.onRelease)
end
Events.OnGameStart.Add(install)

return M
