-- W10 rack (the user's item 7, 27.10.2026): "Затвор иногда автоматически не передёргивается при полном опустошении
-- магазина и патронника". The option "rack the bolt automatically after a reload", on by default (today's behaviour),
-- and the two reconcilers that make the automatic rack of the mod's own reloads reliable (TW/AutoRack.lua holds the
-- state and the decision).
--
-- The vanilla rack after a reload is queued inside the reload action (ISInsertMagazine:loadAmmo in single player and
-- its perform() on a multiplayer client, ISReloadWeaponAction:loadAmmo round by round; Gunworks replaces the bodies of
-- those two with the same conditions). Each of them adds the rack with ISTimedActionQueue.addAfter(self, ...) - which
-- silently does nothing when the action is no longer in the queue - and only when its own condition holds at the
-- instant the animation event arrives. When it does not, the weapon keeps a loaded magazine, an empty chamber and no
-- rack: the player's report. TW/AutoRack.lua is asked at the end of both actions:
--   option on   the reload completed, the chamber is empty, there are rounds to chamber and no rack is queued => rack;
--   option off  the rack this reload has just queued behind itself (it has not started yet) is taken out again, so the
--               chamber stays empty and the player racks by hand, like Tarkov.
-- A rack from anywhere else - the pump or bolt cycle after a shot (ISReloadWeaponAction.OnPlayerAttackFinished), the
-- player's own rack key, a jam - is never touched. The option is a client setting: nothing is synchronised and the
-- chamber state travels exactly as before.
--
-- The rack after a shot (the player's report of 30.10.2026: pump shotguns and bolt-action rifles no longer racked
-- after each shot). Another mod can take the game's after-shot rack away - Hardship: Inertia's sandbox option
-- "Manual cycling" (on by default) removes the game's listener of OnPlayerAttackFinished. With the option on, the
-- mod's weapons that rack after a shot get that one rack back: the listener below notes the attack, and on the next
-- tick (every listener of the event has run by then) TW/AutoRack queues the rack when the chamber holds no live round
-- and none is queued. With the option off nothing is added and the game and other mods decide.
require 'PZAPI/ModOptions'
require('TW_Tactical') -- creates the shared page the options are added to
local State=require('TW/AutoRack')
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')
local M={applied=0,loaded=false,errors=0,lastError='',installed=false,active=true}
TWAutoRackOptions=M

if options then
    options:addTickBox('AutoRack',getText('UI_TW_AutoRack'),true,getText('UI_TW_AutoRack_tooltip'))
end

function M.option() return options and options:getOption('AutoRack') end

-- The option value; an option the page does not hold (a save from before this option, a truncated page) means the
-- default, on, which is the behaviour the mod had.
function M.value()
    local o=M.option()
    return o==nil or o:getValue()==true
end

-- The state TW/AutoRack and the reconcilers read.
function M.apply(force)
    local on=M.value()
    if not force and State.enabled==on then return false end
    State.set(on)
    M.applied=M.applied+1
    print(string.format('[TW-AUTORACK] automatic rack after a reload=%s',tostring(on)))
    return true
end

-- A value saved by the options screen is restored before that screen is opened: the vanilla loader
-- (PZAPI.ModOptions:load) runs only when the Mods tab is built, so without this a restart forgot the choice.
function M.loadSaved()
    if M.loaded then return 0 end
    M.loaded=true
    local saved
    pcall(function()
        local file=getFileReader('ModOptions.ini',true)
        if not file then return end
        while true do
            local line=file:readLine()
            if line==nil then break end
            local id,value=string.match(line,'^[^|]*|[^|]*|([^|]*)|(.*)$')
            if id=='AutoRack' then saved=value end
        end
        file:close()
    end)
    local o=M.option()
    if saved==nil or o==nil then return 0 end
    local want=saved=='true'
    if o:getValue()~=want then
        o:setValue(want)
        M.apply(true)
        print('[TW-AUTORACK] restored from ModOptions.ini: '..tostring(want))
        return 1
    end
    return 0
end

-- QA: set the real option value (the Mods tab and the reconcilers agree), then push it to the state.
function M.set(on)
    local o=M.option()
    if o then o:setValue(on==true) end
    return M.apply(true)
end

-- The reconcile of one completed reload (called from both actions' perform(), see below).
local function apply(action,insert)
    if not State.enabled then State.dropQueuedRack(action);return end
    State.afterReload(action,insert)
end

-- The functions this module replaced, so QA can measure the behaviour without it in the same build (before/after).
local originals={}

local function wrap(class,insert)
    if not class or class.twAutoRack~=nil then return end
    class.twAutoRack=insert==true
    local perform=class.perform
    originals[#originals+1]={class=class,perform=perform}
    class.perform=function(self,...)
        if not M.active then return perform(self,...) end
        -- The option off: the rack this reload has just queued behind itself never starts (perform runs while the
        -- reload is still the current action).
        if not State.enabled then pcall(State.dropQueuedRack,self) end
        local result=perform(self,...)
        local ok,err=pcall(apply,self,insert)
        if not ok then
            State.errors=State.errors+1
            if M.errors<3 then M.errors=M.errors+1;M.lastError=tostring(err);print('[TW-AUTORACK] '..M.lastError) end
        end
        return result
    end
end

function M.install()
    M.active=true
    if M.installed then return end
    M.installed=true
    pcall(require,'TimedActions/ISInsertMagazine')
    pcall(require,'TimedActions/ISReloadWeaponAction')
    pcall(require,'TimedActions/ISRackFirearm')
    wrap(rawget(_G,'ISInsertMagazine'),true)
    wrap(rawget(_G,'ISReloadWeaponAction'),false)
end

-- QA only: the vanilla and Gunworks reload actions exactly as they are without this module (the wrappers stay in the
-- chain of the other mods' wrappers, so they hand every call straight through).
function M.uninstall()
    M.active=false
end

-- QA: "enabled=true installed=true active=true applied=1 errors=0 state=enabled=true racks=0 dropped=0"
function M.stats()
    return string.format('enabled=%s installed=%s active=%s applied=%d errors=%d state=%s',tostring(M.value()),
        tostring(M.installed),tostring(M.active),M.applied,M.errors,State.stats())
end

-- The attack-finished listener of the rack after a shot (see the header): it only notes the attack.
function M.onAttackFinished(player,weapon)
    if not M.active then return end
    local ok,err=pcall(State.noteShot,player,weapon)
    if not ok then
        State.errors=State.errors+1
        if M.errors<3 then M.errors=M.errors+1;M.lastError=tostring(err);print('[TW-AUTORACK] '..M.lastError) end
    end
end

local function tick()
    local ok,err=pcall(M.apply)
    if not ok then
        M.errors=M.errors+1
        if M.lastError~=tostring(err) then M.lastError=tostring(err);print('[TW-AUTORACK] off '..M.lastError) end
    end
    if M.active then pcall(State.reconcileShots) else State.pending={} end
end

Events.OnTick.Add(tick)
Events.OnPlayerAttackFinished.Add(M.onAttackFinished)
Events.OnGameBoot.Add(function() pcall(M.loadSaved);M.install();tick() end)
Events.OnGameStart.Add(function() pcall(M.loadSaved);M.install();tick() end)
if Events.OnMainMenuEnter then Events.OnMainMenuEnter.Add(tick) end
M.install()

return M
