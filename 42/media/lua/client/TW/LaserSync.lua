-- Multiplayer state of tactical devices and the owner's aim (ported from the
-- Brita B42 laser protocol). The server validates and rebroadcasts.
local T=require('TW/Tactical')
local R=require('TW/LaserRay')
local M={sent={},received={},aim={},sequence=0,session=tostring(getTimestampMs())}

function T.remoteAim(player,weapon)
    local a=M.aim[player:getOnlineID()]
    if a and weapon and a.weaponId==weapon:getID() and getTimestampMs()-a.time<2000 then return a end
end

local function deviceStates(weapon)
    local Runtime=TWRuntime
    local key=Runtime and Runtime.visualKey(weapon)
    if not key then return end
    local tree,_,parts=(Runtime.view or Runtime.read)(weapon)
    local list,signature,beams={},'',0
    for _,d in ipairs(T.devices(tree,parts)) do
        local s=T.state(d.part,d.tpl)
        list[#list+1]={partId=d.part:getID(),mode=s.mode,on=s.on}
        signature=signature..d.part:getID()..':'..s.mode..':'..tostring(s.on)..';'
        if s.on then beams=beams+1 end
    end
    if #list==0 then return end
    return list,signature,beams>0,key,tree,parts
end

function T.send(player,weapon,force)
    if not isClient() or not player or not player:isLocalPlayer() then return end
    local devices,signature,anyOn,key,tree,parts=deviceStates(weapon)
    if not devices then return end
    local id=player:getPlayerNum();local now=getTimestampMs();local prior=M.sent[id]
    local aiming=player:isAiming()
    local unchanged=prior and prior.weapon==weapon and prior.signature==signature and prior.aiming==aiming
    -- Avoid picking world geometry every update while waiting for the next packet.
    if not force and unchanged and now-prior.time<(anyOn and aiming and 100 or 10000) then return end
    local beams=T.beams(key,tree,parts)
    local aim=R.aimState(player,weapon,beams[1]);if not aim then return end
    local dx,dy,pitch=aim.dx,aim.dy,aim.pitch
    if not force and unchanged then
        local moving=math.abs(dx-prior.dx)>.012 or math.abs(dy-prior.dy)>.012 or math.abs(pitch-prior.pitch)>.3
        local interval=anyOn and aiming and (moving and 100 or 250) or 10000
        if now-prior.time<interval then return end
    end
    M.sequence=M.sequence+1
    M.sent[id]={weapon=weapon,signature=signature,aiming=aiming,dx=dx,dy=dy,pitch=pitch,time=now}
    sendClientCommand(player,'TWTactical','State',{weaponId=weapon:getID(),devices=devices,aiming=aiming,dx=dx,dy=dy,pitch=pitch,sequence=M.sequence,session=M.session})
end

Events.OnServerCommand.Add(function(module,command,args)
    if module~='TWTactical' or command~='State' or not args then return end
    if type(args.revision)~='number' or (M.received[args.playerId] and args.revision<=M.received[args.playerId]) then return end
    local players=getOnlinePlayers()
    for i=0,players:size()-1 do
        local player=players:get(i)
        if player:getOnlineID()==args.playerId and not player:isLocalPlayer() then
            local weapon=player:getPrimaryHandItem()
            local Runtime=TWRuntime
            local key=Runtime and Runtime.visualKey(weapon)
            if key and weapon:getID()==args.weaponId then
                M.received[args.playerId]=args.revision
                M.aim[args.playerId]={weaponId=args.weaponId,aiming=args.aiming==true,dx=args.dx,dy=args.dy,pitch=args.pitch,time=getTimestampMs()}
                local tree,_,parts=Runtime.read(weapon)
                local byId={}
                for _,d in ipairs(T.devices(tree,parts)) do byId[d.part:getID()]=d end
                for _,state in ipairs(args.devices or {}) do
                    local d=byId[state.partId]
                    if d then
                        local s=T.state(d.part,d.tpl)
                        s.mode=state.mode;s.on=state.on==true
                        T.state(d.part,d.tpl)
                    end
                end
                T.prepare(weapon,key,tree,parts)
            end
        end
    end
end)

Events.OnPlayerUpdate.Add(function(player)
    if player:isLocalPlayer() then T.send(player,player:getPrimaryHandItem(),false) end
    T.refreshNVG(player)
end)
Events.OnGameStart.Add(function() M.sent={};M.received={};M.aim={};M.sequence=0;M.session=tostring(getTimestampMs()) end)
return M
