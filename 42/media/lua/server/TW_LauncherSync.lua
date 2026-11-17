-- Read-only requests; clients can never supply host snapshots or part state.
local L=require('TW/Launchers')
local revision=0
local requests=setmetatable({},{__mode='k'})
function L.broadcastState(player,weapon,recipient)
    if not isServer() then return end
    local state=L.visualSnapshot(player,weapon)
    if not state then return end
    revision=revision+1;state.revision=revision
    if recipient then sendServerCommand(recipient,'TWLaunchers','VisualState',state)
    else sendServerCommand('TWLaunchers','VisualState',state) end
end
Events.OnClientCommand.Add(function(module,command,requester,args)
    if not L.config.enabled or module~='TWLaunchers' or command~='RequestState' or type(args)~='table'
        or not requester or requester:isDead() or type(args.playerId)~='number' or type(args.weaponId)~='number' then return end
    local key=tostring(args.playerId)..':'..tostring(args.weaponId)
    local now=getTimestampMs()
    local recent=requests[requester] or {};requests[requester]=recent
    if recent[key] and now-recent[key]<1000 then return end
    -- Keep request memory bounded even when many item IDs are observed.
    for old,time in pairs(recent) do if now-time>30000 then recent[old]=nil end end
    recent[key]=now
    local players=getOnlinePlayers()
    for i=0,players:size()-1 do
        local player=players:get(i)
        if player:getOnlineID()==args.playerId and not player:isDead() then
            local weapon=player:getPrimaryHandItem()
            if weapon and weapon:getID()==args.weaponId then L.broadcastState(player,weapon,requester) end
            return
        end
    end
end)

-- Dedicated-server remote players do not dispatch OnPlayerUpdate. The first
-- tick after joining restores their carried state; later scans are throttled
-- per player while newly equipped items are restored on every server tick.
Events.OnTick.Add(function()
    if not isServer() or not L.config.enabled or #(L.config.underbarrels or {})==0 then return end
    local players=getOnlinePlayers()
    for i=0,players:size()-1 do L.refreshInventoryMass(players:get(i),false) end
end)
