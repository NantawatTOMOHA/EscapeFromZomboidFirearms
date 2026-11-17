-- W8 animopt (the user's item 9, 25.10.2026): "add two options to the settings: one to turn off entirely
-- all animations and hand attachments (IK) to the weapon in THIRD person, and a second one for FIRST person -
-- i.e. give the animations completely to vanilla and other mods."
--
-- Two client options, off by default (the mod animates as before):
--   Third person: vanilla animations - the isometric view and Viewpoint's third person;
--   First person: vanilla animations  - Viewpoint's first person.
-- With an option on, every animation/pose/IK layer of the mod asks TW/AnimOptOut (and the Java part,
-- AnimOptOut.java through TWZB.animOptOut) and does nothing in that view: no hand IK on the grips, no
-- stance/contact/carry nodes, no round/launcher/inspect clip variables, no first-person eye pose, recoil
-- pose or grenade pose, no hand props. The timed actions keep running with the vanilla clips, the weapon
-- stays on the vanilla hand bone and the sights/ballistics keep working. The trade-offs (the Tarkov sight
-- alignment in the first person, the mod's own hip fire, the ADS narrowing that follows our pose) are in
-- the option tooltips.
--
-- Both options are read every tick and apply at once (no restart); a value saved by the options screen is
-- also restored at the next start before the screen is opened. The state itself is TW/AnimOptOut.
require 'PZAPI/ModOptions'
require('TW_Tactical') -- creates the shared page the options are added to
local State=require('TW/AnimOptOut')
local options=PZAPI.ModOptions:getOptions('EscapeFromZomboidFirearms')

local M={applied=0,pushed=0,errors=0,lastError='',loaded=false}
TWAnimOptOut=M

local IDS={'AnimVanillaThird','AnimVanillaFirst'}
if options then
    options:addTickBox('AnimVanillaThird',getText('UI_TW_AnimVanillaThird'),false,getText('UI_TW_AnimVanillaThird_tooltip'))
    options:addTickBox('AnimVanillaFirst',getText('UI_TW_AnimVanillaFirst'),false,getText('UI_TW_AnimVanillaFirst_tooltip'))
end

function M.option(id)
    local o=options and options:getOption(id)
    return o
end

function M.value(id)
    local o=M.option(id)
    return o~=nil and o:getValue()==true
end

-- The state the layers read (TW/AnimOptOut) and the Java part's copy of it.
function M.apply()
    local third,first=M.value('AnimVanillaThird'),M.value('AnimVanillaFirst')
    if State.third==third and State.first==first then return false end
    State.set(third,first)
    M.applied=M.applied+1
    local z=rawget(_G,'TWZB')
    if type(z)=='table' and type(z.animOptOut)=='function' then
        local ok,err=pcall(z.animOptOut,third,first)
        if ok then M.pushed=M.pushed+1 else M.errors=M.errors+1;M.lastError=tostring(err) end
    end
    print(string.format('[TW-ANIM-OPTOUT] third=%s first=%s views[left alone]=%s',tostring(third),tostring(first),
        third and first and 'none' or third and 'first person' or first and 'third person' or 'none'))
    return true
end

-- A value saved by the options screen is restored before that screen is opened: the vanilla loader
-- (PZAPI.ModOptions:load) runs only when the Mods tab is built, so without this a restart forgot the
-- choice until the player opened the options. Our lines may sit under any page of the layout.
function M.loadSaved()
    if M.loaded then return 0 end
    M.loaded=true
    local saved={}
    pcall(function()
        local file=getFileReader('ModOptions.ini',true)
        if not file then return end
        while true do
            local line=file:readLine()
            if line==nil then break end
            local id,value=string.match(line,'^[^|]*|[^|]*|([^|]*)|(.*)$')
            if id and (id=='AnimVanillaThird' or id=='AnimVanillaFirst') then saved[id]=value end
        end
        file:close()
    end)
    local moved=0
    for _,id in ipairs(IDS) do
        local value=saved[id]
        local o=M.option(id)
        if value~=nil and o then
            local want=value=='true'
            if o:getValue()~=want then o:setValue(want);moved=moved+1 end
        end
    end
    if moved>0 then print('[TW-ANIM-OPTOUT] restored from ModOptions.ini: '..moved) end
    return moved
end

-- QA: set both options (the real option values, so the Mods tab and the layers agree), nil restores off.
function M.set(third,first)
    for _,row in ipairs({{'AnimVanillaThird',third},{'AnimVanillaFirst',first}}) do
        local o=M.option(row[1])
        if o then o:setValue(row[2]==true) end
    end
    M.apply()
end

function M.stats(player)
    local z=rawget(_G,'TWZB')
    local java=z and type(z.animOptOutInfo)=='function' and select(2,pcall(z.animOptOutInfo)) or 'absent'
    return string.format('%s applied=%d pushed=%d errors=%d java=%s',State.stats(player),M.applied,M.pushed,M.errors,tostring(java))
end

local function tick()
    local ok,err=pcall(M.apply)
    if not ok then
        M.errors=M.errors+1
        if M.lastError~=tostring(err) then M.lastError=tostring(err);print('[TW-ANIM-OPTOUT] off '..M.lastError) end
    end
end
Events.OnTick.Add(tick)
Events.OnGameBoot.Add(function() pcall(M.loadSaved);tick() end)
Events.OnGameStart.Add(function() pcall(M.loadSaved);tick() end)
if Events.OnMainMenuEnter then Events.OnMainMenuEnter.Add(tick) end

return M
