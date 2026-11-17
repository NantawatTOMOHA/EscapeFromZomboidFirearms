-- Foraging of plan 0.9.x (docs/PLAN_LOOT_RU.md 3.4, D10): the vanilla firearm and ammunition
-- definitions (Foraging/Categories/Ammo.lua, JunkWeapons.lua) get loot tickets like the loot
-- tables. One definition per ticket kind (a definition is keyed by its item type), its chance in
-- every zone the sum of the vanilla definitions of that kind times the options, place "civil";
-- the representative found is replaced by a Tarkov item in spawnFuncs (both the server builder
-- forageServer.buildItems and the single-player icon ISBaseIcon:getItemList run them). Then the
-- vanilla definitions are halved or removed by VanillaFirearms / VanillaAmmo.
local Loot=require('TW/Loot')
local LI=require('TW/LootItems')
if not forageSystem then pcall(require,'Foraging/forageSystem') end

local TWLootForage={}
TWLootForageAPI=TWLootForage

function TWLootForage.spawn(character,inventory,itemDef,items)
    local o=Loot.options()
    local out=ArrayList.new()
    for i=0,items:size()-1 do
        local choice=Loot.choose(items:get(i):getFullType(),o,Loot.rng)
        for _,item in ipairs(LI.create(choice)) do out:add(item) end
    end
    return out
end

local function copyZones(zones,factor,into)
    for zone,chance in pairs(zones) do
        into[zone]=(into[zone] or 0)+chance*factor
    end
    return into
end

-- defs: forageSystem.forageDefinitions (name -> definition), changed in place.
function TWLootForage.apply(defs,o)
    -- The definitions table holds definitions only (populateItemDefs reads every value).
    if TWLootForage.appliedTo==defs then return nil end
    o=o or Loot.options()
    local report={tickets=0,removed=0,halved=0}
    local names={}
    for name,def in pairs(defs) do
        if type(def)=='table' and type(def.type)=='string' and Loot.vanillaSet(def.type) then names[#names+1]=name end
    end
    table.sort(names)
    local tickets,order={},{}
    for _,name in ipairs(names) do
        local def=defs[name]
        local rep,unit=Loot.ticket(Loot.baseName(def.type),1,'civil',{},o)
        if rep then
            local t=tickets[rep]
            if not t then
                local spawn={TWLootForage.spawn}
                for _,f in ipairs(def.spawnFuncs or {}) do spawn[#spawn+1]=f end
                t={type=rep,minCount=def.minCount,maxCount=def.maxCount,skill=def.skill,xp=def.xp,categories=def.categories,zones={},
                    spawnFuncs=spawn,forceOutside=def.forceOutside,canBeAboveFloor=def.canBeAboveFloor,itemSizeModifier=def.itemSizeModifier,
                    isItemOverrideSize=def.isItemOverrideSize}
                tickets[rep]=t
                order[#order+1]=rep
            end
            copyZones(def.zones or {},unit,t.zones)
        end
    end
    for _,name in ipairs(names) do
        local f=Loot.vanillaFactor(defs[name].type,o)
        if f<=0 then
            defs[name]=nil
            report.removed=report.removed+1
        elseif f<1 then
            local zones={}
            for zone,chance in pairs(defs[name].zones or {}) do zones[zone]=chance*f end
            defs[name].zones=zones
            report.halved=report.halved+1
        end
    end
    for _,rep in ipairs(order) do
        defs['TW_Loot_'..rep]=tickets[rep]
        report.tickets=report.tickets+1
    end
    TWLootForage.appliedTo=defs
    print(string.format('[TW] loot foraging: %d ticket definitions, %d vanilla definitions removed, %d halved',report.tickets,report.removed,report.halved))
    return report
end

-- forageSystem.init (OnLoadedMapZones) imports forageDefinitions in populateItemDefs; the
-- sandbox options are known by then.
if forageSystem and forageSystem.populateItemDefs and not TWLootForage.wrapped then
    TWLootForage.wrapped=true
    local populate=forageSystem.populateItemDefs
    forageSystem.populateItemDefs=function(itemDefs)
        if itemDefs==nil and forageSystem.forageDefinitions then
            local ok,err=pcall(TWLootForage.apply,forageSystem.forageDefinitions)
            if not ok then print('[TW] loot foraging failed: '..tostring(err)) end
        end
        return populate(itemDefs)
    end
end

return TWLootForage
