-- Loot rules of plan 0.9.x without side effects (docs/PLAN_LOOT_RU.md, section 3.3).
-- Numbers live in TW/Data/Loot (tools/loot_options.py, assets/loot.json); this file holds rules.
--
-- Loot tickets (docs/REPORT_RU.md 0.9.x): B42 rolls every entry of a loot list on its own with
-- chance = (weight * 100 * rarity + zombie density) * loot multiplier against Rand.Next(10000)
-- (ItemPickerJava.getActualSpawnChance). The density term is added to every entry, so the
-- plan's "every Tarkov item in every list" would multiply finds where zombies are dense, and
-- weights under 0.01 round up. Instead every vanilla anchor entry gets one ticket entry: a
-- representative mod item with the anchor's weight times the options. When the container is
-- filled the representative is replaced by an item drawn from the whole Tarkov distribution
-- of that kind and place (Loot.choose). Weight, density and resolution stay exactly vanilla.
local D=require('TW/Data')
local LD=require('TW/Data/Loot')
local Loot={data=LD}

-- Options (D12) ----------------------------------------------------------------------------
local OPTION={}
for _,o in ipairs(LD.options) do OPTION[o.id]=o end

local function sandbox()
    local vars=SandboxVars
    if type(vars)=='table' and type(vars[LD.namespace])=='table' then return vars[LD.namespace] end
    return nil
end

-- SandboxVars[LD.namespace][name]; missing, NaN or out of range -> the default clamped to min..max.
-- A boolean option is true or false; anything else is its default.
function Loot.option(name,vars)
    local o=OPTION[name]
    if not o then return nil end
    if vars==nil then vars=sandbox() end
    if o.type=='boolean' then
        local v=vars and vars[name]
        if type(v)=='boolean' then return v end
        return o.default
    end
    local v=vars and tonumber(vars[name]) or nil
    if v==nil or v~=v then v=o.default end
    if v<o.min then v=o.min elseif v>o.max then v=o.max end
    if o.type=='enum' then v=math.floor(v+0.5) end
    return v
end

function Loot.options(vars)
    if vars==nil then vars=sandbox() end
    local t={}
    for _,o in ipairs(LD.options) do t[o.id]=Loot.option(o.id,vars) end
    t._cache={}
    return t
end

local function cached(o,key,fn)
    local c=o._cache
    if not c then c={}; o._cache=c end
    local v=c[key]
    if v==nil then v=fn(); c[key]=v end
    return v
end

-- Places (section 5) -------------------------------------------------------------------------
function Loot.segments(path)
    local list={}
    for s in string.gmatch(path,'[^/]+') do list[#list+1]=s end
    return list
end

local CACHE_RULE
for _,rule in ipairs(LD.places.rules) do if rule.ctx=='caches' then CACHE_RULE=rule end end

local function matches(s,patterns)
    for _,p in ipairs(patterns) do
        if string.find(s,p) then return true end
    end
    return false
end

-- Context of a loot list path: civil, shops, gunstore, police, swat, army, caches, cachesLate.
-- Segments are read from the most specific one; the first segment a rule matches decides.
function Loot.placeOf(path)
    local segs=Loot.segments(path)
    -- Only an actually installed missing-room alias inherits its source's place policy.
    -- More-specific later segments still win; procedural, vehicle and outfit paths are untouched.
    if segs[1]=='rooms' and Loot.roomFallbacks and Loot.roomFallbacks[segs[2]] then
        segs[2]=Loot.roomFallbacks[segs[2]]
    end
    for i=#segs,1,-1 do
        local s=string.lower(segs[i])
        for _,rule in ipairs(LD.places.rules) do
            if matches(s,rule.patterns) and (rule.ctx~='cachesLate' or matches(s,CACHE_RULE.patterns)) then
                return rule.ctx
            end
        end
    end
    return 'civil'
end

function Loot.isPocket(path)
    for _,s in ipairs(Loot.segments(path)) do
        if matches(string.lower(s),LD.places.pockets) then return true end
    end
    return false
end

function Loot.isVehicle(path)
    return string.find(path,'^vehicles/')~=nil
end

function Loot.placeFactor(ctx,o)
    return o[LD.places.option[ctx]] or 1
end

function Loot.botGroup(ctx)
    return LD.places.botGroup[ctx] or 'survivor'
end

-- Weights ------------------------------------------------------------------------------------
local function calibreFactor(cal,o)
    local v=o['Cal_'..tostring(cal)]
    if v==nil then return 1 end
    return v
end
Loot.calibreFactor=calibreFactor

-- Military weapons outside military and SWAT places, the KS-23M outside police places too.
function Loot.militaryFactor(key,ctx,o)
    local w=LD.weapons[key]
    if LD.places.military[ctx] then return 1 end
    if w.military then return o.MilitaryOutside end
    if w.policeOnly and ctx~='police' then return o.MilitaryOutside end
    return 1
end

-- Scope of a draw for the NATO / Warsaw Pact sliders (1.0.9, user 21.10.2026; they replace
-- the old PactBalance and NatoOfficial): 'loot' (general loot), 'stateLoot' (loot at the places of state
-- forces: police, SWAT, army), 'statePeople' (zombies of the police and army groups, A-Life police,
-- military and special units), 'people' (every other zombie and A-Life NPC). people: nil for loot;
-- true for what a person carries, state forces when the person's place ctx is theirs; 'state' or
-- 'other' when the person is known apart from the list's place (a zombie's inventory: its outfit).
function Loot.pactScope(ctx,people)
    local official
    if people=='state' then official=true
    elseif people=='other' then official=false
    else official=ctx~=nil and LD.places.official[ctx]==true end
    if people then return official and 'statePeople' or 'people' end
    return official and 'stateLoot' or 'loot'
end

-- The slider of a draw's scope: -pactExtreme NATO and Western weapons only ... +pactExtreme Warsaw
-- Pact weapons only, 0 half and half.
function Loot.pactBalance(ctx,o,people)
    return o[LD.pactOption[Loot.pactScope(ctx,people)]] or 0
end

-- The Warsaw Pact share of a draw's scope (user 21.10.2026: "0 is 50/50"): 1/2 + b * pactStep, so -5
-- gives NATO 75 / Warsaw Pact 25 and -9 NATO 95 / Warsaw Pact 5; 0 at -pactExtreme, 1 at +pactExtreme.
function Loot.pactShare(ctx,o,people)
    local b=Loot.pactBalance(ctx,o,people)
    if b<=-LD.pactExtreme then return 0 end
    if b>=LD.pactExtreme then return 1 end
    return math.max(0,math.min(1,0.5+b*LD.pactStep))
end

-- Multipliers of the Warsaw Pact weight wp and the other side's weight wo of a pool: the Warsaw Pact
-- gets the share p and the pool keeps its total. A side missing from the pool: between the extremes
-- the pool stays as it is (no share to hold); at the extreme of the missing side the pool is emptied.
function Loot.sideMultipliers(p,wp,wo)
    local total=wp+wo
    if p<=0 then
        if wo>0 then return 0,total/wo end
        return 0,0
    end
    if p>=1 then
        if wp>0 then return total/wp,0 end
        return 0,0
    end
    if wp>0 and wo>0 then return p*total/wp,(1-p)*total/wo end
    return 1,1
end

-- Gives the Warsaw Pact the share p of the sided entries of a list of fresh {w=...} rows (side(e): true
-- the Warsaw Pact, false the other side, nil no side; Loot.sideMultipliers). Returns the rows left with
-- a weight and their total.
function Loot.applySides(list,p,side)
    local wp,wo=0,0
    for _,e in ipairs(list) do
        local s=side(e)
        if s==true then wp=wp+e.w elseif s==false then wo=wo+e.w end
    end
    local kp,ko=Loot.sideMultipliers(p,wp,wo)
    local out,total={},0
    for _,e in ipairs(list) do
        local s=side(e)
        if s==true then e.w=e.w*kp elseif s==false then e.w=e.w*ko end
        if e.w>0 then out[#out+1]=e; total=total+e.w end
    end
    return out,total
end

-- D10 (1.0.1): a category multiplier, like the calibre slider. A missing option
-- before that stage is neutral; the rule never admits a root absent from LD.classes.
function Loot.noTraderFactor(key,o)
    if LD.weapons[key].noTrader then return o['NoTraderWeapons'] or 1 end
    return 1
end

-- Launcher parts and shots outside military places, independently of the host (their side is shared
-- like the weapons': Loot.applySides).
function Loot.launcherOutside(ctx,o)
    return LD.places.military[ctx or 'civil'] and 1 or o.MilitaryOutside
end

local function pactRow(e) return LD.weapons[e.key].pact==true end
Loot.pactRow=pactRow

-- Weapons of a class at a place: list of {key, weight} (weight = w * military * calibre * NoTrader, the
-- NATO / Warsaw Pact share of the draw's scope given within the class: Loot.applySides) and the class
-- factor F = total / sum(m*w) (section 4.2: the calibre slider multiplies; the share keeps the total).
-- side: 0 when the slider at its extreme leaves out every weapon the class has at the place, else 1;
-- between the classes an anchor class splits into. people: weapons people carry (Loot.pactScope).
function Loot.classWeights(cls,ctx,o,people)
    return cached(o,'class:'..cls..':'..ctx..':'..Loot.pactScope(ctx,people),function()
        local p=Loot.pactShare(ctx,o,people)
        local rows,sumM,mp,mo={},0,0,0
        for _,key in ipairs(LD.classes[cls]) do
            local m=LD.weapons[key].w*Loot.militaryFactor(key,ctx,o)
            sumM=sumM+m
            if LD.weapons[key].pact then mp=mp+m else mo=mo+m end
            rows[#rows+1]={key=key,w=m*calibreFactor(D.weapons[key].ammoKey,o)*Loot.noTraderFactor(key,o)}
        end
        local list,total=Loot.applySides(rows,p,pactRow)
        local side=(sumM<=0 or (p<=0 and mo<=0) or (p>=1 and mp<=0)) and 0 or 1
        return {list=list,total=total,F=sumM>0 and total/sumM or 0,side=side}
    end)
end

-- Classes an anchor class becomes at a place (section 4.1), shares normalized to 1.
function Loot.classShares(base,ctx)
    local split=LD.splits[base] and LD.splits[base][ctx]
    if not split then return {{cls=base,share=1}} end
    local total,list=0,{}
    for cls,v in pairs(split) do total=total+v; list[#list+1]={cls=cls,share=v} end
    for _,e in ipairs(list) do e.share=e.share/total end
    table.sort(list,function(a,b) return a.cls<b.cls end)
    return list
end

-- What a weapon ticket of anchor class `base` draws from: classes with weight share*Class*F.
-- A slider at its extreme moves the share of a class it empties to the other classes of the anchor
-- (army rifles: machine guns are all Soviet/Russian), renormalized, so the anchor's total stays the same.
-- people: the weapon a person carries (zombies, A-Life NPCs; Loot.pactScope), not loot.
function Loot.weaponDraw(base,ctx,o,people)
    return cached(o,'weapon:'..base..':'..ctx..':'..Loot.pactScope(ctx,people),function()
        local shares,sum={},0
        for _,cs in ipairs(Loot.classShares(base,ctx)) do
            local cw=Loot.classWeights(cs.cls,ctx,o,people)
            shares[#shares+1]={cls=cs.cls,share=cs.share*cw.side,cw=cw}
            sum=sum+cs.share*cw.side
        end
        local list,total={},0
        for _,e in ipairs(shares) do
            local w=sum>0 and e.share/sum*o[LD.classOption[e.cls]]*e.cw.F or 0
            if e.cls=='launcher' and not LD.places.military[ctx] then w=w*o.MilitaryOutside end
            if w>0 and #e.cw.list>0 then list[#list+1]={cls=e.cls,w=w,items=e.cw.list,total=e.cw.total}; total=total+w end
        end
        return {list=list,total=total}
    end)
end

-- Parts and magazines from the item data (lw: loot share, assets/loot.json) ------------------
local PARTS,MAGS
local function calibresOf(item,tpl)
    local launcher=LD.launcherParts and LD.launcherParts[tpl]
    if launcher then return {launcher.calibre} end
    local set,list={},{}
    for _,key in ipairs(item.weapons or {}) do
        local cal=D.weapons[key] and D.weapons[key].ammoKey
        if cal and not set[cal] then set[cal]=true; list[#list+1]=cal end
    end
    table.sort(list)
    return list
end

local function buildParts()
    if PARTS then return end
    PARTS,MAGS={},{magPistol={},magRifle={}}
    local tpls={}
    for tpl,item in pairs(D.items) do if not item.isRoot and item.lw then tpls[#tpls+1]=tpl end end
    table.sort(tpls)
    for _,tpl in ipairs(tpls) do
        local item=D.items[tpl]
        if item.clip then
            -- A shared magazine: one item type per calibre, the share split between them.
            local variantCal={}
            for cal in pairs(item.variants or {}) do variantCal[cal]=true end
            local types={}
            for _,cal in ipairs(calibresOf(item)) do
                if not variantCal[cal] then types[#types+1]={type=item.pz,cal=cal} end
            end
            for cal,full in pairs(item.variants or {}) do types[#types+1]={type=full,cal=cal} end
            table.sort(types,function(a,b) return a.type<b.type end)
            for _,t in ipairs(types) do
                local class=D.ammo.calibres[t.cal] and D.ammo.calibres[t.cal].class
                local kind=class=='pistol' and 'magPistol' or 'magRifle'
                table.insert(MAGS[kind],{type=t.type,tpl=tpl,cal=t.cal,w=item.lw/#types})
            end
        else
            local group=item.internalMagazine and 'other' or LD.partGroups[item.category]
            PARTS[group]=PARTS[group] or {}
            table.insert(PARTS[group],{tpl=tpl,type=item.pz,w=item.lw,cals=LD.calibreParts[item.category] and calibresOf(item,tpl) or nil})
        end
    end
    for tpl,row in pairs(LD.extraParts or {}) do
        PARTS[row.group]=PARTS[row.group] or {}
        table.insert(PARTS[row.group],{tpl=tpl,type=row.type,w=row.w,policy=row})
    end
    for _,list in pairs(PARTS) do table.sort(list,function(a,b) return a.tpl<b.tpl end) end
end
function Loot.parts(group) buildParts(); return PARTS[group] or {} end
function Loot.magazines(kind) buildParts(); return MAGS[kind] or {} end

local function partCalibre(entry,o)
    if not entry.cals or #entry.cals==0 then return 1 end
    local sum=0
    for _,cal in ipairs(entry.cals) do sum=sum+calibreFactor(cal,o) end
    return sum/#entry.cals
end

-- Parts of a group at a place; underbarrel launchers and hand grenades have a side, shared by the
-- slider of the draw's scope among themselves (Loot.applySides).
function Loot.groupWeights(group,o,ctx,people)
    ctx=ctx or 'civil'
    return cached(o,'group:'..group..':'..ctx..':'..Loot.pactScope(ctx,people),function()
        local rows,sumW={},0
        for _,e in ipairs(Loot.parts(group)) do
            local w=e.w*partCalibre(e,o)
            local launcher=LD.launcherParts and LD.launcherParts[e.tpl]
            local pact
            if launcher then
                pact=launcher.pact==true
                w=w*Loot.launcherOutside(ctx,o)
            elseif e.policy then
                local p=e.policy
                pact=p.pact==true
                if not LD.places.military[ctx] and (p.military or p.police and ctx~='police') then w=w*o.MilitaryOutside end
            end
            sumW=sumW+e.w
            rows[#rows+1]={type=e.type,tpl=e.tpl,w=w,pact=pact}
        end
        local list,sumCW=Loot.applySides(rows,Loot.pactShare(ctx,o,people),function(e) return e.pact end)
        return {list=list,total=sumCW,F=sumW>0 and sumCW/sumW or 0}
    end)
end

function Loot.partDraw(kind,o,ctx,people)
    ctx=ctx or 'civil'
    return cached(o,'part:'..kind..':'..ctx..':'..Loot.pactScope(ctx,people),function()
        local split,sum=LD.partSplits[kind],0
        for _,v in pairs(split) do sum=sum+v end
        local groups={}
        for group in pairs(split) do groups[#groups+1]=group end
        table.sort(groups)
        local list,total={},0
        for _,group in ipairs(groups) do
            local gw=Loot.groupWeights(group,o,ctx,people)
            local w=split[group]/sum*o[LD.partOption[group]]*gw.F
            if w>0 and #gw.list>0 then list[#list+1]={group=group,w=w,items=gw.list,total=gw.total}; total=total+w end
        end
        return {list=list,total=total}
    end)
end

function Loot.magazineDraw(kind,o)
    return cached(o,'mag:'..kind,function()
        local list,sumW,sumCW={},0,0
        for _,e in ipairs(Loot.magazines(kind)) do
            local w=e.w*calibreFactor(e.cal,o)
            sumW=sumW+e.w
            sumCW=sumCW+w
            if w>0 then list[#list+1]={type=e.type,tpl=e.tpl,cal=e.cal,w=w} end
        end
        return {list=list,total=sumCW,F=sumW>0 and sumCW/sumW or 0}
    end)
end

-- Rounds ---------------------------------------------------------------------------------------
-- Rounds of a calibre that may spawn: all of them, or with "Only plain rounds" (W9-ammoopt) just
-- the rows marked plain in the data (the FMJ or standard round; buckshot and slug for shotguns;
-- launcher shots always). Empty rows fall back to the whole list.
function Loot.rows(cal,o)
    return cached(o,'rows:'..cal,function()
        local list=LD.rounds[cal]
        if not list or o.PlainAmmo~=true then return list end
        local out={}
        for _,r in ipairs(list) do if r.launcher or r.plain then out[#out+1]=r end end
        return #out>0 and out or list
    end)
end

-- The ordinary round of a calibre whose vanilla item is the mod's ordinary round (D15): the
-- vanilla item with VanillaAmmo 1-2, the mod's own copy with 3.
local function ordinary(row,o,form)
    if row.vanilla and o.VanillaAmmo==3 then return form=='loose' and row.own.type or row.own.box end
    return form=='loose' and row.type or row.box
end

function Loot.stdRow(cal,o)
    local rows=Loot.rows(cal,o)
    return rows and rows[1] or nil
end

-- Round item type -> {cal=..., row=..., form='loose'|'box'} for loose rounds, packs and the mod's
-- own copies of a vanilla ordinary round. Used by the container sweep of "Only plain rounds"
-- (TW_LootFill): ammunition the engine makes for a loot weapon carries the calibre's primary
-- round, which for a few calibres is not the plain one.
local ROUND_OF
function Loot.roundOf(ft)
    if not ROUND_OF then
        ROUND_OF={}
        for cal,rows in pairs(LD.rounds) do
            for _,r in ipairs(rows) do
                ROUND_OF[r.type]={cal=cal,row=r,form='loose'}
                ROUND_OF[r.box]={cal=cal,row=r,form='box'}
                if r.own then
                    ROUND_OF[r.own.type]={cal=cal,row=r,form='loose'}
                    ROUND_OF[r.own.box]={cal=cal,row=r,form='box'}
                end
            end
        end
    end
    return ROUND_OF[ft]
end

function Loot.stdRound(cal,o)
    local row=Loot.stdRow(cal,o)
    return row and ordinary(row,o,'loose') or nil
end

function Loot.stdBox(cal,o)
    local row=Loot.stdRow(cal,o)
    return row and ordinary(row,o,'box') or nil
end

-- Item of a round row in a form (loose, box, carton).
function Loot.roundItem(row,form,o)
    return ordinary(row,o,form=='loose' and 'loose' or 'box')
end

-- Rounds an anchor of vanilla calibre vc draws from: {cal,row,w} with w = share * Cal * round
-- share. With vanilla ammunition on, the anchor itself is the ordinary round of its own
-- calibre (D15), so that pair is left out. Launcher shots share their sides by the slider.
-- With "Only plain rounds" the pool is the calibre's plain rows (Loot.rows), renormalized over
-- them, so the calibre's share of the anchor stays the same.
function Loot.ammoDraw(vc,o,ctx,people)
    ctx=ctx or 'civil'
    return cached(o,'ammo:'..vc..':'..ctx..':'..Loot.pactScope(ctx,people),function()
        local rule=LD.vanillaCalibre[vc]
        local exclude=rule.own~=nil and o.VanillaAmmo~=3
        local cals,sum={},0
        for cal,s in pairs(rule.shares) do cals[#cals+1]=cal; sum=sum+s end
        table.sort(cals)
        local rows={}
        for _,cal in ipairs(cals) do
            local list=Loot.rows(cal,o)
            local rw=0
            for _,r in ipairs(list) do rw=rw+r.w end
            local cf=calibreFactor(cal,o)
            for _,r in ipairs(list) do
                local w=rule.shares[cal]/sum*cf*r.w/rw
                if r.launcher then w=w*(o['ClassLauncher'] or 1)*Loot.launcherOutside(ctx,o) end
                if w>0 and not (exclude and cal==rule.own and r.vanilla) then rows[#rows+1]={cal=cal,row=r,w=w} end
            end
        end
        local list,total=Loot.applySides(rows,Loot.pactShare(ctx,o,people),function(e)
            if e.row.launcher then return e.row.pact==true end
            return nil
        end)
        return {list=list,total=total}
    end)
end

-- Tickets ------------------------------------------------------------------------------------
-- Ticket of one vanilla anchor entry: representative type and weight, or nil. The pockets of a
-- zombie (flags.pocket) are drawn as what people carry (Loot.pactScope).
function Loot.ticket(name,weight,ctx,flags,o)
    local a=LD.anchors[name]
    if not a then return nil end
    local people=flags.pocket and true or nil
    local f
    if a.family=='weapon' then f=o.Guns*Loot.weaponDraw(a.kind,ctx,o,people).total
    elseif a.family=='part' then f=o.Parts*Loot.partDraw(a.kind,o,ctx,people).total
    elseif a.family=='magazine' then f=o.Magazines*Loot.magazineDraw(a.kind,o).F
    else f=o.Ammo*Loot.ammoDraw(a.vc,o,ctx,people).total end
    local w=weight*f*o.Loot*Loot.placeFactor(ctx,o)*(flags.vehicle and o.Vehicles or 1)*(flags.pocket and o.ZombiePockets or 1)
    if not (w>0) then return nil end
    return LD.reps[a.kind][ctx],w,a
end

local function baseName(name)
    if type(name)~='string' then return nil end
    local s=string.gsub(name,'^Base%.','')
    return s
end
Loot.baseName=baseName

-- Tickets of one loot list (items = {type, weight, ...}) at a path: {{type, weight}, ...}.
function Loot.plan(items,path,o)
    local ctx=Loot.placeOf(path)
    local flags={vehicle=Loot.isVehicle(path),pocket=Loot.isPocket(path)}
    local out={}
    for i=1,#items-1,2 do
        local name,w=baseName(items[i]),tonumber(items[i+1])
        if name and w and w>0 then
            local rep,tw=Loot.ticket(name,w,ctx,flags,o)
            if rep then out[#out+1]={rep,tw} end
        end
    end
    return out,ctx
end

-- Anchor entries of a list by kind: {kind = weight sum}.
function Loot.anchors(items)
    local sums={}
    for i=1,#items-1,2 do
        local a=LD.anchors[baseName(items[i]) or '']
        local w=tonumber(items[i+1])
        if a and w then sums[a.kind]=(sums[a.kind] or 0)+w end
    end
    return sums
end

-- Kind -> {family, vc, form} (one anchor of the kind carries them all).
local KINDS={}
for _,anchor in pairs(LD.anchors) do KINDS[anchor.kind]=anchor end
function Loot.kindInfo(kind) return KINDS[kind] end

-- Order of a loot list (AmmoFirst, AttachmentsFirst): the engine rolls the entries in
-- order and adds an item only while the container has room for it. Rank of an entry: 1
-- ammunition first, 2 parts and magazines first, 3 everything else, 4 a category after the
-- weapons. Tickets and vanilla anchors of the same family share the rank.
function Loot.family(name)
    local t=LD.tickets[name]
    if t then return KINDS[t.kind] and KINDS[t.kind].family end
    local a=LD.anchors[baseName(name) or '']
    return a and a.family
end

function Loot.rank(name,o)
    local f=Loot.family(name)
    if f=='ammo' then return o.AmmoFirst and 1 or 4 end
    if f=='part' or f=='magazine' then return o.AttachmentsFirst and 2 or 4 end
    return 3
end

-- Stable reorder of a well-formed list by rank; returns how many entries changed place.
function Loot.order(items,o)
    local buckets={{},{},{},{}}
    for i=1,#items-1,2 do
        local bucket=buckets[Loot.rank(items[i],o)]
        bucket[#bucket+1]=items[i]
        bucket[#bucket+1]=items[i+1]
    end
    local moved,n=0,0
    for _,bucket in ipairs(buckets) do
        for j=1,#bucket,2 do
            n=n+2
            if items[n-1]~=bucket[j] or items[n]~=bucket[j+1] then moved=moved+1 end
            items[n-1]=bucket[j]
            items[n]=bucket[j+1]
        end
    end
    return moved
end

-- Draws ----------------------------------------------------------------------------------------
local function pick(list,total,rng)
    local r=rng()*total
    for _,e in ipairs(list) do
        r=r-e.w
        if r<0 then return e end
    end
    return list[#list]
end
Loot.pick=pick

function Loot.isTicket(fullType)
    return LD.tickets[fullType]~=nil
end

-- What a representative becomes: {type=..., count=n, weapon=key, cal=..., kind=..., ctx=...}
-- or nil when the options now exclude everything of its kind. people: drawn into the inventory of a
-- person (the pockets and bags of a zombie; Loot.pactScope), else loot.
function Loot.choose(rep,o,rng,people)
    local t=LD.tickets[rep]
    if not t then return nil end
    return Loot.chooseKind(t.kind,t.ctx,o,rng,nil,people)
end

-- Replacement of a vanilla item of an anchor kind (engine-made ammunition, world stories).
-- noVanilla: never a vanilla item (VanillaAmmo 2 keeps the vanilla ordinary round as it is).
function Loot.replacement(name,ctx,o,rng,noVanilla,people)
    local a=LD.anchors[baseName(name) or '']
    if not a then return nil end
    return Loot.chooseKind(a.kind,ctx or 'civil',o,rng,noVanilla,people)
end

function Loot.chooseKind(kind,ctx,o,rng,noVanilla,people)
    local a=Loot.kindInfo(kind)
    if a.family=='weapon' then
        local draw=Loot.weaponDraw(kind,ctx,o,people)
        if draw.total<=0 then return nil end
        local cls=pick(draw.list,draw.total,rng)
        local e=pick(cls.items,cls.total,rng)
        return {type=D.weapons[e.key].pz,weapon=e.key,count=1,kind=kind,ctx=ctx,class=cls.cls,people=people}
    elseif a.family=='part' then
        local draw=Loot.partDraw(kind,o,ctx,people)
        if draw.total<=0 then return nil end
        local g=pick(draw.list,draw.total,rng)
        local e=pick(g.items,g.total,rng)
        return {type=e.type,tpl=e.tpl,count=1,kind=kind,ctx=ctx,group=g.group}
    elseif a.family=='magazine' then
        local draw=Loot.magazineDraw(kind,o)
        if draw.total<=0 then return nil end
        local e=pick(draw.list,draw.total,rng)
        return {type=e.type,tpl=e.tpl,cal=e.cal,count=1,kind=kind,ctx=ctx}
    end
    local draw=Loot.ammoDraw(a.vc,o,ctx,people)
    if noVanilla and o.VanillaAmmo~=3 then
        local list,total={},0
        for _,e in ipairs(draw.list) do
            if not e.row.vanilla then list[#list+1]=e; total=total+e.w end
        end
        draw={list=list,total=total}
    end
    if draw.total<=0 then return nil end
    local e=pick(draw.list,draw.total,rng)
    return {type=Loot.roundItem(e.row,a.form,o),cal=e.cal,role=e.row.role,count=a.form=='carton' and LD.cartonBoxes or 1,kind=kind,ctx=ctx,form=a.form}
end

-- Vanilla firearms and ammunition (D10) -----------------------------------------------------------
local VANILLA={}
for set,names in pairs(LD.vanilla) do
    if set~='toys' then for _,n in ipairs(names) do VANILLA[n]=set end end
end
function Loot.vanillaSet(name)
    return VANILLA[baseName(name) or '']
end

-- The vanilla option value that governs a vanilla item set (W9-vanparts): ammunition by VanillaAmmo,
-- weapon parts by VanillaParts (the "Like vanilla firearms" value - LD.vanillaPartsLike - means
-- VanillaFirearms, the behaviour before the option existed), firearms and magazines by VanillaFirearms.
function Loot.vanillaValue(set,o)
    if set=='ammo' then return o.VanillaAmmo end
    if set=='parts' then
        local v=o.VanillaParts
        if v==nil or v==LD.vanillaPartsLike then return o.VanillaFirearms end
        return v
    end
    return o.VanillaFirearms
end

-- 1 / 0.5 / 0 for a vanilla item by its set's option (Loot.vanillaValue); nil for other items.
function Loot.vanillaFactor(name,o)
    local set=VANILLA[baseName(name) or '']
    if not set then return nil end
    local v=Loot.vanillaValue(set,o)
    if v==1 then return 1 elseif v==2 then return 0.5 end
    return 0
end

-- Vanilla attachments the game put on a vanilla firearm (W9-vanparts): "Vanilla attachments" 2 removes
-- each with probability 0.5, 3 removes all, 1 and "Like vanilla firearms" leave the weapon as it is
-- (the behaviour before the option). The engine attaches them at loot time from SuburbsDistributions.
-- WeaponUpgrades and the story upgrades of RandomizedBuildingBase (ItemPickerJava.DoWeaponUpgrade,
-- addWeapon and rollWeaponUpgrades), so they are not loot list entries and no ticket can replace them.
-- Called on a freshly filled container (TW_LootFill) and by the world replacer (TW_LootWorld); the
-- ModData mark keeps a weapon from being rolled twice by the two passes. Returns how many were removed.
function Loot.stripVanillaParts(item,o,rng)
    local v=o.VanillaParts
    if v~=2 and v~=3 then return 0 end
    if not item or not instanceof(item,'HandWeapon') then return 0 end
    if Loot.vanillaSet(item:getFullType())~='firearms' then return 0 end
    local md=item:getModData()
    if md.TWVanillaParts==v then return 0 end
    md.TWVanillaParts=v
    local parts=item.getAllWeaponParts and item:getAllWeaponParts()
    if not parts then return 0 end
    local list={}
    for i=0,parts:size()-1 do list[#list+1]=parts:get(i) end
    if #list==0 then return 0 end
    rng=rng or Loot.rng
    local n=0
    for _,part in ipairs(list) do
        if v==3 or rng()<0.5 then
            item:detachWeaponPart(part)
            n=n+1
        end
    end
    return n
end

function Loot.rng()
    return ZombRand(1000000)/1000000
end

return Loot
