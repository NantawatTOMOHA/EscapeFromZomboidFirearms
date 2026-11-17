local N=require('TW/ImpactNet')
local peers=setmetatable({},{__mode='k'})
Events.OnClientCommand.Add(function(module,command,player,row)
    if module~=N.module or command~='Contact' or not isServer() or not player or player:isDead() or not N.valid(row) then return end
    -- Authenticated actor, bounded distance/rate and fields. This is a cosmetic
    -- client observation, never a server-validated ballistic contact or damage.
    local dx,dy=row.x-player:getX(),row.y-player:getY()
    if dx*dx+dy*dy>1024*1024 or math.abs(row.z-player:getZ())>16 then return end
    local now=getTimestampMs();local peer=peers[player]
    if not peer then peer={time=now,tokens=128,seen={},count=0};peers[player]=peer end
    peer.tokens=math.min(128,peer.tokens+math.max(0,now-peer.time)*.096);peer.time=now
    if peer.tokens<1 then return end
    peer.tokens=peer.tokens-1
    for id,time in pairs(peer.seen) do if now-time>=120000 then peer.seen[id]=nil;peer.count=peer.count-1 end end
    if peer.seen[row.id] or peer.count>=4096 then return end
    peer.seen[row.id]=now;peer.count=peer.count+1
    local clean=N.copy(row)
    -- IDs of different authenticated senders cannot suppress one another.
    clean.id='mp:'..tostring(player:getOnlineID())..':'..row.id
    if #clean.id>220 then return end
    local players=getOnlinePlayers()
    for i=0,players:size()-1 do
        local recipient=players:get(i)
        if recipient~=player then sendServerCommand(recipient,N.module,'Contact',clean) end
    end
end)
return N
