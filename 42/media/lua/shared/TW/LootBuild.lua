-- Builds and kits of found weapons (plan 0.9.x D8, D16, section 4.5-4.6), without PZ objects.
-- Presets: Presets 1 base build, 2 vanilla Tarkov presets (default x3), 3 with
-- BotBuildShare % a Tarkov bot build. Bot builds follow the SPT generator: required slots
-- always, optional ones with the slot chance of the bot type (chances.weaponMods), a random
-- candidate from the bot's mod pool that the mod's slot filter and conflicts accept, recursion
-- into its slots; a failed build falls back to a preset.
local D=require('TW/Data')
local C=require('TW/Core')
local Loot=require('TW/Loot')
local LD=Loot.data
local B={stats={botBuilds=0,botFallbacks=0}}

-- Bot data (TW/Data/LootBots_<group>: parent|slot|tpl,tpl;...), parsed once per group.
local BOTS={}
local function bots(group)
    if BOTS[group] then return BOTS[group] end
    local raw=require('TW/Data/LootBots_'..group)
    local mods={}
    for entry in string.gmatch(raw.mods,'[^;]+') do
        local parent,slot,list=string.match(entry,'^([^|]+)|([^|]+)|(.*)$')
        if parent then
            mods[parent]=mods[parent] or {}
            local tpls={}
            for tpl in string.gmatch(list,'[^,]+') do tpls[#tpls+1]=tpl end
            mods[parent][slot]=tpls
        end
    end
    BOTS[group]={mods=mods,chances=raw.chances}
    return BOTS[group]
end
B.bots=bots

-- D16: a weapon no bot of its group carries uses every SPT bot that has its mods (group 'any').
local function merged()
    return bots('any')
end

local function contains(list,value)
    for _,v in ipairs(list or {}) do if v==value then return true end end
    return false
end

local function slotChance(chances,name)
    local v=chances[name]
    if v==nil then v=chances[(string.gsub(name,'_%d+$',''))] end
    return v or 0
end

-- A Tarkov bot build of a weapon, or nil and a reason.
function B.botBuild(key,group,rng)
    local root=D.weapons[key].tpl
    local data=bots(group)
    if not data.mods[root] then data=merged() end
    if not data.mods[root] then return nil,'noBotData' end
    local tree={}
    local function fill(tpl,prefix)
        local item=D.items[tpl]
        for _,slot in ipairs(item.slots) do
            local path=prefix=='' and slot.name or prefix..'/'..slot.name
            local pool=data.mods[tpl] and data.mods[tpl][slot.name]
            local candidates={}
            for _,t in ipairs(pool or {}) do
                if contains(slot.allowed,t) and D.items[t] and item.frames and item.frames[slot.name] then candidates[#candidates+1]=t end
            end
            local wanted=slot.required or (#candidates>0 and rng()*100<slotChance(data.chances,slot.name))
            if wanted and #candidates>0 and not tree[path] then
                -- Random order; the first candidate the rules accept.
                for i=#candidates,2,-1 do
                    local j=math.floor(rng()*i)+1
                    candidates[i],candidates[j]=candidates[j],candidates[i]
                end
                for _,t in ipairs(candidates) do
                    local nextTree=C.install(key,tree,path,t)
                    if nextTree then
                        local saved=tree
                        tree=nextTree
                        -- A part whose own required slot cannot be filled is taken back.
                        if fill(t,path) then break end
                        tree=saved
                    end
                end
            end
            if slot.required and not tree[path] and item.frames and item.frames[slot.name] then
                -- No part of the bot's pool fits what is already installed (Mosin: infantry stock
                -- and detachable magazines): any part the slot allows, the most common first.
                local fallback={}
                for _,t in ipairs(slot.allowed) do if D.items[t] then fallback[#fallback+1]=t end end
                table.sort(fallback,function(a,b)
                    local wa,wb=D.items[a].lw or 0,D.items[b].lw or 0
                    if wa~=wb then return wa>wb end
                    return a<b
                end)
                for _,t in ipairs(fallback) do
                    local nextTree=C.install(key,tree,path,t)
                    if nextTree then
                        local saved=tree
                        tree=nextTree
                        if fill(t,path) then break end
                        tree=saved
                    end
                end
            end
            if slot.required and not tree[path] then return false end
        end
        return true
    end
    if not fill(root,'') then return nil,'requiredSlot' end
    local ok,reason=C.validate(key,tree)
    if not ok then return nil,reason end
    if #C.requiredMissing(key,tree)>0 then return nil,'requiredMissing' end
    return tree
end

-- A vanilla preset by weight (default 3, others 1).
function B.preset(key,rng)
    local weights=LD.presets[key]
    local names,total={},0
    for name,w in pairs(weights) do names[#names+1]=name; total=total+w end
    table.sort(names)
    local r=rng()*total
    for _,name in ipairs(names) do
        r=r-weights[name]
        if r<0 then return name end
    end
    return names[#names]
end

-- The build of a found weapon: tree, source ('default', 'preset:<name>', 'bot:<group>').
function B.treeFor(key,group,o,rng)
    local weapon=D.weapons[key]
    if o.Presets<=1 then return C.presetTree(key),'default' end
    if o.Presets>=3 and rng()*100<o.BotBuildShare then
        local tree=B.botBuild(key,group,rng)
        if tree then
            B.stats.botBuilds=B.stats.botBuilds+1
            return tree,'bot:'..group
        end
        B.stats.botFallbacks=B.stats.botFallbacks+1
    end
    local name=B.preset(key,rng)
    if name==weapon.defaultPreset then return C.presetTree(key),'default' end
    return C.presetTree(key,name),'preset:'..name
end

-- Expected number n -> floor(n) + 1 with probability n - floor(n) (section 4.6).
function B.count(n,rng)
    local whole=math.floor(n)
    if rng()<n-whole then whole=whole+1 end
    return whole
end

-- Detachable magazines of a weapon by loot weight (default preset magazine x2 is in lw).
function B.magazineChoices(key)
    local list,total={},0
    for _,full in ipairs(D.weapons[key].magazines or {}) do
        local meta=D.types[full]
        local w=meta and D.items[meta.tpl].lw or 0
        if w>0 then list[#list+1]={type=full,tpl=meta.tpl,w=w}; total=total+w end
    end
    return list,total
end

-- Packs of the weapon's calibre by round weight (the ordinary one by VanillaAmmo, D15; with
-- "Only plain rounds" only the plain rounds, W9-ammoopt).
function B.packChoice(key,o,rng)
    local rows=Loot.rows(D.weapons[key].ammoKey,o)
    if not rows then return nil end
    local total=0
    for _,r in ipairs(rows) do total=total+r.w end
    local r=rng()*total
    for _,row in ipairs(rows) do
        r=r-row.w
        if r<0 then return Loot.roundItem(row,'box',o) end
    end
    return Loot.roundItem(rows[#rows],'box',o)
end

-- Parts that fit a free or occupied slot of the assembly, by loot weight and part options. people:
-- the NATO / Warsaw Pact scope of a person's inventory (TW/Loot.pactScope), nil for loot.
function B.partChoices(key,tree,o,ctx,people)
    local seen,list,total={}, {}, 0
    for _,slot in ipairs(C.allSlots(key,tree)) do
        for _,tpl in ipairs(slot.definition.allowed) do
            local item=D.items[tpl]
            if not seen[tpl] and item and not item.clip and not item.internalMagazine and item.lw then
                seen[tpl]=true
                local group=LD.partGroups[item.category]
                local w=item.lw*(o[LD.partOption[group] or 'PartsOther'] or 1)*o.Parts
                local launcher=LD.launcherParts and LD.launcherParts[tpl]
                local pact
                if launcher then
                    w=w*(o['Cal_'..launcher.calibre] or 1)*Loot.launcherOutside(ctx,o)
                    pact=launcher.pact==true
                end
                if w>0 then list[#list+1]={type=item.pz,tpl=tpl,w=w,pact=pact} end
            end
        end
    end
    table.sort(list,function(a,b) return a.tpl<b.tpl end)
    -- Underbarrel launchers share their sides by the slider of the scope (TW/Loot.applySides).
    list,total=Loot.applySides(list,Loot.pactShare(ctx,o,people),function(e) return e.pact end)
    return list,total
end

-- Kit of a found weapon (D8): what to add next to it. engine = what the game already made for it
-- ({mags, boxes, loaded}); it counts against the expected numbers.
function B.kit(key,tree,o,rng,engine,ctx,people)
    engine=engine or {mags=0,boxes=0,loaded=0}
    local kit={mags={},packs={},parts={}}
    -- A fresh disposable is initialized by TWLaunchers.onCreate. The rocket is
    -- never a loose companion and the Loaded slider cannot create an empty tube.
    if key=='RSHG2' then kit.loaded=1; return kit end
    if engine.loaded and engine.loaded>0 then kit.loaded=engine.loaded
    elseif rng()*100<o.Loaded then kit.loaded=LD.kits.loadedMin+rng()*(LD.kits.loadedMax-LD.kits.loadedMin) end
    local mags,mtotal=B.magazineChoices(key)
    if mtotal>0 then
        for _=1,math.max(0,B.count(o.SpareMagazines,rng)-(engine.mags or 0)) do
            local m=Loot.pick(mags,mtotal,rng)
            kit.mags[#kit.mags+1]={type=m.type,tpl=m.tpl,loaded=rng()*100<o.Loaded and LD.kits.loadedMin+rng()*(LD.kits.loadedMax-LD.kits.loadedMin) or 0}
        end
    end
    for _=1,math.max(0,B.count(o.AmmoWithWeapon,rng)-(engine.boxes or 0)) do
        local pack=B.packChoice(key,o,rng)
        if pack then kit.packs[#kit.packs+1]=pack end
    end
    local parts,ptotal=B.partChoices(key,tree,o,ctx,people)
    if ptotal>0 then
        for _=1,B.count(o.KitParts,rng) do
            local p=Loot.pick(parts,ptotal,rng)
            kit.parts[#kit.parts+1]=p.type
        end
    end
    return kit
end

return B
