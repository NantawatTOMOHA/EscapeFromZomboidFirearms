-- Volume of the mod's own sounds (W10, the player's item 20: an "Audio" page with sliders for gunshots,
-- reload, bolt, magazine loading and so on). One place decides what this player hears:
--
-- * every sound the mod plays is a GameSound of this client (the game caches them per client; a dedicated
--   server plays none), so the slider scales the sound itself and never a world sound radius: the radius
--   that attracts zombies (item SoundRadius/SoundVolume) stays exactly as the scripts set it, and other
--   players' clients keep their own setting;
-- * the scale is written into the clip volume of the GameSound (TWSoundVolume, SoundVolume.java): the one
--   value the engine multiplies into every playback - FMODSoundEmitter$Sound.getVolume() reads it every
--   tick - whichever emitter plays it: our own actions, another player's shot heard here, a world emitter
--   of a grenade or an impact. The engine's own per-sound volume (GameSound.setUserVolume) looks like the
--   right knob but the game never enables it (GameSound.getUserVolume returns 1.0 unless the debug switch
--   SystemDisabler.setEnableAdvancedSoundOptions is set, and nothing in the game sets it), so it changes
--   nothing a player hears;
-- * 100 percent is exactly the volume the sound scripts give the sound (the base is remembered per clip and
--   re-read after a script reload), so the page leaves today's volume untouched until a slider moves.
--
-- The category of a sound comes from its name through media/lua/shared/TW/Data/Audio.lua, generated from
-- tools/audio_categories.py - the same table that gives every sound its 3D distance in the sound scripts and
-- that tools/check_audio.py verifies over the whole catalogue.
local Data=require('TW/Data/Audio')
local A={errors=0,lastError='',applies=0,sounds=0,unclassified={},fingerprint=nil,page='EscapeFromZomboidFirearms'}
TWAudio=A

local function log(text) print('[TW-AUDIO] '..text) end

function A.safe(tag,fn,...)
    local ok,value=pcall(fn,...)
    if ok then return value end
    A.errors=A.errors+1;A.lastError=tag..': '..tostring(value)
    log(A.lastError)
end

-- The one category of a sound name, by the same rules as tools/audio_categories.py.
function A.categoryOf(name)
    if type(name)~='string' or name:sub(1,3)~='TW_' then return nil end
    for prefix,category in pairs(Data.prefix) do
        if name:sub(1,#prefix)==prefix then
            -- Tarkov's grenade prefab also carries the thrower's own draw and holster.
            if prefix=='TW_HG_' then
                local event=name:match('_([^_]+)$')
                local handling=event and Data.grenadeHandling[event]
                if handling then return handling end
            end
            return category
        end
    end
    if Data.exact[name] then return Data.exact[name] end
    local event=name:match('_([^_]+)$')
    if name:sub(1,11)=='TW_Utility_' then return event and Data.utility[event] or nil end
    return event and Data.suffix[event] or nil
end

function A.rows()
    return Data.categories
end

-- The slider of a category, in percent; the default of the table when the options page is not built
-- yet (main menu, single player without the options screen opened).
function A.option(id)
    local api=PZAPI and PZAPI.ModOptions
    local page=api and api.Dict and api.Dict[A.page]
    local option=page and page.dict and page.dict[id]
    return option
end

function A.percent(category)
    local option=A.option(category.option)
    local value=option and option.getValue and option:getValue()
    return tonumber(value) or category.default
end

function A.scale(category)
    return A.percent(category)/100
end

-- Every sound of the mod this client has loaded, with its category (GameSounds holds them by category).
function A.inventory()
    local out={}
    if not GameSounds or not GameSounds.getCategories then return out end
    local categories=GameSounds.getCategories()
    for i=0,categories:size()-1 do
        local list=GameSounds.getSoundsInCategory(categories:get(i))
        for j=0,(list and list:size() or 0)-1 do
            local sound=list:get(j)
            local name=sound and sound.getName and sound:getName()
            if type(name)=='string' and name:sub(1,3)=='TW_' then
                local category=A.categoryOf(name)
                if category then out[#out+1]={name=name,category=category,sound=sound}
                else A.unclassified[name]=true end
            end
        end
    end
    return out
end

-- Applies every slider to every sound of the mod. The engine value is idempotent (TWSoundVolume keeps the
-- script volume of each clip), so this is also the periodic refresh that picks up sounds loaded later and the
-- new clips of a sound script reload.
function A.apply(reason)
    local native=rawget(_G,'TWSoundVolume')
    if not native or not native.set then
        A.errors=A.errors+1;A.lastError='no TWSoundVolume (Java part not loaded)'
        return 0
    end
    local scales={}
    for _,spec in ipairs(Data.categories) do scales[spec.id]=A.scale(spec) end
    local sounds,scaled=0,0
    if native.applyAll and GameSounds and GameSounds.getCategories then
        -- The complete discovery/set pass stays on the Java side of one call. Every refresh still
        -- sees every loaded category, including sounds and clips added by a script reload.
        -- Forward pairs order explicitly: overlapping prefix rules keep Lua's exact priority.
        local prefixes={}
        for prefix,category in pairs(Data.prefix) do prefixes[#prefixes+1]={prefix,category} end
        local result=native.applyAll(Data,scales,prefixes)
        sounds,scaled=result.sounds,result.scaled
        for _,name in ipairs(result.unclassified) do A.unclassified[name]=true end
    else
        local inventory=A.inventory()
        sounds=#inventory
        for _,row in ipairs(inventory) do
            local ok,clips=pcall(native.set,row.name,scales[row.category] or 1)
            if ok and type(clips)=='number' and clips>0 then scaled=scaled+1 end
        end
    end
    A.sounds=sounds;A.scaled=scaled;A.applies=A.applies+1
    A.lastApplyMs=getTimestampMs and getTimestampMs() or 0
    A.fingerprint=A.fingerprintOf()
    if reason then A.lastReason=reason end
    return scaled
end

-- The saved values of the sliders (ModOptions.ini, written by the vanilla options screen). The vanilla loader
-- runs when the options screen is built, so a player who set a slider and starts the game without opening the
-- options would otherwise hear the default until then: the module reads its own ten lines at boot.
function A.readSaved()
    local saved={}
    pcall(function()
        local file=getFileReader('ModOptions.ini',true)
        if not file then return end
        while true do
            local line=file:readLine()
            if line==nil then break end
            local kind,pageId,id,value=string.match(line,'^([^|]*)|([^|]*)|([^|]*)|(.*)$')
            if kind=='slider' and id and tonumber(value) then saved[id]=tonumber(value) end
        end
        file:close()
    end)
    return saved
end

function A.loadSaved()
    local saved=A.readSaved()
    local loaded=0
    for _,spec in ipairs(Data.categories) do
        local option=A.option(spec.option)
        if option and saved[spec.option]~=nil then option:setValue(saved[spec.option]);loaded=loaded+1 end
    end
    A.loaded=loaded
    return loaded
end

-- Keep the same fingerprint while its values and category order are unchanged. Poll still reads
-- every live option; no page/option object is cached, so late options and page replacement are seen.
local fingerprintParts,fingerprintValues,fingerprintIds={},{},{}
local lastFingerprint
function A.fingerprintOf()
    local changed=false
    for i,spec in ipairs(Data.categories) do
        local value=A.percent(spec)
        local previous=fingerprintValues[i]
        -- The old fingerprint compared tostring values: preserve NaN, signed zero and rounding too.
        local same=value==previous and (value~=0 or tostring(value)==tostring(previous))
        if not same then same=tostring(value)==tostring(previous) end
        if fingerprintIds[i]~=spec.id or not same then
            fingerprintValues[i]=value;fingerprintIds[i]=spec.id
            fingerprintParts[i]=spec.id..'='..tostring(value)
            changed=true
        end
    end
    for i=#Data.categories+1,#fingerprintParts do
        fingerprintParts[i]=nil;fingerprintValues[i]=nil;fingerprintIds[i]=nil
        changed=true
    end
    if changed or lastFingerprint==nil then lastFingerprint=table.concat(fingerprintParts,';') end
    return lastFingerprint
end

-- The sliders apply at once, without a restart: the options screen writes its own file only on apply/close,
-- so the values are read here and compared a few times a second (ten numbers, no sound lookup unless one
-- changed). A slow refresh covers sounds that appeared after the last apply (a script reload, a world load);
-- until the sounds are loaded at all it retries twice a second.
function A.poll()
    local now=getTimestampMs and getTimestampMs() or 0
    local fingerprint=A.fingerprintOf()
    if fingerprint~=A.fingerprint then A.safe('apply',A.apply,'options') return end
    local period=(A.sounds or 0)==0 and 500 or 30000
    if now-(A.refreshAt or 0)>=period then A.refreshAt=now;A.safe('refresh',A.apply,'refresh') end
end

local function install()
    if A.installed then return end
    A.installed=true
    A.safe('saved',A.loadSaved)
    A.safe('apply',A.apply,'start')
    A.pollAt=getTimestampMs and getTimestampMs() or 0
    A.refreshAt=A.pollAt
end

Events.OnGameBoot.Add(install)
Events.OnGameStart.Add(install)
Events.OnTick.Add(function()
    if not A.installed then install();return end
    local now=getTimestampMs and getTimestampMs() or 0
    if now-(A.pollAt or 0)<250 then return end
    A.pollAt=now
    A.safe('poll',A.poll)
end)

-- QA receipts (never a player-facing text).
function A.report()
    local parts={}
    for _,spec in ipairs(Data.categories) do
        parts[#parts+1]=spec.id..'='..tostring(A.percent(spec))
    end
    local unknown=0
    for _ in pairs(A.unclassified) do unknown=unknown+1 end
    local native=rawget(_G,'TWSoundVolume')
    return string.format('applies=%d sounds=%d scaled=%d categories=%d unclassified=%d errors=%d values=%s last=%s native=%s',
        A.applies,A.sounds,A.scaled or 0,#Data.categories,unknown,A.errors,table.concat(parts,','),tostring(A.lastError),
        tostring(native and native.info and native.info()))
end

function A.unclassifiedNames()
    local names={}
    for name in pairs(A.unclassified) do names[#names+1]=name end
    table.sort(names)
    return names
end

return A
