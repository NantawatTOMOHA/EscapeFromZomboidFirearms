local M=require('TW/HandGrenades')
local Smoke=require('TW/HandGrenadeSmoke')
local D=M.data
local C={views={},seen={},seq=0,pending=nil,ready=false,flashes={},soundRequests=0,soundsStarted=0,isoAirDraws=0}
TWHandGrenadesClient=C
local function safe(fn,...)
    local ok,value=pcall(fn,...)
    if not ok then M.errors=M.errors+1;print('[TW-HAND-GRENADE] client '..tostring(value)) end
    return ok,value
end
local function sound(tpl,key,x,y,z,baseZ)
    local event=D.resources[tpl].sounds[key]
    if event then
        C.soundRequests=C.soundRequests+1
        local square=getCell():getGridSquare(math.floor(x),math.floor(y),math.floor(z))
        if not square and baseZ then square=getCell():getGridSquare(math.floor(x),math.floor(y),math.floor(baseZ)) end
        if not square then C.lastSound={event=event,started=false,reason='unloaded-sound-column'};return end
        local handle=getSoundManager():PlayWorldSound(event,square,0,0,1,false)
        if handle then C.soundsStarted=C.soundsStarted+1 end
        C.lastSound={event=event,started=handle~=nil}
        return handle
    end
end
local function erase(s)
    if s and s.model then
        if TWGroundItemsNative and TWGroundItemsNative.removeGrenadeVisual then
            pcall(TWGroundItemsNative.removeGrenadeVisual,s.model,s.square)
        end
        local item=s.model:getItem()
        s.model:removeFromWorld();s.model:removeFromSquare()
        if item and item:getContainer() then item:getContainer():Remove(item) end
        s.model=nil;s.square=nil;s.look=nil
    end
end
local function syncVisual(s)
    -- An airborne height need not have a floor square at that Z. The flight
    -- owner supplies the actual support column; keep the real vertical offset.
    local sq=getCell():getGridSquare(math.floor(s.x),math.floor(s.y),math.floor(s.baseZ or s.z))
    if not sq then return end
    local look=(s.rest or s.phase=='smoke') and ('EscapeFromZomboidFirearms.MHG_'..s.tpl..'_Ground') or nil
    local xoff,yoff,zoff=s.x-sq:getX(),s.y-sq:getY(),math.max(0,s.z-sq:getZ())
    local oldSq=s.square
    local dirtyLook=false
    if not s.model then
        local item=instanceItem('EscapeFromZomboidFirearms.HGV_'..s.tpl)
        -- Bug wave 324 (24.10.2026, the user's item 4: items floating above the ground): the held model MHG_ has the
        -- body ~0.2 units from its hand datum, so the thrown grenade flew and lay ~35 cm above its physics point. In
        -- the air the visual's own copy (MHG_<tpl>_Air, the HGV_ script model) has the body's centre on the physics
        -- point, where the throw starts it (emitPoint); come to rest (or smoking) it lies on the floor (_Ground, as a
        -- dropped grenade). Reuse one persistent IsoWorldInventoryObject per grenade and notify Viewpoint's ChunkCache
        -- and ModelAsks via TWGroundItemsNative.syncGrenadeVisual so FP/TP/iso render it every frame without gaps.
        item:getModData().worldStaticModel=look
        item:setWorldZRotation(((tonumber(s.seed) or 0)*137)%360)
        local object=IsoWorldInventoryObject.new(item,sq,xoff,yoff,zoff)
        item:setWorldItem(object)
        sq:getWorldObjects():add(object);sq:getObjects():add(object);object:addToWorld()
        s.model=object;s.square=sq;s.look=look;oldSq=sq;dirtyLook=true
    else
        dirtyLook=(s.look~=look) or (oldSq~=sq)
        if s.look~=look then
            local item=s.model:getItem()
            if item and item.getModData then item:getModData().worldStaticModel=look end
            s.look=look
        end
        s.square=sq
    end
    if TWGroundItemsNative and TWGroundItemsNative.syncGrenadeVisual then
        pcall(TWGroundItemsNative.syncGrenadeVisual,s.model,oldSq,xoff,yoff,zoff,dirtyLook)
    end
end
local show=syncVisual
local function lerpRange(settings,metres)
    if settings.y==settings.x then return metres<=settings.x and 1 or 0 end
    return math.max(0,math.min(1,(settings.y-metres)/(settings.y-settings.x)))
end
function C.flash(s)
    local row=D.items[s.tpl]
    for index=0,getNumActivePlayers()-1 do
        local p=getSpecificPlayer(index)
        if p and not p:isDead() then
            local dx=(s.x-p:getX())*D.throw.metresPerCell
            local dy=(s.y-p:getY())*D.throw.metresPerCell
            local dz=(s.z-p:getZ())*D.throw.metresPerLevel
            local distance=math.sqrt(dx*dx+dy*dy+dz*dz)
            local clear=LosUtil.lineClear(getCell(),math.floor(s.x),math.floor(s.y),math.floor(s.z),math.floor(p:getX()),math.floor(p:getY()),math.floor(p:getZ()),false)
            if tostring(clear)~='Blocked' then
                local pose=HandGrenadeNative and HandGrenadeNative.pose(p)
                local f=p:getForwardDirection()
                -- Java fields are not visible from Lua: Vector2 is read through its getters.
                local dot=distance>0 and ((pose and pose.dx or f:getX())*dx+(pose and pose.dy or f:getY())*dy+(pose and pose.dz or 0)*dz)/distance or 1
                local intensity=lerpRange(row.Blindness,distance)*math.max(0,dot)
                local seconds=row.Blindness.z
                if intensity>0 then C.flashes[index]={remaining=seconds,duration=seconds,intensity=intensity} end
                local deaf=lerpRange(row.Contusion,distance)*row.Contusion.z
                if HandGrenadeNative and HandGrenadeNative.deafen then safe(HandGrenadeNative.deafen,p,deaf) end
            end
        end
    end
end
local function effects(s)
    local row=D.items[s.tpl]
    local args={x=s.x,y=s.y,z=s.z,kind=row.kind,effect=row.ExplosionEffectType,sourceTpl=s.tpl,seed=s.seed}
    if row.kind~='smoke' and TWExplosionFx and TWExplosionFx.explode and not C.seen[s.id] then safe(TWExplosionFx.explode,args) end
    if row.kind=='smoke' then
        sound(s.tpl,'smokeStart',s.x,s.y,s.z,s.baseZ)
        s.hiss=sound(s.tpl,'smokeLoop',s.x,s.y,s.z,s.baseZ)
        args.durationSec=row.EmitTime;args.elapsedSec=s.smokeElapsed or 0;args.radiusM=D.resources[s.tpl].smokeRadiusM
        if TWExplosionFx and TWExplosionFx.smokeStart then
            local ok,id=safe(TWExplosionFx.smokeStart,args)
            if ok then s.effect=id end
        end
        Smoke.apply(s)
    end
end
function C.event(command,args)
    if command=='Ready' then C.ready=args.protocol==D.protocol and args.digest==D.digest;C.answered=true;return end
    if command=='Accepted' or command=='Rejected' then
        if C.pending and C.pending.seq==args.seq then
            if command=='Accepted' then
                local p=C.pending.player;local item=p:getPrimaryHandItem()
                if item and item:getID()==args.itemId then p:setPrimaryHandItem(nil);p:getInventory():Remove(item) end
            end
            C.pending=nil
        end
        return
    end
    if not args or not D.items[args.tpl] or type(args.id)~='string' then return end
    local copy={} for k,v in pairs(args) do copy[k]=v end args=copy
    if command=='Bounce' then sound(args.tpl,'bounce',args.x,args.y,args.z,args.baseZ);return end
    if command=='Stop' then
        local old=C.views[args.id]
        if old and old.hiss then getSoundManager():StopSound(old.hiss);sound(args.tpl,'smokeEnd',args.x,args.y,args.z,args.baseZ) end
        if old and old.effect and TWExplosionFx and TWExplosionFx.smokeStop then safe(TWExplosionFx.smokeStop,old.effect) end
        Smoke.stop(args.id);erase(old);C.views[args.id]=nil;C.seen[args.id]=true;return
    end
    if command=='Flight' or command=='Detonate' then
        local old=C.views[args.id]
        if old then
            args.effect=old.effect;args.hiss=old.hiss
            if args.phase=='flight' or args.phase=='smoke' then
                args.model=old.model;args.square=old.square;args.look=old.look
            else
                erase(old)
            end
        end
        C.views[args.id]=args
        if args.phase=='flight' then
            if not old then
                sound(args.tpl,'SndThrow',args.x,args.y,args.z,args.baseZ)
                sound(args.tpl,'SndLever',args.x,args.y,args.z,args.baseZ)
                sound(args.tpl,'SndFuse',args.x,args.y,args.z,args.baseZ)
            end
            show(args)
        else
            -- A smoke grenade keeps lying while it emits (bug wave 324, item 4: every packet erased it above and
            -- nothing drew it again - the smoking grenade was not shown at all).
            if args.phase=='smoke' then show(args) end
            if not C.seen[args.id] then
                sound(args.tpl,'explode',args.x,args.y,args.z,args.baseZ)
                if D.items[args.tpl].kind=='flash' then C.flash(args) end
                effects(args);C.seen[args.id]=true
            end
            if args.phase=='done' then C.views[args.id]=nil end
        end
    end
end
M.clientEvent=C.event
function C.throw(player,weak,skipPin)
    if not skipPin and C.fpThrow then
        local routed,ok,why=C.fpThrow(player,weak)
        if routed then return ok,why end
    end
    local item=player and player:getPrimaryHandItem()
    local row=M.row(item)
    if not row or C.pending then return false,'held-item' end
    if isClient() and not C.ready then return false,'server-not-ready' end
    local point=row.emitPoint
    local pose=HandGrenadeNative and HandGrenadeNative.throwPose and HandGrenadeNative.throwPose(player,point[1],point[2],point[3])
    if not pose then return false,'hand-frame-unavailable' end
    C.seq=C.seq+1
    local args={protocol=D.protocol,digest=D.digest,seq=C.seq,itemId=item:getID(),tpl=row.tpl,weak=weak==true,
        ox=pose.ox,oy=pose.oy,oz=pose.oz,dx=pose.dx,dy=pose.dy,dz=pose.dz,relief=pose.relief}
    if not skipPin then sound(row.tpl,'SndPin',player:getX(),player:getY(),player:getZ()) end
    C.pending={seq=C.seq,player=player}
    if isClient() then sendClientCommand(player,'TWHandGrenades','Throw',args)
    else
        local id,why=M.accept(player,args)
        if not id then C.pending=nil;return false,why end
    end
    return true
end
-- Poll a physical press as well as the native mouse event. Other attack mods
-- can consume/reorder that event; a held own grenade still has one throw edge.
-- Equipping a grenade is sufficient, as in Tarkov; RMB aiming is not required.
function C.input(player,left,weak)
    local press=left and not (player and C.left[player])
    if player then C.left[player]=left end
    if not press or not player or player:isDead() or not M.row(player:getPrimaryHandItem()) then return false end
    if C.fpMouse and C.fpMouse(player) then return false end
    if TWSightInput and TWSightInput.blocked and TWSightInput.blocked(player) then return false end
    local ok,why=C.throw(player,weak)
    if not ok then C.lastRefusal=why;print('[TW-HAND-GRENADE] throw refused '..tostring(why)) end
    return ok,why
end
-- Multiplayer: a client command sent before the client is in game (GameClient.ingame) never leaves
-- it (LuaManager.sendClientCommand), and OnGameStart comes before that: the single Hello sent there
-- was lost, the client never became ready and no hand grenade could be thrown (21.10.2026 MP run).
-- Hello is repeated until the server answers, as TW_GrenadeFlight's handshake.
local function handshake()
    if not isClient() or C.answered then return end
    local now=getTimestampMs()
    if C.nextHello and now<C.nextHello then return end
    local player=getSpecificPlayer(0)
    if not player then return end
    C.nextHello=now+3000
    sendClientCommand(player,'TWHandGrenades','Hello',{protocol=D.protocol,digest=D.digest})
end
local function tick()
    handshake()
    if HandGrenadeNative and HandGrenadeNative.retireVisuals then HandGrenadeNative.retireVisuals() end
    local dt=getGameTime():getRealworldSecondsSinceLastUpdate()
    for _,flash in pairs(C.flashes) do flash.remaining=math.max(0,flash.remaining-dt) end
    for _,s in pairs(C.views) do
        if s.phase=='flight' or s.phase=='smoke' then syncVisual(s) end
        if s.phase=='smoke' then
            s.smokeElapsed=(s.smokeElapsed or 0)+dt
            if s.smokeElapsed>=D.items[s.tpl].EmitTime then C.event('Stop',s)
            else safe(Smoke.apply,s) end
        end
    end
    for index=0,getNumActivePlayers()-1 do
        local player=getSpecificPlayer(index)
        if player and player:isLocalPlayer() then
            local pad=rawget(_G,'TWGamepad')
            if player:getJoypadBind()~=-1 and pad then
                C.input(player,pad.held('GamepadGrenadeStrong',player),pad.held('GamepadGrenadeWeak',player))
            elseif index==0 then
                C.input(player,C.qaMouse~=nil and C.qaMouse or isMouseButtonDown(0)==true,isKeyDown(Keyboard.KEY_LSHIFT))
            end
        end
    end
end
local function menu(playerNum,context,items)
    local p=getSpecificPlayer(playerNum)
    if p and M.row(p:getPrimaryHandItem()) then
        context:addOption(getText('ContextMenu_TW_HGStrong'),p,function(player) C.throw(player,false) end)
        context:addOption(getText('ContextMenu_TW_HGWeak'),p,function(player) C.throw(player,true) end)
    end
end
local function reset()
    for _,s in pairs(C.views) do if s.hiss then getSoundManager():StopSound(s.hiss) end erase(s) end
    Smoke.reset();C.views={};C.seen={};C.flashes={};C.ready=false;C.answered=nil;C.nextHello=nil;C.pending=nil;C.left={}
end
Events.OnServerCommand.Add(function(module,command,args) if module=='TWHandGrenades' then safe(C.event,command,args) end end)
Events.OnTick.Add(tick)
Events.OnGameStart.Add(function()
    reset()
    -- Multiplayer: Hello goes from the tick until the server answers (handshake).
    if not isClient() then C.ready=true;M.store();for _,s in pairs(M.active) do C.event('Flight',s) end end
end)
Events.OnFillInventoryObjectContextMenu.Add(menu)
Events.OnMouseDown.Add(function(x,y)
    local p=getSpecificPlayer(0)
    C.input(p,true,isKeyDown(Keyboard.KEY_LSHIFT))
end)
Events.OnPostUIDraw.Add(function()
    for index,flash in pairs(C.flashes) do
        if flash.remaining>0 and HandGrenadeNative and HandGrenadeNative.white then
            local alpha=flash.intensity*flash.remaining/flash.duration
            UIManager.DrawTexture(HandGrenadeNative.white(),getPlayerScreenLeft(index),getPlayerScreenTop(index),
                getPlayerScreenWidth(index),getPlayerScreenHeight(index),alpha)
        end
    end
end)
local function renderIsoAirborne()
    if TWZB and TWZB.vpState and TWZB.vpState()~=0 then return end
    if type(Render3DItem)~='function' then return end
    for _,s in pairs(C.views) do
        if s.phase=='flight' and not s.rest and s.model and s.square then
            local item=s.model:getItem()
            local zoff=(tonumber(s.z) or 0)-(s.square:getZ() or 0)
            if item and zoff>0.001 then
                local yaw=((tonumber(s.seed) or 0)*137)%360
                Render3DItem(item,s.square,s.x,s.y,s.z,yaw)
                C.isoAirDraws=(C.isoAirDraws or 0)+1
            end
        end
    end
end
if Events.RenderOpaqueObjectsInWorld then Events.RenderOpaqueObjectsInWorld.Add(renderIsoAirborne) end
if Events.OnMainMenuEnter then Events.OnMainMenuEnter.Add(reset) end
return C
