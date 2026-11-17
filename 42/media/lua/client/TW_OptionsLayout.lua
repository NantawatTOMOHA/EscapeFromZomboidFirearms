-- Layout of the mod options (FB3, user's item 1, 18.10.2026: "group the settings and controls menu
-- and make it clearer"). The options stay where they are read: each module adds its own to the page
-- 'EscapeFromZomboidFirearms' (or 'EscapeFromZomboidFirearmsCarry') and reads it through that page. This module only
-- arranges them for the Mods tab of the options screen: three pages - controls, effects, Viewpoint -
-- with titles, a fixed order, tooltips on keys and lists, and a short help line where a slider has no
-- tooltip of its own (vanilla draws none for sliders).
--
-- The original pages leave PZAPI.ModOptions.Data (not drawn, not saved twice) and stay in
-- PZAPI.ModOptions.Dict: getOptions('EscapeFromZomboidFirearms') and its getOption lookups keep working, and
-- the lines saved under the old page ids before this layout are still read by the vanilla loader.
-- The new pages share one option table, so a line saved under any of them restores its option.
--
-- Key defaults changed with this layout (FB3, user's item 2: some defaults were taken by vanilla or
-- other mods). The vanilla loader prefers a saved key over a new default, and every apply of the
-- options screen saves all keys, so a key still saved at its old default is moved to the new default
-- once: when the saved file has no line of the new pages yet (it was written before this layout).
require 'PZAPI/ModOptions'

local L={arranged=0,migrated={},unplaced={},errors=0,lastError=''}
TWOptionsLayout=L

L.SOURCES={'EscapeFromZomboidFirearms','EscapeFromZomboidFirearmsCarry'}

-- Pages in order. A section is a title, an optional help line and its options; option = id, or
-- {id, tooltip} where tooltip is a translation key for a key binding or a list.
-- W11 options (the player's items 10 and 14, 29.10.2026): one topic per page - every key on the controls
-- page, then aiming and sights, weapon handling, effects, audio and compatibility with other mods. The
-- page ids of the earlier layout stay (a line saved under any of them still finds its option); the
-- Viewpoint page id now holds aiming and sights. Texts and their limits: tools/check_option_texts.py.
L.PAGES={
    {id='EscapeFromZomboidFirearmsControls',name='UI_TW_OptionsPageControls',sections={
        {title='UI_TW_OptionsWeapon',options={
            {'WeaponInspect'},{'CheckChamber'},{'MagazineCheck'},
            {'FireModeSwitch','UI_TW_FireModeSwitch_tooltip'},{'FireModeCheck'}}},
        {title='UI_TW_OptionsStance',options={
            {'ReadyToggle','UI_TW_ReadyToggle_tooltip'},{'StanceMouse','UI_TW_StanceMouse_tooltip'},
            {'StanceEnter','UI_TW_StanceEnter_tooltip'},{'StanceLeave','UI_TW_StanceLeave_tooltip'}}},
        {title='UI_TW_OptionsSights',options={
            {'HoldBreath','UI_TW_HoldBreath_tooltip'},
            {'SightMenu','UI_TW_SightMenu_tooltip'},{'CycleSight'},{'CycleOpticMode','UI_TW_CycleOpticMode_tooltip'},
            {'SightZoomModifier','UI_TW_SightZoomModifier_tooltip'},
            {'ReticleBrightness','UI_TW_ReticleBrightness_tooltip'},
            {'SightZeroIncrease','UI_TW_SightZero_tooltip'},{'SightZeroDecrease','UI_TW_SightZero_tooltip'}}},
        {title='UI_TW_OptionsDevices',options={
            {'ToggleDevice','UI_TW_ToggleDevice_tooltip'},{'CycleDeviceMode','UI_TW_CycleDeviceMode_tooltip'}}},
        {help='UI_TW_OptionsKeyCombos'},
    }},
    {id='EscapeFromZomboidFirearmsViewpoint',name='UI_TW_OptionsPageAiming',sections={
        {title='UI_TW_OptionsVpAiming',options={{'AdsFromThird'},{'AdsCamera'},{'AdsNarrowIron'},{'AdsNarrowOptic'},
            {'AdsHideCrosshair'}},help='UI_TW_AdsNarrowHelp'},
        -- W10 item 9 (27.10.2026): mouse and aiming sensitivity and the per-sight sensitivity.
        {title='UI_TW_OptionsSensitivity',options={{'MouseSensitivity'},{'AimingSensitivity'},{'SightSensitivityByZoom'},
            {'SightZoomSensitivity'}},help='UI_TW_SensitivityHelp'},
        {title='UI_TW_OptionsVpScope',options={{'ScopePip'},{'ScopePicture','UI_TW_ScopePicture_tooltip'},{'ScopePipResolution'},
            {'ScopePipFrequency','UI_TW_ScopePipFrequency_tooltip'}},help='UI_TW_ScopePipResolutionHelp'},
        {title='UI_TW_OptionsVpSeeThrough',options={{'SeeThroughWeapon'},{'SeeThroughOpacity'},{'SeeThroughBlur'}},
            help='UI_TW_SeeThroughHelp'},
    }},
    {id='EscapeFromZomboidFirearmsHandling',name='UI_TW_OptionsPageHandling',sections={
        {title='UI_TW_OptionsRecoil',options={{'FpRecoil'},{'PanicSway'},{'PanicSwayStrength'},{'ArmStaminaBar'}},
            help='UI_TW_OptionsRecoilHelp'},
        {title='UI_TW_OptionsShooting',options={{'HipFire'},{'AutoRack'},{'WeaponObstacleIsometric'}}},
        {title='UI_TW_OptionsBallistics',options={{'ViewpointBallistics','UI_TW_ViewpointBallistics_tooltip'},
            {'IsometricBallistics'}}},
    }},
    {id='EscapeFromZomboidFirearmsEffects',name='UI_TW_OptionsPageEffects',sections={
        {title='UI_TW_OptionsLights',options={{'LightEffects'},{'LightFollowsWeapon'},{'LightBrightness'}}},
        {title='UI_TW_OptionsLasers',options={{'LaserIdle'},{'LaserBrightness'}},help='UI_TW_DeviceBrightnessHelp'},
        {title='UI_TW_OptionsOptics',options={{'OpticEffects'},{'ViewpointSensorHead'},
            {'ViewpointSensorDevice','UI_TW_ViewpointSensorDevice_tooltip'},
            {'ViewpointSensorHeat','UI_TW_ViewpointSensorHeat_tooltip'}}},
        {title='UI_TW_OptionsShot',options={{'ShotSmoke'},{'ShotFlash'},{'ShotSparks'},{'ShotLight'},{'BarrelHeat'}}},
        {title='UI_TW_OptionsImpacts',options={{'ImpactParticles'},{'ImpactDecals'}}},
    }},
    -- W10 (the player's items 20 + 21): the volume of the mod's own sounds, one slider per sound class
    -- (TW_AudioOptions adds them, TW_Audio scales only what this player hears).
    {id='EscapeFromZomboidFirearmsAudio',name='UI_TW_OptionsPageAudio',sections={
        {title='UI_TW_OptionsAudioShots',options={{'AudioShots'},{'AudioSuppressed'}}},
        {title='UI_TW_OptionsAudioWeapon',options={{'AudioReload'},{'AudioBolt'},{'AudioMagLoad'},{'AudioHandling'}}},
        {title='UI_TW_OptionsAudioWorld',options={{'AudioDevices'},{'AudioCasings'},{'AudioExplosions'},
            {'AudioImpacts'}}},
        {help='UI_TW_OptionsAudioHelp'},
    }},
    -- Viewpoint and Shoot to Thrill, and the weapon animations given back to vanilla and other animation
    -- mods (W8 animopt, the user's item 9).
    {id='EscapeFromZomboidFirearmsCompat',name='UI_TW_OptionsPageCompat',sections={
        {title='UI_TW_OptionsVpSupport',options={{'ViewpointSupport'},{'ViewpointGiveToStt'}}},
        {title='UI_TW_OptionsAnimations',options={{'ThirdPersonStances'},{'AnimVanillaThird'},{'AnimVanillaFirst'}}},
        {title='UI_TW_OptionsDiagnostics',options={{'ShowWarnings'},{'ViewpointPerf'}}},
    }},
}
-- Options a module adds after this layout was written land here, on the effects page.
L.FALLBACK_PAGE='EscapeFromZomboidFirearmsEffects'

-- Key defaults up to 1.0.9 (before FB3) and the default of each key now (as its module adds it).
L.OLD_KEYS={
    ToggleDevice=Keyboard.KEY_L,SightMenu=Keyboard.KEY_K,ReadyToggle=Keyboard.KEY_U,
    WeaponInspect=Keyboard.KEY_SEMICOLON,CheckChamber=Keyboard.KEY_APOSTROPHE,MagazineCheck=Keyboard.KEY_COMMA,
    FireModeSwitch=Keyboard.KEY_BACKSLASH,HoldBreath=Keyboard.KEY_CAPITAL,SightZoomModifier=Keyboard.KEY_LMENU,
    SightZeroIncrease=Keyboard.KEY_PRIOR,SightZeroDecrease=Keyboard.KEY_NEXT,
}

local function source(id)
    for _,pageId in ipairs(L.SOURCES) do
        local page=PZAPI.ModOptions.Dict[pageId]
        local option=page and page.dict and page.dict[id]
        if option then return option,page end
    end
end

local function late()
    -- An option a module adds only on first use (TW/WeaponObstacle) is added before arranging.
    local obstacle=rawget(_G,'TWObstacle')
    if obstacle and obstacle.bindOptions then pcall(obstacle.bindOptions) end
end

local function page(spec)
    local options=L.pages and L.pages[spec.id]
    if not options then
        options=PZAPI.ModOptions.Options:new(spec.id,spec.name)
        L.pages=L.pages or {}
        L.pages[spec.id]=options
    end
    options.data={}
    return options
end

-- Builds the pages from the options the modules added; safe to run again (the options screen of
-- the main menu and of a loaded game each build the Mods tab).
function L.arrange()
    local api=PZAPI and PZAPI.ModOptions
    if not (api and api.Dict and api.Data and api.Options) then return false end
    if not api.Dict[L.SOURCES[1]] then return false end
    late()
    local shared={}
    local placed={}
    L.order={}
    for _,spec in ipairs(L.PAGES) do
        local options=page(spec)
        options.dict=shared
        for _,section in ipairs(spec.sections) do
            local first=nil
            for _,row in ipairs(section.options or {}) do
                local option=source(row[1])
                if option then
                    if section.title and not first then options:addTitle(section.title);first=true end
                    if row[2] then option.tooltip=row[2] end
                    table.insert(options.data,option)
                    shared[row[1]]=option
                    placed[row[1]]=true
                    L.order[#L.order+1]=row[1]
                end
            end
            if section.help and (first or not section.options) then options:addDescription(section.help) end
        end
    end
    -- Anything a module added that the layout does not name yet: shown and saved, not lost.
    L.unplaced={}
    local fallback=L.pages[L.FALLBACK_PAGE]
    for _,pageId in ipairs(L.SOURCES) do
        local old=api.Dict[pageId]
        for _,entry in ipairs(old and old.data or {}) do
            if entry.id and entry.getValue and not placed[entry.id] then
                table.insert(fallback.data,entry)
                shared[entry.id]=entry
                placed[entry.id]=true
                L.unplaced[#L.unplaced+1]=entry.id
            end
        end
    end
    if #L.unplaced>0 then print('[TW-OPTIONS] not in the layout yet: '..table.concat(L.unplaced,', ')) end
    -- The new pages replace the old ones where the first of them stood; other mods keep their places.
    local data={}
    local at=nil
    local mine={}
    for _,spec in ipairs(L.PAGES) do mine[spec.id]=true end
    mine.EscapeFromZomboidFirearmsGamepad=true
    for _,options in ipairs(api.Data) do
        local id=options.modOptionsID
        if id==L.SOURCES[1] or id==L.SOURCES[2] or mine[id] then
            if not at then at=#data+1 end
        else
            data[#data+1]=options
        end
    end
    at=at or #data+1
    for i=#L.PAGES,1,-1 do
        local spec=L.PAGES[i]
        table.insert(data,at,L.pages[spec.id])
        api.Dict[spec.id]=L.pages[spec.id]
    end
    local gamepad=api.Dict.EscapeFromZomboidFirearmsGamepad
    if gamepad then
        local controls=at
        for i=#data,1,-1 do
            if data[i].modOptionsID==gamepad.modOptionsID then table.remove(data,i)
            elseif data[i].modOptionsID=='EscapeFromZomboidFirearmsControls' then controls=i end
        end
        table.insert(data,controls+1,gamepad)
    end
    for i=#api.Data,1,-1 do api.Data[i]=nil end
    for i,options in ipairs(data) do api.Data[i]=options end
    L.arranged=L.arranged+1
    return true
end

-- Options retired by this layout whose saved value carries over once: the single 0..1 share of the
-- ADS narrowing became two sliders in percent (FB3, user's item 3).
L.RETIRED={
    {page='EscapeFromZomboidFirearms',id='AdsCameraNarrowing',to={'AdsNarrowIron','AdsNarrowOptic'},scale=100,step=5},
}

-- Options whose scale changed in W11 (item 16): AdsNarrowIron widened from 15 deg (default 25 %) to
-- 50 deg (default 30 % = 15 deg). An untouched old default (25) becomes 30; any other old value v
-- keeps its visual effect (v * 15 / 50 rounded to step 5). Tagged in ModOptions.ini on save so a
-- deliberate choice on the new scale is never remapped a second time.
L.RESCALED={
    {id='AdsNarrowIron',pages={'EscapeFromZomboidFirearmsViewpoint','EscapeFromZomboidFirearms'},
     oldDefault=25,newDefault=30,oldDelta=15,newDelta=50,step=5,tag='w11'},
}

-- ModOptions.ini as read by the vanilla loader: saved[page][id]=value, and whether any line belongs
-- to the new pages (the file was written after this layout).
function L.readSaved()
    local saved,withLayout,tags={},false,{}
    local mine={}
    for _,spec in ipairs(L.PAGES) do mine[spec.id]=true end
    pcall(function()
        local file=getFileReader('ModOptions.ini',true)
        if not file then return end
        while true do
            local line=file:readLine()
            if line==nil then break end
            local _,pageId,id,value,tag=string.match(line,'^([^|]*)|([^|]*)|([^|]*)|([^|]*)|?(.*)$')
            if pageId then
                if mine[pageId] then withLayout=true end
                saved[pageId]=saved[pageId] or {}
                saved[pageId][id]=value
                if tag and tag~='' then
                    tags[pageId]=tags[pageId] or {}
                    tags[pageId][id]=tag
                end
            end
        end
        file:close()
    end)
    return saved,withLayout,tags
end

-- Before the first save with this layout: a key still at its old default takes its new default,
-- and a retired option's value moves to its successors (see above). Runs after the vanilla loader.
-- A saved line of our pages that names no option any more (a retired one) would stay among the
-- vanilla loader's unknown lines and be written back on every save: it is dropped after loading.
function L.clean()
    local other=PZAPI.ModOptions.OtherOptions
    if type(other)~='table' then return 0 end
    local ours={}
    for _,id in ipairs(L.SOURCES) do ours[id]=true end
    for _,spec in ipairs(L.PAGES) do ours[spec.id]=true end
    local dropped=0
    for i=#other,1,-1 do
        local pageId=string.match(tostring(other[i]),'^[^|]*|([^|]*)|')
        if pageId and ours[pageId] then table.remove(other,i);dropped=dropped+1 end
    end
    return dropped
end

function L.migrate()
    local saved,withLayout,tags=L.readSaved()
    L.withLayout=withLayout
    L.clean()
    local moved=0
    L.migrated={}
    if not withLayout then
        for id,old in pairs(L.OLD_KEYS) do
            local option=source(id)
            if option and option.type=='keybind' and option.defaultkey~=old and tonumber(option.key)==old then
                option.key=option.defaultkey
                moved=moved+1
                L.migrated[#L.migrated+1]=id
            end
        end
        for _,row in ipairs(L.RETIRED) do
            local value=tonumber(saved[row.page] and saved[row.page][row.id])
            if value then
                value=math.floor(value*row.scale/row.step+.5)*row.step
                for _,id in ipairs(row.to) do
                    local option=source(id)
                    if option and option.type=='slider' then
                        option.value=math.max(option.min,math.min(option.max,value))
                        moved=moved+1
                        L.migrated[#L.migrated+1]=id
                    end
                end
            end
        end
    end
    for _,row in ipairs(L.RESCALED) do
        local raw,tag
        for _,p in ipairs(row.pages) do
            if saved[p] and saved[p][row.id]~=nil then
                raw=saved[p][row.id]
                tag=tags and tags[p] and tags[p][row.id]
                break
            end
        end
        local value=tonumber(raw)
        if value~=nil and tag~=row.tag then
            local mapped=value==row.oldDefault and row.newDefault
                or math.floor(value*row.oldDelta/row.newDelta/row.step+.5)*row.step
            local option=source(row.id)
            if option and option.type=='slider' then
                option.value=math.max(option.min,math.min(option.max,mapped))
                moved=moved+1
                L.migrated[#L.migrated+1]=row.id
            end
        end
    end
    if moved>0 then print('[TW-OPTIONS] carried over to this layout: '..table.concat(L.migrated,', ')) end
    return moved
end

function L.tagSaved()
    local file=getFileReader('ModOptions.ini',false)
    if not file then return end
    local byId={}
    for _,row in ipairs(L.RESCALED) do byId[row.id]=row.tag end
    local lines,changed={},false
    while true do
        local line=file:readLine()
        if line==nil then break end
        local kind,pageId,id,value,tag=string.match(line,'^([^|]*)|([^|]*)|([^|]*)|([^|]*)|?(.*)$')
        local wanted=id and byId[id]
        if wanted and (not tag or tag=='') then
            line=kind..'|'..pageId..'|'..id..'|'..value..'|'..wanted
            changed=true
        end
        lines[#lines+1]=line
    end
    file:close()
    if not changed then return end
    local out=getFileWriter('ModOptions.ini',true,false)
    if not out then return end
    for _,line in ipairs(lines) do out:write(line..'\r\n') end
    out:close()
end

-- Since 42.21 getText formats every text, and a text that is no translation key with a bare '%'
-- ("Trip reduction (%)": a label another mod translated itself) throws out of the game's own error
-- report. The Mods tab passes every label and tooltip through getText, so one such option of any mod
-- stopped the whole tab (player report 03.11.2026, Visible Vehicle Coupling - Viewpoint Addon; the
-- error named every wrapper on the way, ours included). Such a text gets its percents doubled, which
-- getText shows as one '%'. A translation key is left alone: its bad text only logs a warning.
function L.safeText(text)
    if type(text)=='string' and string.find(text,'%',1,true) and getTextOrNull and getTextOrNull(text)==nil then
        return (string.gsub(text,'%%%%?','%%%%'))
    end
    return text
end

function L.safeGetText(text,...)
    if select('#',...)==0 then text=L.safeText(text) end
    return L.getText(text,...)
end

-- Vanilla shows no tooltip for a list or a slider of a mod; a list shows it this way.
function L.decorate()
    for _,spec in ipairs(L.PAGES) do
        for _,option in ipairs(L.pages and L.pages[spec.id] and L.pages[spec.id].data or {}) do
            if option.type=='combobox' and option.tooltip and option.element then
                option.element.tooltip={defaultTooltip=getText(L.safeText(option.tooltip))}
            end
        end
    end
end

local function guard(name,fn)
    local ok,err=pcall(fn)
    if not ok then
        L.errors=L.errors+1;L.lastError=name..': '..tostring(err)
        print('[TW-OPTIONS] '..L.lastError)
    end
end

-- The vanilla loader (PZAPI.ModOptions:load, called when the options screen is built) and the
-- Mods tab builder are wrapped once; both stay the vanilla functions around our calls.
function L.hook()
    if PZAPI and PZAPI.ModOptions and PZAPI.ModOptions.load and not L.loadHooked then
        local load=PZAPI.ModOptions.load
        PZAPI.ModOptions.load=function(self,...)
            guard('arrange',L.arrange)
            local result=load(self,...)
            guard('migrate',L.migrate)
            return result
        end
        L.loadHooked=true
    end
    if PZAPI and PZAPI.ModOptions and PZAPI.ModOptions.save and not L.saveHooked then
        local save=PZAPI.ModOptions.save
        PZAPI.ModOptions.save=function(self,...)
            local result=save(self,...)
            guard('tagSaved',L.tagSaved)
            return result
        end
        L.saveHooked=true
    end
    local screen=rawget(_G,'MainOptions')
    if screen and screen.addModOptionsPanel and not L.panelHooked then
        local panel=screen.addModOptionsPanel
        screen.addModOptionsPanel=function(self,...)
            -- getText is L.safeGetText only while the tab is built (left over if a build threw).
            local text=rawget(_G,'getText')
            if text~=L.safeGetText then L.getText=text end
            rawset(_G,'getText',L.safeGetText)
            local result=panel(self,...)
            rawset(_G,'getText',L.getText)
            guard('decorate',L.decorate)
            return result
        end
        L.panelHooked=true
    end
end

L.hook()
Events.OnGameBoot.Add(function() L.hook();guard('arrange',L.arrange) end)

return L
