-- Version check at the main menu (player request 27.10.2026, item 10; the idea is PZ_Optimization's main menu notice).
-- The mod updates in parts that can end up from different builds: the Lua and scripts of the item, mod.info, and the
-- Java part inside its JAR (ZeD's ZombieBuddy). Steam can update only some of them, a stale copy in Zomboid\mods or
-- Zomboid\Workshop can shadow the subscription, or the JAR loaded in memory can be older than the Lua. Then features
-- fail without a word. tools/version_manifest.py writes two copies of one manifest: TW_VersionManifest.lua (the build
-- the running game loaded) and TW_VersionManifest.txt (read back from disk here). At the main menu this module
-- compares both, the sentinel files' line hashes, mod.info and the loaded Java part's version, prints exactly one
-- "[TW] version check: ..." line to console.txt, and on any difference shows one small, dismissable notice in the
-- bottom middle of the menu. It never runs in game and never blocks a menu button.
if isServer() then return end

require "ISUI/ISPanel"
require "ISUI/ISButton"

local MOD='EscapeFromZomboidFirearms'
local TXT='media/lua/shared/TW_VersionManifest.txt'
local V={}
TWVersionCheck=V
V.FORMAT=1
-- The text hash of TW/ALifeCompat.lua: djb2 over the characters, every product below 2^53, so doubles keep it exact.
V.PRIME=2147483647
V.reported=0

local function warningsEnabled()
    local options=rawget(_G,'TWWarningOptions')
    return not options or options.enabled()
end

local function hashLines(reader)
    local h,lines=5381,0
    while true do
        local ok,line=pcall(function() return reader:readLine() end)
        if not ok then return nil end
        if line==nil then break end
        for i=1,#line do h=(h*33+string.byte(line,i))%V.PRIME end
        h=(h*33+10)%V.PRIME
        lines=lines+1
    end
    return h,lines
end

local function modFile(path)
    if not getModFileReader then return nil end
    local ok,reader=pcall(getModFileReader,MOD,path,false)
    if not ok or not reader then return nil end
    return reader
end

local function hashFile(path)
    local reader=modFile(path)
    if not reader then return nil end
    local ok,h,lines=pcall(hashLines,reader)
    pcall(function() reader:close() end)
    if not ok then return nil end
    return h,lines
end

local function split(text,sep)
    local out,from={},1
    while true do
        local at=string.find(text,sep,from,true)
        if not at then table.insert(out,string.sub(text,from)) return out end
        table.insert(out,string.sub(text,from,at-1))
        from=at+#sep
    end
end

-- The manifest as it is on disk now; nil when it is missing or unreadable.
local function readDiskManifest()
    local reader=modFile(TXT)
    if not reader then return nil end
    local m={files={}}
    while true do
        local ok,line=pcall(function() return reader:readLine() end)
        if not ok then break end
        if line==nil then break end
        if line~='' and string.sub(line,1,1)~='#' then
            local fields=split(line,'=')
            local key,value=fields[1],table.concat(fields,'=',2)
            if key=='file' then
                local f=split(value,'|')
                if #f>=5 then
                    table.insert(m.files,{path=f[1],bytes=tonumber(f[2]),sha256=f[3],hash=tonumber(f[4]),lines=tonumber(f[5])})
                end
            elseif key~='' then
                m[key]=value
            end
        end
    end
    pcall(function() reader:close() end)
    m.format=tonumber(m.format)
    m.jarBytes=tonumber(m.jarBytes)
    return m
end

local function versionParts(text)
    local parts,source={},tostring(text or '')
    for digits in source:gmatch('%d+') do table.insert(parts,tonumber(digits)) end
    return parts
end

-- -1 when a is older than b, 0 when equal, 1 when newer; missing numbers count as 0.
function V.compare(a,b)
    local x,y=versionParts(a),versionParts(b)
    for i=1,math.max(#x,#y) do
        local left,right=x[i] or 0,y[i] or 0
        if left~=right then return left<right and -1 or 1 end
    end
    return 0
end

-- Our own item's mods as Steam lists them for the installed Workshop item; nil without Steam or the item.
local function workshopMods(id)
    if not id or not getSteamWorkshopItemMods then return nil end
    local ok,mods=pcall(getSteamWorkshopItemMods,tostring(id))
    if not ok or not mods then return nil end
    return mods
end

local function modField(mod,name)
    if not mod or not mod[name] then return nil end
    local ok,value=pcall(mod[name],mod)
    return ok and value or nil
end

-- The Java part's own version string (TWZB.version()); a static method, so it takes no self.
local function javaBuild()
    local zb=rawget(_G,'TWZB')
    if type(zb)~='table' or not zb.version then return nil end
    local ok,value=pcall(zb.version)
    return ok and value or nil
end

-- A newer Workshop copy next to the copy that runs: the stale local copy shadowing the subscription.
local function newerWorkshopCopy(id,activeDir,activeVersion)
    local mods=workshopMods(id)
    if not mods or not mods.size then return nil end
    local best
    for i=0,mods:size()-1 do
        local mod=mods:get(i)
        local modId=modField(mod,'getId') or modField(mod,'getID')
        local dir=modField(mod,'getDir')
        if (not modId or modId==MOD) and dir~=activeDir then
            local version=modField(mod,'getModVersion')
            if version and activeVersion and V.compare(version,activeVersion)>0 then
                if not best or V.compare(version,best)>0 then best=version end
            end
        end
    end
    return best
end

local function add(result,kind,arg,arg2)
    table.insert(result.kinds,{kind=kind,arg=arg,arg2=arg2})
end

-- The whole comparison; result {ok, kinds={{kind,arg,arg2}...}, build, diskBuild, modVersion, activeVersion,
-- javaVersion, javaLoaded}.
function V.check()
    local result={kinds={}}
    local manifest=rawget(_G,'TWVersionManifest')
    if type(manifest)~='table' then
        add(result,'manifest-missing')
        return result
    end
    result.build=manifest.build
    result.modVersion=manifest.modVersion
    result.javaVersion=manifest.javaVersion
    if tonumber(manifest.format)~=V.FORMAT then add(result,'manifest-format',tostring(manifest.format)) end
    for _,entry in ipairs(manifest.files or {}) do
        local hash,lines=hashFile(entry.path)
        if not hash then
            add(result,'file-missing',entry.path)
        elseif hash~=entry.hash or (entry.lines and lines~=entry.lines) then
            add(result,'file-changed',entry.path)
        end
    end
    local disk=readDiskManifest()
    result.diskBuild=disk and disk.build or nil
    if not disk then
        add(result,'manifest-unreadable')
    elseif disk.build~=manifest.build then
        add(result,'manifest-stale',tostring(disk.build))
    else
        local same=#disk.files==#(manifest.files or {})
        for i=1,math.min(#disk.files,#(manifest.files or {})) do
            local left,right=disk.files[i],manifest.files[i]
            same=same and left.path==right.path and left.hash==right.hash and left.lines==right.lines
        end
        if not same then add(result,'manifest-stale',tostring(disk.build)) end
    end
    local okInfo,info=pcall(getModInfoByID,MOD)
    if not okInfo then info=nil end
    local active=modField(info,'getModVersion')
    result.activeVersion=active
    if active and manifest.modVersion and active~=manifest.modVersion then
        add(result,'mod-version',tostring(active),tostring(manifest.modVersion))
    end
    local java=javaBuild()
    result.javaLoaded=java
    if not java then
        add(result,'java-missing')
    elseif manifest.javaVersion and java~=manifest.javaVersion then
        add(result,'java-mismatch',tostring(java),tostring(manifest.javaVersion))
    end
    local newer=newerWorkshopCopy(manifest.workshopId,active and modField(info,'getDir'),active)
    if newer then add(result,'copy-newer',tostring(newer),tostring(active)) end
    result.ok=#result.kinds==0
    return result
end

-- Stable identity of a result: the notice is not shown again for the same state once dismissed.
function V.signature(result)
    local parts={tostring(result.build),tostring(result.diskBuild),tostring(result.activeVersion),tostring(result.javaLoaded)}
    for _,kind in ipairs(result.kinds) do
        table.insert(parts,kind.kind..':'..tostring(kind.arg)..':'..tostring(kind.arg2))
    end
    return table.concat(parts,';')
end

-- One console line per check: what the game loaded, what is on disk, and every difference.
function V.summary(result)
    local text='[TW] version check: '
    if result.ok then
        return text..'ok build='..tostring(result.build)..' mod='..tostring(result.activeVersion or result.modVersion)
            ..' java='..tostring(result.javaLoaded)
    end
    local kinds={}
    for _,kind in ipairs(result.kinds) do
        table.insert(kinds,kind.kind..(kind.arg and (':'..tostring(kind.arg)) or '')
            ..(kind.arg2 and ('/'..tostring(kind.arg2)) or ''))
    end
    return text..'MISMATCH build='..tostring(result.build)..' disk='..tostring(result.diskBuild)
        ..' mod='..tostring(result.activeVersion)..' java='..tostring(result.javaLoaded or 'absent')
        ..' kinds='..table.concat(kinds,',')
end

local function kindText(kind)
    if kind.kind=='file-changed' then return getText('UI_TW_VerKindFileChanged')..' '..tostring(kind.arg)
    elseif kind.kind=='file-missing' then return getText('UI_TW_VerKindFileMissing')..' '..tostring(kind.arg)
    elseif kind.kind=='manifest-missing' then return getText('UI_TW_VerKindManifestMissing')
    elseif kind.kind=='manifest-unreadable' then return getText('UI_TW_VerKindManifestUnreadable')
    elseif kind.kind=='manifest-stale' then return getText('UI_TW_VerKindManifestStale')
    elseif kind.kind=='manifest-format' then return getText('UI_TW_VerKindManifestFormat')
    elseif kind.kind=='mod-version' then return getText('UI_TW_VerKindModVersion')..' '..tostring(kind.arg)..' / '..tostring(kind.arg2)
    elseif kind.kind=='java-missing' then return getText('UI_TW_VerKindJavaMissing')
    elseif kind.kind=='java-mismatch' then return getText('UI_TW_VerKindJavaMismatch')..' '..tostring(kind.arg)..' / '..tostring(kind.arg2)
    elseif kind.kind=='copy-newer' then return getText('UI_TW_VerKindCopyNewer')..' '..tostring(kind.arg)..' / '..tostring(kind.arg2)
    end
    return tostring(kind.kind)
end

-- The notice's text rows: a title, what is wrong (up to three kinds), a gap and what to do, in the game's language.
function V.text(result)
    local rows={{text=getText('UI_TW_VerTitle'),font='medium'}}
    for i,kind in ipairs(result.kinds) do
        if i<=3 then table.insert(rows,{text=kindText(kind),font='small'}) end
    end
    if #result.kinds>3 then table.insert(rows,{text=getText('UI_TW_VerMore'),font='small'}) end
    table.insert(rows,{text='',font='small'})
    table.insert(rows,{text=getText('UI_TW_VerAction'),font='small',warm=true})
    table.insert(rows,{text=getText('UI_TW_WarningsHint'),font='small',warm=true})
    return rows
end

local function wrap(text,limit)
    local out,line={},''
    for word in tostring(text or ''):gmatch('%S+') do
        if line~='' and #line+1+#word>limit then table.insert(out,line) line=word
        else line=line=='' and word or (line..' '..word) end
    end
    if line~='' then table.insert(out,line) end
    return out
end

-- Show the notice for a result; false when there is nothing to show.
function V.show(result)
    if not result or result.ok then return false end
    if not warningsEnabled() then return false end
    if V.banner then V.dismiss() end
    local medium=getTextManager():getFontHeight(UIFont.Medium)
    local small=getTextManager():getFontHeight(UIFont.Small)
    local pad=14
    local screenW,screenH=getCore():getScreenWidth(),getCore():getScreenHeight()
    local width=math.min(math.floor(screenW*0.6),math.max(440,math.floor(screenW*0.34)))
    local charW=math.max(1,getTextManager():MeasureStringX(UIFont.Small,'n'))
    local lines={}
    for _,row in ipairs(V.text(result)) do
        local font=row.font=='medium' and UIFont.Medium or UIFont.Small
        if row.text=='' then
            table.insert(lines,{text='',font=font,height=math.floor(small*0.6)})
        else
            for _,piece in ipairs(wrap(row.text,math.floor((width-2*pad)/charW))) do
                table.insert(lines,{text=piece,font=font,height=getTextManager():getFontHeight(font),warm=row.warm})
            end
        end
    end
    local contentW=0
    for _,line in ipairs(lines) do contentW=math.max(contentW,getTextManager():MeasureStringX(line.font,line.text)) end
    width=math.min(screenW-40,math.max(width,contentW+2*pad))
    local buttonH=24
    local height=pad+medium+8
    for _,line in ipairs(lines) do height=height+line.height end
    height=height+pad+buttonH
    local panel=V.BANNER:new(math.floor((screenW-width)/2),math.max(10,screenH-height-math.floor(screenH*0.03)),width,height)
    panel.rows=lines
    panel.pad=pad
    panel:initialise()
    panel:addToUIManager()
    panel:setAlwaysOnTop(true)
    V.banner=panel
    V.shown=(V.shown or 0)+1
    return panel
end

function V.dismiss()
    local panel=V.banner
    V.banner=nil
    if not panel then return false end
    panel:setVisible(false)
    panel:removeFromUIManager()
    return true
end

-- Run the check, print its one console line and show or keep the notice.
function V.run()
    local result=V.check()
    V.last=result
    V.reported=(V.reported or 0)+1
    print(V.summary(result))
    local signature=V.signature(result)
    if result.ok then
        V.dismiss()
        V.dismissed=nil
    elseif not warningsEnabled() then
        V.dismiss()
    elseif V.dismissed~=signature and not V.banner then
        V.show(result)
    end
    return result
end

-- The close button: hide for this state, do not come back until the state changes.
function V.dismissNotice()
    V.dismiss()
    V.dismissed=V.last and V.signature(V.last) or nil
end

TWVersionBanner=ISPanel:derive('TWVersionBanner')
V.BANNER=TWVersionBanner

function TWVersionBanner:new(x,y,width,height)
    local o=ISPanel:new(x,y,width,height)
    setmetatable(o,self)
    self.__index=self
    o.backgroundColor={r=0.06,g=0.06,b=0.06,a=0.9}
    o.borderColor={r=0.95,g=0.72,b=0.3,a=0.85}
    o.moveWithMouse=true
    o.rows={}
    o.pad=14
    return o
end

function TWVersionBanner:createChildren()
    ISPanel.createChildren(self)
    local w,h=72,24
    self.closeButton=ISButton:new(self.width-self.pad-w,self.height-self.pad-h,w,h,getText('UI_TW_VerClose'),self,TWVersionBanner.onClose)
    self.closeButton:initialise()
    self:addChild(self.closeButton)
end

function TWVersionBanner:onClose()
    TWVersionCheck.dismissNotice()
end

function TWVersionBanner:prerender()
    ISPanel.prerender(self)
    local y=self.pad
    for index,line in ipairs(self.rows) do
        local r,g,b=1,1,1
        if index==1 then r,g,b=1,0.78,0.36 elseif line.warm then r,g,b=0.87,0.87,0.72 end
        if line.text~='' then self:drawText(line.text,self.pad,y,r,g,b,1,line.font) end
        y=y+line.height
    end
end

function TWVersionBanner:onJoypadDown(button)
    if button==Joypad.BButton then TWVersionCheck.dismissNotice() end
end

-- One front-end tick after the main menu is built, so the notice sits on top of the stock screen; never in game.
function V.menuTick()
    Events.OnFETick.Remove(V.menuTick)
    if MainScreen and MainScreen.instance and MainScreen.instance.inGame then return end
    V.run()
end

Events.OnMainMenuEnter.Add(function() Events.OnFETick.Add(V.menuTick) end)
Events.OnGameStart.Add(function() V.dismiss() end)

return V
