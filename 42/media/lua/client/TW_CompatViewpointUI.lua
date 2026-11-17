-- Viewpoint - Interface (Workshop 3812616893, mod id ViewpointUI; read at 0.14.0) compatibility.
--
-- User's report of 02.11.2026 (screenshot): a slightly damaged firearm stretched the item menu of ViewpointUI's
-- INVENTORY screen past the edge of the screen; an intact one did not. ViewpointUI reads the game's context menu
-- (Util/Harvest.lua) and, for a greyed entry, writes the entry's tooltip description beside its label
-- (Harvest.labelWithReason) after taking out only <br> and line breaks. The game's tooltips are ISRichTextPanel
-- text. The repair entry of a damaged item whose spare is not at hand (ISInventoryPaneContextMenu
-- addFixerSubOption) is greyed and its tooltip is the whole recipe in markup, so the row read
--   "Repair X: 1 X (<RGB:0.35,0.65,0> Potential repair: 65% <LINE> <RGB:0,1,0> Chance of success: 100% <LINE> ...)"
-- and ViewpointUI's popup, as wide as its widest row, ran off the screen. Every EFZ firearm has such an entry
-- (TW_Fixing.txt: a donor of the same model, 148 fixings), and so have the vanilla guns and clothing patches.
--
-- The reason shown beside a greyed label becomes what its tooltip marks as missing (the <BHC> and <RED> lines,
-- ISInventoryPaneContextMenu.bhs), else the whole description, in both cases without the markup:
-- "Repair X: 1 X (X 0/1)". Labels, the enabled state and the actions stay ViewpointUI's own. Without ViewpointUI
-- nothing is installed.
local M={installed=false,cleaned=0,errors=0,lastError=''}
TWCompatViewpointUI=M

local BAD_TAGS={'<BHC>','<RED>'}

local function trim(text)
    text=string.gsub(text,'%s+',' ')
    text=string.gsub(text,'%s+,',',')
    return (string.gsub(text,'^%s*(.-)%s*$','%1'))
end

local function hasBadTag(line)
    for _,tag in ipairs(BAD_TAGS) do
        if string.find(line,tag,1,true) then return true end
    end
    return false
end

-- A tooltip description as one plain line: its missing parts if it marks any, else all of it.
function M.plainReason(text)
    if type(text)~='string' or not string.find(text,'<',1,true) then return text end
    local bad={}
    for line in string.gmatch(text..'<LINE>','(.-)<LINE>') do
        if hasBadTag(line) then bad[#bad+1]=line end
    end
    if #bad>0 then text=table.concat(bad,', ') end
    text=trim(string.gsub(text,'<[^<>]*>',' '))
    if text=='' then return nil end
    return text
end

local function harvest()
    local vpui=rawget(_G,'ViewpointUI')
    local util=type(vpui)=='table' and vpui.Util or nil
    local H=type(util)=='table' and util.Harvest or nil
    if type(H)~='table' or type(H.labelWithReason)~='function' then return nil end
    return H
end

function M.install()
    local H=harvest()
    if not H or H.twPlainReason then return M.installed end
    local original=H.labelWithReason
    H.twPlainReason=true
    H.labelWithReason=function(entry)
        if type(entry)=='table' and type(entry.reason)=='string' then
            local ok,plain=pcall(M.plainReason,entry.reason)
            if ok then
                if plain~=entry.reason then M.cleaned=M.cleaned+1 end
                entry.reason=plain
            else
                M.errors=M.errors+1;M.lastError=tostring(plain)
            end
        end
        return original(entry)
    end
    M.installed=true
    print('[TW] Viewpoint - Interface compatibility on: greyed menu entries show their reason without tooltip markup')
    return true
end

-- ViewpointUI's files may load before or after this one: at once if they are there, else when the game starts.
M.install()
Events.OnGameStart.Add(M.install)

return M
