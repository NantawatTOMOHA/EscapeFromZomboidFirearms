-- Only a validated final launcher frame replaces the Gunworks cursor launch.
local C=require('TW/Core')
local R=require('TW/Runtime')
local Sight=require('TW/SightState')
local M=require('TW/GrenadeFlight')
local Zero=require('TW/LauncherZero')
local D=M.data
local Client={sequence=0,readyUntil=0,nextHello=0,installed=false,ordnanceVisuals={}}
TWGrenadeFlight=M
M.client=Client
local function api() return rawget(_G,'GrenadeFlightPose') end
local function allowed()
    local V=rawget(_G,'TWViewpoint')
    return V and V.enabled and V.enabled() and not (V.giveToStt and V.giveToStt())
end
-- The launcher fireport and +Y bore are transformed with the same installed
-- placement, grip shift and render roll as its visible model, never the host muzzle.
function Client.geometry(player,gun)
    local full=gun and gun:getFullType()
    if not allowed() or not D.launchers[full] then return nil,nil,'unsupported-or-disabled' end
    local key=R.visualKey(gun)
    if not key then return nil,nil,'no-visual-parent' end
    local tree=R.tree(gun) or {}
    local tpl,path
    if full:sub(1,#'EscapeFromZomboidFirearms.GL_')=='EscapeFromZomboidFirearms.GL_' then
        tpl=full:sub(#'EscapeFromZomboidFirearms.GL_'+1)
        for candidate,value in pairs(tree) do if value==tpl then if path then return nil,nil,'ambiguous-launcher' end path=candidate end end
        if not path then return nil,nil,'no-launcher-in-tree' end
    else tpl=C.root(key);path='' end
    local item=C.data.items[tpl]
    local place=path=='' and C.identity or C.placement(key,tree,path)
    if not item or not item.muzzle or not place then return nil,nil,'no-launcher-fireport' end
    local muzzle=C.toRender(key,C.transformPoint(place,item.muzzle))
    local bore=C.toRender(key,{place[1][2],place[2][2],place[3][2]})
    local view=Sight.current(player)
    if path~='' and not (view and view.tpl==tpl and view.path==path and view.kind=='launcher') then
        return nil,view,'launcher-channel-mismatch'
    end
    local channel=tpl..'@'..path..':'..tostring(view and view.tpl)..'@'..tostring(view and view.path)
        ..':'..tostring(view and view.channel)..':'..tostring(view and view.mode)
    local parts={}
    for p,t in pairs(tree) do parts[#parts+1]=p..'='..t end
    table.sort(parts)
    -- W10 launchersight: a standalone launcher's mark no sight pose zeroes is zeroed by the launch (TW/LauncherZero).
    if path=='' then bore=Zero.direction(key,tree,view,muzzle,bore,D.ammo[D.launchers[full][1]],full..'|'..table.concat(parts,';')) or bore end
    return {muzzle=muzzle,bore=bore,channel=channel,signature=full..'|'..tostring(gun:getID())..'|'..table.concat(parts,';')
        ..'|'..tostring(view and view.zeroDistance)..'|'..tostring(player:isAiming())..'|'..channel},view
end
function Client.describe(player,gun)
    local ok,detail=pcall(function()
        local geometry,view,why=Client.geometry(player,gun)
        local a=api()
        return 'weapon='..tostring(gun and gun:getFullType())..' id='..tostring(gun and gun:getID())
            ..' aiming='..tostring(player and player:isAiming())..' range='..tostring(view and view.zeroDistance)
            ..' channel='..tostring(geometry and geometry.channel)..' geometry='..tostring(why or 'ready')
            ..' '..(a and a.describe and a.describe(player,gun,geometry and geometry.signature or '')
                or a and a.status and a.status() or 'reason=no-pose-diagnostics')
    end)
    return ok and detail or 'reason=diagnostic-error:'..tostring(detail)
end
function Client.update(player)
    local a=api()
    if not (a and a.set) or not player:isLocalPlayer() then return end
    if not M.enabled then a.set(player,nil,nil);return end
    local gun=player:getPrimaryHandItem()
    local ok,geometry=pcall(Client.geometry,player,gun)
    a.set(player,ok and geometry and gun or nil,ok and geometry or nil)
end
function Client.packet(player,gun)
    local a=api()
    if not (a and a.pose) or not player or not player:isLocalPlayer() then return nil,'no-pose-api-or-player' end
    if isClient() and Client.readyUntil<getTimestampMs() then return nil,'no-server-handshake' end
    local ammo=gun:getModData().GWG_FiringExplosiveAmmo
    if not M.supports(gun:getFullType(),ammo) then return end
    local geometry,view,why=Client.geometry(player,gun)
    if not geometry then return nil,why end
    local pose=a.pose(player,gun,geometry.signature)
    if not pose then return nil,'no-fresh-drawn-frame' end
    Client.sequence=Client.sequence+1
    pose.protocol=D.protocol;pose.digest=D.digest;pose.weapon=gun:getFullType();pose.weaponId=gun:getID()
    pose.ammo=ammo;pose.sequence=Client.sequence;pose.range=view and view.zeroDistance or 0
    -- Retained solely for an explicit native Gunworks failover, never for VP flight.
    local n,z=player:getPlayerNum(),player:getZ()
    local mx,my=getMouseX(),getMouseY()
    pose.fallbackX=screenToIsoX(n,mx,my,z);pose.fallbackY=screenToIsoY(n,mx,my,z);pose.fallbackZ=z
    return pose
end
function Client.install()
    if Client.installed or not M.install() then return end
    require('ExplosivesSystems/Client')
    local E=require('ExplosivesSystems/Physics')
    local previous=E.onWeaponSwingHitPoint
    if type(previous)~='function' then M.fail('onWeaponSwingHitPoint(player,weapon)');return end
    local function hit(player,gun)
        if not M.enabled then return previous(player,gun) end
        local ok,packet,why=pcall(Client.packet,player,gun)
        if not ok then M.fail('launch frame: '..tostring(packet));return previous(player,gun) end
        if not packet or not M.enabled then
            if why and why~='unsupported-or-disabled' and gun and M.supports(gun:getFullType(),gun:getModData().GWG_FiringExplosiveAmmo) then
                print('[TW-GRENADE] FALLBACK Gunworks reason='..why..' '..Client.describe(player,gun))
            end
            return previous(player,gun)
        end
        local accepted
        if isClient() then
            accepted,why=pcall(sendClientCommand,player,M.module,'Launch',packet)
            if not accepted then M.fail('transport: '..tostring(why)) end
        else
            local before=E.nextOrdnanceId
            local safe,value=pcall(M.spawn,player,gun,packet)
            if safe then accepted=value
            else
                M.fail('spawn adapter: '..tostring(value))
                accepted=before~=nil and E.nextOrdnanceId~=before
            end
        end
        if not accepted then return previous(player,gun) end
        gun:getModData().GWG_FiringExplosiveAmmo=nil
    end
    Events.OnWeaponSwingHitPoint.Remove(previous)
    Events.OnWeaponSwingHitPoint.Add(hit)
    E.onWeaponSwingHitPoint=hit;Client.installed=true
    local visual=E.spawnVisualOrdnance
    if type(visual)=='function' then
        E.spawnVisualOrdnance=function(args)
            if args and args.isAmmoLaunch and type(args.sourceWeapon)=='string' and args.sourceWeapon:sub(1,#'EscapeFromZomboidFirearms.TWGF_')=='EscapeFromZomboidFirearms.TWGF_' then
                local ok,copy=pcall(function()
                    local result={} for k,v in pairs(args) do result[k]=v end
                    result.rotation=math.deg(math.atan2(args.destY-args.originY,args.destX-args.originX))%360
                    return result
                end)
                if ok then return visual(copy) end
                M.fail('visual heading: '..tostring(copy))
            end
            return visual(args)
        end
    end
end
local function clearOrdnanceVisual(entry)
    if not entry or not entry.model then return end
    if entry.visual then entry.visual.twModel=nil end
    if TWGroundItemsNative and TWGroundItemsNative.removeGrenadeVisual then
        pcall(TWGroundItemsNative.removeGrenadeVisual,entry.model,entry.square)
    end
    pcall(function() entry.model:removeFromWorld();entry.model:removeFromSquare() end)
end
function Client.syncVisualOrdnance()
    local okE,E=pcall(require,'ExplosivesSystems/Physics')
    local list=okE and E and E.visualOrdnance
    local seen={}
    if type(list)=='table' and IsoWorldInventoryObject then
        for k,visual in pairs(list) do
            if type(visual)=='table' then
                local key=visual.ordnanceId or visual.id or k
                seen[key]=true
                local sq=visual.square or (getCell and getCell() and getCell():getGridSquare(
                    math.floor(visual.originX or 0),math.floor(visual.originY or 0),math.floor(visual.originZ or 0)))
                local item=visual.itemObj or (visual.itemType and instanceItem and instanceItem(visual.itemType))
                if sq and item then
                    local xoff=tonumber(visual.x) or ((tonumber(visual.currentX or visual.originX) or sq:getX())-sq:getX())
                    local yoff=tonumber(visual.y) or ((tonumber(visual.currentY or visual.originY) or sq:getY())-sq:getY())
                    local zoff=math.max(0,tonumber(visual.z) or ((tonumber(visual.currentZ or visual.originZ) or sq:getZ())-sq:getZ()))
                    local entry=Client.ordnanceVisuals[key]
                    if not entry then
                        if item.setWorldZRotation then item:setWorldZRotation((tonumber(visual.rotation) or 0)%360) end
                        local obj=IsoWorldInventoryObject.new(item,sq,xoff,yoff,zoff)
                        if item.setWorldItem then item:setWorldItem(obj) end
                        sq:getWorldObjects():add(obj);sq:getObjects():add(obj);obj:addToWorld()
                        entry={model=obj,square=sq,item=item,visual=visual}
                        visual.twModel=obj
                        Client.ordnanceVisuals[key]=entry
                        if TWGroundItemsNative and TWGroundItemsNative.syncGrenadeVisual then
                            pcall(TWGroundItemsNative.syncGrenadeVisual,obj,sq,xoff,yoff,zoff,true)
                        end
                    else
                        local oldSq=entry.square
                        entry.square=sq
                        entry.visual=visual
                        visual.twModel=entry.model
                        if item.setWorldZRotation and visual.rotation then item:setWorldZRotation((tonumber(visual.rotation) or 0)%360) end
                        if TWGroundItemsNative and TWGroundItemsNative.syncGrenadeVisual then
                            pcall(TWGroundItemsNative.syncGrenadeVisual,entry.model,oldSq,xoff,yoff,zoff,oldSq~=sq)
                        end
                    end
                end
            end
        end
    end
    for key,entry in pairs(Client.ordnanceVisuals) do
        if not seen[key] then
            clearOrdnanceVisual(entry)
            Client.ordnanceVisuals[key]=nil
        end
    end
end
local function handshake()
    Client.syncVisualOrdnance()
    if not isClient() or not M.enabled or getTimestampMs()<Client.nextHello then return end
    local player=getSpecificPlayer(0)
    if not player then return end
    Client.nextHello=getTimestampMs()+10000
    sendClientCommand(player,M.module,'Hello',{protocol=D.protocol,digest=D.digest})
end
Events.OnGameBoot.Add(Client.install)
Events.OnGameStart.Add(function() Client.readyUntil=0;Client.nextHello=0;Client.sequence=0;Client.install() end)
Events.OnPlayerUpdate.Add(function(player)
    local ok,why=pcall(Client.update,player)
    if not ok then M.fail('pose update: '..tostring(why)) end
end)
Events.OnTick.Add(handshake)
Events.OnServerCommand.Add(function(module,command,args)
    if module==M.module and command=='Ready' and type(args)=='table' and args.protocol==D.protocol and args.digest==D.digest then
        Client.readyUntil=getTimestampMs()+30000
    elseif module==M.module and command=='Relief' then
        -- The shooter's drawn relief of a round the server flies: this client's visual of it flies the same arc.
        local ok,why=pcall(M.receiveRelief,args)
        if not ok then print('[TW-GRENADE] relief receive: '..tostring(why)) end
    end
end)
return Client
