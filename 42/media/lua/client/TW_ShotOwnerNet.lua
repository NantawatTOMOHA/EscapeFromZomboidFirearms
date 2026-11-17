local N=require('TW/ShotOwnerNet')
-- MP live owner transport. Java (TWZB.shotNet*) owns the peer; this file only carries strings.
local nextHello=0
local function zb(name,...)
    local api=rawget(_G,'TWZB')
    if type(api)~='table' or not api[name] then return nil end
    local ok,value=pcall(api[name],...)
    return ok and value or nil
end
-- W11 hostauth: the shooter-side owner of a server without the mod's Java (ShotClientLua): the server answered Hello
-- with Authority, this client flies its claimed rounds and sends each round's contacts as Hits rows.
local function client(name,...)
    local api=rawget(_G,'ShotClientLua')
    if type(api)~='table' or not api[name] then return nil end
    local ok,value=pcall(api[name],...)
    return ok and value or nil
end
N.results={accepted=0,refused=0,last='none'}
-- Live: 3 (Viewpoint first person) or 4 (Viewpoint and isometric), the server's sandbox value.
local function wanted()
    local sandbox=SandboxVars and SandboxVars.EscapeFromZomboidFirearms
    local mode=sandbox and sandbox.ViewpointBallistics
    return mode==3 or mode==4
end
-- The server's notice "this server runs without the mod's Java part" (server TW_ShotOwnerNet.lua N.notice, W10
-- srvlive): asked every 5 s until the server answers (commands sent while the game is still connecting are lost; an
-- older server never answers, so at most 60 asks), shown once per session.
-- W11 javanotice (the user 29.10.2026): the notice was a modal window in the middle of the screen that held the mouse,
-- so a player who had just loaded could not see the crosshair or shoot and died to zombies. It is now a small banner
-- under the top edge of the screen and nothing more: a plain ISPanel (never ISModal*), no keyboard events, no joypad
-- focus, no drag, no "always on top", and no mouse consumption outside its small close button, so the game keeps
-- walking and shooting under it. It closes by itself after N.NOTICE_MS (20 s), does not come back in the session, and
-- is shown only to the player it is meant for: the Host player, or a dedicated server's admin (the only ones who can
-- install the server module). Every other player never sees it and never asks the server. The whole text stays in the
-- console log, line by line; the banner itself is short.
local notice={asked=0,next=0,answered=false}
local PAD=12
N.noticeShown=nil
N.NOTICE_MS=20000
N.noticeDismissed=nil

-- Who the notice is for: the Host player (isCoopHost), or this client's role on a dedicated server (the game's own
-- isAdmin: zombie.Lua.LuaManager compares the connection's role with the admin role). Returns allowed, host.
function N.noticeAudience()
    local ok,host=pcall(function() return type(isCoopHost)=='function' and isCoopHost()==true end)
    if ok and host==true then return true,true end
    -- A dedicated server's admin by the player's own access level, as the server side checks it (isAccessLevel): B42.21's
    -- isAdmin() is a role-object identity test (the connection's role == Roles.getDefaultForAdmin()) that is false for a
    -- dedicated server's admin (W11 integration stand: isAdmin=false, accessLevel=admin). isAdmin() stays a further OR.
    local admin,p=false,nil
    pcall(function() p=getSpecificPlayer and getSpecificPlayer(0) end)
    if p then
        ok,admin=pcall(function() return p:isAccessLevel('admin')==true end)
        if ok and admin==true then return true,false end
        ok,admin=pcall(function() local level=p:getAccessLevel();return type(level)=='string' and string.lower(level)=='admin' end)
        if ok and admin==true then return true,false end
    end
    ok,admin=pcall(function() return type(isAdmin)=='function' and isAdmin()==true end)
    return (ok and admin==true)==true,false
end
function N.noticeAllowed() return (N.noticeAudience()) end

-- The banner's own class: an ISPanel, not an ISModal*: ISPanelJoypad and the modal windows take the joypad focus and
-- swallow clicks, this one does not. ISUIElement:new gives every element wantMouseEvents=true; the banner sets it back
-- before instantiate, so only its close button takes its own click.
local Banner=ISPanel and ISPanel:derive('TWServerJavaBanner')
N.noticeBannerClass=Banner
N.noticeBanner=nil

local function wrap(font,text,width,tm)
    local out,line={},''
    for word in tostring(text or ''):gmatch('%S+') do
        local try=line=='' and word or (line..' '..word)
        if line~='' and tm:MeasureStringX(font,try)>width then out[#out+1]=line;line=word else line=try end
    end
    if line~='' then out[#out+1]=line end
    return out
end

if Banner then
    function Banner:new(x,y,width,height)
        local o=ISPanel:new(x,y,width,height)
        setmetatable(o,self)
        self.__index=self
        o.backgroundColor={r=0.04,g=0.04,b=0.04,a=0.86}
        o.borderColor={r=1,g=0.78,b=0.36,a=0.9}
        o.moveWithMouse=false
        o.wantMouseEvents=false
        o.wantKeyEvents=false
        o.pad=PAD
        o.rows={}
        return o
    end

    function Banner:createChildren()
        ISPanel.createChildren(self)
        local size=18
        local close=ISButton:new(self.width-self.pad-size,self.pad-4,size,size,'',self,Banner.onClose)
        close:initialise()
        close.borderColor.a=0
        close.backgroundColor.a=0
        close.backgroundColorMouseOver.a=0.3
        if getTexture then close:setImage(getTexture('media/ui/inventoryPanes/Button_Close.png')) end
        if getText then close:setTooltip(getText('UI_TW_ServerJavaHint')) end
        self:addChild(close)
        self.closeButton=close
    end

    function Banner:onClose()
        N.dismissNotice('closed')
    end

    function Banner:prerender()
        ISPanel.prerender(self)
        local y=self.pad
        for _,row in ipairs(self.rows) do
            if row.text~='' then self:drawText(row.text,self.pad,y,row.r,row.g,row.b,1,row.font) end
            y=y+row.height+row.gap
        end
    end
end

-- The banner of one notice: the title, the lead, the lines the server sent and where the whole text is; centred
-- horizontally, its top edge 1.5% below the screen's top and its bottom well above the crosshair.
local function buildBanner(lead,texts)
    local tm=getTextManager()
    local sw,sh=getCore():getScreenWidth(),getCore():getScreenHeight()
    local width=math.min(560,sw-48)
    local textWidth=width-2*PAD-20
    local rows={}
    local function add(text,font,r,g,b,gap)
        for _,line in ipairs(wrap(font,text,textWidth,tm)) do
            rows[#rows+1]={text=line,font=font,height=tm:getFontHeight(font),gap=gap,r=r,g=g,b=b}
        end
    end
    add(getText('UI_TW_ServerJavaTitle'),UIFont.Medium,1,0.85,0.4,4)
    add(getText(lead),UIFont.Small,1,1,1,2)
    for _,text in ipairs(texts) do add(text,UIFont.Small,0.95,0.86,0.55,2) end
    add(getText('UI_TW_ServerJavaHint'),UIFont.Small,0.7,0.7,0.7,0)
    local height=2*PAD
    for _,row in ipairs(rows) do height=height+row.height+row.gap end
    local panel=Banner:new(math.floor((sw-width)/2),math.max(8,math.floor(sh*0.015)),width,math.max(56,height))
    panel.rows=rows
    panel.untilMs=getTimestampMs()+N.NOTICE_MS
    panel:initialise()
    panel:addToUIManager()
    return panel
end

function N.dismissNotice(why)
    local panel=N.noticeBanner
    N.noticeBanner=nil
    if not panel then return false end
    N.noticeDismissed=why or 'closed'
    panel:setVisible(false)
    panel:removeFromUIManager()
    return true
end

function N.showNotice(lines)
    local keys,texts={},{}
    for i=1,16 do
        local key=type(lines)=='table' and lines[i] or nil
        if type(key)~='string' then break end
        if string.sub(key,1,6)=='UI_TW_' then keys[#keys+1]=key end
    end
    local allowed,host=N.noticeAudience()
    -- The Host player always gets it (coordinator's Host audit: a Host server never runs the mod's Java, whatever the
    -- option says); the others when the server sends lines (Live set, the module listed, its Java absent).
    if host and #keys==0 then for _,key in ipairs(N.javaFeatures) do keys[#keys+1]=key end end
    if #keys==0 then return false end
    if not allowed then return false end
    N.noticeLead=host and 'UI_TW_HostJavaWhy' or 'UI_TW_ServerJavaWhy'
    for i,key in ipairs(keys) do texts[i]=getText(key) end
    N.noticeShown=table.concat(keys,',')
    -- The whole text stays in the log: the banner is short by design.
    print('[TW] server notice: '..N.noticeLead..' '..N.noticeShown)
    print('[TW] server notice: '..getText('UI_TW_ServerJavaTitle'))
    print('[TW] server notice: '..getText(N.noticeLead))
    for _,text in ipairs(texts) do print('[TW] server notice: '..text) end
    print('[TW] server notice: '..getText('UI_TW_ServerJavaHint'))
    N.dismissNotice('replaced')
    local warnings=rawget(_G,'TWWarningOptions')
    if warnings and not warnings.enabled() then return true end
    if not (Banner and getCore and getTextManager and UIFont) then return true end
    local ok,panel=pcall(buildBanner,N.noticeLead,texts)
    if not ok then print('[TW] server notice: banner failed: '..tostring(panel));return true end
    N.noticeBanner=panel
    N.noticeDismissed=nil
    return true
end
-- Kept for QA: one Hello now, as the periodic request below would send it.
function N.request(player)
    if not isClient() or not player then return false end
    local c=zb('shotNetChallenge')
    if type(c)~='string' then return false end
    local challenge,shot=c:match('^(.-)|(%d+)$')
    if not challenge then return false end
    sendClientCommand(player,N.module,'Hello',{challenge=challenge,shot=tonumber(shot)})
    return true
end
Events.OnServerCommand.Add(function(module,command,args)
    if module~=N.module or type(args)~='table' then return end
    if command=='Notice' then
        if not notice.answered then notice.answered=true;N.showNotice(args.lines) end
        return
    end
    if command=='Baseline' then
        N.active=false;N.reason=tostring(args.reason or 'server-adapter-unavailable')
        zb('shotNetBaseline',N.reason);nextHello=getTimestampMs()+30000;return
    end
    -- Stage 3: the server asks this thrower to fly its frag grenade's fragments (rows go out as Hits).
    if command=='Fragments' then
        local status=client('fragments',tostring(args.id),tostring(args.tpl),tostring(args.fragment),tonumber(args.count) or 0,
            tonumber(args.seed) or 0,tonumber(args.x) or 0,tonumber(args.y) or 0,tonumber(args.z) or 0)
        N.fragments={id=args.id,status=tostring(status)}
        print('[TW-SHOT] fragments '..tostring(args.id)..' '..tostring(status))
        return
    end
    if command=='Authority' then
        if client('authority',args.session,args.epoch,args.policy,args.challenge)==true then N.authority=true;N.reason='client-authority' end
        return
    end
    -- The server has no stream for this client's rounds any more (a respawn, a closed native window): a new handshake now.
    if command=='Rehello' then
        N.active=false;N.reason=tostring(args.reason or 'stream-lost')
        zb('shotNetBaseline',N.reason);nextHello=0;return
    end
    -- The server's answer to one Hits row: contacts applied, and each refused one with its reason.
    if command=='Result' then
        local n=tonumber(args.accepted) or 0
        N.results.accepted=N.results.accepted+n;client('accepted',n)
        for _,r in ipairs(type(args.refused)=='table' and args.refused or {}) do
            N.results.refused=N.results.refused+1;N.results.last=tostring(r.reason)
            client('refused',tostring(r.key),tostring(r.reason))
        end
        return
    end
    if not N.payload(args) then return end
    -- The server's native hit fields: every client shows the same hit (Viewpoint or not).
    if command=='Hit' then zb('shotNetHit',args.payload);return end
    -- A contact of the server's flight: the same hole on every client.
    if command=='Mark' then zb('shotNetMark',args.payload);return end
    if command~='Wire' then return end
    if zb('shotNetMessage',args.payload)==true and not N.active then N.active=true;N.reason='handshake-accepted' end
end)
Events.OnTick.Add(function()
    -- The banner closes itself after N.NOTICE_MS; the session's notice never comes back (notice.answered).
    if N.noticeBanner and getTimestampMs()>=N.noticeBanner.untilMs then N.dismissNotice('expired') end
    if not isClient() then return end
    local player=getSpecificPlayer(0)
    if not player then return end
    N.active=zb('shotNetReady')==true or N.authority==true and client('held')==true
    local now=getTimestampMs()
    -- The Host player knows its server runs no Java without asking it (it may be an older published build).
    if not notice.answered and isCoopHost and isCoopHost()==true then notice.answered=true;N.showNotice({}) end
    -- Only the player the notice is for asks: nobody else ever sees it, so nobody else sends 60 asks.
    if not notice.answered and notice.asked<60 and now>=notice.next and N.noticeAllowed() then
        notice.asked=notice.asked+1;notice.next=now+5000
        sendClientCommand(player,N.module,'Notice',{ask=notice.asked})
    end
    -- A Hello can be lost before the game finished connecting (as the grenade hello was): repeat.
    if wanted() and not N.active and now>=nextHello then
        nextHello=now+3000
        N.request(player)
    end
    for _=1,64 do
        local row=zb('shotNetPoll')
        if type(row)~='string' or row=='' then break end
        sendClientCommand(player,N.module,'Wire',{payload=row})
    end
    -- The fragments this thrower flies advance as HandGrenades.tick advances them where the grenade is decided.
    if N.authority then client('fragmentsTick',getGameTime():getRealworldSecondsSinceLastUpdate()) end
    for _=1,16 do
        local row=N.authority and client('poll') or nil
        if type(row)~='table' then break end
        sendClientCommand(player,N.module,'Hits',row)
    end
end)
local reset=N.reset
N.reset=function()
    reset()
    N.dismissNotice('reset')
    nextHello=0;notice={asked=0,next=0,answered=false};N.noticeShown=nil;N.noticeLead=nil;N.authority=nil
end
return N
