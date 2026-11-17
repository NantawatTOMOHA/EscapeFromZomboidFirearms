-- Container fill of plan 0.9.x (docs/PLAN_LOOT_RU.md 3.5): OnFillContainer fires after the
-- engine filled a room container, a zombie's inventory or bag, a vehicle part or a bag spawned
-- inside a container. Here the loot tickets of TW/Loot are drawn: every representative item is
-- replaced by an item drawn from the Tarkov distribution of its kind and place. A loot weapon
-- takes over what the engine made for the representative right after it (gun storage: 1-3
-- magazines and 0-2 ammo packs, ItemPickerJava.doGunStorageContainer; elsewhere maybe one
-- magazine, HandWeapon.randomizeFirearmAsLoot) and its condition. With VanillaAmmo 3 the
-- ammunition the engine made for vanilla weapons is replaced and their rounds unloaded (D10);
-- with "Vanilla attachments" 2/3 the attachments the engine put on a vanilla firearm are taken off
-- (W9-vanparts, Loot.stripVanillaParts).
local Loot=require('TW/Loot')
local LI=require('TW/LootItems')
local LB=require('TW/LootBuild')
local R=require('TW/Runtime')
local D=require('TW/Data')

local TWLootFill={stats={containers=0,tickets=0,weapons=0,companions=0,normalized=0,unloaded=0,builds={},kitMags=0,kitPacks=0,kitParts=0,
    loaded=0,noRoom=0,parts=0}}
TWLootFillAPI=TWLootFill

local function addIfRoom(container,item,report)
    if not item then return nil end
    if container.hasRoomFor and not container:hasRoomFor(nil,item) then
        report.noRoom=report.noRoom+1
        return nil
    end
    container:AddItem(item)
    return item
end

-- A new loot weapon (container fill, zombie drop, world replacer; D8, D16): its build by
-- Presets, its rounds (what the engine loaded for the representative, else Loaded), and in a
-- container the kit next to it where there is room. Once per weapon (ModData TWLootKit). info: the
-- choice (ctx, people: the NATO / Warsaw Pact scope of a zombie's inventory, TW/Loot.pactScope).
function TWLootFill.prepareWeapon(weapon,container,info,o,rng)
    local md=weapon:getModData()
    if md.TWLootKit then return nil end
    md.TWLootKit=1
    local key=D.byType[weapon:getFullType()]
    if not key then return nil end
    o=o or Loot.options()
    rng=rng or Loot.rng
    local report=TWLootFill.stats
    local ctx=info and info.ctx or 'civil'
    local tree,source=LB.treeFor(key,Loot.botGroup(ctx),o,rng)
    if source~='default' then
        local ok=R.applyTree(weapon,tree)
        if not ok then source='default' end
    end
    md.TWLootBuild=source
    local kind=source:match('^(%a+)') or source
    report.builds[kind]=(report.builds[kind] or 0)+1
    local engine=md.TWLootEngine or {mags=0,boxes=0,loaded=0}
    local kit=LB.kit(key,R.tree(weapon),o,rng,engine,ctx,info and info.people)
    if key=='RSHG2' then
        -- OnCreate owns the single rocket. Loot must never refill a used disposable.
        if weapon:getCurrentAmmoCount()>0 then report.loaded=report.loaded+1 end
    elseif kit.loaded and kit.loaded>0 and LI.loadWeapon(weapon,kit.loaded,o)>0 then report.loaded=report.loaded+1 end
    if container and not (info and info.noKit) then
        local cal=D.weapons[key].ammoKey
        for _,m in ipairs(kit.mags) do
            local mag=LI.mark(instanceItem(m.type))
            if mag then
                LI.loadMagazine(mag,cal,m.loaded,o)
                if addIfRoom(container,mag,report) then report.kitMags=report.kitMags+1 end
            end
        end
        for _,pack in ipairs(kit.packs) do
            if addIfRoom(container,LI.mark(instanceItem(pack)),report) then report.kitPacks=report.kitPacks+1 end
        end
        for _,part in ipairs(kit.parts) do
            if addIfRoom(container,LI.mark(instanceItem(part)),report) then report.kitParts=report.kitParts+1 end
        end
    end
    return kit
end

local function add(container,item)
    container:AddItem(item)
    return item
end

local function remove(container,item)
    container:Remove(item)
end

local function loadedFraction(weapon)
    local max=weapon:getMaxAmmo()
    if not max or max<=0 then return 0 end
    return math.min(1,weapon:getCurrentAmmoCount()/max)
end

-- A magazine the engine made for the representative becomes the loot weapon's magazine with the
-- same fill (full in gun storage unless dontSpawnAmmo, else a random count), its rounds the
-- ordinary round of the loot weapon's calibre in the Gunworks format.
local function convertMagazine(container,old,newType,cal,o)
    local fill=0
    local max=old:getMaxAmmo()
    if max and max>0 then fill=old:getCurrentAmmoCount()/max end
    remove(container,old)
    if not newType then return nil end
    local mag=LI.mark(instanceItem(newType))
    if not mag then return nil end
    LI.loadMagazine(mag,cal,fill,o)
    return add(container,mag)
end

local function replaceWeapon(container,list,i,o,rng,report,people)
    local rep=list[i]
    local magType,boxType=rep:getMagazineType(),rep:getAmmoBox()
    local mags,boxes={},{}
    local j=i
    -- doGunStorageContainer adds 1-3 magazines, then 0-2 packs; randomizeFirearmAsLoot one magazine.
    while j+1<=#list do
        local nt=list[j+1]:getFullType()
        if magType and nt==magType and #boxes==0 and #mags<3 and not LI.isMarked(list[j+1]) then mags[#mags+1]=list[j+1]
        elseif boxType and nt==boxType and #boxes<2 and not LI.isMarked(list[j+1]) then boxes[#boxes+1]=list[j+1]
        else break end
        j=j+1
    end
    local choice=Loot.choose(rep:getFullType(),o,rng,people)
    local loaded=(rep:isContainsClip() or rep:getCurrentAmmoCount()>0) and loadedFraction(rep) or 0
    remove(container,rep)
    local weapon=choice and LI.create(choice)[1] or nil
    if weapon then
        LI.condition(rep,weapon)
        add(container,weapon)
        report.weapons=report.weapons+1
        -- What the engine made for the representative now belongs to the loot weapon.
        local md=weapon:getModData()
        md.TWLootEngine={mags=#mags,boxes=#boxes,loaded=loaded}
        local newMag,newBox=LI.weaponMagazine(weapon),LI.weaponBox(weapon,o)
        local cal=D.weapons[choice.weapon].ammoKey
        for _,m in ipairs(mags) do convertMagazine(container,m,newMag,cal,o); report.companions=report.companions+1 end
        for _,b in ipairs(boxes) do
            remove(container,b)
            local pack=newBox and LI.mark(instanceItem(newBox))
            if pack then add(container,pack) end
            report.companions=report.companions+1
        end
        TWLootFill.prepareWeapon(weapon,container,choice,o,rng)
    else
        for _,m in ipairs(mags) do remove(container,m) end
        for _,b in ipairs(boxes) do remove(container,b) end
    end
    return j+1
end

-- Ammunition and other single tickets; loose rounds of one roll come in a row of one type.
local function replaceItems(container,list,i,o,rng,people)
    local item=list[i]
    local ft=item:getFullType()
    local info=Loot.kindInfo(Loot.data.tickets[ft].kind)
    local j=i
    if info.family=='ammo' and info.form=='loose' then
        while j+1<=#list and list[j+1]:getFullType()==ft and not LI.isMarked(list[j+1]) do j=j+1 end
    end
    local choice=Loot.choose(ft,o,rng,people)
    for k=i,j do remove(container,list[k]) end
    if choice then
        choice.count=(choice.count or 1)*(j-i+1)
        for _,new in ipairs(LI.create(choice)) do add(container,new) end
    end
    return j+1
end

-- VanillaAmmo 3: ammunition the engine made for vanilla weapons (packs and magazines next to
-- them, their rounds) never reaches the world.
local function normalizeVanillaAmmo(container,list,o,rng,report,people)
    for _,item in ipairs(list) do
        local name=item:getFullType()
        local set=Loot.vanillaSet(name)
        -- Only these vanilla sets can change; keep the container's own membership test for them.
        if (set=='ammo' or set=='firearms' or set=='magazines') and container:contains(item) then
            if set=='ammo' then
                local choice=Loot.replacement(name,'civil',o,rng,nil,people)
                remove(container,item)
                for _,new in ipairs(LI.create(choice)) do add(container,new) end
                report.normalized=report.normalized+1
            elseif (set=='firearms' or set=='magazines') and item:getCurrentAmmoCount()>0 then
                item:setCurrentAmmoCount(0)
                if instanceof(item,'HandWeapon') then item:setRoundChambered(false) end
                report.unloaded=report.unloaded+1
            end
        end
    end
end

-- "Only plain rounds" (W9-ammoopt): a freshly filled container never keeps a special round,
-- whatever made it. The engine's own ammunition for a loot weapon carries the calibre's primary
-- round, and for .50 AE, 4.6x30, .338 Lapua, 9x39 and .357 that primary round is a special one
-- (Hawk JSP, JSP SX, UCW, PAB-9, HP): it becomes the plain round or pack of its calibre with the
-- same form and count (a launcher shot is not a cartridge and is left alone).
local function normalizeSpecialRounds(container,list,o,report)
    for _,item in ipairs(list) do
        local e=Loot.roundOf(item:getFullType())
        if e and not (e.row.plain or e.row.launcher) and container:contains(item) then
            local plain=Loot.stdRow(e.cal,o)
            if plain then
                local count=item.getCount and item:getCount() or 1
                remove(container,item)
                local type=Loot.roundItem(plain,e.form,o)
                for _=1,math.max(1,count) do add(container,LI.mark(instanceItem(type))) end
                report.plain=(report.plain or 0)+1
            end
        end
    end
end

-- "Rarer": the tables already halved these vanilla items; the world replacer leaves them. The set's own
-- option decides (W9-vanparts: weapon parts by VanillaParts, ammunition by VanillaAmmo, the rest by
-- VanillaFirearms), so a part kept at full weight with "Vanilla attachments" = Spawn is not marked.
function TWLootFill.keepVanilla(list,o)
    for _,item in ipairs(list) do
        local set=Loot.vanillaSet(item:getFullType())
        if set and Loot.vanillaValue(set,o)==2 then item:getModData().TWLootKeep=1 end
    end
end

-- people: nil for loot; 'state' or 'other' for the inventory of a zombie of the state forces or not
-- (the NATO / Warsaw Pact sliders of people, TW/Loot.pactScope).
function TWLootFill.fill(container,o,rng,people)
    if not container then return nil end
    local items=container:getItems()
    local n=items:size()
    if n==0 then return nil end
    local list,tickets,vanilla={},false,false
    for i=0,n-1 do
        local item=items:get(i)
        list[#list+1]=item
        local ft=item:getFullType()
        if Loot.isTicket(ft) and not LI.isMarked(item) then tickets=true end
        if Loot.vanillaSet(ft) then vanilla=true end
    end
    o=o or Loot.options()
    -- "Rarer": the tables already halved these vanilla items; the world replacer leaves them.
    if vanilla then TWLootFill.keepVanilla(list,o) end
    -- W9-vanparts: "Vanilla attachments" 2/3 takes off what the engine attached to a vanilla firearm
    -- (ItemPickerJava.DoWeaponUpgrade), which no list entry can replace; the fill runs even when this is
    -- the only work a container has.
    local strip=o.VanillaParts==2 or o.VanillaParts==3
    local specials
    if o.PlainAmmo then
        for _,item in ipairs(list) do
            local e=Loot.roundOf(item:getFullType())
            if e and not (e.row.plain or e.row.launcher) then specials=true; break end
        end
    end
    if not tickets and not (vanilla and o.VanillaAmmo==3) and not specials and not (strip and vanilla) then return nil end
    rng=rng or Loot.rng
    local report=TWLootFill.stats
    report.containers=report.containers+1
    local i=1
    while i<=#list do
        local item=list[i]
        local ft=item:getFullType()
        if Loot.isTicket(ft) and not LI.isMarked(item) then
            report.tickets=report.tickets+1
            if instanceof(item,'HandWeapon') and D.byType[ft] then i=replaceWeapon(container,list,i,o,rng,report,people)
            else i=replaceItems(container,list,i,o,rng,people) end
        else
            i=i+1
        end
    end
    if vanilla and o.VanillaAmmo==3 then normalizeVanillaAmmo(container,list,o,rng,report,people) end
    if strip and vanilla then
        for _,item in ipairs(list) do
            if Loot.vanillaSet(item:getFullType())=='firearms' and container:contains(item) then
                report.parts=report.parts+Loot.stripVanillaParts(item,o,rng)
            end
        end
    end
    if o.PlainAmmo then normalizeSpecialRounds(container,list,o,report) end
    return report
end

-- W11 milloot (item 6): a nested container the engine left empty. The engine fills a bag item a
-- container roll produced through ItemSpawner -> ItemPickerJava.rollContainerItem, but a bag that
-- comes out empty (the player's military crate: a bulky protective case and an ammo box with
-- nothing inside) is rolled here from the same list the engine would have used - the bag's own
-- SuburbsDistributions entry - and the tickets of that roll are resolved by the same fill pass.
-- Depth first, a filled bag is never touched again, so every nested level of a filled container is
-- reached on every path (event, ZB sweep, world replacer) and a second pass changes nothing.
local function nestedRolls(def)
    -- The build's rolls multiplier is not exposed to Lua (SandboxOptions.instance.rollsMultiplier
    -- is null there and the Java error escapes pcall), so the list's own rolls are used.
    return math.max(1,math.floor(tonumber(def and def.rolls) or 1))
end

local function nestedPick(items)
    local total=0
    for i=2,#items,2 do total=total+(tonumber(items[i]) or 0) end
    if total<=0 then return nil end
    local roll=Loot.rng()*total
    for i=1,#items-1,2 do
        roll=roll-(tonumber(items[i+1]) or 0)
        if roll<=0 then return items[i] end
    end
    return items[#items-1]
end

function TWLootFill.nested(container,o,rng,depth)
    if not container or (depth or 0)>4 then return 0 end
    rng=rng or Loot.rng
    local items=container:getItems()
    local filled=0
    for i=0,items:size()-1 do
        local item=items:get(i)
        if instanceof(item,'InventoryContainer') then
            -- One options snapshot for this walk, only when there is a bag to visit.
            o=o or Loot.options()
            local inv=item:getInventory()
            if inv and inv:getItems():size()==0 then
                local def=SuburbsDistributions and SuburbsDistributions[item:getType()]
                if def and type(def.items)=='table' and #def.items>=2 then
                    local made=0
                    for _=1,nestedRolls(def) do
                        local name=nestedPick(def.items)
                        local rep=name and instanceItem(name)
                        if rep then inv:AddItem(rep); made=made+1 end
                    end
                    if made>0 then TWLootFill.fill(inv,o,rng) end
                    filled=filled+made
                end
            end
            filled=filled+TWLootFill.nested(inv,o,rng,(depth or 0)+1)
        end
    end
    return filled
end

-- Representatives a fill never reached (W11 loot, part B). The engine fills a container through
-- OnFillContainer only from ItemPickerJava.fillContainerInternal; the map stashes (StashSystem),
-- the story buildings (RBBasic, RBSafehouse, RDSSpecificProfession, the vehicle and zone stories)
-- and every other caller of fillContainerType / rollItem / rollContainerItem fill without that
-- event, so the representatives of the loot tickets stay in the container for good. The world
-- replacer sweeps the containers it passes anyway and calls this; the place is the
-- representative's own (TW/Data/Loot tickets[rep].ctx), never the room's, so a ticket taken from
-- a military pool still draws military loot. Returns how many representatives became loot.
function TWLootFill.tickets(container,o,rng,people)
    if not container then return 0 end
    local items=container:getItems()
    local found=false
    for i=0,items:size()-1 do
        local item=items:get(i)
        if Loot.isTicket(item:getFullType()) and not LI.isMarked(item) then found=true;break end
    end
    if not found then return 0 end
    local before=TWLootFill.stats.tickets
    TWLootFill.fill(container,o,rng,people)
    return TWLootFill.stats.tickets-before
end

-- A representative outside a container (an item on the floor, W11 loot part B): what its ticket
-- draws, with the weapon's build and rounds but no kit and no engine companions. nil when the
-- item is no representative; an empty list when the options exclude everything of its kind.
function TWLootFill.representative(item,o,rng,people)
    if not item then return nil end
    local ft=item:getFullType()
    if not Loot.isTicket(ft) or LI.isMarked(item) then return nil end
    local choice=Loot.choose(ft,o,rng,people)
    if not choice then return {} end
    local items=LI.create(choice)
    if instanceof(item,'HandWeapon') then
        local weapon=items[1]
        if weapon and instanceof(weapon,'HandWeapon') then
            LI.condition(item,weapon)
            local max=item:getMaxAmmo() or 0
            local loaded=(max>0 and item:getCurrentAmmoCount()>0) and math.min(1,item:getCurrentAmmoCount()/max) or 0
            weapon:getModData().TWLootEngine={mags=0,boxes=0,loaded=loaded}
            TWLootFill.prepareWeapon(weapon,nil,{ctx=choice.ctx,people=choice.people,noKit=true},o,rng)
        end
    end
    return items
end

-- Native randomizeBullets can turn MaxAmmo=1 into zero after OnCreate. Only
-- recover that fresh attached-item state: its original exact rocket AmmoList is
-- still intact. A fired/emptied tube has no such round and is never refilled.
local function recoverFreshZombieOrdnance(item)
    local weapon=D.weapons.RSHG2
    if not weapon or item:getFullType()~=weapon.pz then return false end
    local md=item:getModData()
    local state=md.TWDisposable
    if md.TWLoot or md.TWLootEngine or md.TWLootKit or not state or
       state.initialized~=true or state.spent~=false or state.zombieRecovered then return false end
    if type(md.AmmoList)~='table' or #md.AmmoList~=1 or md.AmmoList[1]~=weapon.ammo then return false end
    if item:getCurrentAmmoCount()>=1 then return false end
    item:setCurrentAmmoCount(1)
    state.zombieRecovered=true
    return true
end

-- A zombie's Tarkov weapon on its body (twins of D9) gets its build by Presets when the zombie
-- dies; the rounds the engine loaded (HandWeapon.randomizeBullets) keep their count and become
-- the ordinary round of the calibre by VanillaAmmo (D10, D15). No kit on a corpse.
function TWLootFill.zombieDead(zombie,o,rng)
    if not zombie or not zombie.getAttachedItems then return 0 end
    local attached=zombie:getAttachedItems()
    if not attached then return 0 end
    local Z=require('TW/LootZombie')
    local outfit=zombie.getOutfitName and zombie:getOutfitName() or nil
    local ctx=Z.groupCtx[Z.outfitGroup(outfit or '')]
    local n=0
    o=o or Loot.options()
    for i=0,attached:size()-1 do
        local item=attached:get(i):getItem()
        if item then TWLootFill.keepVanilla({item},o) end
        if item and D.byType[item:getFullType()] and not item:getModData().TWLootKit then
            recoverFreshZombieOrdnance(item)
            local max=item:getMaxAmmo() or 0
            local loaded=(max>0 and item:getCurrentAmmoCount()>0) and math.min(1,item:getCurrentAmmoCount()/max) or 0
            item:getModData().TWLoot=1
            item:getModData().TWLootEngine={mags=0,boxes=0,loaded=loaded}
            TWLootFill.prepareWeapon(item,nil,{ctx=ctx,noKit=true},o,rng)
            n=n+1
        end
    end
    TWLootFill.stats.zombieWeapons=(TWLootFill.stats.zombieWeapons or 0)+n
    return n
end

Events.OnZombieDead.Add(function(zombie)
    if isClient() then return end
    local ok,err=pcall(TWLootFill.zombieDead,zombie)
    if not ok then print('[TW] loot zombie drop failed: '..tostring(err)) end
end)

-- The NATO / Warsaw Pact scope of a zombie's inventory: B42.21 fillContainerInternal fires "Zombie"
-- with the outfit name of the corpse (IsoDeadBody.getOutfitName, bytecode 100-133, else the container
-- type inventorymale/female); the police and army outfit groups are the state forces.
function TWLootFill.zombiePeople(outfit)
    local Z=require('TW/LootZombie')
    return Z.outfitGroup(tostring(outfit or ''))~='survivor' and 'state' or 'other'
end

-- The engine passes an ItemContainer, except for the outfit bags of a zombie: B42.21
-- ItemPickerJava.fillContainerInternal fires "Zombie Bag" with the bag's ItemPickerContainer (the
-- distribution, bytecode 261-277) instead of its ItemContainer. Such bags are reached through the
-- "Zombie" event of the inventory that holds them, fired right after (bytecode 430-439).
function TWLootFill.onFill(roomName,containerType,container)
    if isClient() or not container or not instanceof(container,'ItemContainer') then return end
    local people=roomName=='Zombie' and TWLootFill.zombiePeople(containerType) or nil
    TWLootFill.fill(container,nil,nil,people)
    -- W11 milloot (item 6): a nested bag the engine left empty inside this container (the player's
    -- military crate held an empty bulky ammo case) is filled at every level by the mod's own pass.
    TWLootFill.nested(container)
    if roomName=='Zombie' then
        local items=container:getItems()
        for i=0,items:size()-1 do
            local item=items:get(i)
            if instanceof(item,'InventoryContainer') then TWLootFill.fill(item:getInventory(),nil,nil,people) end
        end
    end
end
Events.OnFillContainer.Add(TWLootFill.onFill)

-- Java side (ZB PatchLootPaths, W11 loot part B): a container the engine filled through
-- fillContainerType / rollItem / rollContainerItem - never through OnFillContainer - is swept as
-- soon as the engine returns, so a story building or a map stash placed after the world replacer
-- passed still gets its loot. The same sweep as the world replacer's; a container whose fill did
-- fire OnFillContainer has no representative left and nothing happens. nil-safe and server only:
-- a client's Lua has no authority over a container and the server fills it.
TWLootPaths={off=false}
function TWLootPaths.qaOff(off) TWLootPaths.off=off and true or false end
function TWLootPaths.fill(container)
    if TWLootPaths.off or isClient() then return 0 end
    if not container or not instanceof(container,'ItemContainer') then return 0 end
    local n=TWLootFill.tickets(container)
    -- W11 milloot (item 6): the nested level of a container filled without the event (a map stash,
    -- a story building) as well: its empty bags are filled and resolved like the event path's.
    TWLootFill.nested(container)
    TWLootFill.stats.javaTickets=(TWLootFill.stats.javaTickets or 0)+n
    return n
end

return TWLootFill
