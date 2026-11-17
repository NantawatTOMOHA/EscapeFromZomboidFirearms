-- Own protocol only. No native initiation, inventory mutation or health writes here.
local N={module='TWShotOwner',maxWire=349528,active=false,reason='server-adapter-unavailable'}
TWShotOwnerNet=N
local function api() return rawget(_G,'ShotProtocolLua') end
function N.call(name,...)
    local a=api()
    if type(a)~='table' or not a[name] then return nil end
    local ok,value=pcall(a[name],...)
    if not ok then N.active=false;N.reason='adapter-error:'..name;return nil end
    return value
end
-- What a server without the mod's Java part (a Host server, a dedicated server without ZombieBuddy) turns off, one
-- UI.json line each (coordinator's Host audit, W10 srvlive): the players' notice and the Host player's notice list them.
-- W11 hostauth: Live ballistics and frag-grenade fragments work there (the shooters' clients fly, the server checks): no
-- longer listed.
N.javaFeatures={'UI_TW_ServerJavaSmoke'}
function N.payload(args)
    local wire=type(args)=='table' and args.payload
    return type(wire)=='string' and #wire>0 and #wire<=N.maxWire and #wire%4==0
        and wire:match('^[%w+/=]+$')~=nil
end
-- uint64 stays decimal text across Lua. This helper never converts it to a number.
function N.uint64(value,allowZero)
    if type(value)~='string' or not value:match('^%d+$') or #value>20 then return false end
    if #value>1 and value:sub(1,1)=='0' then return false end
    if #value==20 and value>'18446744073709551615' then return false end
    return allowZero or value~='0'
end
function N.ready()
    return N.active and N.call('ready',isServer and isServer() or false)==true
end
function N.status()
    return tostring(N.call('status') or ('baseline:'..N.reason))
end
function N.reset()
    N.call('reset');N.active=false;N.reason='new-session-handshake-required'
end
function N.disconnect(player)
    if player then N.call('disconnect',player:getOnlineID()) else N.reset() end
    N.active=false;N.reason='disconnected'
end
local function on(name,fn)
    if Events and Events[name] and Events[name].Add then Events[name].Add(fn) end
end
-- Save does not persist this state or terminate a still-running flight. Loading
-- starts a different session; old envelopes cannot resurrect saved projectiles.
on('OnGameStart',function() N.reset() end)
on('OnMainMenuEnter',function() N.reset() end)
on('OnDisconnect',function() N.disconnect() end)
return N
