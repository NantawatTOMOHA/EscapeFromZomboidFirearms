local O={}
TWGamepadOptions=O

local function trueText(value) return value and 'true' or 'false' end
local function label(G,def)
    local option=G.options:getOption(def.id)
    local bound=option and option.binding or def
    local text=G.BUTTONS[bound.primary].label
    if bound.modifier then text=(bound.modifier=='LT' and 'LT' or G.BUTTONS[bound.modifier].label)..' + '..text end
    return getText(def.label)..': '..text
end
local function nativeConflict(G,def,bound)
    if bound.modifier or def.context=='builder' then return nil end
    local primary=JoypadButton[G.BUTTONS[bound.primary].enum]
    if CharacterJoypadButtonBinding and CharacterJoypadButtonBinding.allBindings then
        for _,native in ipairs(CharacterJoypadButtonBinding.allBindings()) do
            if native:getJoypadButton()==primary then return native:getNameTranslationKey() end
        end
    end
    if bound.primary=='Back' or bound.primary=='Start' or bound.primary:match('^DPad') then return bound.primary end
end
function O.conflicts(G,def)
    local option=G.options:getOption(def.id)
    local bound=option and option.binding or def
    local out={}
    for _,other in ipairs(G.actions) do
        if other.id~=def.id then
            local row=G.options:getOption(other.id)
            local value=row and row.binding or other
            if value.primary==bound.primary and value.modifier==bound.modifier then out[#out+1]=getText(other.label) end
        end
    end
    local native=nativeConflict(G,def,bound)
    if native then out[#out+1]=native end
    return out
end
function O.refresh(G)
    for _,def in ipairs(G.actions) do
        local option=G.options:getOption(def.id)
        if option then
            local conflicts=O.conflicts(G,def)
            option.name=label(G,def)..(#conflicts>0 and ' [!]' or '')
            option.tooltip=#conflicts>0 and getText('UI_TW_GamepadConflict',table.concat(conflicts,', ')) or getText('UI_TW_GamepadBind_tooltip')
            if option.element then
                option.element:setTitle(option.name)
                option.element:setTooltip(option.tooltip)
            end
        end
    end
end
function O.save(G)
    local ok,err=pcall(function()
        local file=getFileWriter(G.FILE,true,false)
        file:write('VERSION=1\r\n')
        for _,def in ipairs(G.actions) do
            local option=G.options:getOption(def.id)
            local bound=option and option.binding or def
            file:write(def.id..'=primary:'..bound.primary..';modifier:'..tostring(bound.modifier)..'\r\n')
        end
        file:close()
    end)
    O.lastError=ok and nil or tostring(err)
    return ok
end
function O.load(G)
    local saved={}
    pcall(function()
        local file=getFileReader(G.FILE,false)
        if not file then return end
        while true do
            local line=file:readLine()
            if not line then break end
            local id,primary,modifier=line:match('^([%w_]+)=primary:(%w+);modifier:(%w+)$')
            if id and G.BUTTONS[primary] and (modifier=='nil' or modifier=='LT' or G.BUTTONS[modifier]) then
                saved[id]={primary=primary,modifier=modifier~='nil' and modifier or nil}
            end
        end
        file:close()
    end)
    for _,def in ipairs(G.actions) do
        local option=G.options:getOption(def.id)
        option.binding=saved[def.id] or {primary=def.primary,modifier=def.modifier}
    end
    O.refresh(G)
end
function O.set(G,id,bound)
    if not G.byId[id] or not G.BUTTONS[bound.primary] then return false end
    if bound.modifier and bound.modifier~='LT' and not G.BUTTONS[bound.modifier] then return false end
    G.options:getOption(id).binding={primary=bound.primary,modifier=bound.modifier}
    O.refresh(G);O.save(G)
    return true
end
function O.reset(G)
    for _,def in ipairs(G.actions) do G.options:getOption(def.id).binding={primary=def.primary,modifier=def.modifier} end
    O.refresh(G);O.save(G)
end

local function closeCapture(G)
    local capture=G.capture
    G.capture=nil
    if not capture then return end
    if capture.panel then capture.panel:removeFromUIManager() end
    if capture.joypad and capture.previousFocus~=nil then setJoypadFocus(capture.joypad.player,capture.previousFocus)
    elseif capture.joypad then setJoypadFocus(capture.joypad.player,nil) end
end
local function captureDialog(G,def)
    local panel=ISPanel:new(0,0,560,150)
    panel:initialise()
    panel:setX(getCore():getScreenWidth()/2-panel:getWidth()/2)
    panel:setY(getCore():getScreenHeight()/2-panel:getHeight()/2)
    panel.backgroundColor={r=0.05,g=0.05,b=0.05,a=0.97}
    panel.borderColor={r=0.8,g=0.8,b=0.8,a=1}
    function panel:render()
        ISPanel.render(self)
        self:drawTextCentre(getText('UI_TW_GamepadCapture'),self.width/2,24,1,1,1,1,UIFont.Small)
        self:drawTextCentre(getText(def.label),self.width/2,55,1,1,1,1,UIFont.Small)
        local capture=G.capture
        local text=capture and capture.first and G.BUTTONS[capture.first].label or '...'
        self:drawTextCentre(text,self.width/2,88,1,1,1,1,UIFont.Medium)
    end
    function panel:onMouseDown(x,y)
        if x<0 or y<0 or x>self.width or y>self.height then closeCapture(G) end
        return true
    end
    panel:addToUIManager();panel:setAlwaysOnTop(true);panel:bringToTop()
    return panel
end
function O.capture(G,id)
    local def=G.byId[id]
    if not def then return end
    closeCapture(G)
    local joypad=JoypadState.getMainMenuJoypad and JoypadState.getMainMenuJoypad() or JoypadState.players[1]
    G.capture={def=def,joypad=joypad,previousFocus=joypad and joypad.focus or nil}
    G.capture.panel=captureDialog(G,def)
    if joypad then setJoypadFocus(joypad.player or 0,G.capture.panel) end
end
function O.capturePress(G,controller,value)
    local capture=G.capture
    if not capture then return false end
    local name
    for _,row in ipairs(G.BUTTONS) do if value==JoypadButton[row.enum] then name=row.name;break end end
    if not name then return true end
    if not capture.first then capture.first=name;return true end
    if name==capture.first then return true end
    O.set(G,capture.def.id,{primary=name,modifier=capture.first})
    closeCapture(G)
    return true
end
function O.captureRelease(G,controller,value)
    local capture=G.capture
    if not capture then return false end
    for _,row in ipairs(G.BUTTONS) do
        if value==JoypadButton[row.enum] and row.name==capture.first then
            O.set(G,capture.def.id,{primary=capture.first})
            closeCapture(G)
            return true
        end
    end
    return true
end

function O.install(G)
    G.capturePress=function(controller,value) return O.capturePress(G,controller,value) end
    G.captureRelease=function(controller,value) return O.captureRelease(G,controller,value) end
    if G.options then O.load(G);return end
    G.options=PZAPI.ModOptions:create(G.PAGE,'UI_TW_OptionsPageGamepad')
    G.options:addTitle('UI_TW_OptionsPageGamepad')
    G.options:addDescription(getText('UI_TW_GamepadHelp'))
    for _,def in ipairs(G.actions) do
        G.options:addButton(def.id,label(G,def),getText('UI_TW_GamepadBind_tooltip'),O.capture,G,def.id)
    end
    G.options:addButton('GamepadDefaults',getText('UI_TW_GamepadDefaults'),getText('UI_TW_GamepadDefaults_tooltip'),O.reset,G)
    O.load(G)
    if not PZAPI.ModOptions.twGamepadLoadHook then
        local load=PZAPI.ModOptions.load
        PZAPI.ModOptions.load=function(self,...)
            local result=load(self,...)
            O.load(G)
            return result
        end
        PZAPI.ModOptions.twGamepadLoadHook=true
    end
end
return O
