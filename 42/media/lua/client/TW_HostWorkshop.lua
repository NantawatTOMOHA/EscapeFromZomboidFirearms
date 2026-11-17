-- Host from the main menu: the server's WorkshopItems from its Mods (W10 task host; players 26.10.2026: "on a Host
-- server only vanilla weapons"). A server started with Host loads Workshop mods only from its own WorkshopItems
-- (B42.21: SteamWorkshop.GetInstalledItemFolders returns GameServer.workshopInstallFolders on a server; the player's
-- subscriptions are not seen), and B42.21 never fills that list: choosing mods writes only Mods
-- (ServerSettingsScreen.lua:3075) and setDefaultsFromSingleplayer returns at once. Mod Load Order Sorter [b42]
-- (Workshop 3423660713) replaces Next of "Choose mods" on OnMainMenuEnter and writes both lists itself, keeping only
-- mods it finds an ID for.
--
-- When: Next of "Choose mods" (vanilla, or MLOS after its own write), SAVE of the server settings, START of the Host
-- screen (the settings chosen there, saved before or edited by hand in the ini). What: the Workshop item of every mod
-- of the server's Mods= and of their require= dependencies, recursively (the engine loads those without a Mods entry,
-- loadModAndRequired, but only where it finds their folder). The ID: getWorkshopID(), else the folder 108600\<id>\,
-- else for this mod's own mods TW/Data/Hosting.workshopId (a local copy). Appended after the IDs already there:
-- manual maps and items stay, the order is kept, nothing repeats; Mods= is never changed. A mod without an ID is
-- reported (the server finds it only in this computer's Zomboid/mods or Zomboid/Workshop and other players cannot
-- download it), and so is the weapon pack without its server module. Local mod data only: no Steam query, no network.
--
-- The buttons are wrapped where they are, whoever set their handler (MLOS sets its own on OnMainMenuEnter): after the
-- main menu's first frame and again whenever the Host or the settings screen opens. A Next that reloads the Lua
-- (ResetLua "serversettings") is finished by OnResetLua of the reloaded Lua, which has this file when the server's mods
-- include this mod.
if isServer() then return end
local Data=require 'TW/Data/Hosting'

local H={faults=0,lastError='',shown={},fills=0}
TWHostWorkshop=H
H.data=Data

local function log(text) print('[TW] host workshop: '..text) end

function H.safe(tag,fn,...)
    local ok,result=pcall(fn,...)
    if ok then return result end
    H.faults=H.faults+1
    H.lastError=tag..': '..tostring(result)
    log(H.lastError)
    return nil
end

local function trim(s) return (string.gsub(tostring(s or ''),'^%s*(.-)%s*$','%1')) end

-- A mod ID as Mods=, require= and the mod lists hold it: "\ModID" of B42 is the same mod.
function H.modId(s) return (string.gsub(trim(s),'^\\+','')) end

-- 'a;b;;c' -> {'a','b','c'}
function H.split(s)
    local out={}
    for part in string.gmatch(tostring(s or ''),'[^;]+') do
        local v=trim(part)
        if v~='' then out[#out+1]=v end
    end
    return out
end

local own={}
for _,id in ipairs(Data.ownMods) do own[id]=true end

-- The Workshop item of a mod, or nil (not installed, or a local copy of another mod).
function H.workshopId(id)
    local info=getModInfoByID(id)
    if info then
        local w=info:getWorkshopID()
        if w and string.match(tostring(w),'^%d+$') then return tostring(w) end
        local dir=info:getDir()
        local m=dir and string.match(tostring(dir),'108600[\\/](%d+)[\\/]')
        if m then return m end
    end
    if own[id] then return Data.workshopId end
    return nil
end

-- The mods of `mods` (IDs in order, without repeats) followed by their require= dependencies, breadth first.
function H.closure(mods)
    local order,seen,viaRequire={},{},{}
    local function add(id,dep)
        id=H.modId(id)
        if id=='' or seen[id] then return end
        seen[id]=true
        order[#order+1]=id
        if dep then viaRequire[id]=true end
    end
    for _,id in ipairs(mods) do add(id,false) end
    local i=1
    while i<=#order do
        local info=getModInfoByID(order[i])
        local req=info and info:getRequire()
        if req then
            for j=0,req:size()-1 do add(req:get(j),true) end
        end
        i=i+1
    end
    return order,viaRequire
end

-- What the server needs: mods (a list of Mods= entries), items (the WorkshopItems= string) ->
-- {items=<new WorkshopItems string>, added={...}, missing={mod IDs without an ID}, noServer=<the weapon pack without
-- its server module>, closure, viaRequire}.
function H.plan(mods,items)
    local closure,viaRequire=H.closure(mods)
    local result,have={},{}
    for _,w in ipairs(H.split(items)) do
        result[#result+1]=w
        have[w]=true
    end
    local added,missing={},{}
    for _,id in ipairs(closure) do
        local w=H.workshopId(id)
        if not w then missing[#missing+1]=id
        elseif not have[w] then
            have[w]=true
            result[#result+1]=w
            added[#added+1]=w
        end
    end
    local listed={}
    for _,id in ipairs(mods) do listed[H.modId(id)]=true end
    return {items=table.concat(result,';'),added=added,missing=missing,closure=closure,viaRequire=viaRequire,
        noServer=listed[Data.mod]==true and not listed[Data.serverMod]}
end

local function option(settings,name) return settings:getServerOptions():getOptionByName(name) end

local function describe(plan)
    return string.format('%d mods (%d by require=), +%d: %s',#plan.closure,(function()
        local n=0
        for _ in pairs(plan.viaRequire) do n=n+1 end
        return n
    end)(),#plan.added,plan.items)
end

-- The settings files of `name`: WorkshopItems completed from their own Mods, saved when it grew. nil when there are
-- no files yet (a new name: B42 writes defaults with no mods).
function H.fillFiles(name,why)
    if not name or name=='' then return nil end
    local manager=getServerSettingsManager()
    if manager:isValidNewName(name) then return nil end
    local settings=ServerSettings.new(name)
    if not settings:isValid() or not settings:loadFiles() then return nil end
    local plan=H.plan(H.split(option(settings,'Mods'):getValue()),option(settings,'WorkshopItems'):getValue())
    if #plan.added>0 then
        option(settings,'WorkshopItems'):setValue(plan.items)
        settings:saveFiles()
        H.fills=H.fills+1
    end
    log(why..' "'..name..'" files: '..describe(plan))
    return plan
end

local function panel(page,kind)
    for _,p in ipairs(page.customui or {}) do
        if p.Type==kind then return p end
    end
    return nil
end

-- The settings page being edited: the "Steam Workshop" page gets the IDs of the mods of the "Mods" page (what SAVE
-- writes), the settings object the same list.
function H.fillPage(page,why)
    local settings=page and page.settings
    local modsPanel=page and panel(page,'ServerSettingsScreenModsPanel')
    local shop=page and panel(page,'ServerSettingsScreenWorkshopPanel')
    if not (settings and modsPanel and shop and modsPanel.listbox and shop.listbox) then return nil end
    local mods={}
    for _,row in ipairs(modsPanel.listbox.items) do mods[#mods+1]=row.item.modID end
    local plan=H.plan(mods,shop:itemListToString())
    for _,w in ipairs(plan.added) do shop:addItemToList(w) end
    if #plan.added>0 then
        option(settings,'WorkshopItems'):setValue(shop:itemListToString())
        H.fills=H.fills+1
    end
    log(why..' "'..tostring(settings:getName())..'" page: '..describe(plan))
    return plan
end

-- The player's notice: mods without a Workshop item, the weapon pack without its server module. Once per settings and
-- content in this Lua.
function H.warn(plan,name)
    if not plan then return false end
    local parts={}
    if #plan.missing>0 then parts[#parts+1]=getText('UI_TW_HostNoWorkshopId',table.concat(plan.missing,', ')) end
    if plan.noServer then parts[#parts+1]=getText('UI_TW_HostNoServerModule',Data.mod,Data.serverMod) end
    if #parts==0 then return false end
    local key=tostring(name)..'|'..table.concat(plan.missing,';')..'|'..tostring(plan.noServer)
    if H.shown[key] then return false end
    H.shown[key]=true
    log('notice "'..tostring(name)..'": no Workshop item: '..table.concat(plan.missing,';')..'; server module missing: '
        ..tostring(plan.noServer))
    local text=' <RGB:1,0.85,0.4> <SIZE:medium> '..getText('UI_TW_HostTitle')..' <LINE> <LINE> <RGB:1,1,1> <SIZE:small> '
        ..table.concat(parts,' <LINE> <LINE> ')
    local sw,sh=getCore():getScreenWidth(),getCore():getScreenHeight()
    local w,h=math.min(820,sw-80),math.min(420,sh-80)
    local modal=ISModalRichText:new((sw-w)/2,(sh-h)/2,w,h,text,false,nil,nil,0)
    modal:initialise()
    modal:addToUIManager()
    modal:setAlwaysOnTop(true)
    H.modal=modal
    return true
end

-- The notice of a Next covers the mods chosen in the window too: MLOS leaves the ones without an ID out of Mods.
local function withChosen(plan,window)
    if not (plan and window and window.listbox) then return plan end
    local chosen={}
    for _,row in ipairs(window.listbox.items) do chosen[#chosen+1]=row.item and row.item.modID end
    local seen={}
    for _,id in ipairs(plan.missing) do seen[id]=true end
    for _,id in ipairs(H.plan(chosen,'').missing) do
        if not seen[id] then
            seen[id]=true
            plan.missing[#plan.missing+1]=id
        end
    end
    return plan
end

-- Next of "Choose mods": the handler (vanilla or MLOS) has set the mods. Without a Lua reload the page is open now;
-- after a reload the old Lua stops here and OnResetLua below finishes in the new one.
function H.afterNext(window)
    local page=window and window.parent and window.parent.pageEdit
    if not (page and page:getIsVisible()) then return end
    local name=page.settings and page.settings:getName()
    H.fillFiles(name,'next')
    H.warn(withChosen(H.fillPage(page,'next'),window),name)
end

function H.beforeSave(page)
    H.fillPage(page,'save')
end

function H.beforeStart()
    local name=CoopConnection and CoopConnection.servername
    local plan=H.fillFiles(name,'start')
    -- A Host server never runs the server module's Java (W10 srvlive): its absence turns nothing off there.
    if plan then plan.noServer=false end
    H.warn(plan,name)
end

-- The onclick of `button` runs `before` and `after` around the handler it has now; a handler set later is wrapped the
-- next time.
local function wrap(button,tag,before,after)
    if not button or type(button.onclick)~='function' or button.twHostWrapper==button.onclick then return false end
    local inner=button.onclick
    local function wrapper(target,...)
        if before then H.safe(tag,before,target) end
        inner(target,...)
        if after then H.safe(tag,after,target) end
    end
    button.onclick=wrapper
    button.twHostWrapper=wrapper
    return true
end

-- W10 srvlive marked the Live values of "Server ballistics" "dedicated servers only" here. W11 hostauth (user 28.10.2026):
-- Live works on a Host server too (the shooters' clients fly their rounds, the server checks them), so the settings
-- editor leaves the option as the sandbox file describes it. Kept as the hook's step, now changing nothing.
H.ballisticsOption='EscapeFromZomboidFirearms.ViewpointBallistics'
function H.ballisticsNote(page)
    return false
end

function H.hook()
    local n=0
    local screen=ServerSettingsScreen and ServerSettingsScreen.instance
    local page=screen and screen.pageEdit
    if page then
        H.safe('ballistics',H.ballisticsNote,page)
        if wrap(page.chooseModsWindow and page.chooseModsWindow.buttonAccept,'next',nil,H.afterNext) then n=n+1 end
        if wrap(page.buttonAccept,'save',H.beforeSave,nil) then n=n+1 end
    end
    local host=CoopOptionsScreen and CoopOptionsScreen.instance
    if host and wrap(host.startButton,'start',H.beforeStart,nil) then n=n+1 end
    H.hooked=(H.hooked or 0)+n
    return n
end

local function rehookAfter(class,method)
    if not (class and type(class[method])=='function') then return end
    local f=class[method]
    class[method]=function(self,...)
        f(self,...)
        H.safe('hook',H.hook)
    end
end

-- The first main-menu frame comes after every OnMainMenuEnter handler (MLOS sets its buttons there).
local function firstFrame()
    Events.OnFETick.Remove(firstFrame)
    H.safe('hook',H.hook)
end

-- The page of a Next that reloaded the Lua: vanilla's OnResetLua handler opens it; when it has not yet, the next frame.
local function finishNext(name,tries)
    local screen=ServerSettingsScreen and ServerSettingsScreen.instance
    local page=screen and screen.pageEdit
    if page and page:getIsVisible() and page.settings and page.settings:getName()==name then
        H.warn(H.fillPage(page,'next'),name)
        return true
    end
    return false
end

function H.onResetLua(reason)
    reason=tostring(reason or '')
    if string.sub(reason,1,20)~='ServerSettingsChange' then return end
    local name=string.match(reason,'=(.*)$')
    if not name then return end
    H.safe('next',H.fillFiles,name,'next')
    if H.safe('next',finishNext,name) then return end
    local frames=0
    local function later()
        frames=frames+1
        if H.safe('next',finishNext,name) or frames>=10 then Events.OnFETick.Remove(later) end
    end
    Events.OnFETick.Add(later)
end

function H.install()
    if H.installed then return end
    H.installed=true
    rehookAfter(ServerSettingsScreen,'aboutToShow')
    rehookAfter(CoopOptionsScreen,'aboutToShow')
    Events.OnMainMenuEnter.Add(function() Events.OnFETick.Add(firstFrame) end)
    Events.OnResetLua.Add(H.onResetLua)
end

H.install()

return H
