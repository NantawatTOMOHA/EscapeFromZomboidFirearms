-- Bandits V2 (Workshop 3268487204, mod Bandits2 42.21 run on 42.21): the bandits carry the pack's
-- weapons, assembled by its presets and Tarkov bot builds, in place of the vanilla firearms Bandits
-- gives them (sandbox BanditsWeapons). A runtime adapter only: Bandits' public tables are wrapped;
-- no Bandits file is copied or changed. Server-safe; every hook is checked for presence and type and
-- the adapter stays off without Bandits.
--
-- Bandits keeps the weapon of a bandit as a kit table in its brain (makeBrain, BanditServerSpawner
-- :360-376: BanditWeapons.Make(vanilla type, ammo boxes)): name (item type), type "mag"/"nomag",
-- bulletsLeft, magSize/magCount/clipIn or ammoSize/ammoCount/ammoName. Bandits itself turns that kit
-- into items at three points: Bandit.ApplyVisuals -> applyAttachments (the weapons on the body, every
-- client tick), Bandit.SetHands / ZombieActions.Shoot (the item in the hands and the item a shot is
-- simulated with, both through BanditUtils.ModifyWeapon), Bandit.UpdateItemsToSpawnAtDeath (the death
-- drop). The adapter replaces the kit of each slot Bandits armed (keeping its primary/secondary split
-- and its own melee) with a pack weapon and a build, and every item Bandits makes from that kit gets
-- the build before Bandits reads the weapon (capacity, range, sound, damage).
--
-- Hooks (Bandits2 42.21, read 29.10.2026):
--   Bandit.ApplyVisuals            brain kit -> pack weapon (deterministic: brain id, clan, slot,
--                                  vanilla type; never ZombRand) before Bandits shows it
--   BanditUtils.ModifyWeapon       every weapon item Bandits makes from a kit gets its build (the
--                                  body attachment reuses one built item per bandit and slot)
--   Bandit.UpdateItemsToSpawnAtDeath  the death drop: the gun has the build, the magazines carry the
--                                  calibre's rounds in the Gunworks format
if TWBanditsCompat then return TWBanditsCompat end

local A={ID='EscapeFromZomboidFirearms',MOD='Bandits2',installed=false,hooked={},patches={},logged={},
    display={},displayCount=0,
    stats={brains=0,kits=0,replaced=0,items=0,drops=0,dropMags=0,visual=0,mags=0,faults=0}}
TWBanditsCompat=A

-- Loot places of the bandit clans: the place of the pack's loot rules whose classes, calibre,
-- military and NATO / Warsaw Pact weights decide the weapons the clan carries (the same draw as the
-- loot of that place, as Loot.weaponDraw with people=true - the state forces' people slider applies
-- to the official clans). Read from the clan names of common/bandits/clans.txt (Bandits2 42.21);
-- a clan this table does not name keeps caches. check_bandits_compat fails on a clan missing here.
-- Each row: the outfit/theme the decision rests on (the clan's bandits in common/bandits/bandits.txt).
A.PLACE={
    ['01_Rednecks']='civil',         -- dungarees, cowboy hats, hunting rifles: rural survivors
    ['02_Sport']='civil',            -- tracksuits, no firearm in bandits.txt
    ['03_Butchers']='caches',        -- butchers' aprons and hockey masks, no firearm
    ['04_Criminals']='caches',       -- hoodies, sawn-off shotgun: street criminals
    ['05_Spike']='caches',           -- punk clothes, a revolver only
    ['06_Robbers']='caches',         -- balaclavas, sawn-off shotgun: robbers
    ['07_Heretics']='caches',        -- robes and devil masks, katanas only
    ['08_Hikers']='civil',           -- hiking gear, hunting rifles and shotguns: travellers
    ['09_Leather']='caches',         -- biker leather and bats: a gang
    ['10_Mafia']='caches',           -- suits and pistols: organised crime
    ['11_Cannibals']='caches',       -- hide and bone, melee only
    ['12_Medieval']='civil',         -- armour and swords, melee only
    ['12_Militia']='caches',         -- irregulars in army surplus: a gang, no state force (coordinator 29.10.2026)
    ['13_Trash']='caches',           -- garbage-bag ponchos, melee only
    ['14_Hermits']='civil',          -- burlap dresses and bone spears, melee only
    ['15_Legion']='caches',          -- a gang in stolen SWAT gear: not a police unit
    ['ArmyDesert']='army',           -- desert army uniforms, dog tags: soldiers
    ['Baseball']='civil',            -- baseball kit, bats only
    ['Karate']='civil',              -- martial-arts clothes, bare hands
    ['Officers']='army',             -- army beret, peaked cap, army coat, service trousers: officers
    ['Prison']='caches',             -- prisoner boilersuit and iron bars: inmates, not guards
    ['W_00_Civs']='civil',           -- civilian clothes, a shotgun or revolver
    ['W_01_Rednecks']='civil',       -- dungarees and cowboy hats, a revolver only
    ['W_02_Scavengers']='caches',    -- survivor gear and a rifle: scavengers
    ['W_03_Travellers']='civil',     -- travelling clothes, a revolver only
    ['W_04_Convicts']='caches',      -- prisoner boilersuits and nightsticks: inmates
    ['W_05_LonelyHunter']='civil',   -- camo hunting gear and hunting rifles: a hunter
    ['W_06_BlackDogs']='caches',     -- balaclavas and hunting rifles: a gang
    ['W_06_Supremacists']='caches',  -- a militia gang in army boots, not a state force
    ['W_07_ExCops']='police',        -- police caps, police jackets, body armour: former police
    ['W_09_Deserters']='army',       -- army uniforms and bullet vests: deserters
    ['W_10_Sweepers']='army',        -- hazmat suits and an assault rifle: a military clean-up unit
    ['W_11_Bandits']='caches',       -- mixed army surplus: bandits
    ['W_13_Tribe']='civil',          -- hide and bone, spears only
    ['W_14_Hermits']='caches',       -- crafted dresses, spears only: no firearm to place
    ['Wedding']='civil',             -- wedding clothes, a revolver only
}
-- Never issued: Bandits simulates no launcher shot and no machine-gun belt.
A.EXCLUDED={launcher=true,mg=true}
-- The secondary slot is Bandits' holstered sidearm.
A.SIDEARM={pistol=true}
A.OPTIONS_MS=60000
A.DISPLAY_MAX=128

local D,C,Loot,LD,LB,LI,Builds,R
function A.deps()
    if R then return end
    D=require('TW/Data');C=require('TW/Core');Loot=require('TW/Loot');LD=Loot.data
    LB=require('TW/LootBuild');LI=require('TW/LootItems');Builds=require('TW/Builds');R=require('TW/Runtime')
end

local function log(text) print('[TW] Bandits: '..text) end

-- Our own work never breaks a Bandits call: a fault is counted, logged once per tag and skipped.
function A.safe(tag,fn,...)
    local ok,a,b=pcall(fn,...)
    if ok then return a,b end
    A.stats.faults=A.stats.faults+1
    if not A.logged[tag] then A.logged[tag]=true;log(tag..' skipped: '..tostring(a)) end
    return nil
end

local function bandits() local B=rawget(_G,'Bandit');return type(B)=='table' and B or nil end
local function part(name) local B=bandits();local t=B and rawget(_G,name);return type(t)=='table' and t or nil end

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

-- Loot options (sandbox), kept for a minute: the brain pass runs on every client tick per bandit.
function A.options()
    A.deps()
    local now=type(getTimestampMs)=='function' and getTimestampMs() or 0
    if not A.optionCache or now<A.optionAt or now-A.optionAt>A.OPTIONS_MS then A.optionCache,A.optionAt=Loot.options(),now end
    return A.optionCache
end
function A.reset() A.optionCache=nil end

-- Replacement on: BanditsWeapons, and the mod spawns weapons at all ("All EFZ loot", "EFZ weapons").
function A.enabled(o)
    o=o or A.options()
    return o.BanditsWeapons~=false and (tonumber(o.Loot) or 1)>0 and (tonumber(o.Guns) or 1)>0,o
end

-- Deterministic draws (the same answer on every call, on the server and on an MP client): a text hash
-- seeds a Park-Miller generator; every product stays below 2^53, so doubles keep it exact.
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
    return nil
end

function A.allowed(cls,slot)
    if A.EXCLUDED[cls] then return false end
    if slot=='secondary' then return A.SIDEARM[cls]==true end
    return true
end

-- A pack type whose name says otherwise than its grip (the two-handed Uzi PRO "pistol") is not
-- issued: Bandits picks the bandit's animation by WeaponType, a misnamed type would be holstered as
-- a pistol while it needs both hands.
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
-- trader multipliers: TW/Loot.weaponDraw), as a weapon a person carries (the NATO / Warsaw Pact
-- sliders' people scope, state people's for the official clans), without the classes a bandit slot
-- never gets.
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

-- Clan of a brain -> place: BanditCustom.clanData[cid].general.name (the name of clans.txt).
function A.clanName(brain)
    local custom=part('BanditCustom')
    local cid=brain and brain.cid
    if not custom or not cid then return nil end
    local clan=custom.clanData and custom.clanData[cid]
    local name=clan and clan.general and clan.general.name
    return type(name)=='string' and name or nil
end

function A.place(brain)
    local name=A.clanName(brain)
    local place=name and A.PLACE[name]
    return place or 'caches',name
end

-- Build by BanditsBuilds: 1 bot builds of the faction's group (a preset where the weapon has none),
-- 2 like found weapons (Presets / BotBuildShare, TW/LootBuild.treeFor), 3 default presets.
-- The default preset if a draw gives no complete build.
A.PRESETS={Presets=2}
function A.build(key,ctx,o,rng)
    local mode=tonumber(o.BanditsBuilds) or 1
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

-- A bandit's weapon always feeds: a build drawn without a magazine (a bot loadout may leave its
-- magazine slot empty, which is fine for found loot) takes the magazine of the default preset; the
-- default preset itself if that does not fit. Weapons without a magazine of their own stay.
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
        A.stats.mags=A.stats.mags+1
        return fixed
    end
    return preset
end

-- The kit of one slot for a pack weapon (the fields of BanditWeapons.Make, 42.21): the build's own
-- magazine type and capacity (read after the build is applied), the same number of magazines and the
-- same share of rounds the template had; a built-in magazine keeps the kit's "nomag" shape with the
-- calibre's ordinary round.
function A.kit(key,tree,from,old,slot,source,o)
    local w=D.weapons[key]
    local stats=C.stats(key,tree)
    local capacity=stats.capacity or 0
    local mags=math.max(1,(tonumber(old and old.magCount) or 0)+1)
    local kit={name=w.pz,racked=false,TWKey=key,TWBuild=Builds.encode(key,tree),TWFrom=from,TWSlot=slot,TWSource=source}
    if capacity>0 and C.isClip(tree.mod_magazine) then
        kit.type='mag'
        kit.magName=C.clipType(key,tree.mod_magazine)
        kit.magSize=capacity
        kit.bulletsLeft=capacity
        kit.clipIn=true
        kit.magCount=math.max(0,mags-1)
    else
        kit.type='nomag'
        kit.ammoSize=capacity
        kit.bulletsLeft=capacity
        kit.ammoName=Loot.stdRound(w.ammoKey,o) or w.ammo
        -- The template's rounds as a multiple of one full load (old ammoSize is its capacity).
        local oldSize=math.max(1,tonumber(old and old.ammoSize) or 1)
        local ratio=((tonumber(old and old.bulletsLeft) or 0)+(tonumber(old and old.ammoCount) or 0))/oldSize
        kit.ammoCount=math.max(0,math.floor(ratio*capacity+0.5)-capacity)
    end
    return kit
end

-- The pack weapon and build of a slot's vanilla type, or nil (not a firearm, options off, nothing
-- drawable). The seed keeps the answer of a brain the same on every call and on every machine.
function A.choose(from,slot,brain,ctx,o)
    local key=D.byType[from]
    local cls=nil
    local seed=tostring(brain.id or brain.bid or '')..'|'..tostring(brain.cid or '')..'|'..slot..'|'..from
    local rng=A.rng(seed)
    if not key then
        local kind=A.kindOf(from)
        if not kind then return nil end
        key,cls=A.draw(kind,ctx,o,rng,slot)
        if not key or not D.weapons[key] then return nil end
    end
    local tree,source=A.build(key,ctx,o,rng)
    return key,tree,source,cls
end

-- Kit of one slot -> pack weapon. A kit that was already replaced is left alone (a second client
-- pass, a saved brain); a firearm slot with no draw keeps Bandits' own weapon.
function A.replaceSlot(brain,slot,ctx,o)
    local weapons=brain.weapons
    local kit=weapons and weapons[slot]
    if type(kit)~='table' or kit.TWKey then return false end
    local from=kit.name
    if type(from)~='string' or from=='' then return false end
    local key,tree,source,cls=A.choose(from,slot,brain,ctx,o)
    if not key then return false end
    local new=A.kit(key,tree,from,kit,slot,source,o)
    new.class=cls or (LD.weapons[key] and LD.weapons[key].class)
    brain.weapons[slot]=new
    if new.name~=from then A.stats.replaced=A.stats.replaced+1 end
    A.stats.kits=A.stats.kits+1
    return true
end

-- All firearm slots of a bandit's brain. The mark keeps a bandit that later picks up a vanilla gun
-- from being re-armed: only the firearms Bandits itself issued to its clan are replaced.
function A.convertBrain(brain)
    if type(brain)~='table' or type(brain.weapons)~='table' then return false end
    local on,o=A.enabled()
    if not on then return false end
    if brain.TWBandits then return false end
    local ctx,name=A.place(brain)
    A.stats.brains=A.stats.brains+1
    local n=0
    if A.replaceSlot(brain,'primary',ctx,o) then n=n+1 end
    if A.replaceSlot(brain,'secondary',ctx,o) then n=n+1 end
    if n>0 then brain.TWBandits=1;brain.TWBanditsPlace=ctx;brain.TWBanditsClan=name end
    return n>0
end

-- A fresh item of a converted kit gets the build, its rounds and the marks (the pack's own zombie
-- loot pass must not re-assemble it). One built item per bandit and slot serves the body attachment
-- Bandits rebuilds on every client tick and the item a shot is simulated with.
function A.remember(brain,slot,kit,item)
    if A.displayCount>=A.DISPLAY_MAX then A.display={};A.displayCount=0 end
    local id=(brain.id or brain.bid or '?')..'|'..slot..'|'..kit.TWBuild
    if not A.display[id] then A.displayCount=A.displayCount+1 end
    A.display[id]={type=kit.name,code=kit.TWBuild,item=item}
end

function A.apply(item,kit,o)
    A.deps()
    if not item or type(kit)~='table' or type(kit.TWBuild)~='string' then return false end
    local md=item:getModData()
    if md.TWBuilt==kit.TWBuild then return true end
    local key,tree=Builds.decode(kit.TWBuild)
    if not key or key~=kit.TWKey or not R.applyTree(item,tree) then return false end
    md.TWLootKit=1
    md.TWBuilt=kit.TWBuild
    md.TWBandit=kit.TWSlot
    md.TWFrom=kit.TWFrom
    md.TWLootBuild=kit.TWSource
    local w=D.weapons[key]
    local capacity=item:getMaxAmmo() or 0
    local rounds=tonumber(kit.bulletsLeft) or 0
    if capacity>0 and rounds>0 then LI.loadWeapon(item,math.min(1,rounds/capacity),o or A.options()) end
    A.stats.items=A.stats.items+1
    return true
end

function A.displayFor(brain,slot,kit)
    local e=A.display[(brain.id or brain.bid or '?')..'|'..slot..'|'..kit.TWBuild]
    if e and e.type==kit.name and e.code==kit.TWBuild then A.stats.visual=A.stats.visual+1;return e.item end
end

-- BanditUtils.ModifyWeapon(item, brain): the item Bandits just made from a kit. Inside the body
-- pass the item is one of the attached model weapons rebuilt every tick: the built one is reused.
function A.touch(item,brain)
    if not item or type(brain)~='table' or type(brain.weapons)~='table' then return item end
    local ft=item.getFullType and item:getFullType() or nil
    if not ft then return item end
    for _,slot in ipairs({'primary','secondary'}) do
        local kit=brain.weapons[slot]
        if type(kit)=='table' and kit.TWBuild and kit.name==ft then
            local o=A.options()
            if A.visual then
                local cached=A.displayFor(brain,slot,kit)
                if cached then return cached end
                A.apply(item,kit,o)
                A.remember(brain,slot,kit,item)
                return item
            end
            A.apply(item,kit,o)
            return item
        end
    end
    return item
end

-- Death drops ----------------------------------------------------------------------------------
-- Bandits creates the dropped magazines directly (InstanceItem + setCurrentAmmoCount); during that
-- call every item of a converted kit's magazine type is remembered and, after Bandits set its rounds,
-- gets the calibre's ordinary round in the Gunworks format. `drop` is Bandits' own function (the
-- wrapper passes it, so this second call never re-enters the wrapper).
function A.dropMagazines(brain,zombie,drop)
    local list={}
    for _,slot in ipairs({'primary','secondary'}) do
        local kit=brain.weapons and brain.weapons[slot]
        if type(kit)=='table' and kit.TWKey and kit.type=='mag' and type(kit.magName)=='string' then
            list[#list+1]={type=kit.magName,key=kit.TWKey}
        end
    end
    if #list==0 then return 0 end
    local made={}
    local real=instanceItem
    instanceItem=function(fullType,...)
        local item=real(fullType,...)
        if item then
            for _,e in ipairs(list) do
                if e.type==fullType then made[#made+1]={item=item,key=e.key};break end
            end
        end
        return item
    end
    local ok,a,b=pcall(drop,zombie,brain)
    instanceItem=real
    if not ok then error(a,0) end
    local o=A.options()
    local n=0
    for _,e in ipairs(made) do
        local item=e.item
        if type(item:getModData().AmmoList)~='table' then
            local max,count=item:getMaxAmmo() or 0,item:getCurrentAmmoCount() or 0
            local cal=D.weapons[e.key] and D.weapons[e.key].ammoKey
            if cal and max>0 and count>0 and LI.loadMagazine(item,cal,count/max,o)>0 then n=n+1 end
        end
    end
    A.stats.dropMags=A.stats.dropMags+n
    return n
end

-- The gun of the drop goes through ModifyWeapon (build and rounds) inside Bandits' own function;
-- the magazines need the pass above. Both happen in the bandit's own drop (chances, rarity kept).
function A.drop(zombie,brain,drop)
    if type(brain)~='table' or type(brain.weapons)~='table' then return 0 end
    A.convertBrain(brain)
    local n=A.safe('dropMags',A.dropMagazines,brain,zombie,drop or Bandit.UpdateItemsToSpawnAtDeath) or 0
    A.stats.drops=A.stats.drops+1
    return n
end

-- Wrappers -------------------------------------------------------------------------------------
-- Each wrapper calls Bandits' function exactly once with its own arguments (a fault of Bandits stays
-- its own); our part runs in A.safe.
local function wrapVisuals(original,bandit,brain,...)
    local previous=A.visual
    A.visual=true
    if bandit~=nil and type(brain)=='table' then A.safe('brain',A.convertBrain,brain) end
    local ok,a,b=pcall(original,bandit,brain,...)
    A.visual=previous
    if not ok then error(a,0) end
    return a,b
end

local function wrapModify(original,weapon,brain,...)
    local item=original(weapon,brain,...)
    if item~=nil and type(brain)=='table' then item=A.safe('item',A.touch,item,brain) or item end
    return item
end

local function wrapDrop(original,zombie,brain,...)
    local ok,a,b=pcall(original,zombie,brain,...)
    if not ok then error(a,0) end
    if zombie~=nil and type(brain)=='table' then A.safe('drop',A.drop,zombie,brain,original) end
    return a,b
end

A.HOOKS={
    {'Bandit','ApplyVisuals',wrapVisuals,true},
    {'BanditUtils','ModifyWeapon',wrapModify,true},
    {'Bandit','UpdateItemsToSpawnAtDeath',wrapDrop,true},
}

function A.detect()
    local B,U=part('Bandit'),part('BanditUtils')
    return B~=nil and type(B.ApplyVisuals)=='function' and type(B.UpdateItemsToSpawnAtDeath)=='function'
        and U~=nil and type(U.ModifyWeapon)=='function'
end

-- The first required hook that is missing (for the log when a Bandits update renames one).
function A.missing()
    for _,hook in ipairs(A.HOOKS) do
        local owner=part(hook[1])
        if hook[4] and not (owner and type(owner[hook[2]])=='function') then return hook[1]..'.'..hook[2] end
    end
end

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
    log('adapter on: '..table.concat(A.hooked,', '))
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
    for i=#A.patches,1,-1 do
        local p=A.patches[i]
        if p.owner[p.key]==p.wrapper then p.owner[p.key]=p.original end
    end
    A.patches,A.installed={},false
end

function A.qaLine()
    local parts={'installed='..tostring(A.installed),'hooks='..#A.hooked}
    for _,k in ipairs({'brains','kits','replaced','items','drops','dropMags','visual','mags','faults'}) do
        parts[#parts+1]=k..'='..tostring(A.stats[k])
    end
    return '[TW] Bandits adapter: '..table.concat(parts,' ')
end

-- Registered once per realm; the phase does not matter (Bandits' files load with the mod, the hooks
-- are on shared tables, the brain pass also runs on the MP client that receives a brain).
function A.boot()
    if A.booted then return end
    A.booted=true
    if not A.modActive() then A.mode='absent';return end
    A.mode='direct'
    local function late()
        if A.installed then return end
        if A.detect() then A.safe('install',A.install,A.rawWrap)
        else
            A.mode='absent (hook)'
            if not A.logged.hooks then A.logged.hooks=true;log('required hook missing: '..tostring(A.missing() or '?')) end
        end
    end
    if A.detect() then late() end
    if not A.installed then late() end
    if Events and Events.OnGameStart then Events.OnGameStart.Add(late) end
    if Events and Events.OnServerStarted then Events.OnServerStarted.Add(late) end
end

A.boot()
return A
