-- Project A-Life NPCs (Workshop 3803984183, mod ProjectALifeNPCs 1.4.5): the NPCs carry the pack's
-- weapons, assembled by its presets and Tarkov bot builds, in place of the vanilla firearms of their
-- profiles (sandbox ALifeWeapons). A runtime adapter only: A-Life's public tables are wrapped
-- through its own CompatRegistry (error isolation, undo); no A-Life file is copied or changed.
-- Server-safe; every hook is checked for presence and type, and the adapter stays off without A-Life.
--
-- Hooks (A-Life 1.4.5, read 18.10.2026):
--   ProfileHydrator.plan       vanilla primary/secondary -> pack weapon and build, deterministic: seed
--                              = identity seed, profile, slot, vanilla type (plan() is called again by
--                              death drops, hunting and the MP executor; never ZombRand here)
--   ProfileHydrator.apply      marks the shell being issued for the next hook
--   MagazineState.initialize   the build is applied before A-Life reads the capacity (MaxAmmo)
--   Weapons.select             one-handed pack weapons are handguns (A-Life defaults to rifle by name)
--   ShellState.capture/apply,  builds survive dehydration (A-Life keeps eight parts, no ModData);
--     captureItem/createItem   old saves: issued vanilla weapons become the planned pack weapon
--   DeathDrops.process,        dropped magazines of the built weapon with Gunworks rounds of the
--     dropMagazines,           calibre; A-Life's scarce-weapon rule kept for the replacement
--   LootRarity.scarceWeapon
--   CorpseDresser.wear         corpses A-Life dresses from a plan hold the planned build
if TWALifeCompat then return TWALifeCompat end

local A={ID='EscapeFromZomboidFirearms',MOD='ProjectALifeNPCs',installed=false,hooked={},patches={},logged={},applying={},
    planRating=setmetatable({},{__mode='k'}),shellRating=setmetatable({},{__mode='k'}),
    stats={plans=0,replaced=0,builds=0,issued=0,clips=0,classes=0,captured=0,restored=0,converted=0,
        dropGuns=0,dropMags=0,dropRounds=0,corpseLoaded=0,faults=0,conditions=0,repairedZero=0}}
TWALifeCompat=A

-- Firearms of A-Life profiles that are not loot anchors of the pack (A-Life's own M16A2).
A.KINDS={PALServiceRifle='rifle'}
-- A-Life faction class (LootRarity.factionClass) -> place of the pack's loot rules (TW/Loot.placeOf).
A.CTX={civilian='civil',raider='caches',police='police',military='army',elite='swat'}
-- Never issued: A-Life simulates no launcher shot, a belt of a machine gun is no magazine kit.
A.EXCLUDED={launcher=true,mg=true}
-- The secondary slot is A-Life's holstered sidearm.
A.SIDEARM={pistol=true}
A.OPTIONS_MS=60000

local D,C,Loot,LD,LB,LI,Builds,R
function A.deps()
    if R then return end
    D=require('TW/Data');C=require('TW/Core');Loot=require('TW/Loot');LD=Loot.data
    LB=require('TW/LootBuild');LI=require('TW/LootItems');Builds=require('TW/Builds');R=require('TW/Runtime')
end

local function log(text) print('[TW] A-Life: '..text) end

-- Our own work never breaks an A-Life call: a fault is counted, logged once per tag, and skipped.
function A.safe(tag,fn,...)
    local ok,a,b=pcall(fn,...)
    if ok then return a,b end
    A.stats.faults=A.stats.faults+1
    if not A.logged[tag] then A.logged[tag]=true;log(tag..' skipped: '..tostring(a)) end
    return nil
end

local function alife() local PA=rawget(_G,'ProjectALife');return type(PA)=='table' and PA or nil end
local function part(name) local PA=alife();local t=PA and PA[name];return type(t)=='table' and t or nil end

-- B42 may prefix active mod IDs with '\' (as TW_ViewpointCompat reads them).
function A.modActive()
    local found=false
    if type(getActivatedMods)~='function' then return false end
    pcall(function()
        local mods=getActivatedMods()
        for i=0,mods:size()-1 do
            local id=tostring(mods:get(i))
            while string.sub(id,1,1)=='\\' do id=string.sub(id,2) end
            if id==A.MOD then found=true end
        end
    end)
    return found
end

-- Loot options (sandbox), kept for a minute: plan() runs on every hydration and hunting check.
function A.options()
    local now=type(getTimestampMs)=='function' and getTimestampMs() or 0
    if not A.optionCache or now<A.optionAt or now-A.optionAt>A.OPTIONS_MS then A.optionCache,A.optionAt=Loot.options(),now end
    return A.optionCache
end
function A.reset() A.optionCache=nil end

-- Replacement on: ALifeWeapons, and the mod spawns weapons at all ("All EFZ loot", "EFZ weapons").
function A.enabled(o)
    o=o or A.options()
    return o.ALifeWeapons~=false and (tonumber(o.Loot) or 1)>0 and (tonumber(o.Guns) or 1)>0,o
end

-- Deterministic draws (same result on the server and an MP executor client): a text hash seeds a
-- Park-Miller generator; every product stays below 2^53, so doubles keep it exact.
function A.hash(text)
    local h=5381
    for i=1,#text do h=(h*33+string.byte(text,i))%2147483647 end
    return h
end
function A.rng(text)
    local state=A.hash(text)%2147483646+1
    for _=1,3 do state=(state*16807)%2147483647 end
    return function()
        state=(state*16807)%2147483647
        return (state-1)/2147483646
    end
end

-- Pack anchor kind (pistol, rifle, sniper, shotgun) of a vanilla firearm; nil for anything else.
function A.kindOf(fullType)
    local text=tostring(fullType or '')
    local module,name=string.match(text,'^([^%.]+)%.(.+)$')
    if not module then module,name='Base',text end
    if module~='Base' then return nil end
    local anchor=LD.anchors[name]
    if anchor and anchor.family=='weapon' then return anchor.kind end
    return A.KINDS[name]
end

function A.allowed(cls,slot)
    if A.EXCLUDED[cls] then return false end
    if slot=='secondary' then return A.SIDEARM[cls]==true end
    return true
end

-- A-Life presents a weapon by its type name first (pistol/revolver: handgun, rifle/shotgun: rifle); a
-- pack type whose name says otherwise than its grip (the two-handed Uzi PRO "pistol") is not issued.
local ONE_HAND={handgun=true,revolver=true}
A.misnamedCache={}
function A.misnamed(key)
    local cached=A.misnamedCache[key]
    if cached~=nil then return cached end
    local w=D.weapons[key]
    local low=string.lower(tostring(w and w.pz or ''))
    local named=nil
    if string.find(low,'pistol',1,true) or string.find(low,'revolver',1,true) then named='handgun'
    elseif string.find(low,'rifle',1,true) or string.find(low,'shotgun',1,true) then named='rifle' end
    local result=named~=nil and named~=(ONE_HAND[w.kind] and 'handgun' or 'rifle')
    A.misnamedCache[key]=result
    return result
end

-- The loot draw of a vanilla firearm's kind at a place (class, military, Warsaw Pact, calibre and
-- trader multipliers: TW/Loot.weaponDraw), as a weapon a person carries (the NATO / Warsaw Pact slider
-- PactStatePeople for the police, military and special units, PactPeople for civilians and
-- raiders: TW/Loot.pactScope), without the classes an NPC slot never gets.
function A.draw(kind,ctx,o,rng,slot)
    local list,total={},0
    for _,e in ipairs(Loot.weaponDraw(kind,ctx,o,true).list) do
        if A.allowed(e.cls,slot) and e.total>0 then list[#list+1]=e;total=total+e.w end
    end
    if total<=0 then return nil end
    local cls=Loot.pick(list,total,rng)
    local items,sum={},0
    for _,e in ipairs(cls.items) do
        if not A.misnamed(e.key) then items[#items+1]=e;sum=sum+e.w end
    end
    if sum<=0 then return nil end
    return Loot.pick(items,sum,rng).key,cls.cls
end

function A.factionClass(plan)
    local drop=type(plan.drop)=='table' and plan.drop or nil
    if drop and type(drop.class)=='string' then return drop.class end
    local rarity=part('LootRarity')
    if rarity and type(rarity.factionClass)=='function' then
        local ok,class=pcall(rarity.factionClass,plan.factionId)
        if ok and type(class)=='string' then return class end
    end
    return 'civilian'
end

-- Build by ALifeBuilds: 1 bot builds of the faction's group (a preset where the weapon has none),
-- 2 like found weapons (Presets / BotBuildShare, TW/LootBuild.treeFor), 3 default presets.
-- The default preset if a draw gives no complete build.
A.PRESETS={Presets=2}
function A.build(key,ctx,o,rng)
    local mode=tonumber(o.ALifeBuilds) or 1
    local group=Loot.botGroup(ctx)
    local tree,source=nil,nil
    if mode==1 then
        tree=LB.botBuild(key,group,rng)
        if tree then source='bot:'..group end
    end
    if not tree then tree,source=LB.treeFor(key,group,mode==2 and o or setmetatable(A.PRESETS,{__index=o}),rng) end
    if type(tree)~='table' or not C.validate(key,tree) or #C.requiredMissing(key,tree)>0 then tree,source=C.presetTree(key),'default' end
    return A.withMagazine(key,tree),source
end

-- An NPC's weapon always feeds: a build drawn without a magazine (a bot loadout may leave its magazine
-- slot empty, which is fine for found loot) takes the magazine of the default preset; the default
-- preset itself if that does not fit. Weapons without a magazine of their own (break actions) stay.
function A.withMagazine(key,tree)
    if C.stats(key,tree).capacity>0 then return tree end
    local preset=C.presetTree(key)
    if C.stats(key,preset).capacity<=0 then return tree end
    local fixed={}
    for path,tpl in pairs(tree) do fixed[path]=tpl end
    for path,tpl in pairs(preset) do
        if not fixed[path] and (path=='mod_magazine' or string.sub(path,-13)=='/mod_magazine') then fixed[path]=tpl end
    end
    if C.validate(key,fixed) and #C.requiredMissing(key,fixed)==0 and C.stats(key,fixed).capacity>0 then
        A.stats.magazines=(A.stats.magazines or 0)+1
        return fixed
    end
    return preset
end

function A.replacePlan(plan,actor)
    A.stats.plans=A.stats.plans+1
    local weapons=plan.weapons
    if type(weapons)~='table' then return nil end
    local on,o=A.enabled()
    if not on then return nil end
    -- The condition A-Life gives its own weapons of this plan (a 0..10 rating; "Broken (0/10)" is 0).
    local rating=type(plan.drop)=='table' and tonumber(plan.drop.weaponCondition) or nil
    if type(actor)=='table' then A.planRating[actor]=rating end
    local ctx=A.CTX[A.factionClass(plan)] or 'civil'
    local seed=tostring(plan.appearanceSeed or plan.actorUid or (type(actor)=='table' and actor.uid) or '')..'|'..tostring(plan.profileId or '')
    local info=nil
    for _,slot in ipairs({'primary','secondary'}) do
        local from=weapons[slot]
        if type(from)=='string' and from~='' then
            local rng=A.rng(seed..'|'..slot..'|'..from)
            -- A profile that already names a pack weapon keeps it and gets a build.
            local key,cls=D.byType[from],nil
            if not key then
                local kind=A.kindOf(from)
                if kind then key,cls=A.draw(kind,ctx,o,rng,slot) end
            end
            if key and D.weapons[key] then
                local tree,source=A.build(key,ctx,o,rng)
                local pz=D.weapons[key].pz
                info=info or {}
                info[slot]={from=from,key=key,pz=pz,class=cls or (LD.weapons[key] and LD.weapons[key].class),ctx=ctx,
                    source=source,tree=tree,build=Builds.encode(key,tree),rating=rating}
                weapons[slot]=pz
                if pz~=from then A.stats.replaced=A.stats.replaced+1 end
                A.stats.builds=A.stats.builds+1
            end
        end
    end
    plan.TWALife=info
    return info
end

function A.mark(item,slot,from,source)
    local md=item:getModData()
    md.TWLootKit=1
    if slot then md.TWALifeSlot=slot end
    if from then md.TWALifeFrom=from end
    if source then md.TWLootBuild=source end
end

-- A detachable magazine stays inserted (empty if need be): A-Life sets ContainsClip only by its loaded
-- rounds, and a later Gunworks/TW refresh would otherwise detach the magazine part and zero MaxAmmo.
function A.keepClip(item)
    if not item or not R.key(item) or item:isContainsClip() then return end
    if C.isClip(R.tree(item).mod_magazine) then item:setContainsClip(true);A.stats.clips=A.stats.clips+1 end
end

-- The condition A-Life gives a weapon of a plan's rating (A-Life DropPolicy.conditionFor, proportional to
-- ConditionMax): at issue a "Broken" (0) weapon is floored to 1 and marked to break on drop, as A-Life does;
-- at drop the same rating is 0. Our own layer applies it as well, so a pack weapon whose creation path
-- skipped A-Life's issueCondition (a changed A-Life version, a failed pcall, an item that lost the mark)
-- still follows the A-Life setting instead of staying at 0.
function A.condition(item,rating,drop)
    local policy=part('DropPolicy')
    if not item or rating==nil or not policy or type(policy.conditionFor)~='function' then return false end
    local max=tonumber(item:getConditionMax()) or 0
    if max<=0 then return false end
    local authored=tonumber(policy.conditionFor(max,rating)) or 0
    local value=((authored<=0) and (drop and 0 or 1)) or authored
    local md=item:getModData()
    if authored<=0 and not drop then md.ProjectALifeBreakOnDrop=true else md.ProjectALifeBreakOnDrop=nil end
    if tonumber(item:getCondition())~=value then item:setCondition(value) end
    md.ProjectALifeIssuedCondition=true
    A.stats.conditions=A.stats.conditions+1
    return true
end

-- Only a 0 is repaired: a weapon that A-Life's wear left at 1..max-1 keeps its graduated wear, but a pack
-- weapon with a non-broken A-Life setting never ends at 0 (the player-visible W8 item 11).
function A.repairZero(item,rating)
    if item==nil or rating==nil or rating<=0 then return false end
    if (tonumber(item:getCondition()) or 0)>0 then return false end
    if A.condition(item,rating,true) then A.stats.repairedZero=A.stats.repairedZero+1;return true end
    return false
end

-- After A-Life's death drops: the corpse's issued pack weapons follow the plan's condition; a 0 that a
-- non-broken setting did not intend is put back to the setting's value.
function A.deathCondition(shell,rating)
    local repaired=0
    local items=shell:getInventory():getItems()
    for i=0,items:size()-1 do
        local item=items:get(i)
        if R.key(item) and item:getModData().TWALifeSlot and A.repairZero(item,rating) then repaired=repaired+1 end
    end
    return repaired
end

-- The weapon A-Life just added for a plan slot gets the planned build before its capacity is read.
function A.issue(shell,item,slot,info)
    local e=info[slot=='secondary' and 'secondary' or 'primary']
    if type(e)~='table' or not item or R.key(item)~=e.key then return end
    if R.applyTree(item,e.tree) then
        A.mark(item,slot=='secondary' and 'secondary' or 'primary',e.from,e.source)
        A.stats.issued=A.stats.issued+1
    end
    A.safe('condition',A.condition,item,e.rating,false)
end

function A.fixClass(shell,item)
    if not R.key(item) then return end
    local two=item:isTwoHandWeapon()==true
    if not two and item.isRequiresEquippedBothHands then two=item:isRequiresEquippedBothHands()==true end
    local class=two and 'rifle' or 'handgun'
    local md=shell:getModData()
    if type(md)=='table' and md.ProjectALifeWeaponClass~=class then md.ProjectALifeWeaponClass=class;A.stats.classes=A.stats.classes+1 end
end

-- Restore ----------------------------------------------------------------------------------------
local function isWeapon(item) return item~=nil and item.IsWeapon~=nil and item:IsWeapon()==true end

local function weaponsOf(inventory)
    local list,items={},inventory:getItems()
    for i=0,items:size()-1 do
        local item=items:get(i)
        if isWeapon(item) then list[#list+1]=item end
    end
    return list
end

-- Weapons in the order ShellState.capture walks them (Scavenging.carried, else the inventory).
function A.carriedWeapons(shell)
    local inventory=shell:getInventory()
    local scav=part('Scavenging')
    local list=nil
    if scav and type(scav.carried)=='function' then
        local ok,carried=pcall(scav.carried,shell,inventory)
        if ok and type(carried)=='table' and #carried>0 then list=carried end
    end
    if not list then return weaponsOf(inventory) end
    local weapons={}
    for _,item in ipairs(list) do if isWeapon(item) then weapons[#weapons+1]=item end end
    return weapons
end

-- A saved weapon entry carries its full build (TW/Builds code) and the adapter's marks (the build's
-- source, the vanilla type it replaced; game QA 18.10.2026: a restore from the code alone lost them);
-- A-Life's own part list (at most eight parts, no slot paths) is emptied, so a restore without this
-- adapter keeps the default preset.
function A.annotateItem(item,saved)
    local key=R.key(item)
    if not key or type(saved)~='table' or saved.type~=item:getFullType() then return false end
    local tree,_,_,problem=R.read(item)
    if problem or not C.validate(key,tree) then return false end
    local md=item:getModData()
    saved.twBuild=Builds.encode(key,tree)
    saved.twSource=type(md.TWLootBuild)=='string' and md.TWLootBuild or nil
    saved.twFrom=type(md.TWALifeFrom)=='string' and md.TWALifeFrom or nil
    saved.parts={}
    return true
end

function A.annotate(shell,state)
    local weapons=state.weapons
    if type(weapons)~='table' or #weapons==0 then return end
    local k=0
    for _,item in ipairs(A.carriedWeapons(shell)) do
        k=k+1
        if not weapons[k] then break end
        if A.annotateItem(item,weapons[k]) then A.stats.captured=A.stats.captured+1 end
    end
end

local function copy(t) local r={};for k,v in pairs(t) do r[k]=v end;return r end

local function decoded(code,key)
    if type(code)~='string' then return nil end
    local k,tree=Builds.decode(code)
    if k==key and type(tree)=='table' and C.validate(key,tree) then return tree end
end

local function takeIssued(issued,test)
    for _,e in ipairs(issued) do
        if not e.used and test(e) then e.used=true;return e end
    end
end

-- Before ShellState.apply: the weapons A-Life is about to replace (the plan's issued ones), and a copy
-- of the saved state whose pack entries carry no stale part list. Old saves: a saved vanilla weapon
-- of a slot whose plan now issues a pack weapon is restored as that pack weapon (not one picked up).
function A.prepareRestore(shell,state)
    local inventory=shell:getInventory()
    local before,issued=weaponsOf(inventory),{}
    for _,item in ipairs(before) do
        local key=R.key(item)
        local md=key and item:getModData()
        if md and md.TWALifeSlot then
            issued[#issued+1]={key=key,pz=item:getFullType(),slot=md.TWALifeSlot,from=md.TWALifeFrom,source=md.TWLootBuild,tree=R.tree(item)}
        end
    end
    local on=A.enabled()
    local result,plans=copy(state),{}
    result.weapons={}
    for index,saved in ipairs(state.weapons) do
        local entry=saved
        if type(saved)=='table' and type(saved.type)=='string' then
            local key=D.byType[saved.type]
            if key then
                entry=copy(saved);entry.parts={}
                local tree=decoded(saved.twBuild,key)
                local match=nil
                if not tree then
                    match=takeIssued(issued,function(e) return e.pz==saved.type and e.slot==saved.slot end)
                    tree=match and match.tree
                end
                plans[index]={key=key,saved=saved,tree=tree,from=match and match.from or saved.twFrom,
                    source=match and match.source or saved.twSource}
            elseif on and saved.scavenged~=true and saved.recoveredFrom==nil then
                local match=takeIssued(issued,function(e) return e.from==saved.type and e.slot==saved.slot end)
                if match then
                    entry=copy(saved);entry.type=match.pz;entry.parts={}
                    plans[index]={key=match.key,saved=saved,tree=match.tree,from=saved.type,source=match.source,converted=true}
                end
            end
        end
        result.weapons[index]=entry
    end
    return {state=result,plans=plans,before=before}
end

-- Restore a weapon's build, keeping its rounds; for a converted vanilla weapon the rounds above the
-- new capacity go back to A-Life's reserve of the slot.
function A.rebuild(shell,item,plan,slot)
    local key=R.key(item)
    if not key or key~=plan.key then return false end
    local ammo,chambered=item:getCurrentAmmoCount() or 0,item:isRoundChambered()
    local tree=plan.tree
    if type(tree)=='table' then
        local current,_,_,problem=R.read(item)
        if problem or not C.equalTrees(current,tree) then
            if not R.applyTree(item,tree) then return false end
            item:setRoundChambered(chambered==true)
        end
    end
    local kept=math.max(0,math.min(ammo,item:getMaxAmmo() or 0))
    if item:getCurrentAmmoCount()~=kept then item:setCurrentAmmoCount(kept) end
    local weapons=part('Weapons')
    if plan.converted and ammo>kept and shell and weapons and type(weapons.addReserve)=='function' then
        weapons.addReserve(shell,slot,ammo-kept)
    end
    A.keepClip(item)
    A.partConditions(item,plan.saved)
    if item:getCondition()>item:getConditionMax() then item:setCondition(item:getConditionMax()) end
    -- A pack weapon whose checkpoint was already 0 while the A-Life setting is not "Broken" gets the
    -- setting's condition back (a restore of a shell A-Life unloaded, or an item from before this layer).
    local rating=shell and A.shellRating[shell] or nil
    if rating~=nil then A.repairZero(item,rating) end
    return true
end

-- Conditions of the saved parts by type (entries saved before this adapter still list them).
function A.partConditions(item,saved)
    local list=type(saved)=='table' and type(saved.parts)=='table' and saved.parts or nil
    if not list or #list==0 then return end
    local pool={}
    for _,p in ipairs(list) do
        if type(p)=='table' and type(p.type)=='string' and tonumber(p.condition) then
            pool[p.type]=pool[p.type] or {}
            table.insert(pool[p.type],tonumber(p.condition))
        end
    end
    local parts=item:getAllWeaponParts()
    for i=0,parts:size()-1 do
        local q=pool[parts:get(i):getFullType()]
        if q and #q>0 then parts:get(i):setCondition(table.remove(q,1)) end
    end
end

-- After ShellState.apply: A-Life replaced the weapons only when the whole state applied (all earlier
-- weapons gone); its new weapons follow the saved entries in order.
function A.finishRestore(shell,prepared)
    local inventory=shell:getInventory()
    for _,item in ipairs(prepared.before) do if inventory:contains(item) then return end end
    local weapons,cursor=weaponsOf(inventory),1
    for index,entry in ipairs(prepared.state.weapons) do
        local found=nil
        while cursor<=#weapons and not found do
            local item=weapons[cursor];cursor=cursor+1
            if type(entry)=='table' and item:getFullType()==entry.type then found=item end
        end
        if not found then return end
        local plan=prepared.plans[index]
        if plan then
            local slot=entry.slot=='secondary' and 'secondary' or 'primary'
            if A.rebuild(shell,found,plan,slot) then
                A.mark(found,(entry.slot=='primary' or entry.slot=='secondary') and entry.slot or nil,plan.from,plan.source)
                if plan.converted then A.stats.converted=A.stats.converted+1 else A.stats.restored=A.stats.restored+1 end
            end
        end
    end
end

-- A corpse A-Life dresses from a plan (dead NPCs of the offline world, picked-over places) holds the
-- planned weapon in its hands: it gets the planned build too.
function A.dressBuild(body,info)
    local item=body.getPrimaryHandItem and body:getPrimaryHandItem()
    local e=info.primary
    if item and type(e)=='table' and R.key(item)==e.key and R.applyTree(item,e.tree) then A.mark(item,nil,e.from,e.source) end
end

-- A single item A-Life recreates from a saved entry (stolen loot, offline loot drops).
function A.rebuildCreated(item,saved)
    local key=R.key(item)
    if not key then return end
    local tree=decoded(saved.twBuild,key)
    if not tree then
        local _,_,_,problem=R.read(item)
        if problem then tree=C.presetTree(key) end
    end
    if tree then
        A.rebuild(nil,item,{key=key,tree=tree,saved=saved},nil)
        A.mark(item,nil,type(saved.twFrom)=='string' and saved.twFrom or nil,type(saved.twSource)=='string' and saved.twSource or nil)
    end
end

-- Death drops ------------------------------------------------------------------------------------
-- Builds of the pack weapons the dying shell carries, by type (issued ones first).
function A.slotTrees(shell)
    local trees={}
    for _,item in ipairs(weaponsOf(shell:getInventory())) do
        local key=R.key(item)
        if key then
            local tree,_,_,problem=R.read(item)
            local pz=item:getFullType()
            if not problem and (not trees[pz] or item:getModData().TWALifeSlot) then trees[pz]=tree end
        end
    end
    return trees
end

function A.dropTrees(ctx,plan)
    local types=type(plan)=='table' and type(plan.weaponTypes)=='table' and plan.weaponTypes or nil
    if not types then return nil end
    local byType,any={},false
    for _,slot in ipairs({'primary','secondary'}) do
        local pz=types[slot]
        if type(pz)=='string' and D.byType[pz] then
            local e=ctx.info and ctx.info[slot]
            local tree=ctx.trees[pz] or (type(e)=='table' and e.pz==pz and e.tree) or nil
            if tree and not byType[pz] then byType[pz]=tree;any=true end
        end
    end
    return any and byType or nil
end

local function itemSet(inventory)
    local set,items={},inventory:getItems()
    for i=0,items:size()-1 do set[items:get(i)]=true end
    return set
end

-- New magazines of the built weapons get the calibre's ordinary round in the Gunworks format (VanillaAmmo);
-- loose vanilla rounds A-Life drops for a built-in magazine become that round as well.
function A.normalizeDrops(inventory,before,byType)
    local o=A.options()
    local mags,rounds={},{}
    for pz,tree in pairs(byType) do
        local key=D.byType[pz]
        local w=D.weapons[key]
        if C.isClip(tree.mod_magazine) then mags[C.clipType(key,tree.mod_magazine)]=w.ammoKey end
        local std=Loot.stdRound(w.ammoKey,o)
        if w.ammo and std and std~=w.ammo then rounds[w.ammo]=std end
    end
    local list,items={},inventory:getItems()
    for i=0,items:size()-1 do list[#list+1]=items:get(i) end
    for _,item in ipairs(list) do
        if not before[item] then
            local ft=item:getFullType()
            if mags[ft] then
                local max,count=item:getMaxAmmo() or 0,item:getCurrentAmmoCount() or 0
                if max>0 and count>0 and type(item:getModData().AmmoList)~='table' then
                    LI.loadMagazine(item,mags[ft],count/max,o)
                    A.stats.dropMags=A.stats.dropMags+1
                end
            elseif rounds[ft] then
                local round=instanceItem(rounds[ft])
                if round then inventory:Remove(item);inventory:AddItem(round);A.stats.dropRounds=A.stats.dropRounds+1 end
            end
        end
    end
end

-- Rounds A-Life counted in a pack weapon of the corpse become Gunworks rounds of the calibre.
function A.normalizeCorpse(shell)
    local o=A.options()
    for _,item in ipairs(weaponsOf(shell:getInventory())) do
        if R.key(item) and type(item:getModData().AmmoList)~='table' then
            local max,count=item:getMaxAmmo() or 0,item:getCurrentAmmoCount() or 0
            if max>0 and count>0 and LI.loadWeapon(item,count/max,o)>0 then A.stats.corpseLoaded=A.stats.corpseLoaded+1 end
        end
    end
end

-- Wrappers ---------------------------------------------------------------------------------------
-- Each one calls A-Life's function exactly once with its own arguments (a fault of A-Life stays its
-- own); our part runs in A.safe.
local function wrapPlan(original,actor,...)
    local plan,why=original(actor,...)
    if type(plan)=='table' then
        local info=A.safe('plan',A.replacePlan,plan,actor)
        local ctx=A.death
        if ctx and ctx.actor==actor then ctx.info=info end
    end
    return plan,why
end

local function wrapApply(original,shell,plan,...)
    local info=type(plan)=='table' and type(plan.TWALife)=='table' and plan.TWALife or nil
    if shell~=nil and type(plan)=='table' and type(plan.drop)=='table' then
        A.shellRating[shell]=tonumber(plan.drop.weaponCondition)
    end
    local previous=A.applying[shell]
    A.applying[shell]=info
    local ok,a,b=pcall(original,shell,plan,...)
    A.applying[shell]=previous
    if not ok then error(a,0) end
    return a,b
end

local function wrapInitialize(original,shell,item,slot,amount,mode,...)
    local info=A.applying[shell]
    if info then A.safe('issue',A.issue,shell,item,slot,info) end
    local loaded,remaining=original(shell,item,slot,amount,mode,...)
    if info then A.safe('clip',A.keepClip,item) end
    return loaded,remaining
end

local function wrapSelect(original,shell,selected,slot,...)
    local result=original(shell,selected,slot,...)
    if result==true and selected~=nil then A.safe('class',A.fixClass,shell,selected) end
    return result
end

local function wrapCapture(original,shell,...)
    local state,why=original(shell,...)
    if type(state)=='table' and shell then A.safe('capture',A.annotate,shell,state) end
    return state,why
end

local function wrapStateApply(original,shell,state,...)
    local prepared=nil
    if shell and type(state)=='table' and type(state.weapons)=='table' then prepared=A.safe('restore',A.prepareRestore,shell,state) end
    local ok,errors=original(shell,prepared and prepared.state or state,...)
    if prepared and ok==true then A.safe('restore',A.finishRestore,shell,prepared) end
    return ok,errors
end

local function wrapCaptureItem(original,item,...)
    local saved=original(item,...)
    if type(saved)=='table' and item then A.safe('captureItem',A.annotateItem,item,saved) end
    return saved
end

local function wrapCreateItem(original,saved,...)
    local item=original(saved,...)
    if item and type(saved)=='table' then A.safe('createItem',A.rebuildCreated,item,saved) end
    return item
end

local function wrapWear(original,body,plan,...)
    local a,b=original(body,plan,...)
    if a==true and body and type(plan)=='table' and type(plan.TWALife)=='table' then A.safe('dress',A.dressBuild,body,plan.TWALife) end
    return a,b
end

local function wrapProcess(original,shell,actor,...)
    local previous=A.death
    A.death={shell=shell,actor=actor,trees=(shell and A.safe('death',A.slotTrees,shell)) or {}}
    local ok,a,b=pcall(original,shell,actor,...)
    A.death=previous
    if ok and shell then
        A.safe('corpse',A.normalizeCorpse,shell)
        local rating=type(actor)=='table' and A.planRating[actor] or nil
        if rating~=nil then A.safe('deathCondition',A.deathCondition,shell,rating) end
    end
    if not ok then error(a,0) end
    return a,b
end

-- A-Life reads the dropped magazines' type and capacity from instanceItem(weapon type), a default
-- build: for this one call that item gets the build the shell carried.
local function wrapDropMagazines(original,inventory,plan,...)
    local ctx=A.death
    local byType=ctx and inventory and A.safe('dropTrees',A.dropTrees,ctx,plan) or nil
    if not byType then return original(inventory,plan,...) end
    local before=itemSet(inventory)
    local real=instanceItem
    instanceItem=function(fullType,...)
        local item=real(fullType,...)
        local tree=byType[fullType]
        if item and tree and A.safe('dropGun',R.applyTree,item,tree) then A.stats.dropGuns=A.stats.dropGuns+1 end
        return item
    end
    local ok,a,b=pcall(original,inventory,plan,...)
    instanceItem=real
    if not ok then error(a,0) end
    A.safe('dropAmmo',A.normalizeDrops,inventory,before,byType)
    return a,b
end

local function wrapScarce(original,fullType,...)
    if original(fullType,...) then return true end
    local info=A.death and A.death.info
    if type(info)=='table' then
        for _,e in pairs(info) do
            if type(e)=='table' and e.pz==fullType and e.from~=fullType and original(e.from) then return true end
        end
    end
    return false
end

A.HOOKS={
    {'ProfileHydrator','plan',wrapPlan,true},{'ProfileHydrator','apply',wrapApply,true},
    {'MagazineState','initialize',wrapInitialize,true},{'Weapons','select',wrapSelect},
    {'ShellState','capture',wrapCapture},{'ShellState','apply',wrapStateApply},
    {'ShellState','captureItem',wrapCaptureItem},{'ShellState','createItem',wrapCreateItem},
    {'DeathDrops','process',wrapProcess},{'DeathDrops','dropMagazines',wrapDropMagazines},
    {'LootRarity','scarceWeapon',wrapScarce},{'CorpseDresser','wear',wrapWear},
}

function A.detect()
    local H,M=part('ProfileHydrator'),part('MagazineState')
    return H~=nil and type(H.plan)=='function' and type(H.apply)=='function' and M~=nil and type(M.initialize)=='function'
end

-- wrap(owner,key,callback): CompatRegistry.wrap, or the guarded fallback below.
function A.install(wrap)
    A.deps()
    if A.installed then return true end
    A.hooked={}
    for _,hook in ipairs(A.HOOKS) do
        local owner=part(hook[1])
        if owner and type(owner[hook[2]])=='function' and wrap(owner,hook[2],hook[3]) then
            A.hooked[#A.hooked+1]=hook[1]..'.'..hook[2]
        elseif hook[4] then
            log('required hook missing: '..hook[1]..'.'..hook[2])
            return false
        end
    end
    A.installed=true
    log('adapter on ('..tostring(A.mode)..'): '..table.concat(A.hooked,', '))
    return true
end

function A.rawWrap(owner,key,callback)
    local original=owner[key]
    local wrapper=function(...) return callback(original,...) end
    A.patches[#A.patches+1]={owner=owner,key=key,original=original,wrapper=wrapper}
    owner[key]=wrapper
    return true
end

function A.undo()
    local registry=part('CompatRegistry')
    if A.mode=='registry' and registry and type(registry.restore)=='function' then registry.restore(A.ID) end
    for i=#A.patches,1,-1 do
        local p=A.patches[i]
        if p.owner[p.key]==p.wrapper then p.owner[p.key]=p.original end
    end
    A.patches,A.installed={},false
end

function A.qaLine()
    local parts={'mode='..tostring(A.mode),'installed='..tostring(A.installed),'hooks='..#A.hooked}
    for _,k in ipairs({'plans','replaced','builds','issued','clips','classes','captured','restored','converted','dropGuns','dropMags','dropRounds','corpseLoaded','conditions','repairedZero','faults'}) do
        parts[#parts+1]=k..'='..tostring(A.stats[k])
    end
    return '[TW] A-Life adapter: '..table.concat(parts,' ')
end

-- Registered before CompatRegistry.start (OnGameStart/OnServerStarted). Every realm: the MP executor
-- client hydrates too.
function A.boot()
    if A.booted then return end
    A.booted=true
    if not A.modActive() then A.mode='absent';return end
    local registry=part('CompatRegistry')
    if not registry and type(require)=='function' then
        pcall(require,'ProjectALife/Compat/ALifeCompatRegistry')
        registry=part('CompatRegistry')
    end
    if registry and type(registry.register)=='function' and type(registry.wrap)=='function' then
        local ok,why=registry.register({id=A.ID,mods={'EscapeFromZomboidFirearms'},phase='gameStart',detect=A.detect,
            apply=function() return A.install(function(owner,key,callback) return registry.wrap(A.ID,owner,key,callback) end) end,
            undo=A.undo})
        if ok then A.mode='registry';return end
        if why=='duplicate' then A.mode='duplicate';log('an adapter '..A.ID..' is already registered');return end
        log('CompatRegistry refused the adapter ('..tostring(why)..'): guarded wrapping')
    end
    A.mode='direct'
    local function late() if not A.installed and A.detect() then A.safe('install',A.install,A.rawWrap) end end
    if registry and registry.started==true then late();return end
    if Events and Events.OnGameStart then Events.OnGameStart.Add(late) end
    if Events and Events.OnServerStarted then Events.OnServerStarted.Add(late) end
end

A.boot()
return A
