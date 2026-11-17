-- W11 hostauth (variant B, docs/pending/HOST_CLIENT_AUTHORITY_RU.md, user decisions of 28.10.2026). On a server without
-- the mod's Java (a Host server, a dedicated server without ZombieBuddy) at Server ballistics = Live, the shooter's client
-- flies its claimed round and reports each contact it applied (Hits). This module checks every contact against the
-- server's own facts, applies it with PZ's own calls and relays the hit fields and marks to the other clients, as PZ's
-- server does with a client-reported hit (WeaponHit.process: Hit(..., true), then the relay). Lua cannot trace the world:
-- walls on the line are the shooter's word, as in vanilla MP.
local N=require('TW/ShotOwnerNet')
local Ammo=require('TW/Data/Ammo')
local A={peers=setmetatable({},{__mode='k'}),logged={},
    stats={hello=0,tickets=0,rows=0,contacts=0,applied=0,refused=0,relayed=0,marks=0,xp=0,expired=0,errors=0,kinds={},grenades=0,
        fragments={rows=0,contacts=0,applied=0,refused={}}},last='none'}
TWShotAuthority=A
-- PZ cell and level in metres (TarkovBallistics.METRES_PER_CELL / METRES_PER_LEVEL, the B4 resource's pzUnits).
A.CELL=1.1534025374855825
A.LEVEL=A.CELL*2.449489742783178
-- The dedicated live owner's limits (ShotServerLive.LIMITS, docs/pending/MP_SHOT_LIVE_RU.md): 2 s for a command and the
-- native packet of one shot to meet in any order.
A.LATENCY_MS=2000
-- ShotBodies.LIMITS / ANIMAL_LIMITS: the drawn root within 1.5 m and 0.6 levels of the server's character; its capsules
-- within 1.4 m (an animal 2.2 m) of the root and 0.6 m below to 2.4 m above it.
A.BODY={root=1.5,levels=0.6,reach=1.4,below=0.6,above=2.4}
A.ANIMAL_REACH=2.2
-- ShotPenetration.MAX_CONTACTS: contacts of one cartridge's chain.
A.MAX_CONTACTS=4096
-- Contacts and marks of one Hits row (ShotClientAuthority.ROW_CONTACTS / ROW_MARKS); tickets kept per player.
A.ROW_CONTACTS,A.ROW_MARKS,A.MAX_TICKETS=16,32,64
-- The largest damage PZ's own chain can return for one ranged hit of a weapon, by the B42.21 CombatConfigKey defaults
-- (javap): HandWeapon.getDamageMod 1.2 at most, HEAD_HIT_DAMAGE_SPLIT_MODIFIER 3, the back hit x1.5 of
-- processHitDamage, NON_PLAYER_RECEIVED_DAMAGE_MULTIPLIER 1.5 (PLAYER 0.4), weapon level 10: BASE_WEAPON_DAMAGE_MULTIPLIER
-- 0.3 + 10 x WEAPON_LEVEL_DAMAGE_MULTIPLIER_INCREMENT 0.1, the critical hit x max(2, the weapon's multiplier).
A.DAMAGE_FACTOR=1.2*3*1.5*1.5*(0.3+10*0.1)
function A.damageCap(ticket) return ticket.max*A.DAMAGE_FACTOR*math.max(2,ticket.crit) end

local function now() return getTimestampMs() end
local function console(reason,text)
    -- One line per reason a minute at most: an automatic burst of refusals stays readable.
    local t=now()
    if A.logged[reason] and t-A.logged[reason]<60000 then return end
    A.logged[reason]=t
    print('[TW-SHOT-MP] '..text)
end
local function peer(player)
    local p=A.peers[player]
    if not p then p={epoch=0,tickets={},shots={},pending={}};A.peers[player]=p end
    return p
end
-- The Live server without the mod's Java gives the shooter's client its rounds (TW_ShotOwnerNet server, Hello).
function A.session()
    if not A.sessionId then
        A.sessionId=tostring(getRandomUUID and getRandomUUID() or (tostring(now())..'-'..tostring(ZombRand and ZombRand(1000000) or 0)))
    end
    return A.sessionId
end
function A.hello(player,challenge,policy)
    local p=peer(player)
    if p.challenge~=challenge or p.policy~=policy then
        p.epoch=p.epoch+1;p.challenge=challenge;p.policy=policy;p.tickets={};p.shots={};p.pending={}
        A.stats.hello=A.stats.hello+1
    end
    p.authority=true
    return {session=A.session(),epoch=tostring(p.epoch),policy=policy,challenge=challenge}
end
function A.reset(player)
    if player then A.peers[player]=nil else A.peers=setmetatable({},{__mode='k'}) end
end
function A.active(player) local p=A.peers[player];return p~=nil and p.authority==true end
-- The round's limits by its native type (TW/Data/Ammo limits, tools/generate_scripts.py ammo_limits).
function A.limits(weapon)
    local ok,key=pcall(function() local t=weapon:getAmmoType();return t and t:getItemKey() end)
    local rounds=Ammo.limits and Ammo.limits.rounds
    return ok and rounds and key and rounds[key] or nil,ok and key or nil
end
-- One ticket per native shot of a player in client authority: OnWeaponSwingHitPoint, which PZ's server raises once a
-- shot from Player.attack (the claimed round's miss packet included; ISReloadWeaponAction.onShoot spends the round there).
function A.ticket(player,weapon)
    local p=A.peers[player]
    if not p or not p.authority or not weapon or not instanceof(weapon,'HandWeapon') or not weapon:isAimedFirearm() then return end
    local limits,key=A.limits(weapon)
    local t=now()
    local ticket={weapon=weapon,id=weapon:getID(),ammo=key,at=t,expires=t+((limits and limits.life or 0)*1000)+A.LATENCY_MS,
        pellets=weapon:getProjectileCount(),max=weapon:getMaxDamage(),crit=weapon:getCriticalDamageMultiplier(),
        range=limits and limits.range or 0,limits=limits~=nil,round=limits,contacts=0,keys={},xp={},xpShot=false,vehicles={}}
    table.insert(p.tickets,ticket)
    while #p.tickets>A.MAX_TICKETS do table.remove(p.tickets,1) end
    A.stats.tickets=A.stats.tickets+1
end
-- The ticket of one reported round (its decimal sequence): the one it was bound to, else the oldest unbound unexpired
-- ticket of the same weapon. nil and the reason when there is none (yet).
function A.bind(p,seq,weapon)
    local bound=p.shots[seq]
    if bound then return bound end
    local t,other=now(),false
    for _,ticket in ipairs(p.tickets) do
        if not ticket.seq and t<=ticket.expires then
            if ticket.id==weapon then ticket.seq=seq;p.shots[seq]=ticket;return ticket end
            other=true
        end
    end
    return nil,other and 'weapon-mismatch' or 'no-ticket'
end
-- Zombies by online ID, one walk of the zombie list a tick (automatic fire reports many contacts a tick).
local zombieCache,zombieTick={},-1
local tickCount=0
function A.zombie(player,id)
    if zombieTick~=tickCount then
        zombieCache={};zombieTick=tickCount
        local list=player:getCell():getZombieList()
        for i=0,list:size()-1 do local z=list:get(i);zombieCache[z:getOnlineID()]=z end
    end
    return zombieCache[id]
end
function A.target(player,kind,id)
    if type(id)~='number' or id%1~=0 then return nil end
    -- A killed zombie loses its online ID and PZ recycles the object (mplive-05): the ID must still be its own.
    local target
    if kind=='zombie' then target=A.zombie(player,id)
    elseif kind=='animal' then target=getAnimal and getAnimal(id) or nil
    elseif kind=='player' then
        target=getPlayerByOnlineID and getPlayerByOnlineID(id) or nil
        if target and instanceof(target,'IsoAnimal') then target=nil end
    end
    return target and target:getOnlineID()==id and target or nil
end
local function number(v) return type(v)=='number' and v==v and v>-math.huge and v<math.huge end
-- Stage 2 (players and world objects): what a contact may hit, the kinds of the client's rows.
A.KINDS={zombie=true,animal=true,player=true,window=true,door=true,vehicle=true,['vehicle-glass']=true}
-- A window or door borders its square; the round may meet it from either side: the square widened by one square.
A.OBJECT_REACH=1.5
-- Who a client-flown bullet may hurt (W11, user decision of 28.10.2026: PvP when the admin and the server settings allow
-- it). ShotServerPvp ported (PZ checks PvP on the attacking client only): read at every hit, so an admin's change acts at
-- once. Zombies and animals are always hittable; the god mode of the target is checked by every kind.
function A.pvp(shooter,victim)
    local options=getServerOptions()
    if not options:getBoolean('PVP') then return 'server-pvp-off' end
    if options:getBoolean('SafetySystem') and shooter:getSafety():isEnabled() and victim:getSafety():isEnabled() then return 'safety' end
    if NonPvpZone.getNonPvpZone(math.floor(victim:getX()),math.floor(victim:getY())) then return 'target-non-pvp-zone' end
    if NonPvpZone.getNonPvpZone(math.floor(shooter:getX()),math.floor(shooter:getY())) then return 'shooter-non-pvp-zone' end
    local own,other=Faction.getPlayerFaction(shooter),Faction.getPlayerFaction(victim)
    if other and own==other then return 'same-faction' end
    return nil
end
-- TarkovPenetration.bleeding ported: the proposal for a wound of <damage> on a part of <maxHealth>, PZ's own part maximum
-- (ShotBleedingPz.PART_MAX_HEALTH 100), the health cap 1 (as ShotBleedingPz), the round's biases (TW/Data/Ammo limits).
function A.bleeding(round,damage,maxHealth,cap,blocked,existingHeavy,heavyRoll,lightRoll)
    if blocked then return 'NONE' end
    local spec=Ammo.limits.bleeding
    local f=damage/maxHealth
    if f>=spec.heavy.threshold and heavyRoll*cap<spec.heavy.k*f+spec.heavy.b+(round and round.heavy or 0) then return 'HEAVY' end
    if not existingHeavy and f>=spec.light.threshold and lightRoll*cap<spec.light.k*f+spec.light.b+(round and round.light or 0) then
        return 'LIGHT'
    end
    return 'NONE'
end
-- The window or door of a row on its square (sprite and kind as the client saw them), and its material.
function A.object(c)
    local sq=getCell():getGridSquare(math.floor(c.tx),math.floor(c.ty),math.floor(c.tz))
    local sprite,material=tostring(c.object):match('^(.*)|(%a+)$')
    if not sq or not sprite then return nil end
    local class=c.kind=='window' and 'IsoWindow' or 'IsoDoor'
    for _,list in ipairs({sq:getSpecialObjects(),sq:getObjects()}) do
        for i=0,list:size()-1 do
            local o=list:get(i)
            if instanceof(o,class) and (o:getSpriteName() or '')==sprite then return o,material end
        end
    end
    return nil
end
-- ShotDamagePz.eligibleWorld on the server's object: a pane that is whole and closed, a barricade, a door in the state the
-- round met it.
function A.eligible(o,c,material)
    if c.kind=='window' then
        if material=='barricade' then return o:isBarricaded() end
        return material=='glass' and not o:IsOpen() and not o:isSmashed() and not o:isDestroyed() and not o:isBarricaded()
    end
    return material=='door' and o:IsOpen()==(c.state==1)
end
-- A vehicle's reach from its position: half its script extents' diagonal (PZ units, a square each) plus the body root.
function A.vehicleReach(v)
    local ok,reach=pcall(function()
        local e=v:getScript():getExtents()
        return 0.5*math.sqrt(e:x()*e:x()+e:z()*e:z())*A.CELL
    end)
    return (ok and type(reach)=='number' and reach or 3)+A.BODY.root
end
-- The checks of one contact (the document's list, items 3-7); nil when it passes, else the refusal reason.
function A.check(player,p,ticket,row,c)
    if type(c)~='table' then return 'malformed' end
    if not A.KINDS[c.kind] then return 'kind' end
    for _,k in ipairs({'x','y','z','tx','ty','tz','damage','applied','range','pellet','ordinal','ragdoll','part','min','max','state'}) do
        if not number(c[k]) then return 'malformed' end
    end
    if c.pellet<0 or c.pellet%1~=0 or c.pellet>=ticket.pellets then return 'pellets' end
    if c.ordinal<0 or c.ordinal%1~=0 or ticket.contacts>=A.MAX_CONTACTS then return 'contacts' end
    local key=c.pellet..'/'..c.ordinal
    if ticket.keys[key] then return 'repeated' end
    if not ticket.limits then return 'ammo-unknown' end
    -- From the muzzle's player, or for fragments from the detonation point.
    local o=ticket.origin
    local dx,dy,dz=(c.x-(o and o.x or player:getX()))*A.CELL,(c.y-(o and o.y or player:getY()))*A.CELL,(c.z-(o and o.z or player:getZ()))*A.LEVEL
    if math.sqrt(dx*dx+dy*dy+dz*dz)>ticket.range then return 'range' end
    local weapon=ticket.weapon
    if c.min<0 or c.max<c.min then return 'damage' end
    if c.kind=='window' or c.kind=='door' then
        local o,material=A.object(c)
        if not o then return 'target-unknown' end
        if math.abs(c.x-(math.floor(c.tx)+0.5))>A.OBJECT_REACH or math.abs(c.y-(math.floor(c.ty)+0.5))>A.OBJECT_REACH
            or math.abs(c.z-math.floor(c.tz))>1 then return 'target-far' end
        if not A.eligible(o,c,material) then return 'target-state' end
        return nil,o,key,1
    end
    if c.kind=='vehicle' or c.kind=='vehicle-glass' then
        local v=getVehicleById(c.id)
        if not v or not v:getSquare() then return 'target-unknown' end
        local rx,ry=(c.tx-v:getX())*A.CELL,(c.ty-v:getY())*A.CELL
        local hx,hy=(c.x-v:getX())*A.CELL,(c.y-v:getY())*A.CELL
        if math.sqrt(rx*rx+ry*ry)>A.BODY.root or math.sqrt(hx*hx+hy*hy)>A.vehicleReach(v) then return 'target-far' end
        if c.kind=='vehicle' then
            if ticket.vehicles[c.id] then return 'repeated' end
            -- PZ's damage roll of the copy (Rand(min, max) x HandWeapon.getDamageMod, at most 1.2).
            if c.damage<0 or c.damage>math.min(c.max,weapon:getMaxDamage())*1.2 then return 'damage' end
            return nil,v,key,1
        end
        local part=v:getPartById(tostring(c.object))
        local window=part and part:getWindow()
        local door=part and part:getDoor()
        local parent=part and part:getParent()
        if not window or not part:getInventoryItem() or not window:isHittable() or window:isDestroyed() or window:isOpen()
            or door and door:isOpen() or parent and parent:getDoor() and parent:getDoor():isOpen() then return 'target-state' end
        return nil,part,key,1
    end
    local target=A.target(player,c.kind,c.id)
    if not target or target==player then return 'target-unknown' end
    if target:isDead() then return 'target-dead' end
    if target:isGodMod() or target:isInvulnerable() then return 'target-protected' end
    if c.kind=='player' then
        local reason=A.pvp(player,target)
        if reason then return reason end
        if c.part<0 or c.part%1~=0 or c.part>=BodyPartType.ToIndex(BodyPartType.MAX) then return 'malformed' end
    end
    local B=A.BODY
    local rx,ry=(c.tx-target:getX())*A.CELL,(c.ty-target:getY())*A.CELL
    if math.sqrt(rx*rx+ry*ry)>B.root or math.abs(c.tz-target:getZ())>B.levels then return 'target-far' end
    local reach=c.kind=='animal' and A.ANIMAL_REACH or B.reach
    local hx,hy,up=(c.x-c.tx)*A.CELL,(c.y-c.ty)*A.CELL,(c.z-c.tz)*A.LEVEL
    if math.sqrt(hx*hx+hy*hy)>reach or up<-B.below or up>B.above then return 'target-far' end
    local range=weapon:isRangeFalloff() and 2 or 1
    if c.range~=range then return 'range-multiplier' end
    if c.applied<0 or c.applied>A.damageCap(ticket) then return 'damage' end
    return nil,target,key,range
end
-- The server's copy of the shooter's weapon for a player's wound or a world object: its damage range as the round's copy
-- had it, never above the real weapon's (DamageFromWeapon and WeaponHit read the type, the firearm flag, min and max).
function A.view(ticket,c)
    local weapon=ticket.weapon
    local view=instanceItem(weapon:getFullType())
    view:setMinDamage(math.min(c.min,weapon:getMinDamage()))
    view:setMaxDamage(math.max(math.min(c.max,weapon:getMaxDamage()),math.min(c.min,weapon:getMinDamage())))
    return view
end
-- PZ's own server calls for one accepted contact. Returns whether it was a character hit (XP, relay).
function A.apply(player,ticket,c,target,range)
    if c.kind=='zombie' or c.kind=='animal' then
        -- The shooter's critical flag, then Hit with the damage as reported (the sixth argument skips processHitDamage,
        -- which the shooter's Hit already ran), as vanilla's WeaponHit.process.
        player:setCriticalHit(c.crit==true)
        target:Hit(ticket.weapon,player,c.applied,false,range,true)
        return true
    end
    if c.kind=='player' then
        -- The exact zone: DamageFromWeapon with the part (part >= 0 is taken as is); no Hit, which would add a second
        -- wound in a random part (IsoPlayer.hitConsequences). Then the Tarkov bleeding of that wound (ShotBleedingPz.apply)
        -- with the server's own rolls, and the part's sync.
        local body=target:getBodyDamage()
        local part=body:getBodyPart(BodyPartType.FromIndex(c.part))
        local before,heavy=part:getHealth(),part:isDeepWounded()
        body:DamageFromWeapon(A.view(ticket,c),c.part)
        local damage=math.max(0,before-part:getHealth())
        local blocked=damage<=0 or part:bandaged() or part:IsBleedingStemmed() or part:IsCauterized()
        local proposal=A.bleeding(ticket.round,damage,100,1,blocked,heavy,ZombRandFloat(0,1),ZombRandFloat(0,1))
        local existing=part:getBleedingTime()
        if proposal=='HEAVY' and not part:isDeepWounded() then part:generateDeepWound()
        elseif proposal~='NONE' then part:generateBleeding() end
        if proposal~='NONE' and part:getBleedingTime()<existing then part:setBleedingTime(existing) end
        syncBodyPart(part,0xFFFFFFFFFFF)
        A.last='player part='..c.part..' damage='..string.format('%.2f',damage)..' bleeding='..proposal
        return true
    end
    if c.kind=='window' or c.kind=='door' then
        -- One native operation handles the window's barricade/pane or the door (ShotDamagePz), synced by PZ.
        target:WeaponHit(player,A.view(ticket,c))
        return false
    end
    if c.kind=='vehicle-glass' then
        -- ShotVehicleGlass.breakPane: the pane the round crossed takes its whole health.
        local window=target:getWindow()
        window:damage(window:getHealth())
        return false
    end
    -- A vehicle's body: PZ's processHit picks the part, once per shot and vehicle (ShotDamagePz.vehicleHit).
    ticket.vehicles[c.id]=true
    target:processHit(player,A.view(ticket,c),c.damage)
    return false
end
-- PZ's own Aiming XP of a confirmed hit: vanilla's handler (server/XpSystem/XpUpdate.lua, which WeaponHit.onHit raises
-- through OnWeaponHitXp with hit count 1; Lua's triggerEvent carries four arguments, not five). One per target and
-- shot, one per shot for a weapon with range falloff (ShotXp's rule).
function A.xp(player,ticket,target,damage)
    if ticket.noXp then return false end
    local weapon=ticket.weapon
    if not (xpUpdate and xpUpdate.onWeaponHitXp) or not weapon:getScriptItem() then return false end
    if weapon:isRangeFalloff() then
        if ticket.xpShot then return false end
        ticket.xpShot=true
    else
        if ticket.xp[target] then return false end
        ticket.xp[target]=true
    end
    local ok,err=pcall(xpUpdate.onWeaponHitXp,player,weapon,target,damage,1)
    if not ok then console('xp','XP event failed: '..tostring(err));return false end
    A.stats.xp=A.stats.xp+1
    return true
end
local function relay(player,command,payload)
    if not N.payload({payload=payload}) then return 0 end
    local players,count=getOnlinePlayers(),0
    for i=0,players:size()-1 do
        local other=players:get(i)
        if other~=player then sendServerCommand(other,N.module,command,{payload=payload});count=count+1 end
    end
    return count
end
-- Stage 3: a frag grenade the server detonated for a thrower in client authority. A server without the mod's Java cannot
-- fly the fragments (HandGrenadeNative): it asks the thrower's client to (Fragments) and opens the detonation's ticket -
-- the fragment count, the template's limits (TW/Data/Ammo fragments), the point, and HandGrenadeNative's native damage
-- range of a fragment (count x Tarkov damage x 0.012..0.020) on a Base.Pistol copy. No Aiming XP: an explosion is no shot
-- (ShotXp). Returns true when the thrower was asked.
function A.grenade(thrower,s,row)
    local api=rawget(_G,'ShotServerLua')
    local mode=SandboxVars and SandboxVars.EscapeFromZomboidFirearms and SandboxVars.EscapeFromZomboidFirearms.ViewpointBallistics
    if not N.live(mode) or type(api)=='table' and api.ready and api.ready()==true then return false end
    local p=thrower and A.peers[thrower]
    local frag=Ammo.limits and Ammo.limits.fragments and Ammo.limits.fragments[row.FragmentType]
    if not p or not p.authority or not frag or type(s.id)~='string' then return false end
    local count=row.FragmentsCount
    local view=instanceItem('Base.Pistol')
    view:setMinDamage(count*frag.damage*0.012);view:setMaxDamage(count*frag.damage*0.020)
    local t=now()
    p.grenades=p.grenades or {}
    p.grenades[s.id]={grenade=s.id,weapon=view,id=-1,ammo=row.FragmentType,at=t,expires=t+frag.life*1000+A.LATENCY_MS,pellets=count,
        max=view:getMaxDamage(),crit=view:getCriticalDamageMultiplier(),range=frag.range,limits=true,round=frag,contacts=0,keys={},
        xp={},xpShot=true,noXp=true,vehicles={},origin={x=s.x,y=s.y,z=s.z}}
    sendServerCommand(thrower,N.module,'Fragments',{id=s.id,tpl=row.tpl,fragment=row.FragmentType,count=count,seed=s.seed,x=s.x,y=s.y,z=s.z})
    A.stats.grenades=A.stats.grenades+1
    return true
end
-- One Hits row of a player (TW_ShotOwnerNet server's OnClientCommand). Returns the answer to the shooter, or nil while
-- the round's ticket has not arrived (the row waits in pending up to A.LATENCY_MS).
function A.hits(player,args,policy,queued)
    local p=A.peers[player]
    local seq=type(args)=='table' and args.seq
    if type(seq)~='string' or not N.uint64(seq) then return {seq='',accepted=0,refused={{key='',reason='malformed'}}} end
    local function all(reason)
        local list={}
        local contacts=type(args.contacts)=='table' and args.contacts or {}
        for i=1,math.min(#contacts,A.ROW_CONTACTS) do
            local c=contacts[i]
            list[#list+1]={key=type(c)=='table' and tostring(c.pellet)..'/'..tostring(c.ordinal) or '',reason=reason}
        end
        if #list==0 then list[1]={key='',reason=reason} end
        A.stats.refused=A.stats.refused+#list;A.last=reason
        console(reason,'refused '..player:getUsername()..' shot='..seq..' reason='..reason)
        return {seq=seq,accepted=0,refused=list}
    end
    if not p or not p.authority or not N.live(policy) then return all('mode') end
    -- A round's view, or 'grenade' for the fragments of a detonation (rows naming it).
    local view=args.grenade==nil and (args.view=='viewpoint' or args.view=='isometric') or args.grenade~=nil and args.view=='grenade'
    if type(args.contacts)~='table' or type(args.marks)~='table' or type(args.weapon)~='number'
        or not view or #args.contacts>A.ROW_CONTACTS or #args.marks>A.ROW_MARKS then
        return all('malformed')
    end
    if args.view=='isometric' and policy~=N.ISOMETRIC then return all('server-isometric-off') end
    local ticket,why
    if args.grenade~=nil then
        -- Fragments: the detonation's own ticket, opened before the thrower was asked.
        ticket=type(args.grenade)=='string' and p.grenades and p.grenades[args.grenade]
        if not ticket then return all('no-ticket') end
    else
        ticket,why=A.bind(p,seq,args.weapon)
        if not ticket then
            if not queued then return nil end
            return all(why)
        end
        if ticket.id~=args.weapon then return all('weapon-mismatch') end
    end
    if now()>ticket.expires then A.stats.expired=A.stats.expired+1;return all('ticket-expired') end
    A.stats.rows=A.stats.rows+1
    local accepted,refused=0,{}
    for i=1,#args.contacts do
        local c=args.contacts[i]
        A.stats.contacts=A.stats.contacts+1
        local reason,target,key,range=A.check(player,p,ticket,args,c)
        if not reason then
            ticket.keys[key]=true;ticket.contacts=ticket.contacts+1
            local ok,character=pcall(A.apply,player,ticket,c,target,range)
            local err=character
            if ok then
                accepted=accepted+1;A.stats.applied=A.stats.applied+1
                A.stats.kinds[c.kind]=(A.stats.kinds[c.kind] or 0)+1
                if character then
                    A.xp(player,ticket,target,c.applied)
                    if type(c.hit)=='string' then A.stats.relayed=A.stats.relayed+relay(player,'Hit',c.hit) end
                end
            else
                A.stats.errors=A.stats.errors+1;reason='apply-error'
                console('apply-error','apply failed: '..tostring(err))
            end
        end
        if reason then
            refused[#refused+1]={key=tostring(c and c.pellet)..'/'..tostring(c and c.ordinal),reason=reason}
            A.stats.refused=A.stats.refused+1;A.last=reason
            console(reason,'refused '..player:getUsername()..' shot='..seq..' key='..refused[#refused].key..' reason='..reason)
        end
    end
    if ticket.grenade then
        local f=A.stats.fragments
        f.rows=f.rows+1;f.contacts=f.contacts+#args.contacts;f.applied=f.applied+accepted
        for _,r in ipairs(refused) do f.refused[r.reason]=(f.refused[r.reason] or 0)+1 end
    end
    for i=1,#args.marks do
        local mark=args.marks[i]
        if ticket.contacts<A.MAX_CONTACTS and N.payload({payload=mark}) then
            ticket.contacts=ticket.contacts+1;A.stats.marks=A.stats.marks+1
            relay(player,'Mark',mark)
        end
    end
    return {seq=seq,accepted=accepted,refused=refused}
end
-- A row whose ticket has not arrived yet waits; OnTick retries it and refuses it after A.LATENCY_MS.
function A.receive(player,args,policy)
    local answer=A.hits(player,args,policy,false)
    if answer then return answer end
    local p=A.peers[player]
    if #p.pending>=A.MAX_TICKETS then return A.hits(player,args,policy,true) end
    table.insert(p.pending,{args=args,at=now()})
    return nil
end
function A.tick(policy,reply)
    tickCount=tickCount+1
    local t=now()
    for player,p in pairs(A.peers) do
        local keep={}
        for _,row in ipairs(p.pending) do
            local answer=A.hits(player,row.args,policy,t-row.at>=A.LATENCY_MS)
            if answer then reply(player,'Result',answer) else keep[#keep+1]=row end
        end
        p.pending=keep
        -- Expired tickets go; a bound one stays while its round may still report.
        local tickets={}
        for _,ticket in ipairs(p.tickets) do if t<=ticket.expires then tickets[#tickets+1]=ticket end end
        p.tickets=tickets
        for seq,ticket in pairs(p.shots) do if t>ticket.expires+A.LATENCY_MS then p.shots[seq]=nil end end
        for id,ticket in pairs(p.grenades or {}) do if t>ticket.expires+A.LATENCY_MS then p.grenades[id]=nil end end
    end
end
function A.kindsText()
    local out={}
    for _,k in ipairs({'zombie','animal','player','window','door','vehicle','vehicle-glass'}) do
        if A.stats.kinds[k] then out[#out+1]=k..':'..A.stats.kinds[k] end
    end
    return '{'..table.concat(out,',')..'}'
end
-- The fragment rows of the detonations (stage 3): rows, contacts, applied and the refusals by reason.
function A.fragmentsText()
    local f,reasons=A.stats.fragments,{}
    for reason,count in pairs(f.refused) do reasons[#reasons+1]=reason..':'..count end
    table.sort(reasons)
    return '{rows='..f.rows..',contacts='..f.contacts..',applied='..f.applied..',refused={'..table.concat(reasons,',')..'}}'
end
function A.status()
    local s=A.stats
    return 'authority hello='..s.hello..' tickets='..s.tickets..' rows='..s.rows..' contacts='..s.contacts..' applied='..s.applied
        ..' refused='..s.refused..' relayed='..s.relayed..' marks='..s.marks..' xp='..s.xp..' expired='..s.expired
        ..' errors='..s.errors..' grenades='..s.grenades..' fragments='..A.fragmentsText()..' kinds='..A.kindsText()..' last='..A.last
end
if Events and Events.OnWeaponSwingHitPoint and isServer and isServer() then
    Events.OnWeaponSwingHitPoint.Add(function(player,weapon) pcall(A.ticket,player,weapon) end)
end
return A
