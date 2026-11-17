-- Tarkov's classes of weapon slots and parts for the modding screens, as Escape from Tarkov
-- decides them (ModdingScreenSlotView / ItemViewFactory, EFT 0.16):
--   slot class: a vital part (a required slot) is Master - a blue point, red while empty; a slot
--     whose name is exactly one of the gear kinds is Gear - a green square; any other slot
--     (sights, devices, muzzles, numbered mounts) is Functional - a white ball or ring. The three
--     filters of the screen show and hide these classes; Gear and Master points carry the slot's
--     name, and the leader lines take the class colour.
--   part badge: the coloured square of a part (blue generic, green gear, brown vital) with the
--     glyph of its kind.
local C=require('TW/Core')
local U=require('TW/UIHelpers')
local Theme=require('TW/TarkovUI/Theme')
local D=C.data
local G={}

local GEAR_SLOTS={mod_mount=true,mod_stock=true,mod_magazine=true,mod_barrel=true,mod_handguard=true,mod_reciever=true,mod_charge=true,
    mod_pistol_grip=true,mod_launcher=true,mod_mag_shaft=true}
-- 'vital' (Master), 'gear' or 'functional'.
function G.slotClass(slot)
    if slot.definition and slot.definition.required then return 'vital' end
    return GEAR_SLOTS[C.slot(slot.path)] and 'gear' or 'functional'
end
G.slotGroup=G.slotClass

-- Point and line colours of the classes (EFT: Functional 242,242,242; Gear 167,249,142; Master 16,93,165).
G.LINE={functional={r=242/255,g=242/255,b=242/255},gear={r=167/255,g=249/255,b=142/255},vital={r=16/255,g=93/255,b=165/255}}
G.CONFLICT={r=214/255,g=53/255,b=53/255}
function G.point(ui,class,installed,x,y,size,a)
    local name=(class=='vital' and 'Point_vital' or class=='gear' and 'Point_gear' or 'Point_functional')..(installed and '_installed' or '_empty')
    if Theme.icon(ui,name,x-size/2,y-size/2,size,size,Theme.WHITE,a) then return end
    -- Without the sprite: the same shapes drawn.
    local r=size/2
    if class=='vital' then
        if installed then Theme.pointVital(ui,x,y,r,a) else Theme.disc(ui,x,y,r,Theme.VITAL,a); Theme.disc(ui,x,y,r*0.6,G.CONFLICT,a) end
    elseif class=='gear' then
        local s=r*0.8
        Theme.fill(ui,x-s,y-s,s*2,s*2,G.LINE.gear,a)
        if not installed then Theme.fill(ui,x-s*0.55,y-s*0.55,s*1.1,s*1.1,Theme.BLACK,a) end
    elseif installed then Theme.pointFilled(ui,x,y,r*0.9,a)
    else Theme.pointEmpty(ui,x,y,r*0.85,a) end
end

-- Capital slot names of Gear and Master points, as Tarkov writes them (Tarkov locale: Magazine,
-- Barrel, Receiver, Stock, Charge, PGrip; others MOD_<NAME>). The numbered slots use their base.
local LABEL={mod_magazine='Magazine',mod_barrel='Barrel',mod_foregrip='Foregrip',mod_reciever='Receiver',mod_stock='Stock',
    mod_charge='Charge',mod_pistol_grip='PGrip',mod_mag_shaft='MagShaft',mod_handguard='Handguard',mod_gas_block='GasBlock',
    mod_mount='Mount',mod_launcher='Launcher',mod_muzzle='Muzzle',mod_scope='Scope',mod_pistolgrip='Grip',mod_sight_front='FrontSight',
    mod_sight_rear='RearSight',mod_tactical='Tactical',mod_bipod='Bipod',mod_flashlight='Light',mod_catch='Catch',mod_hammer='Hammer',
    mod_trigger='Trigger'}
function G.slotLabel(path,key)
    local base=U.slotBase(C.slot(path))
    local id=LABEL[base]
    if id then
        local value=getText('IGUI_TWK_Slot_'..id)
        if value~='IGUI_TWK_Slot_'..id then return value end
    end
    return string.upper(U.slotShort(path,key))
end
G.vitalLabel=G.slotLabel

-- Badge of a part: the class square and the glyph of the part's kind.
local BADGE_CLASS={Mount='gear',Stock='gear',Magazine='gear',CylinderMagazine='gear',SpringDrivenCylinder='gear',Charge='gear',Launcher='gear',
    Barrel='vital',Handguard='vital',PistolGrip='vital',Receiver='vital'}
local GLYPH={Mount='mount',Stock='stock',Magazine='magazine',CylinderMagazine='magazine',SpringDrivenCylinder='magazine',Charge='charge',
    Launcher='launcher',AssaultScope='sight',Collimator='sight',CompactCollimator='sight',OpticScope='sight',SpecialScope='sight',
    NightVision='sight',ThermalVision='sight',IronSight='ironsight',FlashHider='muzzle',MuzzleCombo='muzzle',Silencer='muzzle',
    Compensator='muzzle',TacticalCombo='tactical',LightLaser='lightlaser',Flashlight='flashlight',Foregrip='tactical',Bipod='bipod',
    Gasblock='gasblock',AuxiliaryMod='aux',Barrel='barrel',Handguard='handguard',Receiver='reciever',PistolGrip='pistol_grip'}
G.BADGE_FILL={generic={r=57/255,g=100/255,b=126/255},gear={r=66/255,g=104/255,b=52/255},vital={r=150/255,g=88/255,b=66/255}}
function G.partClass(tpl)
    local item=tpl and D.items[tpl]
    return item and BADGE_CLASS[item.category] or 'generic'
end
function G.partGlyph(tpl)
    local item=tpl and D.items[tpl]
    return item and GLYPH[item.category] or 'aux'
end
function G.badge(ui,tpl,x,y,size)
    if not (tpl and D.items[tpl]) then return end
    local class=G.partClass(tpl)
    if not Theme.icon(ui,'Badge_'..class,x,y,size,size*12/14,Theme.WHITE,1) then
        Theme.fill(ui,x,y,size,size*12/14,G.BADGE_FILL[class],0.95)
    end
    Theme.icon(ui,'Glyph_'..G.partGlyph(tpl),x+size*0.12,y+size*0.06,size*0.76,size*0.74,Theme.WHITE,1)
end
-- Glyphs above a slot cell: one per kind of part the slot takes (at most three).
function G.slotGlyphs(slot)
    local seen,list={},{}
    for _,tpl in ipairs(slot.definition.allowed or {}) do
        local g=G.partGlyph(tpl)
        if not seen[g] then seen[g]=true; list[#list+1]=g end
        if #list>=3 then break end
    end
    return list
end
function G.drawSlotGlyphs(ui,slot,x,y,h,c,a)
    local cx=x
    for _,g in ipairs(G.slotGlyphs(slot)) do
        local t=Theme.texture('Glyph_'..g)
        if t then
            local w=h*t:getWidthOrig()/math.max(1,t:getHeightOrig())
            ui:drawTextureScaledAspect2(t,cx,y,w,h,a or 1,c.r,c.g,c.b)
            cx=cx+w+h*0.3
        end
    end
end

-- Weapon classes as Tarkov's handbook names them (the builds screen's weapon list, the inspect
-- window's breadcrumb).
G.CLASS_ORDER={'AssaultRifle','AssaultCarbine','Smg','MachineGun','MarksmanRifle','SniperRifle','Shotgun','Pistol','Revolver','GrenadeLauncher','RocketLauncher'}
function G.weaponClass(key)
    local root=D.items[C.root(key)]
    local category=root and root.category or 'AssaultRifle'
    for _,c in ipairs(G.CLASS_ORDER) do if c==category then return c end end
    return 'AssaultRifle'
end
function G.className(class) return getText('IGUI_TWK_Class_'..class) end

return G
