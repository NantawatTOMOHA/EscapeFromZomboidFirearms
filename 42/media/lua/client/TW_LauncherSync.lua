-- Equipment and Lua commands may arrive in either order. Keep one server-owned
-- visual snapshot per player until the corresponding equipped ID is visible.
local L=require('TW/Launchers')
local R=require('TW/Runtime')
local pending,received,observed={},{},{}
local tick=0
local function apply(player,state)
    local weapon=player:getPrimaryHandItem()
    if L.applyVisualSnapshot(player,weapon,state) then
        received[state.playerId]=state.revision;pending[state.playerId]=nil
        return true
    end
end
Events.OnServerCommand.Add(function(module,command,args)
    if not isClient() or not L.config.enabled or module~='TWLaunchers' or command~='VisualState'
        or type(args)~='table' or type(args.playerId)~='number' or type(args.revision)~='number' then return end
    local prior=pending[args.playerId]
    if args.revision<=(received[args.playerId] or 0) or (prior and args.revision<=prior.state.revision) then return end
    pending[args.playerId]={state=args,expires=getTimestampMs()+15000}
end)
Events.OnTick.Add(function()
    if not isClient() or not L.config.enabled then return end
    local now=getTimestampMs()
    if now-tick<250 then return end
    tick=now
    local players=getOnlinePlayers()
    local requester=getPlayer()
    for id,entry in pairs(pending) do if entry.expires<now then pending[id]=nil end end
    for i=0,players:size()-1 do
        local player=players:get(i);local id=player:getOnlineID()
        local state=pending[id]
        if state then apply(player,state.state) end
        local weapon=player:getPrimaryHandItem()
        if weapon and (L.isUnderbarrel(weapon) or R.key(weapon)) and requester then
            local seen=observed[id]
            if not seen or seen.weapon~=weapon:getID() or (now-seen.time>5000 and L.isUnderbarrel(weapon) and not L.hostKey(weapon)) then
                observed[id]={weapon=weapon:getID(),time=now}
                sendClientCommand(requester,'TWLaunchers','RequestState',{playerId=id,weaponId=weapon:getID()})
            end
        else observed[id]=nil end
    end
end)
Events.OnGameStart.Add(function() pending={};received={};observed={};tick=0 end)
