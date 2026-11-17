-- Server side of TWTactical/State: validate owner, equipped weapon, part ids,
-- mode ranges and numeric fields, store the device state and rebroadcast.
require('TW/Runtime')
local T=require('TW/Tactical')
local latest={};local revision=0
local function finite(n,limit) return type(n)=='number' and n==n and math.abs(n)<=limit end

function T.validateMessage(player,weapon,args,previous)
    if type(args)~='table' or not player or player:isDead() then return false,'args' end
    local Runtime=TWRuntime
    local key=Runtime and Runtime.visualKey(weapon)
    if not key or weapon:getID()~=args.weaponId then return false,'weapon' end
    if type(args.session)~='string' or #args.session>40 or type(args.sequence)~='number' or args.sequence<1 or args.sequence>2147483647 or args.sequence~=math.floor(args.sequence) then return false,'sequence' end
    if previous and previous.player==player and previous.session==args.session and args.sequence<=previous.sequence then return false,'replay' end
    if not finite(args.dx,1.001) or not finite(args.dy,1.001) or not finite(args.pitch,90) or type(args.aiming)~='boolean' then return false,'aim' end
    if type(args.devices)~='table' or #args.devices>8 then return false,'devices' end
    local tree,_,parts=Runtime.read(weapon)
    local byId={}
    for _,d in ipairs(T.devices(tree,parts)) do byId[d.part:getID()]=d end
    for _,state in ipairs(args.devices) do
        local d=type(state)=='table' and byId[state.partId]
        if not d then return false,'part' end
        if type(state.mode)~='number' or state.mode~=math.floor(state.mode) or state.mode<2 or state.mode>#T.modes(d.tpl) or type(state.on)~='boolean' then return false,'mode' end
    end
    return true,nil,key,tree,parts,byId
end

Events.OnClientCommand.Add(function(module,command,player,args)
    if module~='TWTactical' or command~='State' then return end
    local weapon=player and player:getPrimaryHandItem()
    local id=player and player:getOnlineID()
    local ok,_,key,tree,parts,byId=T.validateMessage(player,weapon,args,id and latest[id])
    if not ok then return end
    local devices={}
    for _,state in ipairs(args.devices) do
        local d=byId[state.partId]
        local s=T.state(d.part,d.tpl)
        s.mode=state.mode;s.on=state.on
        devices[#devices+1]={partId=state.partId,mode=s.mode,on=s.on}
    end
    T.prepare(weapon,key,tree,parts)
    revision=revision+1
    latest[id]={player=player,session=args.session,sequence=args.sequence}
    sendServerCommand('TWTactical','State',{playerId=id,weaponId=weapon:getID(),devices=devices,aiming=args.aiming,dx=args.dx,dy=args.dy,pitch=args.pitch,revision=revision})
end)
