-- Key combinations for every binding of the mod (user's item 6, 25.10.2026: "I can't really make complex
-- keybinds ... every time I try mixing ALT and T for the chamber check it just doesn't apply ... if we bind
-- SHIFT + T to switch sights it would just delete the SHIFT and stick with the T").
--
-- Vanilla B42 keeps a binding as a key plus shift/ctrl/alt: ISSetKeybindDialog:onKeyRelease takes the
-- modifier held while binding (one of the three), MainOptions.keyPressHandler stores it on the row,
-- MainOptions.writeKey saves it in keysB42.ini ("Name=key:84;shift:true") and registers the binding with
-- getCore():addKeyBinding(name, key, altCode, shift, ctrl, alt). Its matching (Core.invalidBindingShiftCtrl,
-- used by Core:isKey, GameKeyboard.isKeyDown(name) and isKeyPressed(name), behind the Lua isKeyDown(name) and
-- isShiftKeyDown/isCtrlKeyDown/isAltKeyDown) rejects a binding whose modifier is not held, makes a binding
-- on Shift/Ctrl/Alt itself work only while that key is held, and gives a key to the binding whose modifier
-- row equals the modifiers held when several bindings use that key.
--
-- The ModOptions key widget cannot keep that: its saved line is only "keybind|<page>|<id>|NN" and its screen
-- row is rebuilt from option.shift/option.ctrl (option.alt is not even read), so a combination lost its
-- modifier at the next open or restart. Our handlers compared the raw key event with the key code only, so a
-- combination never fired as one and its plain key fired without the modifier.
--
-- This module keeps the modifier row of every keybind option of this mod beside the key, in TWKeyCombos.ini
-- (same line syntax as keysB42.ini), registers the binding in the engine key map under a stable name, and
-- gives the handlers one matcher (matches / held) that answers with vanilla's own rules - including a vanilla
-- or other mod's binding of the same key: the combination wins while its modifiers are held, the plain key
-- stays usable when it is not part of another bound combination.
--
-- The modifier is captured like vanilla does: while binding, the modifier held is stored; the row is synced
-- back from the option when the options screen is built, so "ALT + T" is shown again next time. A binding is
-- checked when the key goes down, as the engine does: releasing a modifier before the key does not cancel an
-- already pressed combination (K.arm), the same way vanilla's own key checks behave on the press.
-- The options API of the game (loaded by the engine before mod files; the fallback keeps an offline harness
-- that already built PZAPI from being replaced).
if TWKeyCombo then return TWKeyCombo end
if not (PZAPI and PZAPI.ModOptions) then pcall(require,'PZAPI/ModOptions') end

local K={}
TWKeyCombo=K
K.VERSION=1
-- Options of this mod, in lookup order: the pages the modules add to, then the pages of the options layout.
K.PAGES={'EscapeFromZomboidFirearms','EscapeFromZomboidFirearmsCarry',
    'EscapeFromZomboidFirearmsControls','EscapeFromZomboidFirearmsEffects','EscapeFromZomboidFirearmsViewpoint'}
-- Engine key map name of a binding: stable, independent of the translated label the options screen uses.
K.PREFIX='EscapeFromZomboidFirearms.'
-- Our own file, in the same folder as ModOptions.ini and keysB42.ini; keysB42 syntax after the id.
K.FILE='TWKeyCombos.ini'
-- A trigger that is the mouse wheel rather than a key (user's item 14, 26.10.2026: "change the brightness
-- of the reticle (the dot) with a hotkey, default Left Alt + Left Shift + mouse wheel"). 20000 is neither an
-- LWJGL key code nor Mouse.BTN_OFFSET + n (mouse buttons are 10000+), so no engine binding or key event can
-- carry it; the wheel's own UI event hands the binding the notches (TW_SightInput), and this module keeps
-- its modifier row. The options screen keeps it as an ordinary keybind value, so the vanilla loader, the
-- options row and the save format stay unchanged.
K.WHEEL_CODE=20000
-- Bindings whose trigger is the wheel, with the default modifier row (the wheel needs at least one modifier:
-- a bare wheel must keep scrolling the hotbar). Rebound in the options screen: open the row, hold the wanted
-- modifiers and turn the wheel.
K.WHEEL_BINDINGS={ReticleBrightness={shift=true,ctrl=false,alt=true}}
-- The wheel's name in a binding row ("ALT + SHIFT + MOUSE WHEEL"); the engine has no key name for it.
K.WHEEL_KEY='UI_TW_MouseWheel'
K.armed={}
K.signatures={}
K.bound={}
K.stats={applied=0,migrated=0,stale=0,registered=0,presses=0,rows=0,install=0}

local function isTrue(value) return value==true end

-- The key code of an option: its own field in game; an option of an offline harness may only answer getValue.
local function keyOf(option)
    local key=tonumber(option and option.key)
    if key==nil and option and option.getValue then
        local ok,value=pcall(option.getValue,option)
        if ok then key=tonumber(value) end
    end
    return key or 0
end

local function usable(option) return option and (option.type=='keybind' or option.getValue~=nil) end

-- The keybind option of an id, from any of our pages (the options layout shares the option tables).
function K.option(id)
    local api=PZAPI and PZAPI.ModOptions
    if not (api and id) then return nil end
    for _,pageId in ipairs(K.PAGES) do
        local page=api.Dict and api.Dict[pageId]
        local option=page and page.dict and page.dict[id]
        if not option and api.getOptions then
            local ok,found=pcall(api.getOptions,api,pageId)
            if ok and found then
                option=found.dict and found.dict[id]
                if not option and found.getOption then
                    local okOption,foundOption=pcall(found.getOption,found,id)
                    if okOption then option=foundOption end
                end
            end
        end
        if usable(option) then return option,pageId end
    end
end

-- Every keybind option of this mod once: {id=,option=}, in a stable order (the pages, then the drawn data).
function K.keybinds()
    local out,seen={},{}
    local api=PZAPI and PZAPI.ModOptions
    if not (api and api.Dict) then return out end
    local function take(option,pageId)
        if option and option.type=='keybind' and option.id and not seen[option.id] then
            seen[option.id]=true
            out[#out+1]={id=option.id,option=option,page=pageId}
        end
    end
    for _,pageId in ipairs(K.PAGES) do
        local page=api.Dict[pageId]
        for _,option in ipairs(page and page.data or {}) do take(option,pageId) end
    end
    for _,page in ipairs(api.Data or {}) do
        for _,pageId in ipairs(K.PAGES) do
            if page.modOptionsID==pageId then
                for _,option in ipairs(page.data or {}) do take(option,pageId) end
            end
        end
    end
    return out
end

function K.nameFor(id) return K.PREFIX..tostring(id) end

-- "SHIFT + CTRL + ALT + T" in vanilla's prefix order; the key name as the options screen shows it.
-- A wheel trigger has no key name of its own: it is shown as the wheel, after the same modifier prefix.
function K.text(key,shift,ctrl,alt)
    local prefix=''
    if isTrue(shift) then prefix=prefix..'SHIFT + ' end
    if isTrue(ctrl) then prefix=prefix..'CTRL + ' end
    if isTrue(alt) then prefix=prefix..'ALT + ' end
    if (tonumber(key) or 0)==K.WHEEL_CODE then
        local ok,name=pcall(getText,K.WHEEL_KEY)
        return prefix..(ok and type(name)=='string' and name or 'MOUSE WHEEL')
    end
    return prefix..getKeyName(tonumber(key) or 0)
end

function K.textOf(option)
    if not option then return '' end
    return K.text(option.key,option.shift,option.ctrl,option.alt)
end

-- The text of an options row (the drawn row carries keyCode instead of key).
function K.rowText(row)
    if not row then return '' end
    return K.text(row.keyCode,row.shift,row.ctrl,row.alt)
end

function K.isWheel(key) return (tonumber(key) or 0)==K.WHEEL_CODE end

-- Raw key state: the engine's own modifier check reads GameKeyboard.isKeyDownRaw (the polled keyboard), not
-- the Lua isKeyDown that a focused text entry turns off; the vanilla bind dialog polls the same way
-- (Keyboard.isKeyDown in ISSetKeybindDialog). The Lua global stays the fallback of an offline harness.
local function rawDown(code)
    local poll
    if Keyboard then
        local ok,found=pcall(function() return Keyboard.isKeyDown end)
        if ok then poll=found end
    end
    if type(poll)=='function' then
        local ok,value=pcall(poll,code)
        if ok then return value==true end
    end
    local held=isKeyDown
    return code~=0 and type(held)=='function' and held(code)==true
end

-- Modifiers held now: the three globals of the engine (either side of each pair).
local function down(code) return code~=0 and rawDown(code) end
function K.modifiers()
    local shift=down(Keyboard.KEY_LSHIFT) or down(Keyboard.KEY_RSHIFT)
    local ctrl=down(Keyboard.KEY_LCONTROL) or down(Keyboard.KEY_RCONTROL)
    local alt=down(Keyboard.KEY_LMENU) or down(Keyboard.KEY_RMENU)
    return shift,ctrl,alt
end

-- Vanilla's rule for one binding when the engine key map cannot answer: a binding on Shift/Ctrl/Alt itself
-- needs that key held; a binding with a modifier needs it held.
function K.fallback(option)
    local key=keyOf(option)
    if key==0 then return false end
    local shift,ctrl,alt=K.modifiers()
    if key==Keyboard.KEY_LSHIFT or key==Keyboard.KEY_RSHIFT then return shift end
    if key==Keyboard.KEY_LCONTROL or key==Keyboard.KEY_RCONTROL then return ctrl end
    if key==Keyboard.KEY_LMENU or key==Keyboard.KEY_RMENU then return alt end
    if isTrue(option.shift) and not shift then return false end
    if isTrue(option.ctrl) and not ctrl then return false end
    if isTrue(option.alt) and not alt then return false end
    return true
end

-- Register an option's key and modifiers in the engine key map (idempotent; only when they changed).
-- A wheel trigger has no engine binding: the wheel is delivered to our UI layer, which asks wheelHeld.
function K.register(id)
    local option=K.option(id)
    if not option then return false end
    local key=keyOf(option)
    if K.isWheel(key) then
        K.signatures[id]=nil
        K.bound[id]=nil
        return false
    end
    local shift,ctrl,alt=isTrue(option.shift),isTrue(option.ctrl),isTrue(option.alt)
    local signature=key..':'..(shift and 1 or 0)..(ctrl and 1 or 0)..(alt and 1 or 0)
    if K.signatures[id]==signature then return K.bound[id]==true end
    local core=getCore and getCore()
    if not (core and core.addKeyBinding) then return false end
    core:addKeyBinding(K.nameFor(id),key,0,shift,ctrl,alt)
    K.signatures[id]=signature
    K.bound[id]=true
    K.stats.registered=K.stats.registered+1
    return true
end

-- The engine key map holds this binding, so its own rule can answer (an offline harness without the key map
-- map cannot: the modifier rule below answers there).
local function engineReady(id) return K.bound[id]==true end

function K.registerAll()
    local count=0
    for _,row in ipairs(K.keybinds()) do if K.register(row.id) then count=count+1 end end
    return count
end

-- The engine's answer for one binding and a key event (vanilla's Core:isKey): the key, the modifiers and
-- the tie-break between bindings that share the key.
local function engineMatches(id,key)
    local core=getCore and getCore()
    if not (core and core.isKey) then return nil end
    local ok,result=pcall(core.isKey,core,K.nameFor(id),key)
    if not ok then return nil end
    return result==true
end

-- A press of a bound combination: remember them for the release, as the engine checks the press.
function K.arm(key)
    K.armed={}
    K.stats.presses=K.stats.presses+1
    for _,row in ipairs(K.keybinds()) do
        if not K.isWheel(keyOf(row.option)) and keyOf(row.option)==key then
            K.register(row.id)
            local matched=engineReady(row.id) and engineMatches(row.id,key) or nil
            if matched==nil then matched=K.fallback(row.option) end
            if matched then K.armed[row.id]=true end
        end
    end
end

-- Does the key event (a release, as Events.OnKeyPressed gives it) belong to this action's binding?
function K.matches(id,key)
    local option=K.option(id)
    if not (option and key) then return false end
    local bound=keyOf(option)
    if bound==0 or K.isWheel(bound) or key~=bound then return false end
    K.register(id)
    if engineReady(id) then
        local matched=engineMatches(id,key)
        if matched~=nil then return matched or K.armed[id]==true end
    end
    return K.fallback(option)
end

-- Is the binding held now (a held key option: the zoom modifier, hold breath)? Same vanilla rule: the key
-- is down and the engine accepts its modifier row. A binding on Shift/Ctrl/Alt itself is held with that key,
-- either side of it (the engine's own modifier check reads both). A wheel trigger is never a held key.
function K.held(id)
    local option=K.option(id)
    if not option then return false end
    local key=keyOf(option)
    if key==0 or K.isWheel(key) then return false end
    local shift,ctrl,alt=K.modifiers()
    local down
    if key==Keyboard.KEY_LSHIFT or key==Keyboard.KEY_RSHIFT then down=shift
    elseif key==Keyboard.KEY_LCONTROL or key==Keyboard.KEY_RCONTROL then down=ctrl
    elseif key==Keyboard.KEY_LMENU or key==Keyboard.KEY_RMENU then down=alt
    else down=rawDown(key) end
    if not down then return false end
    K.register(id)
    if engineReady(id) then
        local matched=engineMatches(id,key)
        if matched~=nil then return matched==true end
    end
    return K.fallback(option)==true
end

-- The modifier row a wheel binding must keep: at least one modifier, so a bare wheel keeps scrolling the
-- hotbar. A wheel row without modifiers takes its default row again (also after the vanilla "Default" or
-- "Clear" buttons, which know nothing about modifiers).
function K.normalize(option)
    if not (option and option.id and K.isWheel(option.key)) then return false end
    if isTrue(option.shift) or isTrue(option.ctrl) or isTrue(option.alt) then return false end
    local default=K.WHEEL_BINDINGS[option.id]
    if not default then return false end
    option.shift,option.ctrl,option.alt=isTrue(default.shift),isTrue(default.ctrl),isTrue(default.alt)
    return true
end

-- Is the wheel trigger of this binding turning now? The exact modifier row must be held (all three), so a
-- gesture that also holds an extra modifier is not this binding; the wheel is never a key event.
function K.wheelHeld(id)
    local option=K.option(id)
    if not (option and K.isWheel(option.key)) then return false end
    local shift,ctrl,alt=K.modifiers()
    if not (shift or ctrl or alt) then return false end
    return isTrue(option.shift)==shift and isTrue(option.ctrl)==ctrl and isTrue(option.alt)==alt
end

-- One entry for changing a binding to the wheel: the options screen (a wheel turn while its row waits) or a
-- migration. At least one modifier is required.
function K.setWheel(id,shift,ctrl,alt)
    if not (isTrue(shift) or isTrue(ctrl) or isTrue(alt)) then return false end
    return K.set(id,K.WHEEL_CODE,shift,ctrl,alt)
end

-- One entry for changing a binding: the options screen, a migration or a QA case. nil modifier = false.
function K.set(id,key,shift,ctrl,alt)
    local option=K.option(id)
    if not option then return false end
    option.key=tonumber(key) or 0
    option.shift=isTrue(shift)
    option.ctrl=isTrue(ctrl)
    option.alt=isTrue(alt)
    K.normalize(option)
    K.syncRow(option)
    K.register(id)
    return true
end

-- The option's row on the options screen (MainOptions.keyText is what vanilla saves and compares).
function K.syncRow(option)
    local row=option and option.element
    if not (row and row.keyCode~=nil) then return false end
    row.keyCode=tonumber(option.key) or 0
    row.shift=isTrue(option.shift)
    row.ctrl=isTrue(option.ctrl)
    row.alt=isTrue(option.alt)
    if K.isWheel(row.keyCode) and not (row.shift or row.ctrl or row.alt) then
        local default=option.id and K.WHEEL_BINDINGS[option.id]
        if default then
            row.shift,row.ctrl,row.alt=isTrue(default.shift),isTrue(default.ctrl),isTrue(default.alt)
        end
    end
    if row.btn and row.btn.setTitle then row.btn:setTitle(K.rowText(row)) end
    return true
end

-- The rows of the options screen from the options (called after the panel is built: vanilla shows only the
-- key name for a mod bind, and reads no alt field, so the row is completed here).
function K.decorate()
    local screen=rawget(_G,'MainOptions')
    if not (screen and screen.keyText and type(screen.keyText)=='table') then return 0 end
    local byLabel={}
    for _,row in ipairs(K.keybinds()) do
        local name=getText and getText(row.option.name) or row.option.name
        if name then byLabel[name]=row.option end
    end
    local count=0
    for _,row in ipairs(screen.keyText) do
        if row.isModBind and row.txt and row.txt.getName then
            local option=byLabel[row.txt:getName()]
            if option then
                option.element=row
                K.syncRow(option)
                count=count+1
            end
        end
    end
    K.stats.rows=count
    return count
end

-- Before the vanilla save: the drawn rows are the values to keep (vanilla does this for the key itself).
function K.capture()
    local count=0
    for _,row in ipairs(K.keybinds()) do
        local option=row.option
        local screen=option.element
        if screen and screen.keyCode~=nil then
            option.key=tonumber(screen.keyCode) or 0
            option.shift=isTrue(screen.shift)
            option.ctrl=isTrue(screen.ctrl)
            option.alt=isTrue(screen.alt)
            if K.normalize(option) then K.syncRow(option) end
            count=count+1
        end
    end
    return count
end

-- TWKeyCombos.ini: "Id=key:20;shift:false;ctrl:false;alt:true", the keysB42 line syntax.
function K.readCombos()
    local out={}
    pcall(function()
        local file=getFileReader(K.FILE,false)
        if not file then return end
        while true do
            local line=file:readLine()
            if line==nil then break end
            local id,rest=string.match(line,'^([%w_]+)=(.*)$')
            local key=id and tonumber(string.match(rest,'key:(%-?%d+)'))
            if key then
                out[id]={key=key,shift=string.find(rest,'shift:true',1,true)~=nil,
                    ctrl=string.find(rest,'ctrl:true',1,true)~=nil,alt=string.find(rest,'alt:true',1,true)~=nil}
            end
        end
        file:close()
    end)
    return out
end

function K.writeCombos()
    local ok,err=pcall(function()
        local file=getFileWriter(K.FILE,true,false)
        file:write('VERSION='..K.VERSION..'\r\n')
        for _,row in ipairs(K.keybinds()) do
            local option=row.option
            file:write(row.id..'=key:'..(tonumber(option.key) or 0)
                ..';shift:'..(isTrue(option.shift) and 'true' or 'false')
                ..';ctrl:'..(isTrue(option.ctrl) and 'true' or 'false')
                ..';alt:'..(isTrue(option.alt) and 'true' or 'false')..'\r\n')
        end
        file:close()
    end)
    K.lastWriteError=ok and nil or tostring(err)
    return ok
end

-- keysB42.ini of the game, read once per load: a combination bound before this module existed is kept
-- there under the option's label (MainOptions.writeKey writes every row, mod rows included).
function K.readKeysB42()
    if K.keysB42 then return K.keysB42 end
    local out={}
    pcall(function()
        local file=getFileReader('keysB42.ini',false)
        if not file then return end
        while true do
            local line=file:readLine()
            if line==nil then break end
            local name,rest=string.match(line,'^([^=]+)=(.*)$')
            local key=rest and tonumber(string.match(rest,'key:(%-?%d+)'))
            if name and key then
                out[name]={key=key,shift=string.find(rest,'shift:true',1,true)~=nil,
                    ctrl=string.find(rest,'ctrl:true',1,true)~=nil,alt=string.find(rest,'alt:true',1,true)~=nil}
            end
        end
        file:close()
    end)
    K.keysB42=out
    return out
end

function K.migrate(option)
    local name=getText and getText(option.name) or option.name
    local line=name and K.readKeysB42()[name]
    if not line then return nil end
    if line.key~=(tonumber(option.key) or 0) then return nil end
    if not (line.shift or line.ctrl or line.alt) then return nil end
    return line
end

-- After the vanilla loader: the saved modifier row of each key (or, once, the row keysB42 kept).
function K.applySaved()
    local saved=K.readCombos()
    K.stats.applied=K.stats.applied+1
    K.stats.migrated,K.stats.stale=0,0
    for _,row in ipairs(K.keybinds()) do
        local option=row.option
        local entry=saved[row.id]
        local key=tonumber(option.key) or 0
        if entry and entry.key==key then
            option.shift,option.ctrl,option.alt=entry.shift,entry.ctrl,entry.alt
        elseif entry then
            option.shift,option.ctrl,option.alt=false,false,false
            K.stats.stale=K.stats.stale+1
        else
            local line=K.migrate(option)
            if line then
                option.shift,option.ctrl,option.alt=line.shift,line.ctrl,line.alt
                K.stats.migrated=K.stats.migrated+1
            else
                option.shift,option.ctrl,option.alt=false,false,false
            end
        end
        K.normalize(option)
        K.register(row.id)
    end
    return K.stats.migrated
end

-- The keybind options get their modifier row and the two methods a caller uses; the pages are shared by the
-- options layout, so this runs on the option objects themselves.
function K.prepare()
    local count=0
    for _,row in ipairs(K.keybinds()) do
        local option=row.option
        if option.shift==nil then option.shift=false end
        if option.ctrl==nil then option.ctrl=false end
        if option.alt==nil then option.alt=false end
        K.normalize(option)
        if not option.setModifiers then
            option.setModifiers=function(self,shift,ctrl,alt)
                self.shift=isTrue(shift);self.ctrl=isTrue(ctrl);self.alt=isTrue(alt)
                K.syncRow(self);K.register(self.id)
            end
        end
        if not option.modifiers then
            option.modifiers=function(self) return isTrue(self.shift),isTrue(self.ctrl),isTrue(self.alt) end
        end
        if not option.setValueWasCombo then
            -- A plain key set through the options API is a plain binding: stale modifiers of the old key go.
            local setValue=option.setValue
            option.setValue=function(self,key)
                if setValue then pcall(setValue,self,key) end
                if self.key~=key then self.key=key end
                self.shift,self.ctrl,self.alt=false,false,false
                K.normalize(self)
                K.syncRow(self);K.register(self.id)
            end
            option.setValueWasCombo=true
        end
        count=count+1
    end
    return count
end

-- Re-register after the game rebuilt its key maps: MainOptions.loadKeys and MainOptions.saveKeys call
-- getCore():reinitKeyMaps(), which clears every binding (vanilla and mods), then add the vanilla ones back.
function K.reload()
    K.signatures={}
    K.bound={}
    return K.registerAll()
end

-- The option of a bind dialog: the dialog keeps the translated label of the options row.
function K.optionByLabel(label)
    if not label then return nil end
    for _,row in ipairs(K.keybinds()) do
        local name=getText and getText(row.option.name) or row.option.name
        if name==label then return row.option end
    end
end

-- A wheel binding's bind dialog: a wheel turn (or a key pressed while a modifier is held) sets the wheel
-- trigger with the modifiers held. A bare key without a modifier is ignored and the dialog waits: a wheel
-- binding must keep at least one modifier, and a plain key has no direction for a brightness step.
local function attachWheel(dialog)
    if not (dialog and dialog.keybindName) then return false end
    local option=K.optionByLabel(dialog.keybindName)
    if not (option and K.WHEEL_BINDINGS[option.id]) then return false end
    dialog.onMouseWheel=function(self,delta)
        local shift,ctrl,alt=K.modifiers()
        if not (shift or ctrl or alt) then
            K.stats.wheelIgnored=(K.stats.wheelIgnored or 0)+1
            return true
        end
        if MainOptions and MainOptions.keyPressHandler then
            pcall(MainOptions.keyPressHandler,K.WHEEL_CODE,shift,ctrl,alt)
        end
        return true
    end
    dialog.twWheelBinding=option.id
    K.stats.wheelDialogs=(K.stats.wheelDialogs or 0)+1
    return true
end
K.attachWheel=attachWheel

local function installHooks()
    local api=PZAPI and PZAPI.ModOptions
    if not (api and api.load and api.save) then return false end
    if not api.keyComboLoadHooked then
        local load=api.load
        api.load=function(self,...)
            local result=load(self,...)
            pcall(K.prepare)
            pcall(K.applySaved)
            return result
        end
        api.keyComboLoadHooked=true
    end
    if not api.keyComboSaveHooked then
        local save=api.save
        api.save=function(self,...)
            pcall(K.capture)
            local result=save(self,...)
            pcall(K.writeCombos)
            pcall(K.registerAll)
            K.keysB42=nil
            return result
        end
        api.keyComboSaveHooked=true
    end
    local screen=rawget(_G,'MainOptions')
    if not (screen and screen.keyPressHandler) then return false end
    if not screen.keyComboPressHooked then
        local press=screen.keyPressHandler
        screen.keyPressHandler=function(key,shift,ctrl,alt)
            local dialog=screen.setKeybindDialog
            local name=dialog and dialog.keybindName
            if dialog and dialog.twWheelBinding and not K.isWheel(key) then
                if not (isTrue(shift) or isTrue(ctrl) or isTrue(alt)) then
                    K.stats.wheelIgnored=(K.stats.wheelIgnored or 0)+1
                    return
                end
                key=K.WHEEL_CODE
            end
            local result=press(key,shift,ctrl,alt)
            if name then
                pcall(function()
                    if dialog and dialog.twWheelBinding then
                        pcall(K.capture)
                        pcall(K.registerAll)
                    end
                    for _,row in ipairs(screen.keyText or {}) do
                        if row.isModBind and row.txt and row.txt.getName and row.txt:getName()==name then
                            if row.btn and row.btn.setTitle then row.btn:setTitle(K.rowText(row)) end
                            return
                        end
                    end
                end)
            end
            return result
        end
        screen.keyComboPressHooked=true
    end
    if screen.onKeyBindingBtnPress and not screen.keyComboWheelHooked then
        local open=screen.onKeyBindingBtnPress
        screen.onKeyBindingBtnPress=function(self,button)
            local result=open(self,button)
            pcall(attachWheel,rawget(screen,'setKeybindDialog'))
            return result
        end
        screen.keyComboWheelHooked=true
    end
    -- The screen rebuilds every binding title from the key code in onKeyboardLayoutChanged (called by toUI
    -- after the mods panel), which would drop the modifier row and show the engine's name of the wheel code.
    -- Our rows are completed again right after it.
    if screen.onKeyboardLayoutChanged and not screen.keyComboLayoutHooked then
        local layout=screen.onKeyboardLayoutChanged
        screen.onKeyboardLayoutChanged=function(...)
            local result=layout(...)
            pcall(K.prepare)
            pcall(K.decorate)
            return result
        end
        screen.keyComboLayoutHooked=true
    end
    if screen.addModOptionsPanel and not screen.keyComboPanelHooked then
        local panel=screen.addModOptionsPanel
        screen.addModOptionsPanel=function(self,...)
            local result=panel(self,...)
            pcall(K.prepare)
            pcall(K.decorate)
            return result
        end
        screen.keyComboPanelHooked=true
    end
    -- The game's key-map reinitialisation wipes our names; put them back right after it.
    for _,name in ipairs({'loadKeys','saveKeys'}) do
        local original=screen[name]
        local flag='keyCombo'..name..'Hooked'
        if type(original)=='function' and not screen[flag] then
            screen[name]=function(...)
                local result=original(...)
                pcall(K.reload)
                return result
            end
            screen[flag]=true
        end
    end
    return true
end

-- The first keybind id of the mod (the table exists once the modules that add keys are loaded).
function K.firstOption()
    for _,pageId in ipairs(K.PAGES) do
        local page=PZAPI.ModOptions.Dict[pageId]
        for _,option in ipairs(page and page.data or {}) do
            if option.type=='keybind' then return option.id end
        end
    end
end

function K.install()
    if not (PZAPI and PZAPI.ModOptions and PZAPI.ModOptions.Dict) then return false end
    if not K.firstOption() then return false end
    K.prepare()
    installHooks()
    K.stats.install=K.stats.install+1
    return true
end

function K.statsText()
    return string.format('keybinds=%d registered=%d applied=%d migrated=%d stale=%d presses=%d rows=%d install=%d',
        #K.keybinds(),K.stats.registered,K.stats.applied,K.stats.migrated,K.stats.stale,K.stats.presses,K.stats.rows,K.stats.install)
end

-- The raw row of an option ("key:20 shift:false ...") for QA logs.
function K.describe(id)
    local option=K.option(id)
    if not option then return id..'=? missing' end
    return string.format('%s=key:%d shift:%s ctrl:%s alt:%s text:"%s" name:%s',id,tonumber(option.key) or 0,
        tostring(isTrue(option.shift)),tostring(isTrue(option.ctrl)),tostring(isTrue(option.alt)),K.textOf(option),K.nameFor(id))
end

local function armedByKey(key) K.arm(key) end
if Events and Events.OnKeyStartPressed then Events.OnKeyStartPressed.Add(armedByKey) end
if Events and Events.OnGameBoot then
    Events.OnGameBoot.Add(function()
        pcall(K.install)
        pcall(K.prepare)
        pcall(K.registerAll)
    end)
end
if Events and Events.OnMainMenuEnter then Events.OnMainMenuEnter.Add(function() pcall(K.install) end) end
if Events and Events.OnGameStart then
    Events.OnGameStart.Add(function()
        pcall(K.install)
        pcall(K.prepare)
        pcall(K.registerAll)
    end)
end
pcall(K.install)

return K
