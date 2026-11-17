-- The mod weapons on zombies (plan 0.9.x D9, D10), without side effects: twins of the vanilla
-- attached weapon definitions that carry firearms, and the vanilla filter.
--
-- B42 (AttachedWeaponDefinitions.addRandomAttachedWeapon, by bytecode): an outfit with an entry in
-- attachedWeaponCustomOutfit draws only from that list (its chance, maxitem); other zombies draw
-- from every definition whose outfit list fits, under the sandbox ChanceOfAttachedWeapon; among
-- candidates a definition is picked by weight "chance", a weapon from it uniformly. A twin per
-- definition and outfit group, with the same chance (times the firearm share of a mixed
-- definition) and the same places, so a Tarkov weapon is as likely as the vanilla one; with
-- vanilla firearms off only the twins are left, at the vanilla frequency.
local D=require('TW/Data')
local Loot=require('TW/Loot')
local LD=Loot.data
local Z={}

local GROUP_CTX={police='police',army='army',survivor='civil'}
Z.groupCtx=GROUP_CTX

function Z.outfitGroup(outfit)
    for group,list in pairs(LD.zombieOutfitGroups) do
        for _,o in ipairs(list) do if o==outfit then return group end end
    end
    return 'survivor'
end

local function sortedKeys(t)
    local keys={}
    for k in pairs(t) do if type(k)=='string' then keys[#keys+1]=k end end
    table.sort(keys)
    return keys
end

local function copyList(list)
    local out={}
    for i,v in ipairs(list or {}) do out[i]=v end
    return out
end

-- Weapon weights of a twin: classes by the vanilla composition (a holster: pistols only; on the
-- back at army/SWAT the rifle share splits rifle : battle : mg : smg), weapons of the class from
-- the group's Tarkov bot list, else every weapon of the class by static loot. Returns
-- {key=probability} and the class factor (options of class and calibre; 1 at the defaults).
function Z.weaponWeights(firearms,group,holster,o)
    local ctx=GROUP_CTX[group]
    local bases,total={},0
    for _,name in ipairs(firearms) do
        local a=LD.anchors[Loot.baseName(name)]
        local base=holster and 'pistol' or (a and a.kind) or 'rifle'
        bases[base]=(bases[base] or 0)+1
        total=total+1
    end
    local classShare={}
    for base,n in pairs(bases) do
        local shares=holster and {{cls='pistol',share=1}} or Loot.classShares(base,ctx)
        for _,cs in ipairs(shares) do classShare[cs.cls]=(classShare[cs.cls] or 0)+n/total*cs.share end
    end
    local result,factor={},0
    local classes={}
    for cls in pairs(classShare) do classes[#classes+1]=cls end
    table.sort(classes)
    -- A slider at its extreme moves the share of a class it empties to the others (as Loot.weaponDraw).
    local sideSum,shareSum=0,0
    for _,cls in ipairs(classes) do
        local side=Loot.classWeights(cls,ctx,o,true).side
        shareSum=shareSum+classShare[cls]
        classShare[cls]=classShare[cls]*side
        sideSum=sideSum+classShare[cls]
    end
    for _,cls in ipairs(classes) do classShare[cls]=sideSum>0 and classShare[cls]/sideSum*shareSum or 0 end
    -- The NATO / Warsaw Pact share of this group's people (TW/Loot.pactScope: police and army - state
    -- forces, survivors - the others), held in every class.
    local p=Loot.pactShare(ctx,o,true)
    for _,cls in ipairs(classes) do
        -- The group's list of the class, or every weapon of the class by static loot when the list is
        -- empty or lacks a side the share needs (survivor pistols and the bot SMGs of the army are all
        -- Soviet, check_loot 18.10.2026), else the slider could not act in that class.
        local list,pact,other={},false,false
        for _,key in ipairs(LD.classes[cls]) do
            local w=LD.zombies[group][key]
            if w and w>0 then
                list[#list+1]={key=key,w=w}
                if LD.weapons[key].pact then pact=true else other=true end
            end
        end
        if #list==0 or (p<1 and not other) or (p>0 and not pact) then
            list={}
            for _,key in ipairs(LD.classes[cls]) do
                local w=LD.weapons[key].w*Loot.militaryFactor(key,ctx,o)
                if w>0 then list[#list+1]={key=key,w=w} end
            end
        end
        local sumW=0
        for _,e in ipairs(list) do
            sumW=sumW+e.w
            e.w=e.w*Loot.calibreFactor(D.weapons[e.key].ammoKey,o)*Loot.noTraderFactor(e.key,o)
        end
        local kept,sumCW=Loot.applySides(list,p,Loot.pactRow)
        local cw=sumW>0 and classShare[cls]*o[LD.classOption[cls]]*sumCW/sumW or 0
        if cls=='launcher' and not LD.places.military[ctx] then cw=cw*o.MilitaryOutside end
        if cw>0 then
            factor=factor+cw
            for _,e in ipairs(kept) do result[e.key]=(result[e.key] or 0)+cw*e.w/sumCW end
        end
    end
    if factor>0 then for key,p in pairs(result) do result[key]=p/factor end end
    return result,factor
end

-- Probabilities -> LD.zombieSlots repetitions (largest remainder, every weapon at least once; the
-- engine draws one slot uniformly). A thousand slots keep the NATO / Warsaw Pact share within a tenth
-- of a percent per weapon.
function Z.repetitions(weights)
    local keys=sortedKeys(weights)
    local slots=LD.zombieSlots
    local counts,rest,sum={},{},0
    for _,key in ipairs(keys) do
        local x=weights[key]*slots
        counts[key]=math.max(1,math.floor(x))
        rest[#rest+1]={key=key,r=x-math.floor(x)}
        sum=sum+counts[key]
    end
    table.sort(rest,function(a,b) if a.r~=b.r then return a.r>b.r end return a.key<b.key end)
    local i=1
    while sum<slots and #rest>0 do
        counts[rest[i].key]=counts[rest[i].key]+1
        sum=sum+1
        i=i%#rest+1
    end
    while sum>slots do
        local best
        for _,key in ipairs(keys) do if counts[key]>1 and (not best or counts[key]>counts[best]) then best=key end end
        if not best then break end
        counts[best]=counts[best]-1
        sum=sum-1
    end
    local list={}
    for _,key in ipairs(keys) do
        for _=1,counts[key] do list[#list+1]=D.weapons[key].pz end
    end
    return list,counts
end

local function roundChance(x)
    if not (x>0) then return 0 end
    return math.max(1,math.floor(x+0.5))
end

-- Twins and vanilla changes for the definitions table `defs` (AttachedWeaponDefinitions).
-- Returns {twins={name=def}, inserts={outfit={def...}}, removed={name=true}, replaced={name=def},
-- extra={name=def}, extraInserts={outfit={def...}}}.
function Z.plan(defs,o)
    local custom=defs.attachedWeaponCustomOutfit or {}
    local membership={}
    for _,outfit in ipairs(sortedKeys(custom)) do
        for _,def in ipairs(custom[outfit].weapons or {}) do
            membership[def]=membership[def] or {}
            table.insert(membership[def],outfit)
        end
    end
    local plan={twins={},inserts={},removed={},replaced={},extra={},extraInserts={}}
    for _,name in ipairs(sortedKeys(defs)) do
        local def=defs[name]
        if name~='attachedWeaponCustomOutfit' and type(def)=='table' and type(def.weapons)=='table' then
            local firearms,melee={},{}
            for _,w in ipairs(def.weapons) do
                if Loot.vanillaSet(w)=='firearms' then firearms[#firearms+1]=w else melee[#melee+1]=w end
            end
            if #firearms>0 then
                local share=#firearms/(#firearms+#melee)
                local holster=false
                for _,loc in ipairs(def.weaponLocation or {}) do if string.find(loc,'Holster') then holster=true end end
                -- Outfits by group: the definition's own list and the custom lists holding it.
                local byGroup={}
                local function note(outfit,kind)
                    local g=Z.outfitGroup(outfit)
                    byGroup[g]=byGroup[g] or {outfit={},custom={}}
                    table.insert(byGroup[g][kind],outfit)
                end
                for _,outfit in ipairs(def.outfit or {}) do note(outfit,'outfit') end
                for _,outfit in ipairs(membership[def] or {}) do note(outfit,'custom') end
                local groups={}
                for g in pairs(byGroup) do groups[#groups+1]=g end
                table.sort(groups)
                if o.Loot>0 and o.Guns>0 and o.ZombieWeapons>0 then
                    for _,g in ipairs(groups) do
                        local weights,factor=Z.weaponWeights(firearms,g,holster,o)
                        local chance=roundChance((def.chance or 0)*share*o.ZombieWeapons*o.Loot*o.Guns*factor)
                        if chance>0 then
                            local id='TW_'..name..'_'..g
                            -- The engine checks the outfit list of a definition in custom lists too, and an
                            -- empty list matches every zombie: the twin keeps the original's outfits of its
                            -- group; with none of them it matches no outfit (as the original for that group).
                            local outfits=byGroup[g].outfit
                            if #outfits==0 then
                                outfits=(#(def.outfit or {})==0 and #byGroup[g].custom>0) and copyList(byGroup[g].custom) or {'TW_NoOutfit'}
                            end
                            local twin={id=id,chance=chance,weaponLocation=copyList(def.weaponLocation),bloodLocations=copyList(def.bloodLocations),
                                addHoles=def.addHoles,daySurvived=def.daySurvived,ensureItem=def.ensureItem,weapons=(Z.repetitions(weights)),
                                outfit=outfits}
                            plan.twins[id]=twin
                            for _,outfit in ipairs(byGroup[g].custom) do
                                plan.inserts[outfit]=plan.inserts[outfit] or {}
                                table.insert(plan.inserts[outfit],twin)
                            end
                        end
                    end
                end
                -- Vanilla firearms (D10): off removes them, rarer halves them; the melee part of a
                -- mixed definition keeps its vanilla frequency (chance x melee share).
                local v=o.VanillaFirearms
                if v~=1 then
                    if #melee==0 then
                        if v==3 then plan.removed[name]=true
                        else plan.replaced[name]={chance=roundChance((def.chance or 0)*0.5)} end
                    else
                        plan.replaced[name]={chance=roundChance((def.chance or 0)*(1-share)),weapons=melee}
                        if v==2 then
                            local id='TWV_'..name
                            local extra={id=id,chance=roundChance((def.chance or 0)*share*0.5),weaponLocation=copyList(def.weaponLocation),
                                bloodLocations=copyList(def.bloodLocations),addHoles=def.addHoles,daySurvived=def.daySurvived,ensureItem=def.ensureItem,
                                weapons=firearms,outfit=copyList(def.outfit)}
                            plan.extra[id]=extra
                            for _,outfit in ipairs(membership[def] or {}) do
                                plan.extraInserts[outfit]=plan.extraInserts[outfit] or {}
                                table.insert(plan.extraInserts[outfit],extra)
                            end
                        end
                    end
                end
            end
        end
    end
    return plan
end

-- Writes a plan into the definitions table (the caller passes the global).
function Z.apply(defs,plan)
    local custom=defs.attachedWeaponCustomOutfit or {}
    local report={twins=0,inserts=0,removed=0,replaced=0,extra=0}
    for name,change in pairs(plan.replaced) do
        local def=defs[name]
        def.chance=change.chance
        if change.weapons then def.weapons=change.weapons end
        report.replaced=report.replaced+1
    end
    for name in pairs(plan.removed) do
        local def=defs[name]
        for _,entry in pairs(custom) do
            local list=entry.weapons or {}
            for i=#list,1,-1 do if list[i]==def then table.remove(list,i) end end
        end
        defs[name]=nil
        report.removed=report.removed+1
    end
    for id,twin in pairs(plan.twins) do defs[id]=twin; report.twins=report.twins+1 end
    for id,extra in pairs(plan.extra) do defs[id]=extra; report.extra=report.extra+1 end
    -- Several outfits share one list table (Survivalist..Survivalist05): one insert per list.
    local done={}
    for _,inserts in ipairs({plan.inserts,plan.extraInserts}) do
        for outfit,list in pairs(inserts) do
            local entry=custom[outfit]
            if entry then
                entry.weapons=entry.weapons or {}
                done[entry.weapons]=done[entry.weapons] or {}
                for _,def in ipairs(list) do
                    if not done[entry.weapons][def] then
                        done[entry.weapons][def]=true
                        table.insert(entry.weapons,def)
                        report.inserts=report.inserts+1
                    end
                end
            end
        end
    end
    -- A custom outfit left without definitions would give its zombies nothing: drop the entry.
    for outfit,entry in pairs(custom) do
        if entry.weapons and #entry.weapons==0 then custom[outfit]=nil end
    end
    return report
end

return Z
