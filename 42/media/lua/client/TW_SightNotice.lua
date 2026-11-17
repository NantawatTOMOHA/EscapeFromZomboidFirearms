-- Weapon notices by topic: zero, sight, optic mode, fire mode, underbarrel, magazine check, chamber...
-- The next notice of a topic replaces the text of that topic at once, without a queue; other topics
-- stay (FB4, the user's item 22, 19.10.2026: fast zero or fire mode changes must show the last value).
-- In Viewpoint's first-person view the character's halo note is drawn right at the camera, over the
-- sight: there the notices are short lines near the bottom of the screen. Every other view shows
-- them in the character's halo note, the current text of each topic in one line.
local N={}
local SHOW_MS,FADE_MS,LINES=2500,500,4
local HALO_TIME=250
-- Gunworks says the fire mode it applied as the vanilla "Change Fire Mode" text, a colon and the
-- mode, and the underbarrel swap with its two literal lines (a key without translation is the text).
local FIRE_MODE_KEY='ContextMenu_ChangeFireMode'
local UNDERBARREL_LINES={'Using underbarrel weapon','Using main weapon'}
local screen,owner={},nil
local halos=setmetatable({},{__mode='k'})
N.SHOW_MS,N.LINES=SHOW_MS,LINES
N.shown=0

function N.active(player)
    local zb=rawget(_G,'TWZB')
    if not player or type(zb)~='table' or not zb.vpState then return false end
    local ok,active=pcall(function()
        return player:isLocalPlayer() and player:getPlayerNum()==0 and zb.vpState()==1
    end)
    return ok and active==true
end

function N.clear()
    screen,owner={},nil
end

local function translated(key)
    local ok,text=pcall(getText,key)
    return ok and type(text)=='string' and text~='' and text or nil
end

-- Gunworks' words in the current language, read once (the first notice comes after the game start).
local words
local function gunworksWords()
    if not words then
        words={prefix=translated(FIRE_MODE_KEY),underbarrel={}}
        for _,line in ipairs(UNDERBARREL_LINES) do words.underbarrel[translated(line) or line]=true end
    end
    return words
end

-- The topic of an untagged text (speech, TW_FpSpeech): Gunworks' fire mode and underbarrel lines
-- are topics of their own; any other line is its own topic, a repeated line only refreshes it.
function N.topicOf(message)
    local w=gunworksWords()
    if w.prefix and message:sub(1,#w.prefix+1)==w.prefix..':' then return 'firemode' end
    if w.underbarrel[message] then return 'underbarrel' end
    return 'say:'..message
end

-- The topic's line takes the new text and a full time; a new topic is added after the others
-- (the oldest goes when there are more than LINES).
local function put(list,topic,message,now)
    for _,line in ipairs(list) do
        if line.topic==topic then line.text,line.untilMs=message,now+SHOW_MS;return line end
    end
    local line={topic=topic,text=message,untilMs=now+SHOW_MS}
    list[#list+1]=line
    while #list>LINES do table.remove(list,1) end
    return line
end

local function expire(list,now)
    for i=#list,1,-1 do if list[i].untilMs<=now then table.remove(list,i) end end
end

local function haloText(list)
    local parts={}
    for _,line in ipairs(list) do parts[#parts+1]=line.text end
    return table.concat(parts,', ')
end

-- The halo note of an isometric or third-person character: one line with every current topic.
local function showHalo(player,message,topic,now)
    if not player.setHaloNote or not player:isLocalPlayer() then return false end
    local list=halos[player] or {}
    halos[player]=list
    expire(list,now)
    put(list,topic,message,now)
    player:setHaloNote(haloText(list),220,220,220,HALO_TIME)
    N.lastHalo=haloText(list)
    return true
end

-- topic: nil for untagged text (speech), see N.topicOf. True when the notice is shown.
function N.show(player,message,topic)
    if not player or message==nil or tostring(message)=='' then return false end
    message=tostring(message)
    topic=topic or N.topicOf(message)
    local now=getTimestampMs()
    N.shown=N.shown+1
    N.last={topic=topic,text=message,ms=now}
    if not N.active(player) then
        local ok,shown=pcall(showHalo,player,message,topic,now)
        return ok and shown==true
    end
    if owner~=player then N.clear();owner=player end
    expire(screen,now)
    put(screen,topic,message,now)
    return true
end

-- The most recent line and how many others are shown (tests and QA).
function N.info()
    local latest
    for _,line in ipairs(screen) do if not latest or line.untilMs>=latest.untilMs then latest=line end end
    return latest and latest.text or nil,math.max(0,#screen-1)
end

function N.lines()
    local out={}
    for i,line in ipairs(screen) do out[i]={topic=line.topic,text=line.text,untilMs=line.untilMs} end
    return out
end

function N.haloLines(player)
    local out={}
    for i,line in ipairs(halos[player] or {}) do out[i]={topic=line.topic,text=line.text,untilMs=line.untilMs} end
    return out
end

-- A topic of the halo note ran out while another stays: the note keeps only the current ones.
local function tick()
    local now=getTimestampMs()
    for player,list in pairs(halos) do
        local before=#list
        expire(list,now)
        if #list==0 then halos[player]=nil
        elseif #list<before then
            local latest=0
            for _,line in ipairs(list) do latest=math.max(latest,line.untilMs) end
            pcall(function() player:setHaloNote(haloText(list),220,220,220,HALO_TIME*(latest-now)/SHOW_MS) end)
            N.lastHalo=haloText(list)
        end
    end
end

-- The fire mode a switch selects, shown when the switch starts (the mode itself is applied by the
-- selector event of its animation): the same words Gunworks says when it applies the mode.
function N.fireModeText(gun,mode,rpmStage)
    local ok,text=pcall(function()
        local Modes=require('WeaponSystems/ISUI/RateOfFire_ISUI')
        local Rate=require('WeaponSystems/Utils/RateOfFire')
        mode=Modes.NormalizeFiremode(mode,gun)
        local rpm=rpmStage and Rate.GetRpmForStage and Rate.GetRpmForStage(gun,rpmStage) or nil
        local label=Modes.GetFiremodeLabel(mode,rpm)
        local prefix=gunworksWords().prefix
        return label and prefix and prefix..': '..label or nil
    end)
    return ok and text or nil
end

function N.fireMode(player,gun,mode,rpmStage)
    local text=N.fireModeText(gun,mode,rpmStage)
    return text~=nil and N.show(player,text,'firemode')
end
TWFireModeNotice=N.fireMode

-- Gunworks says the applied fire mode and the underbarrel swap over the character (speech lines that
-- stack under the previous ones). For the local player with the mod's weapon those lines are the
-- notices of their topics: each replaces the previous one at once. Other speech, other weapons and
-- other characters keep Say.
function N.hookSay()
    local classes=rawget(_G,'__classmetatables')
    local meta=classes and IsoPlayer and classes[IsoPlayer.class]
    local base=meta and meta.__index
    if type(base)~='table' or rawget(base,'TWNoticeSay') or base.Say==nil then return false end
    local say=base.Say
    rawset(base,'Say',function(self,message,...)
        if type(message)=='string' then
            local topic=N.topicOf(message)
            if topic=='firemode' or topic=='underbarrel' then
                local ok,own=pcall(function()
                    return self:isLocalPlayer() and require('TW/Runtime').visualKey(self:getPrimaryHandItem())~=nil
                end)
                if ok and own and N.show(self,message,topic) then return end
            end
        end
        return say(self,message,...)
    end)
    rawset(base,'TWNoticeSay',true)
    return true
end
N.hookSay()

local function render()
    if #screen==0 then return end
    if not N.active(owner) then N.clear();return end
    local now=getTimestampMs()
    expire(screen,now)
    if #screen==0 then return end
    local font=UIFont.Medium
    local tm=getTextManager()
    local step=tm:getFontHeight(font)+2
    local y=getCore():getScreenHeight()*0.78-(#screen-1)*step
    for _,line in ipairs(screen) do
        local x=(getCore():getScreenWidth()-tm:MeasureStringX(font,line.text))/2
        tm:DrawString(font,x,y,line.text,0.86,0.86,0.86,math.min(1,(line.untilMs-now)/FADE_MS))
        y=y+step
    end
end

Events.OnPostUIDraw.Add(render)
Events.OnTick.Add(tick)
TWSightNotice=N.show
return N
