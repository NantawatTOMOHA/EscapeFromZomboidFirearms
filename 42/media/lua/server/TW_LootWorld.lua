-- World replacer of plan 0.9.x (docs/PLAN_LOOT_RU.md 3.5a): vanilla firearms, their magazines and
-- attachments and vanilla ammunition that world stories create outside the loot tables (Java
-- randomized buildings, dead survivors and zone stories, StoryClutter) are replaced by Tarkov items
-- of the same kind when VanillaFirearms / VanillaParts / VanillaAmmo is not 1 ("rarer": each with
-- probability 0.5). Weapon parts follow their own option (W9-vanparts). The attachments the game
-- attached to a vanilla firearm are taken off here as well (Loot.stripVanillaParts). Rooms when a
-- player first sees them (OnSeeNewRoom; building stories run when the building loads, earlier);
-- outside rooms the first load of a chunk, processed a few seconds later (after zone stories) and
-- remembered in the world's ModData, never twice. Containers (and bags in them), items on the floor and
-- corpses; never a player's inventory, never a room already seen or a chunk already processed. With
-- "rarer" the loot tables already halved the vanilla items, so the ones the tables or zombies made are
-- marked at fill/death (TWLootKeep) and left.
local Loot=require('TW/Loot')
local LI=require('TW/LootItems')
local D=require('TW/Data')

local W={stats={rooms=0,chunks=0,items=0,replaced=0,removed=0,parts=0,ms=0,maxMs=0,tickets=0},pending={},queue={},queueHead=1,queueTail=0}
TWLootWorldAPI=W

-- W11 loot (part B): false keeps the pass that replaces the representatives a container fill never
-- reached; the QA case switches it off to show the world replacer before the fix in the same run.
function W.qaNoTickets(off) W.noTickets=off and true or false end

local CHUNK=8
local DELAY_MS=3000
local PER_TICK=2
-- Performance pass (23.10.2026): a chunk is 8x8 columns of 9 levels, and two whole chunks a tick cost a few ms each
-- while the player moved (a new ring of chunks loads all the time): a stutter at every step of a drive. The tick now
-- goes through at most SQUARES_PER_TICK squares (the same squares in the same order, a chunk over a few ticks;
-- each waits DELAY_MS before it starts anyway).
local SQUARES_PER_TICK=144
local LEVELS=9

local function options()
    return Loot.options()
end

-- Which vanilla item sets are replaced now, and with what probability. The attachments of vanilla
-- firearms are a set of their own (W9-vanparts, "Vanilla attachments"); "Like vanilla firearms" follows
-- VanillaFirearms as before, and the attachments the game put on those firearms are stripped with the
-- same probability (Loot.stripVanillaParts: 1 / 0.5), so the pass also runs when only they are asked for.
function W.active(o)
    local vf,va=o.VanillaFirearms,o.VanillaAmmo
    local vp=Loot.vanillaValue('parts',o)
    local attached=(o.VanillaParts==2 or o.VanillaParts==3)
    if vf==1 and va==1 and vp==1 and not attached then return nil end
    local p={}
    local function prob(v) if v==3 then return 1 elseif v==2 then return 0.5 end return 0 end
    p.firearms,p.magazines=prob(vf),prob(vf)
    p.parts=prob(vp)
    p.ammo=prob(va)
    return p
end

local function replacementItems(item,ctx,o,rng)
    local choice=Loot.replacement(item:getFullType(),ctx,o,rng,true)
    if not choice then return {} end
    local items=LI.create(choice)
    if instanceof(item,'HandWeapon') then
        local weapon=items[1]
        if weapon and instanceof(weapon,'HandWeapon') then
            LI.condition(item,weapon)
            local max=item:getMaxAmmo() or 0
            local loaded=(max>0 and item:getCurrentAmmoCount()>0) and math.min(1,item:getCurrentAmmoCount()/max) or 0
            weapon:getModData().TWLootEngine={mags=0,boxes=0,loaded=loaded}
            if TWLootFillAPI then TWLootFillAPI.prepareWeapon(weapon,nil,{ctx=ctx,noKit=true},o,rng) end
        end
    end
    return items
end

-- Items of a container (and of bags in it): replaced in place.
function W.container(container,ctx,o,rng,p,depth,people)
    if not container or (depth or 0)>3 then return end
    local items=container:getItems()
    local list={}
    for i=0,items:size()-1 do list[#list+1]=items:get(i) end
    for _,item in ipairs(list) do
        W.stats.items=W.stats.items+1
        if instanceof(item,'InventoryContainer') then
            W.container(item:getInventory(),ctx,o,rng,p,(depth or 0)+1,people)
        end
        local set=Loot.vanillaSet(item:getFullType())
        local chance=set and not item:getModData().TWLootKeep and p[set] or 0
        local replaced=false
        if chance>0 and (chance>=1 or rng()<chance) then
            local new=replacementItems(item,ctx,o,rng)
            container:Remove(item)
            for _,n in ipairs(new) do container:AddItem(n) end
            if #new>0 then W.stats.replaced=W.stats.replaced+1 else W.stats.removed=W.stats.removed+1 end
            replaced=true
        end
        -- W9-vanparts: "Vanilla attachments" 2/3 also take off what the game attached to a vanilla firearm
        -- (the same weapon is marked once, so the fill pass and this pass never roll it twice).
        if set=='firearms' and not replaced then W.stats.parts=W.stats.parts+Loot.stripVanillaParts(item,o,rng) end
    end
    -- W11 loot (part B): the representatives a container fill never reached (map stashes, story
    -- buildings: no OnFillContainer). After the vanilla replacement above, never before it:
    -- TWLootFill.fill marks the vanilla items it sees as kept ("rarer"), and a story vanilla
    -- firearm marked there would never be replaced here.
    if not W.noTickets then
        W.stats.tickets=W.stats.tickets+(TWLootFillAPI and TWLootFillAPI.tickets(container,o,rng,people) or 0)
    end
    -- W11 milloot (item 6): a nested bag of a swept container that the engine left empty is filled
    -- and its tickets resolved, the same pass the event and the ZB sweep use.
    if TWLootFillAPI and TWLootFillAPI.nested then TWLootFillAPI.nested(container,o,rng) end
end

-- One square: object containers, items on the floor, corpses.
function W.square(sq,ctx,o,rng,p)
    local objects=sq:getObjects()
    for i=0,objects:size()-1 do
        local obj=objects:get(i)
        if obj and obj.getContainerCount then
            for c=0,obj:getContainerCount()-1 do W.container(obj:getContainerByIndex(c),ctx,o,rng,p,0) end
        end
    end
    local bodies=sq:getDeadBodys()
    if bodies then
        for i=0,bodies:size()-1 do
            local body=bodies:get(i)
            if body and body.getContainer then
                -- An animal carcass has no human visual, and IsoDeadBody.getOutfitName() throws then (it reads
                -- getHumanVisual().getOutfit()); the outfit scope belongs to human corpses only.
                local human=body.getHumanVisual and body:getHumanVisual()
                local people=TWLootFillAPI and human and body.getOutfitName and TWLootFillAPI.zombiePeople(body:getOutfitName()) or nil
                W.container(body:getContainer(),ctx,o,rng,p,0,people)
            end
        end
    end
    local world=sq:getWorldObjects()
    if world and world:size()>0 then
        local list={}
        for i=0,world:size()-1 do list[#list+1]=world:get(i) end
        for _,wo in ipairs(list) do
            local item=wo:getItem()
            if item then
                W.stats.items=W.stats.items+1
                if instanceof(item,'InventoryContainer') then W.container(item:getInventory(),ctx,o,rng,p,1) end
                local set=Loot.vanillaSet(item:getFullType())
                local chance=set and not item:getModData().TWLootKeep and p[set] or 0
                local replaced=false
                if chance>0 and (chance>=1 or rng()<chance) then
                    local new=replacementItems(item,ctx,o,rng)
                    sq:transmitRemoveItemFromSquare(wo)
                    wo:removeFromWorld()
                    wo:removeFromSquare()
                    for _,n in ipairs(new) do sq:AddWorldInventoryItem(n,0.2+rng()*0.6,0.2+rng()*0.6,0) end
                    if #new>0 then W.stats.replaced=W.stats.replaced+1 else W.stats.removed=W.stats.removed+1 end
                    replaced=true
                end
                if set=='firearms' and not replaced then W.stats.parts=W.stats.parts+Loot.stripVanillaParts(item,o,rng) end
                -- W11 loot (part B): a representative on the floor, the same bypass as in a container.
                if not replaced and not W.noTickets and TWLootFillAPI and Loot.isTicket(item:getFullType())
                   and not LI.isMarked(item) then
                    local new=TWLootFillAPI.representative(item,o,rng)
                    if new then
                        sq:transmitRemoveItemFromSquare(wo)
                        wo:removeFromWorld()
                        wo:removeFromSquare()
                        for _,n in ipairs(new) do sq:AddWorldInventoryItem(n,0.2+rng()*0.6,0.2+rng()*0.6,0) end
                        W.stats.tickets=W.stats.tickets+#new
                    end
                end
            end
        end
    end
end

local function timed(fn)
    local t0=getTimestampMs()
    fn()
    local ms=getTimestampMs()-t0
    W.stats.ms=W.stats.ms+ms
    if ms>W.stats.maxMs then W.stats.maxMs=ms end
end

function W.room(room,o,rng)
    o=o or options()
    local p=W.active(o)
    if not p or not room then return end
    rng=rng or Loot.rng
    local ctx=Loot.placeOf('rooms/'..tostring(room:getName()))
    timed(function()
        local squares=room:getSquares()
        for i=0,squares:size()-1 do W.square(squares:get(i),ctx,o,rng,p) end
    end)
    W.stats.rooms=W.stats.rooms+1
end

local function processed()
    return ModData.getOrCreate('TWLootWorld')
end

-- FIFO without shifting every pending chunk after each completion. Keep the queue object and
-- release each job immediately; an empty queue restarts its indices rather than growing forever.
function W.queued() return W.queueTail-W.queueHead+1 end
local function popJob()
    W.queue[W.queueHead]=nil
    W.queueHead=W.queueHead+1
    if W.queueHead>W.queueTail then W.queueHead=1;W.queueTail=0 end
end

-- Rooms in multiplayer. B42 queues a spotted room from the client's renderer: IsoRoom.onSee marks
-- every room of a building and calls IsoCell.roomSpotted, which is reached through FBORenderCell,
-- and IsoCell.ProcessSpottedRooms turns that queue into OnSeeNewRoom. A dedicated or hosted server
-- has no renderer, so it only gets the event for a room a server-side player walks into - a room a
-- player merely looks into through a window, or never enters, keeps the vanilla firearms its
-- building story placed (a gun cabinet, a gun store shelf, a police or army room). In single player
-- the event is exactly what turns a story gun into a Tarkov one the first time the player looks at
-- the room, so there the room pass stays; on a real server the chunk pass takes the rooms of the
-- chunk it already walks, at the room's own place, and every room of a loaded chunk is done once
-- without waiting for anybody's eyes.
local serverRooms
local function ownsRooms()
    if serverRooms==nil then serverRooms=isServer() and not isClient() end
    return serverRooms
end

-- One square with its place: the room's place on a server that owns the rooms, nothing inside a
-- room otherwise, the civil place outside rooms.
function W.squareAt(sq,o,rng,p,withRooms,cache)
    local room=sq:getRoom()
    if not room then W.square(sq,'civil',o,rng,p);return end
    if not withRooms then return end
    local name=room:getName()
    local ctx=cache[name]
    if not ctx then
        ctx=Loot.placeOf('rooms/'..tostring(name))
        cache[name]=ctx
    end
    W.square(sq,ctx,o,rng,p)
end

function W.chunk(cx,cy,o,rng)
    o=o or options()
    local p=W.active(o)
    if not p then return end
    rng=rng or Loot.rng
    local cell=getCell()
    -- Rooms belong to OnSeeNewRoom in single player (seen later, after the building's stories); a
    -- server has no renderer-driven room event and takes them here.
    local rooms,cache=ownsRooms(),{}
    timed(function()
        for x=cx*CHUNK,cx*CHUNK+CHUNK-1 do
            for y=cy*CHUNK,cy*CHUNK+CHUNK-1 do
                for z=-1,7 do
                    local sq=cell:getGridSquare(x,y,z)
                    if sq then W.squareAt(sq,o,rng,p,rooms,cache) end
                end
            end
        end
    end)
    W.stats.chunks=W.stats.chunks+1
end

-- W.chunk in parts: from square index job.next (x, then y, then z from -1, as above) through at most budget squares.
-- Returns the budget left, or nil when the chunk is finished.
function W.chunkPart(job,budget)
    if not job.p then
        job.o=options();job.p=W.active(job.o);job.next=0;job.rooms=ownsRooms();job.cache={}
        if not job.p then return budget end
    end
    local o,p,rng=job.o,job.p,Loot.rng
    local cell=getCell()
    local total=CHUNK*CHUNK*LEVELS
    local i=job.next
    local x=job.cx*CHUNK+math.floor(i/(CHUNK*LEVELS))
    local y=job.cy*CHUNK+math.floor(i/LEVELS)%CHUNK
    local z=i%LEVELS-1
    timed(function()
        while i<total and budget>0 do
            local sq=cell:getGridSquare(x,y,z)
            if sq then W.squareAt(sq,o,rng,p,job.rooms,job.cache) end
            i=i+1;budget=budget-1
            z=z+1
            if z>=LEVELS-1 then
                z=-1;y=y+1
                if y>=(job.cy+1)*CHUNK then y=job.cy*CHUNK;x=x+1 end
            end
        end
    end)
    job.next=i
    if i<total then return nil,budget end
    W.stats.chunks=W.stats.chunks+1
    return budget
end

-- Lazily: the options of the world are known once the game runs.
local enabled=nil
local function isEnabled()
    if enabled==nil then enabled=W.active(options())~=nil end
    return enabled
end
-- The chunk of the last loaded square (LoadGridsquare below).
local lastCx,lastCy
function W.reset() enabled=nil;lastCx,lastCy=nil,nil end

if Events.OnSeeNewRoom then
    Events.OnSeeNewRoom.Add(function(room)
        if isClient() or not isEnabled() then return end
        local ok,err=pcall(W.room,room)
        if not ok then print('[TW] loot world room failed: '..tostring(err)) end
    end)
end

-- Squares load chunk by chunk: the key string is built only when the chunk differs from the last square's.
if Events.LoadGridsquare then Events.LoadGridsquare.Add(function(sq)
    if isClient() or not isEnabled() or not sq then return end
    local x,y=sq:getX(),sq:getY()
    local cx,cy=math.floor(x/CHUNK),math.floor(y/CHUNK)
    if cx==lastCx and cy==lastCy then return end
    lastCx,lastCy=cx,cy
    local key=cx..','..cy
    if W.pending[key] then return end
    local done=processed()
    if done[key] then W.pending[key]=true; return end
    W.pending[key]=true
    W.queueTail=W.queueTail+1
    W.queue[W.queueTail]={key=key,cx=cx,cy=cy,at=getTimestampMs()+DELAY_MS}
end) end

Events.OnTick.Add(function()
    if W.queueHead>W.queueTail or isClient() then return end
    local now=getTimestampMs()
    local done=nil
    local n=0
    local budget=SQUARES_PER_TICK
    while W.queueHead<=W.queueTail and n<PER_TICK and budget>0 and W.queue[W.queueHead].at<=now do
        local job=W.queue[W.queueHead]
        done=done or processed()
        if done[job.key] and not job.next then
            popJob()
        else
            local ok,left,partial=pcall(W.chunkPart,job,budget)
            if not ok then print('[TW] loot world chunk failed: '..tostring(left));left=budget end
            if left==nil then budget=partial
            else
                -- finished (or failed): marked processed, never twice
                popJob()
                done[job.key]=true
                budget=left
            end
        end
        n=n+1
    end
end)

return W
