local B={}
TWGamepadBuilder=B
local G

local function add(id,label,primary)
    local def={id=id,key=id,label=label,primary=primary,context='builder'}
    G.actions[#G.actions+1]=def;G.byId[id]=def
end
local function windowFor(player)
    local window=player and TWAssemblyWindow and TWAssemblyWindow.instances[player:getPlayerNum()]
    return window and window:getIsVisible() and window or nil
end
local function bound(def)
    local option=G.options and G.options:getOption(def.id)
    return option and option.binding or def
end
local function modifierDown(def,controller)
    local modifier=bound(def).modifier
    if not modifier then return true end
    if modifier=='LT' then
        if G.qaLT[controller.id]~=nil then return G.qaLT[controller.id]==true end
        return isJoypadLTPressed and isJoypadLTPressed(controller.id)==true or false
    end
    return G.down[controller.id] and G.down[controller.id][modifier]==true or false
end
local function preview(window,cell)
    window.grid.hoverCell=cell
    if cell and cell.entry then window:previewCandidate(cell.entry) end
end
local function navigate(window,direction)
    if window.grid:getIsVisible() then
        local cells=window.grid:cells()
        if #cells==0 then return end
        local index=0
        for i,cell in ipairs(cells) do
            if cell==window.grid.hoverCell then index=i;break end
        end
        local step=(direction=='DPadLeft' or direction=='DPadUp') and -1 or 1
        preview(window,cells[(index+step)%#cells+1])
        return
    end
    local slots=window.slots or {}
    if #slots==0 then return end
    local index=0
    for i,slot in ipairs(slots) do if slot.path==window.selectedPath then index=i;break end end
    local step=(direction=='DPadLeft' or direction=='DPadUp') and -1 or 1
    window:selectSlot(slots[(index+step)%#slots+1].path)
end
local function accept(window)
    if window.grid:getIsVisible() then
        local cell=window.grid.hoverCell or window.grid:cells()[1]
        if cell then window:chooseFromGrid(cell.remove and {remove=true} or cell.entry) end
        return
    end
    for _,cell in ipairs(window.layer.cells or {}) do
        if cell.key==window.selectedPath then window:openGrid(cell);preview(window,window.grid:cells()[1]);return end
    end
    window:applyCandidate()
end
local function run(window,id)
    if id=='GamepadBuilderAccept' then accept(window)
    elseif id=='GamepadBuilderBack' then
        if window.grid:getIsVisible() then window.grid:close() else window:close() end
    elseif id=='GamepadBuilderRemove' then window:removeSelected()
    elseif id=='GamepadBuilderConfirm' then
        if window.grid:getIsVisible() then accept(window) else window:applyCandidate() end
    elseif id=='GamepadBuilderZoomOut' then
        window.scene:setZoomLevel(window.scene.zoom+1);window.scene.userView=true;window.scene:changed()
    elseif id=='GamepadBuilderZoomIn' then
        window.scene:setZoomLevel(window.scene.zoom-1);window.scene.userView=true;window.scene:changed()
    else navigate(window,id:gsub('GamepadBuilder','DPad')) end
end
function B.press(controller,value,player)
    local window=windowFor(player)
    if not window then return false end
    for _,def in ipairs(G.actions) do
        if def.context=='builder' and value==JoypadButton[G.BUTTONS[bound(def).primary].enum] and modifierDown(def,controller) then
            run(window,def.id)
            return true
        end
    end
    return true
end
function B.focus(window)
    local index=window.playerIndex
    local player=getSpecificPlayer(index)
    if player and type(player.getJoypadBind)=='function' and player:getJoypadBind()>=0 then setJoypadFocus(index,window) end
end
function B.unfocus(window)
    local index=window.playerIndex
    local player=getSpecificPlayer(index)
    if player and type(player.getJoypadBind)=='function' and player:getJoypadBind()>=0 and getFocusForPlayer(index)==window then setPrevFocusForPlayer(index) end
end
function B.update(window)
    local player=window.player
    if not player or type(player.getJoypadBind)~='function' or player:getJoypadBind()<0
        or getFocusForPlayer(window.playerIndex)~=window then return end
    local id=player:getJoypadBind()
    local lx,ly=getJoypadMovementAxisX(id),getJoypadMovementAxisY(id)
    local rx,ry=getJoypadAimingAxisX(id),getJoypadAimingAxisY(id)
    local changed=false
    if math.abs(lx)>0.5 or math.abs(ly)>0.5 then window.scene:rotateBy(lx,ly);changed=true end
    if math.abs(rx)>0.5 or math.abs(ry)>0.5 then window.scene:pan(rx,ry);changed=true end
    if changed then window.scene.userView=true;window.scene:changed() end
end
function B.install(gamepad)
    G=gamepad
    add('GamepadBuilderAccept','UI_TW_GamepadBuilderAccept','A')
    add('GamepadBuilderBack','UI_TW_GamepadBuilderBack','B')
    add('GamepadBuilderRemove','UI_TW_GamepadBuilderRemove','X')
    add('GamepadBuilderConfirm','UI_TW_GamepadBuilderConfirm','Y')
    add('GamepadBuilderZoomOut','UI_TW_GamepadZoomOut','LB')
    add('GamepadBuilderZoomIn','UI_TW_GamepadZoomIn','RB')
    add('GamepadBuilderDPadUp','UI_TW_GamepadBuilderUp','DPadUp')
    add('GamepadBuilderDPadDown','UI_TW_GamepadBuilderDown','DPadDown')
    add('GamepadBuilderDPadLeft','UI_TW_GamepadBuilderLeft','DPadLeft')
    add('GamepadBuilderDPadRight','UI_TW_GamepadBuilderRight','DPadRight')
end
return B
