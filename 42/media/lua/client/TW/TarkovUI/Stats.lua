-- Weapon characteristics of the Tarkov screens, only those that act in Project Zomboid (user's task
-- of 20.10.2026; what each one drives is in docs/REPORT_RU.md, "Меню Таркова"). Tarkov-only values
-- (sighting range, balance, prices, semi fire rate, recoil angle, mastering) are not shown.
--   bars:  technical condition (the PZ item's condition: jams), weight, ergonomics (aiming time,
--          first person ADS/stamina), accuracy (PZ hit chance), vertical recoil, horizontal recoil
--          and muzzle velocity (first person only: shown while that system runs), loudness (PZ
--          sound radius);
--   texts: fire modes, calibre, rate of fire (automatic and burst weapons), effective distance
--          (PZ maximum range).
-- Values come from TW/Core for any tree, so drafts and previews read the same as the weapon.
local C=require('TW/Core')
local Groups=require('TW/TarkovUI/Groups')
local D=C.data
local S={}

-- Bar scales measured over the default presets of all 149 weapons (20.10.2026): weight to
-- 14.6 kg, vertical recoil 30..536, horizontal 150..930, hit chance 45..65 %, range 12..50, sound
-- radius to 220.
S.BARS={
    {id='condition',label='IGUI_TWK_StatCondition',icon='Stat_condition',higher=true},
    {id='weight',label='IGUI_TWK_StatWeight',icon='Stat_weight',max=15,higher=false},
    {id='ergonomics',label='IGUI_TWK_StatErgonomics',icon='Stat_ergonomics',max=100,higher=true},
    {id='accuracy',label='IGUI_TWK_StatAccuracy',icon='Stat_accuracy',max=100,higher=true},
    {id='recoilUp',label='IGUI_TWK_StatRecoilUp',icon='Stat_recoil',max=600,higher=false},
    {id='recoilBack',label='IGUI_TWK_StatRecoilBack',icon='Stat_recoil',max=1000,higher=false,firstPerson=true},
    {id='velocity',label='IGUI_TWK_StatVelocity',icon='Stat_velocity',max=1500,higher=true,firstPerson=true},
    {id='loudness',label='IGUI_TWK_StatLoudness',icon='Stat_loudness',max=240,higher=false},
}
S.TEXTS={
    {id='fireModes',label='IGUI_TWK_StatFireModes',icon='Stat_firemode'},
    {id='caliber',label='IGUI_TWK_StatCaliber',icon='Stat_caliber'},
    {id='rpm',label='IGUI_TWK_StatFireRate',icon='Stat_firerate'},
    {id='range',label='IGUI_TWK_StatRange',icon='Stat_range'},
}

-- Tarkov's recoil, handling and ballistics act only in Viewpoint's first person through the Java
-- part (FpRecoil, ShotOwner); without them the horizontal recoil and the muzzle velocity act nowhere.
function S.firstPersonActive()
    local vp=rawget(_G,'TWViewpoint')
    local recoil=rawget(_G,'TWFirstPersonRecoil')
    local zb=rawget(_G,'TWZB')
    return zb~=nil and vp~=nil and vp.enabled~=nil and vp.enabled()==true and (recoil==nil or recoil.enabled==nil or recoil.enabled()==true)
end

-- Muzzle velocity of a tree (m/s): the cartridge's initial speed from the Java ballistics resource
-- times 1 + (root + parts velocity) / 100; nil without the Java part.
local function velocity(key,tree,ammoTpl)
    local zb=rawget(_G,'TWZB')
    if not (zb and zb.muzzleVelocity) then return nil end
    local parts={}
    local paths={}
    for path in pairs(tree) do paths[#paths+1]=path end
    table.sort(paths)
    for _,path in ipairs(paths) do parts[#parts+1]=tree[path] end
    local ok,value=pcall(zb.muzzleVelocity,key,ammoTpl or '',parts)
    if ok and type(value)=='number' and value>0 then return value end
    return nil
end

-- Values of one tree. item: the PZ weapon (condition); ammoTpl: the loaded cartridge.
function S.values(key,tree,item,ammoTpl)
    local s=C.stats(key,tree)
    local p=C.pzStats(key,tree,s)
    local w=D.weapons[key]
    local v={stats=s,pz=p}
    if item and item.getCondition then v.condition,v.conditionMax=item:getCondition(),item:getConditionMax() end
    v.weight=s.weight
    v.ergonomics=s.ergonomics
    v.accuracy=p.hitChance
    v.recoilUp=s.recoilUp
    v.recoilBack=s.recoilBack
    v.velocity=velocity(key,tree,ammoTpl)
    v.loudness=p.soundRadius
    v.range=p.maxRange
    v.rpm=w.rpm
    v.fireModes=w.fireModes or {}
    v.caliber=w.caliberLabel or ''
    v.capacity=s.capacity
    return v
end

local function automatic(w)
    for _,mode in ipairs(w.fireModes or {}) do if mode=='fullauto' or mode=='burst' then return true end end
    return false
end
local function trim(value)
    local text=string.format('%.1f',value)
    return (text:gsub('%.0$',''))
end
local FORMAT={
    condition=function(v) return v.condition and tostring(v.condition)..'/'..tostring(v.conditionMax) or nil end,
    weight=function(v) return string.format('%.3f',v.weight) end,
    ergonomics=function(v) return trim(v.ergonomics) end,
    accuracy=function(v) return tostring(v.accuracy)..' %' end,
    recoilUp=function(v) return trim(v.recoilUp) end,
    recoilBack=function(v) return trim(v.recoilBack) end,
    velocity=function(v) return v.velocity and string.format('%.0f',v.velocity)..' '..getText('IGUI_TWK_UnitSpeed') or nil end,
    loudness=function(v) return tostring(v.loudness) end,
}
local function fraction(row,v)
    if row.id=='condition' then return v.condition and v.conditionMax and v.conditionMax>0 and v.condition/v.conditionMax or nil end
    local value=v[row.id]
    if type(value)~='number' then return nil end
    return math.max(0,math.min(1,value/row.max))
end
local DECIMALS={weight=3,ergonomics=1,accuracy=0,recoilUp=1,recoilBack=1,velocity=0,loudness=0,condition=0}

-- Rows for the INFO panel and the inspect window. ctx: key, tree, item (condition), ammoTpl,
-- reference (tree the bars compare with: the weapon's default preset), preview (tree of a hovered
-- part). Each bar: id, label, icon, text, fraction, base (reference fraction), better (current vs
-- reference), previewFraction, delta (signed text), improved.
function S.rows(ctx)
    local key=ctx.key
    local w=D.weapons[key]
    local fp=ctx.firstPerson
    if fp==nil then fp=S.firstPersonActive() end
    local v=S.values(key,ctx.tree,ctx.item,ctx.ammoTpl)
    local ref=ctx.reference and S.values(key,ctx.reference,ctx.item,ctx.ammoTpl) or nil
    local pre=ctx.preview and S.values(key,ctx.preview,ctx.item,ctx.ammoTpl) or nil
    local bars={}
    for _,row in ipairs(S.BARS) do
        local text=FORMAT[row.id](v)
        if text and (not row.firstPerson or fp) then
            local entry={id=row.id,label=getText(row.label),icon=row.icon,text=text,fraction=fraction(row,v) or 0}
            local base=ref and row.id~='condition' and fraction(row,ref) or nil
            if base and math.abs(base-entry.fraction)>0.0005 then
                entry.base=base
                entry.better=(entry.fraction>base)==row.higher
            end
            local after=pre and row.id~='condition' and fraction(row,pre) or nil
            if after and math.abs(after-entry.fraction)>0.0005 then
                entry.previewFraction=after
                entry.improved=(after>entry.fraction)==row.higher
                local delta=(pre[row.id] or 0)-(v[row.id] or 0)
                entry.delta=string.format('%+.'..DECIMALS[row.id]..'f',delta)
            end
            bars[#bars+1]=entry
        end
    end
    local texts={}
    local names={}
    for _,mode in ipairs(v.fireModes) do names[#names+1]=getText('IGUI_TWK_Mode_'..mode) end
    texts[#texts+1]={id='fireModes',label=getText('IGUI_TWK_StatFireModes'),icon='Stat_firemode',text=table.concat(names,', ')}
    texts[#texts+1]={id='caliber',label=getText('IGUI_TWK_StatCaliber'),icon='Stat_caliber',text=v.caliber}
    if automatic(w) and (v.rpm or 0)>0 then
        texts[#texts+1]={id='rpm',label=getText('IGUI_TWK_StatFireRate'),icon='Stat_firerate',text=tostring(v.rpm)..' '..getText('IGUI_TWK_UnitRpm')}
    end
    local range={id='range',label=getText('IGUI_TWK_StatRange'),icon='Stat_range',text=tostring(v.range)..' '..getText('IGUI_TWK_UnitMeters')}
    if pre and pre.range~=v.range then
        range.delta=string.format('%+d',pre.range-v.range)
        range.improved=pre.range>v.range
    end
    texts[#texts+1]=range
    return bars,texts,v
end

-- Stat changes a candidate part brings, as short lines for its tooltip (only acting stats).
function S.changes(key,tree,nextTree,fp)
    if fp==nil then fp=S.firstPersonActive() end
    local a,b=S.values(key,tree),S.values(key,nextTree)
    local lines={}
    for _,row in ipairs(S.BARS) do
        if row.id~='condition' and (not row.firstPerson or fp) and type(a[row.id])=='number' and type(b[row.id])=='number' then
            local delta=b[row.id]-a[row.id]
            if math.abs(delta)>=(row.id=='weight' and 0.0005 or 0.05) then
                lines[#lines+1]={label=getText(row.label),text=string.format('%+.'..DECIMALS[row.id]..'f',delta),improved=(delta>0)==row.higher}
            end
        end
    end
    if b.range~=a.range then lines[#lines+1]={label=getText('IGUI_TWK_StatRange'),text=string.format('%+d',b.range-a.range),improved=b.range>a.range} end
    if b.capacity~=a.capacity then lines[#lines+1]={label=getText('IGUI_TWK_StatCapacity'),text=tostring(b.capacity),improved=b.capacity>a.capacity} end
    return lines
end

S.className=function(key) return Groups.className(Groups.weaponClass(key)) end
return S
