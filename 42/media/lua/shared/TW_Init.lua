require('TW/Runtime')
require('TW/Launchers')
require('TW/Mechanics')
require('TW/MagazineSounds')
require('TW/AmmoKind')
require('TW/MeleeSurface').install()
local Ammo=require('WeaponSystems/Utils/Ammo')
local Magazine=require('WeaponSystems/Utils/Magazine')
local Rate=require('WeaponSystems/Utils/RateOfFire')
local Stats=require('WeaponSystems/Utils/StatsFactory')
local D=require('TW/Data')
local C=require('TW/Core')
local A=D.ammo
local registered=false

-- Kept separate from calibre/reload registration for the authored grip layer.
local function registerGrips() require('TW/Grips').register() end
Events.OnGameBoot.Add(registerGrips)

-- One Gunworks ammo family per calibre, shared by every weapon and magazine of
-- that calibre (TW/Data/Ammo, from tools/ammo_catalog.py): the ordinary round
-- first (vanilla B42 where it exists, else the mod's own), then up to three
-- Tarkov rounds of other roles, then the same-calibre rounds of the Gunworks
-- ammunition library and, when enabled, of the Brita B42 port. Exact calibres
-- only (.223 Rem in 5.56 chambers, .308 Win in 7.62x51 chambers).
local ALIASES={}
for key,calibre in pairs(A.calibres) do ALIASES[key]=calibre.aliases end
TWAmmoAliases=ALIASES

-- Stat profiles: a round changes the weapon from its base while it is fired
-- (Gunworks Ammo layer); the restore list returns these stats to the base
-- first, so nothing accumulates. Sound, recoil delay and hit chance belong to
-- the mod's own layer (TWRuntime.modifiers, A.extras) and are never listed here.
local OPS={Multiply=Stats.Multiply,Set=Stats.Set,Adjust=Stats.Adjust}
local function modifiers(rows)
    local list={}
    for _,row in ipairs(rows) do list[#list+1]=OPS[row[1]](row[2],row[3]) end
    return list
end
Ammo.RegisterRestoreStats({'MinDamage','MaxDamage','PiercingBullets','MaxHitCount','ProjectileCount','ProjectileSpread','ConditionLowerChanceOneIn','CriticalChance'})
local profiles={
    TW_ArmorPiercing={Stats.Multiply('MinDamage',0.9),Stats.Multiply('MaxDamage',0.9),Stats.Set('PiercingBullets',true),Stats.Adjust('MaxHitCount',1)},
    TW_HollowPoint={Stats.Multiply('MinDamage',1.15),Stats.Multiply('MaxDamage',1.15),Stats.Set('PiercingBullets',false),Stats.Set('MaxHitCount',1)},
    -- Quieter shot: TWRuntime.modifiers lowers the sound for this profile (A.extras).
    TW_Subsonic={Stats.Multiply('MinDamage',0.85),Stats.Multiply('MaxDamage',0.85)},
    TW_Overpressured={Stats.Multiply('MinDamage',1.1),Stats.Multiply('MaxDamage',1.1),Stats.Multiply('ConditionLowerChanceOneIn',0.75)},
    -- One heavy projectile instead of buckshot.
    TW_Slug={Stats.Set('ProjectileCount',1),Stats.Set('MaxHitCount',2),Stats.Set('ProjectileSpread',0),Stats.Multiply('MinDamage',2),Stats.Multiply('MaxDamage',2),
        Stats.Set('PiercingBullets',true)},
}
for name,rows in pairs(A.profiles) do profiles[name]=modifiers(rows) end
Ammo.RegisterMultipleAmmoStats(profiles)

-- AmmoType of a round: its own script value (Gunworks library), the Brita
-- registry, or the mod's registry; nil when the round is not installed.
local function enumFor(itemType)
    local script=ScriptManager.instance:FindItem(itemType)
    if not script then return nil end
    local at=script:getAmmoType()
    if at and tostring(at:getItemKey())==itemType then return at end
    if BGWLAmmoTypes and BGWLAmmoTypes[itemType] then return BGWLAmmoTypes[itemType] end
    if TWAmmoTypes and TWAmmoTypes[itemType] then return TWAmmoTypes[itemType] end
end
TWAmmoEnumFor=enumFor

function TWAmmoFamily(ammoKey) return 'TW:ammo:'..ammoKey end

-- A family is registered for a calibre only when a weapon of the mod uses it.
local function registerCalibre(weapon,enum,done)
    local family=TWAmmoFamily(weapon.ammoKey)
    if done[family] then return family end
    local calibre=A.calibres[weapon.ammoKey]
    local primary=calibre and calibre.rounds and calibre.rounds[1]
    local bullets={{type=weapon.ammo,enum=enum,profile=primary and primary.profile}}
    for i,round in ipairs(calibre and calibre.rounds or {}) do
        if i>1 then
            local roundEnum=enumFor(round.type)
            assert(roundEnum,'[TW] AmmoType unresolved for '..round.type)
            bullets[#bullets+1]={type=round.type,enum=roundEnum,profile=round.profile}
        end
    end
    for _,itemType in ipairs(ALIASES[weapon.ammoKey] or {}) do
        local aliasEnum=enumFor(itemType)
        if aliasEnum then
            -- Keep a profile another content mod already gave this round.
            local existing=Ammo.FindBulletEntry(itemType)
            bullets[#bullets+1]={type=itemType,enum=aliasEnum,profile=existing and existing.profile or A.aliasProfiles[itemType]}
        end
    end
    Ammo.RegisterAmmoFamily(family,bullets)
    done[family]=#bullets
    return family
end

local function register()
    if registered then return end
    local summary,families={},{}
    for key,weapon in pairs(D.weapons) do
        local script=ScriptManager.instance:FindItem(weapon.pz)
        if not script then return end
        local at=script:getAmmoType()
        assert(at and tostring(at:getItemKey())==weapon.ammo,'[TW] Native ammo unresolved for '..weapon.pz)
        local ammoFamily=registerCalibre(weapon,at,families)
        Ammo.RegisterItemWithFamily(ammoFamily,weapon.pz)
        local profileName='TW:'..key
        local magazines={}
        for _,mag in ipairs(weapon.magazines) do magazines[#magazines+1]=mag; Ammo.RegisterItemWithFamily(ammoFamily,mag) end
        -- Default magazine first; Gunworks keeps the order for its reload choice.
        local defaultTpl=weapon.presets[weapon.defaultPreset].mod_magazine
        local default=defaultTpl and D.items[defaultTpl].clip and C.clipType(key,defaultTpl) or nil
        table.sort(magazines,function(a,b)
            if default and (a==default)~=(b==default) then return a==default end
            return a<b
        end)
        -- Built-in magazines only (revolver cylinder, tube cap): the vanilla
        -- round-by-round reload, no Gunworks magazine profile.
        if #magazines>0 then
            Magazine.RegisterMagazineProfile(profileName,magazines)
            Magazine.RegisterWeaponWithProfile(profileName,weapon.pz)
        end
        -- An installed part with vanilla magazine conflicts (M700 chassis) narrows
        -- the reload choice; Gunworks prefers an installed part's profile.
        for tpl,allowed in pairs(weapon.partMagazines or {}) do
            local profile=profileName..':'..tpl
            local list={}
            for _,mag in ipairs(magazines) do
                for _,ok in ipairs(allowed) do if ok==mag then list[#list+1]=mag end end
            end
            Magazine.RegisterMagazineProfile(profile,list)
            local item=D.items[tpl]
            local types={}
            for copy=0,(item.copies or 1)-1 do types[#types+1]=copy==0 and item.pz or item.pz..'_'..copy end
            Magazine.RegisterWeaponPartWithProfile(profile,types)
        end
        -- Gunworks rate control (RealAuto/RealBurst) only for automatic weapons;
        -- semi-automatic and bolt-action weapons keep the vanilla trigger.
        local automatic,burst=false,false
        for _,mode in ipairs(weapon.fireModes) do
            if mode=='fullauto' then automatic=true elseif mode=='burst' then burst=true end
        end
        if automatic or burst then
            local profile={rpm=weapon.rpm}
            if burst then profile.burstCount=weapon.burstCount or 3 end
            Rate.RegisterWeapon(weapon.pz,profile)
        end
        summary[#summary+1]=key..' '..#magazines..' magazines '..families[ammoFamily]..' ammo'..((automatic or burst) and (' '..weapon.rpm..' rpm') or ' semi')
    end
    registered=true
    table.sort(summary)
    print('[TW] Registered weapons: '..table.concat(summary,', '))
end
Events.OnGameBoot.Add(register)
Events.OnGameStart.Add(register)

-- Magazine weapons without a Tarkov chamber (PPSh-41): vanilla ISInsertMagazine
-- queues a rack after every insertion, and the chamberless branch of
-- ISRackFirearm.rackBullet throws a live round out of the magazine. For the mod's
-- weapons the rack only clears a jam (the Brita B42 port's Chambering fix);
-- revolvers and other weapons keep the original action.
local function installChamberless()
    if TWChamberlessInstalled or not ISRackFirearm then return end
    TWChamberlessInstalled=true
    require('WeaponSystems/Hooks/CustomSystemHooks')
    local rack=ISRackFirearm.rackBullet
    ISRackFirearm.rackBullet=function(self)
        local gun=self.gun
        local key=gun and TWRuntime.key(gun)
        if key and not gun:haveChamber() and #D.weapons[key].magazines>0 then
            if not isClient() then
                gun:setJammed(false)
                if syncHandWeaponFields then syncHandWeaponFields(self.character,gun) end
            end
            return
        end
        return rack(self)
    end
end
Events.OnGameBoot.Add(installChamberless)
