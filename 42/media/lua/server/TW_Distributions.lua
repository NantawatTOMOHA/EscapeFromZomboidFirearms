-- Loot tables of plan 0.9.x (docs/PLAN_LOOT_RU.md 3.4, D1-D2, D10): one pass on
-- OnPostDistributionMerge over ProceduralDistributions, SuburbsDistributions (rooms, zombie
-- outfits, bags) and VehicleDistributions. Every vanilla anchor entry gets a ticket entry
-- (TW/Loot: representative mod item, the anchor's weight times the options); then the vanilla
-- firearms and ammunition are halved or removed by VanillaFirearms / VanillaAmmo. Ticket
-- weights are taken before the vanilla pass, so "Do not spawn" leaves the same Tarkov total.
-- Last, every list is ordered by AmmoFirst / AttachmentsFirst (0.9.7).
require('TW/Loot')
local Loot=require('TW/Loot')

local TWLootTables={}
TWLootTables.lastReport=nil

local function sortedKeys(t)
    local strings,numbers={},{}
    for k in pairs(t) do
        if type(k)=='string' then strings[#strings+1]=k elseif type(k)=='number' then numbers[#numbers+1]=k end
    end
    table.sort(strings)
    table.sort(numbers)
    for _,k in ipairs(numbers) do strings[#strings+1]=k end
    return strings
end

-- Loot lists in a fixed order (Kahlua and Lua 5.1 iterate hashes differently): string keys
-- before numeric ones, so VehicleDistributions.PoliceTruckBed is reached by its own name
-- before the vehicle table at index 1. A table or an items array shared by several paths
-- (vanilla bags share one items array between containers) is visited once.
function TWLootTables.collect(roots)
    local seen,out={},{}
    local function walk(t,path,depth)
        if type(t)~='table' or seen[t] or depth>12 then return end
        seen[t]=true
        if type(t.items)=='table' and not seen[t.items] then
            seen[t.items]=true
            out[#out+1]={path=path,items=t.items}
        end
        for _,k in ipairs(sortedKeys(t)) do
            if k~='items' and k~='procList' and type(t[k])=='table' then walk(t[k],path..'/'..tostring(k),depth+1) end
        end
    end
    for _,root in ipairs(roots) do walk(root[2],root[1],0) end
    return out
end

function TWLootTables.roots()
    local roots={}
    if ProceduralDistributions and ProceduralDistributions.list then roots[#roots+1]={'procedural',ProceduralDistributions.list} end
    if SuburbsDistributions then roots[#roots+1]={'rooms',SuburbsDistributions} end
    if VehicleDistributions then roots[#roots+1]={'vehicles',VehicleDistributions} end
    return roots
end

-- A list the engine can parse: name/weight pairs (BGWL met a vanilla list with a stray number).
local function wellFormed(items)
    if #items%2~=0 then return false end
    for i=1,#items-1,2 do
        if type(items[i])~='string' or tonumber(items[i+1])==nil then return false end
    end
    return true
end
TWLootTables.wellFormed=wellFormed

-- The Louisville surplus gun counter is authored as armysurplusguns in the map, but B42's
-- distributions omit that room. Its display cases therefore take the generic household fallback,
-- while military lockers can still find GarageFirearms through the global container definitions.
-- Give missing armed-store rooms the existing gun-store rules before ItemPickerJava parses them.
-- An explicit room supplied by the game or another mod takes precedence. Sharing the table keeps
-- all pool weights, container rules and loot options identical to the established gun store.
TWLootTables.ROOM_FALLBACKS={
    armysurplusguns='gunstore',
    -- These names are authored by the installed maps but absent from the room table.
    -- Reuse the established room of the same purpose; no new rolls or weights.
    armory='policegunstorage',
    prisonarmory='prisonstorage',
    firearmtraining='gunstorestorage',
    contraband='evidenceroom',
    armyhangar='armystorage', -- Kingsmouth challenge map; not a civilian hangar.
    office_ranger='rangeroffice',
    -- The shooting complex has two real display cases inside its gunstorage room.
    -- Storage keeps the existing ammo/armor rules; only the display cases use the
    -- existing gun-store display rules. Clone the room so gunstorestorage is untouched.
    gunstorage={source='gunstorestorage',containers={displaycase={'gunstore','displaycase'}}},
    -- Exact case/whitespace mistakes in the vanilla map, not inferred room types.
    Bathroom='bathroom',Breakroom='breakroom',Grocery='grocery',Office='office',
    ['bathroom ']='bathroom',
}
function TWLootTables.roomFallbacks()
    local report={added=0,missing={}}
    local rooms=SuburbsDistributions
    if type(rooms)~='table' then return report end
    for _,name in ipairs(sortedKeys(TWLootTables.ROOM_FALLBACKS)) do
        if rooms[name]==nil then
            local spec=TWLootTables.ROOM_FALLBACKS[name]
            local source=type(spec)=='table' and spec.source or spec
            if type(rooms[source])=='table' then
                local mapped=rooms[source]
                local ok=true
                if type(spec)=='table' and spec.containers then
                    mapped={}
                    for key,value in pairs(rooms[source]) do mapped[key]=value end
                    for _,key in ipairs(sortedKeys(spec.containers)) do
                        local ref=spec.containers[key]
                        local def=type(rooms[ref[1]])=='table' and rooms[ref[1]][ref[2]] or nil
                        if type(def)=='table' then mapped[key]=def
                        else ok=false;report.missing[#report.missing+1]=name..'/'..key..'/'..ref[1]..'/'..ref[2] end
                    end
                end
                if ok then
                    rooms[name]=mapped;report.added=report.added+1
                    Loot.roomFallbacks=Loot.roomFallbacks or {}
                    Loot.roomFallbacks[name]=source
                end
            else
                report.missing[#report.missing+1]=name..'/'..source
            end
        end
    end
    return report
end

-- Some map room rules still name an older spelling of an existing vanilla pool.
-- Only equivalent existing pools are aliased: missing unfinished content is not invented.
-- Authored definitions from the game or another mod always win.
TWLootTables.POOL_FALLBACKS={
    CrateLeather_Large='CrateLeatherLarge',
    MedievalDisplayWeaponsJapan='MedievalWeaponsJapan',
    MedievalDisplayWeapons='MedievalWeapons',
    MedievalDisplayBible='MedievalBible',
    MedievalDisplayAlchemy='MedievalAlchemy',
    MedievalDisplayCooking='MedievalCooking',
    MedievalDisplayTools='MedievalTools',
}
function TWLootTables.poolFallbacks()
    local report={added=0,missing={}}
    local pools=ProceduralDistributions and ProceduralDistributions.list
    if type(pools)~='table' then return report end
    for _,name in ipairs(sortedKeys(TWLootTables.POOL_FALLBACKS)) do
        if pools[name]==nil then
            local source=TWLootTables.POOL_FALLBACKS[name]
            if type(pools[source])=='table' then pools[name]=pools[source];report.added=report.added+1
            else report.missing[#report.missing+1]=name..'/'..source end
        end
    end
    return report
end

-- W11 milloot (item 6, the user's option B, 29.10.2026): the share of a fill that reaches a weapon
-- pool. The engine picks one entry of a container's procList by weightChance (an entry without one
-- counts as 1: ItemPickerJava.fillContainerTypeInternal/getDistribInHashMap), so a military crate
-- reached ArmyStorageGuns in 10 of its 230 fills (0.043) and a metal shelf in 5 of 215 (0.023), and
-- the army tent locker and the old bunker lockers had no weapon pool in the vanilla tables at all:
-- the "Military (x)" option multiplies the tickets inside a pool and cannot reach a fill that never
-- picked one. Raised here for every player, no new option. Keyed by room + container + pool name; a
-- table the game renames is reported (missing) and the gate fails, never a silent no-op. The pools
-- themselves are untouched: their tickets, weights and every option stay as they are.
TWLootTables.MILITARY_POOLS={
    {room='armystorage',container='militarycrate',pool='ArmyStorageGuns',weight=60},
    {room='armystorage',container='metal_shelves',pool='ArmyStorageGuns',weight=25},
    -- The only pool of the tent locker has no weightChance (counts 1): it gets 100, so the added
    -- pool sits at 5 of 105 instead of 1 of 2.
    {room='armytent',container='locker',pool='ArmyStorageGuns',weight=5,add=true,
     base={{'LockerArmyBedroom',100}}},
    {room='oldarmy',container='locker',pool='ArmyStorageGuns',weight=7,add=true},
}

local function militaryEntry(procList,name)
    for i=1,#procList do
        local e=procList[i]
        if type(e)=='table' and e.name==name then return e end
    end
    return nil
end

-- The value each entry had before the first change (or that it did not exist), for the QA A/B of
-- TWLootTables.qaMilitaryPools. Keyed by path|name; a second pass keeps the first record.
local function militaryRecord(key,make)
    local list=TWLootTables.militaryOriginal
    if not list then list={}; TWLootTables.militaryOriginal=list end
    if not list[key] and make then list[key]=make() end
    return list[key]
end

-- Applies the weights of TWLootTables.MILITARY_POOLS to the game's own room tables; idempotent.
-- Returns the report of this pass: changed (weights written), missing (room/container/pool the
-- game does not have), pools (path, pool, weights, share of a fill).
function TWLootTables.militaryPoolWeights()
    local report={changed=0,missing={},pools={}}
    TWLootTables.militaryPoolWeightsLast=report
    local rooms=SuburbsDistributions
    for _,spec in ipairs(TWLootTables.MILITARY_POOLS) do
        local path=spec.room..'/'..spec.container
        local room=type(rooms)=='table' and rooms[spec.room] or nil
        local def=type(room)=='table' and room[spec.container] or nil
        local procList=type(def)=='table' and def.procList or nil
        if type(procList)~='table' then
            report.missing[#report.missing+1]=path
        else
            -- The entries this container keeps next to the weapon pool (a missing one skips the
            -- whole container: the share would not be the one this table asks for).
            local ok=true
            for _,b in ipairs(spec.base or {}) do
                local e=militaryEntry(procList,b[1])
                if not e then
                    ok=false
                    report.missing[#report.missing+1]=path..'/'..b[1]
                else
                    militaryRecord(path..'|'..b[1],function() return {entry=e,had=e.weightChance~=nil,weight=e.weightChance} end)
                    if e.weightChance~=b[2] then e.weightChance=b[2]; report.changed=report.changed+1 end
                end
            end
            local e=militaryEntry(procList,spec.pool)
            if ok then
                if e then
                    militaryRecord(path..'|'..spec.pool,function() return {entry=e,had=e.weightChance~=nil,weight=e.weightChance} end)
                    if e.weightChance~=spec.weight then e.weightChance=spec.weight; report.changed=report.changed+1 end
                elseif spec.add then
                    e={name=spec.pool,min=0,max=99,weightChance=spec.weight}
                    procList[#procList+1]=e
                    militaryRecord(path..'|'..spec.pool,function() return {added=true,procList=procList} end)
                    report.changed=report.changed+1
                else
                    ok=false
                    report.missing[#report.missing+1]=path..'/'..spec.pool
                end
            end
            if ok then
                local names,sum,weapon={},0,0
                for i=1,#procList do
                    local p=procList[i]
                    if type(p)=='table' then
                        local w=tonumber(p.weightChance) or 0
                        if w<=0 then w=1 end
                        sum=sum+w
                        if p.name==spec.pool then weapon=weapon+w end
                        names[#names+1]=p.name..'='..w
                    end
                end
                report.pools[#report.pools+1]={path=path,pool=spec.pool,weights=table.concat(names,','),share=weapon/math.max(1,sum)}
            end
        end
    end
    return report
end

-- QA only (W11 milloot): the build before this change in the same run. off=true restores every
-- weight the mod changed and removes the pools it added; off=false applies them again.
function TWLootTables.qaMilitaryPools(off)
    local list=TWLootTables.militaryOriginal or {}
    if not off then TWLootTables.militaryPoolWeights(); return true end
    local keys={}
    for key in pairs(list) do keys[#keys+1]=key end
    table.sort(keys)
    for _,key in ipairs(keys) do
        local r=list[key]
        if r.added then
            local name=key:match('|([^|]+)$')
            for i=#r.procList,1,-1 do
                if type(r.procList[i])=='table' and r.procList[i].name==name then table.remove(r.procList,i) end
            end
        elseif r.had then
            r.entry.weightChance=r.weight
        else
            r.entry.weightChance=nil
        end
    end
    return false
end

function TWLootTables.apply(o)
    if isClient() then return nil end
    if ProceduralDistributions and ProceduralDistributions.TWLootApplied then return TWLootTables.lastReport end
    -- A continued game: the save's options first (TW/WorldSandbox), not the main menu's.
    if not o then require('TW/WorldSandbox').load() end
    o=o or Loot.options()
    local report={lists=0,skipped={},tickets=0,ticketWeight=0,anchorWeight=0,removed=0,halved=0,byKind={},byPlace={},options=o}
    -- 0. Military containers (W11 milloot): the share of a fill that reaches a weapon pool, on the
    -- server's own room tables, once and idempotently. With Loot 0 the mod leaves the tables alone.
    if o.Loot>0 then
        report.pools=TWLootTables.poolFallbacks()
        if report.pools.added>0 or #report.pools.missing>0 then
            print(string.format('[TW] loot: %d missing procedural pools mapped, %d source pools missing (%s)',
                report.pools.added,#report.pools.missing,table.concat(report.pools.missing,', ')))
        end
        report.rooms=TWLootTables.roomFallbacks()
        if report.rooms.added>0 or #report.rooms.missing>0 then
            print(string.format('[TW] loot: %d missing store rooms mapped, %d source rooms missing (%s)',
                report.rooms.added,#report.rooms.missing,table.concat(report.rooms.missing,', ')))
        end
        local military=TWLootTables.militaryPoolWeights()
        report.military=military
        if military.changed>0 or #military.missing>0 then
            local shares={}
            for _,row in ipairs(military.pools) do shares[#shares+1]=string.format('%s %.3f',row.path,row.share) end
            print(string.format('[TW] loot: military pools: %d weights changed, %d missing (%s); share of a fill %s',
                military.changed,#military.missing,table.concat(military.missing,', '),table.concat(shares,', ')))
        end
    end
    local lists=TWLootTables.collect(TWLootTables.roots())
    -- 1. Tickets (budgets from the vanilla anchors, before the vanilla pass).
    for _,list in ipairs(lists) do
        local items=list.items
        if not wellFormed(items) then
            report.skipped[#report.skipped+1]=list.path
        elseif o.Loot>0 then
            local plan,ctx=Loot.plan(items,list.path,o)
            if #plan>0 then
                report.lists=report.lists+1
                report.byPlace[ctx]=(report.byPlace[ctx] or 0)+#plan
                for _,e in ipairs(plan) do
                    items[#items+1]=e[1]
                    items[#items+1]=e[2]
                    report.tickets=report.tickets+1
                    report.ticketWeight=report.ticketWeight+e[2]
                    local kind=Loot.data.tickets[e[1]].kind
                    report.byKind[kind]=(report.byKind[kind] or 0)+e[2]
                end
            end
        end
    end
    -- 1b. Concealed pistols in zombie pockets (D9): no vanilla anchor there; the weight of the
    -- vanilla Outfit_Trucker pistol, as a pistol ticket of a civil place.
    local all=SuburbsDistributions and SuburbsDistributions.all
    if o.Loot>0 and all then
        for _,name in ipairs({'inventorymale','inventoryfemale'}) do
            local items=all[name] and all[name].items
            if items and wellFormed(items) then
                local rep,w=Loot.ticket('Pistol',Loot.data.ccw,'civil',{pocket=true},o)
                if rep then
                    items[#items+1]=rep
                    items[#items+1]=w
                    report.tickets=report.tickets+1
                    report.ccw=(report.ccw or 0)+1
                end
            end
        end
    end
    -- 2. Vanilla firearms, their magazines and attachments, and vanilla ammunition (D10).
    for _,list in ipairs(lists) do
        local items=list.items
        if wellFormed(items) then
            for i=#items-1,1,-2 do
                local f=Loot.vanillaFactor(items[i],o)
                if f~=nil and f<1 then
                    if f<=0 then
                        table.remove(items,i+1)
                        table.remove(items,i)
                        report.removed=report.removed+1
                    else
                        items[i+1]=tonumber(items[i+1])*f
                        report.halved=report.halved+1
                    end
                end
            end
        end
    end
    -- 3. Order (AmmoFirst, AttachmentsFirst): ammunition, then parts and magazines, before
    -- or after the weapons. With Loot 0 the mod leaves the vanilla order.
    report.ordered,report.moved=0,0
    if o.Loot>0 then
        for _,list in ipairs(lists) do
            local items=list.items
            if wellFormed(items) then
                local moved=Loot.order(items,o)
                if moved>0 then
                    report.ordered=report.ordered+1
                    report.moved=report.moved+moved
                end
            end
        end
    end
    if ProceduralDistributions then ProceduralDistributions.TWLootApplied=true end
    TWLootTables.lastReport=report
    local kinds={}
    for kind,w in pairs(report.byKind) do kinds[#kinds+1]=kind..'='..string.format('%.2f',w) end
    table.sort(kinds)
    print(string.format('[TW] loot: %d lists, %d tickets (weight %.2f), %d vanilla entries removed, %d halved, %d malformed lists skipped; Loot=%s Guns=%s Ammo=%s Magazines=%s Parts=%s VanillaFirearms=%s VanillaAmmo=%s',
        report.lists,report.tickets,report.ticketWeight,report.removed,report.halved,#report.skipped,tostring(o.Loot),tostring(o.Guns),tostring(o.Ammo),
        tostring(o.Magazines),tostring(o.Parts),tostring(o.VanillaFirearms),tostring(o.VanillaAmmo)))
    print('[TW] loot tickets by kind: '..table.concat(kinds,', '))
    print(string.format('[TW] loot order: %d lists reordered, %d entries moved; AmmoFirst=%s AttachmentsFirst=%s',
        report.ordered,report.moved,tostring(o.AmmoFirst),tostring(o.AttachmentsFirst)))
    for _,path in ipairs(report.skipped) do print('[TW] loot: malformed list skipped: '..path) end
    return report
end

TWLootTablesAPI=TWLootTables
Events.OnPostDistributionMerge.Add(function() TWLootTables.apply() end)
return TWLootTables
