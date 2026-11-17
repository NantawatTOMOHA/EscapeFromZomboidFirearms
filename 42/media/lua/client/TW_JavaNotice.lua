-- A notice when this mod's Java part did not load (user request 23.10.2026: ZombieBuddy had kept a denial of the
-- updated JAR, and in game the sights, lasers and frame rate broke without a word). TWZB is the Java part's Lua class:
-- absent once the game has started, a dialog says what does not work and how to allow it (Shift held while the game
-- loads opens ZombieBuddy's approval window). Once per game start; the console gets the same in one line.
if isServer() then return end

local N={}
TWJavaNotice=N
N.DELAY_MS=1500
N.shown=0

local function warningsEnabled()
    local options=rawget(_G,'TWWarningOptions')
    if options then return options.enabled() end
    -- HostModsNotice can run this file alone after a server omitted our Lua. There is no options
    -- module or require path then. Read only our saved boolean; any missing/unreadable data keeps
    -- the warning enabled. Do not load client gameplay modules into this bootstrap Lua state.
    local file
    local ok,value=pcall(function()
        file=getFileReader('ModOptions.ini',false)
        if not file then return true end
        local saved=true
        while true do
            local line=file:readLine()
            if line==nil then break end
            local kind,page,id,v=string.match(line,'^([^|]*)|([^|]*)|([^|]*)|(.*)$')
            if (page=='EscapeFromZomboidFirearms' or page=='EscapeFromZomboidFirearmsCompat')
                and id=='ShowWarnings' and (v=='true' or v=='false') then saved=v=='true' end
        end
        return saved
    end)
    if file then pcall(function() file:close() end) end
    return not ok or value~=false
end

local function zombieBuddy()
    if rawget(_G,'ZombieBuddy')~=nil then return true end
    local mods=getActivatedMods and getActivatedMods()
    if mods then
        for i=0,mods:size()-1 do
            if (tostring(mods:get(i)):gsub('^\\',''))=='ZombieBuddy' then return true end
        end
    end
    return false
end

-- nil: the Java part is loaded; 'blocked': ZombieBuddy runs but did not load it; 'missing': no ZombieBuddy.
function N.state()
    if type(rawget(_G,'TWZB'))=='table' then return nil end
    return zombieBuddy() and 'blocked' or 'missing'
end

function N.text(state)
    local cause,fix='UI_TW_JavaBlocked','UI_TW_JavaAllow'
    if state=='missing' then cause,fix='UI_TW_JavaMissing','UI_TW_JavaEnable' end
    return ' <RGB:1,0.45,0.35> <SIZE:medium> '..getText('UI_TW_JavaTitle')..' <LINE> <LINE> <RGB:1,1,1> <SIZE:small> '
        ..getText(cause)..' <LINE> <LINE> '..getText('UI_TW_JavaLost')..' <LINE> <LINE> <RGB:1,0.85,0.4> '..getText(fix)
        ..' <LINE> <LINE> <RGB:0.8,0.8,0.8> '..getText('UI_TW_WarningsHint')
end

function N.dismiss()
    for _,key in ipairs({'modal','serverModsModal'}) do
        local modal=N[key]
        if modal then modal:setVisible(false);modal:removeFromUIManager();N[key]=nil end
    end
end

-- The dialog for state (default: the current one); false when the Java part is loaded.
function N.show(state)
    state=state or N.state()
    if not state then return false end
    print('[TW] Java part not loaded ('..state..'): '..getText(state=='missing' and 'UI_TW_JavaMissing' or 'UI_TW_JavaBlocked'))
    if not warningsEnabled() then N.dismiss();return false end
    local sw,sh=getCore():getScreenWidth(),getCore():getScreenHeight()
    local w,h=math.min(820,sw-80),math.min(460,sh-80)
    local modal=ISModalRichText:new((sw-w)/2,(sh-h)/2,w,h,N.text(state),false,nil,nil,0)
    modal:initialise()
    modal:addToUIManager()
    modal:setAlwaysOnTop(true)
    N.modal=modal
    N.shown=N.shown+1
    return true
end

-- After the start's fade: the first tick past DELAY_MS shows the dialog when the Java part is absent.
local due
local function tick()
    if not due or getTimestampMs()<due then return end
    due=nil
    Events.OnTick.Remove(tick)
    N.show()
end
Events.OnGameStart.Add(function()
    if not N.state() then return end
    due=getTimestampMs()+N.DELAY_MS
    Events.OnTick.Add(tick)
end)

-- The other way round (W10 task host): the Java part runs, but the joined server's mod list has no Escape From Zomboid
-- Firearms, so none of its Lua loaded (a Host server with an empty "Steam Workshop" list). HostModsNotice.java then
-- runs this file itself and passes the texts from the mod's Translate files (the game has not loaded them): title,
-- cause, host, dedicated. The window comes DELAY_MS after the call, like the one above.
function N.serverModsText(t)
    return ' <RGB:1,0.45,0.35> <SIZE:medium> '..t.title..' <LINE> <LINE> <RGB:1,1,1> <SIZE:small> '..t.cause
        ..' <LINE> <LINE> <RGB:1,0.85,0.4> '..t.host..' <LINE> <LINE> <RGB:1,1,1> '..t.dedicated
        ..' <LINE> <LINE> <RGB:0.8,0.8,0.8> '..(t.hint or getText('UI_TW_WarningsHint'))
end

function N.showServerMods(t)
    N.serverMods=t
    local at=getTimestampMs()+N.DELAY_MS
    local function show()
        if getTimestampMs()<at then return end
        Events.OnTick.Remove(show)
        print('[TW] server without the mod: '..t.cause..' '..t.host..' '..t.dedicated)
        if not warningsEnabled() then N.dismiss();return end
        local sw,sh=getCore():getScreenWidth(),getCore():getScreenHeight()
        local w,h=math.min(900,sw-80),math.min(480,sh-80)
        local modal=ISModalRichText:new((sw-w)/2,(sh-h)/2,w,h,N.serverModsText(t),false,nil,nil,0)
        modal:initialise()
        modal:addToUIManager()
        modal:setAlwaysOnTop(true)
        N.serverModsModal=modal
        N.shown=N.shown+1
    end
    Events.OnTick.Add(show)
    return true
end

return N
