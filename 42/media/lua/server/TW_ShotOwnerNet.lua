local N=require('TW/ShotOwnerNet')
local Identity=require('TW/ShotIdentity')
-- W11 hostauth: without a ready server owner the shooters' clients fly their rounds and this server checks them.
local Authority=require('TW_ShotAuthority')
local peers=setmetatable({},{__mode='k'})
local armed=setmetatable({},{__mode='k'})
local function reply(player,command,args) sendServerCommand(player,N.module,command,args) end
local warned=false
-- The dedicated companion's Java (EscapeFromZomboidFirearmsServer). Without it (or with its live owner not ready) a live
-- request is answered Authority: the shooter's client flies the round and TW_ShotAuthority checks it (W11 hostauth).
local function serverCall(name,...)
    local api=rawget(_G,'ShotServerLua')
    if type(api)~='table' or not api[name] then return nil end
    local ok,value=pcall(api[name],...)
    return ok and value or nil
end
-- Sandbox ViewpointBallistics (tools/loot_options.py): 1 Off, 2 Shadow, 3 Live (Viewpoint first person),
-- 4 Live (Viewpoint and isometric). Any other value is Off.
N.OFF,N.SHADOW,N.LIVE,N.ISOMETRIC=1,2,3,4
function N.live(mode) return mode==N.LIVE or mode==N.ISOMETRIC end
local function policy()
    local sandbox=SandboxVars and SandboxVars.EscapeFromZomboidFirearms or {}
    local mode=sandbox.ViewpointBallistics or 1
    if mode~=1 and mode~=2 and mode~=3 and mode~=4 then mode=1 end
    local debug=(getDebug and getDebug()==true) or (isDebugEnabled and isDebugEnabled()==true) or false
    serverCall('options',mode,debug,sandbox.ViewpointShotDiagnostics==true)
    return mode
end
local function status()
    return tostring(serverCall('status') or 'ready=false live=false reason=dedicated-module-absent')
end
-- W10 srvlive (user 27.10.2026): a server flies Live without the admin doing anything. The sandbox default stays
-- Shadow; the first start of a settings name turns its Shadow into Live once and saves the settings; the marker file
-- (Zomboid/Lua, one line per settings name) records that first start. A value chosen after that, and Off at any time,
-- is the admin's and is kept. W11 hostauth (user 28.10.2026): on every server, the mod's Java or not - without it the
-- shooters' clients fly their rounds and the server checks them, so neither the module nor its Java is required.
N.serverMod='EscapeFromZomboidFirearmsServer'
N.marker='EscapeFromZomboidFirearms_ServerBallistics.txt'
function N.serverModule()
    local mods=getActivatedMods and getActivatedMods()
    for i=0,(mods and mods:size() or 0)-1 do
        if (string.gsub(tostring(mods:get(i)),'^\\+',''))==N.serverMod then return true end
    end
    return false
end
-- The server module's Java part runs here (a dedicated server with ZombieBuddy); never on a Host server.
function N.serverJava() return type(rawget(_G,'ShotServerLua'))=='table' end
-- The players' notice "this server runs without the mod's Java part": its lines (UI.json keys, TW/ShotOwnerNet
-- javaFeatures) when Live is set and the server module is in the server's mods but its Java does not run (a Host
-- server, or a dedicated server without ZombieBuddy). Without the module listed: the console line only.
function N.notice(mode)
    local lines={}
    if N.serverJava() or not N.serverModule() or not N.live(mode) then return lines end
    for _,key in ipairs(N.javaFeatures) do lines[#lines+1]=key end
    return lines
end
-- The server console line of a Live setting without the mod's Java (client-flown, server-checked): printed when the
-- state changes.
local consoleState
local function console(mode)
    local java,module=N.serverJava(),N.serverModule()
    local state=tostring(N.live(mode))..'|'..tostring(java)..'|'..tostring(module)
    if state==consoleState then return end
    consoleState=state
    if not N.live(mode) or java then return end
    if module then
        print('[TW-SHOT-MP] Server ballistics is Live; the server module '..N.serverMod..' runs no Java on this server'
            ..' (a Host server, or a dedicated server without ZombieBuddy): shots are flown by the shooters\' clients and'
            ..' checked by the server')
    else
        print('[TW-SHOT-MP] Server ballistics is Live; the server module '..N.serverMod..' is not in this server\'s mods:'
            ..' shots are flown by the shooters\' clients and checked by the server')
    end
end
local function marked(name)
    local reader=getFileReader(N.marker,false)
    if not reader then return false end
    local line,found=reader:readLine(),false
    while line do
        if string.sub(line,1,#name+1)==name..'|' then found=true end
        line=reader:readLine()
    end
    reader:close()
    return found
end
function N.serverDefault()
    if N.defaultResult or not isServer() then return N.defaultResult end
    local name=tostring(getServerName and getServerName() or '')
    if name=='' then N.defaultResult='no-name';return N.defaultResult end
    local options=getSandboxOptions()
    local option=options:getOptionByName('EscapeFromZomboidFirearms.ViewpointBallistics')
    local before=option:getValue()
    if marked(name) then
        N.defaultResult='kept'
    else
        N.defaultResult='first-start'
        if before==N.SHADOW then
            option:setValue(N.LIVE);options:toLua();options:saveServerLuaFile(name)
            N.defaultResult='migrated'
        end
        local writer=getFileWriter(N.marker,true,true)
        if writer then writer:writeln(name..'|'..tostring(before)..'|'..N.defaultResult);writer:close() end
    end
    print('[TW-SHOT-MP] server ballistics "'..name..'": '..N.defaultResult..' value='..tostring(before)..'->'..tostring(option:getValue()))
    return N.defaultResult
end
-- The armed assembly of a player's weapon, from the same identity the shooter's client sends.
-- Pushed only on change; the server's native observer prepares each shot from it.
local function arm(player)
    local gun=player:getPrimaryHandItem()
    local id=gun and Identity.read(gun) or nil
    local token=id and (tostring(gun:getID())..'#'..id.revision) or false
    if armed[player]==token then return end
    armed[player]=token
    serverCall('arm',player,id and gun or nil,id and {key=id.key,revision=id.revision,parts=id.parts} or nil)
end
-- The server flies a round only when both copies of the weapon name the same one, and InventoryItem.ammoType is in no
-- native packet (TW/AmmoKind). The two copies part when the server's changes alone: a found magazine without a Gunworks
-- list after a magazine of another kind (only the server runs ISInsertMagazine.loadAmmo and its TW/AmmoKind restore), or a
-- server load (the client restores its copy itself). The client then kept its round until the weapon was re-equipped and
-- the server refused the whole magazine (a player's report of 04.11.2026). So the shooter hears this copy's round whenever
-- it changes, when the weapon comes to the hand and after a refused round, by Gunworks' own answer. When its list names
-- another round, the client's next shot tells the server through Ammo.AmmoProfileSetter, as for every change of round.
local told=setmetatable({},{__mode='k'})
local function tellRound(player)
    local peer=peers[player]
    if not peer or not peer.hello then told[player]=nil;return end
    local gun=player:getPrimaryHandItem()
    local enum=gun and Identity.read(gun) and gun:getAmmoType() or nil
    local refused=serverCall('takeAmmoRefused',player)==true
    local token=enum and (tostring(gun:getID())..'#'..enum:getItemKey()) or false
    if told[player]==token and not refused then return end
    told[player]=token
    if enum then sendServerCommand(player,'SWMG','applyAmmoProfile',{itemId=gun:getID(),bulletType=enum:getItemKey()}) end
end
Events.OnClientCommand.Add(function(module,command,player,args)
    if module~=N.module or not isServer() or not player or player:isDead() or type(args)~='table' then return end
    local now=getTimestampMs()
    local peer=peers[player]
    if not peer then peer={window=now,count=0};peers[player]=peer end
    if now-peer.window>=1000 then peer.window=now;peer.count=0 end
    peer.count=peer.count+1
    if peer.count>256 then return end
    if command=='Notice' then
        reply(player,'Notice',{lines=N.notice(policy()),java=N.serverJava(),module=N.serverModule()});return
    end
    if command=='Probe' or command=='Wound' or command=='Status' then
        policy()
        if type(args.request)~='string' or #args.request<1 or #args.request>128 then return end
        local result=status()
        if command~='Status' then
            local sandbox=SandboxVars and SandboxVars.EscapeFromZomboidFirearms or {}
            local debug=(getDebug and getDebug()==true) or (isDebugEnabled and isDebugEnabled()==true) or false
            if sandbox.ViewpointShotDiagnostics~=true or not debug or not player:isAccessLevel('admin') then
                reply(player,'Diagnostic',{request=args.request,detail='REFUSED admin/debug/sandbox '..result});return
            end
        end
        if command=='Probe' then result=tostring(serverCall('probe',player) or false)..' '..result end
        if command=='Wound' then
            -- Network input supplies an ID only; resolve the actor/fixture on the server.
            local target
            if type(args.target)=='number' and args.target%1==0 then
                -- IsoCell.getObjectList() is a Set in B42.21: walk the indexable zombie/player lists.
                local objects=args.targetKind=='zombie' and player:getCell():getZombieList()
                    or args.targetKind=='player' and getOnlinePlayers() or nil
                for i=0,(objects and objects:size() or 0)-1 do
                    local row=objects:get(i)
                    if row:getOnlineID()==args.target then target=row;break end
                end
            end
            result=tostring(serverCall('wound',player,target) or 'REFUSED unavailable')..' '..result
        end
        reply(player,'Diagnostic',{request=args.request,detail=result});return
    end
    if command=='Hello' then
        local mode=policy()
        if type(args.challenge)~='string' or #args.challenge<1 or #args.challenge>128
            or type(args.shot)~='number' or args.shot%1~=0 or args.shot<0 or args.shot>255 then return end
        if not N.live(mode) then
            peer.hello=false;Authority.reset(player);reply(player,'Baseline',{reason=status()});return
        end
        if serverCall('ready')~=true then
            -- W11 hostauth: no ready server owner (no Java of the mod here, or its live owner failed): the shooter's
            -- client flies its rounds; a repeated challenge keeps the epoch, a new one opens the next.
            peer.hello=false;reply(player,'Authority',Authority.hello(player,args.challenge,mode));return
        end
        -- A repeated challenge gets the same epoch; a new one opens a new epoch (old intents cannot resume).
        if peer.hello and peer.challenge==args.challenge and peer.payload then reply(player,'Wire',{payload=peer.payload});return end
        armed[player]=nil;arm(player)
        local payload=serverCall('hello',player,args.challenge,args.shot)
        if not N.payload({payload=payload}) then peer.hello=false;reply(player,'Baseline',{reason='server-handshake-refused'});return end
        peer.hello=true;peer.challenge=args.challenge;peer.payload=payload
        reply(player,'Wire',{payload=payload});N.active=true;return
    end
    if command=='Hits' then
        local answer=Authority.receive(player,args,policy())
        if answer then reply(player,'Result',answer) end
        return
    end
    if command~='Wire' or not N.payload(args) then return end
    -- An intent with no stream behind it: this server forgot the shooter (a respawn is a new IsoPlayer, a closed native
    -- window ends the stream) and the client does not know. Without an answer it claims every round, and each stays PZ's
    -- own until the player reconnects (188 rounds of a player's log of 04.11.2026). Its next Hello opens a new epoch.
    if not peer.hello or serverCall('streaming',player)~=true then
        peer.hello=false;reply(player,'Rehello',{reason='stream-lost'});return
    end
    -- Authenticated event actor is authoritative; payload shooter IDs cannot impersonate it.
    serverCall('message',player,args.payload)
end)
local function serverDefault()
    if N.defaultResult or not isServer() then return end
    local ok,err=pcall(N.serverDefault)
    if not ok then N.defaultResult='error';print('[TW-SHOT-MP] server ballistics default failed: '..tostring(err)) end
end
-- OnGameBoot: the server has just loaded its sandbox file; no client is connected yet.
Events.OnGameBoot.Add(serverDefault)
Events.OnTick.Add(function()
    if not isServer() then return end
    serverDefault()
    local mode=policy()
    console(mode)
    if not warned then print('[TW-SHOT-MP] '..status());warned=true end
    local players=getOnlinePlayers()
    if N.live(mode) then for i=0,players:size()-1 do arm(players:get(i));tellRound(players:get(i)) end end
    serverCall('tick')
    Authority.tick(mode,reply)
    for peer in pairs(peers) do
        local found=false
        for i=0,players:size()-1 do if players:get(i)==peer then found=true;break end end
        if not found then serverCall('disconnect',peer:getOnlineID());Authority.reset(peer);peers[peer]=nil;armed[peer]=nil end
    end
    for _=1,256 do
        local row=serverCall('poll')
        if type(row)~='string' or row=='' then break end
        local recipient,command,payload=row:match('^(-?%d+)|(%a+)|(.+)$')
        if recipient and (command=='Wire' or command=='Hit' or command=='Mark') and N.payload({payload=payload}) then
            for i=0,players:size()-1 do
                local peer=players:get(i)
                -- Native hit fields reach every client (with or without Viewpoint); protocol
                -- results reach the handshaken peers, or the one recipient they name.
                local wanted=recipient=='-2' and (command~='Wire' or peers[peer] and peers[peer].hello)
                    or tostring(peer:getOnlineID())==recipient and peers[peer] and peers[peer].hello
                if wanted then reply(peer,command,{payload=payload}) end
            end
        end
    end
end)
return N
